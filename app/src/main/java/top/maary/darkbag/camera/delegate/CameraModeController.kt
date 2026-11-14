package top.maary.darkbag.camera.delegate

import android.app.ActivityManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import top.maary.darkbag.R
import top.maary.darkbag.camera.MultiCameraCaptureManager
import top.maary.darkbag.camera.dispatch.CameraCapturePipelineDispatcher
import top.maary.darkbag.camera.dispatch.CameraMultiCaptureDispatcher
import top.maary.darkbag.camera.session.CameraHardwareStreamController
import top.maary.darkbag.databinding.CameraUiContainerBinding
import top.maary.darkbag.databinding.FragmentCameraBinding
import top.maary.darkbag.fragments.SettingsFragment
import top.maary.darkbag.models.StandardTimingTracker
import top.maary.darkbag.modes.CaptureMode
import top.maary.darkbag.modes.CaptureModeCoordinator
import top.maary.darkbag.modes.ModeCoordinatorFactory
import top.maary.darkbag.modes.ModeExecutionContext
import top.maary.darkbag.pipeline.sink.CaptureSink
import top.maary.darkbag.processor.HdrPlusRequestManager
import top.maary.darkbag.processor.LutSurfaceProcessor
import top.maary.darkbag.ui.CameraBentoDeckView
import top.maary.darkbag.utils.DualLensPairPreference
import top.maary.darkbag.utils.HalfFrameManager
import top.maary.darkbag.utils.LensInfo
import top.maary.darkbag.utils.MultiCameraCountPreference
import top.maary.darkbag.utils.MultiCameraHelper
import top.maary.darkbag.viewmodel.CameraViewModel

/** Host contract for capture-mode resolution, coordinator lifecycle and multi-camera binding. */
interface CameraModeHost {
    val hostContext: Context
    val lifecycleScope: CoroutineScope
    val isHostAdded: Boolean
    val cameraViewModel: CameraViewModel
    val cameraUiContainerBinding: CameraUiContainerBinding?
    val fragmentCameraBinding: FragmentCameraBinding?
    val isHdrPlusEnabled: Boolean
    val isRawSupported: Boolean
    val deviceOrientationDegrees: Int
    val isBurstActive: Boolean
    val camera2Handler: Handler?
    val lutProcessor: LutSurfaceProcessor?
    val currentLens: LensInfo?
    var multiCameraManager: MultiCameraCaptureManager?
    var pendingVfSnapshot: Bitmap?

    fun bindCameraUseCases()

    /** Called after the user cycled modes so the host can refresh dependent UI. */
    fun onCaptureModeCycled(modeChanged: Boolean)
}

/** Collaborators the mode controller drives on behalf of the active [CaptureModeCoordinator]. */
class CameraModeCollaborators(
    val overlay: CameraOverlayController,
    val shutter: CameraShutterController,
    val thumbnail: CameraThumbnailController,
    val motionPhoto: CameraMotionPhotoController,
    val pipeline: CameraCapturePipelineDispatcher,
    val multiCapture: CameraMultiCaptureDispatcher,
    val stream: CameraHardwareStreamController,
    val lens: CameraLensController
)

/**
 * Resolves the persisted capture mode, owns the active [CaptureModeCoordinator] and the
 * [ModeExecutionContext] it runs against, and cycles / binds modes. The fragment itself holds no
 * mode branches (AGENTS.md Invariant 3).
 */
