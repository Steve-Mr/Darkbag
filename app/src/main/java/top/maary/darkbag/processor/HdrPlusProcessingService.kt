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
        val spec = req.toSpec()
        val sink = spec.getEffectiveSink()
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
                        var exportSuccessful = false
                        val spec = req.toSpec()
                        val sink = spec.getEffectiveSink()
                        try {
                            val targets = sink.prepareTargets(applicationContext, spec)

                            val jpgDebugStats = LongArray(15)
                            val dngDebugStats = LongArray(15)
                            var jpgExportRet = 0
                            var dngExportRet = 0
                            var jpgSuccess = false
                            var dngSuccess = false

                            val shouldSaveJpg = targets.hasJpgTarget
                            val shouldSaveRaw = targets.hasDngTarget

                            // Phase 3B: Decoupled Stage 2 export.
                            // Fast Path: Prioritize JPEG export to achieve sub-second T2 time to first output.
                            if (shouldSaveJpg) {
                                try {
                                    jpgExportRet = ColorProcessor.exportHdrPlus(
                                        spec = spec,
                                        jpgPath = targets.outJpgPath,
                                        dngPath = null,
                                        outJpgFd = targets.outJpgFd,
                                        outDngFd = -1,
                                        debugStats = jpgDebugStats
                                    )
                                    jpgSuccess = (jpgExportRet == 0)
                                } finally {
                                    sink.onImageExported(
                                        context = applicationContext,
                                        spec = spec,
                                        success = jpgSuccess,
                                        outputPath = targets.outJpgPath,
                                        outputFd = targets.outJpgFd
                                    )
                                    if (jpgSuccess) {
                                        notifyForegroundComplete()
                                    }
                                    if (jpgSuccess && req.timing?.firstOutputWritten == 0L) {
                                        req.timing?.firstOutputWritten = System.currentTimeMillis()
                                    }
                                }
                            }

                            // Background Path: Export DNG asynchronously without blocking user feedback
                            if (shouldSaveRaw) {
                                try {
                                    dngExportRet = ColorProcessor.exportHdrPlus(
                                        spec = spec,
                                        jpgPath = null,
                                        dngPath = targets.outDngPath,
                                        outJpgFd = -1,
                                        outDngFd = targets.outDngFd,
                                        debugStats = dngDebugStats
                                    )
                                    dngSuccess = (dngExportRet == 0)
                                } finally {
                                    sink.onRawExported(
                                        context = applicationContext,
                                        spec = spec,
                                        success = dngSuccess,
                                        outputPath = targets.outDngPath,
                                        outputFd = targets.outDngFd
                                    )
                                    if (dngSuccess && !shouldSaveJpg) {
                                        notifyForegroundComplete()
                                    }
                                    if (dngSuccess && req.timing?.firstOutputWritten == 0L) {
                                        req.timing?.firstOutputWritten = System.currentTimeMillis()
                                    }
                                }
                            }

                            exportSuccessful = (!shouldSaveJpg || jpgSuccess) && (!shouldSaveRaw || dngSuccess)
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

                                // CaptureSink output delivery (secondary saver / stitching / metadata publish)
                                sink.onComplete(
                                    context = applicationContext,
                                    spec = spec,
                                    jpgSuccess = jpgSuccess,
                                    rawSuccess = dngSuccess
                                )

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
                                sink.onError(
                                    context = applicationContext,
                                    spec = spec,
                                    error = RuntimeException("Stage 2 export failed: jpgRet=$jpgExportRet, dngRet=$dngExportRet")
                                )
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Exception during Stage 2 export for ${req.requestId}", e)
                            try {
                                sink.onError(applicationContext, spec, e)
                            } catch (sinkEx: Throwable) {
                                Log.e(TAG, "Failed calling onError on sink", sinkEx)
                            }
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
                    try {
                        sink.onError(applicationContext, spec, RuntimeException("Failed to hand off Stage 2"))
                    } catch (sinkEx: Throwable) {
                        Log.e(TAG, "Failed calling onError on sink", sinkEx)
                    }
                    finishTaskAndCheckStopService(req.requestId, foregroundAlreadyFinished = foregroundCompleted.get())
                }
            } else {
                Log.e(TAG, "Stage 1 Halide processing failed for ${req.requestId}")
                try {
                    sink.onError(applicationContext, spec, RuntimeException("Stage 1 Halide processing failed with ret=$ret"))
                } catch (sinkEx: Throwable) {
                    Log.e(TAG, "Failed calling onError on sink", sinkEx)
                }
                finishTaskAndCheckStopService(req.requestId, foregroundAlreadyFinished = foregroundCompleted.get())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception processing ${req.requestId}", e)
            try {
                sink.onError(applicationContext, spec, e)
            } catch (sinkEx: Throwable) {
                Log.e(TAG, "Failed calling onError on sink", sinkEx)
            }
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
