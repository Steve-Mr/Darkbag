package top.maary.darkbag.pipeline.darkroom.sink

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import top.maary.darkbag.pipeline.darkroom.model.DarkroomRenderResult
import top.maary.darkbag.pipeline.darkroom.model.DarkroomRenderSpec
import java.io.File
import java.io.FileOutputStream

/**
 * 临时分享与底片外发 Sink (ShareFileSink).
 * 将冲洗产物（如 TIFF 胶片、高质量 JPG）保存至指定的临时文件，用于系统分享 Intent 或外部导出。
 */
class ShareFileSink(
    val outputFile: File,
    val mimeType: String = "image/jpeg"
) : ExportSink {
    var deliveredFile: File? = null
        private set

    override suspend fun onFileExported(
        context: Context,
        file: File,
        mimeType: String,
        spec: DarkroomRenderSpec
    ): Uri? {
        outputFile.parentFile?.mkdirs()
        if (file.absolutePath != outputFile.absolutePath) {
            file.copyTo(outputFile, overwrite = true)
        }
        deliveredFile = outputFile
        return Uri.fromFile(outputFile)
    }

    override suspend fun onBitmapExported(
        context: Context,
        bitmap: Bitmap,
        spec: DarkroomRenderSpec
    ): Boolean {
        outputFile.parentFile?.mkdirs()
        FileOutputStream(outputFile).use { out ->
            val format = if (mimeType.contains("png")) {
                Bitmap.CompressFormat.PNG
            } else {
                Bitmap.CompressFormat.JPEG
            }
            bitmap.compress(format, 95, out)
        }
        deliveredFile = outputFile
        return false
    }

    override suspend fun onComplete(
        context: Context,
        spec: DarkroomRenderSpec,
        result: DarkroomRenderResult
    ) {
        // 完成处理
    }

    override fun onError(
        context: Context,
        spec: DarkroomRenderSpec,
        error: Throwable
    ) {
        deliveredFile = null
    }

    override fun createChildSink(
        childBaseName: String,
        childTargetUri: Uri?,
        isReplacement: Boolean
    ): ExportSink {
        val ext = outputFile.extension.ifEmpty { if (mimeType.contains("tiff")) "tif" else "jpg" }
        val childFile = File(outputFile.parentFile, "$childBaseName.$ext")
        return ShareFileSink(
            outputFile = childFile,
            mimeType = mimeType
        )
    }
}
