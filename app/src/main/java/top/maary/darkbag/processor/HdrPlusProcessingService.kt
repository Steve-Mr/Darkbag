package top.maary.darkbag.processor

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.maary.darkbag.R
import java.nio.ByteBuffer
import top.maary.darkbag.models.CaptureMetadata
import top.maary.darkbag.utils.HalfFrameManager
import top.maary.darkbag.utils.ImageSaver
import top.maary.darkbag.processor.ColorProcessor

class HdrPlusProcessingService : Service() {

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Processing Image")
            .setContentText("HDR+ in progress...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val request = pendingRequest ?: return stopSelfAndReturn()
        pendingRequest = null

        serviceScope.launch {
            val dumpedFile = request.orphanFile ?: OrphanManager.dumpRequest(applicationContext, request)
            try {
                val ret = ColorProcessor.processHdrPlus(
                    dngBuffers = request.buffers,
                    width = request.width,
                    height = request.height,
                    orientation = request.combinedOrientation,
                    whiteLevel = request.whiteLevel,
                    iso = request.captureMetadata.iso ?: 100,
                    blackLevelPattern = request.blackLevelPattern,
                    lensShadingMap = request.lensShadingMapData,
                    lensShadingRows = request.lensShadingRows,
                    lensShadingCols = request.lensShadingCols,
                    useSensorColorMatrix = request.useSensorColorMatrix,
                    whiteBalance = request.wb,
                    ccm = request.ccm,
                    ccmAlt = request.ccmAlt,
                    exportMatrixAB = request.exportMatrixAB,
                    cfaPattern = request.cfa,
                    targetLog = request.targetLogIndex,
                    lutPath = request.nativeLutPath,
                    outputJpgPath = request.tempJpgFile,
                    outputDngPath = request.linearDngPath,
                    digitalGain = request.digitalGain,
                    debugStats = request.debugStats,
                    outputBitmap = null,
                    tempRawPath = null,
                    zoomFactor = request.currentZoom,
                    mirror = request.mirror,
                    metadata = request.captureMetadata,
                    fastDenoise = request.fastDenoise
                )

                if (ret != null && request.targetUri != null) {
                    top.maary.darkbag.utils.ImageSaver.saveDirectJpeg(applicationContext, ret, android.net.Uri.parse(request.targetUri), request.captureMetadata, request.combinedOrientation, request.mirror, request.halfFrameMetadata)
                }

            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                dumpedFile?.delete()
                withContext(Dispatchers.Main) {
                    request.onComplete?.invoke()
                    stopSelf()
                }
            }
        }

        return START_NOT_STICKY
    }

    private fun stopSelfAndReturn(): Int {
        stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Image Processing",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    companion object {
        const val CHANNEL_ID = "HdrPlusProcessingChannel"
        const val NOTIFICATION_ID = 1

        var pendingRequest: ProcessingRequest? = null

        fun enqueueProcessing(context: Context, request: ProcessingRequest) {
            pendingRequest = request
            val intent = Intent(context, HdrPlusProcessingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}

data class ProcessingRequest(
    val buffers: Array<ByteBuffer>,
    val width: Int,
    val height: Int,
    val combinedOrientation: Int,
    val whiteLevel: Int,
    val blackLevelPattern: IntArray,
    val lensShadingMapData: FloatArray?,
    val lensShadingRows: Int,
    val lensShadingCols: Int,
    val useSensorColorMatrix: Boolean,
    val wb: FloatArray,
    val ccm: FloatArray,
    val ccmAlt: FloatArray?,
    val exportMatrixAB: Boolean,
    val cfa: Int,
    val targetLogIndex: Int,
    val nativeLutPath: String?,
    val tempJpgFile: String?,
    val linearDngPath: String?,
    val digitalGain: Float,
    val debugStats: LongArray?,
    val currentZoom: Float,
    val mirror: Boolean,
    val captureMetadata: CaptureMetadata,
    val fastDenoise: Boolean,
    val targetUri: String?,
    val halfFrameMetadata: top.maary.darkbag.utils.HalfFrameManager.Metadata?,
    val orphanFile: java.io.File? = null,
    val onComplete: (() -> Unit)? = null
)
