package top.maary.darkbag.utils

import android.content.Context
import android.graphics.*
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.min

object MultiCameraCollageHelper {

    enum class CollageLayout {
        SIDE_BY_SIDE, // 2 frames horizontal
        TOP_BOTTOM,   // 2 frames vertical
        TRIPTYCH_ROW  // 3 frames horizontal
    }

    suspend fun createCollage(
        context: Context,
        imageUris: List<Uri>,
        layout: CollageLayout,
        borderWidthDp: Float = 16f,
        dividerWidthDp: Float = 8f,
        backgroundColor: Int = Color.WHITE,
        cornerRadiusDp: Float = 0f
    ): Bitmap? = withContext(Dispatchers.IO) {
        if (imageUris.isEmpty()) return@withContext null

        val density = context.resources.displayMetrics.density
        val borderPx = (borderWidthDp * density).toInt()
        val dividerPx = (dividerWidthDp * density).toInt()
        val cornerPx = cornerRadiusDp * density

        val loadedBitmaps = mutableListOf<Bitmap>()
        try {
            for (uri in imageUris) {
                val bmp = decodeSampledBitmapFromUri(context, uri, 2048, 2048)
                if (bmp != null) {
                    loadedBitmaps.add(bmp)
                }
            }

            if (loadedBitmaps.isEmpty()) return@withContext null

            when {
                loadedBitmaps.size == 2 && layout == CollageLayout.SIDE_BY_SIDE -> {
                    composeSideBySide(loadedBitmaps[0], loadedBitmaps[1], borderPx, dividerPx, backgroundColor, cornerPx)
                }
                loadedBitmaps.size == 2 && layout == CollageLayout.TOP_BOTTOM -> {
                    composeTopBottom(loadedBitmaps[0], loadedBitmaps[1], borderPx, dividerPx, backgroundColor, cornerPx)
                }
                loadedBitmaps.size >= 3 || layout == CollageLayout.TRIPTYCH_ROW -> {
                    val frames = loadedBitmaps.take(3)
                    composeTriptychRow(frames, borderPx, dividerPx, backgroundColor, cornerPx)
                }
                else -> {
                    loadedBitmaps.firstOrNull()?.let { Bitmap.createBitmap(it) }
                }
            }
        } finally {
            loadedBitmaps.forEach {
                if (!it.isRecycled) it.recycle()
            }
        }
    }

    private fun composeSideBySide(
        bmp1: Bitmap,
        bmp2: Bitmap,
        borderPx: Int,
        dividerPx: Int,
        bgColor: Int,
        cornerPx: Float
    ): Bitmap {
        val targetHeight = min(bmp1.height, bmp2.height).coerceAtMost(2400)
        val w1 = (bmp1.width * (targetHeight.toFloat() / bmp1.height)).toInt()
        val w2 = (bmp2.width * (targetHeight.toFloat() / bmp2.height)).toInt()

        val totalWidth = borderPx * 2 + w1 + dividerPx + w2
        val totalHeight = borderPx * 2 + targetHeight

        val result = Bitmap.createBitmap(totalWidth, totalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawColor(bgColor)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        // Draw Frame 1
        val dstRect1 = RectF(
            borderPx.toFloat(),
            borderPx.toFloat(),
            (borderPx + w1).toFloat(),
            (borderPx + targetHeight).toFloat()
        )
        drawBitmapWithRoundedCorners(canvas, bmp1, dstRect1, cornerPx, paint)

        // Draw Frame 2
        val dstRect2 = RectF(
            (borderPx + w1 + dividerPx).toFloat(),
            borderPx.toFloat(),
            (borderPx + w1 + dividerPx + w2).toFloat(),
            (borderPx + targetHeight).toFloat()
        )
        drawBitmapWithRoundedCorners(canvas, bmp2, dstRect2, cornerPx, paint)

        return result
    }

    private fun composeTopBottom(
        bmp1: Bitmap,
        bmp2: Bitmap,
        borderPx: Int,
        dividerPx: Int,
        bgColor: Int,
        cornerPx: Float
    ): Bitmap {
        val targetWidth = min(bmp1.width, bmp2.width).coerceAtMost(2400)
        val h1 = (bmp1.height * (targetWidth.toFloat() / bmp1.width)).toInt()
        val h2 = (bmp2.height * (targetWidth.toFloat() / bmp2.height)).toInt()

        val totalWidth = borderPx * 2 + targetWidth
        val totalHeight = borderPx * 2 + h1 + dividerPx + h2

        val result = Bitmap.createBitmap(totalWidth, totalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawColor(bgColor)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        // Draw Frame 1
        val dstRect1 = RectF(
            borderPx.toFloat(),
            borderPx.toFloat(),
            (borderPx + targetWidth).toFloat(),
            (borderPx + h1).toFloat()
        )
        drawBitmapWithRoundedCorners(canvas, bmp1, dstRect1, cornerPx, paint)

        // Draw Frame 2
        val dstRect2 = RectF(
            borderPx.toFloat(),
            (borderPx + h1 + dividerPx).toFloat(),
            (borderPx + targetWidth).toFloat(),
            (borderPx + h1 + dividerPx + h2).toFloat()
        )
        drawBitmapWithRoundedCorners(canvas, bmp2, dstRect2, cornerPx, paint)

        return result
    }

    private fun composeTriptychRow(
        frames: List<Bitmap>,
        borderPx: Int,
        dividerPx: Int,
        bgColor: Int,
        cornerPx: Float
    ): Bitmap {
        val targetHeight = frames.minOf { it.height }.coerceAtMost(1800)
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
