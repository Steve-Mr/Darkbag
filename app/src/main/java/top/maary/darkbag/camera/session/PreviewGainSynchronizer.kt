package top.maary.darkbag.camera.session

import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import kotlin.math.abs

/**
 * Synchronizes Camera2 hardware AE transitions (e.g., HDR+ ZSL EV compensation changes) and
 * underexposed HDR+ burst frames with the OpenGL shader's `previewGain` on a per-frame basis,
 * preventing brightness flashes in both the viewfinder and RAW Motion Photo MP4 streams.
 */
class PreviewGainSynchronizer {
    companion object {
        const val MIN_GAIN = 1.0f
        const val MAX_GAIN = 8.0f
        const val MAX_TIMESTAMP_ENTRIES = 64
        const val CONVERGENCE_TOLERANCE = 0.04f
        const val MAX_TRANSITION_FRAMES = 20
        const val MAX_TRANSITION_DURATION_NS = 650_000_000L
        const val MIN_MATCHING_COMP_FRAMES_FOR_AE_CONVERGED = 2
        const val FALLBACK_RAMP_FRAMES = 6
    }

    private data class AeTransition(
        val startGain: Float,
        val targetGain: Float,
        val refEv0Exposure: Double,
        val expectedAeCompSteps: Int,
        val startedAtNs: Long,
        var framesSeen: Int = 0,
        var matchingCompFrames: Int = 0
    )

    private val lock = Any()
    private var steadyTargetGain: Float = 1.0f
    private var latestResolvedGain: Float = 1.0f
    private var lastSteadyEv0Exposure: Double = 0.0
    private var transition: AeTransition? = null

