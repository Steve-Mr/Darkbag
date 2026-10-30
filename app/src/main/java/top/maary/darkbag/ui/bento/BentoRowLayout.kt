package top.maary.darkbag.ui.bento

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import top.maary.darkbag.R

/**
 * Responsive row container for Bento blocks that automatically balances
 * horizontal gutters (8dp) and adjusts row height based on screen tier.
 */
class BentoRowLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    enum class RowScreenTier {
        AUTO, COMPACT, STANDARD, TALL
    }

    private var gutterPx: Int
    private var screenTier: RowScreenTier = RowScreenTier.AUTO

    init {
        val density = context.resources.displayMetrics.density
        orientation = HORIZONTAL
        gutterPx = (8f * density).toInt()

        if (attrs != null) {
            val a = context.obtainStyledAttributes(attrs, R.styleable.BentoRowLayout, defStyleAttr, 0)
            try {
                gutterPx = a.getDimensionPixelSize(R.styleable.BentoRowLayout_bentoGutter, gutterPx)
                val tierIdx = a.getInt(R.styleable.BentoRowLayout_bentoRowScreenTier, 0)
                screenTier = when (tierIdx) {
                    1 -> RowScreenTier.COMPACT
                    2 -> RowScreenTier.STANDARD
                    3 -> RowScreenTier.TALL
                    else -> RowScreenTier.AUTO
                }
            } finally {
                a.recycle()
            }
        }
    }

    override fun onViewAdded(child: View?) {
        super.onViewAdded(child)
        balanceGutters()
    }

    /**
     * Balances 8dp gutter evenly (4dp marginEnd on left, 4dp marginStart on right)
     * between all adjacent child views.
     */
    fun balanceGutters() {
        val count = childCount
        if (count <= 1) return

        val halfGutter = gutterPx / 2
        for (i in 0 until count) {
            val child = getChildAt(i)
            val lp = child.layoutParams as? MarginLayoutParams ?: continue
            var changed = false

            val targetStart = if (i > 0) halfGutter else 0
            val targetEnd = if (i < count - 1) halfGutter else 0

            if (lp.marginStart != targetStart || lp.marginEnd != targetEnd) {
                lp.marginStart = targetStart
                lp.marginEnd = targetEnd
                child.layoutParams = lp
                changed = true
            }
        }
    }

    /**
     * Dynamically adjusts the row height according to screen height density / ratio.
     */
    fun applyAdaptiveHeight(tier: RowScreenTier) {
        screenTier = tier
        val density = resources.displayMetrics.density
        val targetHeightDp = when (tier) {
            RowScreenTier.COMPACT -> 46
            RowScreenTier.STANDARD -> 54
            RowScreenTier.TALL -> 58
            RowScreenTier.AUTO -> {
                val dm = resources.displayMetrics
                val heightDp = dm.heightPixels / density
                val widthDp = dm.widthPixels / density
                val aspectRatio = if (widthDp > 0) heightDp / widthDp else 2.0f
                if (aspectRatio < 1.88f || heightDp < 720f) 46 else 54
            }
        }
        val targetHeightPx = (targetHeightDp * density).toInt()
        val lp = layoutParams
        if (lp != null && lp.height != targetHeightPx) {
            lp.height = targetHeightPx
            layoutParams = lp
        }
    }
}
