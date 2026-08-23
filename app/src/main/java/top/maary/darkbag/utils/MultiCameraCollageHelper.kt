package top.maary.darkbag.utils

import android.content.Context
import android.graphics.*
import android.net.Uri
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

    suspend fun createCollage(
        context: Context,
        imageUris: List<Uri>,
        layout: CollageLayout,
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
        try {
            for (uri in imageUris) {
                val bmp = decodeSampledBitmapFromUri(context, uri, maxDimension, maxDimension)
                if (bmp != null) {
                    loadedBitmaps.add(bmp)
                }
            }

            if (loadedBitmaps.isEmpty()) return@withContext null

            when (layout) {
                CollageLayout.SIDE_BY_SIDE, CollageLayout.TRIPTYCH_ROW -> {
                    composeHorizontalRow(loadedBitmaps, borderPx, dividerPx, backgroundColor, cornerPx)
                }
                CollageLayout.TOP_BOTTOM, CollageLayout.TRIPTYCH_COLUMN -> {
                    composeVerticalColumn(loadedBitmaps, borderPx, dividerPx, backgroundColor, cornerPx)
                }
                CollageLayout.FEATURED_TOP -> {
                    if (loadedBitmaps.size >= 3) {
                        composeFeaturedTop(loadedBitmaps.take(3), borderPx, dividerPx, backgroundColor, cornerPx)
                    } else {
                        composeHorizontalRow(loadedBitmaps, borderPx, dividerPx, backgroundColor, cornerPx)
                    }
                }
                CollageLayout.FEATURED_LEFT -> {
                    if (loadedBitmaps.size >= 3) {
                        composeFeaturedLeft(loadedBitmaps.take(3), borderPx, dividerPx, backgroundColor, cornerPx)
                    } else {
                        composeVerticalColumn(loadedBitmaps, borderPx, dividerPx, backgroundColor, cornerPx)
                    }
                }
            }
        } finally {
            loadedBitmaps.forEach {
                if (!it.isRecycled) it.recycle()
            }
        }
    }

    private fun composeHorizontalRow(
        frames: List<Bitmap>,
        borderPx: Int,
        dividerPx: Int,
        bgColor: Int,
        cornerPx: Float
    ): Bitmap {
        val targetHeight = frames.minOf { it.height }.coerceAtMost(2400)
        val scaledWidths = frames.map { (it.width * (targetHeight.toFloat() / it.height)).toInt() }

        val totalWidth = borderPx * 2 + scaledWidths.sum() + (frames.size - 1) * dividerPx
        val totalHeight = borderPx * 2 + targetHeight

        val result = Bitmap.createBitmap(totalWidth, totalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawColor(bgColor)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        var currentX = borderPx.toFloat()
        for (i in frames.indices) {
            val bmp = frames[i]
            val w = scaledWidths[i]
            val dstRect = RectF(currentX, borderPx.toFloat(), currentX + w, (borderPx + targetHeight).toFloat())
            drawBitmapWithRoundedCorners(canvas, bmp, dstRect, cornerPx, paint)
            currentX += w + dividerPx
        }

        return result
    }

    private fun composeVerticalColumn(
        frames: List<Bitmap>,
        borderPx: Int,
        dividerPx: Int,
        bgColor: Int,
        cornerPx: Float
    ): Bitmap {
        val targetWidth = frames.minOf { it.width }.coerceAtMost(2400)
        val scaledHeights = frames.map { (it.height * (targetWidth.toFloat() / it.width)).toInt() }

        val totalWidth = borderPx * 2 + targetWidth
        val totalHeight = borderPx * 2 + scaledHeights.sum() + (frames.size - 1) * dividerPx

        val result = Bitmap.createBitmap(totalWidth, totalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawColor(bgColor)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        var currentY = borderPx.toFloat()
        for (i in frames.indices) {
            val bmp = frames[i]
            val h = scaledHeights[i]
            val dstRect = RectF(borderPx.toFloat(), currentY, (borderPx + targetWidth).toFloat(), currentY + h)
            drawBitmapWithRoundedCorners(canvas, bmp, dstRect, cornerPx, paint)
            currentY += h + dividerPx
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
