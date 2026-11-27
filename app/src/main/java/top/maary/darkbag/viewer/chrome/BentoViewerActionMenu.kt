package top.maary.darkbag.viewer.chrome

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.widget.ImageViewCompat
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import top.maary.darkbag.R
import top.maary.darkbag.ui.bento.BentoRowLayout
import kotlin.math.roundToInt

/**
 * Bento-styled floating action tray replacing platform [android.widget.PopupMenu]
 * for viewer action and save menus, with explicit theme-aware icon tinting.
 */
class BentoViewerActionMenu(
    private val context: Context,
    private val items: List<ActionItem>,
    private val onDismiss: () -> Unit = {}
) {

    enum class Role {
        HERO_GRID,
        ROW,
        DESTRUCTIVE
    }

    data class ActionItem(
        val id: Int,
        val title: String,
        @DrawableRes val iconRes: Int,
        val role: Role = Role.ROW,
        val onClick: () -> Unit
    )

    private var popupWindow: PopupWindow? = null

    fun show(anchor: View): PopupWindow {
        val root = LayoutInflater.from(context).inflate(R.layout.layout_bento_viewer_action_menu, null)
        val tray = root.findViewById<View>(R.id.menu_tray)
        val container = root.findViewById<LinearLayout>(R.id.menu_container)

        val density = context.resources.displayMetrics.density
        val gap8dp = (8f * density).roundToInt()

        val colorSurface = MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurface, Color.WHITE)
        val colorOnSurface = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurface, Color.BLACK)
        val colorErrorContainer = MaterialColors.getColor(context, com.google.android.material.R.attr.colorErrorContainer, Color.RED)
        val colorOnErrorContainer = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnErrorContainer, Color.WHITE)

        val heroItems = items.filter { it.role == Role.HERO_GRID }
        val rowItems = items.filter { it.role == Role.ROW }
        val destructiveItems = items.filter { it.role == Role.DESTRUCTIVE }

        val popup = PopupWindow(
            root,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            elevation = 8f * density
            setOnDismissListener { onDismiss() }
        }
        popupWindow = popup

        if (heroItems.isNotEmpty()) {
            val row = BentoRowLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    if (rowItems.isNotEmpty() || destructiveItems.isNotEmpty()) {
                        bottomMargin = gap8dp
                    }
                }
            }
            heroItems.forEach { item ->
                val tile = buildHeroTile(item, density, colorSurface, colorOnSurface) {
                    popup.dismiss()
                    item.onClick()
                }
                row.addView(tile)
            }
            container.addView(row)
        }

        val allLinearItems = rowItems + destructiveItems
        allLinearItems.forEachIndexed { index, item ->
            val isLast = index == allLinearItems.lastIndex
            val isDestructive = item.role == Role.DESTRUCTIVE
            val bg = if (isDestructive) colorErrorContainer else colorSurface
            val fg = if (isDestructive) colorOnErrorContainer else colorOnSurface
            val tile = buildRowTile(item, density, bg, fg, bottomMargin = if (isLast) 0 else gap8dp) {
                popup.dismiss()
                item.onClick()
            }
            container.addView(tile)
        }

        root.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val popupWidth = root.measuredWidth
        val xOff = anchor.width - popupWidth
        val yOff = (4f * density).roundToInt()

        tray.pivotX = popupWidth.toFloat()
        tray.pivotY = 0f
        tray.scaleX = 0.92f
        tray.scaleY = 0.92f
        tray.alpha = 0f

        popup.showAsDropDown(anchor, xOff, yOff, Gravity.TOP or Gravity.START)

        tray.animate()
            .scaleX(1f)
            .scaleY(1f)
            .alpha(1f)
            .setDuration(160L)
            .setInterpolator(DecelerateInterpolator())
            .start()

        return popup
    }

    private fun buildHeroTile(
        item: ActionItem,
        density: Float,
        bgColor: Int,
        fgColor: Int,
        onTap: () -> Unit
    ): MaterialCardView {
        val radius = 14f * density
        val padH = (8f * density).roundToInt()
        val padV = (12f * density).roundToInt()
        val iconSize = (20f * density).roundToInt()
        val iconGap = (6f * density).roundToInt()

        return MaterialCardView(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            this.radius = radius
            cardElevation = 0f
            strokeWidth = 0
            setCardBackgroundColor(bgColor)
            isClickable = true
            isFocusable = true
            setOnClickListener { onTap() }

            val inner = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(padH, padV, padH, padV)
            }
            val icon = ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                    bottomMargin = iconGap
                }
                setImageResource(item.iconRes)
                ImageViewCompat.setImageTintList(this, ColorStateList.valueOf(fgColor))
            }
            val label = TextView(context).apply {
                text = item.title
                textSize = 11.5f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(fgColor)
                gravity = Gravity.CENTER
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }
            inner.addView(icon)
            inner.addView(label)
            addView(inner)
        }
    }

    private fun buildRowTile(
        item: ActionItem,
        density: Float,
        bgColor: Int,
        fgColor: Int,
        bottomMargin: Int,
        onTap: () -> Unit
    ): MaterialCardView {
        val radius = 14f * density
        val padH = (12f * density).roundToInt()
        val padV = (11f * density).roundToInt()
        val iconSize = (18f * density).roundToInt()
        val iconGap = (10f * density).roundToInt()

        return MaterialCardView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                this.bottomMargin = bottomMargin
            }
            this.radius = radius
            cardElevation = 0f
            strokeWidth = 0
            setCardBackgroundColor(bgColor)
            isClickable = true
            isFocusable = true
            setOnClickListener { onTap() }

            val inner = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(padH, padV, padH, padV)
            }
            val icon = ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                    marginEnd = iconGap
                }
                setImageResource(item.iconRes)
                ImageViewCompat.setImageTintList(this, ColorStateList.valueOf(fgColor))
            }
            val label = TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                text = item.title
                textSize = 12.5f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(fgColor)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            inner.addView(icon)
            inner.addView(label)
            addView(inner)
        }
    }
}
