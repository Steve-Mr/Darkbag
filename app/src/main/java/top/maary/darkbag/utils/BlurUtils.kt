package top.maary.darkbag.utils

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint

object BlurUtils {
    /**
     * A simple but effective blur achieved by downsampling and upsampling with bilinear filtering.
     * This provides a "soft" look suitable for placeholders without external dependencies.
     */
    fun blur(bitmap: Bitmap, radius: Int = 8): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        // 1. Create a small bitmap for downsampling
        val sampleSize = radius.coerceAtLeast(1)
        val smallBitmap = Bitmap.createScaledBitmap(
            bitmap,
            (width / sampleSize).coerceAtLeast(1),
            (height / sampleSize).coerceAtLeast(1),
            true
        )

        // 2. Create a result bitmap and draw the small one back, scaled up
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        canvas.drawBitmap(smallBitmap, null, android.graphics.Rect(0, 0, width, height), paint)

        smallBitmap.recycle()
        return result
    }
}
