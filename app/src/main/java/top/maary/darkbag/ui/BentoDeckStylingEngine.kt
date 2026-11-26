package top.maary.darkbag.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.MetricAffectingSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import androidx.core.graphics.ColorUtils
import com.google.android.material.R as MaterialR
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import top.maary.darkbag.R
import top.maary.darkbag.databinding.CameraUiContainerBinding
import top.maary.darkbag.modes.CaptureMode
import top.maary.darkbag.ui.bento.BentoTrayCard
import kotlin.math.roundToInt

/**
 * Material 3 Expressive / Bento Matrix styling and micro-telemetry formatter for
 * Darkbag's camera deck tiles, pills, mode switches, and outer deck container.
 */
object BentoDeckStylingEngine {

    /**
     * Styles the HDR+ Bento tile using Material 3 dynamic colors and squircle morphing.
     */
    @JvmStatic
    fun styleHdrPlusTile(binding: CameraUiContainerBinding, isHdrPlusEnabled: Boolean) {
        val card = binding.hdrPlusPill
        val label = binding.tvHdrStatus
        val density = card.resources.displayMetrics.density

        label.text = if (isHdrPlusEnabled) "HDR+" else "HDR"
        if (isHdrPlusEnabled) {
            card.setCardBackgroundColor(card.m3Color(MaterialR.attr.colorPrimaryContainer, Color.DKGRAY))
            label.setTextColor(card.m3Color(MaterialR.attr.colorOnPrimaryContainer, Color.WHITE))
            card.radius = 14f * density
        } else {
            card.setCardBackgroundColor(card.m3Color(MaterialR.attr.colorSurfaceContainerHigh, Color.GRAY))
            label.setTextColor(card.m3Color(MaterialR.attr.colorOnSurfaceVariant, Color.LTGRAY))
            card.radius = 16f * density
        }
    }

    /**
     * Applies Material 3 Expressive Bento styling to a camera lens button.
     */
    @JvmStatic
    fun styleBentoLensButton(button: MaterialButton, isActive: Boolean, isDigitalCrop: Boolean) {
        val density = button.resources.displayMetrics.density
        val stroke15dp = (1.5f * density).roundToInt()
        val radius12dp = (12f * density).roundToInt()
        val radius14dp = (14f * density).roundToInt()

        button.configureCompactTileButton()

        val colorPrimary = button.m3Color(android.R.attr.colorPrimary, Color.WHITE)
        val colorPrimaryContainer = button.m3Color(MaterialR.attr.colorPrimaryContainer, Color.DKGRAY)
        val colorOnPrimaryContainer = button.m3Color(MaterialR.attr.colorOnPrimaryContainer, colorPrimary)
        val colorSecondaryContainer = button.m3Color(MaterialR.attr.colorSecondaryContainer, Color.DKGRAY)
        val colorTertiary = button.m3Color(MaterialR.attr.colorTertiary, colorPrimary)
        val colorTertiaryContainer = button.m3Color(MaterialR.attr.colorTertiaryContainer, colorSecondaryContainer)
        val colorOnTertiaryContainer = button.m3Color(MaterialR.attr.colorOnTertiaryContainer, colorTertiary)
        val colorSurfaceContainerHigh = button.m3Color(MaterialR.attr.colorSurfaceContainerHigh, Color.GRAY)
        val colorOnSurfaceVariant = button.m3Color(MaterialR.attr.colorOnSurfaceVariant, Color.LTGRAY)

        when {
            isActive && !isDigitalCrop -> {
                button.applyPillStyle(colorPrimaryContainer, colorOnPrimaryContainer, colorOnPrimaryContainer, colorPrimary, stroke15dp, radius12dp)
                applyLayoutWeight(button, 1.25f)
            }
            isActive && isDigitalCrop -> {
                button.applyPillStyle(colorTertiaryContainer, colorOnTertiaryContainer, colorOnTertiaryContainer, colorTertiary, stroke15dp, radius12dp)
                applyLayoutWeight(button, 1.25f)
            }
            else -> {
                button.applyPillStyle(colorSurfaceContainerHigh, colorOnSurfaceVariant, colorOnSurfaceVariant, Color.TRANSPARENT, 0, radius14dp)
                applyLayoutWeight(button, 1.0f)
            }
        }
    }

