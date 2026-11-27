package top.maary.darkbag.viewer.chrome

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.util.AttributeSet
import android.view.Gravity
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.widget.ImageViewCompat
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.shape.ShapeAppearanceModel
import top.maary.darkbag.R
import kotlin.math.roundToInt

/**
 * Custom Bento split trailing menu ear inspired by Material 3 Expressive Split Button:
 * - Rest: asymmetric [5dp, 18dp, 18dp, 5dp] primaryContainer pill with downward chevron.
 * - Expanded (isChecked): morphs to 21dp full circle, primary fill, onPrimary chevron rotated 180°.
 */
class BentoSplitChevronButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : MaterialCardView(context, attrs, defStyleAttr) {

    private val density = context.resources.displayMetrics.density
    private val innerCornerPx = 5f * density
    private val outerCornerPx = 18f * density
    private val circleCornerPx = 21f * density

    private val colorPrimary = MaterialColors.getColor(context, android.R.attr.colorPrimary, Color.WHITE)
    private val colorOnPrimary = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnPrimary, Color.BLACK)
    private val colorPrimaryContainer = MaterialColors.getColor(context, com.google.android.material.R.attr.colorPrimaryContainer, Color.DKGRAY)
    private val colorOnPrimaryContainer = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnPrimaryContainer, Color.WHITE)

    private val iconView = ImageView(context).apply {
        val size = (20f * density).roundToInt()
        layoutParams = FrameLayout.LayoutParams(size, size, Gravity.CENTER)
        setImageResource(R.drawable.ic_expand_more_22px)
        scaleType = ImageView.ScaleType.FIT_CENTER
    }

    private var morphAnimator: ValueAnimator? = null
    private var currentMorphFraction: Float = 0f

    init {
        isCheckable = true
        isClickable = true
        isFocusable = true
        cardElevation = 0f
        strokeWidth = 0
        checkedIcon = null
        checkedIconSize = 0
        checkedIconMargin = 0
        setCardForegroundColor(ColorStateList.valueOf(Color.TRANSPARENT))
        setContentPadding(0, 0, 0, 0)
        preventCornerOverlap = false
        addView(iconView)
        applyVisualState(checked = false, animate = false)
    }

    override fun setChecked(checked: Boolean) {
        val changed = isChecked != checked
        super.setChecked(checked)
        if (changed) {
            applyVisualState(checked = checked, animate = isAttachedToWindow)
        }
    }

    private fun applyVisualState(checked: Boolean, animate: Boolean) {
        setCardBackgroundColor(if (checked) colorPrimary else colorPrimaryContainer)
        ImageViewCompat.setImageTintList(
            iconView,
            ColorStateList.valueOf(if (checked) colorOnPrimary else colorOnPrimaryContainer)
        )

        val targetFraction = if (checked) 1f else 0f
        val targetRotation = if (checked) 180f else 0f

        morphAnimator?.cancel()
        iconView.animate().cancel()

        if (!animate) {
            currentMorphFraction = targetFraction
            applyMorphFraction(targetFraction)
            iconView.rotation = targetRotation
            return
        }

        val startFraction = currentMorphFraction
        morphAnimator = ValueAnimator.ofFloat(startFraction, targetFraction).apply {
            duration = MORPH_DURATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val f = anim.animatedValue as Float
                currentMorphFraction = f
                applyMorphFraction(f)
            }
            start()
        }
        iconView.animate()
            .rotation(targetRotation)
            .setDuration(MORPH_DURATION_MS)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun applyMorphFraction(f: Float) {
        val inner = innerCornerPx + (circleCornerPx - innerCornerPx) * f
        val outer = outerCornerPx + (circleCornerPx - outerCornerPx) * f
        shapeAppearanceModel = ShapeAppearanceModel.builder()
            .setTopLeftCornerSize(inner)
            .setBottomLeftCornerSize(inner)
            .setTopRightCornerSize(outer)
            .setBottomRightCornerSize(outer)
            .build()
    }

    override fun onDetachedFromWindow() {
        morphAnimator?.cancel()
        morphAnimator = null
        iconView.animate().cancel()
        super.onDetachedFromWindow()
    }

    companion object {
        private const val MORPH_DURATION_MS = 180L
    }
}
