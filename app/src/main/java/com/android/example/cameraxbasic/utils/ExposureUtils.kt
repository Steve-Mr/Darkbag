package com.android.example.cameraxbasic.utils

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.util.Range
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

object ExposureUtils {

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
        // 1. Calculate Baseline Total Exposure (Brightness)
        val baselineTotalExposure = currentIso.toDouble() * currentTime.toDouble()
        return calculateHdrPlusExposureInternal(baselineTotalExposure, currentIso, isoRange, timeRange)
    }

    /**
     * Calculates the target exposure configuration based on a closed-loop analysis of the scene.
     *
     * @param currentIso The ISO used for the analyzed frame.
     * @param currentTime The Exposure Time used for the analyzed frame.
     * @param measuredLuma The average luma (0-255) measured from the frame.
     * @param targetLuma The target average luma (e.g. 110.0) we want to achieve.
     * @param isoRange Supported ISO range.
     * @param timeRange Supported Time range.
     */
    fun calculateClosedLoopExposure(
        currentIso: Int,
        currentTime: Long,
        measuredLuma: Double,
        targetLuma: Double,
        isoRange: Range<Int>,
        timeRange: Range<Long>
    ): ExposureConfig {
        // 1. Calculate Scene Brightness Factor (Luma per Exposure Unit)
        val safeTime = currentTime.coerceAtLeast(1L)
        val currentExposure = currentIso.toDouble() * safeTime.toDouble()

        // Avoid division by zero or extremely low values
        val brightnessFactor = if (measuredLuma > 0.001) measuredLuma / currentExposure else 0.0

        // 2. Calculate Baseline Total Exposure needed to hit Target Luma
        // Target = Brightness * NewExposure => NewExposure = Target / Brightness
        val baselineTotalExposure = if (brightnessFactor > 1e-12) {
            targetLuma / brightnessFactor
        } else {
             // Fallback for pitch black: Max exposure
             isoRange.upper.toDouble() * timeRange.upper.toDouble()
        }

        // 3. Estimate "Auto ISO" for Heuristic
        // We need an ISO value to plug into the heuristic "If ISO <= 50, underexpose...".
        // We assume a standard shutter speed of 10ms (1/100s) to map total exposure to an equivalent ISO.
        val estimatedIso = (baselineTotalExposure / 10_000_000.0).toInt().coerceIn(isoRange.lower, isoRange.upper)

        return calculateHdrPlusExposureInternal(baselineTotalExposure, estimatedIso, isoRange, timeRange)
    }

    private fun calculateHdrPlusExposureInternal(
        baselineTotalExposure: Double,
        referenceIso: Int,
        isoRange: Range<Int>,
        timeRange: Range<Long>
    ): ExposureConfig {
        val minIso = isoRange.lower
        val maxIso = isoRange.upper
        val minTime = timeRange.lower
        val maxTime = timeRange.upper

        // 2. Determine Dynamic Underexposure Factor
        // Logic:
        // - Very Bright Scene (ISO <= 50): Underexpose extremely (up to -4 EV) to recover highlights.
        // - Bright Scene (ISO <= 100): Underexpose significantly (-2 to -3 EV).
        // - Dark Scene (ISO >= 800): Underexpose less or not at all (0 EV).

        val underexposeFactor = when {
            referenceIso <= 50 -> 0.0625f // -4 EV
            referenceIso <= 100 -> {
                // Interpolate -4 EV to -3 EV
                val ratio = (referenceIso - 50) / (100.0f - 50.0f)
                0.0625f + (ratio * (0.125f - 0.0625f))
            }
            referenceIso >= 800 -> 1.0f  // 0 EV
            else -> {
                // Interpolate -3 EV to 0 EV
                val ratio = (referenceIso - 100) / (800.0f - 100.0f)
                0.125f + (ratio * (1.0f - 0.125f))
            }
        }

        // Apply Gain Cap to prevent excessive noise in preview (requested by plan)
        // Cap digital gain at 16x (which implies min underexpose factor of 1/16 = 0.0625)
        // Since our heuristic goes down to 0.0625 (-4 EV), this is already within limits.
        // But let's be safe.
        val safeUnderexposeFactor = max(underexposeFactor, 1.0f / 16.0f)

        val targetTotalExposure = baselineTotalExposure * safeUnderexposeFactor
        val digitalGain = 1.0f / safeUnderexposeFactor

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
        val neededTimeS1 = (targetTotalExposure / minIso).toLong()
        targetTime = neededTimeS1.coerceIn(minTime, timeLimit8ms)
        targetIso = minIso

        if (currentExposure() < targetTotalExposure) {
            // Stage 2: Increase ISO up to 4x, keeping Time at 8ms
            val neededIsoS2 = (targetTotalExposure / timeLimit8ms).toInt()
            targetIso = neededIsoS2.coerceIn(minIso, isoLimit4x)
            targetTime = timeLimit8ms // Locked at 8ms

            if (currentExposure() < targetTotalExposure) {
                // Stage 3: Increase both Time and ISO beyond limits
                val remainingFactor = targetTotalExposure / currentExposure()
                // Split factor: sqrt(factor) to ISO, sqrt(factor) to Time
                val splitFactor = kotlin.math.sqrt(remainingFactor)

                val neededIsoS3 = (targetIso * splitFactor).toInt()
                val neededTimeS3 = (targetTime * splitFactor).toLong()

                targetIso = neededIsoS3.coerceIn(minIso, maxIso)
                targetTime = neededTimeS3.coerceIn(minTime, maxTime)
            }
        }

        return ExposureConfig(
            iso = targetIso,
            exposureTime = targetTime,
            digitalGain = digitalGain
        )
    }
}
