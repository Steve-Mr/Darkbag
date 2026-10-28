package top.maary.darkbag.ui.bento

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.materialswitch.MaterialSwitch
import top.maary.darkbag.R

/**
 * Standardized porcelain switch tile for Bento decks and settings menus.
 */
class BentoSwitchTile @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.materialCardViewFilledStyle
) : MaterialCardView(context, attrs, defStyleAttr) {

    enum class BentoBadgeStyle {
        PRIMARY, SECONDARY, TERTIARY
    }

    private val titleView = TextView(context)
    private val badgeView = TextView(context)
    val switchView = MaterialSwitch(context)

    private var cardCheckedChangeListener: OnCheckedChangeListener? = null
    private var compoundCheckedChangeListener: CompoundButton.OnCheckedChangeListener? = null
    private var tileCheckedChangeListener: ((view: BentoSwitchTile, isChecked: Boolean) -> Unit)? = null
    private var isDispatching = false

    override fun isChecked(): Boolean {
        @Suppress("SENSELESS_COMPARISON")
        return if (switchView != null) switchView.isChecked else super.isChecked()
    }

    override fun setChecked(checked: Boolean) {
        super.setChecked(checked)
        @Suppress("SENSELESS_COMPARISON")
        if (switchView != null && switchView.isChecked != checked) {
            switchView.isChecked = checked
        }
    }

    override fun toggle() {
        val wasChecked = isChecked
        super.toggle()
        val newChecked = super.isChecked()
        @Suppress("SENSELESS_COMPARISON")
        if (switchView != null && switchView.isChecked != newChecked) {
            switchView.isChecked = newChecked
        }
        if (wasChecked != newChecked) {
            notifySecondaryListeners(newChecked)
        }
    }

    private fun notifySecondaryListeners(checked: Boolean) {
        if (isDispatching) return
        isDispatching = true
        try {
            compoundCheckedChangeListener?.onCheckedChanged(switchView, checked)
            tileCheckedChangeListener?.invoke(this, checked)
        } finally {
            isDispatching = false
        }
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        @Suppress("SENSELESS_COMPARISON")
        if (switchView != null) {
            switchView.isEnabled = enabled
        }
        alpha = if (enabled) 1.0f else 0.5f
    }

    init {
        isCheckable = true
        isClickable = true
        isFocusable = true
        checkedIcon = null
        setOnClickListener {
            toggle()
        }
        val density = context.resources.displayMetrics.density

        radius = 14f * density
        strokeWidth = (1f * density).toInt()

        val colorSurface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface, Color.WHITE)
        val colorOutlineVariant = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutlineVariant, Color.GRAY)
        val colorOnSurface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface, Color.BLACK)

        setCardBackgroundColor(ColorStateList.valueOf(colorSurface))
        setCardForegroundColor(ColorStateList.valueOf(Color.TRANSPARENT))
        val rippleColorInt = MaterialColors.getColor(this, android.R.attr.colorControlHighlight, Color.LTGRAY)
        rippleColor = ColorStateList.valueOf(rippleColorInt)
        strokeColor = colorOutlineVariant

        val rootLayout = LinearLayout(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative((10 * density).toInt(), 0, (10 * density).toInt(), 0)
        }

        titleView.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
            textSize = 11.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(colorOnSurface)
        }
        rootLayout.addView(titleView)

        badgeView.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = (6 * density).toInt()
            }
            textSize = 9.5f
            setTypeface(typeface, Typeface.BOLD)
            setPaddingRelative((6 * density).toInt(), (1 * density).toInt(), (6 * density).toInt(), (1 * density).toInt())
            visibility = View.GONE
        }
        rootLayout.addView(badgeView)

        switchView.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            isClickable = false
            isFocusable = false
        }
        rootLayout.addView(switchView)

        addView(rootLayout)

        if (attrs != null) {
            val a = context.obtainStyledAttributes(attrs, R.styleable.BentoSwitchTile, defStyleAttr, 0)
            try {
                val title = a.getString(R.styleable.BentoSwitchTile_bentoSwitchTitle)
                if (title != null) setTitle(title)

                val badge = a.getString(R.styleable.BentoSwitchTile_bentoSwitchBadge)
                val badgeStyleIdx = a.getInt(R.styleable.BentoSwitchTile_bentoSwitchBadgeStyle, 1)
                val style = when (badgeStyleIdx) {
                    0 -> BentoBadgeStyle.PRIMARY
                    2 -> BentoBadgeStyle.TERTIARY
                    else -> BentoBadgeStyle.SECONDARY
                }
                if (badge != null) setBadge(badge, style)

                val checked = a.getBoolean(R.styleable.BentoSwitchTile_bentoSwitchChecked, false)
                isChecked = checked
            } finally {
                a.recycle()
            }
        }
    }

    fun setTitle(text: CharSequence) {
        titleView.text = text
    }

    fun setBadge(badgeText: CharSequence?, style: BentoBadgeStyle = BentoBadgeStyle.SECONDARY) {
        if (badgeText.isNullOrEmpty()) {
            badgeView.visibility = View.GONE
            return
        }
        badgeView.text = badgeText
        badgeView.visibility = View.VISIBLE

        when (style) {
            BentoBadgeStyle.PRIMARY -> {
                badgeView.setBackgroundResource(R.drawable.bg_bento_badge_primary)
                badgeView.setTextColor(MaterialColors.getColor(this, android.R.attr.colorPrimary, Color.WHITE))
            }
            BentoBadgeStyle.SECONDARY -> {
                badgeView.setBackgroundResource(R.drawable.bg_bento_badge_secondary)
                badgeView.setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorSecondary, Color.CYAN))
            }
            BentoBadgeStyle.TERTIARY -> {
                badgeView.setBackgroundResource(R.drawable.bg_bento_badge_tertiary)
                badgeView.setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorTertiary, Color.MAGENTA))
            }
        }
    }

    override fun setCheckedIcon(checkedIcon: Drawable?) {
        super.setCheckedIcon(null)
    }

    override fun setCheckedIconResource(id: Int) {
        super.setCheckedIcon(null)
    }

    override fun setOnCheckedChangeListener(listener: OnCheckedChangeListener?) {
        super.setOnCheckedChangeListener(listener)
        this.cardCheckedChangeListener = listener
    }

    fun setOnCheckedChangeListener(listener: CompoundButton.OnCheckedChangeListener?) {
        this.compoundCheckedChangeListener = listener
    }

    fun setOnCheckedChangeListener(listener: ((view: BentoSwitchTile, isChecked: Boolean) -> Unit)?) {
        this.tileCheckedChangeListener = listener
    }
}
