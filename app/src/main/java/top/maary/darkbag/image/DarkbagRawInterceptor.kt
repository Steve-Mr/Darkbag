package top.maary.darkbag.image

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import coil3.asImage
import coil3.intercept.Interceptor
import coil3.request.ImageResult
import coil3.request.SuccessResult
import coil3.decode.DataSource
import top.maary.darkbag.processor.ColorProcessor
import top.maary.darkbag.utils.DarkbagMetadata
import java.io.File

class DarkbagRawInterceptor(private val context: Context) : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        val data = request.data

        if (data is DarkbagImageRequest) {
            val dngUri = data.dngUri
            // If we have a DNG and either we are in RAW mode (neutral display)
            // OR we are in JPG mode but have non-default metadata (adjustments).
            val shouldProcessRaw = dngUri != null && (data.isRawMode || data.metadata != DarkbagMetadata())

            if (shouldProcessRaw) {
                val bitmap = processRaw(context, dngUri!!, data.metadata, data.isRawMode)
                if (bitmap != null) {
                    return SuccessResult(
                        image = bitmap.asImage(),
                        request = request,
                        dataSource = DataSource.DISK
                    )
                }
            }

            // Fallback: Proceed with the actual file URI (usually JPG or embedded DNG preview)
            val newRequest = request.newBuilder()
                .data(if (data.isRawMode) data.dngUri else data.uri)
                .build()
            return chain.withRequest(newRequest).proceed()
        }

        return chain.proceed()
    }

    private fun processRaw(context: Context, dngUri: Uri, metadata: DarkbagMetadata, isRawMode: Boolean): Bitmap? {
        return try {
            val bytes = context.contentResolver.openInputStream(dngUri)?.use { it.readBytes() } ?: return null

            var width = 0
            var height = 0
            var orientation = 0
            context.contentResolver.openInputStream(dngUri)?.use { inputStream ->
                val exif = ExifInterface(inputStream)
                width = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
                height = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)
                orientation = exif.rotationDegrees
            }

            if (width <= 0 || height <= 0) return null

            val isSwapped = orientation == 90 || orientation == 270
            val bmpWidth = if (isSwapped) height else width
            val bmpHeight = if (isSwapped) width else height

            val bitmap = Bitmap.createBitmap(bmpWidth, bmpHeight, Bitmap.Config.ARGB_8888)

            val lutDir = File(context.filesDir, "luts")
            val lutPath = if (isRawMode) null else metadata.lutName?.let {
                if (it.contains("..") || it.startsWith("/")) null
                else File(lutDir, it).absolutePath
            }

            val targetMetadata = if (isRawMode) DarkbagMetadata() else metadata

            val res = ColorProcessor.processRawToBitmap(
                dngData = bytes,
                targetLog = targetMetadata.logIndex,
                lutPath = lutPath,
                outputBitmap = bitmap,
                orientation = orientation,
                mirror = false,
                exposure = targetMetadata.exposure,
                highlights = targetMetadata.highlights,
                shadows = targetMetadata.shadows,
                whites = targetMetadata.whites,
                blacks = targetMetadata.blacks,
                contrast = targetMetadata.contrast,
                saturation = targetMetadata.saturation
            )

            if (res == 0) bitmap else null
        } catch (e: Exception) {
            null
        }
    }
}
