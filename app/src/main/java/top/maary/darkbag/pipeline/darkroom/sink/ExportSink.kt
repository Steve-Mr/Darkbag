package top.maary.darkbag.pipeline.darkroom.sink

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import top.maary.darkbag.pipeline.darkroom.model.DarkroomRenderResult
import top.maary.darkbag.pipeline.darkroom.model.DarkroomRenderSpec
import java.io.File

/**
 * 后期暗房与工坊冲洗管线输出契约 (ExportSink Contract).
 * 遵循 Architectural Invariant 1: Pipeline-Agnostic Output.
 * 冲洗引擎只负责解算调色与拼图，不直接感知存储目标或 MediaStore 路径。
 */
interface ExportSink {
    /**
     * 当渲染产物为内存 Bitmap 时回调（如调色预览、内存缓存）。
     * @return true 表示 Sink 接受并持有该 Bitmap（调用方不应回收它），
     *         false 表示 Sink 未接管所有权（调用方需负责清理或复用）。
     */
    suspend fun onBitmapExported(
        context: Context,
        bitmap: Bitmap,
        spec: DarkroomRenderSpec
    ): Boolean = false

    /**
     * 当渲染产物为本地文件时回调（如全分辨率 JPG、TIFF 底片）。
     * @return 最终持久化后的目标 Uri（如已写入 MediaStore），或 null。
     */
    suspend fun onFileExported(
        context: Context,
        file: File,
        mimeType: String,
        spec: DarkroomRenderSpec
    ): Uri? = null

    /**
     * 当整个冲洗导出任务完成时回调。
     */
    suspend fun onComplete(
        context: Context,
        spec: DarkroomRenderSpec,
        result: DarkroomRenderResult
    ) {}

    /**
     * 当冲洗或导出发生不可恢复的错误时回调。
     */
    fun onError(
        context: Context,
        spec: DarkroomRenderSpec,
        error: Throwable
    ) {}

    /**
     * 为多机位或子流派生子 Sink，保持与主 Sink 相同的存储隔离语义。
     * @param childBaseName 子流的基础文件名
     * @param childTargetUri 覆写模式下的目标 Uri，若为 null 则自动生成或由子 Sink 决定
     * @param isReplacement 是否为覆写原文件模式
     */
    fun createChildSink(
        childBaseName: String,
        childTargetUri: Uri? = null,
        isReplacement: Boolean = false
    ): ExportSink
}
