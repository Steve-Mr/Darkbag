package top.maary.darkbag.camera.multicam

import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import top.maary.darkbag.utils.CameraRepository
import top.maary.darkbag.utils.DualLensPairPreference
import top.maary.darkbag.utils.LogicalMultiCameraInfo
import top.maary.darkbag.utils.MultiCameraCountPreference
import top.maary.darkbag.utils.MultiCameraHardwareType
import top.maary.darkbag.utils.MultiCameraHelper
import top.maary.darkbag.utils.PhysicalLensInfo
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor

@RequiresApi(Build.VERSION_CODES.P)
internal class MultiCameraPreviewController(
    private val cameraManager: CameraManager,
    private val scope: CoroutineScope,
    private val executor: Executor,
    private val dngWriter: MultiCamDngMetadataWriter,
    private val onFallbackToRelay: suspend (LogicalMultiCameraInfo, Surface) -> Unit
) {
    companion object {
        private const val TAG = "MultiCamPreviewCtrl"
    }

    var cameraThread: HandlerThread? = null
    var cameraHandler: Handler? = null

    var cameraDevice: CameraDevice? = null
    var captureSession: CameraCaptureSession? = null

    val openDevices = ConcurrentHashMap<String, CameraDevice>()
    val openSessions = ConcurrentHashMap<String, CameraCaptureSession>()
    var standalonePrimaryPhysicalId: String? = null

    var currentLogicalInfo: LogicalMultiCameraInfo? = null
    var currentCountPref: MultiCameraCountPreference = MultiCameraCountPreference.AUTO_MAX
    var currentPairPref: DualLensPairPreference = DualLensPairPreference.WIDE_ULTRAWIDE
    var currentSaveRaw: Boolean = false

    var activeLenses: List<PhysicalLensInfo> = emptyList()
    val jpegImageReaders = ConcurrentHashMap<String, ImageReader>()
    val rawImageReaders = ConcurrentHashMap<String, ImageReader>()
    var previewSurface: Surface? = null
    var currentPrimaryLens: PhysicalLensInfo? = null

    @Volatile
    var isCapturing: Boolean = false

    @Volatile
    var lastPreviewResult: CaptureResult? = null

    var onSessionConfiguredListener: (() -> Unit)? = null
    var onSessionFailedListener: ((String) -> Unit)? = null
    var onPreviewCaptureResultListener: ((TotalCaptureResult) -> Unit)? = null
    var previewRequestConfigProvider: ((CaptureRequest.Builder) -> Unit)? = null

    private val previewCaptureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            lastPreviewResult = result
            onPreviewCaptureResultListener?.invoke(result)
        }
    }

    fun ensureThread() {
        if (cameraThread == null) {
            cameraThread = HandlerThread("MultiCameraThread").apply { start() }
            cameraHandler = Handler(cameraThread!!.looper)
        }
    }

    fun shutdownThread() {
        cameraThread?.quitSafely()
        cameraThread = null
        cameraHandler = null
    }

    fun createNativeLogicalMultiCameraSession(
        device: CameraDevice,
        logicalInfo: LogicalMultiCameraInfo,
        pairPref: DualLensPairPreference,
        targetPreviewSurface: Surface
    ) {
        closeReaders()

        var currentSelection = activeLenses
        var sessionConfig = buildNativeSessionConfiguration(device, currentSelection, targetPreviewSurface)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val supported = device.isSessionConfigurationSupported(sessionConfig)
            Log.i(TAG, "Native session configuration support check: $supported")

            if (!supported && currentSelection.size > 2) {
                Log.w(TAG, "Triple-camera stream configuration unsupported. Falling back to Dual camera.")
                currentSelection = MultiCameraHelper.resolveActivePhysicalLenses(
                    logicalInfo = logicalInfo,
                    countPref = MultiCameraCountPreference.DUAL,
                    pairPref = pairPref,
                    maxHardwareSupported = 2
                )
                activeLenses = currentSelection
                closeReaders()
                sessionConfig = buildNativeSessionConfiguration(device, currentSelection, targetPreviewSurface)
            }
        }

        try {
            device.createCaptureSession(sessionConfig)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create native logical capture session, falling back to Fast Relay Burst", e)
            val fallbackInfo = currentLogicalInfo?.copy(hardwareType = MultiCameraHardwareType.FAST_RELAY_BURST)
                ?: LogicalMultiCameraInfo(device.id, false, 0, activeLenses, MultiCameraHardwareType.FAST_RELAY_BURST)
            currentLogicalInfo = fallbackInfo
            scope.launch {
                closeInternal()
                onFallbackToRelay(fallbackInfo, targetPreviewSurface)
            }
        }
    }

    fun buildNativeSessionConfiguration(
        device: CameraDevice,
        lenses: List<PhysicalLensInfo>,
        targetPreviewSurface: Surface
    ): SessionConfiguration {
        val outputConfigs = mutableListOf(OutputConfiguration(targetPreviewSurface))

        for (lens in lenses) {
            val jpegSize = dngWriter.getOptimalOutputSize(lens, ImageFormat.JPEG)
            val jpegReader = ImageReader.newInstance(jpegSize.width, jpegSize.height, ImageFormat.JPEG, 2)
            jpegImageReaders[lens.physicalId] = jpegReader
            outputConfigs.add(OutputConfiguration(jpegReader.surface).apply { setPhysicalCameraId(lens.physicalId) })

            if (dngWriter.isRawSupportedForLens(lens)) {
                val rawSize = dngWriter.getOptimalOutputSize(lens, ImageFormat.RAW_SENSOR)
                val rawReader = ImageReader.newInstance(rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, 2)
                rawImageReaders[lens.physicalId] = rawReader
                outputConfigs.add(OutputConfiguration(rawReader.surface).apply { setPhysicalCameraId(lens.physicalId) })
            }
        }

        return SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            outputConfigs,
            executor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    Log.i(TAG, "Native multi-camera capture session successfully configured!")
                    captureSession = session
                    startRepeatingPreview(device, session, targetPreviewSurface)
                    onSessionConfiguredListener?.invoke()
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.w(TAG, "Native multi-camera capture session configuration failed, falling back to Fast Relay Burst")
                    val fallbackInfo = currentLogicalInfo?.copy(hardwareType = MultiCameraHardwareType.FAST_RELAY_BURST)
                        ?: LogicalMultiCameraInfo(device.id, false, 0, activeLenses, MultiCameraHardwareType.FAST_RELAY_BURST)
                    currentLogicalInfo = fallbackInfo
                    scope.launch {
                        closeInternal()
                        onFallbackToRelay(fallbackInfo, targetPreviewSurface)
                    }
                }
            }
        )
    }

    fun getPreviewDevice(): CameraDevice? {
        return if (currentLogicalInfo?.hardwareType == MultiCameraHardwareType.CONCURRENT_STANDALONE) {
            val pid = standalonePrimaryPhysicalId ?: currentPrimaryLens?.physicalId
            if (pid != null) openDevices[pid] else null
        } else {
            cameraDevice
        }
    }

    fun getPreviewSession(): CameraCaptureSession? {
        return if (currentLogicalInfo?.hardwareType == MultiCameraHardwareType.CONCURRENT_STANDALONE) {
            val pid = standalonePrimaryPhysicalId ?: currentPrimaryLens?.physicalId
            if (pid != null) openSessions[pid] else null
        } else {
            captureSession
        }
    }

    fun resolveActivePreviewPhysicalId(): String? {
        return if (currentLogicalInfo?.hardwareType == MultiCameraHardwareType.CONCURRENT_STANDALONE) {
            standalonePrimaryPhysicalId ?: currentPrimaryLens?.physicalId
        } else {
            currentPrimaryLens?.physicalId
        }
    }

    fun resolveActivePreviewLens(): PhysicalLensInfo? {
        val pid = resolveActivePreviewPhysicalId()
        return if (pid != null) {
            activeLenses.find { it.physicalId == pid } ?: currentPrimaryLens
        } else {
            currentPrimaryLens
        }
    }

    fun getActivePreviewPhysicalCharacteristics(): CameraCharacteristics? =
        resolveActivePreviewLens()?.characteristics

    fun getActivePreviewZoomRatio(): Float {
        val dev = getPreviewDevice() ?: return 1.0f
        val isNativeLogical = currentLogicalInfo?.isLogicalMultiCamera == true &&
            dev.id == currentLogicalInfo?.logicalCameraId
        val lens = resolveActivePreviewLens() ?: return 1.0f
        if (!isNativeLogical || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return 1.0f
        return try {
            val chars = CameraRepository.getCharacteristics(cameraManager, dev.id)
            val zoomRange = chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            zoomRange?.let { lens.multiplier.coerceIn(it.lower, it.upper) } ?: 1.0f
        } catch (_: Exception) {
            1.0f
        }
    }

    fun applyCurrentPreviewZoomAndCrop(builder: CaptureRequest.Builder) {
        val dev = getPreviewDevice() ?: return
        try {
            val chars = CameraRepository.getCharacteristics(cameraManager, dev.id)
            applyPreviewZoomAndCrop(builder, chars, dev.id, resolveActivePreviewLens())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to apply preview zoom/crop: ${e.message}")
        }
    }

    fun updatePreviewRequest() {
        val handler = cameraHandler ?: return
        handler.post {
            val dev = getPreviewDevice() ?: return@post
            val session = getPreviewSession() ?: return@post
            val surface = previewSurface ?: return@post
            try {
                val chars = CameraRepository.getCharacteristics(cameraManager, dev.id)
                val builder = buildPreviewRequest(dev, surface, chars, resolveActivePreviewLens())
                session.setRepeatingRequest(builder.build(), previewCaptureCallback, handler)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update multi-camera repeating preview request", e)
            }
        }
    }

    fun switchPrimaryPreviewLensByMultiplier(multiplier: Float, onRelayReopen: (LogicalMultiCameraInfo, Surface, PhysicalLensInfo) -> Unit) {
        val matchingLens = activeLenses.minByOrNull { kotlin.math.abs(it.multiplier - multiplier) } ?: return
        setPrimaryPreviewLens(matchingLens, onRelayReopen)
    }

    fun setPrimaryPreviewLens(
        lens: PhysicalLensInfo,
        onRelayReopen: (LogicalMultiCameraInfo, Surface, PhysicalLensInfo) -> Unit
    ) {
        currentPrimaryLens = lens
        val surface = previewSurface ?: return
        val hwType = currentLogicalInfo?.hardwareType ?: MultiCameraHardwareType.NATIVE_LOGICAL

        if (hwType == MultiCameraHardwareType.CONCURRENT_STANDALONE) {
            updatePreviewRequest()
            return
        }

        if (hwType == MultiCameraHardwareType.FAST_RELAY_BURST ||
            hwType == MultiCameraHardwareType.NONE ||
            cameraDevice?.id != currentLogicalInfo?.logicalCameraId
        ) {
            val info = currentLogicalInfo ?: return
            onRelayReopen(info, surface, lens)
            return
        }

        val dev = cameraDevice ?: return
        val session = captureSession ?: return
        val handler = cameraHandler
        val updateAction = Runnable {
            try {
                val chars = CameraRepository.getCharacteristics(cameraManager, dev.id)
                val requestBuilder = buildPreviewRequest(dev, surface, chars, lens)
                session.setRepeatingRequest(requestBuilder.build(), previewCaptureCallback, handler)
                triggerFast3AWarmup(dev, session, surface, lens)
                Log.i(TAG, "Switched primary preview lens to: ${lens.name} (${lens.multiplier}x)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to switch primary preview lens", e)
            }
        }
        if (handler != null) handler.post(updateAction) else updateAction.run()
    }

    fun buildPreviewRequest(
        device: CameraDevice,
        targetPreviewSurface: Surface,
        chars: CameraCharacteristics,
        lens: PhysicalLensInfo?
    ): CaptureRequest.Builder {
        return device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(targetPreviewSurface)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            previewRequestConfigProvider?.invoke(this)
            applyPreviewZoomAndCrop(this, chars, device.id, lens)
        }
    }

    fun startRepeatingPreview(device: CameraDevice, session: CameraCaptureSession, targetPreviewSurface: Surface) {
        try {
            if (currentPrimaryLens == null) {
                currentPrimaryLens = activeLenses.find { it.type == top.maary.darkbag.utils.LensType.WIDE } ?: activeLenses.firstOrNull()
            }
            val prim = currentPrimaryLens
            val chars = CameraRepository.getCharacteristics(cameraManager, device.id)
            val requestBuilder = buildPreviewRequest(device, targetPreviewSurface, chars, prim)
            session.setRepeatingRequest(requestBuilder.build(), previewCaptureCallback, cameraHandler)
            triggerFast3AWarmup(device, session, targetPreviewSurface, prim)
            Log.i(TAG, "Started repeating preview with lens: ${prim?.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start repeating preview", e)
        }
    }

    fun applyPreviewZoomAndCrop(
        builder: CaptureRequest.Builder,
        chars: CameraCharacteristics,
        deviceId: String,
        lens: PhysicalLensInfo?
    ) {
        val isNativeLogical = currentLogicalInfo?.isLogicalMultiCamera == true &&
            deviceId == currentLogicalInfo?.logicalCameraId
        val activeArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)

        if (isNativeLogical && lens != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val zoomRange = chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                if (zoomRange != null) {
                    builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, lens.multiplier.coerceIn(zoomRange.lower, zoomRange.upper))
                }
            } else if (activeArray != null && lens.multiplier >= 1.0f) {
                val cropW = (activeArray.width() / lens.multiplier).toInt()
                val cropH = (activeArray.height() / lens.multiplier).toInt()
                val cropRect = Rect(
                    activeArray.centerX() - cropW / 2,
                    activeArray.centerY() - cropH / 2,
                    activeArray.centerX() + cropW / 2,
                    activeArray.centerY() + cropH / 2
                )
                builder.set(CaptureRequest.SCALER_CROP_REGION, cropRect)
            }
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val zoomRange = chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                if (zoomRange != null) {
                    builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, 1.0f.coerceIn(zoomRange.lower, zoomRange.upper))
                }
            }
            if (activeArray != null) {
                builder.set(CaptureRequest.SCALER_CROP_REGION, activeArray)
            }
        }
    }

    private fun triggerFast3AWarmup(
        device: CameraDevice,
        session: CameraCaptureSession,
        targetPreviewSurface: Surface,
        lens: PhysicalLensInfo?
    ) {
        try {
            val chars = CameraRepository.getCharacteristics(cameraManager, device.id)
            val triggerReq = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(targetPreviewSurface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
                set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START)
                val isNativeLogical = currentLogicalInfo?.isLogicalMultiCamera == true &&
                    device.id == currentLogicalInfo?.logicalCameraId
                if (isNativeLogical && lens != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val zoomRange = chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                    if (zoomRange != null) {
                        set(CaptureRequest.CONTROL_ZOOM_RATIO, lens.multiplier.coerceIn(zoomRange.lower, zoomRange.upper))
                    }
                }
            }
            session.capture(triggerReq.build(), null, cameraHandler)
            Log.i(TAG, "Fast 3A (AF/AE) warmup triggered on camera ${device.id} for lens ${lens?.name}")
        } catch (e: Exception) {
            Log.w(TAG, "Fast 3A warmup skipped: ${e.message}")
        }
    }

    fun closeReaders() {
        jpegImageReaders.values.forEach { it.close() }
        jpegImageReaders.clear()
        rawImageReaders.values.forEach { it.close() }
        rawImageReaders.clear()
    }

    fun closeInternal() {
        try {
            lastPreviewResult = null
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null

            openSessions.values.forEach { it.close() }
            openSessions.clear()
            openDevices.values.forEach { it.close() }
            openDevices.clear()
            standalonePrimaryPhysicalId = null

            closeReaders()
        } catch (e: Exception) {
            Log.e(TAG, "Error during closeInternal", e)
        }
    }

    fun configurePreviewStateForTesting(
        logicalInfo: LogicalMultiCameraInfo,
        lenses: List<PhysicalLensInfo>,
        primaryLens: PhysicalLensInfo?,
        standalonePrimaryId: String?
    ) {
        currentLogicalInfo = logicalInfo
        activeLenses = lenses
        currentPrimaryLens = primaryLens
        standalonePrimaryPhysicalId = standalonePrimaryId
    }
}
