package top.maary.darkbag.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.os.SystemClock
import android.net.Uri
import android.util.Log
import android.view.View
import android.view.ViewGroup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import top.maary.darkbag.camera.delegate.CameraCoordinatorHost
import top.maary.darkbag.camera.delegate.CameraEnvironmentHost
import top.maary.darkbag.camera.delegate.CameraFocusMeteringController
import top.maary.darkbag.camera.delegate.CameraGestureDelegate
import top.maary.darkbag.camera.delegate.CameraHalfFrameStateController
import top.maary.darkbag.camera.delegate.CameraHdrPlusController
import top.maary.darkbag.camera.delegate.CameraLensController
import top.maary.darkbag.camera.delegate.CameraLutController
import top.maary.darkbag.camera.delegate.CameraModeCollaborators
import top.maary.darkbag.camera.delegate.CameraModeController
import top.maary.darkbag.camera.delegate.CameraMotionPhotoController
import top.maary.darkbag.camera.delegate.CameraOverlayController
import top.maary.darkbag.camera.delegate.CameraShutterController
import top.maary.darkbag.camera.delegate.CameraThumbnailController
import top.maary.darkbag.camera.delegate.CameraTierController
import top.maary.darkbag.camera.delegate.CameraUiHost
import top.maary.darkbag.camera.dispatch.CameraCapturePipelineDispatcher
import top.maary.darkbag.camera.dispatch.CameraMultiCaptureDispatcher
import top.maary.darkbag.camera.session.CameraHardwareStreamController
import top.maary.darkbag.camera.zsl.ZslCaptureController
import top.maary.darkbag.camera.video.CameraVideoController
import top.maary.darkbag.databinding.CameraUiContainerBinding
import top.maary.darkbag.databinding.FragmentCameraBinding
import top.maary.darkbag.models.CaptureTimingMode
import top.maary.darkbag.models.StandardTimingTracker
import top.maary.darkbag.modes.CaptureMode
import top.maary.darkbag.modes.CaptureModeCoordinator
import top.maary.darkbag.processor.HdrPlusRequestManager
import top.maary.darkbag.processor.LutSurfaceProcessor
import top.maary.darkbag.processor.StreamingBufferPool
import top.maary.darkbag.repository.SettingsRepository
import top.maary.darkbag.viewmodel.CameraUiTier

/**
 * Assembles and coordinates the camera subsystem controllers on behalf of [top.maary.darkbag.fragments.CameraFragment],
 * implementing [CameraUiHost] and [CameraCoordinatorHost] so the Fragment stays strictly passive.
 */
