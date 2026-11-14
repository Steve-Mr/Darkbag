package top.maary.darkbag.camera.dispatch

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.util.Log
import android.util.Range
import android.view.Surface
import android.view.View
import android.widget.Toast
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import top.maary.darkbag.MainApplication
import top.maary.darkbag.R
import top.maary.darkbag.databinding.CameraUiContainerBinding
import top.maary.darkbag.fragments.BurstResult
import top.maary.darkbag.fragments.HdrPlusBurst
import top.maary.darkbag.fragments.SettingsFragment
import top.maary.darkbag.models.CaptureMetadata
import top.maary.darkbag.models.StandardTimingTracker
import top.maary.darkbag.modes.CaptureModeCoordinator
import top.maary.darkbag.pipeline.sink.CaptureSink
import top.maary.darkbag.processor.ColorProcessor
import top.maary.darkbag.processor.HdrPlusProcessingService
import top.maary.darkbag.processor.HdrPlusRequest
import top.maary.darkbag.processor.HdrPlusRequestManager
import top.maary.darkbag.processor.HdrPlusStreamingBurst
import top.maary.darkbag.processor.SensorCalibrationHelper
import top.maary.darkbag.processor.StreamingBurstResult
import top.maary.darkbag.repository.ImageRepository
import top.maary.darkbag.utils.CameraRepository
import top.maary.darkbag.utils.ExposureUtils
import top.maary.darkbag.utils.HalfFrameManager
import top.maary.darkbag.utils.ImageSaver
import top.maary.darkbag.utils.LensInfo
import top.maary.darkbag.utils.LocationHelper
import top.maary.darkbag.viewmodel.CameraViewModel
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.pow

/**
 * Host contract for computational photography capture & pipeline dispatching.
 */
interface CameraCapturePipelineHost {
    val hostContext: Context
    val lifecycleScope: CoroutineScope
    val camera2Manager: CameraManager
    val camera2Device: CameraDevice?
    val camera2Session: android.hardware.camera2.CameraCaptureSession?
    val camera2PreviewSurface: Surface?
    val camera2Handler: Handler?
    val rawImageReader: ImageReader?
    val cameraViewModel: CameraViewModel
    val cameraUiContainerBinding: CameraUiContainerBinding?
    val activeCoordinator: CaptureModeCoordinator?
    val imageRepository: ImageRepository
    val locationHelper: LocationHelper
    val currentLens: LensInfo?
    val isoRange: Range<Int>?
    val exposureTimeRange: Range<Long>?
    val lastClippingRatio: Double
    val isManualIso: Boolean
    val isManualShutter: Boolean
    val isManualExposure: Boolean
    val currentIso: Int
    val currentExposureTime: Long
    val currentEvIndex: Int
    val shouldMirror: Boolean
    val captureResultFlow: SharedFlow<TotalCaptureResult>
    val captureResults: MutableMap<Long, TotalCaptureResult>
    val processingSemaphore: Semaphore
    var isBurstActive: Boolean
    var burstStartTime: Long
    var hdrPlusStreamingBurstHelper: HdrPlusStreamingBurst?
    var pendingMotionPhotoTask: CompletableDeferred<Pair<String?, Long>>?

    fun getCombinedOrientation(): Int
    fun applyManualSettingsToRequest(request: CaptureRequest.Builder, isHdrBurst: Boolean = false)
    fun writeScopedHalfFrameStep(prefs: SharedPreferences, step: Int, captureTimeMillis: Long? = null, digitalGain: Float = 1.0f, flareType: Int = -1)
    fun getDigitalGainAndUpdateStep(timestamp: Long, isFrame1Trigger: Boolean, shutterClick: Long?): Float
    fun resetBurstUi()
    fun showProcessingAnimation()
    fun hideProcessingAnimation()
    fun setGalleryThumbnail(filename: String?)
    fun triggerAutoBurst(prefs: SharedPreferences)
}

/**
 * Dispatcher coordinating single-shot and burst Camera2 captures, raw frame buffer management,
 * metadata aggregation via [CameraCaptureMetadataExtractor], spec assembly via [CameraTaskSpecAssembler],
 * and scheduling requests to the background computational photography pipeline.
 */
