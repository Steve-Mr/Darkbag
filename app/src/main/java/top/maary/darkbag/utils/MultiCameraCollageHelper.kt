package top.maary.darkbag.utils

import android.content.Context
import android.graphics.*
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

object MultiCameraCollageHelper {

    enum class CollageLayout(val minImages: Int, val maxImages: Int) {
        SIDE_BY_SIDE(2, 6),    // Horizontal row
        TOP_BOTTOM(2, 6),      // Vertical column
        TRIPTYCH_ROW(3, 3),    // 3-Row panoramic
        TRIPTYCH_COLUMN(3, 3), // 3-Column vertical
        FEATURED_TOP(3, 3),    // 1 Large Top + 2 Small Bottom
        FEATURED_LEFT(3, 3)    // 1 Large Left + 2 Small Right
    }

    enum class FramingStrategy {
        AUTO_BALANCE,  // Automatically rotate sub-frames when extreme ratio would occur
        CROP_FILL,     // Center crop sub-frames to standard aspect ratio
        DIRECT_STITCH  // Raw uncropped, unrotated direct stitch
    }

    data class FrameMetadata(
        val lensTag: String = "",
        val focalLengthMm: String = "",
        val aperture: String = "",
        val shutter: String = "",
        val iso: String = ""
    ) {
        fun toDisplayString(): String {
            val parts = mutableListOf<String>()
            if (lensTag.isNotBlank()) parts.add(lensTag)
            if (focalLengthMm.isNotBlank()) parts.add(focalLengthMm)
            if (aperture.isNotBlank()) parts.add(aperture)
            if (shutter.isNotBlank()) parts.add(shutter)
            if (iso.isNotBlank()) parts.add("ISO $iso")
            return parts.joinToString(" · ")
        }
    }

    suspend fun createCollage(
        context: Context,
        imageUris: List<Uri>,
        layout: CollageLayout,
        framingStrategy: FramingStrategy = FramingStrategy.AUTO_BALANCE,
        rotationAngle: Int = 0,
        showExif: Boolean = false,
        borderWidthDp: Float = 16f,
        dividerWidthDp: Float = 8f,
        backgroundColor: Int = Color.WHITE,
        cornerRadiusDp: Float = 0f,
        maxDimension: Int = 3000
    ): Bitmap? = withContext(Dispatchers.IO) {
        if (imageUris.isEmpty()) return@withContext null

        val density = context.resources.displayMetrics.density
        val borderPx = (borderWidthDp * density).toInt()
        val dividerPx = (dividerWidthDp * density).toInt()
        val cornerPx = cornerRadiusDp * density

        val loadedBitmaps = mutableListOf<Bitmap>()
        val metadataList = mutableListOf<FrameMetadata>()

        try {
            for (uri in imageUris) {
                var bmp = decodeSampledBitmapFromUri(context, uri, maxDimension, maxDimension)
                if (bmp != null) {
                    if (rotationAngle != 0) {
                        val rotated = rotateBitmap(bmp, rotationAngle)
                        if (rotated != bmp) {
                            bmp.recycle()
                            bmp = rotated
                        }
                    }
                    loadedBitmaps.add(bmp)
                    if (showExif) {
                        metadataList.add(readExifFromUri(context, uri))
                    }
                }
            }

            if (loadedBitmaps.isEmpty()) return@withContext null

            // Apply Framing Strategy (Auto-Balance or Crop-Fill)
            val preparedBitmaps = prepareBitmapsWithStrategy(loadedBitmaps, layout, framingStrategy)

            when (layout) {
                CollageLayout.SIDE_BY_SIDE, CollageLayout.TRIPTYCH_ROW -> {
                    composeHorizontalRow(preparedBitmaps, metadataList, showExif, borderPx, dividerPx, backgroundColor, cornerPx, density)
                }
                CollageLayout.TOP_BOTTOM, CollageLayout.TRIPTYCH_COLUMN -> {
                    composeVerticalColumn(preparedBitmaps, metadataList, showExif, borderPx, dividerPx, backgroundColor, cornerPx, density)
                }
                CollageLayout.FEATURED_TOP -> {
                    if (preparedBitmaps.size >= 3) {
                        composeFeaturedTop(preparedBitmaps.take(3), borderPx, dividerPx, backgroundColor, cornerPx)
                    } else {
                        composeHorizontalRow(preparedBitmaps, metadataList, showExif, borderPx, dividerPx, backgroundColor, cornerPx, density)
                    }
                }
                CollageLayout.FEATURED_LEFT -> {
                    if (preparedBitmaps.size >= 3) {
                        composeFeaturedLeft(preparedBitmaps.take(3), borderPx, dividerPx, backgroundColor, cornerPx)
                    } else {
                        composeVerticalColumn(preparedBitmaps, metadataList, showExif, borderPx, dividerPx, backgroundColor, cornerPx, density)
                    }
                }
            }
        } finally {
            loadedBitmaps.forEach {
                if (!it.isRecycled) it.recycle()
            }
        }
    }

