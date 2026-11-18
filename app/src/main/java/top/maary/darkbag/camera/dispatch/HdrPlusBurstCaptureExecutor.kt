package top.maary.darkbag.camera.dispatch

import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.media.ImageReader
import android.util.Log
import android.util.Range
import android.view.View
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.maary.darkbag.camera.delegate.CameraCoordinatorHost
import top.maary.darkbag.camera.delegate.CameraEnvironmentHost
import top.maary.darkbag.camera.delegate.CameraUiHost
import top.maary.darkbag.camera.zsl.ZslBurstSeed
import top.maary.darkbag.camera.zsl.RawFrameRouter
import top.maary.darkbag.models.StandardTimingTracker
import top.maary.darkbag.pipeline.sink.CaptureSink
import top.maary.darkbag.processor.ColorProcessor
import top.maary.darkbag.processor.HdrPlusRequestManager
import top.maary.darkbag.processor.HdrPlusStreamingBurst
import top.maary.darkbag.processor.StreamingBurstFrame
import top.maary.darkbag.processor.StreamingBurstResult
import top.maary.darkbag.processor.StreamingBufferPool
import top.maary.darkbag.repository.SettingsRepository
import top.maary.darkbag.utils.CameraRepository
import top.maary.darkbag.utils.ExposureUtils
import top.maary.darkbag.utils.HalfFrameManager
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.pow

