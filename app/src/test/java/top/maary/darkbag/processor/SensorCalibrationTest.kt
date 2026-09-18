package top.maary.darkbag.processor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SensorCalibrationTest {

    // Typical camera ForwardMatrix1 (D65) and ForwardMatrix2 (StdA / 2856K)
    // Row sums map white [1, 1, 1] to D50 XYZ [0.9642, 1.0000, 0.8249]
    private val sampleFm1 = floatArrayOf(
        0.7888f, 0.1288f, 0.0466f,  // sum = 0.9642
        0.2411f, 0.8061f, -0.0472f, // sum = 1.0000
       -0.0244f, -0.1208f, 0.9701f  // sum = 0.8249
    )

    private val sampleFm2 = floatArrayOf(
        0.6971f, 0.2500f, 0.0171f,  // sum = 0.9642
        0.2100f, 0.7900f, 0.0000f,  // sum = 1.0000
       -0.0100f, -0.1400f, 0.9749f  // sum = 0.8249
    )

    private fun multiplyMatrix3x3WithVec3(mat: FloatArray, vec: FloatArray): FloatArray {
        val out = FloatArray(3)
        for (r in 0 until 3) {
            out[r] = mat[r * 3 + 0] * vec[0] + mat[r * 3 + 1] * vec[1] + mat[r * 3 + 2] * vec[2]
        }
        return out
    }

    @Test
    fun testNeutralGrayPreservation_D65() {
        // Under D65 daylight, sensor measures more blue relative to red
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
        // Under warm tungsten, sensor measures much less blue relative to red
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
        // Intermediate CCT ~4500K
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
        // When neutralColorPoint is null, falls back to reciprocal WB gains
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
