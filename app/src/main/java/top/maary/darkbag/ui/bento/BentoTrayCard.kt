package top.maary.darkbag.ui.bento

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.util.AttributeSet
import com.google.android.material.card.MaterialCardView
import top.maary.darkbag.R
import top.maary.darkbag.modes.CaptureMode

/**
 * Extensible, self-styling outer Bento Tray container compliant with
 * Darkbag's Bento Matrix Design System and Material 3 Expressive guidelines.
 *
 * Defaults:
 * - strokeWidth = 0dp (borderless container fill)
 * - cornerRadius = 26dp (outer concentric radius)
 * - contentPadding = 10dp
 * - dynamic mode linking via [setCaptureMode]
 */
class BentoTrayCard @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.materialCardViewFilledStyle
) : MaterialCardView(context, attrs, defStyleAttr) {

    private var currentRole: BentoContainerRole = BentoContainerRole.SURFACE_CONTAINER_LOW
    val containerRole: BentoContainerRole get() = currentRole
    private var colorAnimator: ValueAnimator? = null

    init {
        val density = context.resources.displayMetrics.density
        val defaultRadius = 26f * density
        val defaultPadding = (10f * density).toInt()

        strokeWidth = 0

        val hasCustomRadius = attrs?.getAttributeValue("http://schemas.android.com/apk/res-auto", "cardCornerRadius") != null
        if (!hasCustomRadius) {
            radius = defaultRadius
        }

        val hasCustomPadding = attrs?.getAttributeValue("http://schemas.android.com/apk/res-auto", "contentPadding") != null
        if (!hasCustomPadding) {
            setContentPadding(defaultPadding, defaultPadding, defaultPadding, defaultPadding)
        }

        var roleSpecified = false
        if (attrs != null) {
            val a = context.obtainStyledAttributes(attrs, R.styleable.BentoTrayCard, defStyleAttr, 0)
            try {
                if (a.hasValue(R.styleable.BentoTrayCard_bentoContainerRole)) {
                    val roleIdx = a.getInt(R.styleable.BentoTrayCard_bentoContainerRole, 2)
                    currentRole = BentoContainerRole.fromAttributeIndex(roleIdx)
                    roleSpecified = true
                }
                if (a.hasValue(R.styleable.BentoTrayCard_bentoRadius)) {
                    radius = a.getDimension(R.styleable.BentoTrayCard_bentoRadius, defaultRadius)
                }
            } finally {
                a.recycle()
            }
        }

        val hasCustomBg = attrs?.getAttributeValue("http://schemas.android.com/apk/res-auto", "cardBackgroundColor") != null
                || attrs?.getAttributeValue("http://schemas.android.com/apk/res/android", "background") != null
        if (roleSpecified || !hasCustomBg) {
            applyContainerRole(currentRole, animated = false)
        }
    }

    /**
     * Updates the semantic M3 container role of this tray.
     */
    fun setContainerRole(role: BentoContainerRole, animated: Boolean = true) {
        currentRole = role
        applyContainerRole(role, animated)
    }

    /**
     * Dynamically themes the container background based on Darkbag's active shooting mode.
     */
    fun setCaptureMode(mode: CaptureMode, animated: Boolean = true) {
        val role = BentoContainerRole.fromCaptureMode(mode)
        setContainerRole(role, animated)
    }

    private fun applyContainerRole(role: BentoContainerRole, animated: Boolean) {
        val targetColor = role.resolveColor(this)

        if (!animated) {
            colorAnimator?.cancel()
            setCardBackgroundColor(ColorStateList.valueOf(targetColor))
            return
        }

        val currentColor = cardBackgroundColor?.defaultColor ?: Color.TRANSPARENT
        if (currentColor == targetColor) return

        colorAnimator?.cancel()
        colorAnimator = ValueAnimator.ofObject(ArgbEvaluator(), currentColor, targetColor).apply {
            duration = 250L
            addUpdateListener { animator ->
                val animatedColor = animator.animatedValue as Int
                setCardBackgroundColor(ColorStateList.valueOf(animatedColor))
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        colorAnimator?.cancel()
    }
}