internal class HdrPlusBurstCaptureExecutor(
    private val env: CameraEnvironmentHost,
    private val ui: CameraUiHost,
    private val coordinator: CameraCoordinatorHost,
    private val metadataExtractor: CameraCaptureMetadataExtractor,
    private val specAssembler: CameraTaskSpecAssembler,
    private val onStreamingBurstComplete: (StreamingBurstResult, StreamingBurstCaptureContext) -> Unit,
    private val onRecordCaptureResult: (TotalCaptureResult) -> Unit,
    private val onNotifySinkFailure: (Context, CaptureSink?, HalfFrameManager.Metadata?, Throwable, Float, String, Long) -> Unit,
    private val onAbortCapture: () -> Unit
) {
    companion object {
        private const val TAG = "HdrPlusBurstExec"
    }

    fun triggerHdrPlusBurstCamera2(
        sink: CaptureSink? = null,
        isFrame1Trigger: Boolean = false,
        hfMetadata: HalfFrameManager.Metadata? = null,
        timing: StandardTimingTracker? = null,
        seed: ZslBurstSeed? = null
    ): Boolean {
        val appContext = env.hostContext.applicationContext
        val device = env.sessionArbiter.camera2Device ?: run {
            if (seed != null) seed.release() else onAbortCapture()
            return false
        }
        val session = env.sessionArbiter.camera2Session ?: run {
            if (seed != null) seed.release() else onAbortCapture()
            return false
        }
        val reader = env.sessionArbiter.rawImageReader ?: run {
            if (seed != null) seed.release() else onAbortCapture()
            return false
        }
        val handler = env.sessionArbiter.camera2Handler ?: run {
            if (seed != null) seed.release() else onAbortCapture()
            return false
        }

        env.runtimeState.isBurstActive = true
        val captureStartTime = hfMetadata?.captureTimeMillis ?: System.currentTimeMillis()
        env.runtimeState.burstStartTime = captureStartTime
        val isTaskStarted = AtomicBoolean(false)
        val currentPrefs = SettingsRepository.getInstance(appContext).current()
        val capturedLens = env.runtimeState.currentLens
        val burstLensId = capturedLens?.id ?: "0"
        val currentZoom = specAssembler.resolveEffectiveZoom(capturedLens, null)
        var watchdog: Job? = null

        var seedEnqueued = 0
        try {
            val result = seed?.baseResult ?: env.runtimeState.captureResultFlow.replayCache.lastOrNull()
            val curIso = result?.get(CaptureResult.SENSOR_SENSITIVITY) ?: 100
            val curTime = result?.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 10_000_000L
            val validIsoRange = env.runtimeState.isoRange ?: Range(100, 3200)
            val validTimeRange = env.runtimeState.exposureTimeRange ?: Range(1000L, 1_000_000_000L)

            val config = ExposureUtils.calculateHdrPlusExposure(
                curIso, curTime, validIsoRange, validTimeRange,
                currentPrefs.hdrUnderexposureMode, env.runtimeState.lastClippingRatio
            )
            val burstIso = seed?.iso ?: if (env.runtimeState.isManualIso) env.runtimeState.currentIso else config.iso
            val burstTime = seed?.exposureTimeNs
                ?: if (env.runtimeState.isManualShutter) env.runtimeState.currentExposureTime else config.exposureTime
            val burstGain = seed?.digitalGain ?: if (env.runtimeState.isManualExposure) {
                if (env.runtimeState.currentEvIndex != 0) (2.0).pow(env.runtimeState.currentEvIndex.toDouble() / 3.0).toFloat() else 1.0f
            } else {
                config.digitalGain
            }
            val burstSize = seed?.targetFrameCount ?: currentPrefs.hdrBurstCount

            if (isFrame1Trigger) {
                coordinator.writeScopedHalfFrameStep(
                    1, captureStartTime, digitalGain = burstGain, flareType = hfMetadata?.flareType ?: -1
                )
            }

            val burstChars = CameraRepository.getCharacteristics(env.camera2Manager, burstLensId)
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

            val combinedOrientation = coordinator.getCombinedOrientation()
            val fusionModeInt = specAssembler.resolveFusionModeInt(currentPrefs)

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
                if (seed != null) {
                    // Let the caller fall back to the forward burst without emitting a sink error.
                    seed.release()
                    env.runtimeState.isBurstActive = false
                    env.cameraViewModel.setBurstActive(false)
                    return false
                }
                if (isFrame1Trigger) coordinator.writeScopedHalfFrameStep(0)
                onNotifySinkFailure(
                    appContext, sink, hfMetadata,
                    RuntimeException("Native streaming session creation failed"),
                    currentZoom, burstLensId, captureStartTime
                )
                onAbortCapture()
                return false
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
                lockFirstFrameAsReference = seed != null,
                onBurstComplete = { burstResult -> onStreamingBurstComplete(burstResult, burstContext) },
                onBurstFailed = { error ->
                    Log.e(TAG, "Streaming burst failed in workerJob", error)
                    if (isTaskStarted.compareAndSet(true, false)) HdrPlusRequestManager.onTaskFinished()
                    if (isFrame1Trigger) coordinator.writeScopedHalfFrameStep(0)
                    onNotifySinkFailure(appContext, sink, hfMetadata, error, currentZoom, burstLensId, captureStartTime)
                    env.runtimeState.isBurstActive = false
                    env.cameraViewModel.setBurstActive(false)
                    env.runtimeState.hdrPlusStreamingBurstHelper?.abort()
                    env.runtimeState.hdrPlusStreamingBurstHelper = null
                    env.sessionArbiter.rawFrameRouter?.endCapture()
                    env.lifecycleScope.launch(Dispatchers.Main) {
                        ui.hideProcessingAnimation()
                        ui.resetBurstUi()
                    }
                }
            )
            env.runtimeState.hdrPlusStreamingBurstHelper = burstHelper

            env.lifecycleScope.launch(Dispatchers.Main) {
                ui.cameraUiContainerBinding?.cameraCaptureButton?.setProgress(0f)
                ui.cameraUiContainerBinding?.cameraCaptureButton?.startRotation()
                ui.cameraUiContainerBinding?.cameraCaptureButton?.isEnabled = false
            }

            val framesCaptured = AtomicInteger(seed?.preRollCount ?: 0)
            val framesComplete = AtomicBoolean(false)
            var router: RawFrameRouter? = null

            fun onBurstFramesComplete() {
                if (!framesComplete.compareAndSet(false, true)) return
                watchdog?.cancel()
                router?.endCapture()
                timing?.captureCallback = System.currentTimeMillis()
                timing?.recordShutterReady()
                env.lifecycleScope.launch(Dispatchers.Main) {
                    if (isTaskStarted.compareAndSet(false, true)) HdrPlusRequestManager.onTaskStarted()
                    env.runtimeState.isBurstActive = false
                    env.cameraViewModel.setBurstActive(false)
                    ui.resetBurstUi()
                    if (!isFrame1Trigger && ui.cameraUiContainerBinding?.processingProgress?.visibility != View.VISIBLE) {
                        ui.showProcessingAnimation()
                    }
                    if (isFrame1Trigger) coordinator.triggerAutoBurst()
                }
            }

            fun handleIncomingImage(image: Image) {
                val frameAccepted = try {
                    val plane = image.planes[0]
                    val frameResult = metadataExtractor.findCaptureResultExact(image.timestamp)
                        ?: metadataExtractor.findCaptureResultSync(image.timestamp)
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
                    env.lifecycleScope.launch(Dispatchers.Main) {
                        ui.cameraUiContainerBinding?.cameraCaptureButton?.setProgress(count.toFloat() / burstSize)
                    }
                    if (count >= burstSize) {
                        onBurstFramesComplete()
                    }
                }
            }

            if (seed != null) {
                val seedFrames = seed.frames
                for (frame in seedFrames) {
                    val frameResult = frame.result
                    val noisePair = frameResult?.get(CaptureResult.SENSOR_NOISE_PROFILE)
                    val npS = noisePair?.firstOrNull()?.first?.toFloat()
                        ?: noiseProfileFlat?.getOrNull(0)?.toFloat() ?: 0.0001f
                    val npO = noisePair?.firstOrNull()?.second?.toFloat()
                        ?: noiseProfileFlat?.getOrNull(1)?.toFloat() ?: 0.00001f
                    val streamingFrame = StreamingBurstFrame(
                        width = frame.width,
                        height = frame.height,
                        timestampNs = frame.timestamp,
                        exposureTimeNs = frame.exposureTimeNs ?: burstTime,
                        rotationDegrees = burstSensorOrientation,
                        physicalId = burstLensId,
                        noiseProfileS = npS,
                        noiseProfileO = npO
                    )
                    if (!burstHelper.enqueuePreloadedFrame(frame.buffer, streamingFrame)) {
                        throw IllegalStateException("HDR+ ZSL ring frame was rejected by the burst")
                    }
                    seedEnqueued++
                }
                env.lifecycleScope.launch(Dispatchers.Main) {
                    ui.cameraUiContainerBinding?.cameraCaptureButton?.setProgress(
                        seed.preRollCount.toFloat() / burstSize
                    )
                }
            }

            if (framesCaptured.get() >= burstSize) {
                router?.endCapture()
                onBurstFramesComplete()
            } else {
                val remaining = burstSize - framesCaptured.get()
                val burstRequests = buildBurstRequests(
                    device, reader, burstChars, combinedOrientation, burstIso, burstTime, remaining
                )
                watchdog = launchBurstWatchdog(
                    appContext, sink, isFrame1Trigger, hfMetadata, captureStartTime,
                    currentZoom, burstLensId, burstSize, framesCaptured, isTaskStarted, burstHelper
                )
                router = env.sessionArbiter.rawFrameRouter
                if (router != null) {
                    if (!router.beginCapture(::handleIncomingImage)) {
                        throw IllegalStateException("Failed to switch RAW router to capture mode")
                    }
                } else {
                    reader.setOnImageAvailableListener({ r ->
                        r.acquireNextImage()?.let(::handleIncomingImage)
                    }, handler)
                }
                session.captureBurst(burstRequests, object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureStarted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        timestamp: Long,
                        frameNumber: Long
                    ) {
                        env.sessionArbiter.rawFrameRouter?.onCaptureStarted(timestamp)
                    }

                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        onRecordCaptureResult(result)
                    }
                }, handler)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Camera2 burst failed", e)
            watchdog?.cancel()
            if (seed != null) {
                seed.releaseFrom(seedEnqueued)
            }
            env.sessionArbiter.rawFrameRouter?.endCapture()
            if (isTaskStarted.compareAndSet(true, false)) HdrPlusRequestManager.onTaskFinished()
            if (isFrame1Trigger) coordinator.writeScopedHalfFrameStep(0)
            onNotifySinkFailure(appContext, sink, hfMetadata, e, currentZoom, burstLensId, captureStartTime)
            env.runtimeState.isBurstActive = false
            env.cameraViewModel.setBurstActive(false)
            env.runtimeState.hdrPlusStreamingBurstHelper?.abort()
            env.runtimeState.hdrPlusStreamingBurstHelper = null
            env.lifecycleScope.launch(Dispatchers.Main) { ui.resetBurstUi() }
            // A seed-backed failure has already been surfaced through the sink; returning true
            // prevents CameraModeController from launching a second forward burst.
            return seed != null
        }
        return true
    }

    private fun launchBurstWatchdog(
        appContext: Context,
        sink: CaptureSink?,
        isFrame1Trigger: Boolean,
        hfMetadata: HalfFrameManager.Metadata?,
        captureStartTime: Long,
        currentZoom: Float,
        burstLensId: String,
        burstSize: Int,
        framesCaptured: AtomicInteger,
        isTaskStarted: AtomicBoolean,
        burstHelper: HdrPlusStreamingBurst
    ): Job = env.lifecycleScope.launch(Dispatchers.Main) {
        delay(8000)
        val capturedCount = framesCaptured.get()
        if (env.runtimeState.isBurstActive && capturedCount < burstSize) {
            Log.e(TAG, "Burst capture timed out! Captured $capturedCount/$burstSize frames.")
            env.sessionArbiter.rawFrameRouter?.endCapture()
            env.runtimeState.isBurstActive = false
            ui.resetBurstUi()
            if (capturedCount > 0) {
                Log.i(TAG, "Flushing partial burst ($capturedCount frames) to background worker.")
                if (isTaskStarted.compareAndSet(false, true)) HdrPlusRequestManager.onTaskStarted()
                if (!isFrame1Trigger && ui.cameraUiContainerBinding?.processingProgress?.visibility != View.VISIBLE) {
                    ui.showProcessingAnimation()
                }
                burstHelper.flush()
            } else {
                if (isTaskStarted.compareAndSet(true, false)) HdrPlusRequestManager.onTaskFinished()
                if (isFrame1Trigger) coordinator.writeScopedHalfFrameStep(0)
                onNotifySinkFailure(
                    appContext, sink, hfMetadata,
                    RuntimeException("Burst capture timed out with 0 frames"),
                    currentZoom, burstLensId, captureStartTime
                )
                burstHelper.abort()
                env.runtimeState.hdrPlusStreamingBurstHelper = null
                ui.hideProcessingAnimation()
            }
        }
    }

    fun buildBurstRequests(
        device: CameraDevice,
        reader: ImageReader,
        chars: CameraCharacteristics,
        combinedOrientation: Int,
        burstIso: Int,
        burstTime: Long,
        burstSize: Int
    ): List<CaptureRequest> {
        val previewSurface = env.sessionArbiter.camera2PreviewSurface
        val lscModes = chars.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_LENS_SHADING_MAP_MODES)
        val supportsLsc = lscModes != null && lscModes.contains(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON)
        return List(burstSize) {
            device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(reader.surface)
                if (previewSurface != null && previewSurface.isValid) addTarget(previewSurface)
                set(CaptureRequest.JPEG_ORIENTATION, combinedOrientation)
                coordinator.applyManualSettingsToRequest(this, true)
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
}
