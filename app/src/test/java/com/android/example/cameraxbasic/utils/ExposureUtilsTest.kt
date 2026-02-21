package com.android.example.cameraxbasic.utils

import android.util.Range
import com.android.example.cameraxbasic.fragments.SettingsFragment
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
    fun testNormalScene_DynamicUnderexposure() {
        // ISO 800 should have -0.5 EV underexposure in Dynamic mode (0.7071)
        val config = ExposureUtils.calculateHdrPlusExposure(
            currentIso = 800,
            currentTime = 33_333_333L, // 1/30s
            isoRange = isoRange,
            timeRange = timeRange,
            underexposureMode = SettingsFragment.HDR_UNDEREXPOSURE_MODE_DYNAMIC,
            clippingRatio = 0.0
        )

        // underexposeFactor is 0.7071
        // digitalGain should be influenced by RECOVERY_TARGET_RATIO (0.8)
        // targetTotalExposure = baseline * 0.7071
        // actualTotalExposure should match target (since we are not at floor)
        // digitalGain = (baseline * 0.8) / actual = (baseline * 0.8) / (baseline * 0.7071) = 0.8 / 0.7071 = 1.131

        assertEquals(1.131f, config.digitalGain, 0.05f)

        val baseline = 800.0 * 33_333_333.0
        val actual = config.iso.toDouble() * config.exposureTime.toDouble()

        // actual / baseline should be approx 0.7071
        assertEquals(0.7071, actual / baseline, 0.05)
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
            underexposureMode = SettingsFragment.HDR_UNDEREXPOSURE_MODE_DYNAMIC,
            clippingRatio = 0.0
        )

        // baseline = 100 * 100k = 10M
        // underexposeFactor for ISO 100 is -3 EV (0.125)
        // targetTotalExposure = 10M * 0.125 = 1.25M
        // actual hardware floor = 100 * 100k = 10M (cannot go lower than minIso * minTime)

        // digitalGain = (baseline * 0.8) / actual = (10M * 0.8) / 10M = 0.8

        assertEquals(0.8f, configFloor.digitalGain, 0.01f)
        assertEquals(100, configFloor.iso)
        assertEquals(100_000L, configFloor.exposureTime)
    }

    @Test
    fun testClippingSmoothness() {
        // Threshold is 0.005
        val configAtThreshold = ExposureUtils.calculateHdrPlusExposure(
            800, 33_333_333L, isoRange, timeRange, clippingRatio = 0.005
        )
        val configJustAbove = ExposureUtils.calculateHdrPlusExposure(
            800, 33_333_333L, isoRange, timeRange, clippingRatio = 0.006
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
    fun testHighClippingRecoveryRatio_Fixed() {
        // Clipping 5%
        val config = ExposureUtils.calculateHdrPlusExposure(
            800, 33_333_333L, isoRange, timeRange, clippingRatio = 0.05
        )

        // Target brightness should be fixed 0.8x (RECOVERY_TARGET_RATIO)
        // underexposeFactor for ISO 800 is -0.5 EV (0.7071)
        // Additional underexposure for 5% clipping:
        // excessClipping = 0.05 - 0.005 = 0.045
        // additionalStops = 0.045 * 20 = 0.9 stops
        // final underexposeFactor = 0.7071 * 0.5^0.9 = 0.7071 * 0.5358 = 0.3788

        // digitalGain calculation:
        // recoveryRatio = 0.8
        // digitalGain = (baseline * 0.8) / (baseline * 0.3788) = 2.112
        // Then dampened by GAIN_DAMPENING_FACTOR (0.4):
        // dampening = 1.0 - 0.4 * (0.9 / 3.0) = 1.0 - 0.12 = 0.88
        // final gain = 2.112 * 0.88 = 1.858

        assertEquals(1.858f, config.digitalGain, 0.05f)
    }

    @Test
    fun testDynamicCurve() {
        val tenMs = 10_000_000L
        // ISO 800 -> -0.5 EV (0.7071)
        val config800 = ExposureUtils.calculateHdrPlusExposure(800, tenMs, isoRange, timeRange)
        val achievedFactor800 = (config800.iso.toDouble() * config800.exposureTime.toDouble()) / (800.0 * tenMs)
        assertEquals(0.7071, achievedFactor800, 0.1)

        // ISO 100 -> -3 EV (0.125)
        val config100 = ExposureUtils.calculateHdrPlusExposure(100, tenMs, isoRange, timeRange)
        // achieved underexposure factor = actual / baseline
        val achievedFactor100 = (config100.iso.toDouble() * config100.exposureTime.toDouble()) / (100.0 * tenMs)
        assertEquals(0.125, achievedFactor100, 0.05)
    }
}
