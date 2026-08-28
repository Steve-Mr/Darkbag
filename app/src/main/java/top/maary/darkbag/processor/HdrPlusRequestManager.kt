package top.maary.darkbag.processor

import android.net.Uri
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import top.maary.darkbag.models.CaptureMetadata
import top.maary.darkbag.models.EditConfig
import top.maary.darkbag.utils.HalfFrameManager
import java.nio.ByteBuffer

data class HdrPlusRequest(
    val requestId: String,
    val megaBuffer: ByteBuffer,
    val numFrames: Int,
    val width: Int,
    val height: Int,
    val orientation: Int,
    val whiteLevel: Int,
    val blackLevelPattern: IntArray,
    val lensShadingMap: FloatArray?,
    val lensShadingRows: Int,
    val lensShadingCols: Int,
    val useSensorColorMatrix: Boolean,
    val whiteBalance: FloatArray,
    val ccm: FloatArray,
    val ccmAlt: FloatArray?,
    val exportMatrixAB: Boolean,
    val cfaPattern: Int,
    val targetLogIndex: Int,
    val lutPath: String?,
    val digitalGain: Float,
    val zoomFactor: Float,
    val mirror: Boolean,
    val metadata: CaptureMetadata,
    val isSingleFrame: Boolean,
    val saveJpg: Boolean,
    val saveRaw: Boolean,
    val baseName: String,
    val fullResJpgPath: String,
    val linearDngPath: String,
    val zslTargetUriStr: String?,
    val jpgFolderUri: String?,
    val rawFolderUri: String?,
    val hfMetadata: HalfFrameManager.Metadata?,
    val editConfig: EditConfig?,
    val runAblationTest: Boolean,
    val motionPhotoMp4Path: String? = null,
    val motionPhotoStillPtsUs: Long = 0L,
    val enableMemoryColor: Boolean = false
)

object HdrPlusRequestManager {
    // UNLIMITED channel to prevent dropping requests during bursts
    private val requestChannel = Channel<HdrPlusRequest>(Channel.UNLIMITED)
    
    val requestFlow = requestChannel.receiveAsFlow()

    private val _pendingTasksCount = MutableStateFlow(0)
    val pendingTasksCount: StateFlow<Int> = _pendingTasksCount.asStateFlow()

    fun enqueue(request: HdrPlusRequest) {
        _pendingTasksCount.update { it + 1 }
        val result = requestChannel.trySend(request)
        if (!result.isSuccess) {
            _pendingTasksCount.update { (it - 1).coerceAtLeast(0) }
            throw IllegalStateException("Failed to enqueue HdrPlusRequest: ${request.requestId}")
        }
    }

    fun onTaskFinished() {
        _pendingTasksCount.update { (it - 1).coerceAtLeast(0) }
    }
}
