package top.maary.darkbag.pipeline.darkroom.sink

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import top.maary.darkbag.pipeline.darkroom.model.DarkroomRenderResult
import top.maary.darkbag.pipeline.darkroom.model.DarkroomRenderSpec

/**
 * 实时降采样调色预览与内存接收 Sink (BitmapPreviewSink).
 * 直接在内存中交付渲染完成的 Bitmap，用于滑动条实时刷新。
 */
class BitmapPreviewSink(
    private val onBitmapReady: ((Bitmap) -> Unit)? = null
) : ExportSink {
    var deliveredBitmap: Bitmap? = null
        private set

    override suspend fun onBitmapExported(
        context: Context,
        bitmap: Bitmap,
        spec: DarkroomRenderSpec
    ): Boolean {
        deliveredBitmap = bitmap
        onBitmapReady?.invoke(bitmap)
        return true // 接管 Bitmap 所有权，供 UI 层渲染显示
    }

    override suspend fun onComplete(
        context: Context,
        spec: DarkroomRenderSpec,
        result: DarkroomRenderResult
    ) {
        // 完成回调
    }

    override fun onError(
        context: Context,
        spec: DarkroomRenderSpec,
        error: Throwable
    ) {
        deliveredBitmap = null
    }

    override fun createChildSink(
        childBaseName: String,
        childTargetUri: Uri?,
        isReplacement: Boolean
    ): ExportSink = BitmapPreviewSink(onBitmapReady = onBitmapReady)
}
