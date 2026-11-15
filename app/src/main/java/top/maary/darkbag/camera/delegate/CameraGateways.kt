package top.maary.darkbag.camera.delegate

import android.content.Context
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.net.Uri
import android.view.ViewGroup
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.CoroutineScope
import top.maary.darkbag.camera.session.CameraSessionArbiter
import top.maary.darkbag.camera.state.CameraRuntimeStateHolder
import top.maary.darkbag.databinding.CameraUiContainerBinding
import top.maary.darkbag.databinding.FragmentCameraBinding
import top.maary.darkbag.modes.CaptureMode
import top.maary.darkbag.modes.CaptureModeCoordinator
import top.maary.darkbag.repository.ImageRepository
import top.maary.darkbag.utils.CameraRepository
import top.maary.darkbag.utils.LocationHelper
import top.maary.darkbag.utils.LutManager
import top.maary.darkbag.viewmodel.CameraViewModel

/**
 * Gateway 1: Platform environment, repositories, shared [CameraRuntimeStateHolder],
 * and unified [CameraSessionArbiter].
 */
interface CameraEnvironmentHost {
    val hostContext: Context
    val hostActivity: FragmentActivity?
    val isHostAdded: Boolean
    val lifecycleScope: CoroutineScope
    val cameraViewModel: CameraViewModel
    val runtimeState: CameraRuntimeStateHolder
    val sessionArbiter: CameraSessionArbiter
    val camera2Manager: CameraManager
    val cameraRepository: CameraRepository
    val imageRepository: ImageRepository
    val lutManager: LutManager
    val locationHelper: LocationHelper
    val displayRotation: Int
    val isHdrPlusEnabled: Boolean
        get() = cameraViewModel.uiState.value.isHdrPlusEnabled
    val shouldMirror: Boolean

    fun shouldShowAudioPermissionRationale(): Boolean
    fun requestAudioPermission()
}

/**
 * Gateway 2: ViewBindings, visual overlays, telemetry formatting, gestures, and thumbnail UI.
 */
interface CameraUiHost {
    val fragmentCameraBinding: FragmentCameraBinding?
    val cameraUiContainerBinding: CameraUiContainerBinding?
    val rootViewGroup: ViewGroup?
    val isFloatingOverlayOpen: Boolean

    fun applyUIVisibility()
    fun dismissFloatingOverlays()
    fun updateProInfoBar()
    fun updateDialPanel()
    fun formatFocusDist(dist: Float): String
    fun formatShutterTime(nanos: Long): String
    fun showShutterBlackout()
    fun showProcessingAnimation()
    fun hideProcessingAnimation()
    fun resetBurstUi()
    fun updateCurrentThumbnail(uri: Uri?)
    fun setGalleryThumbnail(filename: String?)
    fun onSessionConfigured()
    fun triggerTapToFocus(x: Float, y: Float)
    fun unlockFocusAndMetering()
}

/**
 * Gateway 3: Inter-controller coordination for shooting modes, Camera2 request routing,
 * orientation, Motion Photo encoder lifecycle, and half-frame persistence.
 */
interface CameraCoordinatorHost {
    val activeCoordinator: CaptureModeCoordinator?
    val effectiveCaptureMode: CaptureMode
    val halfFrameStep: Int

    fun ensureActiveCoordinator(): CaptureModeCoordinator?
    fun bindCameraUseCases()
    fun applyCameraControls()
    fun applyManualSettingsToRequest(request: CaptureRequest.Builder, isHdrBurst: Boolean = false)
    fun applyPreviewFocusAndExposureSettings(builder: CaptureRequest.Builder)
    fun handleCaptureResult(result: TotalCaptureResult)
    fun getCombinedOrientation(): Int
    fun updateMotionPhotoEncoder()
    fun triggerAutoBurst()
    fun onCaptureModeCycled(modeChanged: Boolean)
    fun readScopedHalfFrameState(requireFileForStep1: Boolean = false)
    fun writeScopedHalfFrameStep(
        step: Int,
        captureTimeMillis: Long? = null,
        digitalGain: Float = 1.0f,
        flareType: Int = -1
    )
    fun getDigitalGainAndUpdateStep(timestamp: Long, isFrame1Trigger: Boolean, shutterClick: Long?): Float
}
