package top.maary.darkbag.utils

import android.util.Range
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ExposureUtilsTest {

    private val isoRange = Range(100, 6400)
    private val timeRange = Range(100_000L, 1_000_000_000L) // 0.1ms to 1s

    @Test
    fun testNormalScene_NoUnderexposure() {
        // ISO 800 should have 0 EV underexposure in Dynamic mode
        val config = ExposureUtils.calculateHdrPlusExposure(
            currentIso = 800,
            currentTime = 33_333_333L, // 1/30s
            isoRange = isoRange,
            timeRange = timeRange,
            underexposureMode = "Dynamic (Experimental)",
            clippingRatio = 0.0
        )

        // At ISO 800, underexposeFactor should be 1.0
        assertEquals(1.0f, config.digitalGain, 0.05f)

        val baseline = 800.0 * 33_333_333.0
        val actual = config.iso.toDouble() * config.exposureTime.toDouble()

        // Since we want 0 EV and we are far from floor, actual should match baseline
        assertEquals(baseline, actual, baseline * 0.05)
    }

    @Test
    fun testBrightScene_FloorHit() {
        // Scenario: Scene is so bright that AE is already at ISO 100, 100us (the floor).
        // We want -3 EV (at ISO 100), but hardware cannot go lower.
        val configFloor = ExposureUtils.calculateHdrPlusExposure(
            currentIso = 100,
            currentTime = 100_000L, // Already at minTime
            isoRange = isoRange,
            timeRange = timeRange,
            underexposureMode = "Dynamic (Experimental)",
            clippingRatio = 0.0
        )

        // baseline = 100 * 100k = 10M
        // underexposeFactor for ISO 100 is -3 EV (0.125)
        // targetTotalExposure = 10M * 0.125 = 1.25M
        // actual hardware floor = 100 * 100k = 10M (cannot go lower than minIso * minTime)

        // Refactored logic: digitalGain = baseline / actual = 10M / 10M = 1.0
        // Old logic would have given digitalGain = 1 / 0.125 = 8.0 (Overexposure!)

        assertEquals(1.0f, configFloor.digitalGain, 0.01f)
        assertEquals(100, configFloor.iso)
        assertEquals(100_000L, configFloor.exposureTime)
    }

    @Test
    fun testClippingSmoothness() {
        // Threshold is 0.03
        val configAtThreshold = ExposureUtils.calculateHdrPlusExposure(
            800, 33_333_333L, isoRange, timeRange, clippingRatio = 0.03
        )
        val configJustAbove = ExposureUtils.calculateHdrPlusExposure(
            800, 33_333_333L, isoRange, timeRange, clippingRatio = 0.031
        )

        // Difference should be small because of smooth ramp
        val diff = Math.abs(configJustAbove.digitalGain - configAtThreshold.digitalGain)
        assertTrue("Jump should be small, got $diff", diff < 0.05f)
    }

    @Test
    fun testGainDampening() {
        // Significant clipping: 10%
        val configNoClipping = ExposureUtils.calculateHdrPlusExposure(
            800, 33_333_333L, isoRange, timeRange, clippingRatio = 0.0
        )
        val configHighClipping = ExposureUtils.calculateHdrPlusExposure(
            800, 33_333_333L, isoRange, timeRange, clippingRatio = 0.10
        )

        // High clipping should trigger underexposure and THEN dampening.
        // Underexposure makes 'actual' smaller, which would INCREASE digitalGain if not dampened.
        // But with dampening, it should be slightly less than (baseline / actual).

        val baseline = 800.0 * 33_333_333.0
        val actualHigh = configHighClipping.iso.toDouble() * configHighClipping.exposureTime.toDouble()
        val expectedGainWithoutDampening = (baseline / actualHigh).toFloat()

        assertTrue("Gain should be dampened: ${configHighClipping.digitalGain} < $expectedGainWithoutDampening",
            configHighClipping.digitalGain < expectedGainWithoutDampening)
    }

    @Test
    fun testDynamicCurve() {
        val tenMs = 10_000_000L
        // ISO 800 -> 0 EV (1.0)
        val config800 = ExposureUtils.calculateHdrPlusExposure(800, tenMs, isoRange, timeRange)
        val achievedFactor800 = (config800.iso.toDouble() * config800.exposureTime.toDouble()) / (800.0 * tenMs)
        assertEquals(1.0, achievedFactor800, 0.1)

        // ISO 100 -> -3 EV (0.125)
        val config100 = ExposureUtils.calculateHdrPlusExposure(100, tenMs, isoRange, timeRange)
        // achieved underexposure factor = actual / baseline
        val achievedFactor100 = (config100.iso.toDouble() * config100.exposureTime.toDouble()) / (100.0 * tenMs)
        assertEquals(0.125, achievedFactor100, 0.05)
    }

    @Test
    fun testOffMode() {
        val tenMs = 10_000_000L
        val config = ExposureUtils.calculateHdrPlusExposure(800, tenMs, isoRange, timeRange, underexposureMode = "Off")
        val achievedFactor = (config.iso.toDouble() * config.exposureTime.toDouble()) / (800.0 * tenMs)
        assertEquals(1.0, achievedFactor, 0.05)
        assertEquals(1.0f, config.digitalGain, 0.05f)
    }
}
