package top.maary.darkbag.ui

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import androidx.appcompat.widget.AppCompatImageButton
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors

/**
 * Material 3 Expressive Bento "Tofu Block" (豆腐块) Shutter Button.
 *
 * Features:
 * - Bento Tofu Block rounded rectangle shape with rest radius 20dp morphing to 12dp on press.
 * - Dynamic M3 tonal container fill and subtle primary border stroke.
 * - Perimeter orientation pill indicator smoothly interpolating along the Tofu Block perimeter.
 * - Internal progress fill (determinate) and sweeping highlight wave (indeterminate burst/capture).
 * - Dual mini-shutter split mode for Half-Frame capture (Frame 1 Left vs Frame 2 Right)
 *   with animated 54%/46% width allocation and locked checkmark indicator.
 * - Warm coral/red overlay and indicator during video recording.
 */
class ExpressiveShutterButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageButton(context, attrs, defStyleAttr) {

    // Paint allocations
    private val blockFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val blockStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val progressFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val standbyDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val checkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val recordingOverlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val recordingDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    // Geometry caches
    private val boundsRect = RectF()
    private val leftMiniRect = RectF()
    private val rightMiniRect = RectF()
    private val activeBlockPath = Path()
    private val standbyBlockPath = Path()
    private val lockedBlockPath = Path()
    private val pillRect = RectF()
    private val checkPath = Path()

    // Dynamic Colors
    private var colorPrimary: Int = 0
    private var colorOnPrimary: Int = 0
    private var colorPrimaryContainer: Int = 0
    private var colorOnPrimaryContainer: Int = 0
    private var colorSecondaryContainer: Int = 0
    private var colorOnSecondaryContainer: Int = 0
    private var colorSurfaceContainerHighest: Int = 0
    private var colorOutlineVariant: Int = 0
    private var colorOnSurfaceVariant: Int = 0
    private val recordingDotColor = Color.parseColor("#E53935") // Warm recording red/coral

    // State
    private var progress = 0f
    private var dotRotation = 0f
    private var isRotating = false
    private var continuousRotation = 0f
    private var clickRotation = 0f

    // Corner Morphing: 20dp rest -> 12dp press
    private var currentCornerRadiusDp = 20f
    private var cornerMorphAnimator: ValueAnimator? = null

    // Half-Frame split state
    private var isHalfFrame = false
    private var halfFrameStep = 0 // 0 = Frame 1 (Left active), 1 = Frame 2 (Right active)
    private var splitRatio = 0.54f // 0.54f for left active, 0.46f for right active
    private var splitRatioAnimator: ValueAnimator? = null

    // Long press and recording
    private var isHoldingLongPress = false
    var isLongPressHoldEnabled: Boolean = false
    var onLongPressHoldStarted: (() -> Unit)? = null
    var onLongPressHoldReleased: (() -> Unit)? = null
    private var isRecordingVideo = false

    private val longPressRunnable = Runnable {
        if (!isEnabled || !isLongPressHoldEnabled) return@Runnable
        isHoldingLongPress = true
        isRecordingVideo = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        invalidate()
        onLongPressHoldStarted?.invoke()
    }

