package top.maary.darkbag.processor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class WhiteBalanceEngineTest {

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

    private val sampleFm1 = floatArrayOf(
        0.6731415f,  0.1950378f,  0.0960236f,
        0.2761841f,  0.8182068f, -0.0944061f,
        0.0216522f, -0.2324524f,  1.0360107f
    )

    private val sampleFm2 = floatArrayOf(
        0.5744934f,  0.1840057f,  0.2057037f,
        0.1938171f,  0.7453766f,  0.0607910f,
        -0.0144958f, -0.5286865f,  1.3684082f
    )

    @Test
    fun testCat16InversionPrecision() {
        val identity = SensorCalibrationHelper.multiply3x3(
            SensorCalibrationHelper.M_CAT16,
            SensorCalibrationHelper.M_CAT16_INV
        )
        for (r in 0 until 3) {
            for (c in 0 until 3) {
                val expected = if (r == c) 1.0f else 0.0f
                assertEquals("CAT16 product must be identity at ($r,$c)", expected, identity[r * 3 + c], 1e-6f)
            }
        }
    }

    @Test
    fun test2DRobertsonBidirectionalRoundtrip() {
        val testTemps = floatArrayOf(2200f, 2856f, 3500f, 4500f, 5500f, 6504f, 9000f)
        val testTints = floatArrayOf(-60f, -20f, 0f, 20f, 60f)

        for (temp in testTemps) {
            for (tint in testTints) {
                val (x, y) = SensorCalibrationHelper.tempTintToXy(temp, tint)
                val (roundtripTemp, roundtripTint, _) = SensorCalibrationHelper.xyToTempTint(x, y)

                val deltaT = abs(roundtripTemp - temp)
                val deltaTint = abs(roundtripTint - tint)

                assertTrue(
                    "Robertson roundtrip temperature error |$deltaT| must be < 2.0K at T=$temp, Tint=$tint (got $roundtripTemp)",
                    deltaT < 2.0f
                )
                assertTrue(
                    "Robertson roundtrip tint error |$deltaTint| must be < 0.2 at T=$temp, Tint=$tint (got $roundtripTint)",
                    deltaTint < 0.2f
                )
            }
        }
    }

    @Test
    fun testNeutralGrayConservation_ForwardMatrixPath() {
        val neutralD65 = floatArrayOf(0.3815246f, 1.0f, 0.7913494f)
        val renderCcm = SensorCalibrationHelper.computeRenderCcm(
            forwardMatrix1 = sampleFm1,
            forwardMatrix2 = sampleFm2,
            calibrationIlluminant1 = 21,
            calibrationIlluminant2 = 17,
            neutralColorPoint = neutralD65,
            colorMatrix1 = sampleCm1,
            colorMatrix2 = sampleCm2
        )
        assertNotNull(renderCcm)

        val neutralInput = floatArrayOf(1.0f, 1.0f, 1.0f)
        val neutralOutput = SensorCalibrationHelper.multiply3x3Vec3(renderCcm!!, neutralInput)

        assertEquals("Red channel must preserve neutral gray within 1e-4", 1.0f, neutralOutput[0], 0.0001f)
        assertEquals("Green channel must preserve neutral gray within 1e-4", 1.0f, neutralOutput[1], 0.0001f)
        assertEquals("Blue channel must preserve neutral gray within 1e-4", 1.0f, neutralOutput[2], 0.0001f)
    }

    @Test
    fun testNeutralGrayConservation_Cat16FallbackPath() {
        val neutralA = floatArrayOf(1.2288657f, 1.0f, 0.2626146f)
        val renderCcm = SensorCalibrationHelper.computeRenderCcm(
            forwardMatrix1 = null,
            forwardMatrix2 = null,
            calibrationIlluminant1 = 21,
            calibrationIlluminant2 = 17,
            neutralColorPoint = neutralA,
            colorMatrix1 = sampleCm1,
            colorMatrix2 = sampleCm2
        )
        assertNotNull("CAT16 fallback CCM must be generated", renderCcm)

        val neutralInput = floatArrayOf(1.0f, 1.0f, 1.0f)
        val neutralOutput = SensorCalibrationHelper.multiply3x3Vec3(renderCcm!!, neutralInput)

        assertEquals("Red channel must preserve neutral gray on CAT16 fallback within 1e-4", 1.0f, neutralOutput[0], 0.0001f)
        assertEquals("Green channel must preserve neutral gray on CAT16 fallback within 1e-4", 1.0f, neutralOutput[1], 0.0001f)
        assertEquals("Blue channel must preserve neutral gray on CAT16 fallback within 1e-4", 1.0f, neutralOutput[2], 0.0001f)
    }

    @Test
    fun testRowNormalizationHealing_PerturbedRows() {
        val perturbedFm1 = sampleFm1.copyOf().apply {
            this[0] *= 1.05f; this[1] *= 1.05f; this[2] *= 1.05f
            this[6] *= 0.95f; this[7] *= 0.95f; this[8] *= 0.95f
        }
        val renderCcm = SensorCalibrationHelper.computeRenderCcm(
            forwardMatrix1 = perturbedFm1,
            forwardMatrix2 = sampleFm2,
            calibrationIlluminant1 = 21,
            calibrationIlluminant2 = 17,
            neutralColorPoint = floatArrayOf(0.44061962f, 1.0f, 0.64483625f),
            colorMatrix1 = sampleCm1,
            colorMatrix2 = sampleCm2
        )
        assertNotNull(renderCcm)

        val neutralOutput = SensorCalibrationHelper.multiply3x3Vec3(renderCcm!!, floatArrayOf(1f, 1f, 1f))
        assertEquals("Red must heal to 1.0 within 1e-4", 1.0f, neutralOutput[0], 0.0001f)
        assertEquals("Green must heal to 1.0 within 1e-4", 1.0f, neutralOutput[1], 0.0001f)
        assertEquals("Blue must heal to 1.0 within 1e-4", 1.0f, neutralOutput[2], 0.0001f)
    }

    @Test
    fun testCanonicalWbResolver_NeutralPointPriority() {
        val asn = floatArrayOf(0.5f, 1.0f, 0.8f)
        val halGains = floatArrayOf(2.5f, 1.2f, 1.2f, 1.4f)

        val (wb, neutral) = SensorCalibrationHelper.resolveCanonicalWbAndNeutral(asn, halGains)
        // ASN takes absolute priority
        assertEquals(2.0f, wb[0], 1e-5f)
        assertEquals(1.0f, wb[1], 1e-5f)
        assertEquals(1.0f, wb[2], 1e-5f)
        assertEquals(1.25f, wb[3], 1e-5f)

        assertEquals(0.5f, neutral[0], 1e-5f)
        assertEquals(1.0f, neutral[1], 1e-5f)
        assertEquals(0.8f, neutral[2], 1e-5f)

        // Physical identity contract: g_c * n_c == 1.0
        assertEquals(1.0f, wb[0] * neutral[0], 1e-5f)
        assertEquals(1.0f, wb[1] * neutral[1], 1e-5f)
        assertEquals(1.0f, wb[3] * neutral[2], 1e-5f)
    }

    @Test
    fun testCanonicalWbResolver_GainsFallback_NormalizesGreenTo1() {
        val halGains = floatArrayOf(4.0f, 2.0f, 2.0f, 3.0f) // G is 2.0
        val (wb, neutral) = SensorCalibrationHelper.resolveCanonicalWbAndNeutral(neutralPoint = null, colorGains = halGains)

        assertEquals(2.0f, wb[0], 1e-5f) // 4.0 / 2.0
        assertEquals(1.0f, wb[1], 1e-5f) // 2.0 / 2.0
        assertEquals(1.0f, wb[2], 1e-5f)
        assertEquals(1.5f, wb[3], 1e-5f) // 3.0 / 2.0

        assertEquals(0.5f, neutral[0], 1e-5f)
        assertEquals(1.0f, neutral[1], 1e-5f)
        assertEquals(1.0f / 1.5f, neutral[2], 1e-5f)

        // Physical identity contract
        assertEquals(1.0f, wb[0] * neutral[0], 1e-5f)
        assertEquals(1.0f, wb[3] * neutral[2], 1e-5f)
    }

    @Test
    fun testCanonicalWbResolver_SafeFallbackOnInvalidOrZero() {
        val (wb1, n1) = SensorCalibrationHelper.resolveCanonicalWbAndNeutral(neutralPoint = null, colorGains = null)
        assertEquals(2.0f, wb1[0], 1e-5f)
        assertEquals(1.0f, wb1[1], 1e-5f)
        assertEquals(0.5f, n1[0], 1e-5f)

        val invalidAsn = floatArrayOf(0f, 0f, 0f)
        val (wb2, n2) = SensorCalibrationHelper.resolveCanonicalWbAndNeutral(neutralPoint = invalidAsn, colorGains = null)
        assertEquals(2.0f, wb2[0], 1e-5f)
        assertEquals(0.5f, n2[0], 1e-5f)

        val nanAsn = floatArrayOf(Float.NaN, Float.POSITIVE_INFINITY, -1f)
        val (wb3, n3) = SensorCalibrationHelper.resolveCanonicalWbAndNeutral(neutralPoint = nanAsn, colorGains = null)
        assertEquals(2.0f, wb3[0], 1e-5f)
        assertEquals(0.5f, n3[0], 1e-5f)

        val (wb4, n4) = SensorCalibrationHelper.resolveCanonicalWbAndNeutral(result = null)
        assertEquals(2.0f, wb4[0], 1e-5f)
        assertEquals(0.5f, n4[0], 1e-5f)
    }

    @Test
    fun testCrossCameraCieXyMapping() {
        // Main camera and Sub camera color matrices
        val mainNeutral = floatArrayOf(0.3815246f, 1.0f, 0.7913494f) // D65 neutral
        val subResult = SensorCalibrationHelper.mapNeutralAcrossCameras(
            mainNeutral = mainNeutral,
            mainCm1 = sampleCm1,
            mainCm2 = sampleCm2,
            mainIllum1 = 21,
            mainIllum2 = 17,
            subCm1 = sampleCm1, // identical sensor for test mapping verification
            subCm2 = sampleCm2,
            subIllum1 = 21,
            subIllum2 = 17
        )
        assertNotNull("Cross-camera mapping must succeed for valid sensor pair", subResult)
        val (subWb, subNeutral) = subResult!!

        assertEquals("Sub neutral R must match main neutral R within 1e-3", mainNeutral[0], subNeutral[0], 0.001f)
        assertEquals("Sub neutral G must be 1.0", 1.0f, subNeutral[1], 0.001f)
        assertEquals("Sub neutral B must match main neutral B within 1e-3", mainNeutral[2], subNeutral[2], 0.001f)

        // Envelope test
        assertTrue("Sub gain R must be in [0.5, 5.0]", subWb[0] in 0.5f..5.0f)
        assertTrue("Sub gain B must be in [0.5, 5.0]", subWb[3] in 0.5f..5.0f)
    }
}
