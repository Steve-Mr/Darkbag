package top.maary.darkbag.ui

import top.maary.darkbag.R
import top.maary.darkbag.camera.delegate.ZslProbeState
import top.maary.darkbag.camera.zsl.probe.ZslProbeFailure

/**
 * 探测 sheet 的纯契约（无 Android View 依赖）：原因文案映射、进度阶段、关闭动作决策。
 *
 * 抽到独立文件是为了让 [ZslProbeBottomSheet] 只保留"渲染 + 生命周期"，
 * 同时让这些规则可在无 Dialog 的 JVM 单测里直接断言。
 */
internal object ZslProbeReasonStrings {
    fun resId(failure: ZslProbeFailure): Int = when (failure) {
        ZslProbeFailure.RAW_FPS_LOW -> R.string.zsl_reason_raw_fps_low
        ZslProbeFailure.PREVIEW_DEGRADED -> R.string.zsl_reason_preview_degraded
        ZslProbeFailure.THERMAL_RISK -> R.string.zsl_reason_thermal_risk
        ZslProbeFailure.COPY_UNMEASURED -> R.string.zsl_reason_copy_unmeasured
        else -> R.string.zsl_reason_unsupported
    }
}

/** 进度阶段：窗口固定（基线 1500 ms → 双流 → 恢复，§3.8.3），按已用时间推导而非随机假进度。 */
internal fun zslStageRes(elapsedMs: Long): Int = when {
    elapsedMs < 1_500L -> R.string.zsl_stage_baseline
    elapsedMs < 5_500L -> R.string.zsl_stage_dual
    else -> R.string.zsl_stage_recovery
}

/** sheet 关闭 / 收到状态时应执行的动作（纯函数，便于单测）。 */
internal enum class ZslProbeAction { ABORT, CONSUME, DISMISS, NONE }

internal fun zslProbeActionFor(state: ZslProbeState?): ZslProbeAction = when (state) {
    is ZslProbeState.Running -> ZslProbeAction.ABORT
    is ZslProbeState.Finished -> ZslProbeAction.CONSUME
    ZslProbeState.Idle -> ZslProbeAction.DISMISS
    null -> ZslProbeAction.NONE
}
