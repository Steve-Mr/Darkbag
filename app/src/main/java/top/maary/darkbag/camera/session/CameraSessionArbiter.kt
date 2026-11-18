package top.maary.darkbag.camera.session

import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import top.maary.darkbag.camera.ConcurrentFrontCameraManager
import top.maary.darkbag.camera.MultiCameraCaptureManager
import top.maary.darkbag.camera.state.CameraRuntimeStateHolder
import top.maary.darkbag.camera.zsl.RawFrameRouter
import top.maary.darkbag.processor.LutSurfaceProcessor
import top.maary.darkbag.utils.CameraRepository
import top.maary.darkbag.utils.LensInfo

/**
 * Unified hardware session arbiter across Single-Camera ([Camera2SessionController]),
 * Multi-Camera ([MultiCameraCaptureManager] in NATIVE_LOGICAL, CONCURRENT_STANDALONE, or FAST_RELAY_BURST),
 * and Concurrent Front PiP ([ConcurrentFrontCameraManager]).
 *
 * Centralizes preview target resolution, zoom/crop application, orientation calculation,
 * and synchronized session teardown.
 */
class CameraSessionArbiter(
    private val cameraManager: CameraManager,
    private val state: CameraRuntimeStateHolder,
    val sessionController: Camera2SessionController = Camera2SessionController(),
    var isMultiCameraActiveProvider: (() -> Boolean)? = null
) {
    val camera2Lock = Mutex()

    var isMultiCameraMode: Boolean = false

    var multiCameraManager: MultiCameraCaptureManager? = null
    var concurrentFrontCameraManager: ConcurrentFrontCameraManager? = null

    var camera2PreviewSurface: Surface? = null
    var rawImageReader: ImageReader? = null
    var analysisImageReader: ImageReader? = null
    var lutProcessor: LutSurfaceProcessor? = null
    @Volatile var rawFrameRouter: RawFrameRouter? = null
    private var zslRawThread: HandlerThread? = null

    val camera2Device: CameraDevice?
        get() = sessionController.cameraDevice

    val camera2Session: CameraCaptureSession?
        get() = sessionController.captureSession

    val camera2Handler: Handler?
        get() = sessionController.backgroundHandler

    private val useMultiCameraRouting: Boolean
        get() = ((isMultiCameraActiveProvider?.invoke() ?: false) || isMultiCameraMode) &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

    val activePreviewDevice: CameraDevice?
        get() = if (useMultiCameraRouting) {
            multiCameraManager?.getPreviewDevice()
        } else {
            camera2Device
        }

    val activePreviewSession: CameraCaptureSession?
        get() = if (useMultiCameraRouting) {
            multiCameraManager?.getPreviewSession()
        } else {
            camera2Session
        }

    val activePreviewSurface: Surface?
        get() = if (useMultiCameraRouting) {
            multiCameraManager?.getPreviewSurface()
        } else {
            camera2PreviewSurface
        }

    val activePreviewHandler: Handler?
        get() = if (useMultiCameraRouting) {
            multiCameraManager?.getCameraHandler()
        } else {
            camera2Handler
        }

    val activePreviewPhysicalCharacteristics: CameraCharacteristics?
        get() = if (useMultiCameraRouting) {
            multiCameraManager?.getActivePreviewPhysicalCharacteristics()
        } else {
            null
        }

    val requiresSessionRebind: Boolean
        get() = camera2Device == null || useMultiCameraRouting

    fun updateActivePreviewRepeatingRequest(onSingleCamera: () -> Unit) {
        if (useMultiCameraRouting) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                multiCameraManager?.updatePreviewRequest()
            }
        } else {
            onSingleCamera()
        }
    }

    fun applyManualSettingsForActiveSession(
        applySingleCameraFull: () -> Unit,
        applyFocusAndExposureOnly: () -> Unit
    ) {
        if (useMultiCameraRouting) {
            applyFocusAndExposureOnly()
        } else {
            applySingleCameraFull()
        }
    }

    fun applyPreviewZoomAndCropToRequest(builder: CaptureRequest.Builder) {
        if (useMultiCameraRouting) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                multiCameraManager?.applyCurrentPreviewZoomAndCrop(builder)
            }
        } else {
            applySingleCameraCropToRequest(builder)
        }
    }

    fun applySingleCameraCropToRequest(builder: CaptureRequest.Builder) {
        val lens = state.currentLens ?: return
        if (!lens.useCamera2) return
        val deviceId = camera2Device?.id ?: lens.id
        try {
            val chars = CameraRepository.getCharacteristics(cameraManager, deviceId)
            val lscModes = chars.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_LENS_SHADING_MAP_MODES)
            if (lscModes != null && lscModes.contains(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON)) {
                builder.set(
                    CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE,
                    CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON
                )
            }
            val activeArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
            builder.set(CaptureRequest.SCALER_CROP_REGION, computeCropRegion(activeArray, lens))
        } catch (_: Exception) {
            // Characteristics unavailable in headless/mock test environment
        }
    }

    internal fun computeCropRegion(activeArray: Rect, lens: LensInfo?): Rect {
        val targetRatio = if (lens?.isZoomPreset == true && lens.targetZoomRatio != null) {
            lens.targetZoomRatio!!
        } else {
            1.0f
        }
        return if (targetRatio > 1.01f) {
            val cropW = (activeArray.width() / targetRatio).toInt()
            val cropH = (activeArray.height() / targetRatio).toInt()
            val centerX = activeArray.centerX()
            val centerY = activeArray.centerY()
            Rect(
                centerX - cropW / 2,
                centerY - cropH / 2,
                centerX + cropW / 2,
                centerY + cropH / 2
            )
        } else {
            activeArray
        }
    }

    fun ensureRawFrameRouter(
        reader: ImageReader,
        resultLookup: (Long) -> android.hardware.camera2.TotalCaptureResult?
    ): RawFrameRouter {
        rawFrameRouter?.close()
        zslRawThread?.quitSafely()
        val thread = HandlerThread("ZslRawThread").apply { start() }
        zslRawThread = thread
        val router = RawFrameRouter(reader, Handler(thread.looper), resultLookup)
        rawFrameRouter = router
        return router
    }

    fun closeRawFrameRouter() {
        rawFrameRouter?.detachRing()?.clear()
        rawFrameRouter?.close()
        rawFrameRouter = null
        zslRawThread?.quitSafely()
        zslRawThread = null
    }

    suspend fun closeSingleCameraResources() {
        camera2Lock.withLock {
            state.captureResults.clear()
            withContext(Dispatchers.Default) { closeRawFrameRouter() }
            sessionController.closeCamera()
            rawImageReader?.close()
            rawImageReader = null
            analysisImageReader?.close()
            analysisImageReader = null
            camera2PreviewSurface = null
            lutProcessor?.releaseInputSurface()
        }
    }

    suspend fun closeInactiveSessionsForModeSwitch() {
        closeAllSessions(
            closeMultiCamera = !useMultiCameraRouting,
            closeConcurrentFront = false,
            releaseSessionController = false
        )
    }

    suspend fun closeAllSessions(
        closeMultiCamera: Boolean = true,
        closeConcurrentFront: Boolean = true,
        releaseSessionController: Boolean = false
    ) {
        if (closeMultiCamera && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            multiCameraManager?.close()
        }
        if (closeConcurrentFront && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            concurrentFrontCameraManager?.stop()
        }
        closeSingleCameraResources()
        if (releaseSessionController) {
            sessionController.release()
        }
    }

    fun releaseLutProcessor() {
        lutProcessor?.release()
        lutProcessor = null
    }

    fun getCombinedOrientation(effectiveDegrees: Int = state.deviceOrientationDegrees): Int {
        val sensorOrientation = try {
            val targetId = state.currentLens?.id
                ?: if (state.lensFacing == CameraCharacteristics.LENS_FACING_BACK) "0" else "1"
            CameraRepository.getCharacteristics(cameraManager, targetId)
                .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        } catch (_: Exception) {
            0
        }

        return if (state.lensFacing == CameraCharacteristics.LENS_FACING_FRONT) {
            (sensorOrientation - effectiveDegrees + 360) % 360
        } else {
            (sensorOrientation + effectiveDegrees) % 360
        }
    }
}
