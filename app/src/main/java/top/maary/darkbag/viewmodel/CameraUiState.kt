package top.maary.darkbag.viewmodel

import android.net.Uri
import top.maary.darkbag.modes.CaptureMode

enum class CameraUiTier(val key: String, val displayLabel: String) {
    POINT_AND_SHOOT("PS", "P&S"),
    FILM("FILM", "FILM"),
    PRO("PRO", "PRO"),
    CUSTOM("CUSTOM", "CUSTOM");

    fun next(): CameraUiTier = when (this) {
        POINT_AND_SHOOT -> FILM
        FILM -> PRO
        PRO -> CUSTOM
        CUSTOM -> POINT_AND_SHOOT
    }

    companion object {
        fun fromKey(key: String?): CameraUiTier {
            return entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: FILM
        }
    }
}

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
    val isSwitchingLens: Boolean = false,
    val uiTier: CameraUiTier = CameraUiTier.FILM,
    val activeLogName: String = "None",
    val activeLutName: String = "None",
    val underexposureMode: String = "Dynamic (Experimental)",
    val isFlashEnabled: Boolean = false,
    val isMotionPhotoEnabled: Boolean = false,
    val activeManualTab: String? = null,
    val isManualFocus: Boolean = false,
    val isManualIso: Boolean = false,
    val isManualShutter: Boolean = false,
    val isProLinked: Boolean = true
) {
    val isHalfFrameActive: Boolean
        get() = currentMode.isHalfFrame

    val isMultiCameraActive: Boolean
        get() = currentMode.isMultiCamera

    val showDetailedTelemetry: Boolean
        get() = uiTier == CameraUiTier.PRO || uiTier == CameraUiTier.CUSTOM

    val showFilmRecipeRow: Boolean
        get() = uiTier == CameraUiTier.FILM || uiTier == CameraUiTier.PRO || uiTier == CameraUiTier.CUSTOM
}
