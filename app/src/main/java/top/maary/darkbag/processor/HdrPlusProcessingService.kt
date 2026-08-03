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
        startForeground(NOTIFICATION_ID, createNotification("Starting engine..."),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        )

        lifecycleScope.launch(Dispatchers.IO) {
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
        updateNotification("Processing image...")
        var buffersReleased = false
        try {
            val start = System.currentTimeMillis()
            
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
                    if (req.saveJpg) req.fullResJpgPath else null,
                    if (req.saveRaw) req.linearDngPath else null,
                    req.digitalGain,
                    null, // debugStats
                    null, // outputBitmap
                    null, // tempRawPath
                    req.zoomFactor,
                    req.mirror,
                    req.metadata
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
                    if (req.saveJpg) req.fullResJpgPath else null,
                    if (req.saveRaw) req.linearDngPath else null,
                    req.digitalGain,
                    null, // debugStats
                    null, // outputBitmap
                    null, // tempRawPath
                    req.zoomFactor,
                    req.mirror,
                    req.metadata
                )
            }

            // Immediately release megaBuffer to free ~168MB memory
            HdrPlusBurst.releaseBuffer(req.megaBuffer)
            buffersReleased = true

            if (ret == 0) {
                if (req.saveJpg || req.saveRaw) {
                    updateNotification("Saving files...")
                    ColorProcessor.onBackgroundSaveComplete(
                        req.baseName,
                        if (req.saveRaw) req.linearDngPath else null,
                        if (req.saveJpg) req.fullResJpgPath else null,
                        req.zslTargetUriStr,
                        req.zoomFactor,
                        req.orientation,
                        req.saveJpg
                    )
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
            updateNotification("Idle")
        }
    }

    private fun createNotificationChannel() {
        val name = "HDR+ Processing"
        val descriptionText = "Background processing for HDR+ images"
        val importance = NotificationManager.IMPORTANCE_LOW
        val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
            description = descriptionText
        }
        val notificationManager: NotificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    private fun createNotification(contentText: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Darkbag Processing")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_launcher_foreground) // Ensure you have a valid icon
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(contentText: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, createNotification(contentText))
    }
}
