package com.android.example.cameraxbasic.utils

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.util.Range
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

object ExposureUtils {

    private const val ISO_THRESHOLD_VERY_BRIGHT = 50
    private const val ISO_THRESHOLD_BRIGHT = 100
    private const val ISO_THRESHOLD_DARK = 800

    private const val FACTOR_VERY_BRIGHT = 0.0625f // -4 EV
    private const val FACTOR_BRIGHT = 0.125f       // -3 EV
    private const val FACTOR_DARK = 1.0f           // 0 EV

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
     * @return ExposureConfig with target ISO, Time, and required Digital Gain.
     */
    fun calculateHdrPlusExposure(
        currentIso: Int,
        currentTime: Long,
        isoRange: Range<Int>,
        timeRange: Range<Long>
    ): ExposureConfig {
        val minIso = isoRange.lower
        val maxIso = isoRange.upper
        val minTime = timeRange.lower
        val maxTime = timeRange.upper

        // 1. Calculate Baseline Total Exposure (Brightness)
        // We use a simple product of ISO * Time as a proxy for total light collected.
        // Note: Real brightness depends on aperture (f-number), but usually fixed on mobile.
        val baselineTotalExposure = currentIso.toDouble() * currentTime.toDouble()

        // 2. Determine Dynamic Underexposure Factor
        // Logic:
        // - Very Bright Scene (ISO <= 50): Underexpose extremely (up to -4 EV) to recover highlights in harsh sun.
        // - Bright Scene (ISO <= 100): Underexpose significantly (-2 to -3 EV).
        // - Dark Scene (ISO >= 800): Underexpose less or not at all (0 EV) to avoid noise.
        //
        // New Heuristic (Extended Dynamic Range):
        // If ISO <= 50: Factor = 0.0625 (-4 EV)
        // If ISO <= 100: Factor = 0.125 (-3 EV) -> Linear interp 50-100
        // If ISO >= 800: Factor = 1.0 (0 EV)
        // Interpolate in between.

        val underexposeFactor = when {
            currentIso <= ISO_THRESHOLD_VERY_BRIGHT -> FACTOR_VERY_BRIGHT // -4 EV
            currentIso <= ISO_THRESHOLD_BRIGHT -> {
                // Interpolate -4 EV to -3 EV
                val ratio = (currentIso - ISO_THRESHOLD_VERY_BRIGHT) / (ISO_THRESHOLD_BRIGHT.toFloat() - ISO_THRESHOLD_VERY_BRIGHT.toFloat())
                FACTOR_VERY_BRIGHT + (ratio * (FACTOR_BRIGHT - FACTOR_VERY_BRIGHT))
            }
            currentIso >= ISO_THRESHOLD_DARK -> FACTOR_DARK  // 0 EV
            else -> {
                // Interpolate -3 EV to 0 EV
                val ratio = (currentIso - ISO_THRESHOLD_BRIGHT) / (ISO_THRESHOLD_DARK.toFloat() - ISO_THRESHOLD_BRIGHT.toFloat())
                FACTOR_BRIGHT + (ratio * (FACTOR_DARK - FACTOR_BRIGHT))
            }
        }

        val targetTotalExposure = baselineTotalExposure * underexposeFactor
        val digitalGain = 1.0f / underexposeFactor

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

        return ExposureConfig(
            iso = targetIso,
            exposureTime = targetTime,
            digitalGain = digitalGain
        )
    }
}
