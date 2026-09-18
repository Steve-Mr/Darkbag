package top.maary.darkbag.processor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorCalibrationTest {

    // Device sample ColorMatrix1 (D65) and ColorMatrix2 (StdA / 2856K)
    private val sampleCm1 = floatArrayOf(
        0.6667938f, -0.1588898f, -0.0857391f,
        -0.5739441f,  1.3897858f,  0.1430206f,
        -0.1378784f,  0.2651520f,  0.6036224f
    )

    private val sampleCm2 = floatArrayOf(
        1.5314636f, -0.4696045f, -0.2150574f,
        -0.4762268f,  1.4453278f,  0.0066986f,
        -0.0717468f,  0.2387238f,  0.2329559f
    )

    // Typical camera ForwardMatrix1 (D65) and ForwardMatrix2 (StdA / 2856K)
    // Row sums map white [1, 1, 1] to D50 XYZ [0.9642, 1.0000, 0.8249]
    private val sampleFm1 = floatArrayOf(
        0.6731415f,  0.1950378f,  0.0960236f, // sum = 0.9642
        0.2761841f,  0.8182068f, -0.0944061f, // sum = 1.0000
        0.0216522f, -0.2324524f,  1.0360107f  // sum = 0.8249
    )

    private val sampleFm2 = floatArrayOf(
        0.5744934f,  0.1840057f,  0.2057037f, // sum = 0.9642
        0.1938171f,  0.7453766f,  0.0607910f, // sum = 1.0000
        -0.0144958f, -0.5286865f,  1.3684082f  // sum = 0.8249
    )

    private fun multiplyMatrix3x3WithVec3(mat: FloatArray, vec: FloatArray): FloatArray {
        val out = FloatArray(3)
        for (r in 0 until 3) {
            out[r] = mat[r * 3 + 0] * vec[0] + mat[r * 3 + 1] * vec[1] + mat[r * 3 + 2] * vec[2]
        }
        return out
    }

    @Test
    fun testD65Convergence() {
        // D65 neutral point: CM1 * XYZ(D65) normalized to Y=1
        val neutralD65 = floatArrayOf(0.3815246f, 1.0f, 0.7913494f)
        val (cct, weight) = SensorCalibrationHelper.estimateCctAndWeight(
            colorMatrix1 = sampleCm1,
            colorMatrix2 = sampleCm2,
            calibrationIlluminant1 = 21,
            calibrationIlluminant2 = 17,
            neutralColorPoint = neutralD65
        )
        assertEquals("D65 neutral point must converge to weight ~ 1.0", 1.0f, weight, 0.01f)
        assertEquals("D65 neutral point must converge to CCT ~ 6504K", 6504f, cct, 50f)
    }

    @Test
    fun testStandardAConvergence() {
        // Standard Light A neutral point: CM2 * XYZ(A) normalized to Y=1
        val neutralA = floatArrayOf(1.2288657f, 1.0f, 0.2626146f)
        val (cct, weight) = SensorCalibrationHelper.estimateCctAndWeight(
            colorMatrix1 = sampleCm1,
            colorMatrix2 = sampleCm2,
            calibrationIlluminant1 = 21,
            calibrationIlluminant2 = 17,
            neutralColorPoint = neutralA
        )
        assertEquals("Standard A neutral point must converge to weight ~ 0.0", 0.0f, weight, 0.01f)
        assertEquals("Standard A neutral point must converge to CCT ~ 2856K", 2856f, cct, 50f)
    }

    @Test
    fun testWhiteWallSampleConvergence() {
        // Real-device white wall sample ASN
        val whiteWallAsn = floatArrayOf(0.44061962f, 1.0f, 0.64483625f)
        val (cct, weight) = SensorCalibrationHelper.estimateCctAndWeight(
            colorMatrix1 = sampleCm1,
            colorMatrix2 = sampleCm2,
            calibrationIlluminant1 = 21,
            calibrationIlluminant2 = 17,
            neutralColorPoint = whiteWallAsn
        )
        assertEquals("White wall sample must converge to weight 0.872f ± 0.01f", 0.872f, weight, 0.01f)
        assertEquals("White wall sample must converge to CCT 5592K ± 50K", 5592f, cct, 50f)
    }

    @Test
    fun testNeutralGrayPreservation_WithColorMatrices() {
        val whiteWallAsn = floatArrayOf(0.44061962f, 1.0f, 0.64483625f)
        val renderCcm = SensorCalibrationHelper.computeRenderCcm(
            forwardMatrix1 = sampleFm1,
            forwardMatrix2 = sampleFm2,
            calibrationIlluminant1 = 21,
            calibrationIlluminant2 = 17,
            neutralColorPoint = whiteWallAsn,
            colorMatrix1 = sampleCm1,
            colorMatrix2 = sampleCm2
        )
        assertNotNull("renderCcm must not be null", renderCcm)

        val neutralInput = floatArrayOf(1.0f, 1.0f, 1.0f)
        val neutralOutput = multiplyMatrix3x3WithVec3(renderCcm!!, neutralInput)

        assertEquals("Red channel must preserve neutral gray within 0.005", 1.0f, neutralOutput[0], 0.005f)
        assertEquals("Green channel must preserve neutral gray within 0.005", 1.0f, neutralOutput[1], 0.005f)
        assertEquals("Blue channel must preserve neutral gray within 0.005", 1.0f, neutralOutput[2], 0.005f)
    }

    @Test
    fun testFallbackWhenColorMatrixIsNull() {
        val whiteWallAsn = floatArrayOf(0.44061962f, 1.0f, 0.64483625f)
        val (cct, weight) = SensorCalibrationHelper.estimateCctAndWeight(
            colorMatrix1 = null,
            colorMatrix2 = null,
            calibrationIlluminant1 = 21,
            calibrationIlluminant2 = 17,
            neutralColorPoint = whiteWallAsn
        )
        assertTrue("Fallback CCT must be positive and bounded", cct in 2000f..10000f)
        assertTrue("Fallback weight must be in [0, 1]", weight in 0f..1f)

        val renderCcm = SensorCalibrationHelper.computeRenderCcm(
            forwardMatrix1 = sampleFm1,
            forwardMatrix2 = sampleFm2,
            calibrationIlluminant1 = 21,
            calibrationIlluminant2 = 17,
            neutralColorPoint = whiteWallAsn,
            colorMatrix1 = null,
            colorMatrix2 = null
        )
        assertNotNull("renderCcm must not be null in fallback", renderCcm)

        val neutralInput = floatArrayOf(1.0f, 1.0f, 1.0f)
        val neutralOutput = multiplyMatrix3x3WithVec3(renderCcm!!, neutralInput)

        assertEquals("Red channel must preserve neutral gray in fallback within 0.005", 1.0f, neutralOutput[0], 0.005f)
        assertEquals("Green channel must preserve neutral gray in fallback within 0.005", 1.0f, neutralOutput[1], 0.005f)
        assertEquals("Blue channel must preserve neutral gray in fallback within 0.005", 1.0f, neutralOutput[2], 0.005f)
    }

    @Test
    fun testNeutralGrayPreservation_D65() {
        val neutralPoint = floatArrayOf(0.5f, 1.0f, 0.65f)
        val renderCcm = SensorCalibrationHelper.computeRenderCcm(
            forwardMatrix1 = sampleFm1,
            forwardMatrix2 = sampleFm2,
            calibrationIlluminant1 = 21,
            calibrationIlluminant2 = 17,
            neutralColorPoint = neutralPoint
        )
        assertNotNull("renderCcm must not be null when FM1 is provided", renderCcm)

        val neutralInput = floatArrayOf(1.0f, 1.0f, 1.0f)
        val neutralOutput = multiplyMatrix3x3WithVec3(renderCcm!!, neutralInput)

        assertEquals("Red channel must preserve neutral gray within 0.005", 1.0f, neutralOutput[0], 0.005f)
        assertEquals("Green channel must preserve neutral gray within 0.005", 1.0f, neutralOutput[1], 0.005f)
        assertEquals("Blue channel must preserve neutral gray within 0.005", 1.0f, neutralOutput[2], 0.005f)
    }

    @Test
    fun testNeutralGrayPreservation_TungstenA() {
        val neutralPoint = floatArrayOf(0.85f, 1.0f, 0.25f)
        val renderCcm = SensorCalibrationHelper.computeRenderCcm(
            forwardMatrix1 = sampleFm1,
            forwardMatrix2 = sampleFm2,
            calibrationIlluminant1 = 21,
            calibrationIlluminant2 = 17,
            neutralColorPoint = neutralPoint
        )
        assertNotNull("renderCcm must not be null when FM1 is provided", renderCcm)

        val neutralInput = floatArrayOf(1.0f, 1.0f, 1.0f)
        val neutralOutput = multiplyMatrix3x3WithVec3(renderCcm!!, neutralInput)

        assertEquals("Red channel must preserve neutral gray within 0.005", 1.0f, neutralOutput[0], 0.005f)
        assertEquals("Green channel must preserve neutral gray within 0.005", 1.0f, neutralOutput[1], 0.005f)
        assertEquals("Blue channel must preserve neutral gray within 0.005", 1.0f, neutralOutput[2], 0.005f)
    }

    @Test
    fun testNeutralGrayPreservation_MidTemperature() {
        val neutralPoint = floatArrayOf(0.65f, 1.0f, 0.45f)
        val renderCcm = SensorCalibrationHelper.computeRenderCcm(
            forwardMatrix1 = sampleFm1,
            forwardMatrix2 = sampleFm2,
            calibrationIlluminant1 = 21,
            calibrationIlluminant2 = 17,
            neutralColorPoint = neutralPoint
        )
        assertNotNull("renderCcm must not be null", renderCcm)

        val neutralInput = floatArrayOf(1.0f, 1.0f, 1.0f)
        val neutralOutput = multiplyMatrix3x3WithVec3(renderCcm!!, neutralInput)

        assertEquals("Red channel must preserve neutral gray within 0.005", 1.0f, neutralOutput[0], 0.005f)
        assertEquals("Green channel must preserve neutral gray within 0.005", 1.0f, neutralOutput[1], 0.005f)
        assertEquals("Blue channel must preserve neutral gray within 0.005", 1.0f, neutralOutput[2], 0.005f)
    }

    @Test
    fun testNeutralGrayPreservation_ReciprocalWbFallback() {
        val wbGains = floatArrayOf(2.0f, 1.0f, 1.0f, 1.5f)
        val renderCcm = SensorCalibrationHelper.computeRenderCcm(
            forwardMatrix1 = sampleFm1,
            forwardMatrix2 = sampleFm2,
            calibrationIlluminant1 = 21,
            calibrationIlluminant2 = 17,
            neutralColorPoint = null,
            wb = wbGains
        )
        assertNotNull("renderCcm must not be null with WB fallback", renderCcm)

        val neutralInput = floatArrayOf(1.0f, 1.0f, 1.0f)
        val neutralOutput = multiplyMatrix3x3WithVec3(renderCcm!!, neutralInput)

        assertEquals("Red channel must preserve neutral gray within 0.005", 1.0f, neutralOutput[0], 0.005f)
        assertEquals("Green channel must preserve neutral gray within 0.005", 1.0f, neutralOutput[1], 0.005f)
        assertEquals("Blue channel must preserve neutral gray within 0.005", 1.0f, neutralOutput[2], 0.005f)
    }

    @Test
    fun testNullForwardMatrix_ReturnsNull() {
        val renderCcm = SensorCalibrationHelper.computeRenderCcm(
            forwardMatrix1 = null,
            forwardMatrix2 = null
        )
        assertNull("renderCcm must be null when ForwardMatrix is not available", renderCcm)
    }
}
