package top.maary.darkbag.ui

import android.content.Context
import android.graphics.Matrix
import android.graphics.PointF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.abs

class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    override fun setImageBitmap(bm: android.graphics.Bitmap?) {
        super.setImageBitmap(bm)
        resetZoom()
    }

    override fun setImageDrawable(drawable: android.graphics.drawable.Drawable?) {
        super.setImageDrawable(drawable)
        resetZoom()
    }

    private var matrixValue = Matrix()
    private var mode = NONE

    private var last = PointF()
    private var start = PointF()
    private var minScale = 1f
    private var maxScale = 4f
    private var m: FloatArray = FloatArray(9)

    private var viewWidth = 0
    private var viewHeight = 0
    var saveScale = 1f
        private set
    private var origWidth = 0f
    private var origHeight = 0f

    private var mScaleDetector: ScaleGestureDetector
    private var mGestureDetector: GestureDetector

    var onTapped: (() -> Unit)? = null
    var onZoomChanged: ((Boolean) -> Unit)? = null
    var onLongPressStarted: ((ZoomableImageView) -> Unit)? = null
    var onLongPressEnded: ((ZoomableImageView) -> Unit)? = null

    private var isLongPressing = false
    private var isLongPressConsumed = false

    private var visualMargin = 0f
    private var visualCornerRadius = 0f
    private var redundancyX = 0f
    private var redundancyY = 0f

    companion object {
        private const val NONE = 0
        private const val DRAG = 1
        private const val ZOOM = 2
        private const val CLICK = 3
    }

    init {
        super.setClickable(true)
        mScaleDetector = ScaleGestureDetector(context, ScaleListener())
        mGestureDetector = GestureDetector(context, GestureListener())
        matrixValue = Matrix()
        imageMatrix = Matrix(matrixValue)
        scaleType = ScaleType.MATRIX
        clipToOutline = true
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                if (saveScale > 1.0f) {
                    outline.setRect(0, 0, view.width, view.height)
                } else {
                    val margin = visualMargin.toInt()
                    val radius = visualCornerRadius
                    outline.setRoundRect(
                        (redundancyX + margin).toInt(),
                        (redundancyY).toInt(),
                        (view.width - redundancyX - margin).toInt(),
                        (view.height - redundancyY).toInt(),
                        radius
                    )
                }
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        mScaleDetector.onTouchEvent(event)
        mGestureDetector.onTouchEvent(event)

        val curr = PointF(event.x, event.y)

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isLongPressConsumed = false
                last.set(curr)
                start.set(last)
                mode = DRAG
                parent.requestDisallowInterceptTouchEvent(saveScale > 1f)
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == DRAG && !isLongPressing) {
                    val deltaX = curr.x - last.x
                    val deltaY = curr.y - last.y
                    val fixTransX = getFixDragTrans(deltaX, viewWidth.toFloat(), origWidth * saveScale)
                    val fixTransY = getFixDragTrans(deltaY, viewHeight.toFloat(), origHeight * saveScale)
                    matrixValue.postTranslate(fixTransX, fixTransY)
                    fixTrans()
                    last.set(curr.x, curr.y)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isLongPressing) {
                    isLongPressing = false
                    onLongPressEnded?.invoke(this@ZoomableImageView)
                }
                mode = NONE
                if (event.action == MotionEvent.ACTION_UP) {
                    val xDiff = abs(curr.x - start.x).toInt()
                    val yDiff = abs(curr.y - start.y).toInt()
                    if (xDiff < CLICK && yDiff < CLICK && !isLongPressConsumed) {
                        performClick()
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                mode = NONE
            }
        }

        imageMatrix = Matrix(matrixValue)
        invalidate()

        // Handle ViewPager2 conflict: Intercept if zoomed in
        if (saveScale > 1f) {
            parent.requestDisallowInterceptTouchEvent(true)
        }

        return true
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            mode = ZOOM
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            var mScaleFactor = detector.scaleFactor
            val origScale = saveScale
            saveScale *= mScaleFactor
            if (saveScale > maxScale) {
                saveScale = maxScale
                mScaleFactor = maxScale / origScale
            } else if (saveScale < minScale) {
                saveScale = minScale
                mScaleFactor = minScale / origScale
            }

            if (origWidth * saveScale <= viewWidth || origHeight * saveScale <= viewHeight) {
                matrixValue.postScale(mScaleFactor, mScaleFactor, viewWidth / 2f, viewHeight / 2f)
            } else {
                matrixValue.postScale(mScaleFactor, mScaleFactor, detector.focusX, detector.focusY)
            }

            fixTrans()
            invalidateOutline()
            onZoomChanged?.invoke(saveScale > 1f)
            return true
            }
        }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            onTapped?.invoke()
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            isLongPressing = true
            isLongPressConsumed = true
            onLongPressStarted?.invoke(this@ZoomableImageView)
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            val targetScale = if (saveScale > 1f) 1f else 2.5f
            val focusX = e.x
            val focusY = e.y

            animateZoom(targetScale, focusX, focusY)
            return true
        }
    }

    private fun animateZoom(targetScale: Float, focusX: Float, focusY: Float) {
        val startScale = saveScale
        val animator = android.animation.ValueAnimator.ofFloat(startScale, targetScale)
        animator.duration = 300
        animator.addUpdateListener { animation ->
            val currentScale = animation.animatedValue as Float
            val deltaScale = currentScale / saveScale
            saveScale = currentScale

            matrixValue.postScale(deltaScale, deltaScale, focusX, focusY)
            fixTrans()
            imageMatrix = Matrix(matrixValue)
            invalidate()
            invalidateOutline()
        }
        animator.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                onZoomChanged?.invoke(saveScale > 1f)
            }
        })
        animator.start()
    }

    private fun fixTrans() {
        matrixValue.getValues(m)
        val transX = m[Matrix.MTRANS_X]
        val transY = m[Matrix.MTRANS_Y]
        val fixTransX = getFixTrans(transX, viewWidth.toFloat(), origWidth * saveScale)
        val fixTransY = getFixTrans(transY, viewHeight.toFloat(), origHeight * saveScale)
        if (fixTransX != 0f || fixTransY != 0f) matrixValue.postTranslate(fixTransX, fixTransY)
    }

    private fun getFixTrans(trans: Float, viewSize: Float, contentSize: Float): Float {
        val minTrans: Float
        val maxTrans: Float

        if (contentSize <= viewSize) {
            minTrans = (viewSize - contentSize) / 2
            maxTrans = (viewSize - contentSize) / 2
        } else {
            minTrans = viewSize - contentSize
            maxTrans = 0f
        }

        if (trans < minTrans) return -trans + minTrans
        if (trans > maxTrans) return -trans + maxTrans
        return 0f
    }

    private fun getFixDragTrans(delta: Float, viewSize: Float, contentSize: Float): Float {
        return if (contentSize <= viewSize) {
            0f
        } else delta
    }

    fun resetZoom() {
        saveScale = 1f
        origWidth = 0f
        origHeight = 0f
        matrixValue.reset()
        fitCenter()
        invalidate()
        invalidateOutline()
    }

    fun restoreZoomState(matrix: Matrix, scale: Float) {
        this.matrixValue.set(matrix)
        this.saveScale = scale

        // Re-calculate original dimensions for current drawable to ensure fixTrans works
        val drawable = drawable
        if (drawable != null && drawable.intrinsicWidth != 0 && drawable.intrinsicHeight != 0 && viewWidth != 0 && viewHeight != 0) {
            val bmWidth = drawable.intrinsicWidth
            val bmHeight = drawable.intrinsicHeight

            val availableWidth = viewWidth - 2 * visualMargin
            val scaleX = availableWidth / bmWidth
            val scaleY = viewHeight.toFloat() / bmHeight
            val baseScale = if (scaleX < scaleY) scaleX else scaleY

            redundancyX = (viewWidth.toFloat() - baseScale * bmWidth) / 2
            redundancyY = (viewHeight.toFloat() - baseScale * bmHeight) / 2
            origWidth = viewWidth - 2 * redundancyX
            origHeight = viewHeight - 2 * redundancyY
        }

        imageMatrix = Matrix(this.matrixValue)
        invalidate()
        invalidateOutline()
        onZoomChanged?.invoke(saveScale > 1f)
    }

    fun setVisualParams(margin: Float, radius: Float) {
        this.visualMargin = margin
        this.visualCornerRadius = radius
        resetZoom()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val newWidth = MeasureSpec.getSize(widthMeasureSpec)
        val newHeight = MeasureSpec.getSize(heightMeasureSpec)

        if (newWidth != viewWidth || newHeight != viewHeight) {
            viewWidth = newWidth
            viewHeight = newHeight
            resetZoom()
        }
    }

    private fun fitCenter() {
        val drawable = drawable
        if (drawable == null || drawable.intrinsicWidth == 0 || drawable.intrinsicHeight == 0 || viewWidth == 0 || viewHeight == 0) return

        val bmWidth = drawable.intrinsicWidth
        val bmHeight = drawable.intrinsicHeight

        val availableWidth = viewWidth - 2 * visualMargin
        val scaleX = availableWidth / bmWidth
        val scaleY = viewHeight.toFloat() / bmHeight
        val scale = if (scaleX < scaleY) scaleX else scaleY

        matrixValue.setScale(scale, scale)

        // Center the image
        redundancyY = (viewHeight.toFloat() - scale * bmHeight) / 2
        redundancyX = (viewWidth.toFloat() - scale * bmWidth) / 2
        matrixValue.postTranslate(redundancyX, redundancyY)

        origWidth = viewWidth - 2 * redundancyX
        origHeight = viewHeight - 2 * redundancyY
        imageMatrix = Matrix(matrixValue)
    }
}