    private val burstFrameGains = object : LinkedHashMap<Long, Float>(MAX_TIMESTAMP_ENTRIES + 1, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Float>?): Boolean =
            size > MAX_TIMESTAMP_ENTRIES
    }

    private val repeatingFrameGains = object : LinkedHashMap<Long, Float>(MAX_TIMESTAMP_ENTRIES + 1, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Float>?): Boolean =
            size > MAX_TIMESTAMP_ENTRIES
    }

    val currentEffectiveGain: Float
        get() = synchronized(lock) { latestResolvedGain }

    val currentSteadyTargetGain: Float
        get() = synchronized(lock) { steadyTargetGain }

    fun reset() {
        synchronized(lock) {
            steadyTargetGain = 1.0f
            latestResolvedGain = 1.0f
            lastSteadyEv0Exposure = 0.0
            transition = null
            burstFrameGains.clear()
            repeatingFrameGains.clear()
        }
    }

    fun beginAeTransition(
        newTargetGain: Float,
        expectedAeCompSteps: Int,
        nowNs: Long = System.nanoTime()
    ) {
        synchronized(lock) {
            val target = newTargetGain.coerceIn(MIN_GAIN, MAX_GAIN)
            steadyTargetGain = target
            if (abs(target - latestResolvedGain) < 0.01f && transition == null) {
                return
            }
            val refEv0 = transition?.refEv0Exposure?.takeIf { it > 0.0 } ?: lastSteadyEv0Exposure
            transition = AeTransition(
                startGain = latestResolvedGain,
                targetGain = target,
                refEv0Exposure = refEv0,
                expectedAeCompSteps = expectedAeCompSteps,
                startedAtNs = nowNs
            )
        }
    }

    fun registerBurstFrame(timestampNs: Long, gain: Float) {
        if (timestampNs == 0L) return
        synchronized(lock) {
            burstFrameGains[timestampNs] = gain.coerceIn(MIN_GAIN, MAX_GAIN)
        }
    }

    fun computeBurstPreviewGain(
        curIso: Int,
        curExposureTimeNs: Long,
        burstIso: Int,
        burstExposureTimeNs: Long,
        isManualExposure: Boolean
    ): Float {
        if (isManualExposure || burstIso <= 0 || burstExposureTimeNs <= 0L) return 1.0f
        return synchronized(lock) {
            val ev0 = (transition?.refEv0Exposure?.takeIf { it > 0.0 }
                ?: lastSteadyEv0Exposure.takeIf { it > 0.0 })
                ?: if (curIso > 0 && curExposureTimeNs > 0L) {
                    curIso.toDouble() * curExposureTimeNs.toDouble() * latestResolvedGain.toDouble()
                } else {
                    0.0
                }
            if (ev0 <= 0.0) return@synchronized 1.0f
            (ev0 / (burstIso.toDouble() * burstExposureTimeNs.toDouble()))
                .toFloat()
                .coerceIn(MIN_GAIN, MAX_GAIN)
        }
    }

    fun onRepeatingCaptureResult(
        timestampNs: Long,
        iso: Int?,
        exposureTimeNs: Long?,
        aeCompSteps: Int?,
        aeState: Int?,
        nowNs: Long = System.nanoTime()
    ): Float {
        synchronized(lock) {
            if (timestampNs != 0L) {
                burstFrameGains[timestampNs]?.let { return it }
            }

            val frameExp = if (iso != null && iso > 0 && exposureTimeNs != null && exposureTimeNs > 0L) {
                iso.toDouble() * exposureTimeNs.toDouble()
            } else {
                0.0
            }

            val activeTransition = transition
            if (activeTransition == null) {
                val resolved = steadyTargetGain
                if (frameExp > 0.0) {
                    lastSteadyEv0Exposure = frameExp * resolved.toDouble()
                }
                latestResolvedGain = resolved
                if (timestampNs != 0L) {
                    repeatingFrameGains[timestampNs] = resolved
                }
                return resolved
            }

            activeTransition.framesSeen++
            val compMatches = (aeCompSteps == null || aeCompSteps == activeTransition.expectedAeCompSteps)
            if (compMatches) {
                activeTransition.matchingCompFrames++
            }

            val minG = minOf(activeTransition.startGain, activeTransition.targetGain)
            val maxG = maxOf(activeTransition.startGain, activeTransition.targetGain)

            var resolved = when {
                !compMatches -> activeTransition.startGain
                frameExp > 0.0 && activeTransition.refEv0Exposure > 0.0 ->
                    (activeTransition.refEv0Exposure / frameExp).toFloat().coerceIn(minG, maxG)
                else -> {
                    val frac = (activeTransition.matchingCompFrames.toFloat() / FALLBACK_RAMP_FRAMES)
                        .coerceIn(0f, 1f)
                    activeTransition.startGain + (activeTransition.targetGain - activeTransition.startGain) * frac
                }
            }

            val ratioConverged = compMatches &&
                frameExp > 0.0 &&
                activeTransition.refEv0Exposure > 0.0 &&
                abs(resolved - activeTransition.targetGain) / maxOf(0.01f, activeTransition.targetGain) <= CONVERGENCE_TOLERANCE
            val aeConverged = compMatches &&
                activeTransition.matchingCompFrames >= MIN_MATCHING_COMP_FRAMES_FOR_AE_CONVERGED &&
                aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED
            val fallbackDone = (frameExp <= 0.0 || activeTransition.refEv0Exposure <= 0.0) &&
                activeTransition.matchingCompFrames >= FALLBACK_RAMP_FRAMES
            val timedOut = activeTransition.framesSeen >= MAX_TRANSITION_FRAMES ||
                (nowNs - activeTransition.startedAtNs) >= MAX_TRANSITION_DURATION_NS

            if (ratioConverged || aeConverged || fallbackDone || timedOut) {
                resolved = activeTransition.targetGain
                transition = null
                if (frameExp > 0.0) {
                    lastSteadyEv0Exposure = frameExp * resolved.toDouble()
                }
            }

            latestResolvedGain = resolved
            if (timestampNs != 0L) {
                repeatingFrameGains[timestampNs] = resolved
            }
            return resolved
        }
    }

    fun resolveGainForTimestamp(timestampNs: Long): Float {
        synchronized(lock) {
            if (timestampNs != 0L) {
                burstFrameGains[timestampNs]?.let { return it }
                repeatingFrameGains[timestampNs]?.let { return it }
            }
            return latestResolvedGain
        }
    }

    fun resolveGainForRawFrame(timestampNs: Long, result: TotalCaptureResult?): Float {
        synchronized(lock) {
            if (timestampNs != 0L) {
                burstFrameGains[timestampNs]?.let { return it }
                repeatingFrameGains[timestampNs]?.let { return it }
            }
            if (result != null && timestampNs != 0L &&
                result.get(CaptureResult.SENSOR_TIMESTAMP) == timestampNs
            ) {
                val activeTransition = transition ?: return latestResolvedGain
                val aeComp = result.get(CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION)
                val iso = result.get(CaptureResult.SENSOR_SENSITIVITY)
                val expNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                val compMatches = (aeComp == null || aeComp == activeTransition.expectedAeCompSteps)
                if (!compMatches) return activeTransition.startGain
                val frameExp = if (iso != null && iso > 0 && expNs != null && expNs > 0L) {
                    iso.toDouble() * expNs.toDouble()
                } else {
                    0.0
                }
                if (frameExp > 0.0 && activeTransition.refEv0Exposure > 0.0) {
                    val minG = minOf(activeTransition.startGain, activeTransition.targetGain)
                    val maxG = maxOf(activeTransition.startGain, activeTransition.targetGain)
                    return (activeTransition.refEv0Exposure / frameExp).toFloat().coerceIn(minG, maxG)
                }
            }
            return latestResolvedGain
        }
    }
}
