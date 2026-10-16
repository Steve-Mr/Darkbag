package top.maary.darkbag.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
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

    companion object {

        const val PREF_KEY_UI_TIER = "pref_camera_bento_ui_tier"

        /**
         * Dynamically calculates and applies Bento row heights based on screen height and aspect ratio.
         *
         * On standard screens (< 750dp or aspect ratio < 1.9):
         * - Pro bar: 42dp
         * - Recipe row: 48dp
         * - Lens row: 48dp
         * - Shutter row: 78dp
         *
         * On tall screens (>= 750dp or aspect ratio >= 1.9):
         * - Pro bar: 48dp
         * - Recipe row: 54dp
         * - Lens row: 54dp
         * - Shutter row: 88dp
         */
        @JvmStatic
        fun applyDynamicRowHeights(view: View) {
            val resources = view.resources
            val density = resources.displayMetrics.density
            val metrics = resources.displayMetrics
            val maxDimension = maxOf(metrics.heightPixels, metrics.widthPixels)
            val minDimension = minOf(metrics.heightPixels, metrics.widthPixels)
            val aspectRatio = if (minDimension > 0) maxDimension.toFloat() / minDimension.toFloat() else 1.77f
            val maxDp = maxOf(resources.configuration.screenHeightDp, resources.configuration.screenWidthDp)

            val isTallScreen = maxDp >= 750 || aspectRatio >= 1.9f

            val proBarDp = 48
            val recipeRowDp = if (isTallScreen) 54 else 48
            val lensRowDp = if (isTallScreen) 54 else 48
            val shutterRowDp = if (isTallScreen) 88 else 78

            val proBarPx = (proBarDp * density).roundToInt()
            val recipeRowPx = (recipeRowDp * density).roundToInt()
            val lensRowPx = (lensRowDp * density).roundToInt()
            val shutterRowPx = (shutterRowDp * density).roundToInt()

            view.findViewById<View>(top.maary.darkbag.R.id.pro_info_bar_card)?.let { v ->
                val lp = v.layoutParams
                if (lp != null && lp.height != proBarPx) {
                    lp.height = proBarPx
                    v.layoutParams = lp
                }
            }
            view.findViewById<View>(top.maary.darkbag.R.id.bento_film_row)?.let { v ->
                val lp = v.layoutParams
                if (lp != null && lp.height != recipeRowPx) {
                    lp.height = recipeRowPx
                    v.layoutParams = lp
                }
            }
            view.findViewById<View>(top.maary.darkbag.R.id.lens_control_row)?.let { v ->
                val lp = v.layoutParams
                if (lp != null && lp.height != lensRowPx) {
                    lp.height = lensRowPx
                    v.layoutParams = lp
                }
            }
            view.findViewById<View>(top.maary.darkbag.R.id.bento_shutter_row)?.let { v ->
                val lp = v.layoutParams
                if (lp != null && lp.height != shutterRowPx) {
                    lp.height = shutterRowPx
                    v.layoutParams = lp
                }
            }
        }

        @JvmStatic
        fun applyDynamicRowHeights(binding: CameraUiContainerBinding) {
            val root = binding.bentoDeckView ?: binding.bottomIslandCard ?: binding.root
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
