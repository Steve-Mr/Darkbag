package top.maary.darkbag.camera.dispatch

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.os.Build
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.maary.darkbag.MainApplication
import top.maary.darkbag.R
import top.maary.darkbag.camera.delegate.CameraCoordinatorHost
import top.maary.darkbag.camera.delegate.CameraEnvironmentHost
import top.maary.darkbag.camera.delegate.CameraUiHost
import top.maary.darkbag.camera.zsl.ZslBurstSeed
import top.maary.darkbag.fragments.BurstResult
import top.maary.darkbag.fragments.HdrPlusBurst
import top.maary.darkbag.models.CaptureMetadata
import top.maary.darkbag.models.StandardTimingTracker
import top.maary.darkbag.pipeline.sink.CaptureSink
import top.maary.darkbag.processor.HdrPlusProcessingService
import top.maary.darkbag.processor.HdrPlusRequest
import top.maary.darkbag.processor.HdrPlusRequestManager
import top.maary.darkbag.processor.SensorCalibrationHelper
import top.maary.darkbag.processor.StreamingBurstResult
import top.maary.darkbag.repository.PreferenceKeys
import top.maary.darkbag.repository.SettingsRepository
import top.maary.darkbag.utils.CameraRepository
import top.maary.darkbag.utils.HalfFrameManager
import top.maary.darkbag.utils.LensInfo

/**
 * Dispatcher coordinating single-shot and burst Camera2 captures, raw frame buffer management,
 * metadata aggregation via [CameraCaptureMetadataExtractor], spec assembly via [CameraTaskSpecAssembler],
 * and scheduling requests to the background computational photography pipeline.
 */
