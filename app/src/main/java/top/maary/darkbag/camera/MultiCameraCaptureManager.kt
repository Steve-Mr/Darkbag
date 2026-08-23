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

    // Native Logical Multi-Camera Session State
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null

    // Standalone Concurrent Session State
    private val openDevices = ConcurrentHashMap<String, CameraDevice>()
    private val openSessions = ConcurrentHashMap<String, CameraCaptureSession>()

    private var currentLogicalInfo: LogicalMultiCameraInfo? = null
    private var currentCountPref: MultiCameraCountPreference = MultiCameraCountPreference.AUTO_MAX
    private var currentPairPref: DualLensPairPreference = DualLensPairPreference.WIDE_ULTRAWIDE
    private var currentSaveRaw: Boolean = false

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

        currentLogicalInfo = logicalInfo
        currentCountPref = countPref
        currentPairPref = pairPref
        currentSaveRaw = saveRaw
        previewSurface = targetPreviewSurface

        // Resolve candidate lenses based on user preferences
        val initialCandidates = MultiCameraHelper.resolveActivePhysicalLenses(
            logicalInfo = logicalInfo,
            countPref = countPref,
            pairPref = pairPref,
            maxHardwareSupported = 3
        )
        activeLenses = initialCandidates
        Log.i(TAG, "Configuring MultiCamera with hardwareType=${logicalInfo.hardwareType}, lenses=${activeLenses.map { it.name }}")

        when (logicalInfo.hardwareType) {
            MultiCameraHardwareType.NATIVE_LOGICAL -> {
                openNativeLogicalSession(logicalInfo, countPref, pairPref, targetPreviewSurface, saveRaw)
            }
            MultiCameraHardwareType.CONCURRENT_STANDALONE -> {
                openConcurrentStandaloneSessions(activeLenses, targetPreviewSurface, saveRaw)
            }
            MultiCameraHardwareType.FAST_RELAY_BURST, MultiCameraHardwareType.NONE -> {
                openRelayPrimarySession(logicalInfo, targetPreviewSurface)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun openNativeLogicalSession(
        logicalInfo: LogicalMultiCameraInfo,
        countPref: MultiCameraCountPreference,
        pairPref: DualLensPairPreference,
        targetPreviewSurface: Surface,
        saveRaw: Boolean
    ) {
        val logicalId = logicalInfo.logicalCameraId
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
                        openDeferred.completeExceptionally(RuntimeException("Camera open failed: $error"))
                    }
                    scope.launch { close() }
                    onSessionFailedListener?.invoke("Camera open error: $error")
                }
            })

            val device = openDeferred.await()
            createNativeLogicalMultiCameraSession(device, logicalInfo, countPref, pairPref, targetPreviewSurface, saveRaw)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to open native logical multi-camera", e)
            closeInternal()
            onSessionFailedListener?.invoke(e.message ?: "Failed to open camera")
        }
    }

    private fun createNativeLogicalMultiCameraSession(
        device: CameraDevice,
        logicalInfo: LogicalMultiCameraInfo,
        countPref: MultiCameraCountPreference,
        pairPref: DualLensPairPreference,
        targetPreviewSurface: Surface,
        saveRaw: Boolean
    ) {
        physicalImageReaders.values.forEach { it.close() }
        physicalImageReaders.clear()

        var currentSelection = activeLenses
        var sessionConfig = buildNativeSessionConfiguration(device, currentSelection, targetPreviewSurface, saveRaw)

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
                physicalImageReaders.values.forEach { it.close() }
                physicalImageReaders.clear()
                sessionConfig = buildNativeSessionConfiguration(device, currentSelection, targetPreviewSurface, saveRaw)
            }
        }

        try {
            device.createCaptureSession(sessionConfig)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create native logical capture session", e)
            onSessionFailedListener?.invoke(e.message ?: "Failed to create session")
        }
    }

    private fun buildNativeSessionConfiguration(
        device: CameraDevice,
        lenses: List<PhysicalLensInfo>,
        targetPreviewSurface: Surface,
        saveRaw: Boolean
    ): SessionConfiguration {
        val outputConfigs = mutableListOf<OutputConfiguration>()

        val previewConfig = OutputConfiguration(targetPreviewSurface)
        outputConfigs.add(previewConfig)

        for (lens in lenses) {
            val format = if (saveRaw && isRawSupportedForLens(lens)) ImageFormat.RAW_SENSOR else ImageFormat.JPEG
            val size = getOptimalOutputSize(lens, format)

            val reader = ImageReader.newInstance(size.width, size.height, format, 2)
            physicalImageReaders[lens.physicalId] = reader

            val outputConfig = OutputConfiguration(reader.surface).apply {
                setPhysicalCameraId(lens.physicalId)
            }
            outputConfigs.add(outputConfig)
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
                    Log.e(TAG, "Native multi-camera capture session configuration failed!")
                    onSessionFailedListener?.invoke("Session configuration failed")
                }
            }
        )
    }

    @SuppressLint("MissingPermission")
    private suspend fun openConcurrentStandaloneSessions(
        lenses: List<PhysicalLensInfo>,
        targetPreviewSurface: Surface,
        saveRaw: Boolean
    ) {
        val primaryLens = lenses.find { it.type == LensType.WIDE } ?: lenses.first()
        var openedCount = 0

        for (lens in lenses) {
            val isPrimary = lens.physicalId == primaryLens.physicalId
            val openDeferred = CompletableDeferred<CameraDevice>()

            try {
                cameraManager.openCamera(lens.physicalId, executor, object : CameraDevice.StateCallback() {
                    override fun onOpened(dev: CameraDevice) {
                        openDevices[lens.physicalId] = dev
                        openDeferred.complete(dev)
                    }

                    override fun onDisconnected(dev: CameraDevice) {
                        openDevices.remove(lens.physicalId)
                    }

                    override fun onError(dev: CameraDevice, error: Int) {
                        Log.e(TAG, "Concurrent camera ${lens.physicalId} error: $error")
                        if (!openDeferred.isCompleted) {
                            openDeferred.completeExceptionally(RuntimeException("Open error: $error"))
                        }
                    }
                })

                val dev = openDeferred.await()
                val format = if (saveRaw && isRawSupportedForLens(lens)) ImageFormat.RAW_SENSOR else ImageFormat.JPEG
                val size = getOptimalOutputSize(lens, format)
                val reader = ImageReader.newInstance(size.width, size.height, format, 2)
                physicalImageReaders[lens.physicalId] = reader

                val surfaces = mutableListOf<Surface>(reader.surface)
                if (isPrimary) {
                    surfaces.add(targetPreviewSurface)
                }

                val sessionDeferred = CompletableDeferred<CameraCaptureSession>()
                val sessionConfig = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    surfaces.map { OutputConfiguration(it) },
                    executor,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(sess: CameraCaptureSession) {
                            openSessions[lens.physicalId] = sess
                            if (isPrimary) {
                                startRepeatingPreview(dev, sess, targetPreviewSurface)
                            }
                            sessionDeferred.complete(sess)
                        }

                        override fun onConfigureFailed(sess: CameraCaptureSession) {
                            sessionDeferred.completeExceptionally(RuntimeException("Session failed on ${lens.physicalId}"))
                        }
                    }
                )
                dev.createCaptureSession(sessionConfig)
                sessionDeferred.await()
                openedCount++

            } catch (e: Exception) {
                Log.w(TAG, "Failed concurrent open for ${lens.name}, will fallback to relay mode", e)
                break
            }
        }

        if (openedCount == lenses.size) {
            Log.i(TAG, "Concurrent standalone sessions opened successfully for all $openedCount cameras")
            onSessionConfiguredListener?.invoke()
        } else {
            Log.w(TAG, "Concurrent open incomplete ($openedCount/${lenses.size}), falling back to Fast Relay Burst")
            closeInternal()
            val fallbackInfo = currentLogicalInfo?.copy(hardwareType = MultiCameraHardwareType.FAST_RELAY_BURST)
                ?: LogicalMultiCameraInfo("0", false, 0, lenses, MultiCameraHardwareType.FAST_RELAY_BURST)
            currentLogicalInfo = fallbackInfo
            openRelayPrimarySession(fallbackInfo, targetPreviewSurface)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun openRelayPrimarySession(
        logicalInfo: LogicalMultiCameraInfo,
        targetPreviewSurface: Surface
    ) {
        val primaryLens = activeLenses.find { it.type == LensType.WIDE } ?: activeLenses.firstOrNull()
        val primaryId = primaryLens?.physicalId ?: logicalInfo.logicalCameraId
        val openDeferred = CompletableDeferred<CameraDevice>()

        try {
            cameraManager.openCamera(primaryId, executor, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    cameraDevice = device
                    openDeferred.complete(device)
                }

                override fun onDisconnected(device: CameraDevice) {
                    scope.launch { close() }
                }

                override fun onError(device: CameraDevice, error: Int) {
                    Log.e(TAG, "Relay primary camera error: $error on $primaryId")
                    if (!openDeferred.isCompleted) {
                        openDeferred.completeExceptionally(RuntimeException("Camera open error: $error"))
                    }
                    onSessionFailedListener?.invoke("Camera open error: $error")
                }
            })

            val dev = openDeferred.await()
            val format = if (currentSaveRaw && primaryLens != null && isRawSupportedForLens(primaryLens)) ImageFormat.RAW_SENSOR else ImageFormat.JPEG
            val size = if (primaryLens != null) getOptimalOutputSize(primaryLens, format) else Size(4000, 3000)
            val reader = ImageReader.newInstance(size.width, size.height, format, 2)
            physicalImageReaders[primaryId] = reader

            val sessionConfig = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                listOf(OutputConfiguration(targetPreviewSurface), OutputConfiguration(reader.surface)),
                executor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        Log.i(TAG, "Relay primary preview session configured on camera $primaryId")
                        captureSession = session
                        startRepeatingPreview(dev, session, targetPreviewSurface)
                        onSessionConfiguredListener?.invoke()
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        onSessionFailedListener?.invoke("Relay preview configuration failed")
                    }
                }
            )
            dev.createCaptureSession(sessionConfig)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to open relay primary session", e)
            closeInternal()
            onSessionFailedListener?.invoke(e.message ?: "Failed to open relay session")
        }
    }

    private fun startRepeatingPreview(device: CameraDevice, session: CameraCaptureSession, targetPreviewSurface: Surface) {
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
        val hwType = currentLogicalInfo?.hardwareType ?: MultiCameraHardwareType.NATIVE_LOGICAL
        when (hwType) {
            MultiCameraHardwareType.NATIVE_LOGICAL -> {
                captureNativeLogical(orientationDegrees, onResult, onError)
            }
            MultiCameraHardwareType.CONCURRENT_STANDALONE -> {
                captureConcurrentStandalone(orientationDegrees, onResult, onError)
            }
            MultiCameraHardwareType.FAST_RELAY_BURST, MultiCameraHardwareType.NONE -> {
                captureFastRelayBurst(orientationDegrees, onResult, onError)
            }
        }
    }

    private fun captureNativeLogical(
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
        val baseName = DarkbagIdentity.FILE_PREFIX + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(captureTimestamp)
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
                    } catch (e: Exception) {
                        Log.e(TAG, "Error acquiring image for ${lens.name}", e)
                    } finally {
                        image.close()
                    }
                }, cameraHandler)
            }

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
            Log.e(TAG, "Failed to dispatch native multi-camera capture", e)
            completionJob.cancel()
            isCapturing = false
            onError(e.message ?: "Failed to dispatch capture")
        }
    }

    private fun captureConcurrentStandalone(
        orientationDegrees: Int,
        onResult: (MultiCameraCaptureResult) -> Unit,
        onError: (String) -> Unit
    ) {
        if (isCapturing) { onError("Capture already in progress"); return }
        isCapturing = true

        val captureTimestamp = System.currentTimeMillis()
        val baseName = DarkbagIdentity.FILE_PREFIX + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(captureTimestamp)
        val expectedLenses = activeLenses.toList()
        val totalExpected = expectedLenses.size

        val collectedFrames = ConcurrentHashMap<String, PhysicalCapturedFrame>()
        val resultsMap = ConcurrentHashMap<String, CaptureResult>()

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
                withContext(Dispatchers.Main) { onResult(result) }
            } else {
                withContext(Dispatchers.Main) { onError("Concurrent capture timed out") }
            }
        }

        try {
            for (lens in expectedLenses) {
                val dev = openDevices[lens.physicalId] ?: continue
                val sess = openSessions[lens.physicalId] ?: continue
                val reader = physicalImageReaders[lens.physicalId] ?: continue

                reader.setOnImageAvailableListener({ r ->
                    val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                    try {
                        val buffer = image.planes[0].buffer
                        val data = ByteArray(buffer.remaining())
                        buffer.get(data)
                        val isRaw = image.format == ImageFormat.RAW_SENSOR
                        val metadata = createMetadataFromCaptureResult(resultsMap[lens.physicalId], lens)
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
                    } finally {
                        image.close()
                    }
                }, cameraHandler)

                val req = dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(reader.surface)
                    set(CaptureRequest.JPEG_ORIENTATION, orientationDegrees)
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                }

                sess.capture(req.build(), object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, res: TotalCaptureResult) {
                        resultsMap[lens.physicalId] = res
                    }
                }, cameraHandler)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Concurrent standalone capture dispatch error", e)
            completionJob.cancel()
            isCapturing = false
            onError(e.message ?: "Concurrent capture failed")
        }
    }

    private fun captureFastRelayBurst(
        orientationDegrees: Int,
        onResult: (MultiCameraCaptureResult) -> Unit,
        onError: (String) -> Unit
    ) {
        if (isCapturing) { onError("Capture already in progress"); return }
        isCapturing = true

        val captureTimestamp = System.currentTimeMillis()
        val baseName = DarkbagIdentity.FILE_PREFIX + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(captureTimestamp)
        val expectedLenses = activeLenses.toList()

        scope.launch(Dispatchers.IO) {
            val collectedFrames = mutableListOf<PhysicalCapturedFrame>()

            try {
                // 1. Capture primary frame first (on currently open preview session)
                val primaryLens = expectedLenses.find { it.type == LensType.WIDE } ?: expectedLenses.first()
                val primaryDev = cameraDevice
                val primarySess = captureSession
                val primaryReader = physicalImageReaders[primaryLens.physicalId]

                if (primaryDev != null && primarySess != null && primaryReader != null) {
                    val frameDeferred = CompletableDeferred<PhysicalCapturedFrame?>()
                    var primaryResult: TotalCaptureResult? = null

                    primaryReader.setOnImageAvailableListener({ r ->
                        val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                        try {
                            val buffer = image.planes[0].buffer
                            val data = ByteArray(buffer.remaining())
                            buffer.get(data)
                            val isRaw = image.format == ImageFormat.RAW_SENSOR
                            val metadata = createMetadataFromCaptureResult(primaryResult, primaryLens)
                            frameDeferred.complete(
                                PhysicalCapturedFrame(
                                    lens = primaryLens,
                                    jpegData = if (!isRaw) data else null,
                                    rawBytes = if (isRaw) data else null,
                                    width = image.width,
                                    height = image.height,
                                    orientation = orientationDegrees,
                                    captureMetadata = metadata,
                                    timestamp = image.timestamp
                                )
                            )
                        } finally {
                            image.close()
                        }
                    }, cameraHandler)

                    val req = primaryDev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                        addTarget(primaryReader.surface)
                        set(CaptureRequest.JPEG_ORIENTATION, orientationDegrees)
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    }

                    primarySess.capture(req.build(), object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, res: TotalCaptureResult) {
                            primaryResult = res
                        }
                    }, cameraHandler)

                    val primaryFrame = withTimeoutOrNull(2000L) { frameDeferred.await() }
                    if (primaryFrame != null) {
                        collectedFrames.add(primaryFrame)
                    }
                }

                // 2. Relay sequentially to remaining lenses
                val remainingLenses = expectedLenses.filter { it.physicalId != primaryLens.physicalId }
                if (remainingLenses.isNotEmpty()) {
                    // Close primary device temporarily to free hardware
                    closeInternal()

                    for (relayLens in remainingLenses) {
                        val relayFrame = captureSingleRelayLens(relayLens, orientationDegrees)
                        if (relayFrame != null) {
                            collectedFrames.add(relayFrame)
                        }
                    }

                    // Re-open primary preview session after burst relay finishes
                    val pSurface = previewSurface
                    val info = currentLogicalInfo
                    if (pSurface != null && info != null) {
                        openRelayPrimarySession(info, pSurface)
                    }
                }

                isCapturing = false

                if (collectedFrames.isNotEmpty()) {
                    val result = MultiCameraCaptureResult(
                        baseName = baseName,
                        captureTimestampMillis = captureTimestamp,
                        frames = collectedFrames.sortedBy { it.lens.multiplier }
                    )
                    withContext(Dispatchers.Main) { onResult(result) }
                } else {
                    withContext(Dispatchers.Main) { onError("Relay capture produced no frames") }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error in captureFastRelayBurst", e)
                isCapturing = false
                withContext(Dispatchers.Main) { onError(e.message ?: "Relay capture error") }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun captureSingleRelayLens(
        lens: PhysicalLensInfo,
        orientationDegrees: Int
    ): PhysicalCapturedFrame? {
        var dev: CameraDevice? = null
        var sess: CameraCaptureSession? = null
        var reader: ImageReader? = null

        return try {
            val openDeferred = CompletableDeferred<CameraDevice>()
            cameraManager.openCamera(lens.physicalId, executor, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    dev = device
                    openDeferred.complete(device)
                }
                override fun onDisconnected(device: CameraDevice) { dev?.close(); dev = null }
                override fun onError(device: CameraDevice, error: Int) {
                    if (!openDeferred.isCompleted) openDeferred.completeExceptionally(RuntimeException("Open err $error"))
                }
            })

            val device = withTimeoutOrNull(1500L) { openDeferred.await() } ?: return null

            val format = if (currentSaveRaw && isRawSupportedForLens(lens)) ImageFormat.RAW_SENSOR else ImageFormat.JPEG
            val size = getOptimalOutputSize(lens, format)
            val imgReader = ImageReader.newInstance(size.width, size.height, format, 2)
            reader = imgReader

            val sessionDeferred = CompletableDeferred<CameraCaptureSession>()
            val sessionConfig = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                listOf(OutputConfiguration(imgReader.surface)),
                executor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        sess = session
                        sessionDeferred.complete(session)
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        sessionDeferred.completeExceptionally(RuntimeException("Relay config failed"))
                    }
                }
            )
            device.createCaptureSession(sessionConfig)
            val session = withTimeoutOrNull(1500L) { sessionDeferred.await() } ?: return null

            val frameDeferred = CompletableDeferred<PhysicalCapturedFrame?>()
            var captureRes: TotalCaptureResult? = null

            imgReader.setOnImageAvailableListener({ r ->
                val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val buffer = image.planes[0].buffer
                    val data = ByteArray(buffer.remaining())
                    buffer.get(data)
                    val isRaw = image.format == ImageFormat.RAW_SENSOR
                    val metadata = createMetadataFromCaptureResult(captureRes, lens)
                    frameDeferred.complete(
                        PhysicalCapturedFrame(
                            lens = lens,
                            jpegData = if (!isRaw) data else null,
                            rawBytes = if (isRaw) data else null,
                            width = image.width,
                            height = image.height,
                            orientation = orientationDegrees,
                            captureMetadata = metadata,
                            timestamp = image.timestamp
                        )
                    )
                } finally {
                    image.close()
                }
            }, cameraHandler)

            val req = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(imgReader.surface)
                set(CaptureRequest.JPEG_ORIENTATION, orientationDegrees)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            }

            session.capture(req.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
                    captureRes = result
                }
            }, cameraHandler)

            withTimeoutOrNull(2000L) { frameDeferred.await() }

        } catch (e: Exception) {
            Log.w(TAG, "Failed single relay capture for ${lens.name}", e)
            null
        } finally {
            try { sess?.close() } catch (e: Exception) {}
            try { dev?.close() } catch (e: Exception) {}
            try { reader?.close() } catch (e: Exception) {}
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

            openSessions.values.forEach { it.close() }
            openSessions.clear()
            openDevices.values.forEach { it.close() }
            openDevices.clear()

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
