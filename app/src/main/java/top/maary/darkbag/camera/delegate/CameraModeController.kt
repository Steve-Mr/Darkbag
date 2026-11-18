package top.maary.darkbag.camera.delegate

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.view.View
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import top.maary.darkbag.R
import top.maary.darkbag.camera.MultiCameraCaptureManager
import top.maary.darkbag.camera.dispatch.CameraCapturePipelineDispatcher
import top.maary.darkbag.camera.dispatch.CameraMultiCaptureDispatcher
import top.maary.darkbag.camera.zsl.ZslCaptureController
import top.maary.darkbag.camera.session.CameraHardwareStreamController
import top.maary.darkbag.models.StandardTimingTracker
import top.maary.darkbag.modes.CaptureMode
import top.maary.darkbag.modes.CaptureModeCoordinator
import top.maary.darkbag.modes.ModeCoordinatorFactory
import top.maary.darkbag.modes.ModeExecutionContext
import top.maary.darkbag.pipeline.sink.CaptureSink
import top.maary.darkbag.processor.HdrPlusRequestManager
import top.maary.darkbag.repository.DarkbagPreferences
import top.maary.darkbag.repository.PreferenceKeys
import top.maary.darkbag.repository.SettingsRepository
import top.maary.darkbag.ui.CameraBentoDeckView
import top.maary.darkbag.utils.DualLensPairPreference
import top.maary.darkbag.utils.HalfFrameManager
import top.maary.darkbag.utils.MultiCameraCountPreference
import top.maary.darkbag.utils.MultiCameraHelper

/** Collaborators the mode controller drives on behalf of the active [CaptureModeCoordinator]. */
class CameraModeCollaborators(
    val overlay: CameraOverlayController,
    val shutter: CameraShutterController,
    val thumbnail: CameraThumbnailController,
    val motionPhoto: CameraMotionPhotoController,
    val pipeline: CameraCapturePipelineDispatcher,
    val multiCapture: CameraMultiCaptureDispatcher,
    val stream: CameraHardwareStreamController,
    val lens: CameraLensController,
    val zsl: ZslCaptureController? = null,
    val focus: CameraFocusMeteringController? = null
)

/**
 * Resolves the persisted capture mode, owns the active [CaptureModeCoordinator] and the
 * [ModeExecutionContext] it runs against, and cycles / binds modes. The fragment itself holds no
 * mode branches (AGENTS.md Invariant 3).
 */
