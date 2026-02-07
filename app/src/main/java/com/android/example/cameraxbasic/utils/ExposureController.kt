package com.android.example.cameraxbasic.utils

import android.util.Range
import com.android.example.cameraxbasic.utils.ExposureUtils.ExposureConfig

/**
 * Controller to manage closed-loop exposure with smoothing.
 */
class ExposureController(
    private val smoothingFactor: Double = 0.15
) {
    private var smoothedIso: Double = -1.0
    private var smoothedTime: Double = -1.0
    private var smoothedGain: Double = 1.0

    /**
     * Updates the exposure calculation based on new luma measurement.
     *
     * @param measuredLuma Average luma from ImageAnalysis.
     * @param currentIso The ISO used for that frame.
     * @param currentTime The exposure time used for that frame.
     * @param isoRange Camera ISO range.
     * @param timeRange Camera Time range.
     * @return The new smoothed ExposureConfig to apply.
     */
    fun update(
        measuredLuma: Double,
        currentIso: Int,
        currentTime: Long,
        isoRange: Range<Int>,
        timeRange: Range<Long>
    ): ExposureConfig {
        // 1. Calculate Instant Target
        // We target 110.0 luma (middle gray ish)
        val targetConfig = ExposureUtils.calculateClosedLoopExposure(
            currentIso, currentTime, measuredLuma, 110.0, isoRange, timeRange
        )

        // 2. Initialize if first run
        if (smoothedIso < 0) {
            smoothedIso = targetConfig.iso.toDouble()
            smoothedTime = targetConfig.exposureTime.toDouble()
            smoothedGain = targetConfig.digitalGain.toDouble()
            return targetConfig
        }

        // 3. Apply Smoothing (EMA)
        // New = Alpha * Target + (1-Alpha) * Old
        smoothedIso = smoothingFactor * targetConfig.iso + (1 - smoothingFactor) * smoothedIso
        smoothedTime = smoothingFactor * targetConfig.exposureTime + (1 - smoothingFactor) * smoothedTime
        smoothedGain = smoothingFactor * targetConfig.digitalGain + (1 - smoothingFactor) * smoothedGain

        // 4. Return Result
        return ExposureConfig(
            iso = smoothedIso.toInt(),
            exposureTime = smoothedTime.toLong(),
            digitalGain = smoothedGain.toFloat()
        )
    }

    fun reset() {
        smoothedIso = -1.0
        smoothedTime = -1.0
        smoothedGain = 1.0
    }
}