    private fun prepareBitmapsWithStrategy(
        bitmaps: List<Bitmap>,
        layout: CollageLayout,
        strategy: FramingStrategy
    ): List<Bitmap> {
        if (bitmaps.isEmpty()) return bitmaps
        val first = bitmaps[0]
        val isFirstPortrait = first.height > first.width

        return when (strategy) {
            FramingStrategy.AUTO_BALANCE -> {
                when (layout) {
                    CollageLayout.TOP_BOTTOM, CollageLayout.TRIPTYCH_COLUMN -> {
                        // Stacking portrait vertically yields an extreme tall aspect ratio. Rotate 90 to balance!
                        if (isFirstPortrait) {
                            bitmaps.map { rotateBitmap(it, 90) }
                        } else bitmaps
                    }
                    CollageLayout.SIDE_BY_SIDE, CollageLayout.TRIPTYCH_ROW -> {
                        // Placing landscape horizontally yields an extreme wide aspect ratio. Rotate 90 to balance!
                        if (!isFirstPortrait) {
                            bitmaps.map { rotateBitmap(it, 90) }
                        } else bitmaps
                    }
                    else -> bitmaps
                }
            }
            FramingStrategy.CROP_FILL -> {
                when (layout) {
                    CollageLayout.TOP_BOTTOM, CollageLayout.TRIPTYCH_COLUMN -> {
                        // Center crop to 3:2 landscape window so vertical stack is balanced 3:4
                        bitmaps.map { cropToAspectRatio(it, 3f / 2f) }
                    }
                    CollageLayout.SIDE_BY_SIDE, CollageLayout.TRIPTYCH_ROW -> {
                        // Center crop to 2:3 portrait window so horizontal row is balanced 4:3
                        bitmaps.map { cropToAspectRatio(it, 2f / 3f) }
                    }
                    else -> bitmaps
                }
            }
            FramingStrategy.DIRECT_STITCH -> bitmaps
        }
    }

    private fun cropToAspectRatio(src: Bitmap, targetRatio: Float): Bitmap {
        val srcRatio = src.width.toFloat() / src.height.toFloat()
        val cropW: Int
        val cropH: Int

        if (srcRatio > targetRatio) {
            cropH = src.height
            cropW = (cropH * targetRatio).toInt()
        } else {
            cropW = src.width
            cropH = (cropW / targetRatio).toInt()
        }

        val startX = (src.width - cropW) / 2
        val startY = (src.height - cropH) / 2
        return Bitmap.createBitmap(src, startX, startY, cropW, cropH)
    }

