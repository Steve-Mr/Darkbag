package top.maary.darkbag.camera.session

import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.MeteringRectangle
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
import top.maary.darkbag.camera.motion.SceneMotionMonitor
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
    val sceneMotionMonitor = SceneMotionMonitor()
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

    internal data class CropMeteringPlan(
        val afRegions: Array<MeteringRectangle>?,
        val aeRegions: Array<MeteringRectangle>?,
        val awbRegions: Array<MeteringRectangle>?
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is CropMeteringPlan) return false
            return afRegions.contentEquals(other.afRegions) &&
                aeRegions.contentEquals(other.aeRegions) &&
                awbRegions.contentEquals(other.awbRegions)
        }

        override fun hashCode(): Int {
            var result = afRegions?.contentHashCode() ?: 0
            result = 31 * result + (aeRegions?.contentHashCode() ?: 0)
            result = 31 * result + (awbRegions?.contentHashCode() ?: 0)
            return result
        }
    }

    fun applySingleCameraCropToRequest(
        builder: CaptureRequest.Builder,
        hasUserAfRegion: Boolean = state.focusMeteringRegion != null,
        hasUserAeRegion: Boolean = state.exposureMeteringRegion != null,
        isManualFocus: Boolean = state.isManualFocus,
        isManualExposure: Boolean = state.isManualExposure,
        isManualWb: Boolean = state.isManualWb
    ) {
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

            val maxAf = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
            val maxAe = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0
            val maxAwb = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AWB) ?: 0
            val meteringPlan = resolveCropMeteringPlan(
                activeArray = activeArray,
                lens = lens,
                maxAfRegions = maxAf,
                maxAeRegions = maxAe,
                maxAwbRegions = maxAwb,
                hasUserAfRegion = hasUserAfRegion,
                hasUserAeRegion = hasUserAeRegion,
                isManualFocus = isManualFocus,
                isManualExposure = isManualExposure,
                isManualWb = isManualWb
            )
            meteringPlan.afRegions?.let { builder.set(CaptureRequest.CONTROL_AF_REGIONS, it) }
            meteringPlan.aeRegions?.let { builder.set(CaptureRequest.CONTROL_AE_REGIONS, it) }
            meteringPlan.awbRegions?.let { builder.set(CaptureRequest.CONTROL_AWB_REGIONS, it) }
        } catch (_: Exception) {
            // Characteristics unavailable in headless/mock test environment
        }
    }

    internal fun computeCropMeteringRect(activeArray: Rect, lens: LensInfo?): MeteringRectangle? {
        val targetRatio = if (lens?.isZoomPreset == true && lens.targetZoomRatio != null) {
            lens.targetZoomRatio!!
        } else {
            1.0f
        }
        if (targetRatio <= 1.05f || activeArray.width() <= 0 || activeArray.height() <= 0) {
            return null
        }
        val cropRegion = computeCropRegion(activeArray, lens)
        val rectW = (cropRegion.width() * 0.8f).toInt().coerceIn(1, activeArray.width())
        val rectH = (cropRegion.height() * 0.8f).toInt().coerceIn(1, activeArray.height())
        val maxLeft = (activeArray.right - rectW).coerceAtLeast(activeArray.left)
        val maxTop = (activeArray.bottom - rectH).coerceAtLeast(activeArray.top)
        val left = (cropRegion.centerX() - rectW / 2).coerceIn(activeArray.left, maxLeft)
        val top = (cropRegion.centerY() - rectH / 2).coerceIn(activeArray.top, maxTop)
        return MeteringRectangle(left, top, rectW, rectH, MeteringRectangle.METERING_WEIGHT_MAX)
    }

    internal fun resolveCropMeteringPlan(
        activeArray: Rect,
        lens: LensInfo?,
        maxAfRegions: Int,
        maxAeRegions: Int,
        maxAwbRegions: Int,
        hasUserAfRegion: Boolean,
        hasUserAeRegion: Boolean,
        isManualFocus: Boolean,
        isManualExposure: Boolean,
        isManualWb: Boolean
    ): CropMeteringPlan {
        val cropRect = computeCropMeteringRect(activeArray, lens)
        return if (cropRect != null) {
            CropMeteringPlan(
                afRegions = if (!isManualFocus && !hasUserAfRegion && maxAfRegions > 0) arrayOf(cropRect) else null,
                aeRegions = if (!isManualExposure && !hasUserAeRegion && maxAeRegions > 0) arrayOf(cropRect) else null,
                awbRegions = if (!isManualWb && maxAwbRegions > 0) arrayOf(cropRect) else null
            )
        } else {
            CropMeteringPlan(
                afRegions = null,
                aeRegions = null,
                awbRegions = if (!isManualWb && maxAwbRegions > 0) {
                    arrayOf(MeteringRectangle(0, 0, 0, 0, 0))
                } else {
                    null
                }
            )
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
            sceneMotionMonitor.reset()
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
