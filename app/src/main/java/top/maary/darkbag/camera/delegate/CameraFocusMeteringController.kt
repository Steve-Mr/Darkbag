package top.maary.darkbag.camera.delegate

import android.graphics.Matrix
import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.View
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import top.maary.darkbag.R
import top.maary.darkbag.utils.CameraRepository
import kotlin.coroutines.resume

/**
 * Controller responsible for autofocus (AF) and autoexposure (AE) tap-to-focus triggers,
 * AF-L lock state transitions, focus ring visual animations, and manual focus synchronization.
 */
class CameraFocusMeteringController(
    private val env: CameraEnvironmentHost,
    private val ui: CameraUiHost,
    private val coordinator: CameraCoordinatorHost,
    private val gestureDelegate: CameraGestureDelegate
) {
    companion object {
        private const val TAG = "CameraFocusMetering"
    }

    private var tapToFocusJob: Job? = null

    fun setupTapToFocus(stageView: View) {
        gestureDelegate.setupTapToFocus(stageView)
    }

    fun clearFocusAndMeteringState() {
        tapToFocusJob?.cancel()
        tapToFocusJob = null

        val hadState = env.runtimeState.clearMeteringRegions()

        val uiAction = {
            hideFocusRingUnlock()
            if (hadState) {
                ui.updateProInfoBar()
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            uiAction()
        } else {
            Handler(Looper.getMainLooper()).post(uiAction)
        }
    }

    fun unlockFocusAndMetering() {
        val state = env.runtimeState
        if (!state.clearMeteringRegions()) return

        tapToFocusJob?.cancel()
        tapToFocusJob = null

        val uiAction = {
            hideFocusRingUnlock()
            ui.updateProInfoBar()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            uiAction()
        } else {
            Handler(Looper.getMainLooper()).post(uiAction)
        }

        val arbiter = env.sessionArbiter
        val handler = arbiter.activePreviewHandler
        val cameraAction = {
            val device = arbiter.activePreviewDevice
            val session = arbiter.activePreviewSession
            val surface = arbiter.activePreviewSurface
            if (device != null && session != null && surface != null && handler != null) {
                try {
                    if (!state.isManualFocus) {
                        val cancelRequest = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                        cancelRequest.addTarget(surface)
                        coordinator.applyManualSettingsToRequest(cancelRequest)
                        arbiter.applyPreviewZoomAndCropToRequest(cancelRequest)
                        cancelRequest.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
                        session.capture(cancelRequest.build(), null, handler)
                    }
                    coordinator.applyCameraControls()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to unlock focus and metering", e)
                }
            }
        }
        if (handler != null) {
            handler.post(cameraAction)
        } else {
            cameraAction()
        }
    }

    fun triggerTapToFocusCamera2(x: Float, y: Float) {
        val arbiter = env.sessionArbiter
        val state = env.runtimeState
        val device = arbiter.activePreviewDevice ?: return
        val session = arbiter.activePreviewSession ?: return
        val surface = arbiter.activePreviewSurface ?: return
        val handler = arbiter.activePreviewHandler ?: return

        tapToFocusJob?.cancel()
        tapToFocusJob = env.lifecycleScope.launch(Dispatchers.Default) {
            try {
                val characteristics = CameraRepository.getCharacteristics(env.camera2Manager, device.id)
                val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
                val activeArray = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return@launch

                val effectiveAfChars = arbiter.activePreviewPhysicalCharacteristics ?: characteristics
                val afModes = effectiveAfChars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
                val maxAfRegions = effectiveAfChars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF)
                val afSupported = (afModes == null || afModes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO)) &&
                    (maxAfRegions == null || maxAfRegions > 0)

                val maxAeRegions = characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE)
                val aeRegionsSupported = maxAeRegions == null || maxAeRegions > 0

                val lastResult = state.captureResultFlow.replayCache.lastOrNull()
                val cropRegion = sanitizeCropRegion(lastResult?.get(CaptureResult.SCALER_CROP_REGION), activeArray)

                val region = getMeteringRectangle(
                    x, y,
                    ui.fragmentCameraBinding?.viewFinderStage?.width ?: 0,
                    ui.fragmentCameraBinding?.viewFinderStage?.height ?: 0,
                    sensorOrientation,
                    state.lensFacing,
                    cropRegion
                )

                state.focusMeteringRegion = if (afSupported) region else null
                if (!state.isManualExposure && aeRegionsSupported) {
                    state.exposureMeteringRegion = region
                }

                // 1. Show scanning visual feedback immediately on UI
                withContext(Dispatchers.Main) {
                    showFocusRingScanning(x, y)
                    ui.updateProInfoBar()
                }

                if (!afSupported) {
                    withContext(Dispatchers.Main) {
                        coordinator.applyCameraControls()
                        if (state.exposureMeteringRegion != null) {
                            showFocusRingLocked()
                        } else {
                            hideFocusRingUnlock()
                        }
                        ui.updateProInfoBar()
                    }
                    return@launch
                }

                // 2. Submit cancel request cleanly and suspend until processed by HAL
                val cancelRequest = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                cancelRequest.addTarget(surface)
                coordinator.applyManualSettingsToRequest(cancelRequest)
                arbiter.applyPreviewZoomAndCropToRequest(cancelRequest)
                cancelRequest.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)

                suspendCancellableCoroutine<Unit> { cont ->
                    try {
                        session.capture(cancelRequest.build(), object : CameraCaptureSession.CaptureCallback() {
                            override fun onCaptureCompleted(
                                session: CameraCaptureSession,
                                request: CaptureRequest,
                                result: TotalCaptureResult
                            ) {
                                super.onCaptureCompleted(session, request, result)
                                if (cont.isActive) cont.resume(Unit)
                            }

                            override fun onCaptureFailed(
                                session: CameraCaptureSession,
                                request: CaptureRequest,
                                failure: CaptureFailure
                            ) {
                                super.onCaptureFailed(session, request, failure)
                                if (cont.isActive) cont.resume(Unit)
                            }
                        }, handler)
                    } catch (e: Exception) {
                        if (cont.isActive) cont.resume(Unit)
                    }
                }

                ensureActive()

                // 3. Update repeating request with AF_MODE_AUTO and updated metering regions
                withContext(Dispatchers.Main) {
                    coordinator.applyCameraControls()
                }

                ensureActive()

                // 4. Submit trigger request (CONTROL_AF_TRIGGER_START)
                val triggerRequest = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                triggerRequest.addTarget(surface)
                coordinator.applyManualSettingsToRequest(triggerRequest)
                arbiter.applyPreviewZoomAndCropToRequest(triggerRequest)
                triggerRequest.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region))
                if (!state.isManualExposure && aeRegionsSupported) {
                    triggerRequest.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(region))
                }
                if (state.isManualFocus) {
                    triggerRequest.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                }
                triggerRequest.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)

                var triggerFrameNumber = -1L
                val triggerSucceeded = suspendCancellableCoroutine<Boolean> { cont ->
                    try {
                        session.capture(triggerRequest.build(), object : CameraCaptureSession.CaptureCallback() {
                            override fun onCaptureCompleted(
                                session: CameraCaptureSession,
                                request: CaptureRequest,
                                result: TotalCaptureResult
                            ) {
                                super.onCaptureCompleted(session, request, result)
                                triggerFrameNumber = result.frameNumber
                                if (state.isManualFocus) {
                                    syncManualFocusAfterTap(triggerFrameNumber)
                                }
                                if (cont.isActive) cont.resume(true)
                            }

                            override fun onCaptureFailed(
                                session: CameraCaptureSession,
                                request: CaptureRequest,
                                failure: CaptureFailure
                            ) {
                                super.onCaptureFailed(session, request, failure)
                                if (cont.isActive) cont.resume(false)
                            }
                        }, handler)
                    } catch (e: Exception) {
                        if (cont.isActive) cont.resume(false)
                    }
                }

                ensureActive()
                if (!triggerSucceeded) {
                    state.focusMeteringRegion = null
                    withContext(Dispatchers.Main) {
                        showFocusRingFailed()
                        ui.updateProInfoBar()
                    }
                    return@launch
                }

                // 5. Await AF convergence (FOCUSED_LOCKED or NOT_FOCUSED_LOCKED)
                if (!state.isManualFocus) {
                    val convergenceResult = withTimeoutOrNull(3500L) {
                        state.captureResultFlow.first { res ->
                            val afState = res.get(CaptureResult.CONTROL_AF_STATE)
                            res.frameNumber >= triggerFrameNumber && (
                                afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                                afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED
                            )
                        }
                    }

                    ensureActive()

                    val afState = convergenceResult?.get(CaptureResult.CONTROL_AF_STATE)
                    val isFocused = afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                        (convergenceResult != null && afState != CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED)

                    withContext(Dispatchers.Main) {
                        if (isFocused) {
                            showFocusRingLocked()
                            ui.updateProInfoBar()
                        } else {
                            showFocusRingFailed()
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Camera2 tap to focus trigger failed", e)
                withContext(Dispatchers.Main) {
                    showFocusRingFailed()
                }
            }
        }
    }

    fun showFocusRingScanning(x: Float, y: Float) {
        val binding = ui.fragmentCameraBinding ?: return
        val size = env.hostContext.resources.getDimension(R.dimen.focus_ring_size)
        gestureDelegate.showFocusRingScanning(binding, x, y, size)
    }

    fun showFocusRingLocked() {
        val binding = ui.fragmentCameraBinding ?: return
        val state = env.runtimeState
        val hasAnyLock = state.focusMeteringRegion != null || state.exposureMeteringRegion != null
        gestureDelegate.showFocusRingLocked(binding, hasAnyLock)
        if (state.focusMeteringRegion == null) {
            binding.focusLockBadge.animate().cancel()
            binding.focusLockBadge.visibility = View.GONE
        }
    }

    fun showFocusRingFailed() {
        val binding = ui.fragmentCameraBinding ?: return
        gestureDelegate.showFocusRingFailed(binding)
    }

    fun hideFocusRingUnlock() {
        val binding = ui.fragmentCameraBinding ?: return
        gestureDelegate.hideFocusRingUnlock(binding)
    }

    fun syncManualFocusAfterTap(minFrameNumber: Long = -1L) {
        val state = env.runtimeState
        env.lifecycleScope.launch(Dispatchers.Default) {
            withTimeoutOrNull(3000) {
                state.captureResultFlow.first { res ->
                    val afState = res.get(CaptureResult.CONTROL_AF_STATE)
                    res.frameNumber >= minFrameNumber && (
                        afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                        afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED
                    )
                }
            }?.let { res ->
                val dist = res.get(CaptureResult.LENS_FOCUS_DISTANCE)
                if (dist != null) {
                    state.currentFocusDistance = dist
                    withContext(Dispatchers.Main) {
                        coordinator.applyCameraControls()
                        ui.updateDialPanel()
                        ui.updateProInfoBar()
                    }
                }
            }
        }
    }

    internal fun sanitizeCropRegion(rawCrop: Rect?, activeArray: Rect): Rect {
        return rawCrop?.takeIf {
            it.width() > 0 && it.height() > 0 && activeArray.contains(it)
        } ?: activeArray
    }

    fun getMeteringRectangle(
        x: Float, y: Float,
        viewWidth: Int, viewHeight: Int,
        sensorOrientation: Int,
        lensFacing: Int,
        cropRegion: Rect
    ): MeteringRectangle {
        val safeWidth = maxOf(1, viewWidth)
        val safeHeight = maxOf(1, viewHeight)
        val normalizedX = (x / safeWidth).coerceIn(0f, 1f)
        val normalizedY = (y / safeHeight).coerceIn(0f, 1f)

        val displayRotation = when (env.displayRotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }

        val sensorToDisplay = if (lensFacing == CameraCharacteristics.LENS_FACING_FRONT) {
            (sensorOrientation + displayRotation) % 360
        } else {
            (sensorOrientation - displayRotation + 360) % 360
        }

        val matrix = Matrix()
        matrix.postRotate(-sensorToDisplay.toFloat(), 0.5f, 0.5f)
        if (lensFacing == CameraCharacteristics.LENS_FACING_FRONT) {
            matrix.postScale(-1f, 1f, 0.5f, 0.5f)
        }

        val pts = floatArrayOf(normalizedX, normalizedY)
        matrix.mapPoints(pts)

        val sx = pts[0].coerceIn(0f, 1f)
        val sy = pts[1].coerceIn(0f, 1f)

        val centerX = cropRegion.left + (sx * cropRegion.width()).toInt()
        val centerY = cropRegion.top + (sy * cropRegion.height()).toInt()

        // Robust bounding box: at least 32 pixels wide and high, clamped strictly within cropRegion
        val halfW = maxOf(16, (cropRegion.width() * 0.05f).toInt())
        val halfH = maxOf(16, (cropRegion.height() * 0.05f).toInt())

        val left = (centerX - halfW).coerceIn(cropRegion.left, cropRegion.right - 1)
        val top = (centerY - halfH).coerceIn(cropRegion.top, cropRegion.bottom - 1)
        val right = (centerX + halfW).coerceIn(left + 1, cropRegion.right)
        val bottom = (centerY + halfH).coerceIn(top + 1, cropRegion.bottom)

        return MeteringRectangle(Rect(left, top, right, bottom), MeteringRectangle.METERING_WEIGHT_MAX)
    }

    fun onDestroy() {
        tapToFocusJob?.cancel()
        tapToFocusJob = null
    }
}
