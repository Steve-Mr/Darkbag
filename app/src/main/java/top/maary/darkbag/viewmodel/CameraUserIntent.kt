package top.maary.darkbag.viewmodel

import android.net.Uri
import top.maary.darkbag.modes.CaptureMode

/**
 * 用户意图 / 操作意图 (MVI User Intents).
 */
sealed interface CameraUserIntent {
    data class ChangeMode(val mode: CaptureMode) : CameraUserIntent
    data class SwitchLens(val lensId: String) : CameraUserIntent
    data class SetZoomRatio(val ratio: Float) : CameraUserIntent
    data class SetHdrPlusEnabled(val enabled: Boolean) : CameraUserIntent
    data class SetBurstActive(val active: Boolean) : CameraUserIntent
    data class UpdateTaskCounts(val totalCount: Int, val foregroundCount: Int) : CameraUserIntent
    data class SetHalfFrameStep(val step: Int) : CameraUserIntent
    data class UpdateThumbnail(val uri: Uri?, val path: String?) : CameraUserIntent
    data class UpdateShutterDotRotation(val rotation: Float) : CameraUserIntent
    data class SetSwitchingLens(val switching: Boolean) : CameraUserIntent
    data class SetUiTier(val tier: CameraUiTier) : CameraUserIntent
    data object CycleUiTier : CameraUserIntent
    data class UpdateColorRecipe(val logName: String, val lutName: String) : CameraUserIntent
    data class SetMotionPhotoEnabled(val enabled: Boolean) : CameraUserIntent
    data class SetFlashEnabled(val enabled: Boolean) : CameraUserIntent
    data class SetUnderexposureMode(val mode: String) : CameraUserIntent
    data class SetActiveManualTab(val tab: String?) : CameraUserIntent
    data class UpdateManualFlags(
        val isManualFocus: Boolean,
        val isManualIso: Boolean,
        val isManualShutter: Boolean,
        val isProLinked: Boolean = true
    ) : CameraUserIntent
}