    /**
     * Applies Material 3 Expressive Bento toggle tile styling.
     */
    @JvmStatic
    @JvmOverloads
    fun styleBentoToggleTile(
        button: MaterialButton,
        isActive: Boolean,
        isTertiaryAccent: Boolean = false
    ) {
        val density = button.resources.displayMetrics.density
        val radius12dp = (12f * density).roundToInt()
        val radius14dp = (14f * density).roundToInt()
        val stroke15dp = (1.5f * density).roundToInt()

        val colorPrimary = button.m3Color(android.R.attr.colorPrimary, Color.WHITE)
        val colorSecondaryContainer = button.m3Color(MaterialR.attr.colorSecondaryContainer, Color.DKGRAY)
        val colorOnSecondaryContainer = button.m3Color(MaterialR.attr.colorOnSecondaryContainer, Color.WHITE)
        val colorTertiary = button.m3Color(MaterialR.attr.colorTertiary, colorPrimary)
        val colorTertiaryContainer = button.m3Color(MaterialR.attr.colorTertiaryContainer, colorSecondaryContainer)
        val colorOnTertiaryContainer = button.m3Color(MaterialR.attr.colorOnTertiaryContainer, colorOnSecondaryContainer)
        val colorSurfaceContainerHigh = button.m3Color(MaterialR.attr.colorSurfaceContainerHigh, Color.GRAY)
        val colorOnSurfaceVariant = button.m3Color(MaterialR.attr.colorOnSurfaceVariant, Color.LTGRAY)

        when {
            isActive && isTertiaryAccent ->
                button.applyPillStyle(colorTertiaryContainer, colorOnTertiaryContainer, colorOnTertiaryContainer, colorTertiary, stroke15dp, radius12dp)
            isActive ->
                button.applyPillStyle(colorSecondaryContainer, colorOnSecondaryContainer, colorOnSecondaryContainer, Color.TRANSPARENT, 0, radius12dp)
            else ->
                button.applyPillStyle(colorSurfaceContainerHigh, colorOnSurfaceVariant, colorOnSurfaceVariant, Color.TRANSPARENT, 0, radius14dp)
        }
    }

    /**
     * Applies Material 3 Expressive Bento Matrix styling to the Focus Peaking quick-toggle badge.
     */
    @JvmStatic
    fun styleFocusPeakingBadge(button: MaterialButton, isActive: Boolean) {
        val density = button.resources.displayMetrics.density
        val radius13dp = (13f * density).roundToInt()
        val radius9dp = (9f * density).roundToInt()
        val stroke1dp = (1f * density).roundToInt()
        val stroke15dp = (1.5f * density).roundToInt()

        val colorTertiaryContainer = button.m3Color(MaterialR.attr.colorTertiaryContainer, Color.parseColor("#2E7D32"))
        val colorOnTertiaryContainer = button.m3Color(MaterialR.attr.colorOnTertiaryContainer, Color.WHITE)
        val colorTertiary = button.m3Color(MaterialR.attr.colorTertiary, Color.parseColor("#00E676"))
        val colorSurface = button.m3Color(MaterialR.attr.colorSurface, Color.WHITE)
        val colorOnSurface = button.m3Color(MaterialR.attr.colorOnSurface, Color.BLACK)
        val colorOutlineVariant = button.m3Color(MaterialR.attr.colorOutlineVariant, Color.LTGRAY)

        button.includeFontPadding = false
        button.insetTop = 0
        button.insetBottom = 0
        button.minWidth = 0
        button.minimumWidth = 0
        button.minHeight = 0
        button.minimumHeight = 0
        button.iconPadding = 0

        if (isActive) {
            button.cornerRadius = radius9dp
            button.backgroundTintList = ColorStateList.valueOf(colorTertiaryContainer)
            button.setTextColor(colorOnTertiaryContainer)
            button.strokeColor = ColorStateList.valueOf(colorTertiary)
            button.strokeWidth = stroke15dp
            button.elevation = 6f * density
        } else {
            button.cornerRadius = radius13dp
            button.backgroundTintList = ColorStateList.valueOf(colorSurface)
            button.setTextColor(colorOnSurface)
            button.strokeColor = ColorStateList.valueOf(colorOutlineVariant)
            button.strokeWidth = stroke1dp
            button.elevation = 4f * density
        }
    }

