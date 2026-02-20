package com.android.example.cameraxbasic.utils

import android.content.Context
import android.graphics.*
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

object HalfFrameUtils {
    private const val TAG = "HalfFrameUtils"

    /**
     * Stitches two images according to the layout.
     * Assumes images are already correctly oriented.
     */
    fun stitchImages(
        firstPath: String,
        secondPath: String,
        layout: String,
        downsample: Boolean
    ): Bitmap? {
        val firstBitmap = BitmapFactory.decodeFile(firstPath) ?: return null
        val secondBitmap = BitmapFactory.decodeFile(secondPath) ?: return null

        try {
            val w1 = firstBitmap.width
            val h1 = firstBitmap.height
            val w2 = secondBitmap.width
            val h2 = secondBitmap.height

            // We use the first image as the reference size
            val targetW = w1
            val targetH = h1

            // Squeezing logic to maintain the final image size similar to a single frame
            // and ensure the final image is always vertical (Portrait).
            // For Half-frame, we squeeze each frame to 50% of the canvas.

            // Canvas size will be targetW x targetH (Portrait)
            val canvasW = if (targetW > targetH) targetH else targetW
            val canvasH = if (targetW > targetH) targetW else targetH

            val divider = (maxOf(canvasW, canvasH) * 0.01f).toInt().coerceAtLeast(4)

            val finalBitmap = Bitmap.createBitmap(canvasW, canvasH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(finalBitmap)
            canvas.drawColor(Color.BLACK)

            val paint = Paint(Paint.FILTER_BITMAP_FLAG)

            if (layout == "Side-by-side" || layout == "左右排列") {
                // Each frame squeezed to (canvasW - divider)/2 width, canvasH height
                val frameW = (canvasW - divider) / 2
                val frameH = canvasH

                // Draw first frame (Left)
                val src1 = Rect(0, 0, w1, h1)
                val dst1 = Rect(0, 0, frameW, frameH)
                canvas.drawBitmap(firstBitmap, src1, dst1, paint)

                // Draw second frame (Right)
                val src2 = Rect(0, 0, w2, h2)
                val dst2 = Rect(frameW + divider, 0, canvasW, frameH)
                canvas.drawBitmap(secondBitmap, src2, dst2, paint)
            } else {
                // Each frame squeezed to canvasW width, (canvasH - divider)/2 height
                val frameW = canvasW
                val frameH = (canvasH - divider) / 2

                // Draw first frame (Top)
                val src1 = Rect(0, 0, w1, h1)
                val dst1 = Rect(0, 0, frameW, frameH)
                canvas.drawBitmap(firstBitmap, src1, dst1, paint)

                // Draw second frame (Bottom)
                val src2 = Rect(0, 0, w2, h2)
                val dst2 = Rect(0, frameH + divider, frameW, canvasH)
                canvas.drawBitmap(secondBitmap, src2, dst2, paint)
            }

            if (downsample) {
                // Economical mode: further downsample the already squeezed image
                val scale = 0.707f
                val scaledW = (finalBitmap.width * scale).toInt()
                val scaledH = (finalBitmap.height * scale).toInt()
                val scaled = Bitmap.createScaledBitmap(finalBitmap, scaledW, scaledH, true)
                if (scaled != finalBitmap) {
                    finalBitmap.recycle()
                }
                return scaled
            }

            return finalBitmap
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OOM during stitching", e)
            return null
        } catch (e: Exception) {
            Log.e(TAG, "Error stitching images", e)
            return null
        } finally {
            firstBitmap.recycle()
            secondBitmap.recycle()
        }
    }

    fun addEffects(bitmap: Bitmap, dateStamp: Boolean, lightLeak: Boolean): Bitmap {
        var result = bitmap

        if (dateStamp) {
            result = addDateStamp(result)
        }

        if (lightLeak) {
            result = addLightLeak(result)
        }

        return result
    }

    private fun addDateStamp(bitmap: Bitmap): Bitmap {
        val workingBitmap = if (bitmap.isMutable) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(workingBitmap)

        val sdf = SimpleDateFormat(" ' 'yy  M  d", Locale.US)
        val dateText = sdf.format(Date())

        val paint = Paint().apply {
            color = Color.parseColor("#FF8C00") // Classic orange
            alpha = 200
            textSize = bitmap.height * 0.03f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setShadowLayer(5f, 0f, 0f, Color.RED)
        }

        val x = workingBitmap.width * 0.85f
        val y = workingBitmap.height * 0.95f

        canvas.drawText(dateText, x, y, paint)
        return workingBitmap
    }

    private fun addLightLeak(bitmap: Bitmap): Bitmap {
        val workingBitmap = if (bitmap.isMutable) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(workingBitmap)

        val random = Random()
        val leakType = random.nextInt(3)

        val paint = Paint().apply {
            style = Paint.Style.FILL
        }

        when (leakType) {
            0 -> { // Side leak
                val colors = intArrayOf(Color.TRANSPARENT, Color.argb(100, 255, 50, 0), Color.argb(150, 255, 100, 0))
                val positions = floatArrayOf(0f, 0.8f, 1f)
                val gradient = LinearGradient(0f, 0f, workingBitmap.width * 0.3f, 0f, colors, positions, Shader.TileMode.CLAMP)
                paint.shader = gradient
                canvas.drawRect(0f, 0f, workingBitmap.width * 0.3f, workingBitmap.height.toFloat(), paint)
            }
            1 -> { // Corner leak
                val colors = intArrayOf(Color.argb(180, 255, 150, 0), Color.TRANSPARENT)
                val gradient = RadialGradient(workingBitmap.width.toFloat(), workingBitmap.height.toFloat(),
                    workingBitmap.width * 0.5f, colors, null, Shader.TileMode.CLAMP)
                paint.shader = gradient
                canvas.drawCircle(workingBitmap.width.toFloat(), workingBitmap.height.toFloat(), workingBitmap.width * 0.5f, paint)
            }
        }

        return workingBitmap
    }
}
