package top.maary.darkbag.pipeline.darkroom.sink

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import top.maary.darkbag.models.CaptureMetadata
import top.maary.darkbag.pipeline.darkroom.model.DarkroomRenderResult
import top.maary.darkbag.pipeline.darkroom.model.DarkroomRenderSpec
import top.maary.darkbag.pipeline.darkroom.model.DarkroomSource
import top.maary.darkbag.utils.DarkbagIdentity
import top.maary.darkbag.utils.ImageSaver
import java.io.File

/**
 * 暗房相册导出 Sink (MediaStoreExportSink).
 * 将处理后的照片、半格拼图或多摄成果持久化至系统 MediaStore，支持覆写或另存为新文件。
 */
class MediaStoreExportSink(
    private val baseName: String? = null,
    private val targetUri: Uri? = null,
    private val isReplacement: Boolean = false,
    private val jpgFolderUri: String? = null,
    private val saveRaw: Boolean = false,
    private val onSaved: ((Uri) -> Unit)? = null
) : ExportSink {
    var savedUri: Uri? = null
        private set

    private fun resolveBaseName(spec: DarkroomRenderSpec): String {
        val rawBase = baseName ?: spec.effectiveBaseName
        val prefixed = DarkbagIdentity.prefixedBaseName(rawBase)
        return if (isReplacement) {
            prefixed
        } else {
            val editTimestamp = java.text.SimpleDateFormat(
                "yyyy-MM-dd-HH-mm-ss-SSS",
                java.util.Locale.US
            ).format(java.util.Date())
            "${prefixed}_edited_$editTimestamp"
        }
    }

    private fun resolveMetadata(spec: DarkroomRenderSpec): CaptureMetadata? {
        return when (val source = spec.source) {
            is DarkroomSource.Single -> source.captureMetadata
            is DarkroomSource.HalfFrame -> source.frame1.captureMetadata ?: source.frame2.captureMetadata
            is DarkroomSource.MultiCamera -> null
        }
    }

    override suspend fun onBitmapExported(
        context: Context,
        bitmap: Bitmap,
        spec: DarkroomRenderSpec
    ): Boolean {
        val outBaseName = resolveBaseName(spec)
        val meta = resolveMetadata(spec)
        val uri = ImageSaver.saveProcessedImage(
            context = context,
            inputBitmap = bitmap,
            bmpPath = null,
            rotationDegrees = 0,
            zoomFactor = 1.0f,
            baseName = outBaseName,
            linearDngPath = null,
            saveJpg = true,
            saveRaw = saveRaw,
            jpgFolderUri = jpgFolderUri,
            targetUri = if (isReplacement) targetUri else null,
            editConfig = spec.editConfig,
            captureMetadata = meta,
            isAlreadyStitched = spec.source is DarkroomSource.HalfFrame
        )
        savedUri = uri
        uri?.let { onSaved?.invoke(it) }
        return false // 由 Engine 负责回收或生命周期管理
    }

    override suspend fun onFileExported(
        context: Context,
        file: File,
        mimeType: String,
        spec: DarkroomRenderSpec
    ): Uri? {
        val outBaseName = resolveBaseName(spec)
        val meta = resolveMetadata(spec)
        val uri = ImageSaver.saveProcessedImage(
            context = context,
            inputBitmap = null,
            bmpPath = file.absolutePath,
            rotationDegrees = 0,
            zoomFactor = 1.0f,
            baseName = outBaseName,
            linearDngPath = null,
            saveJpg = true,
            saveRaw = saveRaw,
            jpgFolderUri = jpgFolderUri,
            targetUri = if (isReplacement) targetUri else null,
            editConfig = spec.editConfig,
            isAlreadyStitched = true,
            captureMetadata = meta
        )
        savedUri = uri
        uri?.let { onSaved?.invoke(it) }
        return uri
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
        savedUri = null
    }
}
