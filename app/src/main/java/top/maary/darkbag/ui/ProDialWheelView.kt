package top.maary.darkbag.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.OverScroller
import com.google.android.material.color.MaterialColors
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Professional camera dial wheel with discrete stops, smooth inertia scrolling,
 * snap-to-tick physics, and haptic feedback.
 */
class ProDialWheelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class DialItem(
        val id: String,
        val label: String,
        val rawValue: Double,
        val isMajor: Boolean = false,
        val isMagnet: Boolean = false
    )

    companion object {
        const val ORIENTATION_HORIZONTAL = 0
        const val ORIENTATION_VERTICAL = 1
    }

    private var orientation: Int = ORIENTATION_HORIZONTAL
    private val items = mutableListOf<DialItem>()
    private var selectedIndex = 0
    private var scrollOffset = 0f // In pixels, relative to item 0 centered

    private var itemSpacing = 0f
    private var majorTickLength = 0f
    private var minorTickLength = 0f
    private var tickStrokeWidth = 0f
    private var majorTickStrokeWidth = 0f
    private var indicatorStrokeWidth = 0f
    private var textSize = 0f

    // Theme Colors
    private var colorOnSurface: Int = Color.WHITE
    private var colorOnSurfaceVariant: Int = Color.LTGRAY
    private var colorAccent: Int = Color.YELLOW
    private var colorIndicator: Int = Color.YELLOW

    // Paints
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val indicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val indicatorFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    // Scroller and Gestures
    private val scroller = OverScroller(context)
    private var snapAnimator: ValueAnimator? = null
    private var isDragging = false
    private var lastHapticIndex = -1

    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vibratorManager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    private var onItemSelectedListener: ((item: DialItem, index: Int, fromUser: Boolean) -> Unit)? = null
    private var onScrollStateChangedListener: ((isScrolling: Boolean) -> Unit)? = null

    private val gestureListener = object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            snapAnimator?.cancel()
            if (!scroller.isFinished) {
                scroller.abortAnimation()
            }
            isDragging = true
            onScrollStateChangedListener?.invoke(true)
            parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }

        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float
        ): Boolean {
            val delta = if (orientation == ORIENTATION_HORIZONTAL) distanceX else distanceY
            scrollByDelta(delta)
            return true
        }

        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float
        ): Boolean {
            val velocity = if (orientation == ORIENTATION_HORIZONTAL) -velocityX else -velocityY
            val maxScroll = (items.size - 1) * itemSpacing
            scroller.fling(
                scrollOffset.toInt(), 0,
                velocity.toInt(), 0,
                0, maxScroll.toInt(),
                0, 0
            )
            postInvalidateOnAnimation()
            return true
        }
    }

    private val gestureDetector = GestureDetector(context, gestureListener)

    init {
        val density = resources.displayMetrics.density
        itemSpacing = 44f * density
        majorTickLength = 18f * density
        minorTickLength = 10f * density
        tickStrokeWidth = 1.5f * density
        majorTickStrokeWidth = 2.2f * density
        indicatorStrokeWidth = 3.0f * density
        textSize = 11f * resources.displayMetrics.scaledDensity

        textPaint.textSize = textSize

        resolveColors()
    }

    fun setThemeColors(primary: Int, onSurface: Int = Color.WHITE, onSurfaceVariant: Int = Color.parseColor("#B0BEC5")) {
        colorAccent = primary
        colorIndicator = primary
        colorOnSurface = onSurface
        colorOnSurfaceVariant = onSurfaceVariant
        invalidate()
    }

    private fun resolveColors() {
        colorAccent = MaterialColors.getColor(this, android.R.attr.colorPrimary, Color.parseColor("#FFD54F"))
        colorIndicator = colorAccent
        colorOnSurface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface, Color.WHITE)
        colorOnSurfaceVariant = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutlineVariant, Color.parseColor("#B0BEC5"))
    }

    fun setItems(newItems: List<DialItem>, initialIndex: Int = 0) {
        items.clear()
        items.addAll(newItems)
        val safeIndex = initialIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        selectedIndex = safeIndex
        scrollOffset = safeIndex * itemSpacing
        lastHapticIndex = safeIndex
        invalidate()
    }

    fun setSelectedIndex(index: Int, animated: Boolean = false) {
        val safeIndex = index.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        if (safeIndex == selectedIndex && scrollOffset == safeIndex * itemSpacing) return

        selectedIndex = safeIndex
        val targetOffset = safeIndex * itemSpacing
        if (animated) {
            animateToOffset(targetOffset, fromUser = false)
        } else {
            snapAnimator?.cancel()
            scroller.abortAnimation()
            scrollOffset = targetOffset
            lastHapticIndex = safeIndex
            invalidate()
            if (items.isNotEmpty()) {
                onItemSelectedListener?.invoke(items[selectedIndex], selectedIndex, false)
            }
        }
    }

    fun getSelectedIndex(): Int = selectedIndex

    fun getSelectedItem(): DialItem? = items.getOrNull(selectedIndex)

    fun setOnItemSelectedListener(listener: (item: DialItem, index: Int, fromUser: Boolean) -> Unit) {
        this.onItemSelectedListener = listener
    }

    fun setOnScrollStateChangedListener(listener: (isScrolling: Boolean) -> Unit) {
        this.onScrollStateChangedListener = listener
    }

    fun setWheelOrientation(newOrientation: Int) {
        if (this.orientation != newOrientation) {
            this.orientation = newOrientation
            requestLayout()
            invalidate()
        }
    }

    private fun scrollByDelta(delta: Float) {
        val maxScroll = ((items.size - 1) * itemSpacing).coerceAtLeast(0f)
        val newOffset = (scrollOffset + delta).coerceIn(-itemSpacing * 0.5f, maxScroll + itemSpacing * 0.5f)
        if (newOffset != scrollOffset) {
            scrollOffset = newOffset
            checkHapticTrigger()
            invalidate()
        }
    }

    private fun checkHapticTrigger() {
        val currentIndex = (scrollOffset / itemSpacing).roundToInt().coerceIn(0, (items.size - 1).coerceAtLeast(0))
        if (currentIndex != lastHapticIndex) {
            lastHapticIndex = currentIndex
            triggerHaptic()
            if (currentIndex != selectedIndex && currentIndex in items.indices) {
                selectedIndex = currentIndex
                onItemSelectedListener?.invoke(items[currentIndex], currentIndex, true)
            }
        }
    }

    private fun triggerHaptic() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(10, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        } catch (_: Exception) {
            // Ignore vibration permission or hardware exceptions
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val result = gestureDetector.onTouchEvent(event)
        if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
            if (scroller.isFinished) {
                snapToNearest()
            }
            isDragging = false
            onScrollStateChangedListener?.invoke(false)
        }
        return result || super.onTouchEvent(event)
    }

    override fun computeScroll() {
        super.computeScroll()
        if (scroller.computeScrollOffset()) {
            scrollOffset = scroller.currX.toFloat()
            checkHapticTrigger()
            postInvalidateOnAnimation()
        } else if (!isDragging && snapAnimator == null) {
            val nearestIndex = (scrollOffset / itemSpacing).roundToInt().coerceIn(0, (items.size - 1).coerceAtLeast(0))
            val targetOffset = nearestIndex * itemSpacing
            if (abs(scrollOffset - targetOffset) > 1f) {
                snapToNearest()
            }
        }
    }

    private fun snapToNearest() {
        val nearestIndex = (scrollOffset / itemSpacing).roundToInt().coerceIn(0, (items.size - 1).coerceAtLeast(0))
        val targetOffset = nearestIndex * itemSpacing
        animateToOffset(targetOffset, fromUser = true)
    }

    private fun animateToOffset(targetOffset: Float, fromUser: Boolean) {
        snapAnimator?.cancel()
        snapAnimator = ValueAnimator.ofFloat(scrollOffset, targetOffset).apply {
            duration = 220L
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                scrollOffset = animator.animatedValue as Float
                checkHapticTrigger()
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    snapAnimator = null
                    val finalIndex = (scrollOffset / itemSpacing).roundToInt().coerceIn(0, (items.size - 1).coerceAtLeast(0))
                    selectedIndex = finalIndex
                    if (finalIndex in items.indices) {
                        onItemSelectedListener?.invoke(items[finalIndex], finalIndex, fromUser)
                    }
                    onScrollStateChangedListener?.invoke(false)
                }
            })
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (items.isEmpty()) return

        val w = width.toFloat()
        val h = height.toFloat()
        val isHorizontal = (orientation == ORIENTATION_HORIZONTAL)

        val centerPos = if (isHorizontal) w / 2f else h / 2f
        val crossPos = if (isHorizontal) h / 2f else w / 2f
        val maxVisibleDist = centerPos

        // Draw Items
        for (i in items.indices) {
            val item = items[i]
            val itemPos = centerPos + (i * itemSpacing - scrollOffset)
            val distFromCenter = abs(itemPos - centerPos)

            if (distFromCenter > maxVisibleDist + itemSpacing) continue

            // Fade out towards edges
            val normalizedDist = (distFromCenter / maxVisibleDist).coerceIn(0f, 1f)
            val alpha = ((1f - normalizedDist * normalizedDist) * 255).toInt().coerceIn(0, 255)

            val isSelected = (i == selectedIndex)
            val tickLength = if (item.isMajor || isSelected) majorTickLength else minorTickLength
            val strokeWidth = if (item.isMajor || isSelected) majorTickStrokeWidth else tickStrokeWidth

            tickPaint.strokeWidth = strokeWidth
            tickPaint.color = if (isSelected) colorAccent else colorOnSurfaceVariant
            tickPaint.alpha = alpha

            textPaint.color = if (isSelected) colorAccent else colorOnSurface
            textPaint.alpha = alpha

            if (isHorizontal) {
                val tickTop = crossPos - tickLength / 2f
                val tickBottom = crossPos + tickLength / 2f
                canvas.drawLine(itemPos, tickTop, itemPos, tickBottom, tickPaint)

                if (item.isMajor || isSelected || items.size <= 10) {
                    val textY = tickBottom + textSize + 4f
                    canvas.drawText(item.label, itemPos, textY, textPaint)
                }
            } else {
                val tickLeft = crossPos - tickLength / 2f
                val tickRight = crossPos + tickLength / 2f
                canvas.drawLine(tickLeft, itemPos, tickRight, itemPos, tickPaint)

                if (item.isMajor || isSelected || items.size <= 10) {
                    val textX = tickRight + textSize + 6f
                    val textY = itemPos + (textSize / 3f)
                    canvas.drawText(item.label, textX, textY, textPaint)
                }
            }
        }

        // Draw Center Indicator Line / Marker
        indicatorPaint.color = colorIndicator
        indicatorPaint.strokeWidth = indicatorStrokeWidth
        indicatorFillPaint.color = colorIndicator

        if (isHorizontal) {
            val indLength = majorTickLength + 10f
            val indTop = crossPos - indLength / 2f - 4f
            val indBottom = crossPos + indLength / 2f - 4f
            canvas.drawLine(centerPos, indTop, centerPos, indBottom, indicatorPaint)

            // Top Triangle Indicator
            val trianglePath = Path().apply {
                moveTo(centerPos - 5f, indTop - 3f)
                lineTo(centerPos + 5f, indTop - 3f)
                lineTo(centerPos, indTop + 4f)
                close()
            }
            canvas.drawPath(trianglePath, indicatorFillPaint)
        } else {
            val indLength = majorTickLength + 10f
            val indLeft = crossPos - indLength / 2f - 4f
            val indRight = crossPos + indLength / 2f - 4f
            canvas.drawLine(indLeft, centerPos, indRight, centerPos, indicatorPaint)

            // Left Triangle Indicator
            val trianglePath = Path().apply {
                moveTo(indLeft - 3f, centerPos - 5f)
                lineTo(indLeft - 3f, centerPos + 5f)
                lineTo(indLeft + 4f, centerPos)
                close()
            }
            canvas.drawPath(trianglePath, indicatorFillPaint)
        }
    }
}
