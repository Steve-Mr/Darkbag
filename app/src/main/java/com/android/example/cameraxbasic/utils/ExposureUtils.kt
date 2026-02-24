package com.android.example.cameraxbasic.utils

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.util.Range
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

object ExposureUtils {

    private const val ISO_THRESHOLD_VERY_LOW = 40
    private const val ISO_THRESHOLD_LOW = 100
    private const val ISO_THRESHOLD_MID = 400
    private const val ISO_THRESHOLD_HIGH = 800

    private const val FACTOR_EV_MINUS_4 = 0.0625f   // -4 EV
    private const val FACTOR_EV_MINUS_3 = 0.125f    // -3 EV
    private const val FACTOR_EV_MINUS_1_5 = 0.3535f // -1.5 EV
    private const val FACTOR_EV_0 = 1.0f            // 0 EV

    private const val CLIPPING_RATIO_THRESHOLD = 0.03
    private const val CLIPPING_TO_EV_FACTOR = 15.0
    private const val MAX_ADDITIONAL_UNDEREXPOSURE_STOPS = 2.0
    private const val GAIN_DAMPENING_FACTOR = 0.2

    data class ExposureConfig(
        val iso: Int,
        val exposureTime: Long, // nanoseconds
        val digitalGain: Float
    )

    /**
     * Calculates the target exposure configuration for HDR+ burst based on current scene brightness.
     * Implements "Exposure Factorization" with dynamic underexposure.
     *
     * @param currentIso Current ISO from auto-exposure.
     * @param currentTime Current Exposure Time (ns) from auto-exposure.
     * @param isoRange Supported ISO range of the camera.
     * @param timeRange Supported Exposure Time range of the camera.
     * @param underexposureMode Mode for underexposure: "Off", "-1 EV", "-2 EV", or "Dynamic (Experimental)".
     * @param clippingRatio Ratio of pixels that are near saturation (0.0 to 1.0).
     * @return ExposureConfig with target ISO, Time, and required Digital Gain.
     */
    fun calculateHdrPlusExposure(
        currentIso: Int,
        currentTime: Long,
        isoRange: Range<Int>,
        timeRange: Range<Long>,
        underexposureMode: String = "Dynamic (Experimental)",
        clippingRatio: Double = 0.0
    ): ExposureConfig {
        val minIso = isoRange.lower
        val maxIso = isoRange.upper
        val minTime = timeRange.lower
        val maxTime = timeRange.upper

        // 1. Calculate Baseline Total Exposure (Brightness)
        // We use a simple product of ISO * Time as a proxy for total light collected.
        // Note: Real brightness depends on aperture (f-number), but usually fixed on mobile.
        val baselineTotalExposure = currentIso.toDouble() * currentTime.toDouble()

        // 2. Determine Underexposure Factor
        var additionalUnderexposure = 0.0
        var underexposeFactor = when (underexposureMode) {
            "Off" -> 1.0f
            "-1 EV" -> 0.5f
            "-2 EV" -> 0.25f
            else -> {
                // Dynamic Logic Refined:
                // - ISO 40 or less: -4 EV (rare)
                // - ISO 100: -3 EV
                // - ISO 400: -1.5 EV
                // - ISO 800 or more: 0 EV
                when {
                    currentIso <= ISO_THRESHOLD_VERY_LOW -> FACTOR_EV_MINUS_4
                    currentIso <= ISO_THRESHOLD_LOW -> {
                        interpolate(currentIso, ISO_THRESHOLD_VERY_LOW, ISO_THRESHOLD_LOW, FACTOR_EV_MINUS_4, FACTOR_EV_MINUS_3)
                    }
                    currentIso <= ISO_THRESHOLD_MID -> {
                        interpolate(currentIso, ISO_THRESHOLD_LOW, ISO_THRESHOLD_MID, FACTOR_EV_MINUS_3, FACTOR_EV_MINUS_1_5)
                    }
                    currentIso >= ISO_THRESHOLD_HIGH -> FACTOR_EV_0
                    else -> {
                        interpolate(currentIso, ISO_THRESHOLD_MID, ISO_THRESHOLD_HIGH, FACTOR_EV_MINUS_1_5, FACTOR_EV_0)
                    }
                }
            }
        }

        // Apply additional underexposure if highlights are clipping (Pixel-based refinement)
        if (underexposureMode == "Dynamic (Experimental)" && clippingRatio > CLIPPING_RATIO_THRESHOLD) {
             // Smooth ramp: If more than threshold (e.g. 3%) pixels are clipping, push underexposure further.
             // Subtraction of threshold ensures a smooth transition from 0 EV additional underexposure.
             val excessClipping = clippingRatio - CLIPPING_RATIO_THRESHOLD
             additionalUnderexposure = (excessClipping * CLIPPING_TO_EV_FACTOR).coerceAtMost(MAX_ADDITIONAL_UNDEREXPOSURE_STOPS)
             underexposeFactor *= (0.5).pow(additionalUnderexposure).toFloat()
        }

        val targetTotalExposure = baselineTotalExposure * underexposeFactor

        // 3. Exposure Factorization (The "Payload" Strategy)
        // Goal: Achieve targetTotalExposure using specific constraints.
        // Constraints:
        // - Stage 1: Prioritize Shutter Speed (Short Time) to freeze motion. Max 8ms.
        // - Stage 2: If 8ms insufficient, increase ISO up to 4x Base.
        // - Stage 3: If still insufficient, increase both beyond limits.

        var targetIso = minIso
        var targetTime = minTime

        val timeLimit8ms = 8_000_000L // 8ms in ns
        val isoLimit4x = minIso * 4

        // Helper to calculate resulting exposure
        fun currentExposure(): Double = targetIso.toDouble() * targetTime.toDouble()

        // Stage 1: Increase Time up to 8ms, keeping ISO at Min
        // We want: minIso * T = targetTotalExposure => T = target / minIso
        val neededTimeS1 = (targetTotalExposure / minIso).toLong()
        targetTime = neededTimeS1.coerceIn(minTime, timeLimit8ms)
        targetIso = minIso

        if (currentExposure() < targetTotalExposure) {
            // Stage 2: Increase ISO up to 4x, keeping Time at 8ms
            // We want: I * 8ms = targetTotalExposure => I = target / 8ms
            val neededIsoS2 = (targetTotalExposure / timeLimit8ms).toInt()
            targetIso = neededIsoS2.coerceIn(minIso, isoLimit4x)
            targetTime = timeLimit8ms // Locked at 8ms

            if (currentExposure() < targetTotalExposure) {
                // Stage 3: Increase both Time and ISO beyond limits
                // The prompt says "increase both... log space proportional".
                // Simple implementation:
                // Distribute the remaining required gain equally between Time and ISO?
                // Or prioritize Time up to a hard limit (e.g. 100ms) then ISO?
                // Prompt: "Simultaneously increase... until limits (100ms, 96x gain)".

                val remainingFactor = targetTotalExposure / currentExposure()
                // Split factor: sqrt(factor) to ISO, sqrt(factor) to Time
                // This keeps them balanced in log space.
                val splitFactor = kotlin.math.sqrt(remainingFactor)

                val neededIsoS3 = (targetIso * splitFactor).toInt()
                val neededTimeS3 = (targetTime * splitFactor).toLong()

                targetIso = neededIsoS3.coerceIn(minIso, maxIso)
                targetTime = neededTimeS3.coerceIn(minTime, maxTime) // Camera max, not 100ms hard limit
            }
        }

        // 4. Final Gain Calculation
        // Calculate gain based on ACTUALLY achieved exposure to avoid overexposure boost when hardware hits its floor.
        val actualTotalExposure = targetIso.toDouble() * targetTime.toDouble()
        var digitalGain = (baselineTotalExposure / actualTotalExposure).toFloat()

        // Apply "Gain Dampening" for highlight preservation.
        // If we underexposed specifically due to clipping, we avoid boosting midtones back to full brightness.
        if (additionalUnderexposure > 0) {
            val dampening = (1.0 - GAIN_DAMPENING_FACTOR * (additionalUnderexposure / MAX_ADDITIONAL_UNDEREXPOSURE_STOPS)).toFloat()
            digitalGain *= dampening
        }

        return ExposureConfig(
            iso = targetIso,
            exposureTime = targetTime,
            digitalGain = digitalGain
        )
    }

    /**
     * Linear interpolation helper.
     */
    private fun interpolate(value: Int, fromX: Int, toX: Int, fromY: Float, toY: Float): Float {
        if (toX == fromX) {
            return fromY
        }
        val ratio = (value - fromX).toFloat() / (toX - fromX)
        return fromY + (ratio * (toY - fromY))
    }
}
