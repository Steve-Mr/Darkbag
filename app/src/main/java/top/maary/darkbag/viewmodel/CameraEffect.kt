package top.maary.darkbag.viewmodel

/**
 * 相机瞬态单次事件 (MVI Single-Live-Event / Side Effects).
 * 驱动动效、触感震动、音效、Toast 等单次消费行为，屏幕旋转或配置变更不重放。
 */
sealed interface CameraEffect {
    data object ShutterBlackout : CameraEffect
    data object PlayShutterClick : CameraEffect
    data object PerformHapticFeedback : CameraEffect
    data class ShowToast(val message: String) : CameraEffect
    data class SetShutterProgress(val progress: Float) : CameraEffect
    data object ResetShutterUi : CameraEffect
    data class AnimateHalfFrame(val isFrame1: Boolean) : CameraEffect
    data object ClearThumbnailPlaceholder : CameraEffect
}
