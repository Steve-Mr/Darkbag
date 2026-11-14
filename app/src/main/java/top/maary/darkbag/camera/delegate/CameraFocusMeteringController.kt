package top.maary.darkbag.camera.delegate

import android.content.Context
import android.graphics.Matrix
import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
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
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import top.maary.darkbag.R
import top.maary.darkbag.databinding.FragmentCameraBinding
import top.maary.darkbag.utils.CameraRepository
import kotlin.coroutines.resume

/**
 * Host contract for focus and metering control.
 */
interface CameraFocusMeteringHost {
    val hostContext: Context
    val lifecycleScope: CoroutineScope
    val fragmentCameraBinding: FragmentCameraBinding?
    val camera2Device: CameraDevice?
    val camera2Session: CameraCaptureSession?
    val camera2PreviewSurface: Surface?
    val camera2Handler: Handler?
    val camera2Manager: CameraManager
    val lensFacing: Int
    val displayRotation: Int
    val isManualFocus: Boolean
    val isManualExposure: Boolean
    val captureResultFlow: SharedFlow<TotalCaptureResult>
    var focusMeteringRegion: MeteringRectangle?
    var exposureMeteringRegion: MeteringRectangle?
    var currentFocusDistance: Float

    fun applyCameraControls()
    fun applyManualSettingsToRequest(builder: CaptureRequest.Builder)
    fun updateProInfoBar()
    fun updateDialPanel()
}

/**
 * Controller responsible for autofocus (AF) and autoexposure (AE) tap-to-focus triggers,
 * AF-L lock state transitions, focus ring visual animations, and manual focus synchronization.
 */