    /**
     * Applies Material 3 Expressive styling to capture mode switch button.
     */
    @JvmStatic
    fun styleModeSwitchButton(button: MaterialButton, mode: CaptureMode) {
        val density = button.resources.displayMetrics.density
        val radius12dp = (12f * density).roundToInt()
        val radius18dp = (18f * density).roundToInt()
        val stroke15dp = (1.5f * density).roundToInt()

        val colorPrimary = button.m3Color(android.R.attr.colorPrimary, Color.WHITE)
        val colorSecondary = button.m3Color(MaterialR.attr.colorSecondary, colorPrimary)
        val colorSecondaryContainer = button.m3Color(MaterialR.attr.colorSecondaryContainer, Color.DKGRAY)
        val colorOnSecondaryContainer = button.m3Color(MaterialR.attr.colorOnSecondaryContainer, Color.WHITE)
        val colorTertiary = button.m3Color(MaterialR.attr.colorTertiary, colorPrimary)
        val colorTertiaryContainer = button.m3Color(MaterialR.attr.colorTertiaryContainer, colorSecondaryContainer)
        val colorOnTertiaryContainer = button.m3Color(MaterialR.attr.colorOnTertiaryContainer, colorOnSecondaryContainer)
        val colorSurfaceContainerHigh = button.m3Color(MaterialR.attr.colorSurfaceContainerHigh, Color.GRAY)
        val colorOnSurfaceVariant = button.m3Color(MaterialR.attr.colorOnSurfaceVariant, Color.LTGRAY)

        when {
            mode.isHalfFrame -> {
                button.backgroundTintList = ColorStateList.valueOf(colorTertiaryContainer)
                button.iconTint = ColorStateList.valueOf(colorOnTertiaryContainer)
                button.strokeColor = ColorStateList.valueOf(colorTertiary)
                button.strokeWidth = stroke15dp
                button.cornerRadius = radius12dp
            }
            mode == CaptureMode.MULTI_CAMERA -> {
                button.backgroundTintList = ColorStateList.valueOf(colorSecondaryContainer)
                button.iconTint = ColorStateList.valueOf(colorOnSecondaryContainer)
                button.strokeColor = ColorStateList.valueOf(colorSecondary)
                button.strokeWidth = stroke15dp
                button.cornerRadius = radius12dp
            }
            else -> {
                button.backgroundTintList = ColorStateList.valueOf(colorSurfaceContainerHigh)
                button.iconTint = ColorStateList.valueOf(colorOnSurfaceVariant)
                button.strokeColor = ColorStateList.valueOf(Color.TRANSPARENT)
                button.strokeWidth = 0
                button.cornerRadius = radius18dp
            }
        }
    }

    @JvmStatic
    fun styleModeSwitchButton(button: MaterialButton, isSpecialMode: Boolean) {
        styleModeSwitchButton(button, if (isSpecialMode) CaptureMode.HALF_FRAME_SBS else CaptureMode.NORMAL)
    }

