package com.android.example.cameraxbasic.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.LinearInterpolator
import androidx.appcompat.widget.AppCompatImageButton
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.toPath
import com.google.android.material.color.MaterialColors
import kotlin.math.cos
import kotlin.math.sin

class ExpressiveShutterButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageButton(context, attrs, defStyleAttr) {

    companion object {
        private const val COOKIE_SIDES = 9
        // Tuned to match Material 3 Expressive "9-sided cookie" contour.
        private const val COOKIE_CORNER_ROUNDING = 0.22f
        private const val COOKIE_CORNER_SMOOTHING = 0.74f
    }

    private val shapePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val progressTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    private var shapePath = Path()
    private var progress = 0f
    private var dotRotation = 0f
    private var isRotating = false
    private var continuousRotation = 0f

    private var colorPrimary: Int = 0
    private var colorOnPrimary: Int = 0
    private var colorPrimaryContainer: Int = 0
    private var colorOnPrimaryContainer: Int = 0
    private var colorDisabled: Int = 0

    private val rotationAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 3000L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            continuousRotation = it.animatedValue as Float
            invalidate()
        }
    }

    init {
        // Remove default background to avoid square corners and default effects
        background = null
        setupColors()

        // Default elevation for depth
        elevation = 4f * resources.displayMetrics.density
    }

    private fun setupColors() {
        colorPrimary = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)
        colorOnPrimary = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnPrimary)
        colorPrimaryContainer = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimaryContainer)
        colorOnPrimaryContainer = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnPrimaryContainer)
        colorDisabled = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceContainerHighest)

        updatePaintColors()
    }

    private fun updatePaintColors() {
        val baseColor = if (!isEnabled) colorDisabled else colorPrimary
        shapePaint.color = baseColor

        // Dot should be visible against the shape
        dotPaint.color = if (!isEnabled) colorOnPrimaryContainer else colorOnPrimary
        dotPaint.alpha = if (!isEnabled) 100 else 255

        progressPaint.color = colorPrimary
        progressTrackPaint.color = colorPrimary
        progressTrackPaint.alpha = 50
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        updatePaintColors()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateShapePath()
    }

    private fun updateShapePath() {
        if (width <= 0 || height <= 0) return

        val size = minOf(width, height).toFloat()
        // Shutter button slightly smaller for the outer progress ring
        val ringWidth = size * 0.08f
        val padding = ringWidth + (size * 0.06f)
        val radius = (size / 2f) - padding

        val polygon = RoundedPolygon(
            numVertices = COOKIE_SIDES,
            radius = 1f,
            centerX = 0f,
            centerY = 0f,
            rounding = CornerRounding(
                radius = COOKIE_CORNER_ROUNDING,
                smoothing = COOKIE_CORNER_SMOOTHING
            )
        )

        shapePath = polygon.toPath().apply {
            transform(
                Matrix().apply {
                    setScale(radius, radius)
                    postRotate(-90f)
                    postTranslate(width / 2f, height / 2f)
                }
            )
        }

        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    outline.setPath(shapePath)
                } else {
                    try {
                        // Older versions might only support convex paths for shadows
                        outline.setConvexPath(shapePath)
                    } catch (e: Exception) {
                        val inset = padding.toInt()
                        outline.setOval(inset, inset, width - inset, height - inset)
                    }
                }
            }
        }
        clipToOutline = false // Don't clip so we can see the progress ring
    }

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        if (event.action == android.view.MotionEvent.ACTION_DOWN) {
            if (!isPointInsidePath(event.x, event.y)) {
                return false
            }
        }
        return super.onTouchEvent(event)
    }

    private fun isPointInsidePath(x: Float, y: Float): Boolean {
        if (shapePath.isEmpty) return false
        val rectF = RectF()
        shapePath.computeBounds(rectF, true)
        val region = Region()
        region.setPath(shapePath, Region(rectF.left.toInt(), rectF.top.toInt(), rectF.right.toInt(), rectF.bottom.toInt()))
        return region.contains(x.toInt(), y.toInt())
    }

    fun setProgress(value: Float) {
        progress = value.coerceIn(0f, 1f)
        invalidate()
    }

    fun setDotRotation(degrees: Float) {
        dotRotation = degrees
        invalidate()
    }

    fun getDotRotation(): Float = dotRotation

    fun startRotation() {
        if (!isRotating) {
            isRotating = true
            rotationAnimator.start()
        }
    }

    fun stopRotation() {
        if (isRotating) {
            isRotating = false
            rotationAnimator.cancel()
            continuousRotation = 0f
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val size = minOf(width, height).toFloat()

        if (size <= 0) return

        val ringWidth = size * 0.06f
        val progressRadius = (size / 2f) - (ringWidth / 2f)

        // 1. Draw Progress Ring
        progressPaint.strokeWidth = ringWidth
        progressTrackPaint.strokeWidth = ringWidth

        if (progress > 0 || isRotating) {
             canvas.drawCircle(cx, cy, progressRadius, progressTrackPaint)
             val rectF = RectF(cx - progressRadius, cy - progressRadius, cx + progressRadius, cy + progressRadius)
             canvas.drawArc(rectF, -90f, progress * 360f, false, progressPaint)
        }

        // 2. Draw the 9-sided shape
        canvas.save()
        if (isRotating) {
            canvas.rotate(continuousRotation, cx, cy)
        }
        canvas.drawPath(shapePath, shapePaint)
        canvas.restore()

        // 3. Draw Orientation Dot
        val ringWidthTotal = size * 0.08f
        val padding = ringWidthTotal + (size * 0.04f)
        val shapeRadius = (size / 2f) - padding
        val dotDistance = shapeRadius * 0.7f
        val dotRadius = shapeRadius * 0.12f

        val angleRad = Math.toRadians((dotRotation - 90).toDouble())
        val dx = cx + (dotDistance * cos(angleRad)).toFloat()
        val dy = cy + (dotDistance * sin(angleRad)).toFloat()

        canvas.drawCircle(dx, dy, dotRadius, dotPaint)

        // 4. Draw the icon (ImageButton's src)
        super.onDraw(canvas)
    }
}
