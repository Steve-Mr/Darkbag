package com.android.example.cameraxbasic.utils

import android.graphics.*
import android.graphics.drawable.Drawable
import androidx.core.graphics.PathParser

class SeptagonProgressDrawable : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 12f
        strokeCap = Paint.Cap.ROUND
    }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 12f
        alpha = 50
    }

    private var progress = 0f
    private val path = Path()
    private val measure = PathMeasure()
    private val segmentPath = Path()

    // Same path as ic_shutter_cookie.xml but scaled to bounds
    private val pathString = "M50,5 C60,5 80,15 85.2,21.9 C90,30 95,50 93.9,60.0 C93,75 75,85 69.5,90.5 C60,95 40,95 30.5,90.5 C25,85 7,75 6.1,60.0 C5,50 10,30 14.8,21.9 C20,15 40,5 50,5 Z"

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        val matrix = Matrix()
        val rawPath = PathParser.createPathFromPathData(pathString)
        val rectF = RectF()
        rawPath.computeBounds(rectF, true)
        matrix.setRectToRect(rectF, RectF(bounds).apply { inset(10f, 10f) }, Matrix.ScaleToFit.CENTER)
        path.set(rawPath)
        path.transform(matrix)
        measure.setPath(path, true)
    }

    fun setProgress(value: Float) {
        progress = value.coerceIn(0f, 1f)
        invalidateSelf()
    }

    fun setColor(color: Int) {
        paint.color = color
        trackPaint.color = color
        trackPaint.alpha = 50
    }

    override fun draw(canvas: Canvas) {
        canvas.drawPath(path, trackPaint)

        segmentPath.reset()
        val length = measure.length
        measure.getSegment(0f, length * progress, segmentPath, true)
        canvas.drawPath(segmentPath, paint)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