class CameraFocusMeteringController(
    private val host: CameraFocusMeteringHost,
    private val gestureDelegate: CameraGestureDelegate
) {
    companion object {
        private const val TAG = "CameraFocusMetering"
    }

    private var tapToFocusJob: Job? = null

    fun setupTapToFocus(stageView: View) {
        gestureDelegate.setupTapToFocus(stageView)
    }

    fun unlockFocusAndMetering() {
        val hadFocusLock = host.focusMeteringRegion != null || host.exposureMeteringRegion != null
        if (!hadFocusLock) return

        tapToFocusJob?.cancel()
        tapToFocusJob = null

        host.focusMeteringRegion = null
        host.exposureMeteringRegion = null

        val uiAction = {
            hideFocusRingUnlock()
            host.updateProInfoBar()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            uiAction()
        } else {
            Handler(Looper.getMainLooper()).post(uiAction)
        }

        val handler = host.camera2Handler
        val cameraAction = {
            val device = host.camera2Device
            val session = host.camera2Session
            val surface = host.camera2PreviewSurface
            if (device != null && session != null && surface != null && handler != null) {
                try {
                    if (!host.isManualFocus) {
                        val cancelRequest = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                        cancelRequest.addTarget(surface)
                        host.applyManualSettingsToRequest(cancelRequest)
                        cancelRequest.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
                        session.capture(cancelRequest.build(), null, handler)
                    }
                    host.applyCameraControls()
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
        val device = host.camera2Device ?: return
        val session = host.camera2Session ?: return
        val surface = host.camera2PreviewSurface ?: return
        val handler = host.camera2Handler ?: return

        tapToFocusJob?.cancel()
        tapToFocusJob = host.lifecycleScope.launch(Dispatchers.Default) {
            try {
                val characteristics = CameraRepository.getCharacteristics(host.camera2Manager, device.id)
                val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
                val activeArray = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return@launch

                val lastResult = host.captureResultFlow.replayCache.lastOrNull()
                val cropRegion = lastResult?.get(CaptureResult.SCALER_CROP_REGION) ?: activeArray

                val region = getMeteringRectangle(
                    x, y,
                    host.fragmentCameraBinding?.viewFinderStage?.width ?: 0,
                    host.fragmentCameraBinding?.viewFinderStage?.height ?: 0,
                    sensorOrientation,
                    host.lensFacing,
                    cropRegion
                )

                host.focusMeteringRegion = region
                if (!host.isManualExposure) {
                    host.exposureMeteringRegion = region
                }

                // 1. Show scanning visual feedback immediately on UI
                withContext(Dispatchers.Main) {
                    showFocusRingScanning(x, y)
                    host.updateProInfoBar()
                }

                // 2. Submit cancel request cleanly and suspend until processed by HAL
                val cancelRequest = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                cancelRequest.addTarget(surface)
                host.applyManualSettingsToRequest(cancelRequest)
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
                    host.applyCameraControls()
                }

                ensureActive()

                // 4. Submit trigger request (CONTROL_AF_TRIGGER_START)
                val triggerRequest = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                triggerRequest.addTarget(surface)
                host.applyManualSettingsToRequest(triggerRequest)
                triggerRequest.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region))
                if (!host.isManualExposure) {
                    triggerRequest.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(region))
                }
                if (host.isManualFocus) {
                    triggerRequest.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                }
                triggerRequest.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)

                suspendCancellableCoroutine<Unit> { cont ->
                    try {
                        session.capture(triggerRequest.build(), object : CameraCaptureSession.CaptureCallback() {
                            override fun onCaptureCompleted(
                                session: CameraCaptureSession,
                                request: CaptureRequest,
                                result: TotalCaptureResult
                            ) {
                                super.onCaptureCompleted(session, request, result)
                                if (host.isManualFocus) {
                                    syncManualFocusAfterTap()
                                }
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

                // 5. Await AF convergence (FOCUSED_LOCKED or NOT_FOCUSED_LOCKED)
                if (!host.isManualFocus) {
                    val convergenceResult = withTimeoutOrNull(3500L) {
                        host.captureResultFlow.first { res ->
                            val afState = res.get(CaptureResult.CONTROL_AF_STATE)
                            afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                            afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED
                        }
                    }

                    ensureActive()

                    val afState = convergenceResult?.get(CaptureResult.CONTROL_AF_STATE)
                    val isFocused = afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                        (convergenceResult != null && afState != CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED)

                    withContext(Dispatchers.Main) {
                        if (isFocused) {
                            showFocusRingLocked()
                            host.updateProInfoBar()
                        } else {
                            showFocusRingFailed()
                        }
                    }
                }
            } catch (e: CancellationException) {
                // Ignore cancellation
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
        val binding = host.fragmentCameraBinding ?: return
        val size = host.hostContext.resources.getDimension(R.dimen.focus_ring_size)
        gestureDelegate.showFocusRingScanning(binding, x, y, size)
    }

    fun showFocusRingLocked() {
        val binding = host.fragmentCameraBinding ?: return
        gestureDelegate.showFocusRingLocked(binding, host.focusMeteringRegion != null)
    }

    fun showFocusRingFailed() {
        val binding = host.fragmentCameraBinding ?: return
        gestureDelegate.showFocusRingFailed(binding)
    }

    fun hideFocusRingUnlock() {
        val binding = host.fragmentCameraBinding ?: return
        gestureDelegate.hideFocusRingUnlock(binding)
    }

    fun syncManualFocusAfterTap() {
        host.lifecycleScope.launch(Dispatchers.Default) {
            withTimeoutOrNull(3000) {
                host.captureResultFlow.first { res ->
                    val afState = res.get(CaptureResult.CONTROL_AF_STATE)
                    afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                            afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED
                }
            }?.let { res ->
                val dist = res.get(CaptureResult.LENS_FOCUS_DISTANCE)
                if (dist != null) {
                    host.currentFocusDistance = dist
                    withContext(Dispatchers.Main) {
                        host.applyCameraControls()
                        host.updateDialPanel()
                        host.updateProInfoBar()
                    }
                }
            }
        }
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

        val displayRotation = when (host.displayRotation) {
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