    /**
     * Applies dynamic expressive container styling to the bottom island card.
     */
    @JvmStatic
    fun styleDeckCard(card: View, mode: CaptureMode) {
        if (card is BentoTrayCard) {
            card.setCaptureMode(mode, animated = false)
            return
        }
        val cardView = card as? MaterialCardView ?: return
        val targetColor = when {
            mode.isHalfFrame -> cardView.m3Color(MaterialR.attr.colorTertiaryContainer, Color.DKGRAY)
            mode == CaptureMode.MULTI_CAMERA -> cardView.m3Color(MaterialR.attr.colorSecondaryContainer, Color.DKGRAY)
            else -> cardView.m3Color(MaterialR.attr.colorSurfaceContainer, Color.DKGRAY)
        }
        cardView.setCardBackgroundColor(ColorStateList.valueOf(targetColor))
        cardView.strokeWidth = 0
    }

    /**
     * Applies Material 3 Expressive Bento styling to Row 1 manual Pro telemetry buttons.
     */
    @JvmStatic
    fun styleBentoProPill(button: MaterialButton, isTabActive: Boolean, isValueManual: Boolean) {
        val density = button.resources.displayMetrics.density
        val stroke1dp = (1f * density).roundToInt()
        val stroke15dp = (1.5f * density).roundToInt()
        val radius10dp = (10f * density).roundToInt()
        val radius12dp = (12f * density).roundToInt()
        val radius14dp = (14f * density).roundToInt()

        val colorPrimary = button.m3Color(android.R.attr.colorPrimary, Color.WHITE)
        val colorPrimaryContainer = button.m3Color(MaterialR.attr.colorPrimaryContainer, Color.DKGRAY)
        val colorOnPrimaryContainer = button.m3Color(MaterialR.attr.colorOnPrimaryContainer, Color.WHITE)
        val colorSecondaryContainer = button.m3Color(MaterialR.attr.colorSecondaryContainer, Color.DKGRAY)
        val colorSurfaceContainerHigh = button.m3Color(MaterialR.attr.colorSurfaceContainerHigh, Color.GRAY)
        val colorOnSurface = button.m3Color(MaterialR.attr.colorOnSurface, Color.WHITE)
        val colorOutlineVariant = button.m3Color(MaterialR.attr.colorOutlineVariant, Color.parseColor("#4DFFFFFF"))

        when {
            isTabActive -> {
                button.backgroundTintList = ColorStateList.valueOf(colorPrimaryContainer)
                button.strokeColor = ColorStateList.valueOf(colorPrimary)
                button.strokeWidth = stroke15dp
                button.cornerRadius = radius10dp
                button.setTextColor(colorOnPrimaryContainer)
            }
            isValueManual -> {
                button.backgroundTintList = ColorStateList.valueOf(colorSecondaryContainer)
                button.strokeColor = ColorStateList.valueOf(ColorUtils.setAlphaComponent(colorPrimary, 180))
                button.strokeWidth = stroke15dp
                button.cornerRadius = radius12dp
                button.setTextColor(colorPrimary)
            }
            else -> {
                button.backgroundTintList = ColorStateList.valueOf(colorSurfaceContainerHigh)
                button.strokeColor = ColorStateList.valueOf(colorOutlineVariant)
                button.strokeWidth = stroke1dp
                button.cornerRadius = radius14dp
                button.setTextColor(colorOnSurface)
            }
        }
    }

