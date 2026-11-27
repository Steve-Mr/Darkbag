package top.maary.darkbag.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs

/**
 * Image-scoped half-frame dim overlay rendered inside [item_image_group.xml]'s `image_container`.
 * Strictly clips to the image's [ImageDisplayGeometry.outlineRect] and `outlineRadius` so the
 * dim scrim never covers letterbox areas or UI chrome, and forwards taps on the dimmed half
 * to switch the active half-frame index.
 */
class HalfFrameDimOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val SCRIM_COLOR = 0x88000000.toInt()

        /**
         * Computes the screen-space rectangle of the unselected half-frame within [geometry],
         * intersected with [ImageDisplayGeometry.visibleRect] and [ImageDisplayGeometry.outlineRect].
         * Returns `null` when the resulting rectangle is empty.
         */
        fun computeDimmedRect(
            geometry: ImageDisplayGeometry,
            isTopBottom: Boolean,
            selectedHalfFrameIndex: Int
        ): RectF? {
            val content = geometry.contentRect
            if (content.isEmpty) return null

            val unselectedRect = if (isTopBottom) {
                val midY = content.centerY()
                if (selectedHalfFrameIndex == 0) {
                    RectF(content.left, midY, content.right, content.bottom)
                } else {
                    RectF(content.left, content.top, content.right, midY)
                }
            } else {
                val midX = content.centerX()
                if (selectedHalfFrameIndex == 0) {
                    RectF(midX, content.top, content.right, content.bottom)
                } else {
                    RectF(content.left, content.top, midX, content.bottom)
                }
            }

            if (!unselectedRect.intersect(geometry.visibleRect)) return null
            if (!geometry.outlineRect.isEmpty && !unselectedRect.intersect(geometry.outlineRect)) return null
            return if (unselectedRect.isEmpty) null else unselectedRect
        }
    }

    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = SCRIM_COLOR
        style = Paint.Style.FILL
    }
    private val clipPath = Path()
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var isOverlayActive = false
    private var isTopBottom = false
    private var selectedHalfFrameIndex = 0
    private var displayGeometry: ImageDisplayGeometry? = null

    private var downX = 0f
    private var downY = 0f
    private var trackingDimTap = false

    var onHalfFrameTapped: ((Int) -> Unit)? = null

    init {
        visibility = GONE
    }

    fun updateState(active: Boolean, isTopBottom: Boolean, selectedHalfFrameIndex: Int) {
        val changed = this.isOverlayActive != active ||
            this.isTopBottom != isTopBottom ||
            this.selectedHalfFrameIndex != selectedHalfFrameIndex
        this.isOverlayActive = active
        this.isTopBottom = isTopBottom
        this.selectedHalfFrameIndex = selectedHalfFrameIndex
        val targetVisibility = if (active) VISIBLE else GONE
        if (visibility != targetVisibility) {
            visibility = targetVisibility
        }
        if (changed || active) {
            invalidate()
        }
    }

    fun setDisplayGeometry(geometry: ImageDisplayGeometry?) {
        this.displayGeometry = geometry
        if (isOverlayActive) {
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!isOverlayActive) return
        val geometry = displayGeometry ?: return
        val dimRect = computeDimmedRect(geometry, isTopBottom, selectedHalfFrameIndex) ?: return

        canvas.save()
        if (!geometry.outlineRect.isEmpty && geometry.outlineRadius > 0f) {
            clipPath.reset()
            clipPath.addRoundRect(
                geometry.outlineRect,
                geometry.outlineRadius,
                geometry.outlineRadius,
                Path.Direction.CW
            )
            canvas.clipPath(clipPath)
        } else if (!geometry.outlineRect.isEmpty) {
            canvas.clipRect(geometry.outlineRect)
        }
        canvas.drawRect(dimRect, dimPaint)
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isOverlayActive) return false
        val geometry = displayGeometry ?: return false
        val dimRect = computeDimmedRect(geometry, isTopBottom, selectedHalfFrameIndex) ?: return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!dimRect.contains(event.x, event.y)) {
                    trackingDimTap = false
                    return false
                }
                downX = event.x
                downY = event.y
                trackingDimTap = true
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!trackingDimTap) return false
                if (abs(event.x - downX) > touchSlop || abs(event.y - downY) > touchSlop) {
                    trackingDimTap = false
                    return false
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!trackingDimTap) return false
                trackingDimTap = false
                if (dimRect.contains(event.x, event.y)) {
                    val targetIndex = if (selectedHalfFrameIndex == 0) 1 else 0
                    onHalfFrameTapped?.invoke(targetIndex)
                    performClick()
                    return true
                }
                return false
            }
            MotionEvent.ACTION_CANCEL -> {
                trackingDimTap = false
                return false
            }
        }
        return false
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
