package top.maary.darkbag.pipeline.model

import top.maary.darkbag.models.StandardTimingTracker
import top.maary.darkbag.utils.HalfFrameManager

/**
 * 完整的拍摄任务规格聚合。
 * 将原先穿透传递的 50+ 个散装参数收拢为强类型领域对象。
 */
data class CaptureTaskSpec(
    val taskId: String,
    val width: Int,
    val height: Int,
    val orientation: Int,
    val zoomFactor: Float = 1.0f,
    val mirror: Boolean = false,
    val isSingleFrame: Boolean = false,
    val hardwareProfile: HardwareProfile,
    val frameMetadata: CaptureFrameMetadata,
    val renderRecipe: RenderRecipe,
    val timing: StandardTimingTracker? = null,
    val dngCompressionMode: Int = 0,
    val rawOutputType: Int = 0,
    val fusionMode: Int = 0,
    // 兼容过渡字段 (在 Milestone 2 引入 CaptureSink 后移出)
    val baseName: String = "",
    val fullResJpgPath: String = "",
    val linearDngPath: String = "",
    val saveJpg: Boolean = true,
    val saveRaw: Boolean = false,
    val jpgFolderUri: String? = null,
    val rawFolderUri: String? = null,
    val hfMetadata: HalfFrameManager.Metadata? = null,
    val motionPhotoMp4Path: String? = null,
    val motionPhotoStillPtsUs: Long = 0L,
    val sink: top.maary.darkbag.pipeline.sink.CaptureSink? = null
) {
    fun getEffectiveSink(): top.maary.darkbag.pipeline.sink.CaptureSink =
        sink ?: top.maary.darkbag.pipeline.sink.CaptureSinkFactory.createDefaultSink(this)
}
