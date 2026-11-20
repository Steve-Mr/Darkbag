package top.maary.darkbag.camera.dispatch

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.maary.darkbag.MainApplication
import top.maary.darkbag.R
import top.maary.darkbag.camera.delegate.CameraCoordinatorHost
import top.maary.darkbag.camera.delegate.CameraEnvironmentHost
import top.maary.darkbag.camera.delegate.CameraUiHost
import top.maary.darkbag.fragments.BurstResult
import top.maary.darkbag.fragments.HdrPlusBurst
import top.maary.darkbag.models.CaptureMetadata
import top.maary.darkbag.models.StandardTimingTracker
import top.maary.darkbag.pipeline.sink.CaptureSink
import top.maary.darkbag.processor.ColorProcessor
import top.maary.darkbag.processor.HdrPlusRequest
import top.maary.darkbag.processor.HdrPlusRequestManager
import top.maary.darkbag.processor.StreamingBurstResult
import top.maary.darkbag.repository.PreferenceKeys
import top.maary.darkbag.repository.SettingsRepository
import top.maary.darkbag.utils.CameraRepository
import top.maary.darkbag.utils.HalfFrameManager
import top.maary.darkbag.utils.ImageSaver
import top.maary.darkbag.utils.LensInfo
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Locale

internal class HdrPlusBurstProcessingEnqueuer(
    private val env: CameraEnvironmentHost,
    private val ui: CameraUiHost,
    private val coordinator: CameraCoordinatorHost,
    private val metadataExtractor: CameraCaptureMetadataExtractor,
    private val specAssembler: CameraTaskSpecAssembler,
    private val onEnqueueRequest: (Context, HdrPlusRequest, StandardTimingTracker?) -> Unit,
    private val onNotifyQueuedOnMain: suspend (Context, Int) -> Unit,
    private val activePhysicalIdProvider: (CaptureResult?) -> String?
) {
    companion object {
        private const val TAG = "HdrPlusBurstEnqueuer"
        private const val FILENAME = "yyyy-MM-dd-HH-mm-ss-SSS"
    }

    fun processHdrPlusBurst(
        burstResult: BurstResult,
        digitalGain: Float,
        hfMetadata: HalfFrameManager.Metadata? = null,
        timing: StandardTimingTracker? = null,
        capturedLens: LensInfo? = null,
        zoomFactor: Float = 1.0f
    ) {
        val effectiveLens = capturedLens ?: env.runtimeState.currentLens
        val currentZoom = specAssembler.resolveEffectiveZoom(capturedLens, env.runtimeState.currentLens, zoomFactor)
        val combinedOrientation = coordinator.getCombinedOrientation()
        val appContext = env.hostContext.applicationContext

        (appContext as MainApplication).applicationScope.launch(Dispatchers.IO) {
            var isHdrPlusSuccess = false
            try {
                val frames = burstResult.frames
                Log.d(TAG, "processHdrPlusBurst started with ${frames.size} frames. DigitalGain=$digitalGain")
                val result = metadataExtractor.findCaptureResult(frames[0].timestamp)
                val targetCharId = activePhysicalIdProvider(result) ?: frames[0].physicalId ?: effectiveLens?.id ?: "0"
                val chars = CameraRepository.getCharacteristics(env.camera2Manager, targetCharId)
                val whiteLevel = chars.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023
                val blackLevelPattern = metadataExtractor.extractBlackLevelPattern(chars)
                val cfa = chars.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: 0
                val (wb, ccmCapture) = metadataExtractor.extractWbAndCcm(result)
                val useSensorColorMatrix = true
                val burstCalib = metadataExtractor.extractSensorCalibration(chars, result, wb)
                val ccmSensor = burstCalib.renderCcm ?: ccmCapture
                val finalCcm = if (useSensorColorMatrix) ccmSensor else ccmCapture
                val ccmAlt = if (useSensorColorMatrix) ccmCapture else ccmSensor
                val (latchedLsc, latchedRows, latchedCols) = metadataExtractor.findLatestLensShadingMap(result)

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
                val assemblyRequest = TaskSpecAssemblyRequest(
                    width = frames[0].width,
                    height = frames[0].height,
                    orientation = combinedOrientation,
                    zoomFactor = currentZoom,
                    mirror = env.shouldMirror,
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
                    sink = null
                )
                enqueueBurstRequestInternal(
                    appContext = appContext,
                    assemblyRequest = assemblyRequest,
                    megaBuffer = burstResult.megaBuffer,
                    streamingSessionHandle = 0L,
                    numFrames = frames.size
                )
                isHdrPlusSuccess = true
            } catch (e: Exception) {
                Log.e(TAG, "processHdrPlusBurst failed", e)
                isHdrPlusSuccess = false
            } finally {
                burstResult.frames.forEach { it.close() }
                if (!isHdrPlusSuccess) HdrPlusBurst.releaseBuffer(burstResult.megaBuffer)
                env.lifecycleScope.launch(Dispatchers.Main) {
                    ui.resetBurstUi()
                    if (!isHdrPlusSuccess) ui.hideProcessingAnimation()
                }
            }
        }
    }

    fun processStreamingHdrPlusBurst(
        streamingResult: StreamingBurstResult,
        burstContext: StreamingBurstCaptureContext
    ) {
        val effectiveLens = burstContext.capturedLens ?: env.runtimeState.currentLens
        val currentZoom = specAssembler.resolveEffectiveZoom(
            burstContext.capturedLens, env.runtimeState.currentLens, burstContext.zoomFactor
        )
        val appContext = env.hostContext.applicationContext

        (appContext as MainApplication).applicationScope.launch(Dispatchers.IO) {
            var isHdrPlusSuccess = false
            try {
                val frames = streamingResult.frames
                Log.d(
                    TAG,
                    "processStreamingHdrPlusBurst started with ${frames.size} frames. " +
                        "DigitalGain=${burstContext.digitalGain}, session=${streamingResult.sessionHandle}"
                )
                val baseFrameTs = frames.firstOrNull()?.timestampNs
                val baseResult = if (baseFrameTs != null) metadataExtractor.findCaptureResult(baseFrameTs) else null
                val result = baseResult ?: burstContext.result
                val (effectiveWb, effectiveCcm) = metadataExtractor.extractWbAndCcm(result)
                val burstCalib = metadataExtractor.extractSensorCalibration(burstContext.chars, result, effectiveWb)

                var effectiveLsc = burstContext.lensShadingMapData
                var effectiveRows = burstContext.lensShadingRows
                var effectiveCols = burstContext.lensShadingCols
                if (baseResult != null) {
                    val (baseLsc, baseRows, baseCols) = metadataExtractor.findLatestLensShadingMap(baseResult)
                    if (baseLsc != null && baseRows > 1 && baseCols > 1) {
                        effectiveLsc = baseLsc
                        effectiveRows = baseRows
                        effectiveCols = baseCols
                    }
                }
                if (effectiveLsc == null || effectiveRows <= 1 || effectiveCols <= 1) {
                    val (latchedLsc, latchedRows, latchedCols) = metadataExtractor.findLatestLensShadingMap(result)
                    if (latchedLsc != null) {
                        effectiveLsc = latchedLsc
                        effectiveRows = latchedRows
                        effectiveCols = latchedCols
                    }
                }

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
                val assemblyRequest = TaskSpecAssemblyRequest(
                    width = burstContext.width,
                    height = burstContext.height,
                    orientation = burstContext.combinedOrientation,
                    zoomFactor = currentZoom,
                    mirror = env.shouldMirror,
                    isSingleFrame = false,
                    isHdrPlus = true,
                    targetCharId = burstContext.targetCharId,
                    chars = burstContext.chars,
                    captureResult = result,
                    captureMetadata = captureMetadata,
                    cfa = burstContext.cfa,
                    whiteLevel = burstContext.whiteLevel,
                    blackLevelPattern = burstContext.blackLevelPattern,
                    wb = effectiveWb,
                    ccm = effectiveCcm,
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
                    sink = burstContext.sink
                )
                enqueueBurstRequestInternal(
                    appContext = appContext,
                    assemblyRequest = assemblyRequest,
                    megaBuffer = null,
                    streamingSessionHandle = streamingResult.sessionHandle,
                    numFrames = frames.size
                )
                isHdrPlusSuccess = true
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
                env.lifecycleScope.launch(Dispatchers.Main) {
                    ui.resetBurstUi()
                    if (!isHdrPlusSuccess) ui.hideProcessingAnimation()
                }
            }
        }
    }

    private suspend fun enqueueBurstRequestInternal(
        appContext: Context,
        assemblyRequest: TaskSpecAssemblyRequest,
        megaBuffer: ByteBuffer?,
        streamingSessionHandle: Long,
        numFrames: Int
    ) {
        onNotifyQueuedOnMain(appContext, R.string.toast_hdr_queued)

        val (motionMp4Path, motionStillPtsUs) = specAssembler.awaitPendingMotionPhoto(
            pendingTaskProvider = { env.runtimeState.pendingMotionPhotoTask },
            clearPendingTask = { env.runtimeState.pendingMotionPhotoTask = null }
        )
        val settingsRepo = SettingsRepository.getInstance(appContext)
        settingsRepo.awaitHydrated()
        val prefs = settingsRepo.current()

        val spec = specAssembler.assemble(
            context = appContext,
            prefs = prefs,
            request = assemblyRequest.copy(
                motionPhotoMp4Path = motionMp4Path,
                motionPhotoStillPtsUs = motionStillPtsUs,
                saveRawOverride = if (settingsRepo.hasExplicitKey(PreferenceKeys.SAVE_RAW)) prefs.saveRaw else true
            )
        )
        val request = HdrPlusRequest.fromSpec(
            spec = spec,
            megaBuffer = megaBuffer,
            streamingSessionHandle = streamingSessionHandle,
            numFrames = numFrames
        )
        onEnqueueRequest(appContext, request, assemblyRequest.timing)
    }

    fun saveJpegFallback(
        data: ByteArray,
        rotationDegrees: Int,
        zoomFactor: Float,
        halfFrameMetadata: HalfFrameManager.Metadata? = null,
        captureMetadata: CaptureMetadata? = null
    ) {
        val appContext = env.hostContext.applicationContext
        val mirror = env.shouldMirror

        (appContext as MainApplication).applicationScope.launch(Dispatchers.IO) {
            try {
                val name = SimpleDateFormat(FILENAME, Locale.US).format(System.currentTimeMillis())
                val bmpFile = File(appContext.cacheDir, "temp_$name.jpg")
                FileOutputStream(bmpFile).use { it.write(data) }

                val settingsRepository = SettingsRepository.getInstance(appContext)
                val jpgFolderUri = settingsRepository.current().jpgStorageUri
                val (motionMp4Path, motionStillPtsUs) = specAssembler.awaitPendingMotionPhoto(
                    pendingTaskProvider = { env.runtimeState.pendingMotionPhotoTask },
                    clearPendingTask = { env.runtimeState.pendingMotionPhotoTask = null }
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
                    if (uri != null) {
                        env.imageRepository.invalidateCache()
                        settingsRepository.setLastCaptureUri(uri.toString())
                        ui.setGalleryThumbnail(uri.toString())
                    } else if (coordinator.activeCoordinator?.shouldClearThumbnailOnCapture() == true) {
                        ui.setGalleryThumbnail(null)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save JPEG fallback", e)
            } finally {
                HdrPlusRequestManager.onTaskFinished()
                withContext(Dispatchers.Main) {
                    ui.resetBurstUi()
                    ui.cameraUiContainerBinding?.cameraCaptureButton?.isEnabled = true
                    if (coordinator.activeCoordinator?.keepsProcessingAnimationActive() != true) {
                        ui.hideProcessingAnimation()
                    }
                }
            }
        }
    }
}
