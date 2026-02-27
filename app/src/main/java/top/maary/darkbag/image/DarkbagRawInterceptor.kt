package top.maary.darkbag.image

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import coil3.asImage
import coil3.intercept.Interceptor
import coil3.request.ImageResult
import coil3.request.SuccessResult
import coil3.decode.DataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import top.maary.darkbag.processor.ColorProcessor
import top.maary.darkbag.utils.DarkbagMetadata
import java.io.File

class DarkbagRawInterceptor(private val context: Context) : Interceptor {
    private val semaphore = Semaphore(1)

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        val data = request.data

        if (data is DarkbagImageRequest) {
            val startTime = System.currentTimeMillis()
            val dngUri = data.dngUri

            // Optimization: If it's a JPG and NOT modified, load the JPG directly.
            // Instant display for existing photos.
            if (!data.isRawMode && !data.isModified && !data.forceRaw) {
                val newRequest = request.newBuilder()
                    .data(data.uri)
                    .build()
                return chain.withRequest(newRequest).proceed()
            }

            // We need RAW processing if:
            // 1. It's RAW mode (neutral DNG view)
            // 2. It's JPG mode but modified (needs re-render)
            // 3. It's DNG-only (no JPG exists)
            val shouldProcessRaw = dngUri != null && (data.isRawMode || data.isModified || data.forceRaw)

            if (shouldProcessRaw) {
                val bitmap = semaphore.withPermit {
                    withContext(Dispatchers.Default) {
                        if (data.isThumbnail) {
                            extractThumbnail(context, dngUri!!)
                        } else {
                            processRaw(context, dngUri!!, data.metadata, data.isRawMode, data.quality)
                        }
                    }
                }
                if (bitmap != null) {
                    Log.d("DarkbagRawInterceptor", "Processed RAW in ${System.currentTimeMillis() - startTime}ms. mode=${if(data.isRawMode) "RAW" else "JPG"}")
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

    private fun extractThumbnail(context: Context, dngUri: Uri): Bitmap? {
        return try {
            val pfd = context.contentResolver.openFileDescriptor(dngUri, "r") ?: return null
            val bytes = pfd.use { fd ->
                val stream = java.io.FileInputStream(fd.fileDescriptor)
                stream.use { it.readBytes() }
            }
            ColorProcessor.extractDngThumbnail(bytes)
        } catch (e: Exception) {
            null
        }
    }

    private fun processRaw(context: Context, dngUri: Uri, metadata: DarkbagMetadata, isRawMode: Boolean, quality: Int): Bitmap? {
        return try {
            val pfd = context.contentResolver.openFileDescriptor(dngUri, "r") ?: return null
            val bytes = pfd.use { fd ->
                val stream = java.io.FileInputStream(fd.fileDescriptor)
                stream.use { it.readBytes() }
            }

            var width = 0
            var height = 0
            var orientation = 0

            // Use a ByteArrayInputStream for ExifInterface to avoid re-opening/re-reading from disk
            java.io.ByteArrayInputStream(bytes).use { bis ->
                val exif = ExifInterface(bis)
                width = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
                height = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)
                orientation = exif.rotationDegrees
            }

            if (width <= 0 || height <= 0) return null

            val isSwapped = orientation == 90 || orientation == 270

            // Limit viewer bitmap size to prevent OOM and keep performance reasonable
            // while still maintaining high fidelity.
            val maxDimension = 2560f
            val scale = if (maxOf(width, height) > maxDimension) maxDimension / maxOf(width, height) else 1.0f

            val targetW = (width * scale).toInt()
            val targetH = (height * scale).toInt()

            val bmpWidth = if (isSwapped) targetH else targetW
            val bmpHeight = if (isSwapped) targetW else targetH

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
                saturation = targetMetadata.saturation,
                quality = quality
            )

            if (res == 0) bitmap else null
        } catch (e: Exception) {
            null
        }
    }
}