    /**
     * Applies Bento styling to the Multi-Camera Pro Sync Link button.
     */
    @JvmStatic
    fun styleBentoProLink(button: MaterialButton, isLinked: Boolean) {
        val density = button.resources.displayMetrics.density
        val stroke1dp = (1f * density).roundToInt()
        val stroke15dp = (1.5f * density).roundToInt()
        val radius14dp = (14f * density).roundToInt()

        val colorPrimary = button.m3Color(android.R.attr.colorPrimary, Color.WHITE)
        val colorSecondaryContainer = button.m3Color(MaterialR.attr.colorSecondaryContainer, Color.DKGRAY)
        val colorSurfaceContainerHigh = button.m3Color(MaterialR.attr.colorSurfaceContainerHigh, Color.GRAY)
        val colorOutlineVariant = button.m3Color(MaterialR.attr.colorOutlineVariant, Color.parseColor("#4DFFFFFF"))
        val colorOnSurfaceVariant = button.m3Color(MaterialR.attr.colorOnSurfaceVariant, Color.LTGRAY)

        button.cornerRadius = radius14dp
        if (isLinked) {
            button.setIconResource(R.drawable.ic_link)
            button.iconTint = ColorStateList.valueOf(colorPrimary)
            button.strokeWidth = stroke15dp
            button.strokeColor = ColorStateList.valueOf(ColorUtils.setAlphaComponent(colorPrimary, 180))
            button.backgroundTintList = ColorStateList.valueOf(colorSecondaryContainer)
        } else {
            button.setIconResource(R.drawable.ic_link_off)
            button.iconTint = ColorStateList.valueOf(colorOnSurfaceVariant)
            button.strokeWidth = stroke1dp
            button.strokeColor = ColorStateList.valueOf(colorOutlineVariant)
            button.backgroundTintList = ColorStateList.valueOf(colorSurfaceContainerHigh)
        }
    }

