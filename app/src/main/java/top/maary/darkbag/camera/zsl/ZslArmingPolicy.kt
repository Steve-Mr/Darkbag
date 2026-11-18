package top.maary.darkbag.camera.zsl

import kotlin.math.pow
import kotlin.math.roundToInt

enum class ZslProfile { OFF, SINGLE_RAW, HDR_PLUS }

/**
 * Pure policy rules governing ZSL admission, profile selection, EV calculations,
 * and UI status formatting. 100% testable on plain JVM.
 */
object ZslArmingPolicy {
    const val MEMORY_HEADROOM_BYTES = 600L * 1024L * 1024L
    const val DEFAULT_AE_STEP_EV = 1f / 3f

    data class ContextFacts(
        val zslEnabled: Boolean,
        val sessionActive: Boolean,
        val isNormalCaptureMode: Boolean,
        val force60Fps: Boolean,
        val isMultiCamera: Boolean,
        val isManualExposure: Boolean,
        val isFlashActive: Boolean,
        val isPowerSaveOrThermalSevere: Boolean,
        val isHdrPlusEnabled: Boolean,
        val capabilityReport: ZslCapabilityReport?
    )

    data class EvaluationResult(
        val profile: ZslProfile,
        val disarmReason: String = ""
    )

    fun evaluate(facts: ContextFacts): EvaluationResult {
        if (!facts.zslEnabled) return EvaluationResult(ZslProfile.OFF, "disabled")
        if (!facts.sessionActive) return EvaluationResult(ZslProfile.OFF, "session_closed")
        if (!facts.isNormalCaptureMode) return EvaluationResult(ZslProfile.OFF, "current_mode")
        if (facts.force60Fps) return EvaluationResult(ZslProfile.OFF, "force_60fps")
        if (facts.isMultiCamera) return EvaluationResult(ZslProfile.OFF, "multi_camera")
        if (facts.isManualExposure) return EvaluationResult(ZslProfile.OFF, "manual_exposure")
        if (facts.isFlashActive) return EvaluationResult(ZslProfile.OFF, "flash")
        if (facts.isPowerSaveOrThermalSevere) return EvaluationResult(ZslProfile.OFF, "thermal")

        val report = facts.capabilityReport
            ?: return EvaluationResult(ZslProfile.OFF, "evaluating")
        if (report.route != ZslRoute.RAW_RING) {
            return EvaluationResult(ZslProfile.OFF, report.reason.key)
        }

        return if (facts.isHdrPlusEnabled) {
            if (report.supportsHdrPlusZsl) {
                EvaluationResult(ZslProfile.HDR_PLUS)
            } else {
                EvaluationResult(ZslProfile.OFF, "hdr_unsupported")
            }
        } else {
            EvaluationResult(ZslProfile.SINGLE_RAW)
        }
    }

    fun limitByMemory(
        config: ZslRingConfig,
        availMemBytes: Long
    ): ZslRingConfig? {
        val usable = availMemBytes - MEMORY_HEADROOM_BYTES
        if (usable < config.frameBytes) return null
        val memoryMaxFrames = (usable / config.frameBytes).toInt()
        val depth = minOf(config.depth, memoryMaxFrames)
        if (depth < 1) return null
        return config.copy(depth = depth)
    }

    fun resolveRingConfig(
        profile: ZslProfile,
        burstCount: Int,
        frameBytes: Long,
        lazyRing: Boolean,
        lazyFailed: Boolean,
        availMemBytes: Long
    ): ZslRingConfig? {
        val mode = if (lazyRing && !lazyFailed) ZslRingMode.LAZY_IMAGE else ZslRingMode.EAGER_COPY
        val desired = if (profile == ZslProfile.HDR_PLUS) {
            ZslRingConfig.forHdrPlus(burstCount, frameBytes, mode)
        } else {
            ZslRingConfig.forSingle(frameBytes, mode)
        } ?: return null
        val minDepth = if (profile == ZslProfile.HDR_PLUS) ZslRingConfig.MIN_HDR_DEPTH else 1
        val limited = limitByMemory(desired, availMemBytes) ?: return null
        if (limited.depth < minDepth) return null
        return limited
    }

    data class HdrEvConfig(
        val steps: Int,
        val digitalGain: Float
    )

    fun resolveHdrTargetEv(
        underexposureMode: String,
        minSteps: Int,
        stepEv: Float
    ): HdrEvConfig {
        val targetEv = when {
            underexposureMode.startsWith("Off") -> 0.0f
            underexposureMode.startsWith("-2") -> -2.0f
            else -> -1.0f // Includes "-1 EV", "Dynamic", and default "Dynamic (Experimental)"
        }
        val safeStepEv = if (stepEv > 0f) stepEv else DEFAULT_AE_STEP_EV
        val steps = (targetEv / safeStepEv).roundToInt().coerceAtLeast(minSteps)
        val actualEv = steps * safeStepEv
        val digitalGain = if (actualEv < 0f) {
            2.0.pow(-actualEv.toDouble()).toFloat()
        } else {
            1.0f
        }
        return HdrEvConfig(steps, digitalGain)
    }

    data class ArmingPlan(
        val profile: ZslProfile,
        val ringConfig: ZslRingConfig? = null,
        val evConfig: HdrEvConfig? = null,
        val report: ZslCapabilityReport? = null,
        val disarmReason: String = ""
    )

    fun planArming(
        facts: ContextFacts,
        isRawReader: Boolean,
        hasLut: Boolean,
        lazyRing: Boolean,
        lazyFailed: Boolean,
        burstCount: Int,
        underexposureMode: String,
        availMemBytes: Long
    ): ArmingPlan {
        val eval = evaluate(facts)
        if (eval.profile == ZslProfile.OFF) return ArmingPlan(ZslProfile.OFF, disarmReason = eval.disarmReason)
        val report = facts.capabilityReport ?: return ArmingPlan(ZslProfile.OFF, disarmReason = "evaluating")
        if (!isRawReader) return ArmingPlan(ZslProfile.OFF, disarmReason = "reader_not_raw")
        if (eval.profile == ZslProfile.HDR_PLUS && !hasLut) return ArmingPlan(ZslProfile.OFF, disarmReason = "lut_missing")
        val ringConfig = resolveRingConfig(eval.profile, burstCount, report.frameBytes, lazyRing, lazyFailed, availMemBytes)
            ?: return ArmingPlan(ZslProfile.OFF, disarmReason = "ring_config")
        val evConfig = if (eval.profile == ZslProfile.HDR_PLUS) {
            resolveHdrTargetEv(underexposureMode, report.minAeCompensationSteps, report.aeCompensationStepEv)
        } else {
            HdrEvConfig(0, 1.0f)
        }
        return ArmingPlan(eval.profile, ringConfig, evConfig, report)
    }
}
