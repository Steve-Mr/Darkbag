package top.maary.darkbag.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import kotlin.math.roundToInt

/**
 * Fixed-width timeline seekbar for CinemaDNG sequences.
 * Seamlessly tiles evenly sampled thumbnail slices across the bar with a rounded capsule clip,
 * an interactive scrubber cursor playhead, and frame index feedback.
 */
class FilmstripSeekBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val NUM_SLICES = 8
        private const val CORNER_RADIUS_DP = 8f
        private const val PLAYHEAD_WIDTH_DP = 5f
        private const val PLAYHEAD_RADIUS_DP = 2.5f
        private const val BADGE_CORNER_RADIUS_DP = 4f
    }

    private val density = context.resources.displayMetrics.density
    private val cornerRadius = CORNER_RADIUS_DP * density
    private val playheadWidth = PLAYHEAD_WIDTH_DP * density
    private val playheadRadius = PLAYHEAD_RADIUS_DP * density
    private val badgeCornerRadius = BADGE_CORNER_RADIUS_DP * density

    var frameUris: List<Uri> = emptyList()
        private set

    var currentFrameIndex: Int = 0
        private set

    private var totalFramesCount: Int = 0

    val totalFrames: Int
        get() = if (totalFramesCount > 0) totalFramesCount else frameUris.size

    val progress: Float
        get() = if (totalFrames > 1) {
            (currentFrameIndex.toFloat() / (totalFrames - 1)).coerceIn(0f, 1f)
        } else {
            0f
        }

    var onFrameSelected: ((index: Int, uri: Uri?) -> Unit)? = null
    var onScrubStateChanged: ((isScrubbing: Boolean) -> Unit)? = null

    private var isScrubbing = false
    private var lastHapticIndex = -1

    private val sliceBitmaps = mutableMapOf<Int, Bitmap>()
    private val activeTargets = mutableListOf<CustomTarget<Bitmap>>()

    // Drawing objects to avoid allocations in onDraw
    private val clipPath = Path()
    private val boundsRectF = RectF()
    private val sliceRectF = RectF()
    private val srcRect = Rect()
    private val playheadRectF = RectF()
    private val badgeRectF = RectF()

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#1C1C1E")
    }

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = Color.argb(45, 0, 0, 0)
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = Color.argb(60, 255, 255, 255)
    }

    private val playheadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
        setShadowLayer(4f * density, 0f, 0f, Color.argb(160, 0, 0, 0))
    }

    private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(220, 20, 20, 20)
    }

    private val badgeBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = Color.argb(100, 255, 255, 255)
    }

    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 11f * density
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    init {
        isHapticFeedbackEnabled = true
    }

    fun setFrames(uris: List<Uri>, initialFrameIndex: Int = 0) {
        val urisChanged = (this.frameUris != uris)
        this.frameUris = uris
        this.totalFramesCount = uris.size
        this.currentFrameIndex = initialFrameIndex.coerceIn(0, (uris.size - 1).coerceAtLeast(0))

        if (urisChanged) {
            if (width > 0 && height > 0) {
                loadSlices()
            } else {
                post {
                    if (width > 0 && height > 0) {
                        loadSlices()
                    }
                }
            }
        }
        invalidate()
    }

    fun setProgress(currentFrameIndex: Int, totalFrames: Int) {
        if (isScrubbing) return
        this.totalFramesCount = totalFrames
        val clamped = currentFrameIndex.coerceIn(0, (totalFrames - 1).coerceAtLeast(0))
        if (this.currentFrameIndex != clamped) {
            this.currentFrameIndex = clamped
            invalidate()
        }
    }

    private fun clearSlices() {
        for (target in activeTargets) {
            try {
                Glide.with(context.applicationContext).clear(target)
            } catch (_: Exception) {}
        }
        activeTargets.clear()
        sliceBitmaps.clear()
    }

    private fun loadSlices() {
        if (frameUris.isEmpty() || width <= 0 || height <= 0) return
        clearSlices()

        val count = NUM_SLICES.coerceAtMost(frameUris.size).coerceAtLeast(1)
        val sliceWidthPx = (width.toFloat() / count).roundToInt().coerceAtLeast(1)
        val sliceHeightPx = height

        for (sliceIdx in 0 until count) {
            val frameIdx = if (count == 1) {
                0
            } else {
                ((sliceIdx.toFloat() / (count - 1)) * (frameUris.size - 1)).roundToInt().coerceIn(0, frameUris.size - 1)
            }
            val uri = frameUris[frameIdx]

            val target = object : CustomTarget<Bitmap>() {
                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                    activeTargets.remove(this)
                    sliceBitmaps[sliceIdx] = resource
                    postInvalidateOnAnimation()
                }

                override fun onLoadCleared(placeholder: Drawable?) {
                    sliceBitmaps.remove(sliceIdx)
                }
            }
            activeTargets.add(target)

            Glide.with(context.applicationContext)
                .asBitmap()
                .load(uri)
                .apply(
                    RequestOptions()
                        .override(sliceWidthPx * 2, sliceHeightPx * 2)
                        .centerCrop()
                        .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                )
                .into(target)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0 && (w != oldw || h != oldh)) {
            loadSlices()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (sliceBitmaps.isEmpty() && frameUris.isNotEmpty()) {
            if (width > 0 && height > 0) {
                loadSlices()
            } else {
                post {
                    if (width > 0 && height > 0 && sliceBitmaps.isEmpty()) {
                        loadSlices()
                    }
                }
            }
        }
    }

    override fun onDetachedFromWindow() {
        clearSlices()
        super.onDetachedFromWindow()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val total = totalFrames
        if (total <= 0) return super.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                isScrubbing = true
                onScrubStateChanged?.invoke(true)
                handleTouch(event.x, total)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                handleTouch(event.x, total)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (isScrubbing) {
                    isScrubbing = false
                    onScrubStateChanged?.invoke(false)
                }
                lastHapticIndex = -1
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun handleTouch(touchX: Float, total: Int) {
        val w = width.toFloat()
        if (w <= 0f) return
        val currentProgress = (touchX / w).coerceIn(0f, 1f)
        val newIndex = (currentProgress * (total - 1)).roundToInt().coerceIn(0, total - 1)

        if (newIndex != currentFrameIndex || lastHapticIndex != newIndex) {
            if (newIndex != lastHapticIndex) {
                val hapticConstant = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    HapticFeedbackConstants.CLOCK_TICK
                } else {
                    HapticFeedbackConstants.KEYBOARD_TAP
                }
                performHapticFeedback(hapticConstant)
                lastHapticIndex = newIndex
            }
            currentFrameIndex = newIndex
            val uri = frameUris.getOrNull(newIndex)
            onFrameSelected?.invoke(newIndex, uri)
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        boundsRectF.set(0f, 0f, w, h)
        clipPath.reset()
        clipPath.addRoundRect(boundsRectF, cornerRadius, cornerRadius, Path.Direction.CW)

        // 1. Draw rounded capsule background and thumbnail slices
        canvas.save()
        canvas.clipPath(clipPath)
        canvas.drawRect(boundsRectF, bgPaint)

        val total = totalFrames
        val count = NUM_SLICES.coerceAtMost(if (total > 0) total else 1).coerceAtLeast(1)
        val sliceWidth = w / count

        for (i in 0 until count) {
            val sliceLeft = i * sliceWidth
            val sliceRight = (i + 1) * sliceWidth
            sliceRectF.set(sliceLeft, 0f, sliceRight, h)

            val bmp = sliceBitmaps[i]
            if (bmp != null && !bmp.isRecycled) {
                val bmpAspect = bmp.width.toFloat() / bmp.height.toFloat()
                val sliceAspect = sliceRectF.width() / sliceRectF.height()
                if (bmpAspect > sliceAspect) {
                    val targetSrcWidth = (bmp.height * sliceAspect).toInt()
                    val srcLeft = ((bmp.width - targetSrcWidth) / 2).coerceAtLeast(0)
                    srcRect.set(srcLeft, 0, (srcLeft + targetSrcWidth).coerceAtMost(bmp.width), bmp.height)
                } else {
                    val targetSrcHeight = (bmp.width / sliceAspect).toInt()
                    val srcTop = ((bmp.height - targetSrcHeight) / 2).coerceAtLeast(0)
                    srcRect.set(0, srcTop, bmp.width, (srcTop + targetSrcHeight).coerceAtMost(bmp.height))
                }
                canvas.drawBitmap(bmp, srcRect, sliceRectF, bitmapPaint)
            }

            if (i > 0) {
                canvas.drawLine(sliceLeft, 0f, sliceLeft, h, dividerPaint)
            }
        }
        canvas.restore()

        // 2. Draw capsule border outline
        canvas.drawRoundRect(boundsRectF, cornerRadius, cornerRadius, borderPaint)

        // 3. Draw Scrubber Playhead Cursor
        val playheadProgress = progress
        val playheadLeft = (playheadProgress * (w - playheadWidth)).coerceIn(0f, w - playheadWidth)
        val playheadRight = playheadLeft + playheadWidth
        playheadRectF.set(playheadLeft, 0f, playheadRight, h)

        canvas.drawRoundRect(playheadRectF, playheadRadius, playheadRadius, playheadPaint)

        // 4. Draw interactive Frame Indicator Badge when scrubbing
        if (isScrubbing && total > 0) {
            val text = "${currentFrameIndex + 1} / $total"
            val textWidth = badgeTextPaint.measureText(text)
            val fontMetrics = badgeTextPaint.fontMetrics
            val textHeight = fontMetrics.descent - fontMetrics.ascent

            val padH = 8f * density
            val padV = 4f * density
            val badgeW = textWidth + padH * 2f
            val badgeH = textHeight + padV * 2f

            val badgeCenterX = playheadRectF.centerX().coerceIn(badgeW / 2f + 4f * density, w - badgeW / 2f - 4f * density)
            val badgeCenterY = h / 2f

            badgeRectF.set(
                badgeCenterX - badgeW / 2f,
                badgeCenterY - badgeH / 2f,
                badgeCenterX + badgeW / 2f,
                badgeCenterY + badgeH / 2f
            )

            canvas.drawRoundRect(badgeRectF, badgeCornerRadius, badgeCornerRadius, badgeBgPaint)
            canvas.drawRoundRect(badgeRectF, badgeCornerRadius, badgeCornerRadius, badgeBorderPaint)

            val textBaseline = badgeCenterY - (fontMetrics.descent + fontMetrics.ascent) / 2f
            canvas.drawText(text, badgeCenterX, textBaseline, badgeTextPaint)
        }
    }
}
