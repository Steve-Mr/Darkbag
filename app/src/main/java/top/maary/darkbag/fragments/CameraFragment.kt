package top.maary.darkbag.fragments

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.util.Range
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.viewModels
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope as ownerLifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.Navigation
import androidx.window.layout.WindowMetricsCalculator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import top.maary.darkbag.MainApplication
import top.maary.darkbag.R
import top.maary.darkbag.camera.ConcurrentFrontCameraManager
import top.maary.darkbag.camera.MultiCameraCaptureManager
import top.maary.darkbag.camera.delegate.CameraFocusMeteringController
import top.maary.darkbag.camera.delegate.CameraFocusMeteringHost
import top.maary.darkbag.camera.delegate.CameraGestureDelegate
import top.maary.darkbag.camera.delegate.CameraGestureHost
import top.maary.darkbag.camera.delegate.CameraHalfFrameStateController
import top.maary.darkbag.camera.delegate.CameraHalfFrameStateHost
import top.maary.darkbag.camera.delegate.CameraHdrPlusController
import top.maary.darkbag.camera.delegate.CameraHdrPlusHost
import top.maary.darkbag.camera.delegate.CameraLensController
import top.maary.darkbag.camera.delegate.CameraLensHost
import top.maary.darkbag.camera.delegate.CameraLutController
import top.maary.darkbag.camera.delegate.CameraLutHost
import top.maary.darkbag.camera.delegate.CameraModeCollaborators
import top.maary.darkbag.camera.delegate.CameraModeController
import top.maary.darkbag.camera.delegate.CameraModeHost
import top.maary.darkbag.camera.delegate.CameraMotionPhotoController
import top.maary.darkbag.camera.delegate.CameraMotionPhotoHost
import top.maary.darkbag.camera.delegate.CameraOverlayController
import top.maary.darkbag.camera.delegate.CameraOverlayHost
import top.maary.darkbag.camera.delegate.CameraShutterController
import top.maary.darkbag.camera.delegate.CameraShutterHost
import top.maary.darkbag.camera.delegate.CameraThumbnailController
import top.maary.darkbag.camera.delegate.CameraThumbnailHost
import top.maary.darkbag.camera.delegate.CameraTierController
import top.maary.darkbag.camera.delegate.CameraTierHost
import top.maary.darkbag.camera.dispatch.CameraCapturePipelineDispatcher
import top.maary.darkbag.camera.dispatch.CameraCapturePipelineHost
import top.maary.darkbag.camera.dispatch.CameraMultiCaptureDispatcher
import top.maary.darkbag.camera.dispatch.CameraMultiCaptureHost
import top.maary.darkbag.camera.session.Camera2SessionController
import top.maary.darkbag.camera.session.CameraHardwareStreamController
import top.maary.darkbag.camera.session.CameraHardwareStreamHost
import top.maary.darkbag.camera.video.CameraVideoController
import top.maary.darkbag.camera.video.CameraVideoHost
import top.maary.darkbag.databinding.CameraUiContainerBinding
import top.maary.darkbag.databinding.FragmentCameraBinding
import top.maary.darkbag.modes.CaptureMode
import top.maary.darkbag.modes.CaptureModeCoordinator
import top.maary.darkbag.processor.ColorProcessor
import top.maary.darkbag.processor.HdrPlusRequestManager
import top.maary.darkbag.processor.HdrPlusStreamingBurst
import top.maary.darkbag.processor.LutSurfaceProcessor
import top.maary.darkbag.processor.StreamingBufferPool
import top.maary.darkbag.repository.ImageRepository
import top.maary.darkbag.ui.CameraBentoDeckView
import top.maary.darkbag.utils.CameraRepository
import top.maary.darkbag.utils.ExposureUtils
import top.maary.darkbag.utils.LensInfo
import top.maary.darkbag.utils.LocationHelper
import top.maary.darkbag.utils.LutManager
import top.maary.darkbag.viewmodel.CameraEffect
import top.maary.darkbag.viewmodel.CameraUiTier
import top.maary.darkbag.viewmodel.CameraViewModel
import java.util.Collections

/**
 * Main camera fragment. Serves as a passive View coordinator observing CameraViewModel.uiState
 * and delegating hardware streams, pipeline dispatching, lens switching, LUTs, shooting modes,
 * shutter, HDR+/flash, Motion Photo, half-frame session state, thumbnails, and tap-to-focus
 * to dedicated Controllers and Delegates.
 */
