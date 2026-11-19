package top.maary.darkbag.camera.multicam

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.RggbChannelVector
import android.util.Rational
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowCameraCharacteristics
import org.robolectric.shadows.ShadowTotalCaptureResult

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MultiCamColorHarmonizerTest {

    private fun createColorTransform(values: IntArray): ColorSpaceTransform {
        val rationals = Array(9) { i -> Rational(values[i], 1000) }
        return ColorSpaceTransform(rationals)
    }

    @Test
    fun testHarmonize_nullInputs() {
        assertNull(MultiCamColorHarmonizer.harmonize(null, null, null))
        val chars = ShadowCameraCharacteristics.newCameraCharacteristics()
        assertNull(MultiCamColorHarmonizer.harmonize(chars, null, chars))
    }

    @Test
    fun testHarmonize_validNeutralPoint() {
        val primChars = ShadowCameraCharacteristics.newCameraCharacteristics()
        val secChars = ShadowCameraCharacteristics.newCameraCharacteristics()

        val cmD65 = intArrayOf(
            1500, -300, -200,
            -200, 1300, -100,
            -50, -200, 1250
        )
        val cmA = intArrayOf(
            1700, -500, -200,
            -300, 1400, -100,
            -50, -400, 1450
        )

        Shadows.shadowOf(primChars).set(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1, createColorTransform(cmD65))
        Shadows.shadowOf(primChars).set(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2, createColorTransform(cmA))
        Shadows.shadowOf(primChars).set(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1, 21) // D65
        Shadows.shadowOf(primChars).set(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2, 17.toByte()) // StdA

        Shadows.shadowOf(secChars).set(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1, createColorTransform(cmD65))
        Shadows.shadowOf(secChars).set(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2, createColorTransform(cmA))
        Shadows.shadowOf(secChars).set(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1, 21)
        Shadows.shadowOf(secChars).set(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2, 17.toByte())

        val captureResult = ShadowTotalCaptureResult.newTotalCaptureResult()
        Shadows.shadowOf(captureResult).set(
            CaptureResult.SENSOR_NEUTRAL_COLOR_POINT,
            arrayOf(Rational(1, 2), Rational(1, 1), Rational(4, 5))
        )
        Shadows.shadowOf(captureResult).set(
            CaptureResult.COLOR_CORRECTION_GAINS,
            RggbChannelVector(2.0f, 1.0f, 1.0f, 1.25f)
        )

        val profile = MultiCamColorHarmonizer.harmonize(primChars, captureResult, secChars)
        assertNotNull(profile)
        profile?.let {
            assertTrue(it.cctKelvin > 1500f)
            assertTrue(it.neutralPoint[0] > 0f)
            assertEquals(1.0f, it.neutralPoint[1], 0.001f)
            assertTrue(it.neutralPoint[2] > 0f)
            assertTrue(it.gains.red in 0.5f..8.0f)
            assertTrue(it.gains.blue in 0.5f..8.0f)
            assertEquals(1.0f, it.gains.greenEven, 0.001f)
            assertEquals(1.0f, it.gains.greenOdd, 0.001f)
            assertNotNull(it.colorTransform)
        }
    }

    @Test
    fun testHarmonize_fallbackToGainsWhenNeutralPointNull() {
        val primChars = ShadowCameraCharacteristics.newCameraCharacteristics()
        val secChars = ShadowCameraCharacteristics.newCameraCharacteristics()

        val captureResult = ShadowTotalCaptureResult.newTotalCaptureResult()
        Shadows.shadowOf(captureResult).set(
            CaptureResult.COLOR_CORRECTION_GAINS,
            RggbChannelVector(2.5f, 1.0f, 1.0f, 1.5f)
        )

        val profile = MultiCamColorHarmonizer.harmonize(primChars, captureResult, secChars)
        assertNotNull(profile)
        profile?.let {
            assertEquals(0.4f, it.neutralPoint[0], 0.05f)
            assertEquals(1.0f, it.neutralPoint[1], 0.001f)
            assertEquals(0.667f, it.neutralPoint[2], 0.05f)
        }
    }

    @Test
    fun testHarmonize_reversedIlluminantsAndMissingCm2() {
        val primChars = ShadowCameraCharacteristics.newCameraCharacteristics()
        val secChars = ShadowCameraCharacteristics.newCameraCharacteristics()

        val cmD65 = intArrayOf(
            1500, -300, -200,
            -200, 1300, -100,
            -50, -200, 1250
        )
        val cmA = intArrayOf(
            1700, -500, -200,
            -300, 1400, -100,
            -50, -400, 1450
        )

        // Primary: 1=D65, 2=StdA
        Shadows.shadowOf(primChars).set(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1, createColorTransform(cmD65))
        Shadows.shadowOf(primChars).set(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2, createColorTransform(cmA))
        Shadows.shadowOf(primChars).set(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1, 21)
        Shadows.shadowOf(primChars).set(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2, 17.toByte())

        // Secondary: Reversed order 1=StdA, 2=D65, and SENSOR_COLOR_TRANSFORM2 is null (falls back to CM1)
        Shadows.shadowOf(secChars).set(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1, createColorTransform(cmA))
        Shadows.shadowOf(secChars).set(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1, 17)
        Shadows.shadowOf(secChars).set(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2, 21.toByte())

        // Forward matrix on secondary
        val fm = intArrayOf(
            700, 150, 100,
            200, 750, 50,
            50, 100, 800
        )
        Shadows.shadowOf(secChars).set(CameraCharacteristics.SENSOR_FORWARD_MATRIX1, createColorTransform(fm))

        val captureResult = ShadowTotalCaptureResult.newTotalCaptureResult()
        Shadows.shadowOf(captureResult).set(
            CaptureResult.SENSOR_NEUTRAL_COLOR_POINT,
            arrayOf(Rational(1, 2), Rational(1, 1), Rational(4, 5))
        )
        Shadows.shadowOf(captureResult).set(
            CaptureResult.COLOR_CORRECTION_GAINS,
            RggbChannelVector(2.0f, 1.0f, 1.0f, 1.25f)
        )

        val profile = MultiCamColorHarmonizer.harmonize(primChars, captureResult, secChars)
        assertNotNull(profile)
        profile?.let {
            assertTrue(it.cctKelvin > 1500f)
            assertTrue(it.neutralPoint[0] > 0f)
            assertEquals(1.0f, it.neutralPoint[1], 0.001f)
            assertTrue(it.neutralPoint[2] > 0f)
            assertNotNull(it.colorTransform)
        }
    }

    @Test
    fun testHarmonize_asymmetricMatricesWithoutForwardMatrix_preservesNeutralGrayRowSum() {
        val primChars = ShadowCameraCharacteristics.newCameraCharacteristics()
        val secChars = ShadowCameraCharacteristics.newCameraCharacteristics()

        // Realistic device ColorMatrix1 (D65) and ColorMatrix2 (StdA) from physical sensors
        val cmPrim = intArrayOf(
            667, -159, -86,
            -574, 1390, 143,
            -138, 265, 604
        )
        val cmSec = intArrayOf(
            1531, -470, -215,
            -476, 1445, 7,
            -72, 239, 233
        )

        Shadows.shadowOf(primChars).set(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1, createColorTransform(cmPrim))
        Shadows.shadowOf(primChars).set(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1, 21)

        // Secondary has NO SENSOR_FORWARD_MATRIX1, testing the invCmSec fallback path
        Shadows.shadowOf(secChars).set(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1, createColorTransform(cmSec))
        Shadows.shadowOf(secChars).set(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1, 21)

        val captureResult = ShadowTotalCaptureResult.newTotalCaptureResult()
        Shadows.shadowOf(captureResult).set(
            CaptureResult.SENSOR_NEUTRAL_COLOR_POINT,
            arrayOf(Rational(381, 1000), Rational(1, 1), Rational(791, 1000))
        )
        Shadows.shadowOf(captureResult).set(
            CaptureResult.COLOR_CORRECTION_GAINS,
            RggbChannelVector(2.62f, 1.0f, 1.0f, 1.26f)
        )

        val profile = MultiCamColorHarmonizer.harmonize(primChars, captureResult, secChars)
        assertNotNull(profile)
        val transform = profile?.colorTransform
        assertNotNull(transform)

        // Verify that every row sum of the derived ColorSpaceTransform equals 1.0f (± 0.01f),
        // guaranteeing that white-balanced [1, 1, 1] maps strictly to neutral sRGB [1, 1, 1].
        for (r in 0 until 3) {
            var rowSum = 0f
            for (c in 0 until 3) {
                val element = transform!!.getElement(c, r)
                rowSum += element.numerator.toFloat() / element.denominator.toFloat()
            }
            assertEquals(1.0f, rowSum, 0.01f)
        }
    }
}
