package top.maary.darkbag.utils

import android.content.Context
import android.graphics.*
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

object HalfFrameUtils {
    private const val TAG = "HalfFrameUtils"
    const val GAP_RATIO = 0.03f
    const val MIN_GAP_PX = 16

    fun calculateGap(refDimension: Int): Int {
        return (refDimension * GAP_RATIO).toInt().coerceAtLeast(MIN_GAP_PX)
    }

    fun ensureOrientation(bitmap: Bitmap, wantPortrait: Boolean): Bitmap {
        val isPortrait = bitmap.height > bitmap.width
        val isSquare = bitmap.height == bitmap.width

        // If it's already the desired orientation or square, return as is
        if (isPortrait == wantPortrait || isSquare) return bitmap

        val matrix = Matrix().apply { postRotate(90f) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /**
     * Stitches two images according to the layout without stretching.
     */

    /**
     * Composes two bitmaps into a single half-frame layout.
     * Assumes the bitmaps have already been correctly oriented (e.g., using ensureOrientation).
     */
    fun composeBitmaps(firstBitmap: Bitmap, secondBitmap: Bitmap, isSideBySide: Boolean): Bitmap {
        val w1 = firstBitmap.width
        val h1 = firstBitmap.height
        val w2 = secondBitmap.width
        val h2 = secondBitmap.height

        // Target size for each slot (use first frame as reference)
        val targetW = w1
        val targetH = h1

        // Divider width
        val divider = calculateGap(maxOf(targetW, targetH))

        return if (isSideBySide) {
            val combinedW = targetW + divider + targetW
            val combinedH = targetH

            val result = Bitmap.createBitmap(combinedW, combinedH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(result)
            canvas.drawColor(Color.BLACK)

            // Draw first (Left)
            canvas.drawBitmap(firstBitmap, 0f, 0f, null)
            // Draw second (Right) - scaled to fit if necessary, but keep aspect ratio
            val rect2 = getFitRect(w2, h2, targetW, targetH)
            val dest2 = Rect(targetW + divider + rect2.left, rect2.top, targetW + divider + rect2.right, rect2.bottom)
            canvas.drawBitmap(secondBitmap, Rect(0, 0, w2, h2), dest2, Paint(Paint.FILTER_BITMAP_FLAG))
            result
        } else {
            // Top-bottom: [Img1 / Divider / Img2]
            val combinedW = targetW
            val combinedH = targetH + divider + targetH

            val result = Bitmap.createBitmap(combinedW, combinedH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(result)
            canvas.drawColor(Color.BLACK)

            // Draw first (Top)
            canvas.drawBitmap(firstBitmap, 0f, 0f, null)
            // Draw second (Bottom)
            val rect2 = getFitRect(w2, h2, targetW, targetH)
            val dest2 = Rect(rect2.left, targetH + divider + rect2.top, rect2.right, targetH + divider + rect2.bottom)
            canvas.drawBitmap(secondBitmap, Rect(0, 0, w2, h2), dest2, Paint(Paint.FILTER_BITMAP_FLAG))
            result
        }
    }

    fun stitchImages(
        firstPath: String,
        secondPath: String,
        layout: String,
        downsample: Boolean
    ): Bitmap? {
        val firstRaw = BitmapFactory.decodeFile(firstPath) ?: return null
        val secondRaw = BitmapFactory.decodeFile(secondPath) ?: return null

        val isSideBySide = layout == "Side-by-side" || layout == "左右排列" || layout.contains("side", ignoreCase = true) || layout == "SBS"

        // Side-by-side wants Portrait inputs; Top-bottom wants Landscape inputs
        val firstBitmap = ensureOrientation(firstRaw, isSideBySide)
        val secondBitmap = ensureOrientation(secondRaw, isSideBySide)

        try {
            var resultBitmap = composeBitmaps(firstBitmap, secondBitmap, isSideBySide)

            if (downsample) {
                // Digital "film saving": Downsample so the final area is approx equal to a single frame.
                // Combined area is ~2x. Scale factor = sqrt(0.5) ~ 0.707
                val scale = 0.707f
                val scaledW = (resultBitmap.width * scale).toInt()
                val scaledH = (resultBitmap.height * scale).toInt()
                val scaled = Bitmap.createScaledBitmap(resultBitmap, scaledW, scaledH, true)
                if (scaled != resultBitmap) {
                    resultBitmap.recycle()
                }
                return scaled
            }

            return resultBitmap
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OOM during stitching", e)
            return null
        } catch (e: Exception) {
            Log.e(TAG, "Error stitching images", e)
            return null
        } finally {
            firstRaw.recycle()
            secondRaw.recycle()
            if (firstBitmap != firstRaw) firstBitmap.recycle()
            if (secondBitmap != secondRaw) secondBitmap.recycle()
        }
    }

    private fun getFitRect(srcW: Int, srcH: Int, dstW: Int, dstH: Int): Rect {
        val srcAspect = srcW.toFloat() / srcH
        val dstAspect = dstW.toFloat() / dstH

        return if (srcAspect > dstAspect) {
            // Src is wider than Dst
            val h = (dstW / srcAspect).toInt()
            val top = (dstH - h) / 2
            Rect(0, top, dstW, top + h)
        } else {
            // Src is taller than Dst
            val w = (dstH * srcAspect).toInt()
            val left = (dstW - w) / 2
            Rect(left, 0, left + w, dstH)
        }
    }

    fun addEffects(
        bitmap: Bitmap,
        dateStamp: Boolean,
        lightLeak: Boolean,
        layout: String,
        time1: Long = System.currentTimeMillis(),
        time2: Long = System.currentTimeMillis(),
        flareType: Int = 0 // 0: random, 1: vertical, 2: corner, -1: none
    ): Bitmap {
        var result = bitmap

        if (dateStamp) {
            result = addDateStampToBoth(result, layout, time1, time2)
        }

        if (lightLeak || flareType >= 0) {
            result = addLightLeak(result, flareType)
        }

        return result
    }

    private fun addDateStampToBoth(bitmap: Bitmap, layout: String, time1: Long, time2: Long): Bitmap {
        val workingBitmap = if (bitmap.isMutable) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(workingBitmap)

        val sdf = SimpleDateFormat(" ' 'yy  M  d", Locale.US)
        val dateText1 = sdf.format(Date(time1))
        val dateText2 = sdf.format(Date(time2))

        // More robust detection: if string matches Side-by-side OR if it's a wide image that isn't square
        val isSideBySide = layout == "Side-by-side" ||
                          layout == "左右排列" ||
                          layout.contains("side", ignoreCase = true) ||
                          layout == "SBS" ||
                          (layout.isEmpty() && bitmap.width > bitmap.height)

        val isTopBottom = layout == "Top-bottom" ||
                          layout == "上下排列" ||
                          layout.contains("top", ignoreCase = true) ||
                          layout == "TB"

        val paint = Paint().apply {
            color = Color.parseColor("#FF8C00") // Classic orange
            alpha = 220
            // For Side-by-side, we use height as reference; for Top-bottom, we use width.
            // Basically use the "single frame" dimension.
            textSize = (if (isSideBySide) bitmap.height else bitmap.width) * 0.04f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setShadowLayer(5f, 0f, 0f, Color.RED)
            textAlign = Paint.Align.RIGHT
        }

        val textWidth = paint.measureText(dateText2)
        val margin = textWidth * 0.2f

        if (isSideBySide && !isTopBottom) {
            // Side-by-side (Wide): [ Frame 1 (Portrait) | Divider | Frame 2 (Portrait) ]
            // Actually, we can just use the center gap.
            val centerGapX = bitmap.width / 2f
            val dividerHalf = (bitmap.height * 0.03f).coerceAtLeast(16f) / 2f

            val x1 = centerGapX - dividerHalf - margin
            val x2 = bitmap.width - margin
            val y = bitmap.height - margin

            canvas.drawText(dateText1, x1, y, paint)
            canvas.drawText(dateText2, x2, y, paint)
        } else {
            // Top-bottom (Tall): [ Frame 1 (Landscape) / Divider / Frame 2 (Landscape) ]
            val centerGapY = bitmap.height / 2f
            val dividerHalf = (bitmap.width * 0.03f).coerceAtLeast(16f) / 2f

            val x = bitmap.width - margin
            val y1 = centerGapY - dividerHalf - margin
            val y2 = bitmap.height - margin

            canvas.drawText(dateText1, x, y1, paint)
            canvas.drawText(dateText2, x, y2, paint)
        }

        return workingBitmap
    }

    private fun addLightLeak(bitmap: Bitmap, flareType: Int = 0): Bitmap {
        if (flareType == -1) return bitmap
        val workingBitmap = if (bitmap.isMutable) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(workingBitmap)

        val leakType = if (flareType == 0) Random().nextInt(2) else flareType - 1

        val paint = Paint().apply {
            style = Paint.Style.FILL
        }

        when (leakType) {
            0 -> { // Vertical side leak
                val colors = intArrayOf(Color.argb(150, 255, 100, 0), Color.argb(100, 255, 50, 0), Color.TRANSPARENT)
                val gradient = LinearGradient(0f, 0f, workingBitmap.width * 0.2f, 0f, colors, null, Shader.TileMode.CLAMP)
                paint.shader = gradient
                canvas.drawRect(0f, 0f, workingBitmap.width * 0.2f, workingBitmap.height.toFloat(), paint)
            }
            1 -> { // Bottom corner glow
                val colors = intArrayOf(Color.argb(180, 255, 150, 0), Color.TRANSPARENT)
                val gradient = RadialGradient(workingBitmap.width.toFloat(), workingBitmap.height.toFloat(),
                    workingBitmap.width * 0.6f, colors, null, Shader.TileMode.CLAMP)
                paint.shader = gradient
                canvas.drawCircle(workingBitmap.width.toFloat(), workingBitmap.height.toFloat(), workingBitmap.width * 0.6f, paint)
            }
        }

        return workingBitmap
    }
}