    private fun rotateBitmap(source: Bitmap, angle: Int): Bitmap {
        if (angle == 0) return source
        val matrix = Matrix().apply { postRotate(angle.toFloat()) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    private fun composeHorizontalRow(
        frames: List<Bitmap>,
        metadata: List<FrameMetadata>,
        showExif: Boolean,
        borderPx: Int,
        dividerPx: Int,
        bgColor: Int,
        cornerPx: Float,
        density: Float
    ): Bitmap {
        val targetHeight = frames.minOf { it.height }.coerceAtMost(2400)
        val scaledWidths = frames.map { (it.width * (targetHeight.toFloat() / it.height)).toInt() }

        val exifHeightPx = if (showExif) (32 * density).toInt() else 0
        val totalWidth = borderPx * 2 + scaledWidths.sum() + (frames.size - 1) * dividerPx
        val totalHeight = borderPx * 2 + targetHeight + exifHeightPx

        val result = Bitmap.createBitmap(totalWidth, totalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawColor(bgColor)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 12f * density
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            color = if (isDarkColor(bgColor)) Color.parseColor("#CCCCCC") else Color.parseColor("#555555")
            textAlign = Paint.Align.CENTER
        }

        var currentX = borderPx.toFloat()
        for (i in frames.indices) {
            val bmp = frames[i]
            val w = scaledWidths[i]
            val dstRect = RectF(currentX, borderPx.toFloat(), currentX + w, (borderPx + targetHeight).toFloat())
            drawBitmapWithRoundedCorners(canvas, bmp, dstRect, cornerPx, paint)

            if (showExif && i < metadata.size) {
                val text = metadata[i].toDisplayString()
                val textY = borderPx + targetHeight + (20 * density)
                canvas.drawText(text, currentX + w / 2f, textY, textPaint)
            }

            currentX += w + dividerPx
        }

        return result
    }

    private fun composeVerticalColumn(
        frames: List<Bitmap>,
        metadata: List<FrameMetadata>,
        showExif: Boolean,
        borderPx: Int,
        dividerPx: Int,
        bgColor: Int,
        cornerPx: Float,
        density: Float
    ): Bitmap {
        val targetWidth = frames.minOf { it.width }.coerceAtMost(2400)
        val scaledHeights = frames.map { (it.height * (targetWidth.toFloat() / it.width)).toInt() }

        val exifHeightPerFrame = if (showExif) (26 * density).toInt() else 0
        val totalWidth = borderPx * 2 + targetWidth
        val totalHeight = borderPx * 2 + scaledHeights.sum() + (frames.size - 1) * dividerPx + (frames.size * exifHeightPerFrame)

        val result = Bitmap.createBitmap(totalWidth, totalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawColor(bgColor)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 11.5f * density
            color = if (isDarkColor(bgColor)) Color.parseColor("#CCCCCC") else Color.parseColor("#555555")
            textAlign = Paint.Align.CENTER
        }

        var currentY = borderPx.toFloat()
        for (i in frames.indices) {
            val bmp = frames[i]
            val h = scaledHeights[i]
            val dstRect = RectF(borderPx.toFloat(), currentY, (borderPx + targetWidth).toFloat(), currentY + h)
            drawBitmapWithRoundedCorners(canvas, bmp, dstRect, cornerPx, paint)

            currentY += h
            if (showExif && i < metadata.size) {
                val text = metadata[i].toDisplayString()
                val textY = currentY + (17 * density)
                canvas.drawText(text, totalWidth / 2f, textY, textPaint)
                currentY += exifHeightPerFrame
            }
            currentY += dividerPx
        }

        return result
    }

    private fun composeFeaturedTop(
        frames: List<Bitmap>,
        borderPx: Int,
        dividerPx: Int,
        bgColor: Int,
        cornerPx: Float
    ): Bitmap {
        val topBmp = frames[0]
        val botBmp1 = frames[1]
        val botBmp2 = frames[2]

        val rTop = topBmp.width.toFloat() / topBmp.height.toFloat()
        val rBot1 = botBmp1.width.toFloat() / botBmp1.height.toFloat()
        val rBot2 = botBmp2.width.toFloat() / botBmp2.height.toFloat()

        val contentWidth = 2400
        val topHeight = (contentWidth / rTop).toInt()

        val availableBotWidth = contentWidth - dividerPx
        val botHeight = (availableBotWidth / (rBot1 + rBot2)).toInt()
        val botW1 = (botHeight * rBot1).toInt()
        val botW2 = availableBotWidth - botW1

        val totalWidth = borderPx * 2 + contentWidth
        val totalHeight = borderPx * 2 + topHeight + dividerPx + botHeight

        val result = Bitmap.createBitmap(totalWidth, totalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawColor(bgColor)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        // Draw Top Image
        val topRect = RectF(
            borderPx.toFloat(),
            borderPx.toFloat(),
            (borderPx + contentWidth).toFloat(),
            (borderPx + topHeight).toFloat()
        )
        drawBitmapWithRoundedCorners(canvas, topBmp, topRect, cornerPx, paint)

        // Draw Bottom Left Image
        val bot1Rect = RectF(
            borderPx.toFloat(),
            (borderPx + topHeight + dividerPx).toFloat(),
            (borderPx + botW1).toFloat(),
            (borderPx + topHeight + dividerPx + botHeight).toFloat()
        )
        drawBitmapWithRoundedCorners(canvas, botBmp1, bot1Rect, cornerPx, paint)

        // Draw Bottom Right Image
        val bot2Rect = RectF(
            (borderPx + botW1 + dividerPx).toFloat(),
            (borderPx + topHeight + dividerPx).toFloat(),
            (borderPx + contentWidth).toFloat(),
            (borderPx + topHeight + dividerPx + botHeight).toFloat()
        )
        drawBitmapWithRoundedCorners(canvas, botBmp2, bot2Rect, cornerPx, paint)

        return result
    }

    private fun composeFeaturedLeft(
        frames: List<Bitmap>,
        borderPx: Int,
        dividerPx: Int,
        bgColor: Int,
        cornerPx: Float
    ): Bitmap {
        val leftBmp = frames[0]
        val rightBmp1 = frames[1]
        val rightBmp2 = frames[2]

        val rLeft = leftBmp.width.toFloat() / leftBmp.height.toFloat()
        val invR1 = rightBmp1.height.toFloat() / rightBmp1.width.toFloat()
        val invR2 = rightBmp2.height.toFloat() / rightBmp2.width.toFloat()

        val contentHeight = 2400
        val leftWidth = (contentHeight * rLeft).toInt()

        val availableRightHeight = contentHeight - dividerPx
        val rightWidth = (availableRightHeight / (invR1 + invR2)).toInt()
        val rightH1 = (rightWidth * invR1).toInt()
        val rightH2 = availableRightHeight - rightH1

        val totalWidth = borderPx * 2 + leftWidth + dividerPx + rightWidth
        val totalHeight = borderPx * 2 + contentHeight

        val result = Bitmap.createBitmap(totalWidth, totalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawColor(bgColor)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        // Draw Left Image
        val leftRect = RectF(
            borderPx.toFloat(),
            borderPx.toFloat(),
            (borderPx + leftWidth).toFloat(),
            (borderPx + contentHeight).toFloat()
        )
        drawBitmapWithRoundedCorners(canvas, leftBmp, leftRect, cornerPx, paint)

        // Draw Right Top Image
        val right1Rect = RectF(
            (borderPx + leftWidth + dividerPx).toFloat(),
            borderPx.toFloat(),
            (borderPx + leftWidth + dividerPx + rightWidth).toFloat(),
            (borderPx + rightH1).toFloat()
        )
        drawBitmapWithRoundedCorners(canvas, rightBmp1, right1Rect, cornerPx, paint)

        // Draw Right Bottom Image
        val right2Rect = RectF(
            (borderPx + leftWidth + dividerPx).toFloat(),
            (borderPx + rightH1 + dividerPx).toFloat(),
            (borderPx + leftWidth + dividerPx + rightWidth).toFloat(),
            (borderPx + contentHeight).toFloat()
        )
        drawBitmapWithRoundedCorners(canvas, rightBmp2, right2Rect, cornerPx, paint)

        return result
    }

    private fun drawBitmapWithRoundedCorners(
        canvas: Canvas,
        bitmap: Bitmap,
        dstRect: RectF,
        cornerRadius: Float,
        paint: Paint
    ) {
        if (cornerRadius > 0) {
            val path = Path().apply {
                addRoundRect(dstRect, cornerRadius, cornerRadius, Path.Direction.CW)
            }
            canvas.save()
            canvas.clipPath(path)
            canvas.drawBitmap(bitmap, null, dstRect, paint)
            canvas.restore()
        } else {
            canvas.drawBitmap(bitmap, null, dstRect, paint)
        }
    }

    private fun readExifFromUri(context: Context, uri: Uri): FrameMetadata {
        return try {
            val tag = ImageUtils.extractMultiCameraLensTag(uri.toString())
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                val focalLength = exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM)
                    ?: exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH)
                val focalStr = focalLength?.let { "${it}mm" } ?: ""

                val fNumber = exif.getAttribute(ExifInterface.TAG_F_NUMBER)
                val fStr = fNumber?.let { "f/$it" } ?: ""

                val expTime = exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)
                val shutterStr = expTime?.let {
                    val d = it.toDoubleOrNull()
                    if (d != null && d > 0 && d < 1.0) "1/${(1.0 / d).toInt()}s" else "${it}s"
                } ?: ""

                val iso = exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)
                    ?: exif.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS)
                    ?: ""

                FrameMetadata(
                    lensTag = tag,
                    focalLengthMm = focalStr,
                    aperture = fStr,
                    shutter = shutterStr,
                    iso = iso
                )
            } ?: FrameMetadata(lensTag = tag)
        } catch (e: Exception) {
            FrameMetadata(lensTag = ImageUtils.extractMultiCameraLensTag(uri.toString()))
        }
    }

    private fun isDarkColor(color: Int): Boolean {
        val darkness = 1 - (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255
        return darkness >= 0.5
    }

    private fun decodeSampledBitmapFromUri(
        context: Context,
        uri: Uri,
        reqWidth: Int,
        reqHeight: Int
    ): Bitmap? {
        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }

            options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
            options.inJustDecodeBounds = false

            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
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