    // Continuous rotation for sweeping highlight
    private val rotationAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 1500L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            continuousRotation = it.animatedValue as Float
            invalidate()
        }
    }

    // Click spin animator for tactile feedback
    private val clickSpinAnimator = ValueAnimator.ofFloat(0f, 45f).apply {
        duration = 250L
        interpolator = DecelerateInterpolator()
        addUpdateListener {
            clickRotation = it.animatedValue as Float
            invalidate()
        }
    }

    init {
        background = null
        setupColors()
        elevation = 0f
        stateListAnimator = null
    }

    private fun setupColors() {
        colorPrimary = MaterialColors.getColor(this, android.R.attr.colorPrimary)
        colorOnPrimary = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnPrimary)
        colorPrimaryContainer = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimaryContainer)
        colorOnPrimaryContainer = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnPrimaryContainer)
        colorSecondaryContainer = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSecondaryContainer)
        colorOnSecondaryContainer = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSecondaryContainer)
        colorSurfaceContainerHighest = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceContainerHighest)
        colorOutlineVariant = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutlineVariant)
        colorOnSurfaceVariant = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant)
    }

    override fun setEnabled(enabled: Boolean) {
        if (isEnabled == enabled) return
        super.setEnabled(enabled)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateOutlineAndRipple()
    }

    private fun updateOutlineAndRipple() {
        if (width <= 0 || height <= 0) return
        val dp = resources.displayMetrics.density
        val inset = 2f * dp

        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val r = currentCornerRadiusDp * dp
                outline.setRoundRect(
                    inset.toInt(),
                    inset.toInt(),
                    (width - inset).toInt(),
                    (height - inset).toInt(),
                    r
                )
            }
        }
        clipToOutline = false

        val rippleColor = MaterialColors.getColor(this, android.R.attr.colorControlHighlight)
        val maskDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 20f * dp
            setColor(Color.WHITE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            foreground = RippleDrawable(ColorStateList.valueOf(rippleColor), null, maskDrawable)
        } else {
            background = RippleDrawable(ColorStateList.valueOf(rippleColor), null, maskDrawable)
        }
    }

    private fun animateCornerRadius(targetDp: Float, durationMs: Long) {
        cornerMorphAnimator?.cancel()
        cornerMorphAnimator = ValueAnimator.ofFloat(currentCornerRadiusDp, targetDp).apply {
            duration = durationMs
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                currentCornerRadiusDp = it.animatedValue as Float
                invalidateOutline()
                invalidate()
            }
            start()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return super.onTouchEvent(event)

        val dp = resources.displayMetrics.density
        val inset = 2f * dp
        val hitBounds = RectF(inset, inset, width - inset, height - inset)

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                if (!hitBounds.contains(event.x, event.y)) {
                    return false
                }
                isHoldingLongPress = false
                if (isLongPressHoldEnabled) {
                    removeCallbacks(longPressRunnable)
                    postDelayed(longPressRunnable, 350L)
                }
                animateCornerRadius(12f, 120L)
                animate().scaleX(0.96f).scaleY(0.96f).setDuration(120).start()
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPressRunnable)
                animateCornerRadius(20f, 180L)
                animate().scaleX(1.0f).scaleY(1.0f).setDuration(160).withEndAction {
                    if (!isRotating) {
                        clickSpinAnimator.cancel()
                        clickSpinAnimator.start()
                    }
                }.start()

                if (isHoldingLongPress) {
                    isHoldingLongPress = false
                    isRecordingVideo = false
                    invalidate()
                    onLongPressHoldReleased?.invoke()
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                animateCornerRadius(20f, 180L)
                animate().scaleX(1.0f).scaleY(1.0f).setDuration(160).start()
                if (isHoldingLongPress) {
                    isHoldingLongPress = false
                    isRecordingVideo = false
                    invalidate()
                    onLongPressHoldReleased?.invoke()
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }

    // Public API compatibility
    fun setRecordingState(recording: Boolean) {
        isRecordingVideo = recording
        invalidate()
    }

    fun setProgress(value: Float) {
        val clamped = value.coerceIn(0f, 1f)
        if (progress != clamped) {
            progress = clamped
            invalidate()
        }
    }

    fun setDotRotation(degrees: Float) {
        if (dotRotation != degrees) {
            dotRotation = degrees
            invalidate()
        }
    }

    fun getDotRotation(): Float = dotRotation

    fun cancelClickSpin() {
        clickSpinAnimator.cancel()
        clickRotation = 0f
        invalidate()
    }

    fun startRotation() {
        clickSpinAnimator.cancel()
        clickRotation = 0f
        if (!isRotating) {
            isRotating = true
            rotationAnimator.start()
        }
    }

    fun stopRotation() {
        clickSpinAnimator.cancel()
        clickRotation = 0f
        if (isRotating) {
            isRotating = false
            rotationAnimator.cancel()
            continuousRotation = 0f
            invalidate()
        }
    }

    fun simulateClick(delay: Long = 50L) {
        performClick()
        isPressed = true
        invalidate()
        postDelayed({
            isPressed = false
            invalidate()
        }, delay)
    }

    // Half-Frame Split API
    fun setHalfFrameState(enabled: Boolean, step: Int) {
        val stepClamped = if (step <= 0) 0 else 1
        val wasEnabled = isHalfFrame
        val wasStep = halfFrameStep

        isHalfFrame = enabled
        halfFrameStep = stepClamped

        val targetRatio = if (stepClamped == 0) 0.54f else 0.46f

        if (wasEnabled && enabled && wasStep != stepClamped) {
            splitRatioAnimator?.cancel()
            splitRatioAnimator = ValueAnimator.ofFloat(splitRatio, targetRatio).apply {
                duration = 280L
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener {
                    splitRatio = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else {
            splitRatioAnimator?.cancel()
            splitRatio = targetRatio
            invalidate()
        }
    }

    fun isHalfFrameEnabled(): Boolean = isHalfFrame

    fun getHalfFrameStep(): Int = halfFrameStep

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0) return
        val dp = resources.displayMetrics.density
        val inset = 2f * dp
        boundsRect.set(inset, inset, width - inset, height - inset)

        val activeCornerRadius = currentCornerRadiusDp * dp

        if (!isHalfFrame) {
            drawActiveBlock(canvas, boundsRect, activeCornerRadius, dp)
        } else {
            val gap = 6f * dp
            val availableW = boundsRect.width() - gap
            val leftW = availableW * splitRatio
            val rightW = availableW * (1f - splitRatio)

            leftMiniRect.set(boundsRect.left, boundsRect.top, boundsRect.left + leftW, boundsRect.bottom)
            rightMiniRect.set(boundsRect.left + leftW + gap, boundsRect.top, boundsRect.right, boundsRect.bottom)

            val miniCornerRadius = activeCornerRadius * 0.75f

            if (halfFrameStep == 0) {
                // Step 0: Frame 1 (Left active 54%), Standby (Right 46%)
                drawActiveBlock(canvas, leftMiniRect, miniCornerRadius, dp)
                drawStandbyBlock(canvas, rightMiniRect, miniCornerRadius, dp)
            } else {
                // Step 1: Captured/Locked (Left 46%), Frame 2 (Right active 54%)
                drawLockedBlock(canvas, leftMiniRect, miniCornerRadius, dp)
                drawActiveBlock(canvas, rightMiniRect, miniCornerRadius, dp)
            }
        }

        if (drawable != null) {
            super.onDraw(canvas)
        }
    }

    private fun drawActiveBlock(canvas: Canvas, rect: RectF, radius: Float, dp: Float) {
        activeBlockPath.reset()
        activeBlockPath.addRoundRect(rect, radius, radius, Path.Direction.CW)

        // 1. Base fill color (Normal / Multi-Cam mode): Hero prominent M3 colorPrimary tonal accent
        val baseFill = if (!isEnabled) {
            colorSecondaryContainer
        } else {
            colorPrimaryContainer
        }
        blockFillPaint.color = baseFill
        canvas.drawPath(activeBlockPath, blockFillPaint)

        // Warm recording red/coral overlay if recording video
        if (isRecordingVideo) {
            recordingOverlayPaint.color = ColorUtils.setAlphaComponent(recordingDotColor, 65)
            canvas.drawPath(activeBlockPath, recordingOverlayPaint)
        }

        // 2. Internal progress / sweeping highlight fill (clipped)
        canvas.save()
        canvas.clipPath(activeBlockPath)

        if (progress > 0f) {
            val fillW = rect.width() * progress
            val progressShader = LinearGradient(
                rect.left, rect.top, rect.left + fillW, rect.top,
                ColorUtils.setAlphaComponent(colorPrimary, 110),
                ColorUtils.setAlphaComponent(colorPrimary, 200),
                Shader.TileMode.CLAMP
            )
            progressFillPaint.shader = progressShader
            canvas.drawRect(rect.left, rect.top, rect.left + fillW, rect.bottom, progressFillPaint)
        } else if (isRotating && progress <= 0.01f) {
            val phase = (continuousRotation % 360f) / 360f
            val bandW = rect.width() * 0.5f
            val sweepStart = rect.left - bandW
            val sweepDist = rect.width() + 2 * bandW
            val bandCenter = sweepStart + sweepDist * phase
            val sweepShader = LinearGradient(
                bandCenter - bandW / 2f, rect.top, bandCenter + bandW / 2f, rect.top,
                intArrayOf(
                    ColorUtils.setAlphaComponent(colorPrimary, 0),
                    ColorUtils.setAlphaComponent(colorPrimary, 130),
                    ColorUtils.setAlphaComponent(colorPrimary, 0)
                ),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
            sweepPaint.shader = sweepShader
            canvas.drawRect(rect, sweepPaint)
        }

        canvas.restore()

        // 3. 1.5dp border stroke of ColorUtils.setAlphaComponent(colorPrimary, 150)
        blockStrokePaint.strokeWidth = 1.5f * dp
        blockStrokePaint.color = if (isRecordingVideo) {
            recordingDotColor
        } else {
            ColorUtils.setAlphaComponent(colorPrimary, 150)
        }
        canvas.drawPath(activeBlockPath, blockStrokePaint)

        // 4. If recording video, draw subtle recording indicator
        if (isRecordingVideo) {
            recordingDotPaint.color = recordingDotColor
            val recRadius = 3.5f * dp
            canvas.drawCircle(rect.centerX(), rect.centerY(), recRadius, recordingDotPaint)
        }

        // 5. Perimeter Orientation Indicator Pill (dotRotation)
        drawOrientationPill(canvas, rect, dp)
    }

    private fun drawStandbyBlock(canvas: Canvas, rect: RectF, radius: Float, dp: Float) {
        standbyBlockPath.reset()
        standbyBlockPath.addRoundRect(rect, radius, radius, Path.Direction.CW)

        // Standby fill: colorSurfaceContainerHighest
        blockFillPaint.color = colorSurfaceContainerHighest
        canvas.drawPath(standbyBlockPath, blockFillPaint)

        // Standby border: colorOutlineVariant, 1dp
        blockStrokePaint.strokeWidth = 1f * dp
        blockStrokePaint.color = colorOutlineVariant
        canvas.drawPath(standbyBlockPath, blockStrokePaint)

        // Small centered standby dot
        standbyDotPaint.color = colorOutlineVariant
        canvas.drawCircle(rect.centerX(), rect.centerY(), 2.5f * dp, standbyDotPaint)
    }

    private fun drawLockedBlock(canvas: Canvas, rect: RectF, radius: Float, dp: Float) {
        lockedBlockPath.reset()
        lockedBlockPath.addRoundRect(rect, radius, radius, Path.Direction.CW)

        // Captured/locked fill: colorSecondaryContainer
        blockFillPaint.color = colorSecondaryContainer
        canvas.drawPath(lockedBlockPath, blockFillPaint)

        // Locked border: colorOutlineVariant, 1dp
        blockStrokePaint.strokeWidth = 1f * dp
        blockStrokePaint.color = ColorUtils.setAlphaComponent(colorOutlineVariant, 160)
        canvas.drawPath(lockedBlockPath, blockStrokePaint)

        // Subtle check in colorOnSecondaryContainer at 60% alpha
        val cx = rect.centerX()
        val cy = rect.centerY()
        val checkW = 10f * dp
        val checkH = 8f * dp
        checkPath.reset()
        checkPath.moveTo(cx - checkW * 0.45f, cy + checkH * 0.05f)
        checkPath.lineTo(cx - checkW * 0.1f, cy + checkH * 0.45f)
        checkPath.lineTo(cx + checkW * 0.45f, cy - checkH * 0.45f)

        checkPaint.color = ColorUtils.setAlphaComponent(colorOnSecondaryContainer, (255 * 0.60f).toInt())
        checkPaint.strokeWidth = 2f * dp
        canvas.drawPath(checkPath, checkPaint)
    }

    private fun drawOrientationPill(canvas: Canvas, rect: RectF, dp: Float) {
        val edgeInset = 6f * dp
        val baseHorizW = (if (isHalfFrame) 18f else 24f) * dp
        val horizW = minOf(baseHorizW, rect.width() - 2 * edgeInset)
        val horizH = 4f * dp
        val vertW = 4f * dp
        val baseVertH = (if (isHalfFrame) 16f else 20f) * dp
        val vertH = minOf(baseVertH, rect.height() - 2 * edgeInset)

        val topY = rect.top + edgeInset + horizH / 2f
        val bottomY = rect.bottom - edgeInset - horizH / 2f
        val leftX = rect.left + edgeInset + vertW / 2f
        val rightX = rect.right - edgeInset - vertW / 2f

        val centerX = rect.centerX()
        val centerY = rect.centerY()
        val halfX = (rightX - leftX) / 2f
        val halfY = (bottomY - topY) / 2f

        val norm = ((dotRotation % 360f) + 360f) % 360f
        val angleRad = Math.toRadians((norm - 90.0))
        val cosA = Math.cos(angleRad)
        val sinA = Math.sin(angleRad)

        val factorX = (Math.signum(cosA) * Math.pow(Math.abs(cosA), 0.5)).toFloat()
        val factorY = (Math.signum(sinA) * Math.pow(Math.abs(sinA), 0.5)).toFloat()

        val cx = centerX + factorX * halfX
        val cy = centerY + factorY * halfY

        val verticalness = Math.abs(cosA).toFloat()
        val w = horizW * (1f - verticalness) + vertW * verticalness
        val h = horizH * (1f - verticalness) + vertH * verticalness

        pillRect.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
        val pillRadius = minOf(w, h) / 2f

        pillPaint.color = if (isRecordingVideo) recordingDotColor else colorPrimary
        canvas.drawRoundRect(pillRect, pillRadius, pillRadius, pillPaint)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        rotationAnimator.cancel()
        clickSpinAnimator.cancel()
        cornerMorphAnimator?.cancel()
        splitRatioAnimator?.cancel()
        removeCallbacks(longPressRunnable)
    }
}
