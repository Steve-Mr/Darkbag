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

class HdrPlusProcessingService : LifecycleService() {

    companion object {
        private const val TAG = "HdrPlusService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "hdrplus_processing_channel"
    }

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
        try {
            val start = System.currentTimeMillis()
            val debugStats = LongArray(15)
            
            // Just one mask for normal processing (unlike ablation which did multiple passes)
            val ret = if (req.isSingleFrame) {
                ColorProcessor.processSingleFrameRaw(
                    req.megaBuffer,
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
                    req.colorEngineMode
                )
            } else {
                ColorProcessor.processHdrPlus(
                    req.megaBuffer,
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
                    req.colorEngineMode
                )
            }

            var exportRet = ret
            if (ret >= 0) {
                // Export full resolution image from shared memory using C++
                val edit = req.editConfig
                exportRet = ColorProcessor.exportHdrPlus(
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
                    jpgPath = if (req.saveJpg) req.fullResJpgPath else null,
                    dngPath = if (req.saveRaw && !req.isSingleFrame) req.linearDngPath else null,
                    ccm = req.ccm,
                    whiteBalance = req.whiteBalance,
                    zoomFactor = req.zoomFactor,
                    mirror = req.mirror,
                    metadata = req.metadata,
                    enableMemoryColor = req.enableMemoryColor,
                    colorEngineMode = req.colorEngineMode
                )
            }

            // Immediately release megaBuffer to free ~168MB memory
            HdrPlusBurst.releaseBuffer(req.megaBuffer)
            buffersReleased = true

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
                top.maary.darkbag.utils.DebugLogManager.addLog(report)

                if (req.saveJpg || req.saveRaw) {
                    var savedUri: android.net.Uri? = null
                    val shouldSaveJpg = req.saveJpg
                    val shouldSaveRaw = req.saveRaw && !req.isSingleFrame // Single Bayer RAW was already saved in front-end
                    
                    if (shouldSaveJpg || shouldSaveRaw) {
                        savedUri = top.maary.darkbag.utils.ImageSaver.saveProcessedImage(
                            context = this@HdrPlusProcessingService,
                            inputBitmap = null,
                            bmpPath = if (shouldSaveJpg) req.fullResJpgPath else null,
                            rotationDegrees = 0,
                            zoomFactor = req.zoomFactor,
                            baseName = req.baseName,
                            linearDngPath = if (shouldSaveRaw) req.linearDngPath else null,
                            saveJpg = shouldSaveJpg,
                            saveRaw = shouldSaveRaw,
                            jpgFolderUri = req.jpgFolderUri,
                            rawFolderUri = req.rawFolderUri,
                            mirror = req.mirror,
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
                }
            } else {
                Log.e(TAG, "Processing failed for ${req.requestId}")
            }
            Log.d(TAG, "Processed ${req.requestId} in ${System.currentTimeMillis() - start}ms")
        } catch (e: Exception) {
            Log.e(TAG, "Exception processing ${req.requestId}", e)
        } finally {
            if (!buffersReleased) {
                HdrPlusBurst.releaseBuffer(req.megaBuffer)
            }
            HdrPlusRequestManager.onTaskFinished()
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