class CameraCapturePipelineDispatcher(
    private val host: CameraCapturePipelineHost
) {
    companion object {
        private const val TAG = "CameraCapturePipeline"
        private const val FILENAME = "yyyy-MM-dd-HH-mm-ss-SSS"
    }

    val metadataExtractor = CameraCaptureMetadataExtractor(host)
    val specAssembler = CameraTaskSpecAssembler(metadataExtractor)

    private val processingChannel = Channel<RawImageHolder>(2)
    private var processingChannelJob: Job? = null

    fun startProcessingWorker(applicationScope: CoroutineScope) {
        if (processingChannelJob == null || processingChannelJob?.isActive != true) {
            processingChannelJob = applicationScope.launch(Dispatchers.IO) {
                for (holder in processingChannel) {
                    processImageAsync(host.hostContext.applicationContext, holder)
                }
            }
        }
    }

    fun stopProcessingWorker() {
        processingChannelJob?.cancel()
        processingChannelJob = null
    }

    fun triggerAutoBurst(prefs: SharedPreferences) {
        val autoBurst = prefs.getBoolean(SettingsFragment.KEY_HALF_FRAME_AUTO_BURST, false)
        if (autoBurst) {
            host.lifecycleScope.launch(Dispatchers.Main) {
                delay(800)
                host.cameraUiContainerBinding?.cameraCaptureButton?.simulateClick()
            }
        }
    }

    fun takeSinglePictureCamera2(
        sink: CaptureSink? = null,
        timing: StandardTimingTracker? = null,
        isFrame1Trigger: Boolean = false,
        hfMetadata: HalfFrameManager.Metadata? = null
    ) {
        val appContext = host.hostContext.applicationContext
        val device = host.camera2Device ?: run { abortCapture(); return }
        val session = host.camera2Session ?: run { abortCapture(); return }
        val reader = host.rawImageReader ?: run { abortCapture(); return }
        val handler = host.camera2Handler ?: run { abortCapture(); return }

        HdrPlusRequestManager.onTaskStarted()
        var taskStarted = true

        val capturedLens = host.currentLens
        val capturedZoom = specAssembler.resolveEffectiveZoom(capturedLens, null)
        val capturedOrientation = host.getCombinedOrientation()
        val capturedLensId = capturedLens?.id

        try {
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            request.addTarget(reader.surface)
            request.set(CaptureRequest.JPEG_ORIENTATION, capturedOrientation)
            host.applyManualSettingsToRequest(request)

            reader.setOnImageAvailableListener({ r ->
                timing?.recordFrameArrival()
                timing?.captureCallback = System.currentTimeMillis()
                val image = r.acquireLatestImage() ?: run {
                    if (taskStarted) {
                        taskStarted = false
                        HdrPlusRequestManager.onTaskFinished()
                    }
                    abortCapture()
                    return@setOnImageAvailableListener
                }
                try {
                    if (image.format == android.graphics.ImageFormat.RAW_SENSOR) {
                        val digitalGain = host.getDigitalGainAndUpdateStep(
                            image.timestamp,
                            isFrame1Trigger,
                            timing?.shutterClick
                        )
                        val holder = copyAndroidImageToHolder(
                            image = image,
                            zoomRatio = capturedZoom,
                            combinedOrientation = capturedOrientation,
                            physicalId = capturedLensId,
                            halfFrameMetadata = hfMetadata?.copy(digitalGain = digitalGain),
                            sink = sink,
                            capturedLens = capturedLens
                        ).copy(timing = timing, digitalGain = digitalGain)
                        image.close()
                        onSingleFrameAccepted(timing, isFrame1Trigger)
                        (appContext as MainApplication).applicationScope.launch {
                            timing?.enqueued = System.currentTimeMillis()
                            processingChannel.send(holder)
                        }
                    } else {
                        val buffer = image.planes[0].buffer
                        val data = ByteArray(buffer.remaining())
                        buffer.get(data)
                        val ts = image.timestamp
                        image.close()
                        onSingleFrameAccepted(timing, isFrame1Trigger)
                        val captureMetadata = if (hfMetadata == null) {
                            metadataExtractor.createCaptureMetadataFromTimestamp(ts, capturedLens)
                        } else null
                        saveJpegFallback(data, 0, capturedZoom, hfMetadata, captureMetadata)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to process Camera2 image", e)
                    image.close()
                    if (taskStarted) {
                        taskStarted = false
                        HdrPlusRequestManager.onTaskFinished()
                    }
                    notifySinkFailureAsync(appContext, sink, hfMetadata, e)
                    abortCapture()
                }
            }, handler)

            session.capture(request.build(), object : android.hardware.camera2.CameraCaptureSession.CaptureCallback() {
                override fun onCaptureStarted(session: android.hardware.camera2.CameraCaptureSession, request: CaptureRequest, timestamp: Long, frameNumber: Long) {
                    if (isFrame1Trigger) {
                        val prefs = appContext.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
                        host.triggerAutoBurst(prefs)
                    }
                }

                override fun onCaptureCompleted(session: android.hardware.camera2.CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    recordCaptureResult(result)
                }
            }, handler)
        } catch (e: Exception) {
            Log.e(TAG, "Camera2 capture failed", e)
            if (taskStarted) {
                HdrPlusRequestManager.onTaskFinished()
            }
            notifySinkFailureAsync(appContext, sink, hfMetadata, e)
            abortCapture()
        }
    }

    private fun onSingleFrameAccepted(timing: StandardTimingTracker?, isFrame1Trigger: Boolean) {
        timing?.recordShutterReady()
        host.isBurstActive = false
        host.cameraViewModel.setBurstActive(false)
        host.lifecycleScope.launch(Dispatchers.Main) { host.resetBurstUi() }
        if (!isFrame1Trigger) {
            host.showProcessingAnimation()
        }
    }

    private fun abortCapture() {
        host.isBurstActive = false
        host.cameraViewModel.setBurstActive(false)
        host.lifecycleScope.launch(Dispatchers.Main) { host.resetBurstUi() }
        host.processingSemaphore.release()
    }

    fun triggerHdrPlusBurstCamera2(
        sink: CaptureSink? = null,
        isFrame1Trigger: Boolean = false,
        hfMetadata: HalfFrameManager.Metadata? = null,
        timing: StandardTimingTracker? = null
    ) {
        val appContext = host.hostContext.applicationContext
        val device = host.camera2Device ?: run { abortCapture(); return }
        val session = host.camera2Session ?: run { abortCapture(); return }
        val reader = host.rawImageReader ?: run { abortCapture(); return }
        val handler = host.camera2Handler ?: run { abortCapture(); return }

        host.isBurstActive = true
        val captureStartTime = hfMetadata?.captureTimeMillis ?: System.currentTimeMillis()
        host.burstStartTime = captureStartTime
        val isTaskStarted = AtomicBoolean(false)
        val prefs = appContext.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)

        try {
            val result = host.captureResultFlow.replayCache.lastOrNull()
            val curIso = result?.get(CaptureResult.SENSOR_SENSITIVITY) ?: 100
            val curTime = result?.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 10_000_000L
            val validIsoRange = host.isoRange ?: Range(100, 3200)
            val validTimeRange = host.exposureTimeRange ?: Range(1000L, 1_000_000_000L)
            val underexposureMode = prefs.getString(SettingsFragment.KEY_HDR_UNDEREXPOSURE_MODE, "Dynamic (Experimental)") ?: "Dynamic (Experimental)"

            val config = ExposureUtils.calculateHdrPlusExposure(
                curIso, curTime, validIsoRange, validTimeRange, underexposureMode, host.lastClippingRatio
            )
            val burstIso = if (host.isManualIso) host.currentIso else config.iso
            val burstTime = if (host.isManualShutter) host.currentExposureTime else config.exposureTime
            val burstGain = if (host.isManualExposure) {
                if (host.currentEvIndex != 0) (2.0).pow(host.currentEvIndex.toDouble() / 3.0).toFloat() else 1.0f
            } else {
                config.digitalGain
            }
            val burstSize = (prefs.getString(SettingsFragment.KEY_HDR_BURST_COUNT, "5") ?: "5").toIntOrNull() ?: 5

            if (isFrame1Trigger) {
                host.writeScopedHalfFrameStep(prefs, 1, captureStartTime, digitalGain = burstGain, flareType = hfMetadata?.flareType ?: -1)
            }

            val capturedLens = host.currentLens
            val burstLensId = capturedLens?.id ?: "0"
            val burstChars = CameraRepository.getCharacteristics(host.camera2Manager, burstLensId)
            val burstSensorOrientation = burstChars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            val whiteLevel = burstChars.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023
            val blackLevelPattern = metadataExtractor.extractBlackLevelPattern(burstChars)
            val cfa = burstChars.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: 0

            val (wb, ccmCapture) = metadataExtractor.extractWbAndCcm(result)
            val (lensShadingMapData, lensShadingRows, lensShadingCols) = metadataExtractor.findLatestLensShadingMap(result)

            val useSensorColorMatrix = true
            val burstCalib = metadataExtractor.extractSensorCalibration(burstChars, result, wb)
            val ccmSensor = burstCalib.renderCcm ?: ccmCapture
            val ccm = if (useSensorColorMatrix) ccmSensor else ccmCapture
            val ccmAlt = if (useSensorColorMatrix) ccmCapture else ccmSensor
            val noiseProfileFlat = metadataExtractor.extractNoiseProfile(result)

            val combinedOrientation = host.getCombinedOrientation()
            val fusionModeInt = specAssembler.resolveFusionModeInt(prefs)
            val currentZoom = specAssembler.resolveEffectiveZoom(capturedLens, null)

            val sessionHandle = ColorProcessor.nativeCreateStreamingSession(
                width = reader.width,
                height = reader.height,
                orientation = combinedOrientation,
                whiteLevel = whiteLevel,
                blackLevelPattern = blackLevelPattern,
                lensShadingMap = lensShadingMapData,
                lensShadingRows = lensShadingRows,
                lensShadingCols = lensShadingCols,
                whiteBalance = wb,
                ccm = ccm,
                cfaPattern = cfa,
                noiseProfile = noiseProfileFlat,
                fusionMode = fusionModeInt,
                zoomFactor = currentZoom
            )
            if (sessionHandle == 0L) {
                Log.e(TAG, "Failed to create native streaming session")
                if (isFrame1Trigger) host.writeScopedHalfFrameStep(prefs, 0)
                notifySinkFailureAsync(
                    appContext, sink, hfMetadata,
                    RuntimeException("Native streaming session creation failed"),
                    zoomFactor = currentZoom, lensId = burstLensId, timestamp = captureStartTime
                )
                abortCapture()
                return
            }

            HdrPlusRequestManager.onTaskStarted()
            isTaskStarted.set(true)

            val burstContext = StreamingBurstCaptureContext(
                sink = sink,
                digitalGain = burstGain,
                hfMetadata = hfMetadata?.copy(digitalGain = burstGain),
                timing = timing,
                width = reader.width,
                height = reader.height,
                combinedOrientation = combinedOrientation,
                targetCharId = burstLensId,
                chars = burstChars,
                result = result,
                whiteLevel = whiteLevel,
                blackLevelPattern = blackLevelPattern,
                wb = wb,
                ccm = ccm,
                ccmAlt = ccmAlt,
                exportMatrixAB = false,
                cfa = cfa,
                lensShadingMapData = lensShadingMapData,
                lensShadingRows = lensShadingRows,
                lensShadingCols = lensShadingCols,
                useSensorColorMatrix = useSensorColorMatrix,
                noiseProfileFlat = noiseProfileFlat,
                capturedLens = capturedLens,
                zoomFactor = currentZoom
            )

            val burstHelper = HdrPlusStreamingBurst(
                sessionHandle = sessionHandle,
                frameCount = burstSize,
                timing = timing,
                cfaPattern = cfa,
                onBurstComplete = { burstResult ->
                    processStreamingHdrPlusBurst(burstResult, burstContext)
                },
                onBurstFailed = { error ->
                    Log.e(TAG, "Streaming burst failed in workerJob", error)
                    if (isTaskStarted.compareAndSet(true, false)) {
                        HdrPlusRequestManager.onTaskFinished()
                    }
                    if (isFrame1Trigger) host.writeScopedHalfFrameStep(prefs, 0)
                    notifySinkFailureAsync(appContext, sink, hfMetadata, error, timestamp = captureStartTime)
                    host.isBurstActive = false
                    host.cameraViewModel.setBurstActive(false)
                    host.hdrPlusStreamingBurstHelper = null
                    host.processingSemaphore.release()
                    host.lifecycleScope.launch(Dispatchers.Main) {
                        host.hideProcessingAnimation()
                        host.resetBurstUi()
                    }
                }
            )
            host.hdrPlusStreamingBurstHelper = burstHelper

            host.lifecycleScope.launch(Dispatchers.Main) {
                host.cameraUiContainerBinding?.cameraCaptureButton?.setProgress(0f)
                host.cameraUiContainerBinding?.cameraCaptureButton?.startRotation()
                host.cameraUiContainerBinding?.cameraCaptureButton?.isEnabled = false
            }

            val burstRequests = buildBurstRequests(
                device = device,
                reader = reader,
                chars = burstChars,
                combinedOrientation = combinedOrientation,
                burstIso = burstIso,
                burstTime = burstTime,
                burstSize = burstSize
            )

            val framesCaptured = AtomicInteger(0)
            val watchdog = host.lifecycleScope.launch(Dispatchers.Main) {
                delay(8000)
                val capturedCount = framesCaptured.get()
                if (host.isBurstActive && capturedCount < burstSize) {
                    Log.e(TAG, "Burst capture timed out! Captured $capturedCount/$burstSize frames.")
                    host.isBurstActive = false
                    host.resetBurstUi()
                    if (capturedCount > 0) {
                        Log.i(TAG, "Flushing partial burst ($capturedCount frames) to background worker.")
                        if (isTaskStarted.compareAndSet(false, true)) HdrPlusRequestManager.onTaskStarted()
                        if (!isFrame1Trigger && host.cameraUiContainerBinding?.processingProgress?.visibility != View.VISIBLE) {
                            host.showProcessingAnimation()
                        }
                        burstHelper.flush()
                    } else {
                        if (isTaskStarted.compareAndSet(true, false)) HdrPlusRequestManager.onTaskFinished()
                        if (isFrame1Trigger) host.writeScopedHalfFrameStep(prefs, 0)
                        notifySinkFailureAsync(
                            appContext, sink, hfMetadata,
                            RuntimeException("Burst capture timed out with 0 frames"),
                            timestamp = captureStartTime
                        )
                        burstHelper.abort()
                        host.hdrPlusStreamingBurstHelper = null
                        host.processingSemaphore.release()
                        host.hideProcessingAnimation()
                    }
                }
            }

            reader.setOnImageAvailableListener({ r ->
                val image = r.acquireNextImage() ?: return@setOnImageAvailableListener
                val frameAccepted = try {
                    val plane = image.planes[0]
                    val frameResult = metadataExtractor.findCaptureResultSync(image.timestamp)
                    val exposureTimeNs = frameResult?.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: burstTime
                    val noisePair = frameResult?.get(CaptureResult.SENSOR_NOISE_PROFILE)
                    val npS = noisePair?.firstOrNull()?.first?.toFloat()
                        ?: noiseProfileFlat?.getOrNull(0)?.toFloat() ?: 0.0001f
                    val npO = noisePair?.firstOrNull()?.second?.toFloat()
                        ?: noiseProfileFlat?.getOrNull(1)?.toFloat() ?: 0.00001f

                    burstHelper.addFrame(
                        buffer = plane.buffer,
                        width = image.width,
                        height = image.height,
                        rowStride = plane.rowStride,
                        pixelStride = plane.pixelStride,
                        timestampNs = image.timestamp,
                        exposureTimeNs = exposureTimeNs,
                        rotationDegrees = burstSensorOrientation,
                        physicalId = burstLensId,
                        noiseProfileS = npS,
                        noiseProfileO = npO
                    )
                } finally {
                    image.close()
                }

                if (frameAccepted) {
                    timing?.recordFrameArrival()
                    val count = framesCaptured.incrementAndGet()
                    host.lifecycleScope.launch(Dispatchers.Main) {
                        host.cameraUiContainerBinding?.cameraCaptureButton?.setProgress(count.toFloat() / burstSize)
                    }
                    if (count >= burstSize) {
                        watchdog.cancel()
                        timing?.captureCallback = System.currentTimeMillis()
                        timing?.recordShutterReady()
                        host.lifecycleScope.launch(Dispatchers.Main) {
                            if (isTaskStarted.compareAndSet(false, true)) HdrPlusRequestManager.onTaskStarted()
                            host.isBurstActive = false
                            host.cameraViewModel.setBurstActive(false)
                            host.resetBurstUi()
                            if (!isFrame1Trigger && host.cameraUiContainerBinding?.processingProgress?.visibility != View.VISIBLE) {
                                host.showProcessingAnimation()
                            }
                            if (isFrame1Trigger) host.triggerAutoBurst(prefs)
                        }
                    }
                }
            }, handler)

            session.captureBurst(burstRequests, object : android.hardware.camera2.CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: android.hardware.camera2.CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    recordCaptureResult(result)
                }
            }, handler)
        } catch (e: Exception) {
            Log.e(TAG, "Camera2 burst failed", e)
            if (isTaskStarted.compareAndSet(true, false)) HdrPlusRequestManager.onTaskFinished()
            if (isFrame1Trigger) host.writeScopedHalfFrameStep(prefs, 0)
            notifySinkFailureAsync(appContext, sink, hfMetadata, e, timestamp = captureStartTime)
            host.isBurstActive = false
            host.cameraViewModel.setBurstActive(false)
            host.hdrPlusStreamingBurstHelper?.abort()
            host.hdrPlusStreamingBurstHelper = null
            host.processingSemaphore.release()
            host.lifecycleScope.launch(Dispatchers.Main) { host.resetBurstUi() }
        }
    }

    private fun buildBurstRequests(
        device: CameraDevice,
        reader: ImageReader,
        chars: CameraCharacteristics,
        combinedOrientation: Int,
        burstIso: Int,
        burstTime: Long,
        burstSize: Int
    ): List<CaptureRequest> {
        val previewSurface = host.camera2PreviewSurface
        val lscModes = chars.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_LENS_SHADING_MAP_MODES)
        val supportsLsc = lscModes != null && lscModes.contains(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON)
        return List(burstSize) {
            device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(reader.surface)
                if (previewSurface != null && previewSurface.isValid) addTarget(previewSurface)
                set(CaptureRequest.JPEG_ORIENTATION, combinedOrientation)
                host.applyManualSettingsToRequest(this, true)
                if (supportsLsc) {
                    set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON)
                }
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                set(CaptureRequest.SENSOR_SENSITIVITY, burstIso)
                set(CaptureRequest.SENSOR_EXPOSURE_TIME, burstTime)
                set(CaptureRequest.CONTROL_AWB_LOCK, true)
            }.build()
        }
    }

    private fun recordCaptureResult(result: TotalCaptureResult) {
        val timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP)
        if (timestamp != null) {
            host.captureResults[timestamp] = result
        }
        (host.captureResultFlow as? MutableSharedFlow)?.tryEmit(result)
    }

    fun copyAndroidImageToHolder(
        image: Image,
        zoomRatio: Float,
        combinedOrientation: Int,
        physicalId: String?,
        halfFrameMetadata: HalfFrameManager.Metadata? = null,
        sink: CaptureSink? = null,
        capturedLens: LensInfo? = null
    ): RawImageHolder {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = 2 // RAW16
        val width = image.width
        val height = image.height

        val sensorOrientation = try {
            CameraRepository.getCharacteristics(host.camera2Manager, physicalId ?: "0")
                .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        } catch (_: Exception) {
            0
        }

        val rowLength = width * pixelStride
        val capacity = rowLength * height
        val data = HdrPlusBurst.acquireBuffer(capacity)

        if (buffer.isDirect) {
            ColorProcessor.copyBayerWithStride(
                buffer, buffer.position(),
                data, data.position(),
                width, height, rowStride, pixelStride
            )
        } else {
            val oldPos = buffer.position()
            buffer.rewind()
            if (rowStride == rowLength) {
                if (buffer.remaining() == capacity) {
                    data.put(buffer)
                } else {
                    val oldLimit = buffer.limit()
                    buffer.limit(buffer.position() + capacity)
                    data.put(buffer)
                    buffer.limit(oldLimit)
                }
            } else {
                val oldLimit = buffer.limit()
                for (y in 0 until height) {
                    val rowStart = y * rowStride
                    if (rowStart + rowLength > buffer.capacity()) break
                    buffer.position(rowStart)
                    buffer.limit(rowStart + rowLength)
                    data.put(buffer)
                }
                buffer.limit(oldLimit)
            }
            buffer.position(oldPos)
        }
        data.limit(capacity)
        data.rewind()

        return RawImageHolder(
            data = data,
            width = width,
            height = height,
            timestamp = image.timestamp,
            rotationDegrees = sensorOrientation,
            combinedOrientation = combinedOrientation,
            zoomRatio = zoomRatio,
            physicalId = physicalId,
            halfFrameMetadata = halfFrameMetadata,
            sink = sink,
            capturedLens = capturedLens
        )
    }

    fun processHdrPlusBurst(
        burstResult: BurstResult,
        digitalGain: Float,
        hfMetadata: HalfFrameManager.Metadata? = null,
        timing: StandardTimingTracker? = null,
        capturedLens: LensInfo? = null,
        zoomFactor: Float = 1.0f
    ) {
        val effectiveLens = capturedLens ?: host.currentLens
        val currentZoom = specAssembler.resolveEffectiveZoom(capturedLens, host.currentLens, zoomFactor)
        val combinedOrientation = host.getCombinedOrientation()
        val appContext = host.hostContext.applicationContext

        (appContext as MainApplication).applicationScope.launch(Dispatchers.IO) {
            var isHdrPlusSuccess = false
            try {
                val frames = burstResult.frames
                Log.d(TAG, "processHdrPlusBurst started with ${frames.size} frames. DigitalGain=$digitalGain")

                val width = frames[0].width
                val height = frames[0].height
                val result = metadataExtractor.findCaptureResult(frames[0].timestamp)
                val targetCharId = activePhysicalId(result) ?: frames[0].physicalId ?: effectiveLens?.id ?: "0"
                val chars = CameraRepository.getCharacteristics(host.camera2Manager, targetCharId)

                val whiteLevel = chars.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023
                val blackLevelPattern = metadataExtractor.extractBlackLevelPattern(chars)
                val cfa = chars.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: 0
                val (wb, ccmCapture) = metadataExtractor.extractWbAndCcm(result)
                val useSensorColorMatrix = true
                val burstCalib = metadataExtractor.extractSensorCalibration(chars, result, wb)
                val ccmSensor = burstCalib.renderCcm ?: ccmCapture
                val finalCcm = if (useSensorColorMatrix) ccmSensor else ccmCapture
                val ccmAlt = if (useSensorColorMatrix) ccmCapture else ccmSensor

                val captureMetadata = metadataExtractor.createCaptureMetadata(
                    iso = result?.get(CaptureResult.SENSOR_SENSITIVITY) ?: 100,
                    exposureTime = result?.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 10_000_000L,
                    fNumber = result?.get(CaptureResult.LENS_APERTURE) ?: 1.8f,
                    focalLength = result?.get(CaptureResult.LENS_FOCAL_LENGTH) ?: 0.0f,
                    captureTime = System.currentTimeMillis(),
                    targetCharId = targetCharId,
                    isHdrPlus = true,
                    captureResult = result,
                    capturedLens = effectiveLens
                )

                isHdrPlusSuccess = true
                notifyQueuedOnMain(appContext, R.string.toast_hdr_queued)

                val (motionMp4Path, motionStillPtsUs) = specAssembler.awaitPendingMotionPhoto(
                    pendingTaskProvider = { host.pendingMotionPhotoTask },
                    clearPendingTask = { host.pendingMotionPhotoTask = null }
                )
                val (latchedLsc, latchedRows, latchedCols) = metadataExtractor.findLatestLensShadingMap(result)
                val prefs = appContext.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)

                val spec = specAssembler.assemble(
                    context = appContext,
                    prefs = prefs,
                    request = TaskSpecAssemblyRequest(
                        width = width,
                        height = height,
                        orientation = combinedOrientation,
                        zoomFactor = currentZoom,
                        mirror = host.shouldMirror,
                        isSingleFrame = false,
                        isHdrPlus = true,
                        targetCharId = targetCharId,
                        chars = chars,
                        captureResult = result,
                        captureMetadata = captureMetadata,
                        cfa = cfa,
                        whiteLevel = whiteLevel,
                        blackLevelPattern = blackLevelPattern,
                        wb = wb,
                        ccm = finalCcm,
                        ccmAlt = ccmAlt,
                        exportMatrixAB = false,
                        useSensorColorMatrix = useSensorColorMatrix,
                        calibration = burstCalib,
                        lensShadingMapData = latchedLsc,
                        lensShadingRows = latchedRows,
                        lensShadingCols = latchedCols,
                        noiseProfile = metadataExtractor.extractNoiseProfile(result),
                        digitalGain = digitalGain,
                        faithfulHighlights = false,
                        hfMetadata = hfMetadata,
                        timing = timing,
                        motionPhotoMp4Path = motionMp4Path,
                        motionPhotoStillPtsUs = motionStillPtsUs,
                        sink = null
                    )
                )
                val request = HdrPlusRequest.fromSpec(
                    spec = spec,
                    megaBuffer = burstResult.megaBuffer,
                    numFrames = frames.size
                )
                enqueueProcessingRequest(appContext, request, timing)
            } catch (e: Exception) {
                Log.e(TAG, "processHdrPlusBurst failed", e)
                isHdrPlusSuccess = false
            } finally {
                burstResult.frames.forEach { it.close() }
                if (!isHdrPlusSuccess) {
                    HdrPlusBurst.releaseBuffer(burstResult.megaBuffer)
                }
                host.processingSemaphore.release()
                host.lifecycleScope.launch(Dispatchers.Main) {
                    host.resetBurstUi()
                    if (!isHdrPlusSuccess) host.hideProcessingAnimation()
                }
            }
        }
    }

    fun processStreamingHdrPlusBurst(
        streamingResult: StreamingBurstResult,
        burstContext: StreamingBurstCaptureContext
    ) {
        val effectiveLens = burstContext.capturedLens ?: host.currentLens
        val currentZoom = specAssembler.resolveEffectiveZoom(
            burstContext.capturedLens, host.currentLens, burstContext.zoomFactor
        )
        val appContext = host.hostContext.applicationContext

        (appContext as MainApplication).applicationScope.launch(Dispatchers.IO) {
            var isHdrPlusSuccess = false
            try {
                val frames = streamingResult.frames
                Log.d(
                    TAG,
                    "processStreamingHdrPlusBurst started with ${frames.size} frames. " +
                        "DigitalGain=${burstContext.digitalGain}, session=${streamingResult.sessionHandle}"
                )

                val result = burstContext.result
                val burstCalib = metadataExtractor.extractSensorCalibration(burstContext.chars, result, burstContext.wb)
                val prefs = appContext.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)

                val captureMetadata = metadataExtractor.createCaptureMetadata(
                    iso = result?.get(CaptureResult.SENSOR_SENSITIVITY) ?: 100,
                    exposureTime = result?.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 10_000_000L,
                    fNumber = result?.get(CaptureResult.LENS_APERTURE) ?: 1.8f,
                    focalLength = result?.get(CaptureResult.LENS_FOCAL_LENGTH) ?: 0.0f,
                    captureTime = System.currentTimeMillis(),
                    targetCharId = burstContext.targetCharId,
                    isHdrPlus = true,
                    captureResult = result,
                    capturedLens = effectiveLens
                )

                isHdrPlusSuccess = true
                notifyQueuedOnMain(appContext, R.string.toast_hdr_queued)

                val (motionMp4Path, motionStillPtsUs) = specAssembler.awaitPendingMotionPhoto(
                    pendingTaskProvider = { host.pendingMotionPhotoTask },
                    clearPendingTask = { host.pendingMotionPhotoTask = null }
                )

                var effectiveLsc = burstContext.lensShadingMapData
                var effectiveRows = burstContext.lensShadingRows
                var effectiveCols = burstContext.lensShadingCols
                if (effectiveLsc == null || effectiveRows <= 1 || effectiveCols <= 1) {
                    val (latchedLsc, latchedRows, latchedCols) = metadataExtractor.findLatestLensShadingMap(result)
                    if (latchedLsc != null) {
                        effectiveLsc = latchedLsc
                        effectiveRows = latchedRows
                        effectiveCols = latchedCols
                    }
                }

                val spec = specAssembler.assemble(
                    context = appContext,
                    prefs = prefs,
                    request = TaskSpecAssemblyRequest(
                        width = burstContext.width,
                        height = burstContext.height,
                        orientation = burstContext.combinedOrientation,
                        zoomFactor = currentZoom,
                        mirror = host.shouldMirror,
                        isSingleFrame = false,
                        isHdrPlus = true,
                        targetCharId = burstContext.targetCharId,
                        chars = burstContext.chars,
                        captureResult = result,
                        captureMetadata = captureMetadata,
                        cfa = burstContext.cfa,
                        whiteLevel = burstContext.whiteLevel,
                        blackLevelPattern = burstContext.blackLevelPattern,
                        wb = burstContext.wb,
                        ccm = burstContext.ccm,
                        ccmAlt = burstContext.ccmAlt,
                        exportMatrixAB = burstContext.exportMatrixAB,
                        useSensorColorMatrix = burstContext.useSensorColorMatrix,
                        calibration = burstCalib,
                        lensShadingMapData = effectiveLsc,
                        lensShadingRows = effectiveRows,
                        lensShadingCols = effectiveCols,
                        noiseProfile = burstContext.noiseProfileFlat,
                        digitalGain = burstContext.digitalGain,
                        faithfulHighlights = false,
                        hfMetadata = burstContext.hfMetadata,
                        timing = burstContext.timing,
                        motionPhotoMp4Path = motionMp4Path,
                        motionPhotoStillPtsUs = motionStillPtsUs,
                        sink = burstContext.sink
                    )
                )
                val request = HdrPlusRequest.fromSpec(
                    spec = spec,
                    megaBuffer = null,
                    streamingSessionHandle = streamingResult.sessionHandle,
                    numFrames = frames.size
                )
                enqueueProcessingRequest(appContext, request, burstContext.timing)
            } catch (e: Exception) {
                Log.e(TAG, "Streaming HDR+ enqueue failed", e)
                HdrPlusRequestManager.onTaskFinished()
                isHdrPlusSuccess = false
                try {
                    ColorProcessor.nativeAbortStreamingSession(streamingResult.sessionHandle)
                } catch (abortEx: Throwable) {
                    Log.w(TAG, "Error aborting session", abortEx)
                }
                burstContext.sink?.let { s ->
                    try {
                        val failSpec = specAssembler.buildFailureSpec(
                            width = burstContext.width,
                            height = burstContext.height,
                            orientation = burstContext.combinedOrientation,
                            lensId = burstContext.targetCharId,
                            hfMetadata = burstContext.hfMetadata,
                            sink = s
                        )
                        s.onError(appContext, failSpec, e)
                    } catch (sinkErr: Throwable) {
                        Log.w(TAG, "Error invoking sink.onError", sinkErr)
                    }
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(appContext, appContext.getString(R.string.toast_hdr_failed_fallback), Toast.LENGTH_SHORT).show()
                }
            } finally {
                host.processingSemaphore.release()
                host.lifecycleScope.launch(Dispatchers.Main) {
                    host.resetBurstUi()
                    if (!isHdrPlusSuccess) host.hideProcessingAnimation()
                }
            }
        }
    }

    suspend fun processImageAsync(context: Context, image: RawImageHolder) =
        withContext(Dispatchers.IO) {
            val timing = image.timing
            timing?.processingStart = System.currentTimeMillis()
            var enqueued = false
            try {
                val captureResult = metadataExtractor.findCaptureResult(image.timestamp)
                val targetCharId = activePhysicalId(captureResult)
                    ?: image.physicalId
                    ?: image.capturedLens?.id
                    ?: host.currentLens?.id
                    ?: "0"
                val chars = CameraRepository.getCharacteristics(host.camera2Manager, targetCharId)

                val whiteLevel = chars.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023
                val blackLevelPattern = metadataExtractor.extractBlackLevelPattern(chars)
                val cfa = chars.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: 0
                val (wb, ccmCapture) = metadataExtractor.extractWbAndCcm(captureResult)
                val (lensShadingMapData, lensShadingRows, lensShadingCols) =
                    metadataExtractor.findLatestLensShadingMap(captureResult)

                val prefs = context.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
                val captureMetadata = metadataExtractor.createCaptureMetadata(
                    iso = captureResult?.get(CaptureResult.SENSOR_SENSITIVITY) ?: 100,
                    exposureTime = captureResult?.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 10_000_000L,
                    fNumber = captureResult?.get(CaptureResult.LENS_APERTURE) ?: 1.8f,
                    focalLength = captureResult?.get(CaptureResult.LENS_FOCAL_LENGTH) ?: 0.0f,
                    captureTime = System.currentTimeMillis(),
                    targetCharId = targetCharId,
                    isHdrPlus = false,
                    captureResult = captureResult,
                    capturedLens = image.capturedLens
                )

                notifyQueuedOnMain(host.hostContext, R.string.toast_processing_queued)

                var motionMp4Path = image.motionPhotoMp4Path
                var motionStillPtsUs = image.motionPhotoStillPtsUs
                if (motionMp4Path == null && host.pendingMotionPhotoTask != null) {
                    val (path, pts) = specAssembler.awaitPendingMotionPhoto(
                        pendingTaskProvider = { host.pendingMotionPhotoTask },
                        clearPendingTask = { host.pendingMotionPhotoTask = null }
                    )
                    motionMp4Path = path
                    motionStillPtsUs = pts
                }

                val singleCalib = metadataExtractor.extractSensorCalibration(chars, captureResult, wb)
                val useSensorColorMatrix = true
                val finalCcm = if (useSensorColorMatrix && singleCalib.renderCcm != null) {
                    singleCalib.renderCcm
                } else {
                    ccmCapture
                }

                val spec = specAssembler.assemble(
                    context = context,
                    prefs = prefs,
                    request = TaskSpecAssemblyRequest(
                        width = image.width,
                        height = image.height,
                        orientation = image.combinedOrientation,
                        zoomFactor = image.zoomRatio,
                        mirror = host.shouldMirror,
                        isSingleFrame = true,
                        isHdrPlus = false,
                        fusionModeOverride = 0,
                        targetCharId = targetCharId,
                        chars = chars,
                        captureResult = captureResult,
                        captureMetadata = captureMetadata,
                        cfa = cfa,
                        whiteLevel = whiteLevel,
                        blackLevelPattern = blackLevelPattern,
                        wb = wb,
                        ccm = finalCcm,
                        ccmAlt = null,
                        exportMatrixAB = false,
                        useSensorColorMatrix = useSensorColorMatrix,
                        calibration = singleCalib,
                        lensShadingMapData = lensShadingMapData,
                        lensShadingRows = lensShadingRows,
                        lensShadingCols = lensShadingCols,
                        noiseProfile = metadataExtractor.extractNoiseProfile(captureResult),
                        digitalGain = image.digitalGain,
                        faithfulHighlights = true,
                        hfMetadata = image.halfFrameMetadata,
                        timing = timing,
                        motionPhotoMp4Path = motionMp4Path,
                        motionPhotoStillPtsUs = motionStillPtsUs,
                        sink = image.sink
                    ),
                    frameTimestampOverride = image.timestamp
                )
                val request = HdrPlusRequest.fromSpec(
                    spec = spec,
                    megaBuffer = image.data,
                    numFrames = 1
                )
                HdrPlusRequestManager.enqueue(request, alreadyTracked = true)
                enqueued = true
                startProcessingService(context)

                timing?.let {
                    val baselineReport = SensorCalibrationHelper.formatHardwareBaselineLog(
                        cfaPattern = cfa,
                        blackLevelPattern = blackLevelPattern,
                        lensShadingMap = lensShadingMapData,
                        lensShadingRows = lensShadingRows,
                        lensShadingCols = lensShadingCols,
                        whiteBalance = wb,
                        digitalGain = image.digitalGain
                    )
                    Log.i(TAG, baselineReport)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in background processing", e)
                image.sink?.let { s ->
                    try {
                        val failSpec = specAssembler.buildFailureSpec(
                            width = image.width,
                            height = image.height,
                            orientation = image.combinedOrientation,
                            timestamp = image.timestamp,
                            hfMetadata = image.halfFrameMetadata,
                            sink = s
                        )
                        s.onError(context, failSpec, e)
                    } catch (sinkErr: Throwable) {
                        Log.w(TAG, "Error in image.sink.onError", sinkErr)
                    }
                }
            } finally {
                if (!enqueued) {
                    HdrPlusRequestManager.onTaskFinished()
                    HdrPlusBurst.releaseBuffer(image.data)
                }
            }
        }

    fun saveJpegFallback(
        data: ByteArray,
        rotationDegrees: Int,
        zoomFactor: Float,
        halfFrameMetadata: HalfFrameManager.Metadata? = null,
        captureMetadata: CaptureMetadata? = null
    ) {
        val appContext = host.hostContext.applicationContext
        val mirror = host.shouldMirror

        (appContext as MainApplication).applicationScope.launch(Dispatchers.IO) {
            try {
                val name = SimpleDateFormat(FILENAME, Locale.US).format(System.currentTimeMillis())
                val bmpFile = File(appContext.cacheDir, "temp_$name.jpg")
                FileOutputStream(bmpFile).use { it.write(data) }

                val jpgFolderUri = appContext.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
                    .getString(SettingsFragment.KEY_JPG_STORAGE_URI, null)

                val (motionMp4Path, motionStillPtsUs) = specAssembler.awaitPendingMotionPhoto(
                    pendingTaskProvider = { host.pendingMotionPhotoTask },
                    clearPendingTask = { host.pendingMotionPhotoTask = null }
                )

                val uri = ImageSaver.saveProcessedImage(
                    context = appContext,
                    inputBitmap = null,
                    bmpPath = bmpFile.absolutePath,
                    rotationDegrees = rotationDegrees,
                    zoomFactor = zoomFactor,
                    baseName = name,
                    linearDngPath = null,
                    saveJpg = true,
                    jpgFolderUri = jpgFolderUri,
                    mirror = mirror,
                    halfFrameMetadata = halfFrameMetadata,
                    captureMetadata = captureMetadata,
                    motionPhotoMp4Path = motionMp4Path,
                    motionPhotoStillPtsUs = motionStillPtsUs
                )
                withContext(Dispatchers.Main) {
                    val uiPrefs = appContext.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
                    if (uri != null) {
                        host.imageRepository.invalidateCache()
                        uiPrefs.edit().putString(SettingsFragment.KEY_LAST_CAPTURE_URI, uri.toString()).apply()
                        host.setGalleryThumbnail(uri.toString())
                    } else if (host.activeCoordinator?.shouldClearThumbnailOnCapture() == true) {
                        host.setGalleryThumbnail(null)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save JPEG fallback", e)
            } finally {
                HdrPlusRequestManager.onTaskFinished()
                host.processingSemaphore.release()
                withContext(Dispatchers.Main) {
                    host.resetBurstUi()
                    host.cameraUiContainerBinding?.cameraCaptureButton?.isEnabled = true
                    if (host.activeCoordinator?.keepsProcessingAnimationActive() != true) {
                        host.hideProcessingAnimation()
                    }
                }
            }
        }
    }

    private fun activePhysicalId(result: CaptureResult?): String? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && result != null) {
            result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)
        } else null
    }

    private suspend fun notifyQueuedOnMain(toastContext: Context, toastResId: Int) {
        withContext(Dispatchers.Main) {
            Toast.makeText(toastContext, toastContext.getString(toastResId), Toast.LENGTH_SHORT).show()
            if (host.activeCoordinator?.shouldClearThumbnailOnCapture() == true) {
                host.setGalleryThumbnail(null)
            }
        }
    }

    private fun enqueueProcessingRequest(
        appContext: Context,
        request: HdrPlusRequest,
        timing: StandardTimingTracker?
    ) {
        timing?.enqueued = System.currentTimeMillis()
        HdrPlusRequestManager.enqueue(request, alreadyTracked = true)
        startProcessingService(appContext)
    }

    private fun startProcessingService(context: Context) {
        val serviceIntent = Intent(context, HdrPlusProcessingService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }

    private fun notifySinkFailureAsync(
        appContext: Context,
        sink: CaptureSink?,
        hfMetadata: HalfFrameManager.Metadata?,
        error: Throwable,
        zoomFactor: Float = 1.0f,
        lensId: String = "0",
        timestamp: Long = System.currentTimeMillis()
    ) {
        if (sink == null || hfMetadata == null) return
        (appContext as MainApplication).applicationScope.launch {
            val failSpec = specAssembler.buildFailureSpec(
                zoomFactor = zoomFactor,
                lensId = lensId,
                timestamp = timestamp,
                hfMetadata = hfMetadata,
                sink = sink
            )
            sink.onError(appContext, failSpec, error)
        }
    }
}
