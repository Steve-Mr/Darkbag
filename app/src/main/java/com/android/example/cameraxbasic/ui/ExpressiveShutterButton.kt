package com.android.example.cameraxbasic.ui

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.PathShape
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.LinearInterpolator
import androidx.appcompat.widget.AppCompatImageButton
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.star
import androidx.graphics.shapes.toPath
import com.google.android.material.color.MaterialColors
import kotlin.math.cos
import kotlin.math.sin

class ExpressiveShutterButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageButton(context, attrs, defStyleAttr) {

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
    private var clickRotation = 0f

    private var colorPrimary: Int = 0
    private var colorOnPrimary: Int = 0
    private var colorPrimaryContainer: Int = 0
    private var colorOnPrimaryContainer: Int = 0
    private var colorSecondaryContainer: Int = 0

    private val rotationAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 3000L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            continuousRotation = it.animatedValue as Float
            invalidate()
        }
    }

    private val clickSpinAnimator = ValueAnimator.ofFloat(0f, 45f).apply {
        duration = 250L
        interpolator = android.view.animation.DecelerateInterpolator()
        addUpdateListener {
            clickRotation = it.animatedValue as Float
            invalidate()
        }
    }

    init {
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
        colorSecondaryContainer = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSecondaryContainer)

        updatePaintColors()
    }

    private fun updatePaintColors() {
        // Use container variant when disabled/processing for better visual continuity
        val baseColor = if (!isEnabled) colorPrimaryContainer else colorPrimary
        shapePaint.color = baseColor

        // Dot should be visible against the shape
        dotPaint.color = if (!isEnabled) colorOnPrimaryContainer else colorOnPrimary
        dotPaint.alpha = if (!isEnabled) 160 else 255

        progressPaint.color = colorPrimary
        progressTrackPaint.color = colorPrimary
        progressTrackPaint.alpha = 40
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
        // Reduced padding slightly as progress ring is thinner now
        val ringWidth = size * 0.04f
        val padding = ringWidth + (size * 0.06f)
        val radius = (size / 2f) - padding

        val polygon = RoundedPolygon.star(
            numVerticesPerRadius = 8,
            innerRadius = 0.8f * radius,
            rounding = CornerRounding(radius = radius * 0.15f, smoothing = 0.9f),
            radius = radius,
            centerX = width / 2f,
            centerY = height / 2f,
            innerRounding = CornerRounding(radius = radius * 0.15f, smoothing = 0.9f)
        )

        shapePath = polygon.toPath()

        // Create Ripple with Star Mask
        val rippleColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorControlHighlight)
        val maskPath = Path(shapePath)
        val maskDrawable = ShapeDrawable(PathShape(maskPath, width.toFloat(), height.toFloat()))

        // Use foreground for ripple to be on top of the custom drawn star
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            foreground = RippleDrawable(ColorStateList.valueOf(rippleColor), null, maskDrawable)
        } else {
            background = RippleDrawable(ColorStateList.valueOf(rippleColor), null, maskDrawable)
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
        if (!isEnabled) return super.onTouchEvent(event)

        when (event.action) {
            android.view.MotionEvent.ACTION_DOWN -> {
                if (!isPointInsidePath(event.x, event.y)) {
                    return false
                }
                animate().scaleX(0.95f).scaleY(0.95f).setDuration(100).start()
            }
            android.view.MotionEvent.ACTION_UP -> {
                animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).withEndAction {
                    if (!isRotating) {
                        clickSpinAnimator.cancel()
                        clickSpinAnimator.start()
                    }
                }.start()
            }
            android.view.MotionEvent.ACTION_CANCEL -> {
                animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()
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
            clickRotation = 0f
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val size = minOf(width, height).toFloat()

        if (size <= 0) return

        // Progress ring thinner and radius smaller as requested
        val ringWidth = size * 0.04f
        val progressRadius = (size / 2f) - (ringWidth * 1.5f)

        // 1. Draw Progress Ring
        progressPaint.strokeWidth = ringWidth
        progressTrackPaint.strokeWidth = ringWidth

        if (progress > 0 || isRotating) {
             canvas.drawCircle(cx, cy, progressRadius, progressTrackPaint)
             val rectF = RectF(cx - progressRadius, cy - progressRadius, cx + progressRadius, cy + progressRadius)
             canvas.drawArc(rectF, -90f, progress * 360f, false, progressPaint)
        }

        // 2. Draw the star shape
        canvas.save()
        val totalRotation = if (isRotating) continuousRotation else clickRotation
        if (totalRotation != 0f) {
            canvas.rotate(totalRotation, cx, cy)
        }
        canvas.drawPath(shapePath, shapePaint)
        canvas.restore()

        // 3. Draw Orientation Dot
        val ringWidthTotal = size * 0.04f
        val padding = ringWidthTotal + (size * 0.06f)
        val radius = (size / 2f) - padding
        // Dot distance synced with innerRadius (0.8f * radius)
        val dotDistance = (0.8f * radius) * 0.75f
        val dotRadius = radius * 0.1f

        val angleRad = Math.toRadians((dotRotation - 90).toDouble())
        val dx = cx + (dotDistance * cos(angleRad)).toFloat()
        val dy = cy + (dotDistance * sin(angleRad)).toFloat()

        canvas.drawCircle(dx, dy, dotRadius, dotPaint)

        // 4. Draw the icon (ImageButton's src)
        super.onDraw(canvas)
    }
}
