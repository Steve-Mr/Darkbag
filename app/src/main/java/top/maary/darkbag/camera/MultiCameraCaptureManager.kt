package top.maary.darkbag.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.annotation.RequiresApi
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.maary.darkbag.models.CaptureMetadata
import top.maary.darkbag.utils.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors

data class PhysicalCapturedFrame(
    val lens: PhysicalLensInfo,
    val jpegData: ByteArray?,
    val rawBytes: ByteArray?,
    val width: Int,
    val height: Int,
    val orientation: Int,
    val captureMetadata: CaptureMetadata?,
    val timestamp: Long
)

data class MultiCameraCaptureResult(
    val baseName: String,
    val captureTimestampMillis: Long,
    val frames: List<PhysicalCapturedFrame>
)

@RequiresApi(Build.VERSION_CODES.P)
class MultiCameraCaptureManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "MultiCameraCaptureMgr"
        private const val CAPTURE_TIMEOUT_MS = 6000L
    }

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val executor: Executor = Executors.newSingleThreadExecutor()

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null

    private var activeLenses: List<PhysicalLensInfo> = emptyList()
    private val physicalImageReaders = ConcurrentHashMap<String, ImageReader>()
    private var previewSurface: Surface? = null

    private val lock = Mutex()
    private var isCapturing = false

    var onSessionConfiguredListener: (() -> Unit)? = null
    var onSessionFailedListener: ((String) -> Unit)? = null

    private fun ensureThread() {
        if (cameraThread == null) {
            cameraThread = HandlerThread("MultiCameraThread").apply { start() }
            cameraHandler = Handler(cameraThread!!.looper)
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun openAndConfigure(
        logicalInfo: LogicalMultiCameraInfo,
        countPref: MultiCameraCountPreference,
        pairPref: DualLensPairPreference,
        targetPreviewSurface: Surface,
        saveRaw: Boolean = false
    ) = lock.withLock {
        ensureThread()
        closeInternal()

        previewSurface = targetPreviewSurface
        val logicalId = logicalInfo.logicalCameraId

        // Resolve candidate lenses based on user preferences
        val initialCandidates = MultiCameraHelper.resolveActivePhysicalLenses(
            logicalInfo = logicalInfo,
            countPref = countPref,
            pairPref = pairPref,
            maxHardwareSupported = 3
        )

        activeLenses = initialCandidates
        Log.i(TAG, "Opening logical camera $logicalId with candidates: ${activeLenses.map { it.name }}")

        val openDeferred = CompletableDeferred<CameraDevice>()

        try {
            cameraManager.openCamera(logicalId, executor, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    cameraDevice = device
                    openDeferred.complete(device)
                }

                override fun onDisconnected(device: CameraDevice) {
                    Log.w(TAG, "Logical camera disconnected: ${device.id}")
                    scope.launch { close() }
                }

                override fun onError(device: CameraDevice, error: Int) {
                    Log.e(TAG, "Logical camera error: $error on device: ${device.id}")
                    if (!openDeferred.isCompleted) {
                        openDeferred.completeExceptionally(RuntimeException("Camera open failed with error: $error"))
                    }
                    scope.launch { close() }
                    onSessionFailedListener?.invoke("Camera open error: $error")
                }
            })

            val device = openDeferred.await()
            createMultiCameraSession(device, logicalInfo, countPref, pairPref, targetPreviewSurface, saveRaw)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to open and configure multi-camera session", e)
            closeInternal()
            onSessionFailedListener?.invoke(e.message ?: "Failed to open camera")
        }
    }

    private fun createMultiCameraSession(
        device: CameraDevice,
        logicalInfo: LogicalMultiCameraInfo,
        countPref: MultiCameraCountPreference,
        pairPref: DualLensPairPreference,
        targetPreviewSurface: Surface,
        saveRaw: Boolean
    ) {
        // Clear previous readers
        physicalImageReaders.values.forEach { it.close() }
        physicalImageReaders.clear()

        // 1. Try with currently selected candidate lenses
        var currentSelection = activeLenses
        var sessionConfig = buildSessionConfiguration(device, currentSelection, targetPreviewSurface, saveRaw)

        // 2. Probe hardware support via isSessionConfigurationSupported (API 29+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val supported = device.isSessionConfigurationSupported(sessionConfig)
            Log.i(TAG, "Session configuration support check for ${currentSelection.map { it.name }}: $supported")

            if (!supported && currentSelection.size > 2) {
                Log.w(TAG, "Triple-camera stream configuration unsupported by HAL. Falling back to Dual camera pair.")
                currentSelection = MultiCameraHelper.resolveActivePhysicalLenses(
                    logicalInfo = logicalInfo,
                    countPref = MultiCameraCountPreference.DUAL,
                    pairPref = pairPref,
                    maxHardwareSupported = 2
                )
                activeLenses = currentSelection
                // Rebuild with fallback
                physicalImageReaders.values.forEach { it.close() }
                physicalImageReaders.clear()
                sessionConfig = buildSessionConfiguration(device, currentSelection, targetPreviewSurface, saveRaw)
                val dualSupported = device.isSessionConfigurationSupported(sessionConfig)
                Log.i(TAG, "Fallback Dual-camera support check: $dualSupported")
            }
        }

        try {
            device.createCaptureSession(sessionConfig)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create multi-camera capture session", e)
            onSessionFailedListener?.invoke(e.message ?: "Failed to create capture session")
        }
    }

    private fun buildSessionConfiguration(
        device: CameraDevice,
        lenses: List<PhysicalLensInfo>,
        targetPreviewSurface: Surface,
        saveRaw: Boolean
    ): SessionConfiguration {
        val outputConfigs = mutableListOf<OutputConfiguration>()

        // Add preview output
        val previewConfig = OutputConfiguration(targetPreviewSurface)
        outputConfigs.add(previewConfig)

        // Add physical ImageReader outputs
        for (lens in lenses) {
            val format = if (saveRaw && isRawSupportedForLens(lens)) ImageFormat.RAW_SENSOR else ImageFormat.JPEG
            val size = getOptimalOutputSize(lens, format)

            val reader = ImageReader.newInstance(size.width, size.height, format, 2)
            physicalImageReaders[lens.physicalId] = reader

            val outputConfig = OutputConfiguration(reader.surface).apply {
                setPhysicalCameraId(lens.physicalId)
            }
            outputConfigs.add(outputConfig)
            Log.d(TAG, "Configured physical stream for ${lens.name} (ID: ${lens.physicalId}) -> ${size.width}x${size.height}")
        }

        return SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            outputConfigs,
            executor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    Log.i(TAG, "Multi-camera capture session successfully configured!")
                    captureSession = session
                    startRepeatingPreview(session, targetPreviewSurface)
                    onSessionConfiguredListener?.invoke()
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "Multi-camera capture session configuration failed!")
                    onSessionFailedListener?.invoke("Session configuration failed")
                }
            }
        )
    }

    private fun startRepeatingPreview(session: CameraCaptureSession, targetPreviewSurface: Surface) {
        val device = cameraDevice ?: return
        try {
            val requestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(targetPreviewSurface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            }
            session.setRepeatingRequest(requestBuilder.build(), null, cameraHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start repeating preview", e)
        }
    }

    fun captureMultiCamera(
        orientationDegrees: Int,
        onResult: (MultiCameraCaptureResult) -> Unit,
        onError: (String) -> Unit
    ) {
        val device = cameraDevice ?: run { onError("Camera device is null"); return }
        val session = captureSession ?: run { onError("Capture session is null"); return }

        if (isCapturing) {
            onError("Capture already in progress")
            return
        }
        isCapturing = true

        val captureTimestamp = System.currentTimeMillis()
        val baseName = "Darkbag_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(captureTimestamp)
        val expectedLenses = activeLenses.toList()
        val totalExpected = expectedLenses.size

        val collectedFrames = ConcurrentHashMap<String, PhysicalCapturedFrame>()
        val physicalCaptureResults = ConcurrentHashMap<String, CaptureResult>()

        val completionJob = scope.launch(Dispatchers.Default) {
            withTimeoutOrNull(CAPTURE_TIMEOUT_MS) {
                while (collectedFrames.size < totalExpected && isActive) {
                    delay(50)
                }
            }

            isCapturing = false

            if (collectedFrames.isNotEmpty()) {
                val sortedFrames = expectedLenses.mapNotNull { collectedFrames[it.physicalId] }
                val result = MultiCameraCaptureResult(
                    baseName = baseName,
                    captureTimestampMillis = captureTimestamp,
                    frames = sortedFrames
                )
                withContext(Dispatchers.Main) {
                    onResult(result)
                }
            } else {
                withContext(Dispatchers.Main) {
                    onError("Multi-camera capture timed out with no frames")
                }
            }
        }

        try {
            // Setup individual ImageReader listeners
            for (lens in expectedLenses) {
                val reader = physicalImageReaders[lens.physicalId] ?: continue
                reader.setOnImageAvailableListener({ r ->
                    val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                    try {
                        val buffer = image.planes[0].buffer
                        val data = ByteArray(buffer.remaining())
                        buffer.get(data)

                        val isRaw = image.format == ImageFormat.RAW_SENSOR
                        val metadata = createMetadataFromCaptureResult(physicalCaptureResults[lens.physicalId], lens)

                        val frame = PhysicalCapturedFrame(
                            lens = lens,
                            jpegData = if (!isRaw) data else null,
                            rawBytes = if (isRaw) data else null,
                            width = image.width,
                            height = image.height,
                            orientation = orientationDegrees,
                            captureMetadata = metadata,
                            timestamp = image.timestamp
                        )
                        collectedFrames[lens.physicalId] = frame
                        Log.d(TAG, "Received frame for lens ${lens.name} (${collectedFrames.size}/$totalExpected)")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error acquiring image for ${lens.name}", e)
                    } finally {
                        image.close()
                    }
                }, cameraHandler)
            }

            // Build single capture request with ALL physical targets
            val captureRequest = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                for (lens in expectedLenses) {
                    physicalImageReaders[lens.physicalId]?.surface?.let { addTarget(it) }
                }
                set(CaptureRequest.JPEG_ORIENTATION, orientationDegrees)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            }

            session.capture(captureRequest.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    val physResults = result.physicalCameraResults
                    for ((pid, presult) in physResults) {
                        physicalCaptureResults[pid] = presult
                    }
                }

                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                    Log.e(TAG, "Multi-camera capture request failed: reason=${failure.reason}")
                    completionJob.cancel()
                    isCapturing = false
                    onError("Capture request failed: reason=${failure.reason}")
                }
            }, cameraHandler)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to dispatch multi-camera capture", e)
            completionJob.cancel()
            isCapturing = false
            onError(e.message ?: "Failed to dispatch capture")
        }
    }

    private fun isRawSupportedForLens(lens: PhysicalLensInfo): Boolean {
        val chars = lens.characteristics ?: return false
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return false
        return map.outputFormats.contains(ImageFormat.RAW_SENSOR)
    }

    private fun getOptimalOutputSize(lens: PhysicalLensInfo, format: Int): Size {
        val chars = lens.characteristics
        val map = chars?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(format)
        return sizes?.maxByOrNull { it.width * it.height } ?: Size(4000, 3000)
    }

    private fun createMetadataFromCaptureResult(result: CaptureResult?, lens: PhysicalLensInfo): CaptureMetadata {
        val chars = lens.characteristics
        val focalLength = lens.focalLength
        val focalLength35mm = lens.equivalentFocalLength.toInt()
        val iso = result?.get(CaptureResult.SENSOR_SENSITIVITY)
        val expTime = result?.get(CaptureResult.SENSOR_EXPOSURE_TIME)
        val fNumber = chars?.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.firstOrNull()

        return CaptureMetadata(
            iso = iso,
            exposureTime = expTime,
            fNumber = fNumber,
            focalLength = focalLength,
            focalLengthIn35mmFilm = focalLength35mm,
            dateTimeOriginal = System.currentTimeMillis(),
            make = Build.MANUFACTURER,
            model = Build.MODEL,
            lensModel = "${lens.name} (${lens.type})"
        )
    }

    private fun closeInternal() {
        try {
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
            physicalImageReaders.values.forEach { it.close() }
            physicalImageReaders.clear()
        } catch (e: Exception) {
            Log.e(TAG, "Error during closeInternal", e)
        }
    }

    suspend fun close() = lock.withLock {
        closeInternal()
        cameraThread?.quitSafely()
        cameraThread = null
        cameraHandler = null
    }

    fun getActiveLenses(): List<PhysicalLensInfo> = activeLenses
}
