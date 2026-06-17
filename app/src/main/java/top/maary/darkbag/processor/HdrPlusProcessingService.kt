package top.maary.darkbag.processor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.maary.darkbag.R
import top.maary.darkbag.fragments.HdrPlusBurst
import top.maary.darkbag.fragments.SettingsFragment
import top.maary.darkbag.utils.ImageSaver

class HdrPlusProcessingService : Service() {

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var isProcessing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification("Processing image...")
        startForeground(NOTIFICATION_ID, notification)

        processNextInQueue()

        return START_NOT_STICKY
    }

    private fun processNextInQueue() {
        if (isProcessing) return

        val request = HdrPlusRequestManager.dequeue()
        if (request == null) {
            // Queue empty, stop service
            androidx.core.app.ServiceCompat.stopForeground(this, androidx.core.app.ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        isProcessing = true
        serviceScope.launch {
            try {
                processRequest(request)
            } catch (e: Exception) {
                Log.e(TAG, "Error processing HDR+ request: ${request.requestId}", e)
            } finally {
                withContext(Dispatchers.Main) {
                    isProcessing = false
                    processNextInQueue()
                }
            }
        }
    }

    private suspend fun processRequest(req: HdrPlusRequest) {
        Log.d(TAG, "Processing started for ${req.baseName}")

        val outputJpgPath = if (req.saveJpg) req.fullResJpgPath else null
        val outputDngPath = if (req.saveRaw) req.linearDngPath else null
        val debugStats = LongArray(15)

        val jniStartTime = System.currentTimeMillis()
        val ret = if (req.isSingleFrame) {
            ColorProcessor.processSingleFrameRaw(
                req.buffers[0],
                req.width, req.height,
                req.orientation,
                req.whiteLevel, req.blackLevelPattern,
                req.lensShadingMap, req.lensShadingRows, req.lensShadingCols,
                req.whiteBalance, req.ccm, req.cfaPattern,
                req.targetLogIndex,
                req.lutPath,
                outputJpgPath,
                outputDngPath,
                req.digitalGain,
                debugStats,
                null,
                req.zoomFactor,
                req.mirror,
                req.metadata
            )
        } else {
            ColorProcessor.processHdrPlus(
                req.buffers,
                req.width, req.height,
                req.orientation,
                req.whiteLevel, req.blackLevelPattern,
                req.lensShadingMap, req.lensShadingRows, req.lensShadingCols, req.useSensorColorMatrix,
                req.whiteBalance, req.ccm, req.ccmAlt, req.exportMatrixAB, req.cfaPattern,
                req.targetLogIndex,
                req.lutPath,
                outputJpgPath,
                outputDngPath,
                req.digitalGain,
                debugStats,
                null,
                req.zoomFactor,
                req.mirror,
                req.metadata
            )
        }
        val jniEndTime = System.currentTimeMillis()

        if (ret == 0) {
            Log.d(TAG, "JNI finished successfully for ${req.baseName}")
            val saveStartTime = System.currentTimeMillis()
            val targetUri = req.zslTargetUriStr?.let { Uri.parse(it) }

            val finalUri = ImageSaver.saveProcessedImage(
                context = applicationContext,
                inputBitmap = null,
                bmpPath = outputJpgPath,
                rotationDegrees = 0,
                zoomFactor = 1.0f,
                baseName = req.baseName,
                linearDngPath = outputDngPath,
                saveJpg = req.saveJpg,
                saveRaw = req.saveRaw,
                jpgFolderUri = req.jpgFolderUri,
                rawFolderUri = req.rawFolderUri,
                targetUri = targetUri,
                mirror = false,
                halfFrameMetadata = req.hfMetadata,
                editConfig = req.editConfig,
                digitalGain = req.digitalGain,
                captureMetadata = req.metadata
            )

            val saveEndTime = System.currentTimeMillis()

            val totalTime = saveEndTime - jniStartTime
            val jniTime = jniEndTime - jniStartTime
            val halideTime = debugStats[0]
            val copyTime = debugStats[1]
            val postTime = debugStats[2]
            val dngEncodeTime = debugStats[3]
            val nativeSaveTime = debugStats[4]
            val dngWaitTime = debugStats[5]
            val nativeTotalTime = debugStats[6]
            val saveTime = saveEndTime - saveStartTime

            val logMsg = """
                [Background Process: ${totalTime}ms]
                JNI (Total): ${jniTime}ms
                  - Native Total: ${nativeTotalTime}ms
                  - JNI Prep: ${debugStats[12]}ms
                  - Copy: ${copyTime}ms
                  - Halide: ${halideTime}ms
                    * Align: ${debugStats[7]}ms
                    * Merge: ${debugStats[8]}ms
                    * BlackWhite: ${debugStats[13]}ms
                    * WB: ${debugStats[14]}ms
                    * Demosaic: ${debugStats[9]}ms
                    * Denoise: ${debugStats[10]}ms
                    * sRGB: ${debugStats[11]}ms
                  - Post: ${postTime}ms
                  - DNG Encode: ${dngEncodeTime}ms
                  - Save(Log/BMP): ${nativeSaveTime}ms
                  - DNG Wait(get): ${dngWaitTime}ms
                Save (IO/Compress): ${saveTime}ms
            """.trimIndent()

            Log.i(TAG, logMsg)
            top.maary.darkbag.utils.DebugLogManager.addLog(logMsg)

            if (finalUri != null) {
                val prefs = applicationContext.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit().putString(SettingsFragment.KEY_LAST_CAPTURE_URI, finalUri.toString()).apply()
            }

            withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(applicationContext, "HDR+ Background Processed", android.widget.Toast.LENGTH_SHORT).show()
            }

            ColorProcessor.onBackgroundSaveComplete(
                req.baseName, null, null, finalUri?.toString(), req.zoomFactor, req.orientation, req.saveJpg
            )
        } else {
            Log.e(TAG, "JNI failed with code $ret for ${req.baseName}")
            ColorProcessor.onBackgroundSaveComplete(
                req.baseName, null, null, null, req.zoomFactor, req.orientation, req.saveJpg
            )
            withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(applicationContext, "HDR+ failed in background", android.widget.Toast.LENGTH_SHORT).show()
            }
        }

        req.buffers.forEach {
            HdrPlusBurst.releaseBuffer(it)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Processing",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Image processing"
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(content: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Darkbag")
            .setContentText(content)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
    }

    companion object {
        private const val TAG = "HdrPlusService"
        private const val CHANNEL_ID = "hdr_plus_processing_channel"
        private const val NOTIFICATION_ID = 1001
    }
}
