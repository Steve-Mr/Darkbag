package top.maary.darkbag.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.hypot
import kotlin.math.max

/**
 * Interactive 60fps hardware-accelerated preview for lens distortion calibration.
 * Uses Android Canvas drawBitmapMesh to perform real-time polynomial unwarping
 * with an overlay reference grid for sub-millimeter visual alignment.
 */
class LensCalibrationPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var sourceBitmap: Bitmap? = null
    private var defaultPatternBitmap: Bitmap? = null

    var k1: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    var k2: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    var showGrid: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    var isBypassed: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#80FF5252") // Translucent coral red reference grid
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * resources.displayMetrics.density
    }
    private val subGridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#40FFFFFF") // Faint white sub-grid lines
        style = Paint.Style.STROKE
        strokeWidth = 1f * resources.displayMetrics.density
        pathEffect = DashPathEffect(floatArrayOf(8f, 8f), 0f)
    }
    private val centerCrossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CCFFD700") // Gold center crosshair
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
    }

    private val dstRect = RectF()
    private val meshW = 28
    private val meshH = 28
    private val meshVerts = FloatArray((meshW + 1) * (meshH + 1) * 2)

    init {
        setBackgroundColor(Color.parseColor("#121212"))
    }

    fun setBitmap(bitmap: Bitmap?) {
        sourceBitmap = bitmap
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (defaultPatternBitmap == null && w > 0 && h > 0) {
            defaultPatternBitmap = createDefaultCalibrationPattern(800, 600)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val bmp = sourceBitmap ?: defaultPatternBitmap ?: return
        val viewW = width.toFloat()
        val viewH = height.toFloat()
        if (viewW <= 0f || viewH <= 0f) return

        // Aspect fit inside preview area
        val bmpW = bmp.width.toFloat()
        val bmpH = bmp.height.toFloat()
        val scale = (viewW / bmpW).coerceAtMost(viewH / bmpH) * 0.94f // 6% padding for barrel expansion
        val targetW = bmpW * scale
        val targetH = bmpH * scale
        val left = (viewW - targetW) * 0.5f
        val top = (viewH - targetH) * 0.5f
        dstRect.set(left, top, left + targetW, top + targetH)

        val activeK1 = if (isBypassed) 0f else k1
        val activeK2 = if (isBypassed) 0f else k2

        if (kotlin.math.abs(activeK1) > 0.0001f || kotlin.math.abs(activeK2) > 0.0001f) {
            // Draw warped mesh
            val f = max(bmpW, bmpH)
            val cxBmp = bmpW * 0.5f
            val cyBmp = bmpH * 0.5f
            val cxScreen = dstRect.centerX()
            val cyScreen = dstRect.centerY()

            var idx = 0
            for (j in 0..meshH) {
                val v = j.toFloat() / meshH
                val bmpY = v * bmpH
                val dy = bmpY - cyBmp
                val screenYBase = dstRect.top + v * targetH

                for (i in 0..meshW) {
                    val u = i.toFloat() / meshW
                    val bmpX = u * bmpW
                    val dx = bmpX - cxBmp
                    val screenXBase = dstRect.left + u * targetW

                    val rd = hypot(dx, dy) / f
                    val rd2 = rd * rd
                    val rd4 = rd2 * rd2
                    // Forward coordinate displacement from distorted sensor to unwarped display:
                    // When k1 < 0 (barrel distortion), corners are pushed outward to straighten straight lines
                    val factor = 1.0f - activeK1 * rd2 - activeK2 * rd4

                    val displacedScreenX = cxScreen + (screenXBase - cxScreen) * factor
                    val displacedScreenY = cyScreen + (screenYBase - cyScreen) * factor

                    meshVerts[idx++] = displacedScreenX
                    meshVerts[idx++] = displacedScreenY
                }
            }

            canvas.drawBitmapMesh(bmp, meshW, meshH, meshVerts, 0, null, 0, bitmapPaint)
        } else {
            canvas.drawBitmap(bmp, null, dstRect, bitmapPaint)
        }

        // Draw reference grid on top
        if (showGrid) {
            drawReferenceGrid(canvas, dstRect)
        }
    }

    private fun drawReferenceGrid(canvas: Canvas, bounds: RectF) {
        val cols = 8
        val rows = 6

        // Sub-grid lines (dashed)
        for (i in 1 until cols * 2) {
            val x = bounds.left + bounds.width() * (i.toFloat() / (cols * 2))
            canvas.drawLine(x, bounds.top, x, bounds.bottom, subGridPaint)
        }
        for (j in 1 until rows * 2) {
            val y = bounds.top + bounds.height() * (j.toFloat() / (rows * 2))
            canvas.drawLine(bounds.left, y, bounds.right, y, subGridPaint)
        }

        // Primary solid reference lines
        for (i in 1 until cols) {
            val x = bounds.left + bounds.width() * (i.toFloat() / cols)
            canvas.drawLine(x, bounds.top, x, bounds.bottom, gridPaint)
        }
        for (j in 1 until rows) {
            val y = bounds.top + bounds.height() * (j.toFloat() / rows)
            canvas.drawLine(bounds.left, y, bounds.right, y, gridPaint)
        }

        // Center crosshair
        val cx = bounds.centerX()
        val cy = bounds.centerY()
        val crosshairArm = 16f * resources.displayMetrics.density
        canvas.drawLine(cx - crosshairArm, cy, cx + crosshairArm, cy, centerCrossPaint)
        canvas.drawLine(cx, cy - crosshairArm, cx, cy + crosshairArm, centerCrossPaint)

        // Outer border
        canvas.drawRect(bounds, gridPaint)
    }

    private fun createDefaultCalibrationPattern(w: Int, h: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.parseColor("#1E1E1E"))

        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#444444")
            strokeWidth = 2f
        }
        val brickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#2A2A2A")
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
        }

        // Draw simulated architectural perspective grid lines
        val brickH = 40f
        val brickW = 80f
        var y = 0f
        var row = 0
        while (y < h) {
            val offset = if (row % 2 == 0) 0f else brickW * 0.5f
            var x = -offset
            while (x < w) {
                canvas.drawRect(x, y, x + brickW, y + brickH, brickPaint)
                x += brickW
            }
            y += brickH
            row++
        }

        // Outer border frame
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), linePaint)
        return bitmap
    }
}