    /**
     * Creates an ultra-compact two-line formatted label for 4-tier segmented buttons.
     */
    @JvmStatic
    @JvmOverloads
    fun formatCompactTierLabel(
        context: Context,
        title: String,
        subtitle: String = "",
        isActive: Boolean = false
    ): CharSequence {
        val text = if (subtitle.isNotEmpty()) "$title\n$subtitle" else title
        val builder = SpannableStringBuilder(text)
        val dm = context.resources.displayMetrics
        val titlePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10f, dm).roundToInt()
        val subPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 9f, dm).roundToInt()

        val colorOnSurface = MaterialColors.getColor(context, MaterialR.attr.colorOnSurface, Color.BLACK)
        val colorOnSurfaceVariant = MaterialColors.getColor(context, MaterialR.attr.colorOnSurfaceVariant, Color.DKGRAY)
        val colorOnPrimaryContainer = MaterialColors.getColor(context, MaterialR.attr.colorOnPrimaryContainer, Color.WHITE)

        val (titleColor, subColor) = if (isActive) {
            colorOnPrimaryContainer to ColorUtils.setAlphaComponent(colorOnPrimaryContainer, 220)
        } else {
            colorOnSurface to colorOnSurfaceVariant
        }

        if (title.isNotEmpty()) {
            builder.applyLineStyle(0, title.length, titlePx, titleColor, letterSpacing = 0.04f)
        }
        val subStart = title.length + 1
        if (subStart < text.length) {
            builder.applyLineStyle(subStart, text.length, subPx, subColor)
        }
        return builder
    }

    /**
     * Strips `.cube` extension and returns clean best-effort recipe label.
     */
    @JvmStatic
    @JvmOverloads
    fun formatRecipeLabel(rawName: String?, fallback: String = "None"): String {
        if (rawName.isNullOrBlank()) return fallback
        val trimmed = rawName.trim()
        if (trimmed.equals("None", ignoreCase = true)) return fallback
        val stripped = if (trimmed.endsWith(".cube", ignoreCase = true)) {
            trimmed.substring(0, trimmed.length - 5).trim()
        } else {
            trimmed
        }
        return if (stripped.isNotBlank()) stripped else fallback
    }

    /**
     * Creates a sleek two-line formatted telemetry text using SpannableStringBuilder.
     */
    @JvmStatic
    @JvmOverloads
    fun formatProTelemetry(
        label: String,
        value: String,
        isTabActive: Boolean = false,
        isValueManual: Boolean = false,
        context: Context,
        maxChars: Int = 16
    ): CharSequence {
        val truncatedValue = if (maxChars > 0 && value.length > maxChars && !value.endsWith("…")) {
            value.take(maxChars - 1) + "…"
        } else {
            value
        }
        val text = "$label\n$truncatedValue"
        val builder = SpannableStringBuilder(text)
        val dm = context.resources.displayMetrics
        val labelPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 9.5f, dm).roundToInt()
        val valuePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11.5f, dm).roundToInt()

        val colorPrimary = MaterialColors.getColor(context, android.R.attr.colorPrimary, Color.WHITE)
        val colorOnPrimaryContainer = MaterialColors.getColor(context, MaterialR.attr.colorOnPrimaryContainer, Color.WHITE)
        val colorOnSurface = MaterialColors.getColor(context, MaterialR.attr.colorOnSurface, Color.WHITE)
        val colorOnSurfaceVariant = MaterialColors.getColor(context, MaterialR.attr.colorOnSurfaceVariant, Color.LTGRAY)

        val (labelColor, valueColor) = when {
            isTabActive -> ColorUtils.setAlphaComponent(colorOnPrimaryContainer, 215) to colorOnPrimaryContainer
            isValueManual -> ColorUtils.setAlphaComponent(colorPrimary, 215) to colorPrimary
            else -> colorOnSurfaceVariant to colorOnSurface
        }

        if (label.isNotEmpty()) {
            builder.applyLineStyle(0, label.length, labelPx, labelColor, letterSpacing = 0.08f)
        }
        val valueStart = label.length + 1
        if (valueStart < text.length) {
            builder.applyLineStyle(valueStart, text.length, valuePx, valueColor)
        }
        return builder
    }

    private fun View.m3Color(attr: Int, fallback: Int): Int =
        MaterialColors.getColor(this, attr, fallback)

    private fun MaterialButton.configureCompactTileButton() {
        gravity = Gravity.CENTER
        textAlignment = View.TEXT_ALIGNMENT_CENTER
        includeFontPadding = false
        insetTop = 0
        insetBottom = 0
        minHeight = 0
        minimumHeight = 0
        minWidth = 0
        minimumWidth = 0
        iconPadding = 0
        setPadding(0, 0, 0, 0)
    }

    private fun MaterialButton.applyPillStyle(
        bgColor: Int,
        textColor: Int,
        iconColor: Int,
        strokeColorValue: Int,
        strokeWidthPx: Int,
        cornerRadiusPx: Int
    ) {
        backgroundTintList = ColorStateList.valueOf(bgColor)
        setTextColor(textColor)
        iconTint = ColorStateList.valueOf(iconColor)
        strokeColor = ColorStateList.valueOf(strokeColorValue)
        strokeWidth = strokeWidthPx
        cornerRadius = cornerRadiusPx
    }

    private fun SpannableStringBuilder.applyLineStyle(
        start: Int,
        end: Int,
        sizePx: Int,
        color: Int,
        letterSpacing: Float? = null
    ) {
        setSpan(AbsoluteSizeSpan(sizePx, false), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        setSpan(StyleSpan(Typeface.BOLD), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (letterSpacing != null) {
            setSpan(ProLetterSpacingSpan(letterSpacing), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        setSpan(ForegroundColorSpan(color), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun applyLayoutWeight(button: MaterialButton, targetWeight: Float) {
        val lp = button.layoutParams
        if (lp is LinearLayout.LayoutParams && lp.weight != targetWeight) {
            lp.weight = targetWeight
            button.layoutParams = lp
        }
    }
}

private class ProLetterSpacingSpan(private val letterSpacing: Float) : MetricAffectingSpan() {
    override fun updateDrawState(tp: TextPaint) {
        tp.letterSpacing = letterSpacing
    }

    override fun updateMeasureState(textPaint: TextPaint) {
        textPaint.letterSpacing = letterSpacing
    }
}
