package top.maary.darkbag.processor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import top.maary.darkbag.fragments.HdrPlusBurst
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.maary.darkbag.R

import kotlinx.coroutines.sync.Semaphore

class HdrPlusProcessingService : LifecycleService() {

    companion object {
        private const val TAG = "HdrPlusService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "hdrplus_processing_channel"
        private const val MAX_CONCURRENT_STAGE2_EXPORTS = 2
    }

    private val exportSemaphore = Semaphore(MAX_CONCURRENT_STAGE2_EXPORTS)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification("Starting processing..."),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        )

        lifecycleScope.launch(Dispatchers.Main) {
            HdrPlusRequestManager.pendingTasksCount.collect { count ->
                if (count > 0) {
                    val text = if (count == 1) {
                        "Processing 1 photo..."
                    } else {
                        "Processing photos ($count remaining in queue)..."
                    }
                    updateNotification(text)
                }
            }
        }

        lifecycleScope.launch(top.maary.darkbag.processor.ColorProcessor.imageProcessingDispatcher) {
            HdrPlusRequestManager.requestFlow.collect { req ->
                processRequest(req)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    private suspend fun processRequest(req: HdrPlusRequest) {
        var buffersReleased = false
        val foregroundCompleted = java.util.concurrent.atomic.AtomicBoolean(false)
        fun notifyForegroundComplete() {
            if (foregroundCompleted.compareAndSet(false, true)) {
                HdrPlusRequestManager.onForegroundTaskFinished()
            }
        }
        try {
            val start = System.currentTimeMillis()
            req.timing?.processingStart = start
            req.timing?.recordEnvironment(
                width = req.width,
                height = req.height,
                isoVal = req.metadata.iso ?: 0,
                exposureNs = req.metadata.exposureTime ?: 0L,
                zoom = req.zoomFactor,
                fusion = req.fusionMode,
                isSingle = req.isSingleFrame
            )
            val debugStats = LongArray(20)
            
            // Just one mask for normal processing (unlike ablation which did multiple passes)
            val ret = if (req.streamingSessionHandle != 0L) {
                ColorProcessor.nativeFinishStreamingSession(
                    sessionHandle = req.streamingSessionHandle,
                    tempRawPath = req.requestId,
                    outputBitmap = null,
                    digitalGain = req.digitalGain,
                    targetLog = req.targetLogIndex,
                    lutPath = req.lutPath,
                    zoomFactor = req.zoomFactor,
                    mirror = req.mirror,
                    enableMemoryColor = req.enableMemoryColor,
                    colorEngineMode = req.colorEngineMode,
                    fusionMode = req.fusionMode,
                    debugStats = debugStats
                )
            } else if (req.isSingleFrame) {
                ColorProcessor.processSingleFrameRaw(
                    req.megaBuffer!!,
                    req.width, req.height,
                    req.orientation,
                    req.whiteLevel, req.blackLevelPattern,
                    req.lensShadingMap, req.lensShadingRows, req.lensShadingCols,
                    req.whiteBalance, req.ccm, req.cfaPattern,
                    req.targetLogIndex,
                    req.lutPath,
                    null, // outputJpgPath - prevent low-res fast thumbnail
                    null, // outputDngPath
                    req.digitalGain,
                    debugStats,
                    null, // outputBitmap
                    req.requestId, // tempRawPath - shared memory key!
                    req.zoomFactor,
                    req.mirror,
                    req.metadata,
                    req.enableMemoryColor,
                    req.colorEngineMode,
                    req.colorMatrix1,
                    req.colorMatrix2,
                    req.forwardMatrix1,
                    req.forwardMatrix2,
                    req.calibrationIlluminant1,
                    req.calibrationIlluminant2,
                    req.neutralColorPoint,
                    req.dngCompressionMode
                )
            } else {
                ColorProcessor.processHdrPlus(
                    req.megaBuffer!!,
                    req.numFrames,
                    req.width, req.height,
                    req.orientation,
                    req.whiteLevel, req.blackLevelPattern,
                    req.lensShadingMap, req.lensShadingRows, req.lensShadingCols, req.useSensorColorMatrix,
                    req.whiteBalance, req.ccm, req.ccmAlt, req.exportMatrixAB, req.cfaPattern,
                    req.targetLogIndex,
                    req.lutPath,
                    null, // outputJpgPath
                    null, // outputDngPath
                    req.digitalGain,
                    debugStats,
                    null, // outputBitmap
                    req.requestId, // tempRawPath - shared memory key!
                    req.zoomFactor,
                    req.mirror,
                    req.metadata,
                    req.enableMemoryColor,
                    req.colorEngineMode,
                    req.colorMatrix1,
                    req.colorMatrix2,
                    req.forwardMatrix1,
                    req.forwardMatrix2,
                    req.calibrationIlluminant1,
                    req.calibrationIlluminant2,
                    req.neutralColorPoint,
                    req.dngCompressionMode,
                    noiseProfile = req.noiseProfile
                )
            }


            // Immediately release megaBuffer as soon as Halide processing is done to free ~168-208MB memory
            if (req.megaBuffer != null) {
                HdrPlusBurst.releaseBuffer(req.megaBuffer)
            }
            buffersReleased = true
            val stage1End = System.currentTimeMillis()
            req.timing?.stage1ComputeDone = stage1End
            req.timing?.jniDone = stage1End

            if (debugStats[15] > 0) {
                req.timing?.recordStreamingPushStats(
                    count = debugStats[15].toInt(),
                    avgMs = debugStats[16],
                    minMs = debugStats[17],
                    maxMs = debugStats[18]
                )
            }
            if (debugStats[8] > 0 || debugStats[9] > 0) {
                req.timing?.recordStage1ComputeBreakdown(
                    normalizeMs = debugStats[8],
                    fusionMs = debugStats[9]
                )
            }

            if (ret >= 0) {
                // Handoff Stage 2 (Export & MediaStore Save) to exportProcessingDispatcher
                // so imageProcessingDispatcher can immediately start the next capture computation.
                var stage2HandedOff = false
                try {
                    val app = applicationContext as top.maary.darkbag.MainApplication
                    app.applicationScope.launch(ColorProcessor.exportProcessingDispatcher) {
                        exportSemaphore.acquire()
                        var pfdJpg: Pair<android.os.ParcelFileDescriptor, android.net.Uri>? = null
                        var pfdDng: Pair<android.os.ParcelFileDescriptor, android.net.Uri>? = null
                        var exportSuccessful = false
                        try {
                            val edit = req.editConfig
                            val isHalfFrameActive = (req.hfMetadata != null && req.hfMetadata.profile != top.maary.darkbag.utils.HalfFrameSessionStore.PROFILE_NORMAL)
                            val halfFrameManager = if (isHalfFrameActive) top.maary.darkbag.utils.HalfFrameManager(this@HdrPlusProcessingService) else null
                            val shouldSaveJpg = req.saveJpg
                            val shouldSaveRaw = if (isHalfFrameActive) (halfFrameManager?.saveRaw ?: false) else req.saveRaw

                            if (shouldSaveJpg && req.jpgFolderUri == null && req.motionPhotoMp4Path == null && !isHalfFrameActive) {
                                pfdJpg = top.maary.darkbag.utils.ImageSaver.createMediaStorePendingPfd(
                                    context = this@HdrPlusProcessingService,
                                    displayName = "${req.baseName}.jpg",
                                    mimeType = "image/jpeg"
                                )
                            }

                            val dngFileName = if (req.rawOutputType == 0) "${req.baseName}.dng" else "${req.baseName}_linear.dng"
                            val dngPathToUse = if (req.rawOutputType == 0 && req.linearDngPath.endsWith("_linear.dng")) {
                                req.linearDngPath.removeSuffix("_linear.dng") + ".dng"
                            } else {
                                req.linearDngPath
                            }

                            if (shouldSaveRaw && req.rawFolderUri == null && !isHalfFrameActive) {
                                pfdDng = top.maary.darkbag.utils.ImageSaver.createMediaStorePendingPfd(
                                    context = this@HdrPlusProcessingService,
                                    displayName = dngFileName,
                                    mimeType = "image/x-adobe-dng"
                                )
                            }

                            val jpgDebugStats = LongArray(15)
                            val dngDebugStats = LongArray(15)
                            var jpgExportRet = 0
                            var dngExportRet = 0
                            var jpgSuccess = false
                            var dngSuccess = false

                            // Phase 3B: Decoupled Stage 2 export.
                            // Fast Path: Prioritize JPEG export to achieve sub-second T2 time to first output.
                            if (shouldSaveJpg) {
                                try {
                                    jpgExportRet = ColorProcessor.exportHdrPlus(
                                        tempRawPath = req.requestId,
                                        width = req.width,
                                        height = req.height,
                                        orientation = req.orientation,
                                        digitalGain = req.digitalGain,
                                        targetLog = req.targetLogIndex,
                                        lutPath = req.lutPath,
                                        exposure = edit?.exposure ?: 0f,
                                        contrast = edit?.contrast ?: 0f,
                                        saturation = edit?.saturation ?: 0f,
                                        highlights = edit?.highlights ?: 0f,
                                        shadows = edit?.shadows ?: 0f,
                                        whites = edit?.whites ?: 0f,
                                        blacks = edit?.blacks ?: 0f,
                                        jpgPath = if (pfdJpg == null) req.fullResJpgPath else null,
                                        dngPath = null,
                                        faithfulHighlights = req.isSingleFrame,
                                        ccm = req.ccm,
                                        whiteBalance = req.whiteBalance,
                                        zoomFactor = req.zoomFactor,
                                        mirror = req.mirror,
                                        metadata = req.metadata,
                                        enableMemoryColor = req.enableMemoryColor,
                                        colorEngineMode = req.colorEngineMode,
                                        colorMatrix1 = req.colorMatrix1,
                                        colorMatrix2 = req.colorMatrix2,
                                        forwardMatrix1 = req.forwardMatrix1,
                                        forwardMatrix2 = req.forwardMatrix2,
                                        calibrationIlluminant1 = req.calibrationIlluminant1,
                                        calibrationIlluminant2 = req.calibrationIlluminant2,
                                        neutralColorPoint = req.neutralColorPoint,
                                        debugStats = jpgDebugStats,
                                        outJpgFd = pfdJpg?.first?.fd ?: -1,
                                        outDngFd = -1,
                                        dngCompressionMode = req.dngCompressionMode,
                                        rawOutputType = req.rawOutputType,
                                        cfaPattern = req.cfaPattern,
                                        blackLevelPattern = req.blackLevelPattern,
                                        whiteLevel = req.whiteLevel,
                                        dynamicBlackLevel = req.dynamicBlackLevel,
                                        noiseProfile = req.noiseProfile,
                                        activeArray = req.activeArray,
                                        lensShadingMap = req.lensShadingMap,
                                        lensShadingRows = req.lensShadingRows,
                                        lensShadingCols = req.lensShadingCols
                                    )
                                    jpgSuccess = (jpgExportRet == 0)
                                } finally {
                                    if (pfdJpg != null) {
                                        top.maary.darkbag.utils.ImageSaver.finalizeMediaStorePendingPfd(
                                            context = this@HdrPlusProcessingService,
                                            pfdPair = pfdJpg,
                                            success = jpgSuccess,
                                            editConfig = req.editConfig,
                                            captureMetadata = req.metadata
                                        )
                                        if (jpgSuccess) {
                                            top.maary.darkbag.processor.ColorProcessor.backgroundSaveFlow.tryEmit(
                                                top.maary.darkbag.processor.ColorProcessor.BackgroundSaveEvent(
                                                    baseName = req.baseName,
                                                    dngPath = if (req.saveRaw) dngPathToUse else null,
                                                    jpgPath = null,
                                                    targetUri = pfdJpg.second.toString(),
                                                    zoomFactor = req.zoomFactor,
                                                    orientation = req.orientation,
                                                    saveJpg = true
                                                )
                                            )
                                            notifyForegroundComplete()
                                        }
                                    }
                                    if (jpgSuccess && req.timing?.firstOutputWritten == 0L) {
                                        req.timing?.firstOutputWritten = System.currentTimeMillis()
                                    }
                                    if (pfdJpg == null && jpgSuccess) {
                                        notifyForegroundComplete()
                                    }
                                }
                            }

                            // Background Path: Export DNG asynchronously without blocking user feedback
                            if (shouldSaveRaw) {
                                try {
                                    dngExportRet = ColorProcessor.exportHdrPlus(
                                        tempRawPath = req.requestId,
                                        width = req.width,
                                        height = req.height,
                                        orientation = req.orientation,
                                        digitalGain = req.digitalGain,
                                        targetLog = req.targetLogIndex,
                                        lutPath = req.lutPath,
                                        exposure = edit?.exposure ?: 0f,
                                        contrast = edit?.contrast ?: 0f,
                                        saturation = edit?.saturation ?: 0f,
                                        highlights = edit?.highlights ?: 0f,
                                        shadows = edit?.shadows ?: 0f,
                                        whites = edit?.whites ?: 0f,
                                        blacks = edit?.blacks ?: 0f,
                                        jpgPath = null,
                                        dngPath = if (pfdDng == null) dngPathToUse else null,
                                        faithfulHighlights = req.isSingleFrame,
                                        ccm = req.ccm,
                                        whiteBalance = req.whiteBalance,
                                        zoomFactor = req.zoomFactor,
                                        mirror = req.mirror,
                                        metadata = req.metadata,
                                        enableMemoryColor = req.enableMemoryColor,
                                        colorEngineMode = req.colorEngineMode,
                                        colorMatrix1 = req.colorMatrix1,
                                        colorMatrix2 = req.colorMatrix2,
                                        forwardMatrix1 = req.forwardMatrix1,
                                        forwardMatrix2 = req.forwardMatrix2,
                                        calibrationIlluminant1 = req.calibrationIlluminant1,
                                        calibrationIlluminant2 = req.calibrationIlluminant2,
                                        neutralColorPoint = req.neutralColorPoint,
                                        debugStats = dngDebugStats,
                                        outJpgFd = -1,
                                        outDngFd = pfdDng?.first?.fd ?: -1,
                                        dngCompressionMode = req.dngCompressionMode,
                                        rawOutputType = req.rawOutputType,
                                        cfaPattern = req.cfaPattern,
                                        blackLevelPattern = req.blackLevelPattern,
                                        whiteLevel = req.whiteLevel,
                                        dynamicBlackLevel = req.dynamicBlackLevel,
                                        noiseProfile = req.noiseProfile,
                                        activeArray = req.activeArray,
                                        lensShadingMap = req.lensShadingMap,
                                        lensShadingRows = req.lensShadingRows,
                                        lensShadingCols = req.lensShadingCols
                                    )
                                    dngSuccess = (dngExportRet == 0)
                                } finally {
                                    if (pfdDng != null) {
                                        top.maary.darkbag.utils.ImageSaver.finalizeMediaStorePendingPfd(
                                            context = this@HdrPlusProcessingService,
                                            pfdPair = pfdDng,
                                            success = dngSuccess
                                        )
                                        if (pfdJpg == null && dngSuccess) {
                                            top.maary.darkbag.processor.ColorProcessor.backgroundSaveFlow.tryEmit(
                                                top.maary.darkbag.processor.ColorProcessor.BackgroundSaveEvent(
                                                    baseName = req.baseName,
                                                    dngPath = dngPathToUse,
                                                    jpgPath = null,
                                                    targetUri = pfdDng.second.toString(),
                                                    zoomFactor = req.zoomFactor,
                                                    orientation = req.orientation,
                                                    saveJpg = false
                                                )
                                            )
                                        }
                                        if (dngSuccess && !shouldSaveJpg) {
                                            notifyForegroundComplete()
                                        }
                                    }
                                    if (dngSuccess && req.timing?.firstOutputWritten == 0L) {
                                        req.timing?.firstOutputWritten = System.currentTimeMillis()
                                    }
                                    if (pfdDng == null && dngSuccess && !shouldSaveJpg) {
                                        notifyForegroundComplete()
                                    }
                                }
                            }

                            val exportSuccessful = (!shouldSaveJpg || jpgSuccess) && (!shouldSaveRaw || dngSuccess)
                            val exportRet = if (exportSuccessful) 0 else -1
                            req.timing?.stage2ExportDone = System.currentTimeMillis()
                            req.timing?.recordStage2Metrics(
                                postMs = if (shouldSaveJpg) jpgDebugStats[2] else 0L,
                                dngMs = if (shouldSaveRaw) dngDebugStats[3] else 0L,
                                jpgMs = if (shouldSaveJpg) jpgDebugStats[4] else 0L
                            )
                            debugStats[2] = if (shouldSaveJpg) jpgDebugStats[2] else 0L
                            debugStats[3] = if (shouldSaveRaw) dngDebugStats[3] else 0L
                            debugStats[4] = if (shouldSaveJpg) jpgDebugStats[4] else 0L

                            if (exportRet == 0) {
                                val totalTime = System.currentTimeMillis() - start
                                val mode = if (req.isSingleFrame) "Single RAW" else "HDR+ Burst"
                                val report = """
                                    [$mode Report]
                                    Total Background Time: ${totalTime}ms
                                    - Halide JNI Prep: ${debugStats[12]}ms
                                    - Halide Pipeline: ${debugStats[0]}ms
                                        * Align: ${debugStats[7]}ms
                                        * Merge: ${debugStats[8]}ms
                                        * Demosaic: ${debugStats[9]}ms
                                        * Denoise: ${debugStats[10]}ms
                                        * sRGB: ${debugStats[11]}ms
                                        * BlackWhite: ${debugStats[13]}ms
                                        * WB: ${debugStats[14]}ms
                                    - C++ Post/ColorPipe: ${debugStats[2]}ms
                                    - DNG Encode: ${debugStats[3]}ms
                                    - JPEG Native Save: ${debugStats[4]}ms
                                """.trimIndent()
                                Log.i(TAG, report)

                                val baselineReport = top.maary.darkbag.processor.SensorCalibrationHelper.formatHardwareBaselineLog(
                                    cfaPattern = req.cfaPattern,
                                    blackLevelPattern = req.blackLevelPattern,
                                    lensShadingMap = req.lensShadingMap,
                                    lensShadingRows = req.lensShadingRows,
                                    lensShadingCols = req.lensShadingCols,
                                    whiteBalance = req.whiteBalance,
                                    digitalGain = req.digitalGain
                                )
                                Log.i(TAG, baselineReport)
                                top.maary.darkbag.utils.DebugLogManager.addDiagnosticLog(baselineReport)

                                val needsSecondaryJpg = shouldSaveJpg && pfdJpg == null
                                val needsSecondaryRaw = shouldSaveRaw && pfdDng == null

                                if (needsSecondaryJpg || needsSecondaryRaw) {
                                    top.maary.darkbag.utils.ImageSaver.saveProcessedImage(
                                        context = this@HdrPlusProcessingService,
                                        inputBitmap = null,
                                        bmpPath = if (needsSecondaryJpg) req.fullResJpgPath else null,
                                        rotationDegrees = 0,
                                        zoomFactor = req.zoomFactor,
                                        baseName = req.baseName,
                                        linearDngPath = if (needsSecondaryRaw) dngPathToUse else null,
                                        saveJpg = needsSecondaryJpg,
                                        saveRaw = needsSecondaryRaw,
                                        jpgFolderUri = req.jpgFolderUri,
                                        rawFolderUri = req.rawFolderUri,
                                        mirror = false,
                                        isFastPath = false,
                                        halfFrameMetadata = req.hfMetadata,
                                        editConfig = req.editConfig,
                                        digitalGain = req.digitalGain,
                                        captureMetadata = req.metadata,
                                        isAlreadyCropped = true,
                                        motionPhotoMp4Path = req.motionPhotoMp4Path,
                                        motionPhotoStillPtsUs = req.motionPhotoStillPtsUs
                                    )
                                }
                                if (req.timing?.firstOutputWritten == 0L) {
                                    req.timing?.firstOutputWritten = System.currentTimeMillis()
                                }
                                req.timing?.taskCompleted = System.currentTimeMillis()

                                req.timing?.let { t ->
                                    val timingReport = t.buildSummaryReport()
                                    Log.i(TAG, timingReport)
                                    top.maary.darkbag.utils.DebugLogManager.addTimingReport(timingReport)
                                }
                            } else {
                                Log.e(TAG, "Processing failed for ${req.requestId}")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Exception during Stage 2 export for ${req.requestId}", e)
                        } finally {
                            if (req.timing?.taskCompleted == 0L) {
                                req.timing?.taskCompleted = System.currentTimeMillis()
                            }
                            exportSemaphore.release()
                            finishTaskAndCheckStopService(req.requestId, foregroundAlreadyFinished = foregroundCompleted.get())
                        }
                    }
                    stage2HandedOff = true
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to launch Stage 2 for ${req.requestId}", e)
                }

                if (!stage2HandedOff) {
                    finishTaskAndCheckStopService(req.requestId, foregroundAlreadyFinished = foregroundCompleted.get())
                }
            } else {
                Log.e(TAG, "Stage 1 Halide processing failed for ${req.requestId}")
                finishTaskAndCheckStopService(req.requestId, foregroundAlreadyFinished = foregroundCompleted.get())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception processing ${req.requestId}", e)
            if (!buffersReleased && req.megaBuffer != null) {
                HdrPlusBurst.releaseBuffer(req.megaBuffer)
            }
            if (req.streamingSessionHandle != 0L) {
                try {
                    ColorProcessor.nativeAbortStreamingSession(req.streamingSessionHandle)
                } catch (abortEx: Throwable) {
                    Log.w(TAG, "Error aborting streaming session ${req.streamingSessionHandle}", abortEx)
                }
            }
            finishTaskAndCheckStopService(req.requestId, foregroundAlreadyFinished = foregroundCompleted.get())
        }
    }

    private fun finishTaskAndCheckStopService(requestId: String? = null, foregroundAlreadyFinished: Boolean = false) {
        if (requestId != null) {
            try {
                ColorProcessor.freeSharedRawMemory(requestId)
            } catch (e: Throwable) {
                Log.e(TAG, "Error freeing shared raw memory for $requestId", e)
            }
        }
        HdrPlusRequestManager.onTaskFinished(foregroundAlreadyFinished = foregroundAlreadyFinished)
        val remaining = HdrPlusRequestManager.pendingTasksCount.value
        if (remaining == 0) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf()
        }
    }

    private fun createNotificationChannel() {
        val name = "Image Processing"
        val descriptionText = "Background processing for photos"
        val importance = NotificationManager.IMPORTANCE_LOW
        val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
            description = descriptionText
        }
        val notificationManager: NotificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    private fun createNotification(contentText: String): Notification {
        val intent = Intent(this, top.maary.darkbag.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = android.app.PendingIntent.getActivity(
            this, 0, intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                android.app.PendingIntent.FLAG_UPDATE_CURRENT
            }
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Darkbag Processing")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_photo)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(contentText: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, createNotification(contentText))
    }
}
