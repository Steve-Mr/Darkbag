package top.maary.darkbag.viewmodel

import android.net.Uri
import top.maary.darkbag.modes.CaptureMode

/**
 * 相机 UI 持久可观察状态 (MVI / UDF).
 * 驱动所有控件的可见性、角标、文案、高亮及指示器。
 */
data class CameraUiState(
    val currentMode: CaptureMode = CaptureMode.NORMAL,
    val activeLensId: String = "0",
    val zoomRatio: Float = 1.0f,
    val isProcessing: Boolean = false,
    val pendingTasksCount: Int = 0,
    val pendingForegroundTasksCount: Int = 0,
    val latestThumbnailUri: Uri? = null,
    val latestThumbnailPath: String? = null,
    val halfFrameStep: Int = 0, // 0: 待拍第 1 帧, 1: 待拍第 2 帧
    val isBurstActive: Boolean = false,
    val isHdrPlusEnabled: Boolean = true,
    val shutterDotRotation: Float = 0f,
    val isSwitchingLens: Boolean = false
) {
    val isHalfFrameActive: Boolean
        get() = currentMode.isHalfFrame

    val isMultiCameraActive: Boolean
        get() = currentMode.isMultiCamera
}