class CameraModeController(
    private val host: CameraModeHost,
    private val parts: CameraModeCollaborators
) {
    var activeCoordinator: CaptureModeCoordinator? = null
        private set

    private var lastMemInfoTime = 0L
    private val cachedMemInfo = ActivityManager.MemoryInfo()

    private val prefs: SharedPreferences
        get() = host.hostContext.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)

    /** Active coordinator's mode, falling back to (and activating) the persisted mode. */
    val effectiveMode: CaptureMode
        get() = activeCoordinator?.mode ?: resolveActiveCaptureMode()

    val isHalfFrameActive: Boolean
        get() = (activeCoordinator?.mode ?: host.cameraViewModel.uiState.value.currentMode).isHalfFrame
    val isMultiCameraActive: Boolean
        get() = (activeCoordinator?.mode ?: host.cameraViewModel.uiState.value.currentMode).isMultiCamera

    fun updateActiveCoordinator(mode: CaptureMode) {
        if (activeCoordinator?.mode == mode) return
        activeCoordinator?.onDeactivated()
        activeCoordinator = ModeCoordinatorFactory.create(mode, executionContext)
        activeCoordinator?.onActivated()
        host.cameraViewModel.setMode(mode)
        host.cameraUiContainerBinding?.bottomIslandCard?.let { CameraBentoDeckView.styleDeckCard(it, mode) }
    }

    /** Validates the persisted mode against current prefs/hardware, persists and activates it. */
    fun resolveActiveCaptureMode(): CaptureMode {
        val prefs = prefs
        val isHalfFramePref = prefs.getBoolean(SettingsFragment.KEY_HALF_FRAME_MODE, false)
        val canUseMultiCam = canUseMultiCamera(prefs)
        val isMultiCamPref = prefs.getBoolean(SettingsFragment.KEY_MULTI_CAMERA_MODE, false) ||
            prefs.getBoolean(SettingsFragment.KEY_MULTI_CAMERA_FORCE_ENABLE, false)

        val savedModeKey = prefs.getString(SettingsFragment.KEY_ACTIVE_CAPTURE_MODE, null)
        val requestedMode = if (savedModeKey != null) {
            CaptureMode.fromKey(savedModeKey) ?: CaptureMode.NORMAL
        } else {
            when {
                isMultiCamPref && canUseMultiCam && !isHalfFramePref -> CaptureMode.MULTI_CAMERA
                isHalfFramePref -> {
                    val layout = prefs.getString(SettingsFragment.KEY_HALF_FRAME_LAYOUT, SettingsFragment.HALF_FRAME_LAYOUT_SBS)
                    if (layout == SettingsFragment.HALF_FRAME_LAYOUT_TB) CaptureMode.HALF_FRAME_TB else CaptureMode.HALF_FRAME_SBS
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
            prefs.edit().putString(SettingsFragment.KEY_ACTIVE_CAPTURE_MODE, validatedMode.key).apply()
        }
        updateActiveCoordinator(validatedMode)
        return validatedMode
    }

    private fun canUseMultiCamera(prefs: SharedPreferences): Boolean {
        val forceEnable = prefs.getBoolean(SettingsFragment.KEY_MULTI_CAMERA_FORCE_ENABLE, false)
        val isMultiCamPref = prefs.getBoolean(SettingsFragment.KEY_MULTI_CAMERA_MODE, false)
        return (isMultiCamPref || forceEnable) && MultiCameraHelper.isMultiCameraSupported(host.hostContext, forceEnable)
    }

    /** Advances to the next enabled mode (Normal → Half-frame SBS/TB → Multi-camera → Normal). */
    fun cycleCaptureMode() {
        if (host.isBurstActive) return
        val prefs = prefs
        val currentMode = resolveActiveCaptureMode()
        val availableModes = mutableListOf(CaptureMode.NORMAL)
        if (prefs.getBoolean(SettingsFragment.KEY_HALF_FRAME_MODE, false)) {
            availableModes += CaptureMode.HALF_FRAME_SBS
            availableModes += CaptureMode.HALF_FRAME_TB
        }
        if (canUseMultiCamera(prefs)) availableModes += CaptureMode.MULTI_CAMERA

        val index = availableModes.indexOf(currentMode)
        val nextMode = availableModes[if (index != -1 && index < availableModes.size - 1) index + 1 else 0]

        val editor = prefs.edit().putString(SettingsFragment.KEY_ACTIVE_CAPTURE_MODE, nextMode.key)
        when (nextMode) {
            CaptureMode.HALF_FRAME_SBS -> editor.putString(SettingsFragment.KEY_HALF_FRAME_LAYOUT, SettingsFragment.HALF_FRAME_LAYOUT_SBS)
            CaptureMode.HALF_FRAME_TB -> editor.putString(SettingsFragment.KEY_HALF_FRAME_LAYOUT, SettingsFragment.HALF_FRAME_LAYOUT_TB)
            else -> Unit
        }
        editor.apply()

        updateActiveCoordinator(nextMode)
        host.onCaptureModeCycled(currentMode != nextMode)
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
        host.cameraUiContainerBinding?.bottomIslandCard?.let { CameraBentoDeckView.styleDeckCard(it, activeMode) }
    }

    /** Wires the mode switch tile. */
    fun bindModeSwitchButton() {
        val btn = host.cameraUiContainerBinding?.modeSwitchButton ?: return
        updateModeSwitchIcon(btn)
        btn.setOnClickListener {
            if (host.isBurstActive) return@setOnClickListener
            cycleCaptureMode()
            updateModeSwitchIcon(btn)
        }
    }

    /** Falls back to Normal mode and refreshes the mode tile (multi-camera unsupported / failed). */
    private fun fallBackToNormalMode() {
        prefs.edit().putString(SettingsFragment.KEY_ACTIVE_CAPTURE_MODE, CaptureMode.NORMAL.key).apply()
        updateActiveCoordinator(CaptureMode.NORMAL)
        host.cameraUiContainerBinding?.modeSwitchButton?.let { updateModeSwitchIcon(it) }
    }

    /** Opens the logical multi-camera session; returns false when the device cannot do it. */
    fun bindMultiCameraSession(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        val prefs = prefs
        val logicalInfo = MultiCameraHelper.getLogicalMultiCameraInfo(host.hostContext)
        if (logicalInfo == null) {
            Log.w(TAG, "Multi-camera requested but not supported on device, disabling multi-camera mode")
            fallBackToNormalMode()
            return false
        }
        Log.d(TAG, "Binding Multi-Camera Session with logical camera: ${logicalInfo.logicalCameraId}")
        val countPref = MultiCameraCountPreference.fromKey(prefs.getString(SettingsFragment.KEY_MULTI_CAMERA_COUNT_PREF, null))
        val pairPref = DualLensPairPreference.fromKey(prefs.getString(SettingsFragment.KEY_MULTI_CAMERA_DUAL_PAIR, null))
        val saveRaw = prefs.getBoolean(SettingsFragment.KEY_MULTI_CAMERA_SAVE_RAW, false)

        host.lifecycleScope.launch(Dispatchers.Main) {
            parts.stream.closeCamera2()
            if (!host.isHostAdded) return@launch
            parts.lens.initLensControls()
            host.lutProcessor?.getInputSurface(1440, 1080) { previewSurface ->
                host.lifecycleScope.launch(Dispatchers.Main) {
                    if (!host.isHostAdded) return@launch
                    val manager = host.multiCameraManager
                        ?: MultiCameraCaptureManager(host.hostContext, host.lifecycleScope).also { host.multiCameraManager = it }
                    manager.onSessionFailedListener = { error ->
                        Log.e(TAG, "Multi-camera session failed: $error, falling back to standard mode")
                        host.lifecycleScope.launch(Dispatchers.Main) {
                            fallBackToNormalMode()
                            host.bindCameraUseCases()
                        }
                    }
                    manager.openAndConfigure(
                        logicalInfo = logicalInfo,
                        countPref = countPref,
                        pairPref = pairPref,
                        targetPreviewSurface = previewSurface,
                        saveRaw = saveRaw,
                        initialLensMultiplier = host.currentLens?.multiplier
                    )
                }
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
        host.camera2Handler?.post(block) ?: block()
    }

    private val executionContext: ModeExecutionContext by lazy {
        object : ModeExecutionContext {
            override val context: Context get() = host.hostContext
            override val preferences: SharedPreferences get() = prefs
            override val isHdrPlusEnabled: Boolean get() = host.isHdrPlusEnabled
            override val isRawSupported: Boolean get() = host.isRawSupported
            override val deviceOrientationDegrees: Int get() = host.deviceOrientationDegrees

            override fun canTriggerCapture(): Boolean {
                if (host.isBurstActive) return false
                val strategy = prefs.getString(
                    SettingsFragment.KEY_BURST_PROCESSING_STRATEGY,
                    SettingsFragment.BURST_STRATEGY_BALANCED
                )
                val maxQueue = if (strategy == SettingsFragment.BURST_STRATEGY_AGGRESSIVE) 8 else 3
                val memInfo = getThrottledMemoryInfo(host.hostContext)
                if (HdrPlusRequestManager.canAcceptNewTask(maxQueue, host.hostContext, memInfo)) return true
                val pending = HdrPlusRequestManager.pendingTasksCount.value
                val msg = if (pending >= maxQueue) {
                    host.hostContext.getString(top.maary.darkbag.R.string.toast_queue_full, pending)
                } else {
                    host.hostContext.getString(top.maary.darkbag.R.string.toast_memory_low)
                }
                Toast.makeText(host.hostContext, msg, Toast.LENGTH_SHORT).show()
                return false
            }

            override fun showShutterVisuals() = parts.shutter.showShutterVisuals()

            /** Downsampled (1/4) viewfinder snapshot so the UI thread never reads back full-res frames. */
            override fun captureViewFinderSnapshot(): Bitmap? {
                val vf = host.fragmentCameraBinding?.viewFinder ?: return null
                if (vf.width <= 0 || vf.height <= 0) return null
                return try {
                    vf.getBitmap((vf.width / 4).coerceAtLeast(1), (vf.height / 4).coerceAtLeast(1))
                        .also { host.pendingVfSnapshot = it }
                } catch (e: Exception) {
                    null
                }
            }

            override fun showProcessingAnimation() = parts.overlay.showProcessingAnimation()

            override fun updateHalfFrameUi(animate: Boolean) = parts.overlay.updateHalfFrameUI(animate = animate)

            override fun clearThumbnailPlaceholder() {
                host.cameraUiContainerBinding?.photoViewButton?.visibility = View.VISIBLE
                parts.thumbnail.setGalleryThumbnail(null)
            }

            override fun triggerMotionPhotoSnapshot(timestamp: Long): CompletableDeferred<Pair<String?, Long>>? =
                parts.motionPhoto.triggerSnapshot(timestamp, host.deviceOrientationDegrees)

            override fun triggerHdrPlusBurst(
                sink: CaptureSink,
                isFrame1: Boolean,
                hfMetadata: HalfFrameManager.Metadata?,
                timing: StandardTimingTracker
            ) = onCameraThread { parts.pipeline.triggerHdrPlusBurstCamera2(sink, isFrame1, hfMetadata, timing) }

            override fun triggerSinglePicture(
                sink: CaptureSink,
                isFrame1: Boolean,
                hfMetadata: HalfFrameManager.Metadata?,
                timing: StandardTimingTracker
            ) = onCameraThread { parts.pipeline.takeSinglePictureCamera2(sink, timing, isFrame1, hfMetadata) }

            override fun triggerMultiCameraPicture(timing: StandardTimingTracker) =
                onCameraThread { parts.multiCapture.takeMultiCameraPicture(timing) }

            override fun toggleFrontPipInMultiCameraMode() = parts.multiCapture.toggleFrontPipInMultiCameraMode()

            override fun switchMultiCameraPrimaryLens(multiplier: Float) {
                host.multiCameraManager?.switchPrimaryPreviewLensByMultiplier(multiplier)
            }

            override fun bindMultiCameraSession(): Boolean = this@CameraModeController.bindMultiCameraSession()
        }
    }

    private companion object {
        const val TAG = "CameraModeController"
    }
}
