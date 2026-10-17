package top.maary.darkbag.utils

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Unified helper for decoding DNG thumbnails across Bento gallery grids.
 * Encapsulates primary embedded thumbnail decoding and fallback BitmapFactory decoding
 * with full EXIF rotation support.
 */
object GalleryThumbnailLoader {
    private const val TAG = "GalleryThumbnailLoader"

    /**
     * Loads and returns an upright thumbnail [Bitmap] for a given DNG [uri].
     *
     * 1. Attempts fast embedded JPEG preview extraction via [ImageUtils.decodeDngThumbnail].
     * 2. Falls back to subsampled [BitmapFactory] decoding with EXIF orientation correction.
     * 3. Ultimate fallback: renders raw mosaic via [ImageUtils.renderDngBitmap] if previews are absent.
     */
    suspend fun loadDngThumbnail(
        context: Context,
        uri: Uri,
        reqWidth: Int = 400,
        reqHeight: Int = 400
    ): Bitmap? = withContext(Dispatchers.IO) {
        try {
            // 1. Primary: decode embedded thumbnail via ImageUtils
            val primaryBitmap = ImageUtils.decodeDngThumbnail(
                context = context,
                uri = uri,
                reqWidth = reqWidth,
                reqHeight = reqHeight
            )
            if (primaryBitmap != null) {
                return@withContext primaryBitmap
            }

            // 2. Fallback: BitmapFactory + calculateInSampleSize + ExifInterface + rotateBitmap
            val fallbackBitmap = decodeFallback(context, uri, reqWidth, reqHeight)
            if (fallbackBitmap != null) {
                return@withContext fallbackBitmap
            }

            // 3. Ultimate fallback: full RAW render (for sensor dumps with no preview JPEG)
            ImageUtils.renderDngBitmap(context, uri, reqWidth = reqWidth, reqHeight = reqHeight)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load DNG thumbnail for $uri", e)
            null
        }
    }

    private fun decodeFallback(
        context: Context,
        uri: Uri,
        reqWidth: Int,
        reqHeight: Int
    ): Bitmap? {
        val filePath = if (uri.scheme == ContentResolver.SCHEME_FILE || uri.scheme == null) {
            uri.path
        } else {
            null
        }

        if (filePath != null && File(filePath).exists()) {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(filePath, options)
            val inSampleSize = ImageUtils.calculateInSampleSize(options, reqWidth, reqHeight)
            val decodeOpts = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            var bitmap = BitmapFactory.decodeFile(filePath, decodeOpts)
            if (bitmap != null) {
                val orientation = try {
                    ExifInterface(filePath).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL
                    )
                } catch (e: Exception) {
                    ExifInterface.ORIENTATION_NORMAL
                }
                if (orientation != ExifInterface.ORIENTATION_NORMAL) {
                    bitmap = ImageUtils.rotateBitmap(bitmap, orientation)
                }
            }
            return bitmap
        }

        // For Content URIs
        return try {
            var orientation = ExifInterface.ORIENTATION_NORMAL
            var bitmap: Bitmap? = null
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                try {
                    val exif = ExifInterface(pfd.fileDescriptor)
                    orientation = exif.getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL
                    )
                } catch (_: Exception) {
                }
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFileDescriptor(pfd.fileDescriptor, null, options)
                options.inSampleSize = ImageUtils.calculateInSampleSize(options, reqWidth, reqHeight)
                options.inJustDecodeBounds = false
                options.inPreferredConfig = Bitmap.Config.ARGB_8888
                try {
                    android.system.Os.lseek(pfd.fileDescriptor, 0, android.system.OsConstants.SEEK_SET)
                } catch (_: Exception) {
                }
                bitmap = BitmapFactory.decodeFileDescriptor(pfd.fileDescriptor, null, options)
            }
            if (bitmap != null && orientation != ExifInterface.ORIENTATION_NORMAL) {
                bitmap = ImageUtils.rotateBitmap(bitmap, orientation)
            }
            bitmap
        } catch (e: Exception) {
            null
        }
    }
}