class CameraControllerHub(
    private val env: CameraEnvironmentHost,
    private val fragmentBindingProvider: () -> FragmentCameraBinding?,
    private val uiBindingProvider: () -> CameraUiContainerBinding?,
    private val isPortraitBoundsProvider: () -> Boolean
) : CameraUiHost, CameraCoordinatorHost {

    companion object {
        private const val TAG = "CameraControllerHub"
    }

    val overlayController = CameraOverlayController(env, this, this)
    val gestureDelegate = CameraGestureDelegate(env.hostContext, this)
    val focusMeteringController = CameraFocusMeteringController(env, this, this, gestureDelegate)
    val streamController = CameraHardwareStreamController(env, this, this)
    val pipelineDispatcher = CameraCapturePipelineDispatcher(env, this, this)
    val zslCaptureController = ZslCaptureController(
        env, this, this, pipelineDispatcher, streamController
    )
    val multiCaptureDispatcher = CameraMultiCaptureDispatcher(env, this, this)
    val videoController = CameraVideoController(env, this, this)
    val lensController = CameraLensController(env, this, this)
    val lutController = CameraLutController(env, this, this)
    val halfFrameStateController = CameraHalfFrameStateController(env)
    val thumbnailController = CameraThumbnailController(env, this, this)
    val motionPhotoController = CameraMotionPhotoController(env, this, this)
    val tierController = CameraTierController(env, this, this)
    val hdrPlusController = CameraHdrPlusController(env, this, this, lutController, lensController)
    val shutterController = CameraShutterController(
        env, this, this, overlayController, focusMeteringController, videoController
    )
    val modeController = CameraModeController(
        env,
        this,
        this,
        CameraModeCollaborators(
            overlay = overlayController,
            shutter = shutterController,
            thumbnail = thumbnailController,
            motionPhoto = motionPhotoController,
            pipeline = pipelineDispatcher,
            multiCapture = multiCaptureDispatcher,
            stream = streamController,
            lens = lensController,
            zsl = zslCaptureController,
            focus = focusMeteringController
        )
    )

    private var bindJob: Job? = null

    init {
        env.sessionArbiter.isMultiCameraActiveProvider = { modeController.isMultiCameraActive }
    }

    // -------------------------------------------------------------------------
    // CameraUiHost
    // -------------------------------------------------------------------------

    override val fragmentCameraBinding: FragmentCameraBinding?
        get() = fragmentBindingProvider()

    override val cameraUiContainerBinding: CameraUiContainerBinding?
        get() = uiBindingProvider()

    override val rootViewGroup: ViewGroup?
        get() = fragmentCameraBinding?.root as? ViewGroup

    override val isFloatingOverlayOpen: Boolean
        get() = env.runtimeState.activeManualTab != null ||
            cameraUiContainerBinding?.lutListContainer?.visibility == View.VISIBLE

    override fun applyUIVisibility() {
        tierController.applyDeckVisibility(
            supportsMotionPhoto = modeController.effectiveMode.supportsMotionPhoto,
            isHdrPlusEnabled = env.isHdrPlusEnabled,
            beforeApply = {
                motionPhotoController.updateButton()
                hdrPlusController.updateHdrPlusConstraints()
            }
        )
    }

    override fun dismissFloatingOverlays() = tierController.dismissFloatingOverlays()

    override fun updateProInfoBar() = tierController.updateProInfoBar()

    override fun updateDialPanel() = tierController.updateDialPanel()

    override fun formatFocusDist(dist: Float): String = tierController.formatFocusDist(dist)

    override fun formatShutterTime(nanos: Long): String = tierController.formatShutterTime(nanos)

    override fun showShutterBlackout() = overlayController.showShutterBlackout()

    override fun showProcessingAnimation() = overlayController.showProcessingAnimation()

    override fun hideProcessingAnimation() = overlayController.hideProcessingAnimation()

    override fun showZslCaptureFeedback() = shutterController.showZslCaptureFeedback()

    override fun resetBurstUi() = shutterController.resetBurstUi()

    override fun updateCurrentThumbnail(uri: Uri?) = thumbnailController.updateCurrentThumbnail(uri)

    override fun setGalleryThumbnail(filename: String?) = thumbnailController.setGalleryThumbnail(filename)

    override fun onSessionConfigured() {
        env.runtimeState.isSwitchingLens = false
        fragmentCameraBinding?.viewFinder?.animate()?.alpha(1f)?.setDuration(150L)?.start()
        zslCaptureController.onSessionConfigured()
    }

    override fun triggerTapToFocus(x: Float, y: Float) =
        focusMeteringController.triggerTapToFocusCamera2(x, y)

    override fun unlockFocusAndMetering() =
        focusMeteringController.unlockFocusAndMetering()

    // -------------------------------------------------------------------------
    // CameraCoordinatorHost
    // -------------------------------------------------------------------------

    override val activeCoordinator: CaptureModeCoordinator?
        get() = modeController.activeCoordinator

    override val effectiveCaptureMode: CaptureMode
        get() = modeController.effectiveMode

    override val halfFrameStep: Int
        get() = halfFrameStateController.step

    override fun ensureActiveCoordinator(): CaptureModeCoordinator? {
        modeController.resolveActiveCaptureMode()
        return modeController.activeCoordinator
    }

    override fun applyCameraControls() {
        zslCaptureController.onCaptureStateChanged()
        env.sessionArbiter.updateActivePreviewRepeatingRequest {
            streamController.updateCamera2RepeatingRequest()
        }
    }

    override fun applyManualSettingsToRequest(
        request: CaptureRequest.Builder,
        isHdrBurst: Boolean,
        isPreviewStream: Boolean
    ) {
        env.sessionArbiter.applyManualSettingsForActiveSession(
            applySingleCameraFull = {
                streamController.applyManualSettingsToRequest(request, isHdrBurst, isPreviewStream)
            },
            applyFocusAndExposureOnly = {
                streamController.applyFocusAndExposureSettingsToRequest(request, isHdrBurst, isPreviewStream)
            }
        )
    }

    override fun applyPreviewFocusAndExposureSettings(builder: CaptureRequest.Builder) {
        // Multi-camera preview also feeds the same LUT processor, so it needs the preview
        // tone map policy too; without it that mode would keep the double tone curve.
        streamController.applyFocusAndExposureSettingsToRequest(builder, isHdrBurst = false, isPreviewStream = true)
    }

    override fun updatePreviewToneMapPolicyForDevice(
        deviceId: String,
        chars: CameraCharacteristics
    ) {
        streamController.updateToneMapPolicyForCharacteristics(deviceId, chars)
    }

    override fun handleCaptureResult(result: TotalCaptureResult) =
        streamController.handleCaptureResult(result)

    override fun getCombinedOrientation(): Int {
        val effectiveDegrees = activeCoordinator?.getEffectiveOrientation(env.runtimeState.deviceOrientationDegrees)
            ?: env.runtimeState.deviceOrientationDegrees
        return env.sessionArbiter.getCombinedOrientation(effectiveDegrees)
    }

    override fun updateMotionPhotoEncoder() = motionPhotoController.updateEncoder()

    override fun triggerAutoBurst() {
        val timing = StandardTimingTracker(
            shutterClick = System.currentTimeMillis(),
            shutterPressElapsedNs = SystemClock.elapsedRealtimeNanos(),
            captureMode = CaptureTimingMode.HDR_BURST
        )
        ensureActiveCoordinator()?.onShutterTriggered(timing)
    }

    override fun onCaptureModeCycled(modeChanged: Boolean) {
        cameraUiContainerBinding?.modeSwitchButton?.let { modeController.updateModeSwitchIcon(it) }
        overlayController.updateHalfFrameUI()
        motionPhotoController.updateEncoder()
        applyUIVisibility()
        if (modeChanged) {
            bindCameraUseCases()
        }
    }

    override fun readScopedHalfFrameState(requireFileForStep1: Boolean) =
        halfFrameStateController.readState(requireFileForStep1)

    override fun writeScopedHalfFrameStep(
        step: Int,
        captureTimeMillis: Long?,
        digitalGain: Float,
        flareType: Int
    ) = halfFrameStateController.writeStep(step, captureTimeMillis, digitalGain, flareType)

    override fun getDigitalGainAndUpdateStep(
        timestamp: Long,
        isFrame1Trigger: Boolean,
        shutterClick: Long?
    ): Float = halfFrameStateController.getDigitalGainAndUpdateStep(timestamp, isFrame1Trigger, shutterClick)

    // -------------------------------------------------------------------------
    // Lifecycle & View Orchestration
    // -------------------------------------------------------------------------

    fun onStart() {
        shutterController.enableOrientationTracking()
        hdrPlusController.updateHdrPlusConstraints()
    }

    fun onStop() {
        bindJob?.cancel()
        bindJob = null
        modeController.cancelMultiCameraBind()
        videoController.onStop()
        env.locationHelper.stopListening()
        shutterController.disableOrientationTracking()
        motionPhotoController.release()
        zslCaptureController.onSessionClosed()
        env.lifecycleScope.launch(Dispatchers.Main.immediate + NonCancellable) {
            env.sessionArbiter.closeAllSessions(releaseSessionController = true)
        }
    }

    fun onViewCreated(applicationScope: CoroutineScope) {
        hdrPlusController.restorePersistedState()
        hdrPlusController.updateHdrPlusUi()
        hdrPlusController.updateHdrPlusConstraints()
        modeController.resolveActiveCaptureMode()
        halfFrameStateController.readState(requireFileForStep1 = true)
        overlayController.updateHalfFrameUI()
        shutterController.updateShutterOrientation()
        pipelineDispatcher.startProcessingWorker(applicationScope)
        zslCaptureController.start()
    }

    fun setupViewFinderSurface() {
        val vfStage = fragmentCameraBinding?.viewFinderStage ?: return
        if (env.sessionArbiter.lutProcessor == null) {
            env.sessionArbiter.lutProcessor = LutSurfaceProcessor()
            motionPhotoController.updateEncoder()
        }
        lutController.setupViewFinderBinding()
        focusMeteringController.setupTapToFocus(vfStage)
    }

    suspend fun onSettingsHydrated(
        settingsRepository: SettingsRepository,
        onRebuildUi: () -> Unit
    ) {
        hdrPlusController.restorePersistedState()
        val hydratedPrefs = settingsRepository.current()
        hydratedPrefs.lensFacing?.let { env.runtimeState.lensFacing = it }
        val previousMode = modeController.activeCoordinator?.mode
        val activeMode = modeController.resolveActiveCaptureMode()
        val savedTier = CameraUiTier.fromKey(hydratedPrefs.uiTier)
        if (env.cameraViewModel.uiState.value.uiTier != savedTier) {
            env.cameraViewModel.setUiTier(savedTier)
            tierController.updateTierUi(savedTier)
        }
        if (hydratedPrefs.saveLocationEnabled) {
            env.locationHelper.startListening()
        } else {
            env.locationHelper.stopListening()
        }
        halfFrameStateController.readState(requireFileForStep1 = true)
        onRebuildUi()
        overlayController.updateHalfFrameUI()
        cameraUiContainerBinding?.modeSwitchButton?.let { modeController.updateModeSwitchIcon(it) }
        applyUIVisibility()
        if (previousMode != activeMode) {
            bindCameraUseCases()
        }
    }

    fun onResume(
        isViewFinderLaidOut: Boolean,
        onRebuildUi: () -> Unit,
        onPostBind: (() -> Unit) -> Unit
    ) {
        hdrPlusController.updateHdrPlusConstraints()
        val previousMode = modeController.activeCoordinator?.mode
        val activeMode = modeController.resolveActiveCaptureMode()
        val modeChanged = (previousMode != activeMode)
        if (modeChanged || env.sessionArbiter.requiresSessionRebind) {
            if (isViewFinderLaidOut) {
                bindCameraUseCases()
            } else {
                onPostBind { bindCameraUseCases() }
            }
        }
        val settingsRepository = SettingsRepository.getInstance(env.hostContext)
        if (settingsRepository.current().saveLocationEnabled) {
            env.locationHelper.startListening()
        } else {
            env.locationHelper.stopListening()
        }
        val savedTier = CameraUiTier.fromKey(settingsRepository.current().uiTier)
        env.cameraViewModel.setUiTier(savedTier)
        halfFrameStateController.readState(requireFileForStep1 = true)
        onRebuildUi()
        overlayController.updateHalfFrameUI()
        cameraUiContainerBinding?.modeSwitchButton?.let { modeController.updateModeSwitchIcon(it) }
        applyUIVisibility()
        motionPhotoController.updateEncoder()
    }

    fun onDestroyView() {
        bindJob?.cancel()
        bindJob = null
        modeController.cancelMultiCameraBind()
        env.runtimeState.abortStreamingBurst()
        pipelineDispatcher.stopProcessingWorker()
        lutController.onDestroy()
        lensController.onDestroy()
        focusMeteringController.onDestroy()
        shutterController.onDestroy()
        motionPhotoController.release()
        zslCaptureController.stop()
        val processorToRelease = env.sessionArbiter.lutProcessor
        env.sessionArbiter.lutProcessor = null
        env.lifecycleScope.launch(Dispatchers.Main.immediate + NonCancellable) {
            env.sessionArbiter.closeAllSessions(releaseSessionController = true)
            processorToRelease?.releaseInputSurface()
            processorToRelease?.release()
        }
    }

    fun onViewFinderLayoutChanged() {
        overlayController.onViewFinderLayoutChanged()
    }

    fun onConfigurationChanged() {
        overlayController.updateHalfFrameUI()
        overlayController.updateViewFinderConstraints()
    }

    fun onForegroundTaskCountChanged(count: Int) {
        env.cameraViewModel.setTaskCounts(count, count)
        overlayController.updateProcessingAnimationUi()
        if (count == 0 && !env.runtimeState.isBurstActive) {
            thumbnailController.setGalleryThumbnail(thumbnailController.currentThumbnailUri?.toString())
        }
    }

    fun onBackgroundSaveCompleted(targetUri: String?) {
        if (targetUri != null) {
            env.imageRepository.invalidateCache()
            SettingsRepository.getInstance(env.hostContext).setLastCaptureUri(targetUri)
            thumbnailController.setGalleryThumbnail(targetUri)
            overlayController.updateProcessingAnimationUi()
        }
    }

    fun onHalfFrameStoreChanged() {
        halfFrameStateController.readState()
        overlayController.updateHalfFrameUI(animate = true)
    }

    fun triggerVolumeShutter(): Boolean {
        if (cameraUiContainerBinding?.cameraCaptureButton?.isEnabled != true) return false
        val timing = StandardTimingTracker(
            shutterClick = System.currentTimeMillis(),
            shutterPressElapsedNs = SystemClock.elapsedRealtimeNanos()
        )
        ensureActiveCoordinator()?.onShutterTriggered(timing)
        return true
    }

    fun bindCameraDeckUi() {
        tierController.bindTierControls(
            onVisibilityRefresh = { applyUIVisibility() },
            onLensRefresh = { lensController.updateLensUI() }
        )
        hdrPlusController.bindHdrPlusControls()
        hdrPlusController.bindFlashButton()
        motionPhotoController.bindButton()
        shutterController.bindShutter()
        lensController.bindCameraSwitchButton()
        modeController.bindModeSwitchButton()
        lutController.bindRecipeControls()
        thumbnailController.bindPhotoViewButton()
        lensController.updateCameraSwitchButton()
        tierController.initManualControls()
        lensController.initLensControls()
        hdrPlusController.updateHdrPlusUi()
        hdrPlusController.updateHdrPlusConstraints()
        thumbnailController.setGalleryThumbnail(thumbnailController.currentThumbnailUri?.toString())
        overlayController.updateViewFinderConstraints()
        overlayController.updateProcessingAnimationUi()
        applyUIVisibility()
    }

    override fun bindCameraUseCases() {
        bindJob?.cancel()
        modeController.cancelMultiCameraBind()
        bindJob = env.lifecycleScope.launch {
            bindCameraUseCasesInternal()
        }
    }

    private suspend fun bindCameraUseCasesInternal() {
        coroutineContext.ensureActive()
        if (!env.isHostAdded) return

        val state = env.runtimeState
        val arbiter = env.sessionArbiter
        val isPortraitBounds = isPortraitBoundsProvider()

        withContext(Dispatchers.Default) {
            lensController.refreshLenses()
        }
        coroutineContext.ensureActive()
        if (!env.isHostAdded) return

        arbiter.closeInactiveSessionsForModeSwitch()
        coroutineContext.ensureActive()
        if (!env.isHostAdded) return

        if (!state.isBurstActive && !state.isProcessing && HdrPlusRequestManager.pendingTasksCount.value == 0) {
            StreamingBufferPool.clear()
        }

        focusMeteringController.clearFocusAndMeteringState()

        val targetId = state.currentLens?.id
            ?: if (state.lensFacing == CameraCharacteristics.LENS_FACING_BACK) "0" else "1"
        try {
            val snapshot = withContext(Dispatchers.Default) {
                streamController.readCameraCharacteristics(targetId)
            }
            coroutineContext.ensureActive()
            if (!env.isHostAdded) return

            state.applyCharacteristics(
                isoRange = snapshot.isoRange,
                exposureTimeRange = snapshot.exposureTimeRange,
                minFocusDistance = snapshot.minFocusDistance,
                evRange = snapshot.evRange,
                isRawSupported = snapshot.isRawSupported,
                isOisSupported = snapshot.isOisSupported,
                isHdrOisEnabledPref = snapshot.isHdrOisEnabledPref,
                previewToneMapStrategy = snapshot.previewToneMapStrategy,
                tonemapMaxCurvePoints = snapshot.tonemapMaxCurvePoints,
                availableToneMapModes = snapshot.availableToneMapModes,
                currentToneMapCameraId = targetId
            )

            withContext(Dispatchers.Main) {
                if (!env.isHostAdded) return@withContext
                if (state.activeManualTab != null) {
                    tierController.updateDialPanel()
                }
                tierController.updateProInfoBar()
                if (state.isRawSupported) {
                    cameraUiContainerBinding?.hdrPlusSwitch?.visibility = View.VISIBLE
                    hdrPlusController.updateHdrPlusUi()
                } else {
                    cameraUiContainerBinding?.hdrPlusSwitch?.visibility = View.GONE
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch camera characteristics", e)
        }

        coroutineContext.ensureActive()
        if (!env.isHostAdded) return

        overlayController.syncViewFinderAspectRatio(isPortraitBounds)

        if (activeCoordinator?.bindCameraSession() == true) {
            return
        }

        lensController.initLensControls()
        hdrPlusController.updateHdrPlusConstraints()
        overlayController.updateHalfFrameUI()

        delay(300)
        coroutineContext.ensureActive()
        if (!env.isHostAdded) return

        streamController.openCamera2(targetId)
    }
}