class CameraModeController(
    private val env: CameraEnvironmentHost,
    private val ui: CameraUiHost,
    private val coordinator: CameraCoordinatorHost,
    private val parts: CameraModeCollaborators
) {
    var activeCoordinator: CaptureModeCoordinator? = null
        private set

    private var lastMemInfoTime = 0L
    private val cachedMemInfo = ActivityManager.MemoryInfo()
    private var multiCamBindJob: Job? = null

    fun cancelMultiCameraBind() {
        multiCamBindJob?.cancel()
        multiCamBindJob = null
    }

    private val settingsRepo: SettingsRepository
        get() = SettingsRepository.getInstance(env.hostContext)

    /** Active coordinator's mode, falling back to (and activating) the persisted mode. */
    val effectiveMode: CaptureMode
        get() = activeCoordinator?.mode ?: resolveActiveCaptureMode()

    val isHalfFrameActive: Boolean
        get() = (activeCoordinator?.mode ?: env.cameraViewModel.uiState.value.currentMode).isHalfFrame
    val isMultiCameraActive: Boolean
        get() = (activeCoordinator?.mode ?: env.cameraViewModel.uiState.value.currentMode).isMultiCamera

    fun updateActiveCoordinator(mode: CaptureMode) {
        if (activeCoordinator?.mode == mode) return
        activeCoordinator?.onDeactivated()
        activeCoordinator = ModeCoordinatorFactory.create(mode, executionContext)
        activeCoordinator?.onActivated()
        env.sessionArbiter.isMultiCameraMode = mode.isMultiCamera
        env.cameraViewModel.setMode(mode)
        ui.cameraUiContainerBinding?.bottomIslandCard?.let { CameraBentoDeckView.styleDeckCard(it, mode) }
    }

    /** Validates the persisted mode against current prefs/hardware, persists and activates it. */
    fun resolveActiveCaptureMode(): CaptureMode {
        val current = settingsRepo.current()
        val isHalfFramePref = current.halfFrameMode
        val canUseMultiCam = canUseMultiCamera(current)
        val isMultiCamPref = current.multiCameraModeEnabled || current.multiCameraForceEnable

        val hasExplicitMode = settingsRepo.hasExplicitKey(PreferenceKeys.ACTIVE_CAPTURE_MODE)
        val savedModeKey = if (hasExplicitMode) current.activeCaptureMode else null
        val requestedMode = if (savedModeKey != null) {
            CaptureMode.fromKey(savedModeKey) ?: CaptureMode.NORMAL
        } else {
            when {
                isMultiCamPref && canUseMultiCam && !isHalfFramePref -> CaptureMode.MULTI_CAMERA
                isHalfFramePref -> {
                    if (current.halfFrameLayout == PreferenceKeys.HALF_FRAME_LAYOUT_TB) {
                        CaptureMode.HALF_FRAME_TB
                    } else {
                        CaptureMode.HALF_FRAME_SBS
                    }
                }
                else -> CaptureMode.NORMAL
            }
        }

        val validatedMode = when {
            requestedMode.isMultiCamera -> if (canUseMultiCam) requestedMode else CaptureMode.NORMAL
            requestedMode.isHalfFrame -> if (isHalfFramePref) requestedMode else CaptureMode.NORMAL
            else -> CaptureMode.NORMAL
        }
        if (savedModeKey != validatedMode.key) {
            settingsRepo.setActiveCaptureMode(validatedMode.key)
        }
        updateActiveCoordinator(validatedMode)
        return validatedMode
    }

    private fun canUseMultiCamera(current: DarkbagPreferences): Boolean {
        val forceEnable = current.multiCameraForceEnable
        val isMultiCamPref = current.multiCameraModeEnabled
        return (isMultiCamPref || forceEnable) && MultiCameraHelper.isMultiCameraSupported(env.hostContext, forceEnable)
    }

    /** Advances to the next enabled mode (Normal → Half-frame SBS/TB → Multi-camera → Normal). */
    fun cycleCaptureMode() {
        if (env.runtimeState.isBurstActive) return
        val current = settingsRepo.current()
        val currentMode = resolveActiveCaptureMode()
        val availableModes = mutableListOf(CaptureMode.NORMAL)
        if (current.halfFrameMode) {
            availableModes += CaptureMode.HALF_FRAME_SBS
            availableModes += CaptureMode.HALF_FRAME_TB
        }
        if (canUseMultiCamera(current)) availableModes += CaptureMode.MULTI_CAMERA

        val index = availableModes.indexOf(currentMode)
        val nextMode = availableModes[if (index != -1 && index < availableModes.size - 1) index + 1 else 0]

        settingsRepo.edit {
            this[PreferenceKeys.ACTIVE_CAPTURE_MODE] = nextMode.key
            when (nextMode) {
                CaptureMode.HALF_FRAME_SBS -> this[PreferenceKeys.HALF_FRAME_LAYOUT] = PreferenceKeys.HALF_FRAME_LAYOUT_SBS
                CaptureMode.HALF_FRAME_TB -> this[PreferenceKeys.HALF_FRAME_LAYOUT] = PreferenceKeys.HALF_FRAME_LAYOUT_TB
                else -> Unit
            }
        }

        updateActiveCoordinator(nextMode)
        coordinator.onCaptureModeCycled(currentMode != nextMode)
    }

    fun updateModeSwitchIcon(btn: MaterialButton) {
        val activeMode = resolveActiveCaptureMode()
        val iconRes = when (activeMode) {
            CaptureMode.MULTI_CAMERA -> R.drawable.ic_mode_multi_camera
            CaptureMode.HALF_FRAME_SBS -> R.drawable.ic_mode_half_side
            CaptureMode.HALF_FRAME_TB -> R.drawable.ic_mode_half_top
            CaptureMode.NORMAL -> R.drawable.ic_mode_normal
        }
        btn.setIconResource(iconRes)
        CameraBentoDeckView.styleModeSwitchButton(btn, activeMode)
        ui.cameraUiContainerBinding?.bottomIslandCard?.let { CameraBentoDeckView.styleDeckCard(it, activeMode) }
    }

    /** Wires the mode switch tile. */
    fun bindModeSwitchButton() {
        val btn = ui.cameraUiContainerBinding?.modeSwitchButton ?: return
        updateModeSwitchIcon(btn)
        btn.setOnClickListener {
            if (env.runtimeState.isBurstActive) return@setOnClickListener
            cycleCaptureMode()
            updateModeSwitchIcon(btn)
        }
    }

    /** Falls back to Normal mode and refreshes the mode tile (multi-camera unsupported / failed). */
    private fun fallBackToNormalMode() {
        settingsRepo.setActiveCaptureMode(CaptureMode.NORMAL.key)
        updateActiveCoordinator(CaptureMode.NORMAL)
        ui.cameraUiContainerBinding?.modeSwitchButton?.let { updateModeSwitchIcon(it) }
    }

    /** Opens the logical multi-camera session; returns false when the device cannot do it. */
    fun bindMultiCameraSession(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        val current = settingsRepo.current()
        val logicalInfo = MultiCameraHelper.getLogicalMultiCameraInfo(env.hostContext)
        if (logicalInfo == null) {
            Log.w(TAG, "Multi-camera requested but not supported on device, disabling multi-camera mode")
            fallBackToNormalMode()
            return false
        }
        Log.d(TAG, "Binding Multi-Camera Session with logical camera: ${logicalInfo.logicalCameraId}")
        val countPref = MultiCameraCountPreference.fromKey(current.multiCameraCountPref)
        val pairPref = DualLensPairPreference.fromKey(current.multiCameraDualPair)
        val saveRaw = current.multiCameraSaveRaw

        cancelMultiCameraBind()
        val job = env.lifecycleScope.launch(Dispatchers.Main) {
            parts.stream.closeCamera2()
            ensureActive()
            if (!env.isHostAdded) return@launch
            parts.lens.initLensControls()
            val processor = env.sessionArbiter.lutProcessor ?: return@launch
            val previewSurface = suspendCancellableCoroutine<Surface> { cont ->
                processor.getInputSurface(1440, 1080) { surface ->
                    if (cont.isActive) {
                        cont.resume(surface)
                    } else {
                        surface.release()
                    }
                }
            }
            ensureActive()
            if (!env.isHostAdded) {
                previewSurface.release()
                return@launch
            }
            val arbiter = env.sessionArbiter
            val manager = arbiter.multiCameraManager
                ?: MultiCameraCaptureManager(env.hostContext, env.lifecycleScope).also { arbiter.multiCameraManager = it }
            manager.onPreviewCaptureResultListener = { result ->
                coordinator.handleCaptureResult(result)
            }
            manager.previewRequestConfigProvider = { builder ->
                coordinator.applyPreviewFocusAndExposureSettings(builder)
            }
            manager.onSessionFailedListener = { error ->
                Log.e(TAG, "Multi-camera session failed: $error, falling back to standard mode")
                env.lifecycleScope.launch(Dispatchers.Main) {
                    fallBackToNormalMode()
                    coordinator.bindCameraUseCases()
                }
            }
            manager.openAndConfigure(
                logicalInfo = logicalInfo,
                countPref = countPref,
                pairPref = pairPref,
                targetPreviewSurface = previewSurface,
                saveRaw = saveRaw,
                initialLensMultiplier = env.runtimeState.currentLens?.multiplier
            )
        }
        multiCamBindJob = job
        job.invokeOnCompletion {
            if (multiCamBindJob === job) {
                multiCamBindJob = null
            }
        }
        return true
    }

    private fun getThrottledMemoryInfo(context: Context): ActivityManager.MemoryInfo {
        val now = SystemClock.elapsedRealtime()
        if (now - lastMemInfoTime > 1000L || lastMemInfoTime == 0L) {
            (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.getMemoryInfo(cachedMemInfo)
            lastMemInfoTime = now
        }
        return cachedMemInfo
    }

    /** Posts [block] to the camera2 background handler (Invariant 4), or runs inline when absent. */
    private fun onCameraThread(block: () -> Unit) {
        env.sessionArbiter.camera2Handler?.post(block) ?: block()
    }

    private val executionContext: ModeExecutionContext by lazy {
        object : ModeExecutionContext {
            override val context: Context get() = env.hostContext
            override val settingsRepository: SettingsRepository get() = settingsRepo
            override val isHdrPlusEnabled: Boolean get() = env.isHdrPlusEnabled
            override val isRawSupported: Boolean get() = env.runtimeState.isRawSupported
            override val deviceOrientationDegrees: Int get() = env.runtimeState.deviceOrientationDegrees

            override fun canTriggerCapture(): Boolean {
                if (env.runtimeState.isBurstActive) return false
                val strategy = settingsRepo.current().burstProcessingStrategy
                val maxQueue = if (strategy == PreferenceKeys.BURST_STRATEGY_AGGRESSIVE) 8 else 3
                val memInfo = getThrottledMemoryInfo(env.hostContext)
                if (HdrPlusRequestManager.canAcceptNewTask(maxQueue, env.hostContext, memInfo)) return true
                val pending = HdrPlusRequestManager.pendingTasksCount.value
                val msg = if (pending >= maxQueue) {
                    env.hostContext.getString(R.string.toast_queue_full, pending)
                } else {
                    env.hostContext.getString(R.string.toast_memory_low)
                }
                Toast.makeText(env.hostContext, msg, Toast.LENGTH_SHORT).show()
                return false
            }

            override fun showShutterVisuals() = parts.shutter.showShutterVisuals()

            /** Downsampled (1/4) viewfinder snapshot so the UI thread never reads back full-res frames. */
            override fun captureViewFinderSnapshot(): Bitmap? {
                val vf = ui.fragmentCameraBinding?.viewFinder ?: return null
                if (vf.width <= 0 || vf.height <= 0) return null
                return try {
                    vf.getBitmap((vf.width / 4).coerceAtLeast(1), (vf.height / 4).coerceAtLeast(1))
                        .also { env.runtimeState.pendingVfSnapshot = it }
                } catch (e: Exception) {
                    null
                }
            }

            override fun showProcessingAnimation() = parts.overlay.showProcessingAnimation()

            override fun updateHalfFrameUi(animate: Boolean) = parts.overlay.updateHalfFrameUI(animate = animate)

            override fun clearThumbnailPlaceholder() {
                ui.cameraUiContainerBinding?.photoViewButton?.visibility = View.VISIBLE
                parts.thumbnail.setGalleryThumbnail(null)
            }

            override fun triggerMotionPhotoSnapshot(timestamp: Long): CompletableDeferred<Pair<String?, Long>>? =
                parts.motionPhoto.triggerSnapshot(timestamp, env.runtimeState.deviceOrientationDegrees)

            override fun triggerHdrPlusBurst(
                sink: CaptureSink,
                isFrame1: Boolean,
                hfMetadata: HalfFrameManager.Metadata?,
                timing: StandardTimingTracker
            ) = onCameraThread {
                val outcome = parts.zsl?.tryCaptureHdrPlus(sink, isFrame1, hfMetadata, timing)
                if (outcome is top.maary.darkbag.camera.zsl.ZslDispatchOutcome.Consumed) {
                    if (outcome.sensorTimestampNs > 0L) {
                        parts.motionPhoto.updatePendingStillTimestamp(
                            outcome.sensorTimestampNs,
                            outcome.realtimeTimestamp
                        )
                    }
                    return@onCameraThread
                }
                parts.zsl?.onForwardCaptureStarting(timing)
                parts.pipeline.triggerHdrPlusBurstCamera2(sink, isFrame1, hfMetadata, timing)
            }

            override fun triggerSinglePicture(
                sink: CaptureSink,
                isFrame1: Boolean,
                hfMetadata: HalfFrameManager.Metadata?,
                timing: StandardTimingTracker
            ) = onCameraThread {
                val outcome = parts.zsl?.tryCaptureSingle(sink, isFrame1, hfMetadata, timing)
                if (outcome is top.maary.darkbag.camera.zsl.ZslDispatchOutcome.Consumed) {
                    if (outcome.sensorTimestampNs > 0L) {
                        parts.motionPhoto.updatePendingStillTimestamp(
                            outcome.sensorTimestampNs,
                            outcome.realtimeTimestamp
                        )
                    }
                    return@onCameraThread
                }
                parts.zsl?.onForwardCaptureStarting(timing)
                parts.pipeline.takeSinglePictureCamera2(sink, timing, isFrame1, hfMetadata)
            }

            override fun triggerMultiCameraPicture(timing: StandardTimingTracker) =
                onCameraThread { parts.multiCapture.takeMultiCameraPicture(timing) }

            override fun toggleFrontPipInMultiCameraMode() = parts.multiCapture.toggleFrontPipInMultiCameraMode()

            override fun switchMultiCameraPrimaryLens(multiplier: Float) {
                parts.focus?.clearFocusAndMeteringState()
                env.sessionArbiter.multiCameraManager?.switchPrimaryPreviewLensByMultiplier(multiplier)
            }

            override fun bindMultiCameraSession(): Boolean = this@CameraModeController.bindMultiCameraSession()
        }
    }

    private companion object {
        const val TAG = "CameraModeController"
    }
}
