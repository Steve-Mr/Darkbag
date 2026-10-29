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
import com.google.android.material.card.MaterialCardView
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

    /**
     * Immutable snapshot of viewfinder deck component visibility.
     * Decouples P&S, FILM, and PRO presets from user custom switches.
     */
    data class DeckVisibilitySpec(
        val tier: CameraUiTier,
        val showProModule: Boolean = false,
        val showLogAndLut: Boolean = false,
        val showHdrPlusPill: Boolean = false,
        val showUnderexposure: Boolean = false,
        val showMotionPhoto: Boolean = false,
        val showModeSwitch: Boolean = true,
        val showLensControls: Boolean = true,
        val showCameraSwitch: Boolean = true,
        val showSettingsButton: Boolean = true,
        val enableFocusPeaking: Boolean = false
    ) {
        val showFilmRow: Boolean
            get() = showLogAndLut || showHdrPlusPill || showUnderexposure

        val showLensRow: Boolean
            get() = showModeSwitch || showLensControls || showCameraSwitch
    }

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

            // 8. Capture button & weighted container layout (Bento Tofu Block)
            view.findViewById<View>(top.maary.darkbag.R.id.camera_capture_button)?.let { v ->
                val isBento = view.id == top.maary.darkbag.R.id.bento_shutter_row ||
                    view.findViewById<View>(top.maary.darkbag.R.id.bento_shutter_row) != null
                if (isBento) {
                    val lp = v.layoutParams
                    if (lp != null && (lp.width != ViewGroup.LayoutParams.MATCH_PARENT || lp.height != ViewGroup.LayoutParams.MATCH_PARENT)) {
                        lp.width = ViewGroup.LayoutParams.MATCH_PARENT
                        lp.height = ViewGroup.LayoutParams.MATCH_PARENT
                        v.layoutParams = lp
                    }
                    (v.parent as? ViewGroup)?.let { parent ->
                        val parentLp = parent.layoutParams as? LinearLayout.LayoutParams
                        if (parentLp != null) {
                            var parentChanged = false
                            if (parentLp.height != ViewGroup.LayoutParams.MATCH_PARENT) {
                                parentLp.height = ViewGroup.LayoutParams.MATCH_PARENT
                                parentChanged = true
                            }
                            if (parentLp.weight != 1f) {
                                parentLp.weight = 1f
                                parentChanged = true
                            }
                            if (parentChanged) {
                                parent.layoutParams = parentLp
                            }
                        }
                    }
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
         * Resolves the canonical visibility specification for the given UI tier.
         * For presets (P&S, FILM, PRO), returns invariant tier definitions.
         * For CUSTOM, reads granular user preferences from SharedPreferences.
         */
        @JvmStatic
        fun resolveVisibilitySpec(
            prefs: android.content.SharedPreferences,
            tier: CameraUiTier
        ): DeckVisibilitySpec {
            return when (tier) {
                CameraUiTier.POINT_AND_SHOOT -> DeckVisibilitySpec(
                    tier = tier,
                    showProModule = false,
                    showLogAndLut = false,
                    showHdrPlusPill = false,
                    showUnderexposure = false,
                    showMotionPhoto = false,
                    showModeSwitch = true,
                    showLensControls = true,
                    showCameraSwitch = true,
                    showSettingsButton = true,
                    enableFocusPeaking = false
                )
                CameraUiTier.FILM -> DeckVisibilitySpec(
                    tier = tier,
                    showProModule = false,
                    showLogAndLut = true,
                    showHdrPlusPill = true,
                    showUnderexposure = true,
                    showMotionPhoto = true,
                    showModeSwitch = true,
                    showLensControls = true,
                    showCameraSwitch = true,
                    showSettingsButton = true,
                    enableFocusPeaking = false
                )
                CameraUiTier.PRO -> DeckVisibilitySpec(
                    tier = tier,
                    showProModule = true,
                    showLogAndLut = true,
                    showHdrPlusPill = true,
                    showUnderexposure = true,
                    showMotionPhoto = true,
                    showModeSwitch = true,
                    showLensControls = true,
                    showCameraSwitch = true,
                    showSettingsButton = true,
                    enableFocusPeaking = true
                )
                CameraUiTier.CUSTOM -> DeckVisibilitySpec(
                    tier = tier,
                    showProModule = prefs.getBoolean(top.maary.darkbag.fragments.SettingsFragment.KEY_MANUAL_CONTROLS, false),
                    showLogAndLut = prefs.getBoolean(top.maary.darkbag.fragments.SettingsFragment.KEY_SHOW_LUT_SWITCHER, true),
                    showHdrPlusPill = prefs.getBoolean(top.maary.darkbag.fragments.SettingsFragment.KEY_SHOW_HDR_PLUS_SWITCH, true),
                    showUnderexposure = prefs.getBoolean(top.maary.darkbag.fragments.SettingsFragment.KEY_SHOW_HDR_UNDEREXPOSURE_BUTTON, true),
                    showMotionPhoto = true,
                    showModeSwitch = prefs.getBoolean(top.maary.darkbag.fragments.SettingsFragment.KEY_SHOW_MODE_SWITCH_BUTTON, true),
                    showLensControls = prefs.getBoolean(top.maary.darkbag.fragments.SettingsFragment.KEY_SHOW_LENS_CONTROLS, true),
                    showCameraSwitch = prefs.getBoolean(top.maary.darkbag.fragments.SettingsFragment.KEY_SHOW_CAMERA_SWITCH_BUTTON, true),
                    showSettingsButton = prefs.getBoolean(top.maary.darkbag.fragments.SettingsFragment.KEY_SHOW_SETTINGS_BUTTON, true),
                    enableFocusPeaking = prefs.getBoolean(top.maary.darkbag.fragments.SettingsFragment.KEY_EXP_FOCUS_PEAKING, false)
                )
            }
        }

        /**
         * Updates the Bento Matrix deck visibility and tier switcher button styling for
         * `P&S` (Point & Shoot), `FILM`, and `PRO` modes.
         *
         * Notably, detailed exposure telemetry (AF/MF, ISO, Shutter, EV) is strictly hidden
         * in `P&S` and `FILM` modes and only revealed in `PRO` mode.
         */
        @JvmStatic
        @JvmOverloads
        fun bindTierState(
            binding: CameraUiContainerBinding,
            tier: CameraUiTier,
            isAnyManual: Boolean = false,
            hasActiveManualTab: Boolean = false,
            showLutSwitcherPref: Boolean = true,
            showManualControlsPref: Boolean = false,
            visibilitySpec: DeckVisibilitySpec? = null
        ) {
            val root = binding.bentoDeckView ?: binding.bottomIslandCard ?: return
            val density = root.resources.displayMetrics.density

            val spec = visibilitySpec ?: when (tier) {
                CameraUiTier.POINT_AND_SHOOT -> DeckVisibilitySpec(
                    tier = tier,
                    showProModule = false,
                    showLogAndLut = false,
                    showHdrPlusPill = false,
                    showUnderexposure = false,
                    showMotionPhoto = false,
                    showModeSwitch = true,
                    showLensControls = true,
                    showCameraSwitch = true,
                    showSettingsButton = true,
                    enableFocusPeaking = false
                )
                CameraUiTier.FILM -> DeckVisibilitySpec(
                    tier = tier,
                    showProModule = false,
                    showLogAndLut = true,
                    showHdrPlusPill = true,
                    showUnderexposure = true,
                    showMotionPhoto = true,
                    showModeSwitch = true,
                    showLensControls = true,
                    showCameraSwitch = true,
                    showSettingsButton = true,
                    enableFocusPeaking = false
                )
                CameraUiTier.PRO -> DeckVisibilitySpec(
                    tier = tier,
                    showProModule = true,
                    showLogAndLut = true,
                    showHdrPlusPill = true,
                    showUnderexposure = true,
                    showMotionPhoto = true,
                    showModeSwitch = true,
                    showLensControls = true,
                    showCameraSwitch = true,
                    showSettingsButton = true,
                    enableFocusPeaking = true
                )
                CameraUiTier.CUSTOM -> DeckVisibilitySpec(
                    tier = tier,
                    showProModule = showManualControlsPref,
                    showLogAndLut = showLutSwitcherPref,
                    showHdrPlusPill = true,
                    showUnderexposure = true,
                    showMotionPhoto = true,
                    showModeSwitch = true,
                    showLensControls = true,
                    showCameraSwitch = true,
                    showSettingsButton = true,
                    enableFocusPeaking = false
                )
            }

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
                    CameraUiTier.CUSTOM -> {
                        btn.backgroundTintList = ColorStateList.valueOf(colorPrimaryContainer)
                        btn.setTextColor(colorOnPrimaryContainer)
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

            // Row B: Film Recipe + Quick Switches (visible in FILM and PRO; in CUSTOM respects spec.showFilmRow; hidden in P&S)
            val showFilmRow = spec.showFilmRow && !hasActiveManualTab
            binding.bentoFilmRow?.visibility = if (showFilmRow) View.VISIBLE else View.GONE
            binding.bentoLogButton?.visibility = if (showFilmRow && spec.showLogAndLut) View.VISIBLE else View.GONE
            binding.lutSwitcherButton?.visibility = if (showFilmRow && spec.showLogAndLut) View.VISIBLE else View.GONE
            binding.hdrPlusPill?.visibility = if (showFilmRow && spec.showHdrPlusPill) View.VISIBLE else View.GONE

            // Row A: Pro Telemetry & Manual Dial Module (ONLY visible in PRO mode or CUSTOM with manual controls enabled, or manual override)
            val showPro = spec.showProModule || isAnyManual
            binding.bentoProModule?.visibility = if (showPro) View.VISIBLE else View.GONE
            binding.proInfoBarCard?.visibility = if (showPro) View.VISIBLE else View.GONE
            binding.manualControlsRoot?.visibility = if (showPro && hasActiveManualTab) View.VISIBLE else View.GONE
        }

        /**
         * Applies the full 4-row DeckVisibilitySpec to a CameraUiContainerBinding, including
         * dynamic layout weight and margin balancing so hidden weighted strips (e.g. Log/LUT
         * or Lens Controls) never leave empty horizontal holes in Row B, Row C, or Row D.
         */
        @JvmStatic
        @JvmOverloads
        fun applyVisibilitySpec(
            binding: CameraUiContainerBinding,
            spec: DeckVisibilitySpec,
            hasMultipleLenses: Boolean = true,
            isAnyManual: Boolean = false,
            hasActiveManualTab: Boolean = false,
            supportsMotionPhoto: Boolean = true,
            isFlashOrUnderexposureAvailable: Boolean = true
        ) {
            val root = binding.bentoDeckView ?: binding.bottomIslandCard ?: binding.root
            val density = root.resources.displayMetrics.density

            // 1. Style tier pill and apply Row A + base Row B state
            bindTierState(
                binding = binding,
                tier = spec.tier,
                isAnyManual = isAnyManual,
                hasActiveManualTab = hasActiveManualTab,
                showLutSwitcherPref = spec.showLogAndLut,
                showManualControlsPref = spec.showProModule,
                visibilitySpec = spec
            )

            // 2. Row B: Film Recipe & Quick Switches + Dynamic Weight Balancing
            val showUnderexposureTile = spec.showUnderexposure && isFlashOrUnderexposureAvailable
            val showFilmRow = (spec.showLogAndLut || spec.showHdrPlusPill || showUnderexposureTile) && !hasActiveManualTab
            binding.bentoFilmRow?.visibility = if (showFilmRow) View.VISIBLE else View.GONE
            binding.bentoLogButton?.visibility = if (showFilmRow && spec.showLogAndLut) View.VISIBLE else View.GONE
            binding.lutSwitcherButton?.visibility = if (showFilmRow && spec.showLogAndLut) View.VISIBLE else View.GONE
            binding.hdrPlusPill?.visibility = if (showFilmRow && spec.showHdrPlusPill) View.VISIBLE else View.GONE
            binding.flashButton?.visibility = if (showFilmRow && showUnderexposureTile) View.VISIBLE else View.GONE
            val showMotionTile = showFilmRow && spec.showMotionPhoto && supportsMotionPhoto
            binding.motionPhotoButton?.visibility = if (showMotionTile) View.VISIBLE else View.GONE

            val expandFilmPills = !spec.showLogAndLut
            val hdrWidthPx = (50f * density).roundToInt()
            val iconWidthPx = (44f * density).roundToInt()
            val gap5dp = (5f * density).roundToInt()

            fun updateRowBWeight(view: View?, fixedWidthPx: Int) {
                val lp = view?.layoutParams as? LayoutParams ?: return
                val targetWidth = if (expandFilmPills) 0 else fixedWidthPx
                val targetWeight = if (expandFilmPills) 1f else 0f
                if (lp.width != targetWidth || lp.weight != targetWeight) {
                    lp.width = targetWidth
                    lp.weight = targetWeight
                    view.layoutParams = lp
                }
            }
            updateRowBWeight(binding.hdrPlusPill, hdrWidthPx)
            updateRowBWeight(binding.flashButton, iconWidthPx)
            updateRowBWeight(binding.motionPhotoButton, iconWidthPx)

            val rowBVisible = listOfNotNull(
                binding.bentoLogButton,
                binding.lutSwitcherButton,
                binding.hdrPlusPill,
                binding.flashButton,
                binding.motionPhotoButton
            ).filter { it.visibility == View.VISIBLE }
            rowBVisible.forEachIndexed { idx, v ->
                val lp = v.layoutParams as? MarginLayoutParams ?: return@forEachIndexed
                val targetMarginEnd = if (idx < rowBVisible.lastIndex) gap5dp else 0
                if (lp.marginEnd != targetMarginEnd) {
                    lp.marginEnd = targetMarginEnd
                    v.layoutParams = lp
                }
            }

            // 3. Row C: Lens Controls & Mode/Camera Switches + Dynamic Weight Balancing
            val showLensStrip = spec.showLensControls && hasMultipleLenses
            val showLensRow = spec.showModeSwitch || showLensStrip || spec.showCameraSwitch
            binding.lensControlRow?.visibility = if (showLensRow) View.VISIBLE else View.GONE
            val isModeSwitchVisible = showLensRow && spec.showModeSwitch
            val isLensControlsVisible = showLensRow && showLensStrip
            val isCameraSwitchVisible = showLensRow && spec.showCameraSwitch

            binding.modeSwitchButton?.visibility = if (isModeSwitchVisible) View.VISIBLE else View.GONE
            binding.lensControlsCard?.visibility = if (isLensControlsVisible) View.VISIBLE else View.GONE
            binding.cameraSwitchButtonAlt?.visibility = if (isCameraSwitchVisible) View.VISIBLE else View.GONE

            val expandLensSideButtons = !showLensStrip
            val sideBtnWidthPx = (52f * density).roundToInt()
            val gap6dp = (6f * density).roundToInt()
            val sideSlotPx = sideBtnWidthPx + gap6dp

            fun updateRowCWeight(view: View?) {
                val lp = view?.layoutParams as? LayoutParams ?: return
                val targetWidth = if (expandLensSideButtons) 0 else sideBtnWidthPx
                val targetWeight = if (expandLensSideButtons) 1f else 0f
                if (lp.width != targetWidth || lp.weight != targetWeight) {
                    lp.width = targetWidth
                    lp.weight = targetWeight
                    view.layoutParams = lp
                }
            }
            updateRowCWeight(binding.modeSwitchButton)
            updateRowCWeight(binding.cameraSwitchButtonAlt)

            // Dynamic symmetrical balance for lensControlsCard:
            // When only one side button is enabled in CUSTOM tier, compensate the opposite margin
            // so lensControlsCard is always centered with the shutter button in Row D.
            binding.lensControlsCard?.let { card ->
                val lp = card.layoutParams as? MarginLayoutParams
                if (lp != null) {
                    val targetStart = if (!isModeSwitchVisible && isCameraSwitchVisible) sideSlotPx else 0
                    val targetEnd = if (isModeSwitchVisible && !isCameraSwitchVisible) sideSlotPx else (if (isCameraSwitchVisible) gap6dp else 0)
                    if (lp.marginStart != targetStart || lp.marginEnd != targetEnd) {
                        lp.marginStart = targetStart
                        lp.marginEnd = targetEnd
                        card.layoutParams = lp
                    }
                }
            }
            binding.modeSwitchButton?.let { btn ->
                val lp = btn.layoutParams as? MarginLayoutParams
                if (lp != null) {
                    val targetEnd = if (isLensControlsVisible || isCameraSwitchVisible) gap6dp else 0
                    if (lp.marginEnd != targetEnd) {
                        lp.marginEnd = targetEnd
                        btn.layoutParams = lp
                    }
                }
            }

            // 4. Row D: Shutter & Right Dual-Layer Stack
            binding.settingsButton?.visibility = if (spec.showSettingsButton) View.VISIBLE else View.GONE
            (binding.btnBentoPlayground?.layoutParams as? MarginLayoutParams)?.let { lp ->
                val targetEnd = if (spec.showSettingsButton) (4f * density).roundToInt() else 0
                if (lp.marginEnd != targetEnd) {
                    lp.marginEnd = targetEnd
                    binding.btnBentoPlayground.layoutParams = lp
                }
            }

            // 5. Focus Peaking Quick-Toggle Badge (Anchored to top-right corner of viewfinder)
            binding.btnFocusPeakingBadge?.visibility = if (spec.enableFocusPeaking) View.VISIBLE else View.GONE
        }

        /**
         * Populates and styles an inflated [CameraUiContainerBinding] so it renders an authentic,
         * 1:1 WYSIWYG viewfinder Bento deck preview inside [ViewfinderDeckCustomizerSheet].
         */
        @JvmStatic
        fun bindAuthenticPreview(
            binding: CameraUiContainerBinding,
            context: Context,
            prefs: android.content.SharedPreferences,
            spec: DeckVisibilitySpec
        ) {
            val density = context.resources.displayMetrics.density

            // Hide non-deck overlays from camera_ui_container.xml
            binding.touchOverlay?.visibility = View.GONE
            binding.topRightControls?.visibility = View.GONE
            binding.btnProMode?.visibility = View.GONE
            binding.lutListContainer?.visibility = View.GONE
            binding.manualControlsRoot?.visibility = View.GONE

            // Fit bottom_island_card snugly inside the preview host container
            (binding.bottomIslandCard?.layoutParams as? MarginLayoutParams)?.let { lp ->
                lp.marginStart = 0
                lp.marginEnd = 0
                lp.topMargin = 0
                lp.bottomMargin = 0
                binding.bottomIslandCard.layoutParams = lp
            }

            // Apply compact preview row heights so all 4 rows fit comfortably in the BottomSheet
            val proBarPx = (40f * density).roundToInt()
            val recipeRowPx = (40f * density).roundToInt()
            val lensRowPx = (42f * density).roundToInt()
            val shutterRowPx = (68f * density).roundToInt()
            val cardPaddingPx = (7f * density).roundToInt()
            val rowMarginPx = (4f * density).roundToInt()

            binding.proInfoBarCard?.let { v ->
                (v.layoutParams as? MarginLayoutParams)?.let { lp ->
                    lp.height = proBarPx
                    lp.bottomMargin = rowMarginPx
                    v.layoutParams = lp
                }
            }
            binding.bentoFilmRow?.let { v ->
                (v.layoutParams as? MarginLayoutParams)?.let { lp ->
                    lp.height = recipeRowPx
                    lp.bottomMargin = rowMarginPx
                    v.layoutParams = lp
                }
            }
            binding.lensControlRow?.let { v ->
                (v.layoutParams as? MarginLayoutParams)?.let { lp ->
                    lp.height = lensRowPx
                    lp.bottomMargin = rowMarginPx
                    v.layoutParams = lp
                }
            }
            binding.bentoShutterRow?.let { v ->
                v.layoutParams?.let { lp ->
                    lp.height = shutterRowPx
                    v.layoutParams = lp
                }
            }
            binding.photoViewCard?.let { v ->
                v.layoutParams?.let { lp ->
                    lp.width = (64f * density).roundToInt()
                    v.layoutParams = lp
                }
            }
            binding.bentoRightStack?.let { v ->
                v.layoutParams?.let { lp ->
                    lp.width = (92f * density).roundToInt()
                    v.layoutParams = lp
                }
            }
            (binding.bottomIslandCard as? MaterialCardView)?.setContentPadding(
                cardPaddingPx, cardPaddingPx, cardPaddingPx, cardPaddingPx
            )

            // Resolve active capture mode for authentic mode color reactivity
            val savedModeKey = prefs.getString(
                top.maary.darkbag.fragments.SettingsFragment.KEY_ACTIVE_CAPTURE_MODE,
                CaptureMode.NORMAL.key
            )
            val activeMode = CaptureMode.fromKey(savedModeKey) ?: CaptureMode.NORMAL
            binding.bottomIslandCard?.let { styleDeckCard(it, activeMode) }

            // Row A: Pro Telemetry Bar (AF, ISO, SEC, EV)
            binding.pillFocus?.let { pill ->
                styleBentoProPill(pill, isTabActive = false, isValueManual = false)
                pill.text = formatProTelemetry("AF", "AUTO", false, false, context)
            }
            binding.pillIso?.let { pill ->
                styleBentoProPill(pill, isTabActive = false, isValueManual = false)
                pill.text = formatProTelemetry("ISO", "100", false, false, context)
            }
            binding.pillShutter?.let { pill ->
                styleBentoProPill(pill, isTabActive = false, isValueManual = false)
                pill.text = formatProTelemetry("SEC", "1/125s", false, false, context)
            }
            binding.pillEv?.let { pill ->
                styleBentoProPill(pill, isTabActive = false, isValueManual = false)
                pill.text = formatProTelemetry("EV", "0.0", false, false, context)
            }
            binding.btnProLink?.visibility = if (activeMode == CaptureMode.MULTI_CAMERA) View.VISIBLE else View.GONE
            binding.btnProLink?.let { styleBentoProLink(it, isLinked = true) }

            // Row B: Film Recipe & Quick Switches
            val targetLogName = prefs.getString(top.maary.darkbag.fragments.SettingsFragment.KEY_TARGET_LOG, "F-Log2")
                ?.takeIf { !it.equals("None", ignoreCase = true) } ?: "F-Log2"
            val activeLutName = prefs.getString(top.maary.darkbag.fragments.SettingsFragment.KEY_ACTIVE_LUT, null)
                ?.takeIf { !it.equals("None", ignoreCase = true) } ?: "Classic Neg"
            bindRecipeRow(binding, targetLogName, activeLutName)

            val isHdrPlus = prefs.getBoolean(top.maary.darkbag.fragments.CameraFragment.KEY_HDR_PLUS_ENABLED, true)
            styleHdrPlusTile(binding, isHdrPlus)

            val underexposureMode = prefs.getString(
                top.maary.darkbag.fragments.SettingsFragment.KEY_HDR_UNDEREXPOSURE_MODE,
                "Dynamic (Experimental)"
            ) ?: "Dynamic (Experimental)"
            binding.flashButton?.let { btn ->
                btn.text = null
                val iconRes = when {
                    underexposureMode.contains("Dynamic") -> top.maary.darkbag.R.drawable.ic_hdr_dynamic
                    underexposureMode == "Off" -> top.maary.darkbag.R.drawable.ic_exposure_off
                    underexposureMode == "-1 EV" -> top.maary.darkbag.R.drawable.ic_exposure_neg_1
                    underexposureMode == "-2 EV" -> top.maary.darkbag.R.drawable.ic_exposure_neg_2
                    else -> top.maary.darkbag.R.drawable.ic_hdr_dynamic
                }
                btn.setIconResource(iconRes)
                styleBentoToggleTile(btn, isActive = underexposureMode != "Off", isTertiaryAccent = false)
            }

            val isMotionPhoto = prefs.getBoolean(top.maary.darkbag.fragments.SettingsFragment.KEY_MOTION_PHOTO, false)
            binding.motionPhotoButton?.let { btn ->
                btn.setIconResource(
                    if (isMotionPhoto) top.maary.darkbag.R.drawable.ic_motion_photo_on
                    else top.maary.darkbag.R.drawable.ic_motion_photo_off
                )
                styleBentoToggleTile(btn, isActive = isMotionPhoto, isTertiaryAccent = false)
            }

            // Row C: Mode Switch, Lens Focal Strip, Camera Switch
            binding.modeSwitchButton?.let { btn ->
                val iconRes = when (activeMode) {
                    CaptureMode.MULTI_CAMERA -> top.maary.darkbag.R.drawable.ic_mode_multi_camera
                    CaptureMode.HALF_FRAME_SBS -> top.maary.darkbag.R.drawable.ic_mode_half_side
                    CaptureMode.HALF_FRAME_TB -> top.maary.darkbag.R.drawable.ic_mode_half_top
                    CaptureMode.NORMAL -> top.maary.darkbag.R.drawable.ic_mode_normal
                }
                btn.setIconResource(iconRes)
                styleModeSwitchButton(btn, activeMode)
            }

            binding.lensControlsContainer?.let { container ->
                if (container.childCount == 0) {
                    val previewLenses = listOf(
                        Triple("0.6x", false, false),
                        Triple("1.0x", true, false),
                        Triple("28mm", false, true),
                        Triple("35mm", false, true),
                        Triple("3.0x", false, false)
                    )
                    val marginPx = (2.5f * density).roundToInt()
                    for ((label, isActive, isDigitalCrop) in previewLenses) {
                        val btn = MaterialButton(context).apply {
                            layoutParams = LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.0f).apply {
                                marginStart = marginPx
                                marginEnd = marginPx
                            }
                            text = label
                            textSize = 10.5f
                            isSingleLine = true
                            maxLines = 1
                            gravity = Gravity.CENTER
                            textAlignment = View.TEXT_ALIGNMENT_CENTER
                            includeFontPadding = false
                            setPadding(0, 0, 0, 0)
                            insetTop = 0
                            insetBottom = 0
                            minWidth = 0
                            minimumWidth = 0
                            minHeight = 0
                            minimumHeight = 0
                            iconPadding = 0
                        }
                        styleBentoLensButton(btn, isActive = isActive, isDigitalCrop = isDigitalCrop)
                        container.addView(btn)
                    }
                }
            }

            // Row D: Gallery Thumbnail Placeholder & ExpressiveShutterButton
            binding.photoViewButton?.let { iv ->
                iv.visibility = View.VISIBLE
                iv.setImageResource(top.maary.darkbag.R.drawable.ic_photo)
                val pad = (16f * density).roundToInt()
                iv.setPadding(pad, pad, pad, pad)
                val tintColor = MaterialColors.getColor(
                    iv,
                    com.google.android.material.R.attr.colorOnSurfaceVariant,
                    Color.GRAY
                )
                iv.imageTintList = ColorStateList.valueOf(tintColor)
            }
            binding.cameraCaptureButton?.setHalfFrameState(activeMode.isHalfFrame, 0)

            // Apply visibility spec & dynamic weight balancing
            applyVisibilitySpec(
                binding = binding,
                spec = spec,
                hasMultipleLenses = true,
                isAnyManual = false,
                hasActiveManualTab = false,
                supportsMotionPhoto = activeMode.supportsMotionPhoto,
                isFlashOrUnderexposureAvailable = true
            )

            // In customizer preview, the mockup stage displays previewFocusPeakingBadge
            binding.btnFocusPeakingBadge?.visibility = View.GONE

            // Keep all preview controls enabled for 100% authentic M3 contrast, but non-clickable so scrolling is smooth
            disableInteractivityForPreview(binding.root)
        }

        private fun disableInteractivityForPreview(view: View?) {
            if (view == null) return
            view.isClickable = false
            view.isLongClickable = false
            view.isFocusable = false
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    disableInteractivityForPreview(view.getChildAt(i))
                }
            }
        }

        /**
         * Creates an ultra-compact two-line formatted label for 4-tier segmented buttons,
         * with zero vertical overflow, custom letter-spacing, and dynamic contrast colors.
         */
        @JvmStatic
        @JvmOverloads
        fun formatCompactTierLabel(
            context: Context,
            enLabel: String,
            zhSub: String,
            isActive: Boolean = false
        ): CharSequence {
            val text = "$enLabel\n$zhSub"
            val builder = SpannableStringBuilder(text)
            val displayMetrics = context.resources.displayMetrics
            val enPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10f, displayMetrics).roundToInt()
            val zhPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 9f, displayMetrics).roundToInt()

            val colorOnSurface = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurface, Color.BLACK)
            val colorOnSurfaceVariant = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.DKGRAY)
            val colorOnPrimaryContainer = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnPrimaryContainer, Color.WHITE)

            val (enColor, zhColor) = if (isActive) {
                Pair(colorOnPrimaryContainer, ColorUtils.setAlphaComponent(colorOnPrimaryContainer, 220))
            } else {
                Pair(colorOnSurface, colorOnSurfaceVariant)
            }

            val enEnd = enLabel.length
            if (enEnd > 0) {
                builder.setSpan(AbsoluteSizeSpan(enPx, false), 0, enEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                builder.setSpan(StyleSpan(Typeface.BOLD), 0, enEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                builder.setSpan(ProLetterSpacingSpan(0.04f), 0, enEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                builder.setSpan(ForegroundColorSpan(enColor), 0, enEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }

            val zhStart = enEnd + 1
            val zhEnd = text.length
            if (zhStart < zhEnd) {
                builder.setSpan(AbsoluteSizeSpan(zhPx, false), zhStart, zhEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                builder.setSpan(StyleSpan(Typeface.BOLD), zhStart, zhEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                builder.setSpan(ForegroundColorSpan(zhColor), zhStart, zhEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }

            return builder
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

            button.gravity = Gravity.CENTER
            button.textAlignment = View.TEXT_ALIGNMENT_CENTER
            button.includeFontPadding = false
            button.insetTop = 0
            button.insetBottom = 0
            button.minHeight = 0
            button.minimumHeight = 0
            button.minWidth = 0
            button.minimumWidth = 0
            button.iconPadding = 0
            button.setPadding(0, 0, 0, 0)

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
                colorPrimary
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
            val colorOnTertiaryContainer = MaterialColors.getColor(
                button,
                com.google.android.material.R.attr.colorOnTertiaryContainer,
                colorTertiary
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
                    button.backgroundTintList = ColorStateList.valueOf(colorPrimaryContainer)
                    button.setTextColor(colorOnPrimaryContainer)
                    button.iconTint = ColorStateList.valueOf(colorOnPrimaryContainer)
                    button.strokeColor = ColorStateList.valueOf(colorPrimary)
                    button.strokeWidth = stroke1_5dp
                    button.cornerRadius = radius12dp
                    applyLayoutWeight(button, 1.25f)
                }
                isActive && isDigitalCrop -> {
                    button.backgroundTintList = ColorStateList.valueOf(colorTertiaryContainer)
                    button.setTextColor(colorOnTertiaryContainer)
                    button.iconTint = ColorStateList.valueOf(colorOnTertiaryContainer)
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
         * Applies Material 3 Expressive styling to the Focus Peaking quick-toggle capsule badge.
         *
         * - Active (ON): colorTertiaryContainer fill, colorOnTertiaryContainer text, 1.5dp colorTertiary stroke.
         * - Inactive (OFF): Translucent dark overlay with subtle outline, colorOnSurfaceVariant text.
         */
        @JvmStatic
        fun styleFocusPeakingBadge(button: MaterialButton, isActive: Boolean) {
            val density = button.resources.displayMetrics.density
            val radius13dp = (13f * density).roundToInt()
            val stroke1_5dp = (1.5f * density).roundToInt()

            val colorTertiaryContainer = MaterialColors.getColor(
                button,
                com.google.android.material.R.attr.colorTertiaryContainer,
                Color.parseColor("#2E7D32")
            )
            val colorOnTertiaryContainer = MaterialColors.getColor(
                button,
                com.google.android.material.R.attr.colorOnTertiaryContainer,
                Color.WHITE
            )
            val colorTertiary = MaterialColors.getColor(
                button,
                com.google.android.material.R.attr.colorTertiary,
                Color.parseColor("#00E676")
            )
            val colorOnSurfaceVariant = MaterialColors.getColor(
                button,
                com.google.android.material.R.attr.colorOnSurfaceVariant,
                Color.LTGRAY
            )

            button.cornerRadius = radius13dp
            button.includeFontPadding = false
            button.insetTop = 0
            button.insetBottom = 0
            button.minWidth = 0
            button.minimumWidth = 0
            button.minHeight = 0
            button.minimumHeight = 0
            button.iconPadding = 0

            if (isActive) {
                button.backgroundTintList = ColorStateList.valueOf(colorTertiaryContainer)
                button.setTextColor(colorOnTertiaryContainer)
                button.strokeColor = ColorStateList.valueOf(colorTertiary)
                button.strokeWidth = stroke1_5dp
            } else {
                button.backgroundTintList = ColorStateList.valueOf(Color.argb(160, 20, 24, 28))
                button.setTextColor(colorOnSurfaceVariant)
                button.strokeColor = ColorStateList.valueOf(Color.argb(90, 255, 255, 255))
                button.strokeWidth = (1f * density).roundToInt()
            }
        }

        /**
         * Applies Material 3 Expressive styling to capture mode switch button.
         *
         * - Normal mode: colorSurfaceContainerHigh fill, colorOnSurfaceVariant icon, 0dp stroke, 18dp radius.
         * - Half-Frame: colorTertiaryContainer fill, colorOnTertiaryContainer icon, 1.5dp colorTertiary stroke, 12dp radius.
         * - Multi-Camera: colorSecondaryContainer fill, colorOnSecondaryContainer icon, 1.5dp colorSecondary stroke, 12dp radius.
         */
        @JvmStatic
        fun styleModeSwitchButton(button: MaterialButton, mode: CaptureMode) {
            val density = button.resources.displayMetrics.density
            val radius12dp = (12f * density).roundToInt()
            val radius18dp = (18f * density).roundToInt()
            val stroke1_5dp = (1.5f * density).roundToInt()

            val colorPrimary = MaterialColors.getColor(
                button,
                android.R.attr.colorPrimary,
                Color.WHITE
            )
            val colorSecondary = MaterialColors.getColor(
                button,
                com.google.android.material.R.attr.colorSecondary,
                colorPrimary
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

            when {
                mode.isHalfFrame -> {
                    button.backgroundTintList = ColorStateList.valueOf(colorTertiaryContainer)
                    button.iconTint = ColorStateList.valueOf(colorOnTertiaryContainer)
                    button.strokeColor = ColorStateList.valueOf(colorTertiary)
                    button.strokeWidth = stroke1_5dp
                    button.cornerRadius = radius12dp
                }
                mode == CaptureMode.MULTI_CAMERA -> {
                    button.backgroundTintList = ColorStateList.valueOf(colorSecondaryContainer)
                    button.iconTint = ColorStateList.valueOf(colorOnSecondaryContainer)
                    button.strokeColor = ColorStateList.valueOf(colorSecondary)
                    button.strokeWidth = stroke1_5dp
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
         * Applies dynamic expressive border and container styling to the bottom island card.
         */
        @JvmStatic
        fun styleDeckCard(card: View, mode: CaptureMode) {
            if (card is top.maary.darkbag.ui.bento.BentoTrayCard) {
                card.setCaptureMode(mode, animated = false)
                return
            }
            val cardView = card as? MaterialCardView ?: return
            val colorTertiaryContainer = MaterialColors.getColor(
                cardView,
                com.google.android.material.R.attr.colorTertiaryContainer,
                Color.DKGRAY
            )
            val colorSecondaryContainer = MaterialColors.getColor(
                cardView,
                com.google.android.material.R.attr.colorSecondaryContainer,
                Color.DKGRAY
            )
            val colorSurfaceContainer = MaterialColors.getColor(
                cardView,
                com.google.android.material.R.attr.colorSurfaceContainer,
                Color.DKGRAY
            )

            val targetColor = when {
                mode.isHalfFrame -> colorTertiaryContainer
                mode == CaptureMode.MULTI_CAMERA -> colorSecondaryContainer
                else -> colorSurfaceContainer
            }

            cardView.setCardBackgroundColor(ColorStateList.valueOf(targetColor))
            cardView.strokeWidth = 0
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
            val truncatedValue = if (value.length > 16 && !value.endsWith("…")) value.take(15) + "…" else value
            val text = "$label\n$truncatedValue"
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
