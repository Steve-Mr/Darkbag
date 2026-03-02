package top.maary.darkbag.utils

import android.content.Context
import android.graphics.*
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ImageUtils {

    suspend fun generateHalfFrameComposite(
        context: Context,
        uri1: Uri?,
        uri2: Uri?,
        layout: String? // "SBS" or "TB"
    ): Bitmap? = withContext(Dispatchers.IO) {
        val bit1 = uri1?.let { decodeDngThumbnail(context, it) }
        val bit2 = uri2?.let { decodeDngThumbnail(context, it) }

        if (bit1 == null && bit2 == null) return@withContext null

        val wantPortrait = layout != "TB"
        val oriented1 = bit1?.let { ensureOrientation(it, wantPortrait) }
        val oriented2 = bit2?.let { ensureOrientation(it, wantPortrait) }

        try {
            // Use first available frame as reference for dimensions
            val ref = oriented1 ?: oriented2!!
            val w = ref.width
            val h = ref.height

            val isSBS = layout != "TB"
            val gap = (if (isSBS) h else w) * 0.03f

            val resultW = if (isSBS) (w * 2 + gap).toInt() else w
            val resultH = if (isSBS) h else (h * 2 + gap).toInt()

            val composite = Bitmap.createBitmap(resultW, resultH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(composite)
            canvas.drawColor(Color.BLACK)

            val paint = Paint(Paint.FILTER_BITMAP_FLAG)

            if (isSBS) {
                oriented1?.let { canvas.drawBitmap(it, 0f, 0f, paint) }
                oriented2?.let { canvas.drawBitmap(it, w + gap, 0f, paint) }
            } else {
                oriented1?.let { canvas.drawBitmap(it, 0f, 0f, paint) }
                oriented2?.let { canvas.drawBitmap(it, 0f, h + gap, paint) }
            }

            return@withContext composite
        } catch (e: Exception) {
            android.util.Log.e("ImageUtils", "Failed to generate composite", e)
            null
        } finally {
            // Cleanup oriented bitmaps as they are intermediate
            oriented1?.recycle()
            oriented2?.recycle()
        }
    }

    private fun ensureOrientation(bitmap: Bitmap, wantPortrait: Boolean): Bitmap {
        val isPortrait = bitmap.height >= bitmap.width
        if (isPortrait == wantPortrait) {
            val config = bitmap.config ?: Bitmap.Config.ARGB_8888
            return bitmap.copy(config, true)
        }

        val matrix = Matrix().apply { postRotate(90f) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

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
