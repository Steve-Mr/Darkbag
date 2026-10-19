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
import android.util.AttributeSet
import android.content.res.Resources
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.graphics.ColorUtils
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import top.maary.darkbag.databinding.CameraUiContainerBinding
import top.maary.darkbag.modes.CaptureMode
import top.maary.darkbag.viewmodel.CameraUiTier
import kotlin.math.roundToInt

/**
 * Composite controller view and Material 3 Expressive styling engine for Darkbag's
 * Modular Darkroom Bento Matrix camera interface.
 *
 * Encapsulates:
 * - Progressive disclosure across `P&S` (Point & Shoot), `FILM`, and `PRO` tiers.
 * - Dynamic M3 tonal color and squircle morphing for Physical vs. Digital Crop lenses.
 * - Anti-squeeze single-line truncation and tonal active states for the Base Log & 3D LUT row.
 */
class CameraBentoDeckView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    init {
        orientation = VERTICAL
        gravity = Gravity.BOTTOM
        clipToPadding = false
        clipChildren = false
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        applyDynamicRowHeights()
    }

    fun applyDynamicRowHeights() {
        applyDynamicRowHeights(this)
    }

    fun styleLensButton(button: MaterialButton, isActive: Boolean, isDigitalCrop: Boolean) {
        styleBentoLensButton(button, isActive, isDigitalCrop)
    }

    fun styleToggleTile(button: MaterialButton, isActive: Boolean, isTertiaryAccent: Boolean = false) {
        styleBentoToggleTile(button, isActive, isTertiaryAccent)
    }

    fun styleModeButton(button: MaterialButton, isSpecialMode: Boolean) {
        styleModeSwitchButton(button, isSpecialMode)
    }

    fun styleProPill(button: MaterialButton, isTabActive: Boolean, isValueManual: Boolean) {
        styleBentoProPill(button, isTabActive, isValueManual)
    }

    fun styleProLink(button: MaterialButton, isLinked: Boolean) {
        styleBentoProLink(button, isLinked)
    }

    enum class BentoScreenTier {
        COMPACT,
        STANDARD,
        TALL;

        companion object {
            fun resolve(aspectRatio: Float, maxDp: Int): BentoScreenTier {
                return when {
                    aspectRatio < 1.88f || maxDp < 720 -> COMPACT
                    aspectRatio > 2.05f && maxDp >= 840 -> TALL
                    else -> STANDARD
                }
            }
        }
    }

    data class BentoDimensions(
        val shutterRowDp: Int,
        val lensRowDp: Int,
        val recipeRowDp: Int,
        val proBarDp: Int,
        val dialWheelDp: Int,
        val cardPaddingDp: Int,
        val rowMarginDp: Int
    )

    companion object {

        const val PREF_KEY_UI_TIER = "pref_camera_bento_ui_tier"

        @JvmStatic
        fun resolveScreenTier(resources: Resources): BentoScreenTier {
            val metrics = resources.displayMetrics
            val maxDimension = maxOf(metrics.heightPixels, metrics.widthPixels)
            val minDimension = minOf(metrics.heightPixels, metrics.widthPixels)
            val aspectRatio = if (minDimension > 0) maxDimension.toFloat() / minDimension.toFloat() else 1.77f
            val maxDp = maxOf(resources.configuration.screenHeightDp, resources.configuration.screenWidthDp)
            return BentoScreenTier.resolve(aspectRatio, maxDp)
        }

        @JvmStatic
        fun getBentoDimensions(resources: Resources): BentoDimensions {
            val tier = resolveScreenTier(resources)
            return when (tier) {
                BentoScreenTier.COMPACT -> BentoDimensions(
                    shutterRowDp = 68,
                    lensRowDp = 38,
                    recipeRowDp = 38,
                    proBarDp = 38,
                    dialWheelDp = 38,
                    cardPaddingDp = 5,
                    rowMarginDp = 3
                )
                BentoScreenTier.STANDARD -> BentoDimensions(
                    shutterRowDp = 78,
                    lensRowDp = 48,
                    recipeRowDp = 48,
                    proBarDp = 44,
                    dialWheelDp = 44,
                    cardPaddingDp = 8,
                    rowMarginDp = 5
                )
                BentoScreenTier.TALL -> BentoDimensions(
                    shutterRowDp = 88,
                    lensRowDp = 54,
                    recipeRowDp = 54,
                    proBarDp = 48,
                    dialWheelDp = 46,
                    cardPaddingDp = 10,
                    rowMarginDp = 6
                )
            }
        }

        /**
         * Adaptive 3-tier Bento row heights and spacing based on screen height and aspect ratio:
         *
         * 1. COMPACT tier (aspectRatio < 1.88f or maxDp < 720dp, e.g. 16:9, split screen, compact devices):
         *    - Pro info bar: 38dp
         *    - Recipe row: 38dp
         *    - Lens row: 38dp
         *    - Shutter row: 68dp
         *    - Dial wheel: 38dp
         *    - Card padding: 5dp
         *    - Row bottom margin: 3dp
         *
         * 2. STANDARD tier (aspectRatio in 1.88f..2.05f and maxDp in 720..840dp, e.g. 18:9, 19:9):
         *    - Pro info bar: 44dp
         *    - Recipe row: 48dp
         *    - Lens row: 48dp
         *    - Shutter row: 78dp
         *    - Dial wheel: 44dp
         *    - Card padding: 8dp
         *    - Row bottom margin: 5dp
         *
         * 3. TALL tier (aspectRatio > 2.05f and maxDp >= 840dp, e.g. 20:9, 21:9 modern flagships):
         *    - Pro info bar: 48dp
         *    - Recipe row: 54dp
         *    - Lens row: 54dp
         *    - Shutter row: 88dp
         *    - Dial wheel: 46dp
         *    - Card padding: 10dp
         *    - Row bottom margin: 6dp
         */
        @JvmStatic
        fun applyDynamicRowHeights(view: View) {
            val resources = view.resources
            val density = resources.displayMetrics.density
            val dims = getBentoDimensions(resources)
            val tier = resolveScreenTier(resources)

            val proBarPx = (dims.proBarDp * density).roundToInt()
            val recipeRowPx = (dims.recipeRowDp * density).roundToInt()
            val lensRowPx = (dims.lensRowDp * density).roundToInt()
            val shutterRowPx = (dims.shutterRowDp * density).roundToInt()
            val dialWheelPx = (dims.dialWheelDp * density).roundToInt()
            val cardPaddingPx = (dims.cardPaddingDp * density).roundToInt()
            val rowMarginPx = (dims.rowMarginDp * density).roundToInt()

            // 1. Pro parameter bar
            view.findViewById<View>(top.maary.darkbag.R.id.pro_info_bar_card)?.let { v ->
                val lp = v.layoutParams as? ViewGroup.MarginLayoutParams
                if (lp != null) {
                    var changed = false
                    if (lp.height != proBarPx) {
                        lp.height = proBarPx
                        changed = true
                    }
                    if (lp.bottomMargin != rowMarginPx) {
                        lp.bottomMargin = rowMarginPx
                        changed = true
                    }
                    if (changed) v.layoutParams = lp
                }
            }

            // 2. Dial wheel panel & view
            view.findViewById<View>(top.maary.darkbag.R.id.manual_controls_root)?.let { v ->
                val lp = v.layoutParams as? ViewGroup.MarginLayoutParams
                if (lp != null && lp.bottomMargin != rowMarginPx) {
                    lp.bottomMargin = rowMarginPx
                    v.layoutParams = lp
                }
            }
            view.findViewById<View>(top.maary.darkbag.R.id.dial_wheel_view)?.let { v ->
                val lp = v.layoutParams
                if (lp != null && lp.height != dialWheelPx) {
                    lp.height = dialWheelPx
                    v.layoutParams = lp
                }
            }

            // 3. Film recipe row
            view.findViewById<View>(top.maary.darkbag.R.id.bento_film_row)?.let { v ->
                val lp = v.layoutParams as? ViewGroup.MarginLayoutParams
                if (lp != null) {
                    var changed = false
                    if (lp.height != recipeRowPx) {
                        lp.height = recipeRowPx
                        changed = true
                    }
                    if (lp.bottomMargin != rowMarginPx) {
                        lp.bottomMargin = rowMarginPx
                        changed = true
                    }
                    if (changed) v.layoutParams = lp
                }
            }

            // 4. Lens control row
            view.findViewById<View>(top.maary.darkbag.R.id.lens_control_row)?.let { v ->
                val lp = v.layoutParams as? ViewGroup.MarginLayoutParams
                if (lp != null) {
                    var changed = false
                    if (lp.height != lensRowPx) {
                        lp.height = lensRowPx
                        changed = true
                    }
                    if (lp.bottomMargin != rowMarginPx) {
                        lp.bottomMargin = rowMarginPx
                        changed = true
                    }
                    if (changed) v.layoutParams = lp
                }
            }

            // 5. Shutter row
            view.findViewById<View>(top.maary.darkbag.R.id.bento_shutter_row)?.let { v ->
                val lp = v.layoutParams
                if (lp != null && lp.height != shutterRowPx) {
                    lp.height = shutterRowPx
                    v.layoutParams = lp
                }
            }

            // 6. Photo thumbnail card width adjustment for compact tier
            view.findViewById<View>(top.maary.darkbag.R.id.photo_view_card)?.let { v ->
                val lp = v.layoutParams
                val photoWidthDp = when (tier) {
                    BentoScreenTier.COMPACT -> 66
                    BentoScreenTier.TALL -> 76
                    BentoScreenTier.STANDARD -> 72
                }
                val photoWidthPx = (photoWidthDp * density).roundToInt()
                if (lp != null && lp.width != photoWidthPx) {
                    lp.width = photoWidthPx
                    v.layoutParams = lp
                }
            }

            // 7. Right Bento stack width adjustment for compact tier
            view.findViewById<View>(top.maary.darkbag.R.id.bento_right_stack)?.let { v ->
                val lp = v.layoutParams
                val stackWidthDp = when (tier) {
                    BentoScreenTier.COMPACT -> 92
                    BentoScreenTier.STANDARD -> 98
                    BentoScreenTier.TALL -> 102
                }
                val stackWidthPx = (stackWidthDp * density).roundToInt()
                if (lp != null && lp.width != stackWidthPx) {
                    lp.width = stackWidthPx
                    v.layoutParams = lp
                }
            }

            // 8. Capture button size adjustment for compact tier
            view.findViewById<View>(top.maary.darkbag.R.id.camera_capture_button)?.let { v ->
                val lp = v.layoutParams
                val shutterBtnDp = when (tier) {
                    BentoScreenTier.COMPACT -> 60
                    BentoScreenTier.STANDARD -> 68
                    BentoScreenTier.TALL -> 72
                }
                val shutterBtnPx = (shutterBtnDp * density).roundToInt()
                if (lp != null && (lp.width != shutterBtnPx || lp.height != shutterBtnPx)) {
                    lp.width = shutterBtnPx
                    lp.height = shutterBtnPx
                    v.layoutParams = lp
                }
            }

            // 9. Outer Card Padding
            val cardView = (view as? com.google.android.material.card.MaterialCardView)
                ?: (view.parent as? com.google.android.material.card.MaterialCardView)
                ?: (view.findViewById<View>(top.maary.darkbag.R.id.bottom_island_card) as? com.google.android.material.card.MaterialCardView)
            cardView?.setContentPadding(cardPaddingPx, cardPaddingPx, cardPaddingPx, cardPaddingPx)
        }

        @JvmStatic
        fun applyDynamicRowHeights(binding: CameraUiContainerBinding) {
            val root = binding.bottomIslandCard ?: binding.bentoDeckView ?: binding.root
            applyDynamicRowHeights(root)
        }

        /**
         * Updates the Bento Matrix deck visibility and tier switcher button styling for
         * `P&S` (Point & Shoot), `FILM`, and `PRO` modes.
         *
         * Notably, detailed exposure telemetry (AF/MF, ISO, Shutter, EV) is strictly hidden
         * in `P&S` and `FILM` modes and only revealed in `PRO` mode.
         */
        @JvmStatic
        fun bindTierState(
            binding: CameraUiContainerBinding,
            tier: CameraUiTier,
            isAnyManual: Boolean = false,
            hasActiveManualTab: Boolean = false,
            showLutSwitcherPref: Boolean = true
        ) {
            val root = binding.bentoDeckView ?: binding.bottomIslandCard ?: return
            val density = root.resources.displayMetrics.density

            val colorPrimary = MaterialColors.getColor(
                root,
                android.R.attr.colorPrimary,
                Color.WHITE
            )
            val colorPrimaryContainer = MaterialColors.getColor(
                root,
                com.google.android.material.R.attr.colorPrimaryContainer,
                Color.DKGRAY
            )
            val colorOnPrimaryContainer = MaterialColors.getColor(
                root,
                com.google.android.material.R.attr.colorOnPrimaryContainer,
                Color.WHITE
            )
            val colorSecondaryContainer = MaterialColors.getColor(
                root,
                com.google.android.material.R.attr.colorSecondaryContainer,
                Color.DKGRAY
            )
            val colorOnSecondaryContainer = MaterialColors.getColor(
                root,
                com.google.android.material.R.attr.colorOnSecondaryContainer,
                Color.WHITE
            )
            val colorSurfaceContainerHighest = MaterialColors.getColor(
                root,
                com.google.android.material.R.attr.colorSurfaceContainerHighest,
                Color.GRAY
            )
            val colorOnSurface = MaterialColors.getColor(
                root,
                com.google.android.material.R.attr.colorOnSurface,
                Color.WHITE
            )
            val colorInverseSurface = MaterialColors.getColor(
                root,
                com.google.android.material.R.attr.colorSurfaceInverse,
                Color.DKGRAY
            )
            val colorInverseOnSurface = MaterialColors.getColor(
                root,
                com.google.android.material.R.attr.colorOnSurfaceInverse,
                Color.WHITE
            )

            binding.btnBentoTier?.let { btn ->
                btn.text = tier.displayLabel
                when (tier) {
                    CameraUiTier.POINT_AND_SHOOT -> {
                        btn.backgroundTintList = ColorStateList.valueOf(colorSurfaceContainerHighest)
                        btn.setTextColor(colorOnSurface)
                        btn.cornerRadius = (14f * density).roundToInt()
                    }
                    CameraUiTier.FILM -> {
                        btn.backgroundTintList = ColorStateList.valueOf(colorSecondaryContainer)
                        btn.setTextColor(colorOnSecondaryContainer)
                        btn.cornerRadius = (12f * density).roundToInt()
                    }
                    CameraUiTier.PRO -> {
                        btn.backgroundTintList = ColorStateList.valueOf(colorInverseSurface)
                        btn.setTextColor(colorInverseOnSurface)
                        btn.cornerRadius = (10f * density).roundToInt()
                    }
                }
                if (isAnyManual) {
                    btn.strokeWidth = (1.5f * density).roundToInt()
                    btn.strokeColor = ColorStateList.valueOf(colorPrimary)
                } else {
                    btn.strokeWidth = 0
                }
            }

            // Row B: Film Recipe + Quick Switches (visible in FILM and PRO; hidden in P&S)
            val showFilmRow = (tier == CameraUiTier.FILM || tier == CameraUiTier.PRO) && !hasActiveManualTab
            binding.bentoFilmRow?.visibility = if (showFilmRow) View.VISIBLE else View.GONE
            binding.bentoLogButton?.visibility = if (showFilmRow && showLutSwitcherPref) View.VISIBLE else View.GONE
            binding.lutSwitcherButton?.visibility = if (showFilmRow && showLutSwitcherPref) View.VISIBLE else View.GONE

            // Row A: Pro Telemetry & Manual Dial Module (ONLY visible in PRO mode!)
            val showPro = (tier == CameraUiTier.PRO)
            binding.bentoProModule?.visibility = if (showPro) View.VISIBLE else View.GONE
            binding.proInfoBarCard?.visibility = if (showPro) View.VISIBLE else View.GONE
            binding.manualControlsRoot?.visibility = if (showPro && hasActiveManualTab) View.VISIBLE else View.GONE
        }

        /**
         * Updates the Base Log and 3D LUT Bento tiles with best-effort single-line labels
         * and Material 3 dynamic active/inactive tonal styling.
         */
        @JvmStatic
        fun bindRecipeRow(
            binding: CameraUiContainerBinding,
            targetLogName: String?,
            activeLutName: String?
        ) {
            val isLogActive = !targetLogName.isNullOrBlank() && !targetLogName.equals("None", ignoreCase = true)
            val isLutActive = !activeLutName.isNullOrBlank() && !activeLutName.equals("None", ignoreCase = true)

            binding.bentoLogButton?.let { btn ->
                btn.text = if (isLogActive) targetLogName else "Log: Off"
                styleBentoToggleTile(btn, isActive = isLogActive, isTertiaryAccent = true)
            }

            binding.lutSwitcherButton?.let { btn ->
                btn.text = if (isLutActive) formatRecipeLabel(activeLutName, "LUT: Off") else "LUT: Off"
                styleBentoToggleTile(btn, isActive = isLutActive, isTertiaryAccent = true)
            }
        }

        /**
         * Styles the HDR+ Bento tile using Material 3 dynamic colors and squircle morphing.
         */
        @JvmStatic
        fun styleHdrPlusTile(binding: CameraUiContainerBinding, isHdrPlusEnabled: Boolean) {
            val card = binding.hdrPlusPill ?: return
            val label = binding.tvHdrStatus ?: return
            val density = card.resources.displayMetrics.density

            val colorPrimaryContainer = MaterialColors.getColor(
                card,
                com.google.android.material.R.attr.colorPrimaryContainer,
                Color.DKGRAY
            )
            val colorOnPrimaryContainer = MaterialColors.getColor(
                card,
                com.google.android.material.R.attr.colorOnPrimaryContainer,
                Color.WHITE
            )
            val colorSurfaceContainerHigh = MaterialColors.getColor(
                card,
                com.google.android.material.R.attr.colorSurfaceContainerHigh,
                Color.GRAY
            )
            val colorOnSurfaceVariant = MaterialColors.getColor(
                card,
                com.google.android.material.R.attr.colorOnSurfaceVariant,
                Color.LTGRAY
            )

            label.text = if (isHdrPlusEnabled) "HDR+" else "HDR"
            if (isHdrPlusEnabled) {
                card.setCardBackgroundColor(colorPrimaryContainer)
                label.setTextColor(colorOnPrimaryContainer)
                card.radius = 14f * density
            } else {
                card.setCardBackgroundColor(colorSurfaceContainerHigh)
                label.setTextColor(colorOnSurfaceVariant)
                card.radius = 16f * density
            }
        }

        /**
         * Applies Material 3 Expressive Bento styling to a camera lens button.
         *
         * - Physical Lens (active && !isDigitalCrop):
         *     Background: ?attr/colorSecondaryContainer
         *     Text & Stroke: ?attr/colorPrimary (stroke 1.5dp, squircle morph cornerRadius 12dp)
         *     Layout weight: 1.25f (if in a LinearLayout)
         * - Digital Crop Lens (active && isDigitalCrop):
         *     Background: ?attr/colorTertiaryContainer
         *     Text & Stroke: ?attr/colorTertiary (stroke 1.5dp, squircle cornerRadius 12dp)
         *     Layout weight: 1.25f (if in a LinearLayout)
         * - Inactive Lens:
         *     Background: ?attr/colorSurfaceContainerHigh
         *     Text: ?attr/colorOnSurfaceVariant (stroke 0dp, pill cornerRadius 14dp)
         *     Layout weight: 1.0f
         */
        @JvmStatic
        fun styleBentoLensButton(button: MaterialButton, isActive: Boolean, isDigitalCrop: Boolean) {
            val density = button.resources.displayMetrics.density
            val stroke1_5dp = (1.5f * density).roundToInt()
            val radius12dp = (12f * density).roundToInt()
            val radius14dp = (14f * density).roundToInt()

            val colorPrimary = MaterialColors.getColor(
                button,
                android.R.attr.colorPrimary,
                Color.WHITE
            )
            val colorSecondaryContainer = MaterialColors.getColor(
                button,
                com.google.android.material.R.attr.colorSecondaryContainer,
                Color.DKGRAY
            )
            val colorTertiary = MaterialColors.getColor(
                button,
                com.google.android.material.R.attr.colorTertiary,
                colorPrimary
            )
            val colorTertiaryContainer = MaterialColors.getColor(
                button,
                com.google.android.material.R.attr.colorTertiaryContainer,
                colorSecondaryContainer
            )
            val colorSurfaceContainerHigh = MaterialColors.getColor(
                button,
                com.google.android.material.R.attr.colorSurfaceContainerHigh,
                Color.GRAY
            )
            val colorOnSurfaceVariant = MaterialColors.getColor(
                button,
                com.google.android.material.R.attr.colorOnSurfaceVariant,
                Color.LTGRAY
            )

            when {
                isActive && !isDigitalCrop -> {
                    button.backgroundTintList = ColorStateList.valueOf(colorSecondaryContainer)
                    button.setTextColor(colorPrimary)
                    button.iconTint = ColorStateList.valueOf(colorPrimary)
                    button.strokeColor = ColorStateList.valueOf(colorPrimary)
                    button.strokeWidth = stroke1_5dp
                    button.cornerRadius = radius12dp
                    applyLayoutWeight(button, 1.25f)
                }
                isActive && isDigitalCrop -> {
                    button.backgroundTintList = ColorStateList.valueOf(colorTertiaryContainer)
                    button.setTextColor(colorTertiary)
                    button.iconTint = ColorStateList.valueOf(colorTertiary)
                    button.strokeColor = ColorStateList.valueOf(colorTertiary)
                    button.strokeWidth = stroke1_5dp
                    button.cornerRadius = radius12dp
                    applyLayoutWeight(button, 1.25f)
                }
                else -> {
                    button.backgroundTintList = ColorStateList.valueOf(colorSurfaceContainerHigh)
                    button.setTextColor(colorOnSurfaceVariant)
                    button.iconTint = ColorStateList.valueOf(colorOnSurfaceVariant)
                    button.strokeWidth = 0
                    button.strokeColor = ColorStateList.valueOf(Color.TRANSPARENT)
                    button.cornerRadius = radius14dp
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
            val stroke1_5dp = (1.5f * density).roundToInt()

            val colorPrimary = MaterialColors.getColor(
                button,
                android.R.attr.colorPrimary,
                Color.WHITE
            )
            val colorSecondaryContainer = MaterialColors.getColor(
                button,
                com.google.android.material.R.attr.colorSecondaryContainer,
                Color.DKGRAY
            )
            val colorOnSecondaryContainer = MaterialColors.getColor(
                button,
                com.google.android.material.R.attr.colorOnSecondaryContainer,
                Color.WHITE
            )
            val colorTertiary = MaterialColors.getColor(
                button,
                com.google.android.material.R.attr.colorTertiary,
                colorPrimary
            )
            val colorTertiaryContainer = MaterialColors.getColor(
                button,
                com.google.android.material.R.attr.colorTertiaryContainer,
                colorSecondaryContainer
            )
            val colorOnTertiaryContainer = MaterialColors.getColor(
                button,
                com.google.android.material.R.attr.colorOnTertiaryContainer,
                colorOnSecondaryContainer
            )
            val colorSurfaceContainerHigh = MaterialColors.getColor(
                button,
                com.google.android.material.R.attr.colorSurfaceContainerHigh,
                Color.GRAY
            )
            val colorOnSurfaceVariant = MaterialColors.getColor(
                button,
                com.google.android.material.R.attr.colorOnSurfaceVariant,
                Color.LTGRAY
            )

            if (isActive) {
                if (isTertiaryAccent) {
                    button.backgroundTintList = ColorStateList.valueOf(colorTertiaryContainer)
                    button.setTextColor(colorOnTertiaryContainer)
                    button.iconTint = ColorStateList.valueOf(colorOnTertiaryContainer)
                    button.strokeColor = ColorStateList.valueOf(colorTertiary)
                    button.strokeWidth = stroke1_5dp
                    button.cornerRadius = radius12dp
                } else {
                    button.backgroundTintList = ColorStateList.valueOf(colorSecondaryContainer)
                    button.setTextColor(colorOnSecondaryContainer)
                    button.iconTint = ColorStateList.valueOf(colorOnSecondaryContainer)
                    button.strokeColor = ColorStateList.valueOf(Color.TRANSPARENT)
                    button.strokeWidth = 0
                    button.cornerRadius = radius12dp
                }
            } else {
                button.backgroundTintList = ColorStateList.valueOf(colorSurfaceContainerHigh)
                button.setTextColor(colorOnSurfaceVariant)
                button.iconTint = ColorStateList.valueOf(colorOnSurfaceVariant)
                button.strokeColor = ColorStateList.valueOf(Color.TRANSPARENT)
                button.strokeWidth = 0
                button.cornerRadius = radius14dp
            }
        }

        /**
         * Applies Material 3 Expressive styling to capture mode switch button.
         *
         * - Normal mode: colorSurfaceContainerHigh fill, colorOnSurfaceVariant icon, 0dp stroke, 14dp radius.
         * - Half-Frame / Multi-Camera: colorSecondaryContainer fill, colorOnSecondaryContainer icon, 1.5dp colorPrimary stroke, 12dp radius.
         */
        @JvmStatic
        fun styleModeSwitchButton(button: MaterialButton, isSpecialMode: Boolean) {
            val density = button.resources.displayMetrics.density
            val radius12dp = (12f * density).roundToInt()
            val radius18dp = (18f * density).roundToInt()
            val stroke1_5dp = (1.5f * density).roundToInt()

            val colorPrimary = MaterialColors.getColor(
                button,
                android.R.attr.colorPrimary,
                Color.WHITE
            )
            val colorSecondaryContainer = MaterialColors.getColor(
                button,
                com.google.android.material.R.attr.colorSecondaryContainer,
                Color.DKGRAY
            )
            val colorOnSecondaryContainer = MaterialColors.getColor(
                button,
                com.google.android.material.R.attr.colorOnSecondaryContainer,
                Color.WHITE
            )
            val colorSurfaceContainerHigh = MaterialColors.getColor(
                button,
                com.google.android.material.R.attr.colorSurfaceContainerHigh,
                Color.GRAY
            )
            val colorOnSurfaceVariant = MaterialColors.getColor(
                button,
                com.google.android.material.R.attr.colorOnSurfaceVariant,
                Color.LTGRAY
            )

            if (isSpecialMode) {
                button.backgroundTintList = ColorStateList.valueOf(colorSecondaryContainer)
                button.iconTint = ColorStateList.valueOf(colorOnSecondaryContainer)
                button.strokeColor = ColorStateList.valueOf(colorPrimary)
                button.strokeWidth = stroke1_5dp
                button.cornerRadius = radius12dp
            } else {
                button.backgroundTintList = ColorStateList.valueOf(colorSurfaceContainerHigh)
                button.iconTint = ColorStateList.valueOf(colorOnSurfaceVariant)
                button.strokeColor = ColorStateList.valueOf(Color.TRANSPARENT)
                button.strokeWidth = 0
                button.cornerRadius = radius18dp
            }
        }

        @JvmStatic
        fun styleModeSwitchButton(button: MaterialButton, mode: CaptureMode) {
            styleModeSwitchButton(button, mode != CaptureMode.NORMAL)
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
         * Applies Material 3 Expressive Bento styling to Row 1 manual Pro telemetry buttons.
         *
         * - Inactive (Auto parameter, dial closed):
         *     Background: colorSurfaceContainerHigh
         *     Stroke: 1dp colorOutlineVariant
         *     Corner Radius: 14dp
         *     Text Color: colorOnSurface
         * - Active / Tab Selected (dial wheel is currently open for this parameter):
         *     Background: colorPrimaryContainer
         *     Stroke: 1.5dp colorPrimary
         *     Corner Radius: 10dp (squircle morph!)
         *     Text Color: colorOnPrimaryContainer
         * - Manual Override (locked to manual value, dial closed):
         *     Background: colorSecondaryContainer
         *     Stroke: 1.5dp ColorUtils.setAlphaComponent(colorPrimary, 180)
         *     Corner Radius: 12dp
         *     Text Color: colorPrimary
         */
        @JvmStatic
        fun styleBentoProPill(button: MaterialButton, isTabActive: Boolean, isValueManual: Boolean) {
            val density = button.resources.displayMetrics.density
            val stroke1dp = (1f * density).roundToInt()
            val stroke1_5dp = (1.5f * density).roundToInt()
            val radius10dp = (10f * density).roundToInt()
            val radius12dp = (12f * density).roundToInt()
            val radius14dp = (14f * density).roundToInt()

            val colorPrimary = MaterialColors.getColor(
                button,
                android.R.attr.colorPrimary,
                Color.WHITE
            )
            val colorPrimaryContainer = MaterialColors.getColor(
                button,
                com.google.android.material.R.attr.colorPrimaryContainer,
                Color.DKGRAY
            )
            val colorOnPrimaryContainer = MaterialColors.getColor(
                button,
                com.google.android.material.R.attr.colorOnPrimaryContainer,
                Color.WHITE
            )
            val colorSecondaryContainer = MaterialColors.getColor(
                button,
                com.google.android.material.R.attr.colorSecondaryContainer,
                Color.DKGRAY
            )
            val colorSurfaceContainerHigh = MaterialColors.getColor(
                button,
                com.google.android.material.R.attr.colorSurfaceContainerHigh,
                Color.GRAY
            )
            val colorOnSurface = MaterialColors.getColor(
                button,
                com.google.android.material.R.attr.colorOnSurface,
                Color.WHITE
            )
            val colorOutlineVariant = MaterialColors.getColor(
                button,
                com.google.android.material.R.attr.colorOutlineVariant,
                Color.parseColor("#4DFFFFFF")
            )

            when {
                isTabActive -> {
                    button.backgroundTintList = ColorStateList.valueOf(colorPrimaryContainer)
                    button.strokeColor = ColorStateList.valueOf(colorPrimary)
                    button.strokeWidth = stroke1_5dp
                    button.cornerRadius = radius10dp
                    button.setTextColor(colorOnPrimaryContainer)
                }
                isValueManual -> {
                    val strokeColor = ColorUtils.setAlphaComponent(colorPrimary, 180)
                    button.backgroundTintList = ColorStateList.valueOf(colorSecondaryContainer)
                    button.strokeColor = ColorStateList.valueOf(strokeColor)
                    button.strokeWidth = stroke1_5dp
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
            val stroke1_5dp = (1.5f * density).roundToInt()
            val radius14dp = (14f * density).roundToInt()

            val colorPrimary = MaterialColors.getColor(
                button,
                android.R.attr.colorPrimary,
                Color.WHITE
            )
            val colorSecondaryContainer = MaterialColors.getColor(
                button,
                com.google.android.material.R.attr.colorSecondaryContainer,
                Color.DKGRAY
            )
            val colorSurfaceContainerHigh = MaterialColors.getColor(
                button,
                com.google.android.material.R.attr.colorSurfaceContainerHigh,
                Color.GRAY
            )
            val colorOutlineVariant = MaterialColors.getColor(
                button,
                com.google.android.material.R.attr.colorOutlineVariant,
                Color.parseColor("#4DFFFFFF")
            )
            val colorOnSurfaceVariant = MaterialColors.getColor(
                button,
                com.google.android.material.R.attr.colorOnSurfaceVariant,
                Color.LTGRAY
            )

            button.cornerRadius = radius14dp
            if (isLinked) {
                button.setIconResource(top.maary.darkbag.R.drawable.ic_link)
                button.iconTint = ColorStateList.valueOf(colorPrimary)
                button.strokeWidth = stroke1_5dp
                button.strokeColor = ColorStateList.valueOf(ColorUtils.setAlphaComponent(colorPrimary, 180))
                button.backgroundTintList = ColorStateList.valueOf(colorSecondaryContainer)
            } else {
                button.setIconResource(top.maary.darkbag.R.drawable.ic_link_off)
                button.iconTint = ColorStateList.valueOf(colorOnSurfaceVariant)
                button.strokeWidth = stroke1dp
                button.strokeColor = ColorStateList.valueOf(colorOutlineVariant)
                button.backgroundTintList = ColorStateList.valueOf(colorSurfaceContainerHigh)
            }
        }

        /**
         * Creates a sleek two-line formatted telemetry text using SpannableStringBuilder:
         * - Line 1: [label] (e.g. AF, ISO, SEC, EV) in 9.5sp, bold, letter-spacing.
         * - Line 2: [value] (e.g. Macro / 0.8m, 1330, 1/33s, 0.0) in 11.5sp, bold.
         */
        @JvmStatic
        @JvmOverloads
        fun formatProTelemetry(
            label: String,
            value: String,
            isTabActive: Boolean = false,
            isValueManual: Boolean = false,
            context: Context
        ): CharSequence {
            val text = "$label\n$value"
            val builder = SpannableStringBuilder(text)
            val displayMetrics = context.resources.displayMetrics
            val labelPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 9.5f, displayMetrics).roundToInt()
            val valuePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11.5f, displayMetrics).roundToInt()

            val colorPrimary = MaterialColors.getColor(
                context,
                android.R.attr.colorPrimary,
                Color.WHITE
            )
            val colorOnPrimaryContainer = MaterialColors.getColor(
                context,
                com.google.android.material.R.attr.colorOnPrimaryContainer,
                Color.WHITE
            )
            val colorOnSurface = MaterialColors.getColor(
                context,
                com.google.android.material.R.attr.colorOnSurface,
                Color.WHITE
            )
            val colorOnSurfaceVariant = MaterialColors.getColor(
                context,
                com.google.android.material.R.attr.colorOnSurfaceVariant,
                Color.LTGRAY
            )

            val (labelColor, valueColor) = when {
                isTabActive -> Pair(
                    ColorUtils.setAlphaComponent(colorOnPrimaryContainer, 215),
                    colorOnPrimaryContainer
                )
                isValueManual -> Pair(
                    ColorUtils.setAlphaComponent(colorPrimary, 215),
                    colorPrimary
                )
                else -> Pair(
                    colorOnSurfaceVariant,
                    colorOnSurface
                )
            }

            val labelEnd = label.length
            if (labelEnd > 0) {
                builder.setSpan(AbsoluteSizeSpan(labelPx, false), 0, labelEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                builder.setSpan(StyleSpan(Typeface.BOLD), 0, labelEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                builder.setSpan(ProLetterSpacingSpan(0.08f), 0, labelEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                builder.setSpan(ForegroundColorSpan(labelColor), 0, labelEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }

            val valueStart = labelEnd + 1
            val valueEnd = text.length
            if (valueStart < valueEnd) {
                builder.setSpan(AbsoluteSizeSpan(valuePx, false), valueStart, valueEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                builder.setSpan(StyleSpan(Typeface.BOLD), valueStart, valueEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                builder.setSpan(ForegroundColorSpan(valueColor), valueStart, valueEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }

            return builder
        }

        private fun applyLayoutWeight(button: MaterialButton, targetWeight: Float) {
            val lp = button.layoutParams
            if (lp is LayoutParams) {
                if (lp.weight != targetWeight) {
                    lp.weight = targetWeight
                    button.layoutParams = lp
                }
            }
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
