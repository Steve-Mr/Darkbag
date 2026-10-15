package top.maary.darkbag.pipeline.sink

import android.content.Context
import top.maary.darkbag.pipeline.model.CaptureTaskSpec

/**
 * 拍摄管线输出目标描述符与文件路径。
 */
data class SinkOutputTargets(
    val outJpgFd: Int = -1,
    val outJpgPath: String? = null,
    val outDngFd: Int = -1,
    val outDngPath: String? = null
) {
    val hasJpgTarget: Boolean get() = outJpgFd >= 0 || !outJpgPath.isNullOrEmpty()
    val hasDngTarget: Boolean get() = outDngFd >= 0 || !outDngPath.isNullOrEmpty()
}

/**
 * 拍摄管线交付产物的核心契约接口。
 * 计算管线只负责解算和调色，不关心图像数据的存储介质或最终去向。
 */
interface CaptureSink {
    /**
     * 准备输出目标句柄或路径 (在 Stage 2 导出前调用)。
     * 例如：申请 MediaStore Pending PFD 或配置本地磁盘缓存路径。
     */
    fun prepareTargets(context: Context, spec: CaptureTaskSpec): SinkOutputTargets

    /**
     * 当 JPEG 图像数据完成 native 导出后回调。
     */
    suspend fun onImageExported(
        context: Context,
        spec: CaptureTaskSpec,
        success: Boolean,
        outputPath: String?,
        outputFd: Int
    )

    /**
     * 当 RAW (DNG) 数据完成 native 导出后回调。
     */
    suspend fun onRawExported(
        context: Context,
        spec: CaptureTaskSpec,
        success: Boolean,
        outputPath: String?,
        outputFd: Int
    )

    /**
     * 阶段 2 导出流程全部结束时回调。
     * 可在此执行二次拼接、EXIF 增强或通知。
     */
    suspend fun onComplete(
        context: Context,
        spec: CaptureTaskSpec,
        jpgSuccess: Boolean,
        rawSuccess: Boolean
    )

    /**
     * 异常或中断处理。
     */
    suspend fun onError(context: Context, spec: CaptureTaskSpec, error: Throwable)
}
