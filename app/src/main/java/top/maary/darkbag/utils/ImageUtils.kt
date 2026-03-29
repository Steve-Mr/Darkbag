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
    }

    fun parseUserComment(comment: String?): top.maary.darkbag.models.EditConfig? {
        if (comment == null) return null
        if (comment.startsWith("HF_LAYOUT:")) {
            val layout = comment.substringAfter("HF_LAYOUT:")
            return top.maary.darkbag.models.EditConfig(hfLayout = layout)
        } else if (comment.startsWith("{")) {
            return parseEditConfig(comment)
        }
        return null
    }

    fun parseEditConfig(jsonStr: String): top.maary.darkbag.models.EditConfig? {
        return try {
            val json = org.json.JSONObject(jsonStr)
            val adjustmentsArray = json.optJSONArray("adjustments")
            val adjustments = if (adjustmentsArray != null) {
                List(adjustmentsArray.length()) { i ->
                    val adjJson = adjustmentsArray.getJSONObject(i)
                    top.maary.darkbag.models.BasicAdjustments(
                        exposure = adjJson.optDouble("exposure", 0.0).toFloat(),
                        contrast = adjJson.optDouble("contrast", 0.0).toFloat(),
                        saturation = adjJson.optDouble("saturation", 0.0).toFloat(),
                        highlights = adjJson.optDouble("highlights", 0.0).toFloat(),
                        shadows = adjJson.optDouble("shadows", 0.0).toFloat(),
                        whites = adjJson.optDouble("whites", 0.0).toFloat(),
                        blacks = adjJson.optDouble("blacks", 0.0).toFloat(),
                        digitalGain = adjJson.optDouble("digital_gain", 1.0).toFloat()
                    )
                }
            } else null

            top.maary.darkbag.models.EditConfig(
                log = json.optString("log", "None"),
                lut = json.optString("lut", "None"),
                exposure = json.optDouble("exposure", 0.0).toFloat(),
                contrast = json.optDouble("contrast", 0.0).toFloat(),
                saturation = json.optDouble("saturation", 0.0).toFloat(),
                highlights = json.optDouble("highlights", 0.0).toFloat(),
                shadows = json.optDouble("shadows", 0.0).toFloat(),
                whites = json.optDouble("whites", 0.0).toFloat(),
                blacks = json.optDouble("blacks", 0.0).toFloat(),
                digitalGain = json.optDouble("digital_gain", 1.0).toFloat(),
                adjustments = adjustments,
                showTimestamp = json.optBoolean("show_timestamp", false),
                flareType = json.optInt("flare_type", -1),
                hfLayout = json.optString("hf_layout", "").takeIf { it.isNotBlank() },
                zoomFactor = json.optDouble("zoom_factor", 1.0).toFloat()
            )
        } catch (e: Exception) {
            null
        }
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
        var oriented1 = bit1?.let { ensureOrientation(it, wantPortrait) }
        var oriented2 = bit2?.let { ensureOrientation(it, wantPortrait) }

        try {
            // Resolution Matching: Scale higher-res image down to match lower-res image
            if (oriented1 != null && oriented2 != null) {
                if (layout == "TB") {
                    // Match width
                    if (oriented1.width != oriented2.width) {
                        if (oriented1.width > oriented2.width) {
                            val scale = oriented2.width.toFloat() / oriented1.width
                            val scaled = Bitmap.createScaledBitmap(oriented1, oriented2.width, (oriented1.height * scale).toInt(), true)
                            if (scaled != oriented1) oriented1.recycle()
                            oriented1 = scaled
                        } else {
                            val scale = oriented1.width.toFloat() / oriented2.width
                            val scaled = Bitmap.createScaledBitmap(oriented2, oriented1.width, (oriented2.height * scale).toInt(), true)
                            if (scaled != oriented2) oriented2.recycle()
                            oriented2 = scaled
                        }
                    }
                } else {
                    // Match height (SBS)
                    if (oriented1.height != oriented2.height) {
                        if (oriented1.height > oriented2.height) {
                            val scale = oriented2.height.toFloat() / oriented1.height
                            val scaled = Bitmap.createScaledBitmap(oriented1, (oriented1.width * scale).toInt(), oriented2.height, true)
                            if (scaled != oriented1) oriented1.recycle()
                            oriented1 = scaled
                        } else {
                            val scale = oriented1.height.toFloat() / oriented2.height
                            val scaled = Bitmap.createScaledBitmap(oriented2, (oriented2.width * scale).toInt(), oriented1.height, true)
                            if (scaled != oriented2) oriented2.recycle()
                            oriented2 = scaled
                        }
                    }
                }
            }

            val w1 = oriented1?.width ?: oriented2?.width ?: 0
            val h1 = oriented1?.height ?: oriented2?.height ?: 0
            val w2 = oriented2?.width ?: w1
            val h2 = oriented2?.height ?: h1

            val isSBS = layout != "TB"
            val gap = HalfFrameUtils.calculateGap(maxOf(w1, h1)).toFloat()

            val resultW = if (isSBS) (w1 + w2 + gap).toInt() else maxOf(w1, w2)
            val resultH = if (isSBS) maxOf(h1, h2) else (h1 + h2 + gap).toInt()

            val composite = Bitmap.createBitmap(resultW, resultH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(composite)
            canvas.drawColor(Color.BLACK)

            val paint = Paint(Paint.FILTER_BITMAP_FLAG)

            if (isSBS) {
                oriented1?.let { canvas.drawBitmap(it, 0f, 0f, paint) }
                oriented2?.let { canvas.drawBitmap(it, w1 + gap, 0f, paint) }
            } else {
                oriented1?.let { canvas.drawBitmap(it, 0f, 0f, paint) }
                oriented2?.let { canvas.drawBitmap(it, 0f, h1 + gap, paint) }
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

        val degrees = if (wantPortrait) 90f else 270f
        val matrix = Matrix().apply { postRotate(degrees) }
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
                        bitmap = bitmap?.let { rotateBitmap(it, orientation) }
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

    fun getCaptureTime(context: Context, uri: Uri): Long {
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                val exif = ExifInterface(pfd.fileDescriptor)
                val dateStr = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                    ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
                if (dateStr != null) {
                    val sdf = java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.US)
                    sdf.parse(dateStr)?.time ?: 0L
                } else 0L
            } ?: 0L
        } catch (e: Exception) {
            0L
        }
    }
}
