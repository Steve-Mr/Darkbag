package top.maary.darkbag.viewer.motion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.google.android.material.color.MaterialColors
import kotlin.math.abs

/**
 * Tactile 35mm filmstrip scrubber for Motion Photo frame picking,
 * featuring a shutter keyframe diamond pin and haptic detent crossing.
 */
class MotionFilmstripScrubberView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var thumbnails: List<Bitmap> = emptyList()
    private var keyframeFraction: Float = 0.5f
    private var currentFraction: Float = 0.5f
    private var isDragging: Boolean = false

    var onScrubListener: ((fraction: Float, fromUser: Boolean) -> Unit)? = null
    var onScrubStartListener: (() -> Unit)? = null
    var onScrubEndListener: ((fraction: Float) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val clipPath = Path()
    private val frameRect = RectF()
    private val srcRect = Rect()
    private val dstRect = RectF()
    private val diamondPath = Path()

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF141414.toInt()
    }
    private val sprocketPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x55FFFFFF
    }
    private val thumbPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOutlineVariant, Color.DKGRAY)
    }
    private val keyframePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFFFB300.toInt()
    }
    private val playheadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val playheadShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = 0x88000000.toInt()
    }

    fun setThumbnails(bitmaps: List<Bitmap>) {
        thumbnails = bitmaps
        invalidate()
    }

    fun setKeyframeFraction(fraction: Float) {
        keyframeFraction = fraction.coerceIn(0f, 1f)
        invalidate()
    }

    fun setProgressFraction(fraction: Float, notify: Boolean = false) {
        val clamped = fraction.coerceIn(0f, 1f)
        currentFraction = clamped
        invalidate()
        if (notify) onScrubListener?.invoke(clamped, false)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val outerPad = 4f * density
        val radius = 12f * density
        frameRect.set(outerPad, outerPad, w - outerPad, h - outerPad)

        clipPath.reset()
        clipPath.addRoundRect(frameRect, radius, radius, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clipPath)
        canvas.drawRect(frameRect, bgPaint)

        val sprocketBand = 7f * density
        val stripTop = frameRect.top + sprocketBand
        val stripBottom = frameRect.bottom - sprocketBand

        if (thumbnails.isNotEmpty()) {
            val tileW = frameRect.width() / thumbnails.size
            thumbnails.forEachIndexed { index, bmp ->
                if (!bmp.isRecycled) {
                    val left = frameRect.left + index * tileW
                    srcRect.set(0, 0, bmp.width, bmp.height)
                    dstRect.set(left, stripTop, left + tileW + 0.5f, stripBottom)
                    canvas.drawBitmap(bmp, srcRect, dstRect, thumbPaint)
                }
            }
        }

        // Subtle 35mm sprocket holes along top and bottom rails
        val holeW = 4f * density
        val holeH = 2.5f * density
        val holeStep = 10f * density
        var x = frameRect.left + 6f * density
        while (x + holeW < frameRect.right - 4f * density) {
            canvas.drawRoundRect(
                x,
                frameRect.top + (sprocketBand - holeH) / 2f,
                x + holeW,
                frameRect.top + (sprocketBand + holeH) / 2f,
                1f * density,
                1f * density,
                sprocketPaint
            )
            canvas.drawRoundRect(
                x,
                frameRect.bottom - (sprocketBand + holeH) / 2f,
                x + holeW,
                frameRect.bottom - (sprocketBand - holeH) / 2f,
                1f * density,
                1f * density,
                sprocketPaint
            )
            x += holeStep
        }

        canvas.restore()
        canvas.drawRoundRect(frameRect, radius, radius, borderPaint)

        // Shutter diamond pin
        val kx = frameRect.left + frameRect.width() * keyframeFraction
        val ky = frameRect.top + sprocketBand * 0.5f
        val dR = 4.5f * density
        diamondPath.reset()
        diamondPath.moveTo(kx, ky - dR)
        diamondPath.lineTo(kx + dR, ky)
        diamondPath.lineTo(kx, ky + dR)
        diamondPath.lineTo(kx - dR, ky)
        diamondPath.close()
        canvas.drawPath(diamondPath, keyframePaint)

        // Draggable white playhead capsule
        val px = frameRect.left + frameRect.width() * currentFraction
        val barW = 3.5f * density
        val barRect = RectF(px - barW / 2f, 1f * density, px + barW / 2f, h - 1f * density)
        canvas.drawRoundRect(barRect, barW / 2f, barW / 2f, playheadShadowPaint)
        canvas.drawRoundRect(barRect, barW / 2f, barW / 2f, playheadPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val w = width.toFloat()
        val pad = 4f * density
        val usableW = (w - 2f * pad).coerceAtLeast(1f)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                isDragging = true
                onScrubStartListener?.invoke()
                updateFromTouchX(event.x, pad, usableW)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDragging) {
                    updateFromTouchX(event.x, pad, usableW)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    updateFromTouchX(event.x, pad, usableW)
                    isDragging = false
                    onScrubEndListener?.invoke(currentFraction)
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }

    private fun updateFromTouchX(x: Float, pad: Float, usableW: Float) {
        val prev = currentFraction
        var next = ((x - pad) / usableW).coerceIn(0f, 1f)
        // Magnetic snap within 1.8% of shutter diamond
        if (abs(next - keyframeFraction) <= 0.018f) {
            next = keyframeFraction
        }
        if (crossedKeyframeDetent(prev, next, keyframeFraction)) {
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
        currentFraction = next
        invalidate()
        onScrubListener?.invoke(next, true)
    }

    companion object {
        fun computeKeyframeFraction(stillPtsUs: Long, durationUs: Long): Float {
            if (durationUs <= 0L) return 0.5f
            return (stillPtsUs.toFloat() / durationUs.toFloat()).coerceIn(0f, 1f)
        }

        fun crossedKeyframeDetent(prev: Float, next: Float, keyframe: Float): Boolean {
            if (abs(prev - next) < 1e-5f) return false
            val crossed = (prev < keyframe && next >= keyframe) || (prev > keyframe && next <= keyframe)
            val snappedOnto = abs(prev - keyframe) > 1e-4f && abs(next - keyframe) <= 1e-4f
            return crossed || snappedOnto
        }
    }
}
