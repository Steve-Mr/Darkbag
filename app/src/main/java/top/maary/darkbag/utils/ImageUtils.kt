package top.maary.darkbag.utils

import android.content.Context
import android.graphics.*
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.maary.darkbag.processor.ColorProcessor

object ImageUtils {

    fun getBaseName(fileName: String): String {
        return fileName.substringBeforeLast(".")
            .replace("_linear", "")
            .replace("_bayer", "")
            .replace("_HDRPLUS", "")
            .replace("_full", "")
            .replace("_HF1", "")
            .replace("_HF2", "")
            .replace("_stitched", "")
            .replace("stitched_hf_", "")
            .replace(top.maary.darkbag.utils.DarkbagIdentity.FILE_PREFIX, "")
    }

    suspend fun generateHalfFrameComposite(
        context: Context,
        uri1: Uri?,
        uri2: Uri?,
        layout: String?, // "SBS" or "TB"
        zoomFactor: Float = 1.0f
    ): Bitmap? = withContext(Dispatchers.IO) {
        val bit1 = uri1?.let { decodeDngThumbnail(context, it, zoomFactor) }
        val bit2 = uri2?.let { decodeDngThumbnail(context, it, zoomFactor) }

        if (bit1 == null && bit2 == null) return@withContext null

        val wantPortrait = layout != "TB"
        val oriented1 = bit1?.let { ensureOrientation(it, wantPortrait) }
        val oriented2 = bit2?.let { ensureOrientation(it, wantPortrait) }

        try {
            // Use first available frame as reference for dimensions
            val ref = oriented1 ?: oriented2!!
            val w = ref.width
            val h = ref.height

            val isSBS = top.maary.darkbag.utils.LayoutUtils.isSideBySide(layout)

            val final1 = oriented1 ?: createPlaceholderBitmap(context, w, h)
            val final2 = oriented2 ?: createPlaceholderBitmap(context, w, h)

            val composite = HalfFrameUtils.composeBitmaps(final1, final2, isSBS)

            if (final1 != oriented1) final1.recycle()
            if (final2 != oriented2) final2.recycle()

            return@withContext composite
        } catch (e: Exception) {
            android.util.Log.e("ImageUtils", "Failed to generate composite", e)
            null
        } finally {
            // Cleanup oriented bitmaps as they are intermediate
            if (oriented1 != bit1) oriented1?.recycle()
            if (oriented2 != bit2) oriented2?.recycle()
        }
    }

    private fun createPlaceholderBitmap(context: Context, w: Int, h: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val rect = RectF(0f, 0f, w.toFloat(), h.toFloat())
        val paint = Paint().apply {
            color = Color.DKGRAY
            style = Paint.Style.FILL
        }
        canvas.drawRect(rect, paint)

        val icon = androidx.core.content.ContextCompat.getDrawable(context, android.R.drawable.ic_menu_gallery)
        icon?.let {
            val iconSize = (kotlin.math.min(w, h) * 0.3f).toInt()
            val left = (w / 2) - (iconSize / 2)
            val top = (h / 2) - (iconSize / 2)
            it.setBounds(left, top, left + iconSize, top + iconSize)
            it.setTint(Color.LTGRAY)
            it.draw(canvas)
        }
        return bitmap
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

    suspend fun decodeDngThumbnail(context: Context, uri: Uri, zoomFactor: Float = 1.0f): Bitmap? = withContext(Dispatchers.IO) {
        try {
            var bitmap: Bitmap? = null
            var orientation = ExifInterface.ORIENTATION_NORMAL

            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                val exif = ExifInterface(pfd.fileDescriptor)
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
                bitmap = bitmap?.let { rotateBitmap(it, orientation) }
            }

            return@withContext if (bitmap != null && zoomFactor > 1.05f) {
                val newWidth = (bitmap.width / zoomFactor).toInt()
                val newHeight = (bitmap.height / zoomFactor).toInt()
                val x = (bitmap.width - newWidth) / 2
                val y = (bitmap.height - newHeight) / 2
                val safeX = kotlin.math.max(0, x)
                val safeY = kotlin.math.max(0, y)
                val safeWidth = kotlin.math.min(newWidth, bitmap.width - safeX)
                val safeHeight = kotlin.math.min(newHeight, bitmap.height - safeY)

                val cropped = Bitmap.createBitmap(bitmap, safeX, safeY, safeWidth, safeHeight)
                if (cropped != bitmap) bitmap.recycle()
                cropped
            } else bitmap
        } catch (e: Exception) {
            android.util.Log.e("ImageUtils", "Failed to decode DNG: $uri", e)
        }
        null
    }

    suspend fun renderDngBitmap(
        context: Context,
        uri: Uri,
        reqWidth: Int = 2048,
        reqHeight: Int = 2048,
        zoomFactor: Float = 1.0f
    ): Bitmap? = withContext(Dispatchers.IO) {
        try {
            coroutineContext.ensureActive()
            val dngBytes = context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                java.io.FileInputStream(pfd.fileDescriptor).use { it.readBytes() }
            } ?: return@withContext null

            coroutineContext.ensureActive()
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(dngBytes, 0, dngBytes.size, bounds)
            val downsample = calculateInSampleSize(bounds, reqWidth, reqHeight)

            val orientation = try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                } ?: ExifInterface.ORIENTATION_NORMAL
            } catch (e: Exception) {
                ExifInterface.ORIENTATION_NORMAL
            }

            val rotDegrees = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }

            val fullW = if (rotDegrees == 90 || rotDegrees == 270) bounds.outHeight / downsample else bounds.outWidth / downsample
            val fullH = if (rotDegrees == 90 || rotDegrees == 270) bounds.outWidth / downsample else bounds.outHeight / downsample
            if (fullW <= 0 || fullH <= 0) return@withContext null

            val bmpW = kotlin.math.max(1, (fullW / zoomFactor).toInt())
            val bmpH = kotlin.math.max(1, (fullH / zoomFactor).toInt())
            val bitmap = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)

            coroutineContext.ensureActive()
            val result = ColorProcessor.processRaw(
                dngData = dngBytes,
                targetLog = 0,
                lutPath = null,
                digitalGain = 1.0f,
                outputJpgPath = null,
                useGpu = false,
                orientation = rotDegrees,
                mirror = false,
                outputBitmap = bitmap,
                downsampleFactor = downsample,
                zoomFactor = zoomFactor
            )

            if (result < 0) {
                bitmap.recycle()
                return@withContext null
            }

            coroutineContext.ensureActive()
            bitmap
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("ImageUtils", "Failed to render DNG bitmap: $uri", e)
            null
        }
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