class CameraFragment : Fragment(),
    CameraHardwareStreamHost,
    CameraCapturePipelineHost,
    CameraMultiCaptureHost,
    CameraVideoHost,
    CameraFocusMeteringHost,
    CameraLensHost,
    CameraLutHost,
    CameraModeHost,
    CameraHalfFrameStateHost,
    CameraHdrPlusHost,
    CameraMotionPhotoHost,
    CameraShutterHost,
    CameraThumbnailHost {

    private var _fragmentCameraBinding: FragmentCameraBinding? = null
    override val fragmentCameraBinding get() = _fragmentCameraBinding

    override var cameraUiContainerBinding: CameraUiContainerBinding? = null

    private var displayId: Int = -1
    override var lensFacing: Int = CameraCharacteristics.LENS_FACING_BACK
    private lateinit var windowMetricsCalculator: WindowMetricsCalculator

    // Camera2 State & Controllers
    override val sessionController = Camera2SessionController()
    override val cameraViewModel: CameraViewModel by viewModels()

    override val camera2Device: CameraDevice?
        get() = sessionController.cameraDevice
    override val camera2Session: android.hardware.camera2.CameraCaptureSession?
        get() = sessionController.captureSession
    override val camera2Handler: Handler?
        get() = sessionController.backgroundHandler

    override var camera2PreviewSurface: Surface? = null
    override var rawImageReader: ImageReader? = null
    override var analysisImageReader: ImageReader? = null
    override val camera2Manager by lazy {
        requireContext().getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    override var lutProcessor: LutSurfaceProcessor? = null
    private lateinit var _lutManager: LutManager
    override val lutManager: LutManager get() = _lutManager

    private lateinit var _cameraRepository: CameraRepository
    override val cameraRepository: CameraRepository get() = _cameraRepository

    private lateinit var _imageRepository: ImageRepository
    override val imageRepository: ImageRepository get() = _imageRepository

    override var availableLenses: List<LensInfo> = emptyList()
    override var currentLens: LensInfo? = null

    private val displayManager by lazy {
        requireContext().getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    }

    // Manual Control State
    override var isManualFocus = false
    override var isManualIso = false
    override var isManualShutter = false
    override val isManualExposure: Boolean
        get() = isManualIso || isManualShutter
    override var lastClippingRatio: Double = 0.0
    override val activeManualTab: String?
        get() = tierController.activeManualTab
    override var focusMeteringRegion: MeteringRectangle? = null
    override var exposureMeteringRegion: MeteringRectangle? = null

    // Live Telemetry
    override var liveIso: Int = 100
    override var liveExposureTime: Long = 10_000_000L
    override var liveFocusDistance: Float = 0.0f
    override var lastTelemetryUpdateMs: Long = 0L

    // Flash & HDR+
    override var isFlashEnabled = false
    override val isHdrPlusEnabled: Boolean
        get() = cameraViewModel.uiState.value.isHdrPlusEnabled
    override var lastHdrPlusConfig: ExposureUtils.ExposureConfig? = null

    private val _locationHelper by lazy { LocationHelper(requireContext()) }
    override val locationHelper: LocationHelper get() = _locationHelper

    override val shouldMirror: Boolean
        get() = lensFacing == CameraCharacteristics.LENS_FACING_FRONT &&
                requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
                    .getBoolean(SettingsFragment.KEY_MIRROR_FRONT_CAMERA, true)

    override var isBurstActive = false
    override var burstStartTime: Long = 0L
    override var isSwitchingLens = false
    override var camera2RetryCount = 0

    override val rawVideoSessionManager = top.maary.darkbag.rawvideo.RawVideoSessionManager()
    override var isVideoSaving = false

    override var hdrPlusStreamingBurstHelper: HdrPlusStreamingBurst? = null
    override var pendingMotionPhotoTask: CompletableDeferred<Pair<String?, Long>>? = null

    var minFocusDistance = 0.0f
    override var isoRange: Range<Int>? = null
    override var exposureTimeRange: Range<Long>? = null
    var evRange: Range<Int>? = null
    override var isRawSupported = false
    override var isOisSupported = false
    override var isHdrOisEnabledPref = true

    override var currentFocusDistance = 0.0f
    override var currentIso = 100
    override var currentExposureTime = 10_000_000L
    override var currentEvIndex = 0

    override val isProcessing: Boolean
        get() = isBurstActive || HdrPlusRequestManager.pendingForegroundTasksCount.value > 0 || isVideoSaving

    override val processingSemaphore: Semaphore = object : Semaphore {
        override val availablePermits: Int get() = 3
        override suspend fun acquire() {}
        override fun release() {}
        override fun tryAcquire(): Boolean = true
    }
    override val captureResults = Collections.synchronizedMap(mutableMapOf<Long, TotalCaptureResult>())
    override val captureResultFlow = MutableSharedFlow<TotalCaptureResult>(replay = 10, extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    // Half-frame & Multi-Camera State
    override var pendingVfSnapshot: Bitmap? = null
    override var isMultiCameraManualLinked = true
    override var multiCameraManager: MultiCameraCaptureManager? = null
    override var concurrentFrontCameraManager: ConcurrentFrontCameraManager? = null
    override var isFrontPipActive = false
    var isHalfFrameUiAnimating = false

    override val hostContext: Context get() = requireContext()
    override val hostActivity: FragmentActivity? get() = activity
    override val isHostAdded: Boolean get() = isAdded
    override val lifecycleScope: CoroutineScope get() = (this as LifecycleOwner).ownerLifecycleScope
    override val effectiveCaptureMode: CaptureMode get() = modeController.effectiveMode
    override val activeCoordinator: CaptureModeCoordinator? get() = modeController.activeCoordinator

    override var transientLensLabel: String? = null
    override var deviceOrientationDegrees = 0
    override val displayRotation: Int
        get() = view?.display?.rotation ?: Surface.ROTATION_0

    // Controller Instances
    val streamController: CameraHardwareStreamController by lazy { CameraHardwareStreamController(this) }
    val pipelineDispatcher: CameraCapturePipelineDispatcher by lazy { CameraCapturePipelineDispatcher(this) }
    val multiCaptureDispatcher: CameraMultiCaptureDispatcher by lazy { CameraMultiCaptureDispatcher(this) }
    val videoController: CameraVideoController by lazy { CameraVideoController(this) }
    val lensController: CameraLensController by lazy { CameraLensController(this) }
    val lutController: CameraLutController by lazy { CameraLutController(this) }
    val focusMeteringController: CameraFocusMeteringController by lazy { CameraFocusMeteringController(this, gestureDelegate) }
    val halfFrameStateController: CameraHalfFrameStateController by lazy { CameraHalfFrameStateController(this) }
    val thumbnailController: CameraThumbnailController by lazy { CameraThumbnailController(this) }
    val motionPhotoController: CameraMotionPhotoController by lazy { CameraMotionPhotoController(this) }
    val shutterController: CameraShutterController by lazy {
        CameraShutterController(this, overlayController, focusMeteringController, videoController)
    }
    val hdrPlusController: CameraHdrPlusController by lazy { CameraHdrPlusController(this, lutController, lensController) }
    val modeController: CameraModeController by lazy {
        CameraModeController(
            this,
            CameraModeCollaborators(
                overlay = overlayController,
                shutter = shutterController,
                thumbnail = thumbnailController,
                motionPhoto = motionPhotoController,
                pipeline = pipelineDispatcher,
                multiCapture = multiCaptureDispatcher,
                stream = streamController,
                lens = lensController
            )
        )
    }

    private val gestureHost = object : CameraGestureHost {
        override val isFloatingOverlayOpen: Boolean
            get() = (cameraUiContainerBinding?.lutListContainer?.visibility == View.VISIBLE ||
                    cameraUiContainerBinding?.manualControlsRoot?.visibility == View.VISIBLE ||
                    tierController.activeManualTab != null)

        override fun dismissFloatingOverlays() {
            tierController.dismissFloatingOverlays()
        }

        override fun triggerTapToFocus(x: Float, y: Float) {
            focusMeteringController.triggerTapToFocusCamera2(x, y)
        }

        override fun unlockFocusAndMetering() {
            focusMeteringController.unlockFocusAndMetering()
        }
    }
    val gestureDelegate: CameraGestureDelegate by lazy { CameraGestureDelegate(requireContext(), gestureHost) }

    private val tierHost = object : CameraTierHost {
        override val context: Context get() = requireContext()
        override val binding: CameraUiContainerBinding? get() = cameraUiContainerBinding
        override val rootViewGroup: ViewGroup? get() = _fragmentCameraBinding?.root as? ViewGroup
        override val activeCoordinator: CaptureModeCoordinator? get() = this@CameraFragment.activeCoordinator
        override val cameraViewModel: CameraViewModel get() = this@CameraFragment.cameraViewModel
        override val lutProcessor: LutSurfaceProcessor? get() = this@CameraFragment.lutProcessor
        override val isFrontPipActive: Boolean get() = this@CameraFragment.isFrontPipActive
        override val hasMultipleAvailableLenses: Boolean get() = availableLenses.size > 1
        override val hasMultiCameraManager: Boolean get() = multiCameraManager != null
        override val minFocusDistance: Float get() = this@CameraFragment.minFocusDistance
        override val isoRange: Range<Int>? get() = this@CameraFragment.isoRange
        override val exposureTimeRange: Range<Long>? get() = this@CameraFragment.exposureTimeRange
        override val evRange: Range<Int>? get() = this@CameraFragment.evRange

        override var currentFocusDistance: Float
            get() = this@CameraFragment.currentFocusDistance
            set(v) { this@CameraFragment.currentFocusDistance = v }

        override var liveFocusDistance: Float
            get() = this@CameraFragment.liveFocusDistance
            set(v) { this@CameraFragment.liveFocusDistance = v }

        override var isManualFocus: Boolean
            get() = this@CameraFragment.isManualFocus
            set(v) { this@CameraFragment.isManualFocus = v }

        override var currentIso: Int
            get() = this@CameraFragment.currentIso
            set(v) { this@CameraFragment.currentIso = v }

        override var liveIso: Int
            get() = this@CameraFragment.liveIso
            set(v) { this@CameraFragment.liveIso = v }

        override var isManualIso: Boolean
            get() = this@CameraFragment.isManualIso
            set(v) { this@CameraFragment.isManualIso = v }

        override var currentExposureTime: Long
            get() = this@CameraFragment.currentExposureTime
            set(v) { this@CameraFragment.currentExposureTime = v }

        override var liveExposureTime: Long
            get() = this@CameraFragment.liveExposureTime
            set(v) { this@CameraFragment.liveExposureTime = v }

        override var isManualShutter: Boolean
            get() = this@CameraFragment.isManualShutter
            set(v) { this@CameraFragment.isManualShutter = v }

        override var currentEvIndex: Int
            get() = this@CameraFragment.currentEvIndex
            set(v) { this@CameraFragment.currentEvIndex = v }

        override var isMultiCameraManualLinked: Boolean
            get() = this@CameraFragment.isMultiCameraManualLinked
            set(v) { this@CameraFragment.isMultiCameraManualLinked = v }

        override var hasFocusMeteringLock: Boolean
            get() = focusMeteringRegion != null
            set(_) {}

        override fun applyCameraControls() {
            this@CameraFragment.applyCameraControls()
        }

        override fun clearFocusMetering() {
            focusMeteringRegion = null
        }

        override fun clearExposureMetering() {
            exposureMeteringRegion = null
        }
    }
    val tierController by lazy { CameraTierController(tierHost) }

    private val overlayHost = object : CameraOverlayHost {
        override val context: Context get() = requireContext()
        override val fragmentCameraBinding: FragmentCameraBinding? get() = _fragmentCameraBinding
        override val cameraUiContainerBinding: CameraUiContainerBinding? get() = this@CameraFragment.cameraUiContainerBinding
        override val activeCoordinator: CaptureModeCoordinator? get() = this@CameraFragment.activeCoordinator
        override val cameraViewModel: CameraViewModel get() = this@CameraFragment.cameraViewModel
        override val isProcessing: Boolean get() = this@CameraFragment.isProcessing
        override val halfFrameStep: Int get() = halfFrameStateController.step
        override var isHalfFrameUiAnimating: Boolean
            get() = this@CameraFragment.isHalfFrameUiAnimating
            set(v) { this@CameraFragment.isHalfFrameUiAnimating = v }
        override var pendingVfSnapshot: Bitmap?
            get() = this@CameraFragment.pendingVfSnapshot
            set(v) { this@CameraFragment.pendingVfSnapshot = v }

        override fun readScopedHalfFrameState(prefs: SharedPreferences, requireFileForStep1: Boolean) {
            halfFrameStateController.readState(requireFileForStep1)
        }
    }
    val overlayController by lazy { CameraOverlayController(overlayHost) }

    private val requestAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Toast.makeText(context, R.string.toast_audio_permission_granted, Toast.LENGTH_SHORT).show()
        } else {
            if (!shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
                videoController.showAudioPermissionSettingsDialog()
            } else {
                Toast.makeText(context, R.string.error_mic_permission_silent_video, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private var bindJob: Job? = null

    // Host Contract Implementations
    override fun formatFocusDist(dist: Float): String = tierController.formatFocusDist(dist)
    override fun formatShutterTime(nanos: Long): String = tierController.formatShutterTime(nanos)

    override fun onSessionConfigured() {
        isSwitchingLens = false
        _fragmentCameraBinding?.viewFinder?.animate()?.alpha(1f)?.setDuration(200L)?.start()
    }

    override fun applyManualSettingsToRequest(request: CaptureRequest.Builder, isHdrBurst: Boolean) {
        streamController.applyManualSettingsToRequest(request, isHdrBurst)
    }

    override fun applyManualSettingsToRequest(request: CaptureRequest.Builder) {
        streamController.applyManualSettingsToRequest(request, false)
    }

    override fun handleCaptureResult(result: TotalCaptureResult) {
        streamController.handleCaptureResult(result)
    }

    override fun applyCameraControls() {
        streamController.updateCamera2RepeatingRequest()
    }

    override fun updateProInfoBar() {
        tierController.updateProInfoBar()
    }

    override fun updateDialPanel() {
        tierController.updateDialPanel()
    }

    override fun dismissFloatingOverlays() {
        tierController.dismissFloatingOverlays()
    }

    override fun showProcessingAnimation() {
        overlayController.showProcessingAnimation()
    }

    override fun hideProcessingAnimation() {
        overlayController.hideProcessingAnimation()
    }

    override fun updateCurrentThumbnail(uri: Uri?) {
        thumbnailController.updateCurrentThumbnail(uri)
    }

    override fun setGalleryThumbnail(filename: String?) {
        thumbnailController.setGalleryThumbnail(filename)
    }

    override fun showShutterBlackout() {
        overlayController.showShutterBlackout()
    }

    override fun updateMotionPhotoEncoder() {
        motionPhotoController.updateEncoder()
    }

    override fun triggerAutoBurst(prefs: SharedPreferences) {
        pipelineDispatcher.triggerAutoBurst(prefs)
    }

    override fun ensureActiveCoordinator(): CaptureModeCoordinator? {
        return modeController.activeCoordinator ?: run {
            modeController.resolveActiveCaptureMode()
            modeController.activeCoordinator
        }
    }

    override fun shouldShowAudioPermissionRationale(): Boolean {
        return shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
    }

    override fun requestAudioPermission() {
        requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    override fun onCaptureModeCycled(modeChanged: Boolean) {
        halfFrameStateController.readState(requireFileForStep1 = true)
        overlayController.updateHalfFrameUI()
        shutterController.updateShutterOrientation()
        motionPhotoController.updateEncoder()
        tierController.updateProInfoBar()
        cameraUiContainerBinding?.modeSwitchButton?.let { modeController.updateModeSwitchIcon(it) }
        lensController.updateCameraSwitchButton()
        lensController.initLensControls()
        if (modeChanged) {
            bindCameraUseCases()
        }
    }

    override fun getCombinedOrientation(): Int {
        val sensorOrientation = try {
            val lens = currentLens
            val targetId = lens?.id ?: if (lensFacing == CameraCharacteristics.LENS_FACING_BACK) "0" else "1"
            CameraRepository.getCharacteristics(camera2Manager, targetId)
                .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        } catch (e: Exception) { 0 }

        val effectiveDegrees = activeCoordinator?.getEffectiveOrientation(deviceOrientationDegrees)
            ?: deviceOrientationDegrees

        return if (lensFacing == CameraCharacteristics.LENS_FACING_FRONT) {
            (sensorOrientation - effectiveDegrees + 360) % 360
        } else {
            (sensorOrientation + effectiveDegrees) % 360
        }
    }

    override fun writeScopedHalfFrameStep(
        prefs: SharedPreferences,
        step: Int,
        captureTimeMillis: Long?,
        digitalGain: Float,
        flareType: Int
    ) {
        halfFrameStateController.writeStep(step, captureTimeMillis, digitalGain, flareType)
    }

    override fun getDigitalGainAndUpdateStep(
        timestamp: Long,
        isFrame1Trigger: Boolean,
        shutterClick: Long?
    ): Float {
        return halfFrameStateController.getDigitalGainAndUpdateStep(timestamp, isFrame1Trigger, shutterClick)
    }

    override fun resetBurstUi() {
        shutterController.resetBurstUi()
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) = view?.let { view ->
            if (displayId == this@CameraFragment.displayId) {
                Log.d(TAG, "Rotation changed: ${view.display.rotation}")
            }
        } ?: Unit
    }

    override fun onStart() {
        super.onStart()
        shutterController.enableOrientationTracking()
        hdrPlusController.updateHdrPlusConstraints()
    }

    override fun onStop() {
        super.onStop()
        if (rawVideoSessionManager.recording) {
            videoController.stopRawVideoRecording()
        }
        locationHelper.stopListening()
        shutterController.disableOrientationTracking()
        motionPhotoController.release()

        lifecycleScope.launch(Dispatchers.Main.immediate + NonCancellable) {
            streamController.closeCamera2()
            sessionController.release()
        }
    }

    override fun onResume() {
        super.onResume()
        (activity as? top.maary.darkbag.MainActivity)?.setFloatingToolbarForcedHidden(true)
        if (!PermissionsFragment.hasPermissions(requireContext())) {
            Navigation.findNavController(requireActivity(), R.id.fragment_container).navigate(
                CameraFragmentDirections.actionCameraToPermissions()
            )
            return
        }

        hdrPlusController.updateHdrPlusConstraints()
        val prefs = requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        val previousMode = activeCoordinator?.mode
        val activeMode = modeController.resolveActiveCaptureMode()

        val modeChanged = (previousMode != activeMode)
        if (modeChanged || camera2Device == null || modeController.isMultiCameraActive) {
            if (_fragmentCameraBinding?.viewFinderContainer?.isLaidOut == true) {
                bindCameraUseCases()
            } else {
                _fragmentCameraBinding?.viewFinderContainer?.post {
                    if (isAdded && _fragmentCameraBinding != null) {
                        bindCameraUseCases()
                    }
                }
            }
        }

        if (prefs.getBoolean(SettingsFragment.KEY_SAVE_LOCATION, false)) {
            locationHelper.startListening()
        } else {
            locationHelper.stopListening()
        }

        val savedTier = CameraUiTier.fromKey(prefs.getString(CameraBentoDeckView.PREF_KEY_UI_TIER, CameraUiTier.FILM.key))
        if (cameraViewModel.uiState.value.uiTier != savedTier) {
            cameraViewModel.setUiTier(savedTier)
            tierController.updateTierUi(savedTier)
        }
        halfFrameStateController.readState(requireFileForStep1 = true)
        updateCameraUi()
        overlayController.updateHalfFrameUI()
        cameraUiContainerBinding?.modeSwitchButton?.let { modeController.updateModeSwitchIcon(it) }
        applyUIVisibility()
        motionPhotoController.updateEncoder()
    }

    override fun onDestroyView() {
        (activity as? top.maary.darkbag.MainActivity)?.setFloatingToolbarForcedHidden(false)
        bindJob?.cancel()
        bindJob = null
        _fragmentCameraBinding?.viewFinderBlackout?.animate()?.cancel()
        cameraUiContainerBinding = null
        _fragmentCameraBinding = null
        super.onDestroyView()

        focusMeteringController.onDestroy()
        lutController.onDestroy()
        lensController.onDestroy()
        shutterController.onDestroy()

        lifecycleScope.launch(Dispatchers.Main.immediate + NonCancellable) {
            streamController.closeCamera2()
            sessionController.release()
        }

        motionPhotoController.release()
        lutProcessor?.release()
        lutProcessor = null

        hdrPlusStreamingBurstHelper?.abort()
        hdrPlusStreamingBurstHelper = null
        pipelineDispatcher.stopProcessingWorker()
        displayManager.unregisterDisplayListener(displayListener)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        Log.w(TAG, "onLowMemory received: clearing GPU and CPU raw processing caches")
        StreamingBufferPool.clear()
        ColorProcessor.clearSabreTexturePoolAsync()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _fragmentCameraBinding = FragmentCameraBinding.inflate(inflater, container, false)
        return fragmentCameraBinding!!.root
    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _fragmentCameraBinding = FragmentCameraBinding.bind(view)

        ColorProcessor.warmupGpuAsync(requireContext())
        val prefs = requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)

        val onBackPressedCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val uiBinding = cameraUiContainerBinding
                if (uiBinding?.manualControlsRoot?.visibility == View.VISIBLE ||
                    uiBinding?.lutListContainer?.visibility == View.VISIBLE ||
                    activeManualTab != null
                ) {
                    dismissFloatingOverlays()
                } else {
                    isEnabled = false
                    requireActivity().onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, onBackPressedCallback)

        viewLifecycleOwner.ownerLifecycleScope.launch {
            (requireContext().applicationContext as MainApplication).keyEventFlow.collect { event ->
                if (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                    cameraUiContainerBinding?.cameraCaptureButton?.simulateClick()
                }
            }
        }

        displayManager.registerDisplayListener(displayListener, null)
        windowMetricsCalculator = WindowMetricsCalculator.getOrCreate()
        _lutManager = LutManager(requireContext())
        _cameraRepository = CameraRepository(requireContext())
        _imageRepository = ImageRepository(requireContext())

        hdrPlusController.restorePersistedState()
        hdrPlusController.updateHdrPlusUi()
        hdrPlusController.updateHdrPlusConstraints()

        modeController.resolveActiveCaptureMode()
        halfFrameStateController.readState(requireFileForStep1 = true)

        overlayController.updateHalfFrameUI()
        shutterController.updateShutterOrientation()
        _fragmentCameraBinding?.viewFinderContainer?.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (modeController.isHalfFrameActive && !isHalfFrameUiAnimating) {
                overlayController.updateHalfFrameUI()
            }
        }

        viewLifecycleOwner.ownerLifecycleScope.launch {
            ColorProcessor.backgroundSaveFlow.collect { event ->
                (requireContext().applicationContext as MainApplication).applicationScope.launch(Dispatchers.IO) {
                    try {
                        if (event.targetUri != null) {
                            withContext(Dispatchers.Main) {
                                imageRepository.invalidateCache()
                                prefs.edit().putString(SettingsFragment.KEY_LAST_CAPTURE_URI, event.targetUri).apply()
                                thumbnailController.setGalleryThumbnail(event.targetUri)
                                overlayController.updateProcessingAnimationUi()
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Background UI update failed for ${event.baseName}", e)
                    }
                }
            }
        }

        viewLifecycleOwner.ownerLifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                launch {
                    HdrPlusRequestManager.pendingForegroundTasksCount.collect { foregroundCount ->
                        cameraViewModel.setTaskCounts(
                            totalCount = HdrPlusRequestManager.pendingTasksCount.value,
                            foregroundCount = foregroundCount
                        )
                        overlayController.updateProcessingAnimationUi()
                    }
                }
                launch {
                    cameraViewModel.uiState.collect { state ->
                        cameraUiContainerBinding?.cameraCaptureButton?.setDotRotation(state.shutterDotRotation)
                    }
                }
                launch {
                    cameraViewModel.uiState
                        .map { Pair(it.activeLogName, it.activeLutName) }
                        .distinctUntilChanged()
                        .collect { (log, lut) ->
                            val cleanLut = lut.takeIf { !it.equals("None", ignoreCase = true) }
                            cameraUiContainerBinding?.let { binding ->
                                CameraBentoDeckView.bindRecipeRow(binding, log, cleanLut)
                            }
                            lutController.updateLiveLut(log, cleanLut)
                        }
                }
                launch {
                    cameraViewModel.effects.collect { effect ->
                        when (effect) {
                            is CameraEffect.ShutterBlackout -> overlayController.showShutterBlackout()
                            is CameraEffect.ResetShutterUi -> shutterController.resetBurstUi()
                            is CameraEffect.SetShutterProgress -> cameraUiContainerBinding?.cameraCaptureButton?.setProgress(effect.progress)
                            is CameraEffect.ShowToast -> Toast.makeText(context, effect.message, Toast.LENGTH_SHORT).show()
                            is CameraEffect.AnimateHalfFrame -> overlayController.updateHalfFrameUI(animate = true)
                            is CameraEffect.ClearThumbnailPlaceholder -> {
                                cameraUiContainerBinding?.photoViewButton?.visibility = View.VISIBLE
                                thumbnailController.setGalleryThumbnail(null)
                            }
                            is CameraEffect.PerformHapticFeedback -> view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            is CameraEffect.PlayShutterClick -> {}
                        }
                    }
                }
                launch {
                    ColorProcessor.halfFrameFlow.collect {
                        overlayController.updateHalfFrameUI(animate = true)
                    }
                }
            }
        }

        val app = requireContext().applicationContext as MainApplication
        pipelineDispatcher.startProcessingWorker(app.applicationScope)

        _fragmentCameraBinding?.viewFinder?.post {
            if (!isAdded || _fragmentCameraBinding == null) return@post
            displayId = _fragmentCameraBinding?.viewFinder?.display?.displayId ?: -1
            if (lutProcessor == null) {
                lutProcessor = LutSurfaceProcessor()
                motionPhotoController.updateEncoder()
            }
            lutController.setupViewFinderBinding()
            _fragmentCameraBinding?.viewFinderStage?.let { focusMeteringController.setupTapToFocus(it) }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateCameraUi()
        lensController.initLensControls()
        tierController.updateTierUi()
        overlayController.updateHalfFrameUI()
    }

    override fun bindCameraUseCases() {
        bindJob?.cancel()
        bindJob = lifecycleScope.launch {
            withContext(Dispatchers.Default) {
                lensController.refreshLenses()
            }
            streamController.closeCamera2()
            if (!isBurstActive && !isProcessing && HdrPlusRequestManager.pendingTasksCount.value == 0) {
                StreamingBufferPool.clear()
            }
            bindCameraUseCasesInternal()
        }
    }

    private suspend fun bindCameraUseCasesInternal() {
        focusMeteringRegion = null
        exposureMeteringRegion = null

        val targetId = currentLens?.id ?: if (lensFacing == CameraCharacteristics.LENS_FACING_BACK) "0" else "1"

        try {
            val snapshot = streamController.readCameraCharacteristics(targetId)
            isoRange = snapshot.isoRange
            exposureTimeRange = snapshot.exposureTimeRange
            minFocusDistance = snapshot.minFocusDistance
            evRange = snapshot.evRange
            isRawSupported = snapshot.isRawSupported
            isOisSupported = snapshot.isOisSupported
            isHdrOisEnabledPref = snapshot.isHdrOisEnabledPref

            isoRange?.let { currentIso = currentIso.coerceIn(it.lower, it.upper) }
            exposureTimeRange?.let { currentExposureTime = currentExposureTime.coerceIn(it.lower, it.upper) }

            lifecycleScope.launch(Dispatchers.Main) {
                if (activeManualTab != null) {
                    tierController.updateDialPanel()
                }
                tierController.updateProInfoBar()
                if (isRawSupported) {
                    cameraUiContainerBinding?.hdrPlusSwitch?.visibility = View.VISIBLE
                    hdrPlusController.updateHdrPlusUi()
                } else {
                    cameraUiContainerBinding?.hdrPlusSwitch?.visibility = View.GONE
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch camera characteristics", e)
        }

        val metrics = windowMetricsCalculator.computeCurrentWindowMetrics(requireActivity()).bounds
        overlayController.syncViewFinderAspectRatio(metrics.width() < metrics.height())

        if (activeCoordinator?.bindCameraSession() == true) {
            return
        }

        Log.d(TAG, "Switching to Camera2 Engine for lens: ${currentLens?.name ?: targetId}")
        lensController.initLensControls()
        hdrPlusController.updateHdrPlusConstraints()
        overlayController.updateHalfFrameUI()

        delay(300)
        streamController.openCamera2(currentLens?.id ?: targetId)
    }

    private fun updateCameraUi() {
        val root = _fragmentCameraBinding?.root as? ConstraintLayout ?: return

        val viewsToRemove = mutableListOf<View>()
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            if (child.id != R.id.viewFinderContainer) {
                viewsToRemove.add(child)
            }
        }
        viewsToRemove.forEach { root.removeView(it) }

        cameraUiContainerBinding = CameraUiContainerBinding.inflate(
            LayoutInflater.from(requireContext()),
            root
        )

        cameraUiContainerBinding?.let {
            CameraBentoDeckView.applyDynamicRowHeights(it)
        }
        overlayController.updateViewFinderConstraints()
        shutterController.updateShutterOrientation()
        thumbnailController.loadLatestThumbnail()

        cameraUiContainerBinding?.root?.let { rootView ->
            var currentBottomInset = 0
            var currentToolbarHeight = 0

            fun updateContainerPadding() {
                rootView.updatePadding(
                    bottom = kotlin.math.max(currentBottomInset, currentToolbarHeight)
                )
            }

            val mainActivity = activity as? top.maary.darkbag.MainActivity
            mainActivity?.let {
                viewLifecycleOwner.ownerLifecycleScope.launch {
                    viewLifecycleOwner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                        it.toolbarHeightFlow.collect { height ->
                            currentToolbarHeight = height
                            updateContainerPadding()
                        }
                    }
                }
            }

            ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, windowInsets ->
                val insets = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.mandatorySystemGestures()
                )
                view.updatePadding(left = insets.left, top = insets.top, right = insets.right)
                currentBottomInset = insets.bottom
                updateContainerPadding()
                overlayController.updateViewFinderConstraints()
                WindowInsetsCompat.CONSUMED
            }
            rootView.requestApplyInsets()
        }

        cameraUiContainerBinding?.touchOverlay?.setOnClickListener {
            dismissFloatingOverlays()
        }

        cameraUiContainerBinding?.settingsButton?.setOnClickListener {
            Navigation.findNavController(requireActivity(), R.id.fragment_container)
                .navigate(CameraFragmentDirections.actionCameraToSettings())
        }

        cameraUiContainerBinding?.btnBentoPlayground?.setOnClickListener {
            val safeActivity = activity ?: return@setOnClickListener
            val navController = Navigation.findNavController(safeActivity, R.id.fragment_container)
            if (navController.currentDestination?.id == R.id.camera_fragment) {
                navController.navigate(R.id.action_camera_to_playground_gallery)
            }
        }

        tierController.bindTierControls(
            onVisibilityRefresh = { applyUIVisibility() },
            onLensRefresh = { lensController.updateLensUI() }
        )

        hdrPlusController.bindFlashButton()
        motionPhotoController.bindButton()
        shutterController.bindShutter()
        lensController.bindCameraSwitchButton()

        tierController.initManualControls()
        hdrPlusController.updateHdrPlusConstraints()
        hdrPlusController.bindHdrPlusControls()
        modeController.bindModeSwitchButton()

        lutController.bindRecipeControls()
        thumbnailController.bindPhotoViewButton()
        lensController.updateCameraSwitchButton()
        lensController.initLensControls()
        tierController.updateFocusPeakingState()
    }

    override fun applyUIVisibility() {
        tierController.applyDeckVisibility(
            supportsMotionPhoto = modeController.effectiveMode.supportsMotionPhoto,
            isHdrPlusEnabled = isHdrPlusEnabled,
            beforeApply = {
                motionPhotoController.updateButton()
                hdrPlusController.updateHdrPlusConstraints()
            }
        )
    }

    companion object {
        private const val TAG = "CameraFragment"
        const val KEY_SELECTED_LENS_ID = "selected_lens_sensor_id"
        const val KEY_LENS_FACING = "lens_facing"
        const val KEY_HDR_PLUS_ENABLED = "hdr_plus_enabled"
    }
}
