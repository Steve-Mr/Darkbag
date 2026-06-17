package top.maary.darkbag.processor

import java.nio.ByteBuffer
import top.maary.darkbag.models.CaptureMetadata
import top.maary.darkbag.utils.HalfFrameManager

data class HdrPlusRequest(
    val requestId: String,
    val buffers: Array<ByteBuffer>,
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

    // Output specs
    val isSingleFrame: Boolean, // false for burst, true for single frame raw
    val saveJpg: Boolean,
    val saveRaw: Boolean,
    val baseName: String,
    val fullResJpgPath: String,
    val linearDngPath: String,
    val zslTargetUriStr: String?,

    // Save metadata
    val jpgFolderUri: String?,
    val rawFolderUri: String?,
    val hfMetadata: HalfFrameManager.Metadata?,
    val editConfig: top.maary.darkbag.models.EditConfig?
)

object HdrPlusRequestManager {
    private val queue = java.util.concurrent.LinkedBlockingQueue<HdrPlusRequest>(2)

    fun enqueue(request: HdrPlusRequest) {
        if (!queue.offer(request)) throw IllegalStateException("HDR+ Queue is full. Please wait before capturing more.")
    }

    fun dequeue(): HdrPlusRequest? {
        return queue.poll()
    }

    fun peek(): HdrPlusRequest? {
        return queue.peek()
    }
}
