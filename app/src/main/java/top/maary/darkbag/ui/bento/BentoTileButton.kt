package top.maary.darkbag.ui.bento

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.text.TextUtils
import android.util.AttributeSet
import android.view.Gravity
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import top.maary.darkbag.R
import top.maary.darkbag.ui.CameraBentoDeckView

/**
 * Reusable inner porcelain Bento button block with two-line micro-telemetry
 * and active squircle morphing.
 */
class BentoTileButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.materialButtonTonalStyle
) : MaterialButton(context, attrs, defStyleAttr) {

    private var isActiveSquircle: Boolean = false

    init {
        val density = context.resources.displayMetrics.density

        insetTop = 0
        insetBottom = 0
        setPaddingRelative((6 * density).toInt(), (4 * density).toInt(), (6 * density).toInt(), (4 * density).toInt())
        gravity = Gravity.CENTER
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        isAllCaps = false

        if (attrs != null) {
            val a = context.obtainStyledAttributes(attrs, R.styleable.BentoTileButton, defStyleAttr, 0)
            try {
                isActiveSquircle = a.getBoolean(R.styleable.BentoTileButton_bentoActiveSquircle, false)
                val label = a.getString(R.styleable.BentoTileButton_bentoTelemetryLabel)
                val value = a.getString(R.styleable.BentoTileButton_bentoTelemetryValue)
                if (!label.isNullOrEmpty() && !value.isNullOrEmpty()) {
                    setTelemetry(label, value)
                }
            } finally {
                a.recycle()
            }
        }

        applySquircleStyling(isActiveSquircle)
    }

    /**
     * Formats two-line micro-telemetry (Title + Value) with defensive length truncation.
     */
    fun setTelemetry(
        label: String,
        value: String,
        isValueManual: Boolean = false,
        isTabActive: Boolean = false
    ) {
        text = CameraBentoDeckView.formatProTelemetry(
            label = label,
            value = value,
            isTabActive = isTabActive,
            isValueManual = isValueManual,
            context = context
        )
    }

    /**
     * Morphs between standard 14dp porcelain tile and active 10dp squircle badge.
     */
    fun setActiveSquircle(active: Boolean) {
        if (isActiveSquircle == active) return
        isActiveSquircle = active
        applySquircleStyling(active)
    }

    private fun applySquircleStyling(active: Boolean) {
        val density = resources.displayMetrics.density
        val colorPrimary = MaterialColors.getColor(this, android.R.attr.colorPrimary, Color.WHITE)
        val colorPrimaryContainer = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimaryContainer, Color.DKGRAY)
        val colorSurface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface, Color.WHITE)
        val colorOutlineVariant = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutlineVariant, Color.GRAY)
        val colorOnSurface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface, Color.BLACK)
        val colorOnPrimaryContainer = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnPrimaryContainer, colorPrimary)

        if (active) {
            cornerRadius = (10 * density).toInt()
            backgroundTintList = ColorStateList.valueOf(colorPrimaryContainer)
            strokeColor = ColorStateList.valueOf(colorPrimary)
            strokeWidth = (1.5f * density).toInt()
            setTextColor(colorOnPrimaryContainer)
        } else {
            cornerRadius = (14 * density).toInt()
            backgroundTintList = ColorStateList.valueOf(colorSurface)
            strokeColor = ColorStateList.valueOf(colorOutlineVariant)
            strokeWidth = (1f * density).toInt()
            setTextColor(colorOnSurface)
        }
    }
}
