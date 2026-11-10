package top.maary.darkbag.pipeline.darkroom.sink

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import top.maary.darkbag.pipeline.darkroom.model.DarkroomRenderResult
import top.maary.darkbag.pipeline.darkroom.model.DarkroomRenderSpec
import java.io.File
import java.io.FileOutputStream

/**
 * 工坊沙盒隔离导出 Sink (SandboxExportSink).
 * 将冲洗产物直接写入 App 私有目录或指定的沙盒文件，不污染公共系统 MediaStore.
 */
class SandboxExportSink(
    private val targetFile: File? = null,
    private val targetDirectory: File? = null,
    private val baseName: String? = null
) : ExportSink {
    var exportedFile: File? = null
        private set

    override suspend fun onBitmapExported(
        context: Context,
        bitmap: Bitmap,
        spec: DarkroomRenderSpec
    ): Boolean {
        val destination = targetFile ?: File(
            targetDirectory ?: context.cacheDir,
            "${baseName ?: spec.effectiveBaseName}.jpg"
        )
        destination.parentFile?.mkdirs()
        FileOutputStream(destination).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
        }
        exportedFile = destination
        return false
    }

    override suspend fun onFileExported(
        context: Context,
        file: File,
        mimeType: String,
        spec: DarkroomRenderSpec
    ): Uri? {
        val destination = targetFile ?: File(
            targetDirectory ?: context.cacheDir,
            "${baseName ?: spec.effectiveBaseName}.${if (mimeType.contains("tiff")) "tif" else "jpg"}"
        )
        destination.parentFile?.mkdirs()
        if (file.absolutePath != destination.absolutePath) {
            file.copyTo(destination, overwrite = true)
        }
        exportedFile = destination
        return Uri.fromFile(destination)
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
        exportedFile = null
    }
}
