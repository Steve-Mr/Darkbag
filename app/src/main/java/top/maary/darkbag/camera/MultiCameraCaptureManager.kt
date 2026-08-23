package top.maary.darkbag.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ExifInterface
import android.media.Image
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
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors

data class PhysicalCapturedFrame(
    val lens: PhysicalLensInfo,
    val jpegData: ByteArray?,
    val tempDngPath: String?,
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
        private const val CAPTURE_TIMEOUT_MS = 8000L
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
    private val jpegImageReaders = ConcurrentHashMap<String, ImageReader>()
    private val rawImageReaders = ConcurrentHashMap<String, ImageReader>()
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
        Log.i(TAG, "Configuring MultiCamera with hardwareType=${logicalInfo.hardwareType}, lenses=${activeLenses.map { it.name }}, saveRaw=$saveRaw")

        when (logicalInfo.hardwareType) {
            MultiCameraHardwareType.NATIVE_LOGICAL -> {
                openNativeLogicalSession(logicalInfo, countPref, pairPref, targetPreviewSurface, saveRaw)
            }
            MultiCameraHardwareType.CONCURRENT_STANDALONE -> {
                openConcurrentStandaloneSessions(activeLenses, targetPreviewSurface, saveRaw)
            }
            MultiCameraHardwareType.FAST_RELAY_BURST, MultiCameraHardwareType.NONE -> {
                openRelayPrimarySession(logicalInfo, targetPreviewSurface, isInitialConfig = true)
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
        closeReaders()

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
                closeReaders()
                sessionConfig = buildNativeSessionConfiguration(device, currentSelection, targetPreviewSurface, saveRaw)
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
                openRelayPrimarySession(fallbackInfo, targetPreviewSurface, isInitialConfig = true)
            }
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
            val jpegSize = getOptimalOutputSize(lens, ImageFormat.JPEG)
            val jpegReader = ImageReader.newInstance(jpegSize.width, jpegSize.height, ImageFormat.JPEG, 2)
            jpegImageReaders[lens.physicalId] = jpegReader

            val jpegConfig = OutputConfiguration(jpegReader.surface).apply {
                setPhysicalCameraId(lens.physicalId)
            }
            outputConfigs.add(jpegConfig)

            if (isRawSupportedForLens(lens)) {
                val rawSize = getOptimalOutputSize(lens, ImageFormat.RAW_SENSOR)
                val rawReader = ImageReader.newInstance(rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, 2)
                rawImageReaders[lens.physicalId] = rawReader

                val rawConfig = OutputConfiguration(rawReader.surface).apply {
                    setPhysicalCameraId(lens.physicalId)
                }
                outputConfigs.add(rawConfig)
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
                        openRelayPrimarySession(fallbackInfo, targetPreviewSurface, isInitialConfig = true)
                    }
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
                val jpegSize = getOptimalOutputSize(lens, ImageFormat.JPEG)
                val jpegReader = ImageReader.newInstance(jpegSize.width, jpegSize.height, ImageFormat.JPEG, 2)
                jpegImageReaders[lens.physicalId] = jpegReader

                val surfaces = mutableListOf<Surface>(jpegReader.surface)
                if (isPrimary) {
                    surfaces.add(targetPreviewSurface)
                }

                if (isRawSupportedForLens(lens)) {
                    val rawSize = getOptimalOutputSize(lens, ImageFormat.RAW_SENSOR)
                    val rawReader = ImageReader.newInstance(rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, 2)
                    rawImageReaders[lens.physicalId] = rawReader
                    surfaces.add(rawReader.surface)
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
        targetPreviewSurface: Surface,
        targetLens: PhysicalLensInfo? = null,
        isInitialConfig: Boolean = false
    ) {
        val primaryLens = targetLens ?: currentPrimaryLens ?: activeLenses.find { it.type == LensType.WIDE } ?: activeLenses.firstOrNull()
        currentPrimaryLens = primaryLens
        val primaryId = primaryLens?.physicalId ?: logicalInfo.logicalCameraId

        // Close previous session and device cleanly before switching
        try {
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
        } catch (e: Exception) {
            Log.w(TAG, "Error closing previous session during relay switch", e)
        }

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
                    if (isInitialConfig) {
                        onSessionFailedListener?.invoke("Camera open error: $error")
                    }
                }
            })

            val dev = openDeferred.await()
            val jpegSize = if (primaryLens != null) getOptimalOutputSize(primaryLens, ImageFormat.JPEG) else Size(4000, 3000)
            val jpegReader = ImageReader.newInstance(jpegSize.width, jpegSize.height, ImageFormat.JPEG, 2)
            jpegImageReaders[primaryId] = jpegReader

            val outputConfigs = mutableListOf(
                OutputConfiguration(targetPreviewSurface),
                OutputConfiguration(jpegReader.surface)
            )

            if (primaryLens != null && isRawSupportedForLens(primaryLens)) {
                val rawSize = getOptimalOutputSize(primaryLens, ImageFormat.RAW_SENSOR)
                val rawReader = ImageReader.newInstance(rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, 2)
                rawImageReaders[primaryId] = rawReader
                outputConfigs.add(OutputConfiguration(rawReader.surface))
            }

            val sessionConfig = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                outputConfigs,
                executor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        Log.i(TAG, "Relay primary preview session configured on camera $primaryId")
                        captureSession = session
                        startRepeatingPreview(dev, session, targetPreviewSurface)
                        onSessionConfiguredListener?.invoke()
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.w(TAG, "Relay preview configuration failed on $primaryId")
                        if (isInitialConfig) {
                            onSessionFailedListener?.invoke("Relay preview configuration failed")
                        }
                    }
                }
            )
            dev.createCaptureSession(sessionConfig)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to open relay primary session", e)
            closeInternal()
            if (isInitialConfig) {
                onSessionFailedListener?.invoke(e.message ?: "Failed to open relay session")
            }
        }
    }

    var currentPrimaryLens: PhysicalLensInfo? = null
        private set

    fun switchPrimaryPreviewLensByMultiplier(multiplier: Float) {
        val matchingLens = activeLenses.minByOrNull { kotlin.math.abs(it.multiplier - multiplier) } ?: return
        setPrimaryPreviewLens(matchingLens)
    }

    fun setPrimaryPreviewLens(lens: PhysicalLensInfo) {
        currentPrimaryLens = lens
        val surface = previewSurface ?: return
        val hwType = currentLogicalInfo?.hardwareType ?: MultiCameraHardwareType.NATIVE_LOGICAL

        if (hwType == MultiCameraHardwareType.FAST_RELAY_BURST || cameraDevice?.id != currentLogicalInfo?.logicalCameraId) {
            scope.launch {
                val info = currentLogicalInfo ?: return@launch
                openRelayPrimarySession(info, surface, targetLens = lens)
            }
            return
        }

        val dev = cameraDevice ?: return
        val session = captureSession ?: return

        try {
            val chars = cameraManager.getCameraCharacteristics(dev.id)
            val requestBuilder = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val zoomRange = chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                    if (zoomRange != null) {
                        val clampedRatio = lens.multiplier.coerceIn(zoomRange.lower, zoomRange.upper)
                        set(CaptureRequest.CONTROL_ZOOM_RATIO, clampedRatio)
                    }
                }

                val activeArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                if (activeArray != null && lens.multiplier >= 1.0f) {
                    val cropW = (activeArray.width() / lens.multiplier).toInt()
                    val cropH = (activeArray.height() / lens.multiplier).toInt()
                    val cropRect = android.graphics.Rect(
                        activeArray.centerX() - cropW / 2,
                        activeArray.centerY() - cropH / 2,
                        activeArray.centerX() + cropW / 2,
                        activeArray.centerY() + cropH / 2
                    )
                    set(CaptureRequest.SCALER_CROP_REGION, cropRect)
                }
            }
            session.setRepeatingRequest(requestBuilder.build(), null, cameraHandler)
            Log.i(TAG, "Switched primary preview lens to: ${lens.name} (${lens.multiplier}x)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to switch primary preview lens", e)
        }
    }

    private fun startRepeatingPreview(device: CameraDevice, session: CameraCaptureSession, targetPreviewSurface: Surface) {
        try {
            if (currentPrimaryLens == null) {
                currentPrimaryLens = activeLenses.find { it.type == LensType.WIDE } ?: activeLenses.firstOrNull()
            }
            val requestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(targetPreviewSurface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                val prim = currentPrimaryLens
                if (prim != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    set(CaptureRequest.CONTROL_ZOOM_RATIO, prim.multiplier)
                }
            }
            session.setRepeatingRequest(requestBuilder.build(), null, cameraHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start repeating preview", e)
        }
    }

    fun captureMultiCamera(
        orientationDegrees: Int,
        isHdrPlusActive: Boolean = false,
        onResult: (MultiCameraCaptureResult) -> Unit,
        onError: (String) -> Unit
    ) {
        val hwType = currentLogicalInfo?.hardwareType ?: MultiCameraHardwareType.NATIVE_LOGICAL
        when (hwType) {
            MultiCameraHardwareType.NATIVE_LOGICAL -> {
                captureNativeLogical(orientationDegrees, isHdrPlusActive, onResult, onError)
            }
            MultiCameraHardwareType.CONCURRENT_STANDALONE -> {
                captureConcurrentStandalone(orientationDegrees, onResult, onError)
            }
            MultiCameraHardwareType.FAST_RELAY_BURST, MultiCameraHardwareType.NONE -> {
                captureFastRelayBurst(orientationDegrees, isHdrPlusActive, onResult, onError)
            }
        }
    }

    private fun captureNativeLogical(
        orientationDegrees: Int,
        isHdrPlusActive: Boolean = false,
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
        val physicalCaptureResults = ConcurrentHashMap<String, TotalCaptureResult>()
        val resultDeferredMap = ConcurrentHashMap<String, CompletableDeferred<TotalCaptureResult>>()
        for (lens in expectedLenses) {
            resultDeferredMap[lens.physicalId] = CompletableDeferred()
        }

        val collectedJpegs = ConcurrentHashMap<String, Pair<ByteArray, Size>>()
        val collectedDngPaths = ConcurrentHashMap<String, String>()

        val completionJob = scope.launch(Dispatchers.Default) {
            withTimeoutOrNull(CAPTURE_TIMEOUT_MS) {
                while (collectedFrames.size < totalExpected && isActive) {
                    delay(50)
                }
            }

            isCapturing = false

            if (collectedFrames.isNotEmpty()) {
                val result = MultiCameraCaptureResult(
                    baseName = baseName,
                    captureTimestampMillis = captureTimestamp,
                    frames = collectedFrames.values.toList().sortedBy { it.lens.multiplier }
                )
                withContext(Dispatchers.Main) {
                    onResult(result)
                }
            } else {
                withContext(Dispatchers.Main) {
                    onError("Capture timed out without receiving frames")
                }
            }
        }

        try {
            val captureRequest = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                for (lens in expectedLenses) {
                    jpegImageReaders[lens.physicalId]?.surface?.let { addTarget(it) }
                    rawImageReaders[lens.physicalId]?.surface?.let { addTarget(it) }
                }
                set(CaptureRequest.JPEG_ORIENTATION, orientationDegrees)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            }

            fun tryAssembleFrame(lens: PhysicalLensInfo) {
                val jpegPair = collectedJpegs[lens.physicalId]
                val dngPath = collectedDngPaths[lens.physicalId]
                if (jpegPair == null && dngPath == null) return

                val metadata = createMetadataFromCaptureResult(physicalCaptureResults[lens.physicalId], lens)
                val frame = PhysicalCapturedFrame(
                    lens = lens,
                    jpegData = jpegPair?.first,
                    tempDngPath = dngPath,
                    width = jpegPair?.second?.width ?: 4000,
                    height = jpegPair?.second?.height ?: 3000,
                    orientation = orientationDegrees,
                    captureMetadata = metadata,
                    timestamp = captureTimestamp
                )
                collectedFrames[lens.physicalId] = frame
            }

            for (lens in expectedLenses) {
                jpegImageReaders[lens.physicalId]?.setOnImageAvailableListener({ r ->
                    val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                    try {
                        val buffer = image.planes[0].buffer
                        val data = ByteArray(buffer.remaining())
                        buffer.get(data)
                        collectedJpegs[lens.physicalId] = data to Size(image.width, image.height)
                        tryAssembleFrame(lens)
                    } finally {
                        image.close()
                    }
                }, cameraHandler)

                rawImageReaders[lens.physicalId]?.setOnImageAvailableListener({ r ->
                    val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                    scope.launch(Dispatchers.IO) {
                        try {
                            val chars = lens.characteristics ?: cameraManager.getCameraCharacteristics(lens.physicalId)
                            val captureRes = withTimeoutOrNull(2500L) { resultDeferredMap[lens.physicalId]?.await() }
                            if (captureRes != null) {
                                val isPrimary = lens.physicalId == (currentPrimaryLens?.physicalId ?: expectedLenses.first().physicalId)
                                val tempPath = writeDngToFile(image, chars, captureRes, orientationDegrees, lens.physicalId, isHdrPlus = isHdrPlusActive && isPrimary)
                                if (tempPath != null) {
                                    collectedDngPaths[lens.physicalId] = tempPath
                                }
                            }
                            tryAssembleFrame(lens)
                        } finally {
                            image.close()
                        }
                    }
                }, cameraHandler)
            }

            session.capture(captureRequest.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    val physResults = result.physicalCameraResults
                    for ((pid, presult) in physResults) {
                        if (presult is TotalCaptureResult) {
                            physicalCaptureResults[pid] = presult
                            resultDeferredMap[pid]?.complete(presult)
                        }
                    }
                    for (lens in expectedLenses) {
                        if (!resultDeferredMap[lens.physicalId]!!.isCompleted) {
                            physicalCaptureResults[lens.physicalId] = result
                            resultDeferredMap[lens.physicalId]?.complete(result)
                        }
                        tryAssembleFrame(lens)
                    }
                }

                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                    Log.e(TAG, "Multi-camera capture request failed: reason=${failure.reason}")
                    completionJob.cancel()
                    isCapturing = false
                    onError("Multi-camera capture request failed: reason=${failure.reason}")
                }
            }, cameraHandler)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initiate native logical multi-camera capture", e)
            completionJob.cancel()
            isCapturing = false
            onError(e.message ?: "Failed to initiate capture")
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
        val resultDeferredMap = ConcurrentHashMap<String, CompletableDeferred<TotalCaptureResult>>()
        for (lens in expectedLenses) {
            resultDeferredMap[lens.physicalId] = CompletableDeferred()
        }

        val collectedJpegs = ConcurrentHashMap<String, Pair<ByteArray, Size>>()
        val collectedDngPaths = ConcurrentHashMap<String, String>()

        val completionJob = scope.launch(Dispatchers.Default) {
            withTimeoutOrNull(CAPTURE_TIMEOUT_MS) {
                while (collectedFrames.size < totalExpected && isActive) {
                    delay(50)
                }
            }

            isCapturing = false

            if (collectedFrames.isNotEmpty()) {
                val result = MultiCameraCaptureResult(
                    baseName = baseName,
                    captureTimestampMillis = captureTimestamp,
                    frames = collectedFrames.values.toList().sortedBy { it.lens.multiplier }
                )
                withContext(Dispatchers.Main) { onResult(result) }
            } else {
                withContext(Dispatchers.Main) { onError("Concurrent standalone capture timed out") }
            }
        }

        try {
            for (lens in expectedLenses) {
                val dev = openDevices[lens.physicalId] ?: continue
                val sess = openSessions[lens.physicalId] ?: continue
                val jpegReader = jpegImageReaders[lens.physicalId]
                val rawReader = rawImageReaders[lens.physicalId]

                fun tryAssembleFrame(lens: PhysicalLensInfo) {
                    val jpegPair = collectedJpegs[lens.physicalId]
                    val dngPath = collectedDngPaths[lens.physicalId]
                    if (jpegPair == null && dngPath == null) return

                    val metadata = createMetadataFromCaptureResult(resultDeferredMap[lens.physicalId]?.getCompleted(), lens)
                    val frame = PhysicalCapturedFrame(
                        lens = lens,
                        jpegData = jpegPair?.first,
                        tempDngPath = dngPath,
                        width = jpegPair?.second?.width ?: 4000,
                        height = jpegPair?.second?.height ?: 3000,
                        orientation = orientationDegrees,
                        captureMetadata = metadata,
                        timestamp = captureTimestamp
                    )
                    collectedFrames[lens.physicalId] = frame
                }

                jpegReader?.setOnImageAvailableListener({ r ->
                    val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                    try {
                        val buffer = image.planes[0].buffer
                        val data = ByteArray(buffer.remaining())
                        buffer.get(data)
                        collectedJpegs[lens.physicalId] = data to Size(image.width, image.height)
                        tryAssembleFrame(lens)
                    } finally {
                        image.close()
                    }
                }, cameraHandler)

                rawReader?.setOnImageAvailableListener({ r ->
                    val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                    scope.launch(Dispatchers.IO) {
                        try {
                            val chars = lens.characteristics ?: cameraManager.getCameraCharacteristics(lens.physicalId)
                            val captureRes = withTimeoutOrNull(2500L) { resultDeferredMap[lens.physicalId]?.await() }
                            if (captureRes != null) {
                                val tempPath = writeDngToFile(image, chars, captureRes, orientationDegrees, lens.physicalId)
                                if (tempPath != null) {
                                    collectedDngPaths[lens.physicalId] = tempPath
                                }
                            }
                            tryAssembleFrame(lens)
                        } finally {
                            image.close()
                        }
                    }
                }, cameraHandler)

                val req = dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    jpegReader?.surface?.let { addTarget(it) }
                    rawReader?.surface?.let { addTarget(it) }
                    set(CaptureRequest.JPEG_ORIENTATION, orientationDegrees)
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                }

                sess.capture(req.build(), object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                        resultDeferredMap[lens.physicalId]?.complete(result)
                        tryAssembleFrame(lens)
                    }

                    override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                        Log.w(TAG, "Concurrent capture failed on ${lens.physicalId}")
                    }
                }, cameraHandler)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initiating concurrent standalone capture", e)
            completionJob.cancel()
            isCapturing = false
            onError(e.message ?: "Concurrent capture failed")
        }
    }

    private fun captureFastRelayBurst(
        orientationDegrees: Int,
        isHdrPlusActive: Boolean = false,
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
                // 1. Determine currently active primary preview lens
                val primaryLens = currentPrimaryLens ?: expectedLenses.find { it.physicalId == cameraDevice?.id } ?: expectedLenses.find { it.type == LensType.WIDE } ?: expectedLenses.first()
                val primaryDev = cameraDevice
                val primarySess = captureSession
                val primaryJpegReader = jpegImageReaders[primaryLens.physicalId]
                val primaryRawReader = rawImageReaders[primaryLens.physicalId]

                if (primaryDev != null && primarySess != null && primaryJpegReader != null) {
                    val resultDeferred = CompletableDeferred<TotalCaptureResult?>()
                    val jpegDeferred = CompletableDeferred<Pair<ByteArray, Size>?>()
                    val dngDeferred = CompletableDeferred<String?>()

                    primaryJpegReader.setOnImageAvailableListener({ r ->
                        val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                        try {
                            val buffer = image.planes[0].buffer
                            val data = ByteArray(buffer.remaining())
                            buffer.get(data)
                            jpegDeferred.complete(data to Size(image.width, image.height))
                        } finally {
                            image.close()
                        }
                    }, cameraHandler)

                    if (primaryRawReader != null) {
                        primaryRawReader.setOnImageAvailableListener({ r ->
                            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                            scope.launch(Dispatchers.IO) {
                                try {
                                    val chars = primaryLens.characteristics ?: cameraManager.getCameraCharacteristics(primaryLens.physicalId)
                                    val captureRes = withTimeoutOrNull(2500L) { resultDeferred.await() }
                                    if (captureRes != null) {
                                        val tempPath = writeDngToFile(image, chars, captureRes, orientationDegrees, primaryLens.physicalId, isHdrPlus = isHdrPlusActive)
                                        dngDeferred.complete(tempPath)
                                    } else {
                                        Log.e(TAG, "Primary captureResult timed out")
                                        dngDeferred.complete(null)
                                    }
                                } finally {
                                    image.close()
                                }
                            }
                        }, cameraHandler)
                    } else {
                        dngDeferred.complete(null)
                    }

                    val req = primaryDev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                        addTarget(primaryJpegReader.surface)
                        primaryRawReader?.surface?.let { addTarget(it) }
                        set(CaptureRequest.JPEG_ORIENTATION, orientationDegrees)
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    }

                    primarySess.capture(req.build(), object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, res: TotalCaptureResult) {
                            resultDeferred.complete(res)
                        }
                        override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, failure: CaptureFailure) {
                            resultDeferred.complete(null)
                        }
                    }, cameraHandler)

                    val captureRes = withTimeoutOrNull(3000L) { resultDeferred.await() }
                    val jpegDataPair = withTimeoutOrNull(3000L) { jpegDeferred.await() }
                    val dngPath = if (primaryRawReader != null) withTimeoutOrNull(3000L) { dngDeferred.await() } else null

                    if (jpegDataPair != null || dngPath != null) {
                        val metadata = createMetadataFromCaptureResult(captureRes, primaryLens)
                        collectedFrames.add(
                            PhysicalCapturedFrame(
                                lens = primaryLens,
                                jpegData = jpegDataPair?.first,
                                tempDngPath = dngPath,
                                width = jpegDataPair?.second?.width ?: 4000,
                                height = jpegDataPair?.second?.height ?: 3000,
                                orientation = orientationDegrees,
                                captureMetadata = metadata,
                                timestamp = captureTimestamp
                            )
                        )
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

                    // Re-open primary preview session after burst relay finishes on the selected lens
                    val pSurface = previewSurface
                    val info = currentLogicalInfo
                    if (pSurface != null && info != null) {
                        openRelayPrimarySession(info, pSurface, targetLens = primaryLens)
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
                    withContext(Dispatchers.Main) { onError("No frames captured in fast relay burst") }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error in captureFastRelayBurst", e)
                isCapturing = false
                withContext(Dispatchers.Main) { onError(e.message ?: "Relay capture failed") }
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
        var jpegReader: ImageReader? = null
        var rawReader: ImageReader? = null

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

            // 1. Always create JPEG ImageReader
            val jpegSize = getOptimalOutputSize(lens, ImageFormat.JPEG)
            jpegReader = ImageReader.newInstance(jpegSize.width, jpegSize.height, ImageFormat.JPEG, 2)
            val outputConfigs = mutableListOf(OutputConfiguration(jpegReader.surface))

            // 2. Create RAW ImageReader if supported
            if (isRawSupportedForLens(lens)) {
                val rawSize = getOptimalOutputSize(lens, ImageFormat.RAW_SENSOR)
                rawReader = ImageReader.newInstance(rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, 2)
                outputConfigs.add(OutputConfiguration(rawReader.surface))
            }

            val sessionDeferred = CompletableDeferred<CameraCaptureSession>()
            val sessionConfig = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                outputConfigs,
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

            val resultDeferred = CompletableDeferred<TotalCaptureResult?>()
            val jpegDeferred = CompletableDeferred<Pair<ByteArray, Size>?>()
            val dngDeferred = CompletableDeferred<String?>()

            jpegReader.setOnImageAvailableListener({ r ->
                val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val buffer = image.planes[0].buffer
                    val data = ByteArray(buffer.remaining())
                    buffer.get(data)
                    jpegDeferred.complete(data to Size(image.width, image.height))
                } finally {
                    image.close()
                }
            }, cameraHandler)

            if (rawReader != null) {
                rawReader.setOnImageAvailableListener({ r ->
                    val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                    scope.launch(Dispatchers.IO) {
                        try {
                            val chars = lens.characteristics ?: cameraManager.getCameraCharacteristics(lens.physicalId)
                            val captureRes = withTimeoutOrNull(2500L) { resultDeferred.await() }
                            if (captureRes != null) {
                                val tempPath = writeDngToFile(image, chars, captureRes, orientationDegrees, lens.physicalId)
                                dngDeferred.complete(tempPath)
                            } else {
                                Log.e(TAG, "CaptureResult timed out for relay lens ${lens.name}")
                                dngDeferred.complete(null)
                            }
                        } finally {
                            image.close()
                        }
                    }
                }, cameraHandler)
            } else {
                dngDeferred.complete(null)
            }

            val req = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(jpegReader.surface)
                rawReader?.surface?.let { addTarget(it) }
                set(CaptureRequest.JPEG_ORIENTATION, orientationDegrees)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            }

            session.capture(req.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
                    resultDeferred.complete(result)
                }
                override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, failure: CaptureFailure) {
                    resultDeferred.complete(null)
                }
            }, cameraHandler)

            val captureRes = withTimeoutOrNull(3000L) { resultDeferred.await() }
            val jpegDataPair = withTimeoutOrNull(3000L) { jpegDeferred.await() }
            val dngPath = if (rawReader != null) withTimeoutOrNull(3000L) { dngDeferred.await() } else null

            if (jpegDataPair != null || dngPath != null) {
                val metadata = createMetadataFromCaptureResult(captureRes, lens)
                PhysicalCapturedFrame(
                    lens = lens,
                    jpegData = jpegDataPair?.first,
                    tempDngPath = dngPath,
                    width = jpegDataPair?.second?.width ?: 4000,
                    height = jpegDataPair?.second?.height ?: 3000,
                    orientation = orientationDegrees,
                    captureMetadata = metadata,
                    timestamp = System.currentTimeMillis()
                )
            } else {
                null
            }

        } catch (e: Exception) {
            Log.w(TAG, "Failed single relay capture for ${lens.name}", e)
            null
        } finally {
            try { sess?.close() } catch (e: Exception) {}
            try { dev?.close() } catch (e: Exception) {}
            try { jpegReader?.close() } catch (e: Exception) {}
            try { rawReader?.close() } catch (e: Exception) {}
        }
    }

    private fun writeDngToFile(
        rawImage: Image,
        chars: CameraCharacteristics,
        captureResult: TotalCaptureResult?,
        orientationDegrees: Int,
        lensId: String,
        isHdrPlus: Boolean = false
    ): String? {
        return try {
            if (captureResult == null) {
                Log.e(TAG, "Cannot create DNG: captureResult is null for lens $lensId")
                return null
            }
            val dngOrientation = when (orientationDegrees) {
                90 -> ExifInterface.ORIENTATION_ROTATE_90
                180 -> ExifInterface.ORIENTATION_ROTATE_180
                270 -> ExifInterface.ORIENTATION_ROTATE_270
                else -> ExifInterface.ORIENTATION_NORMAL
            }

            val tempFile = File(context.cacheDir, "dng_${lensId}_${System.currentTimeMillis()}.dng")
            val dngCreator = DngCreator(chars, captureResult)
            dngCreator.setOrientation(dngOrientation)
            dngCreator.setDescription(DarkbagIdentity.imageDescription(isHdrPlus = isHdrPlus))
            FileOutputStream(tempFile).use { out ->
                dngCreator.writeImage(out, rawImage)
            }
            dngCreator.close()
            Log.i(TAG, "Successfully written valid DNG to ${tempFile.absolutePath} (${tempFile.length()} bytes)")
            tempFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write DNG file for lens $lensId", e)
            null
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

    private fun closeReaders() {
        jpegImageReaders.values.forEach { it.close() }
        jpegImageReaders.clear()
        rawImageReaders.values.forEach { it.close() }
        rawImageReaders.clear()
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

            closeReaders()
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
