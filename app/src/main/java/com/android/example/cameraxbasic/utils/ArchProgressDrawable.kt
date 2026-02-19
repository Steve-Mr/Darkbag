package com.android.example.cameraxbasic.utils

import android.graphics.*
import android.graphics.drawable.Drawable
import androidx.core.graphics.PathParser

class ArchProgressDrawable : Drawable() {
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

    // Rounded Arch path starting at 12 o'clock (50,5)
    private val pathString = "M 50,5 A 45,45 0 0 1 95,50 L 95,80 Q 95,95 80,95 L 20,95 Q 5,95 5,80 L 5,50 A 45,45 0 0 1 50,5 Z"

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