class CameraCapturePipelineDispatcher(
    private val env: CameraEnvironmentHost,
    private val ui: CameraUiHost,
    private val coordinator: CameraCoordinatorHost
) {
    companion object {
        private const val TAG = "CameraCapturePipeline"
    }

    val metadataExtractor = CameraCaptureMetadataExtractor(env)
    val specAssembler = CameraTaskSpecAssembler(metadataExtractor)

    private val processingChannel = Channel<RawImageHolder>(2)
    private var processingChannelJob: Job? = null

    private val burstEnqueuer = HdrPlusBurstProcessingEnqueuer(
        env = env,
        ui = ui,
        coordinator = coordinator,
        metadataExtractor = metadataExtractor,
        specAssembler = specAssembler,
        onEnqueueRequest = ::enqueueProcessingRequest,
        onNotifyQueuedOnMain = ::notifyQueuedOnMain,
        activePhysicalIdProvider = ::activePhysicalId
    )

    private val burstExecutor = HdrPlusBurstCaptureExecutor(
        env = env,
        ui = ui,
        coordinator = coordinator,
        metadataExtractor = metadataExtractor,
        specAssembler = specAssembler,
        onStreamingBurstComplete = burstEnqueuer::processStreamingHdrPlusBurst,
        onRecordCaptureResult = ::recordCaptureResult,
        onNotifySinkFailure = ::notifySinkFailureAsync,
        onAbortCapture = ::abortCapture
    )

    private val singleExecutor = SingleRawCaptureExecutor(
        env = env,
        ui = ui,
        coordinator = coordinator,
        metadataExtractor = metadataExtractor,
        specAssembler = specAssembler,
        enqueueRawHolder = { app, holder, timing ->
            app.applicationScope.launch {
                timing?.enqueued = System.currentTimeMillis()
                processingChannel.send(holder)
            }
        },
        onJpegFallback = { data, zoom, hfMeta, captureMeta ->
            burstEnqueuer.saveJpegFallback(data, 0, zoom, hfMeta, captureMeta)
        },
        onRecordCaptureResult = ::recordCaptureResult,
        onNotifySinkFailure = { ctx, sink, hfMeta, err ->
            notifySinkFailureAsync(ctx, sink, hfMeta, err)
        },
        onAbortCapture = ::abortCapture
    )

    fun startProcessingWorker(applicationScope: CoroutineScope) {
        if (processingChannelJob == null || processingChannelJob?.isActive != true) {
            processingChannelJob = applicationScope.launch(Dispatchers.IO) {
                for (holder in processingChannel) {
                    processImageAsync(env.hostContext.applicationContext, holder)
                }
            }
        }
    }

    fun stopProcessingWorker() {
        processingChannelJob?.cancel()
        processingChannelJob = null
        while (true) {
            val holder = processingChannel.tryReceive().getOrNull() ?: break
            HdrPlusBurst.releaseBuffer(holder.data)
            HdrPlusRequestManager.onTaskFinished()
        }
    }

    fun triggerAutoBurst() {
        val autoBurst = SettingsRepository.getInstance(env.hostContext).current().halfFrameAutoBurst
        if (autoBurst) {
            env.lifecycleScope.launch(Dispatchers.Main) {
                delay(800)
                ui.cameraUiContainerBinding?.cameraCaptureButton?.simulateClick()
            }
        }
    }

    fun takeSinglePictureCamera2(
        sink: CaptureSink? = null,
        timing: StandardTimingTracker? = null,
        isFrame1Trigger: Boolean = false,
        hfMetadata: HalfFrameManager.Metadata? = null
    ) = singleExecutor.takeSinglePictureCamera2(sink, timing, isFrame1Trigger, hfMetadata)

    fun triggerHdrPlusBurstCamera2(
        sink: CaptureSink? = null,
        isFrame1Trigger: Boolean = false,
        hfMetadata: HalfFrameManager.Metadata? = null,
        timing: StandardTimingTracker? = null,
        seed: ZslBurstSeed? = null
    ): Boolean = burstExecutor.triggerHdrPlusBurstCamera2(sink, isFrame1Trigger, hfMetadata, timing, seed)

    fun dispatchZslSingleFrame(
        frame: top.maary.darkbag.camera.zsl.ZslFrame,
        sink: CaptureSink?,
        isFrame1Trigger: Boolean,
        hfMetadata: HalfFrameManager.Metadata?,
        timing: StandardTimingTracker,
        digitalGain: Float
    ): Boolean {
        val buffer = frame.buffer ?: return false
        val effectiveGain = if (isFrame1Trigger) {
            if (env.isHdrPlusEnabled || digitalGain != 1.0f) {
                coordinator.writeScopedHalfFrameStep(
                    1, timing.shutterClick, digitalGain = digitalGain, flareType = hfMetadata?.flareType ?: -1
                )
                digitalGain
            } else {
                coordinator.getDigitalGainAndUpdateStep(frame.timestamp, true, timing.shutterClick)
            }
        } else digitalGain
        val holder = RawImageHolder(
            data = buffer,
            width = frame.width,
            height = frame.height,
            timestamp = frame.timestamp,
            rotationDegrees = frame.rotationDegrees,
            combinedOrientation = coordinator.getCombinedOrientation(),
            zoomRatio = frame.zoomRatio,
            physicalId = frame.lensId,
            timing = timing,
            halfFrameMetadata = hfMetadata?.copy(digitalGain = effectiveGain),
            digitalGain = effectiveGain,
            sink = sink,
            capturedLens = env.runtimeState.currentLens,
            captureResult = frame.result
        )
        val sendResult = processingChannel.trySend(holder)
        if (!sendResult.isSuccess) {
            Log.w(TAG, "ZSL single frame rejected by processing queue")
            HdrPlusBurst.releaseBuffer(buffer)
            return false
        }
        if (isFrame1Trigger) {
            coordinator.triggerAutoBurst()
        }
        HdrPlusRequestManager.onTaskStarted()
        timing.enqueued = System.currentTimeMillis()
        timing.recordFrameArrival()
        timing.recordShutterReady()
        env.runtimeState.isBurstActive = false
        env.cameraViewModel.setBurstActive(false)
        env.lifecycleScope.launch(Dispatchers.Main) {
            ui.resetBurstUi()
            if (!isFrame1Trigger) ui.showProcessingAnimation()
            ui.showZslCaptureFeedback()
        }
        return true
    }

    fun copyAndroidImageToHolder(
        image: Image,
        zoomRatio: Float,
        combinedOrientation: Int,
        physicalId: String?,
        halfFrameMetadata: HalfFrameManager.Metadata? = null,
        sink: CaptureSink? = null,
        capturedLens: LensInfo? = null
    ): RawImageHolder = singleExecutor.copyAndroidImageToHolder(
        image, zoomRatio, combinedOrientation, physicalId, halfFrameMetadata, sink, capturedLens
    )

    fun processHdrPlusBurst(
        burstResult: BurstResult,
        digitalGain: Float,
        hfMetadata: HalfFrameManager.Metadata? = null,
        timing: StandardTimingTracker? = null,
        capturedLens: LensInfo? = null,
        zoomFactor: Float = 1.0f
    ) = burstEnqueuer.processHdrPlusBurst(burstResult, digitalGain, hfMetadata, timing, capturedLens, zoomFactor)

    fun processStreamingHdrPlusBurst(
        streamingResult: StreamingBurstResult,
        burstContext: StreamingBurstCaptureContext
    ) = burstEnqueuer.processStreamingHdrPlusBurst(streamingResult, burstContext)

    fun saveJpegFallback(
        data: ByteArray,
        rotationDegrees: Int,
        zoomFactor: Float,
        halfFrameMetadata: HalfFrameManager.Metadata? = null,
        captureMetadata: CaptureMetadata? = null
    ) = burstEnqueuer.saveJpegFallback(data, rotationDegrees, zoomFactor, halfFrameMetadata, captureMetadata)

    suspend fun processImageAsync(context: Context, image: RawImageHolder) =
        withContext(Dispatchers.IO) {
            val timing = image.timing
            timing?.processingStart = System.currentTimeMillis()
            var enqueued = false
            try {
                val captureResult = image.captureResult ?: metadataExtractor.findCaptureResult(image.timestamp)
                val targetCharId = activePhysicalId(captureResult)
                    ?: image.physicalId
                    ?: image.capturedLens?.id
                    ?: env.runtimeState.currentLens?.id
                    ?: "0"
                val chars = CameraRepository.getCharacteristics(env.camera2Manager, targetCharId)

                val whiteLevel = chars.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023
                val blackLevelPattern = metadataExtractor.extractBlackLevelPattern(chars)
                val cfa = chars.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: 0
                val (wb, ccmCapture) = metadataExtractor.extractWbAndCcm(captureResult)
                val (lensShadingMapData, lensShadingRows, lensShadingCols) =
                    metadataExtractor.findLatestLensShadingMap(captureResult)

                val settingsRepo = SettingsRepository.getInstance(context)
                settingsRepo.awaitHydrated()
                val prefs = settingsRepo.current()
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

                notifyQueuedOnMain(env.hostContext, R.string.toast_processing_queued)

                var motionMp4Path = image.motionPhotoMp4Path
                var motionStillPtsUs = image.motionPhotoStillPtsUs
                if (motionMp4Path == null && env.runtimeState.pendingMotionPhotoTask != null) {
                    val (path, pts) = specAssembler.awaitPendingMotionPhoto(
                        pendingTaskProvider = { env.runtimeState.pendingMotionPhotoTask },
                        clearPendingTask = { env.runtimeState.pendingMotionPhotoTask = null }
                    )
                    motionMp4Path = path
                    motionStillPtsUs = pts
                    timing?.motionPhotoStillPtsUs = pts
                }

                val singleCalib = metadataExtractor.extractSensorCalibration(
                    characteristics = chars,
                    result = captureResult,
                    wb = wb,
                    wbMode = prefs.wbStrategy
                )
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
                        mirror = env.shouldMirror,
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
                        sink = image.sink,
                        saveRawOverride = if (settingsRepo.hasExplicitKey(PreferenceKeys.SAVE_RAW)) prefs.saveRaw else true,
                        wbModeOverride = if (env.runtimeState.isManualWb) 2 else null,
                        wbManualTempKOverride = if (env.runtimeState.isManualWb) env.runtimeState.manualWbTempK.toFloat() else null
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

    private fun abortCapture() {
        env.runtimeState.isBurstActive = false
        env.cameraViewModel.setBurstActive(false)
        env.lifecycleScope.launch(Dispatchers.Main) { ui.resetBurstUi() }
    }

    private fun recordCaptureResult(result: TotalCaptureResult) {
        val timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP)
        if (timestamp != null) {
            synchronized(env.runtimeState.captureResults) {
                if (env.runtimeState.captureResults.size >= 32) {
                    env.runtimeState.captureResults.keys.firstOrNull()?.let {
                        env.runtimeState.captureResults.remove(it)
                    }
                }
                env.runtimeState.captureResults[timestamp] = result
            }
        }
        (env.runtimeState.captureResultFlow as? MutableSharedFlow)?.tryEmit(result)
    }

    private fun activePhysicalId(result: CaptureResult?): String? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && result != null) {
            result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)
        } else null
    }

    private suspend fun notifyQueuedOnMain(toastContext: Context, toastResId: Int) {
        withContext(Dispatchers.Main) {
            Toast.makeText(toastContext, toastContext.getString(toastResId), Toast.LENGTH_SHORT).show()
            if (coordinator.activeCoordinator?.shouldClearThumbnailOnCapture() == true) {
                ui.setGalleryThumbnail(null)
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
