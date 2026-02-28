package top.maary.darkbag.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ImageUtils {

    suspend fun decodeDngThumbnail(context: Context, uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        try {
            var bitmap: Bitmap? = null
            var orientation = ExifInterface.ORIENTATION_NORMAL

            context.contentResolver.openInputStream(uri)?.use { input ->
                val exif = ExifInterface(input)
                orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                if (exif.hasThumbnail()) {
                    val thumb = exif.thumbnailBytes
                    if (thumb != null) {
                        bitmap = BitmapFactory.decodeByteArray(thumb, 0, thumb.size)
                    }
                }
            }

            if (bitmap == null) {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    val options = BitmapFactory.Options().apply {
                        inJustDecodeBounds = true
                    }
                    BitmapFactory.decodeFileDescriptor(pfd.fileDescriptor, null, options)
                    options.inSampleSize = calculateInSampleSize(options, 1024, 1024)
                    options.inJustDecodeBounds = false
                    bitmap = BitmapFactory.decodeFileDescriptor(pfd.fileDescriptor, null, options)
                }
            }

            return@withContext bitmap?.let { rotateBitmap(it, orientation) }
        } catch (e: Exception) {
            android.util.Log.e("ImageUtils", "Failed to decode DNG: $uri", e)
        }
        null
    }

    fun rotateBitmap(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            else -> return bitmap
        }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated != bitmap) bitmap.recycle()
        return rotated
    }

    fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.run { outHeight to outWidth }
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }
}
