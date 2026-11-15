package top.maary.darkbag.ui

import android.content.Context
import android.content.res.Resources
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.google.android.material.button.MaterialButton
import top.maary.darkbag.databinding.CameraUiContainerBinding
import top.maary.darkbag.modes.CaptureMode
import top.maary.darkbag.repository.DarkbagPreferences
import top.maary.darkbag.repository.PreferenceKeys
import top.maary.darkbag.viewmodel.CameraUiTier

/**
 * Composite controller view and facade for Darkbag's Modular Darkroom Bento Matrix
 * camera interface.
 *
 * Delegates domain responsibilities to:
 * - [BentoDeckDimensions]: 3-tier screen classification, adaptive row sizing, and weight balancing.
 * - [BentoDeckStylingEngine]: Material 3 Expressive tonal styling and micro-telemetry formatting.
 * - [BentoDeckPreviewBinder]: Visibility spec resolution, tier binding, and customizer preview.
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
        BentoDeckDimensions.applyDynamicRowHeights(this)
    }

    fun styleLensButton(button: MaterialButton, isActive: Boolean, isDigitalCrop: Boolean) {
        BentoDeckStylingEngine.styleBentoLensButton(button, isActive, isDigitalCrop)
    }

    fun styleToggleTile(button: MaterialButton, isActive: Boolean, isTertiaryAccent: Boolean = false) {
        BentoDeckStylingEngine.styleBentoToggleTile(button, isActive, isTertiaryAccent)
    }

    fun styleModeButton(button: MaterialButton, isSpecialMode: Boolean) {
        BentoDeckStylingEngine.styleModeSwitchButton(button, isSpecialMode)
    }

    fun styleProPill(button: MaterialButton, isTabActive: Boolean, isValueManual: Boolean) {
        BentoDeckStylingEngine.styleBentoProPill(button, isTabActive, isValueManual)
    }

    fun styleProLink(button: MaterialButton, isLinked: Boolean) {
        BentoDeckStylingEngine.styleBentoProLink(button, isLinked)
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

        const val PREF_KEY_UI_TIER = PreferenceKeys.KEY_UI_TIER

        @JvmStatic
        fun resolveScreenTier(resources: Resources): BentoScreenTier =
            BentoDeckDimensions.resolveScreenTier(resources)

        @JvmStatic
        fun getBentoDimensions(resources: Resources): BentoDimensions =
            BentoDeckDimensions.getBentoDimensions(resources)

        @JvmStatic
        fun applyDynamicRowHeights(view: View) =
            BentoDeckDimensions.applyDynamicRowHeights(view)

        @JvmStatic
        fun applyDynamicRowHeights(binding: CameraUiContainerBinding) =
            BentoDeckDimensions.applyDynamicRowHeights(binding)

        @JvmStatic
        fun resolveVisibilitySpec(
            prefs: DarkbagPreferences,
            tier: CameraUiTier
        ): DeckVisibilitySpec =
            BentoDeckPreviewBinder.resolveVisibilitySpec(prefs, tier)

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
        ) = BentoDeckPreviewBinder.bindTierState(
            binding = binding,
            tier = tier,
            isAnyManual = isAnyManual,
            hasActiveManualTab = hasActiveManualTab,
            showLutSwitcherPref = showLutSwitcherPref,
            showManualControlsPref = showManualControlsPref,
            visibilitySpec = visibilitySpec
        )

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
        ) = BentoDeckPreviewBinder.applyVisibilitySpec(
            binding = binding,
            spec = spec,
            hasMultipleLenses = hasMultipleLenses,
            isAnyManual = isAnyManual,
            hasActiveManualTab = hasActiveManualTab,
            supportsMotionPhoto = supportsMotionPhoto,
            isFlashOrUnderexposureAvailable = isFlashOrUnderexposureAvailable
        )

        @JvmStatic
        fun bindAuthenticPreview(
            binding: CameraUiContainerBinding,
            context: Context,
            prefs: DarkbagPreferences,
            spec: DeckVisibilitySpec
        ) = BentoDeckPreviewBinder.bindAuthenticPreview(binding, context, prefs, spec)

        @JvmStatic
        @JvmOverloads
        fun formatCompactTierLabel(
            context: Context,
            title: String,
            subtitle: String = "",
            isActive: Boolean = false
        ): CharSequence = BentoDeckStylingEngine.formatCompactTierLabel(context, title, subtitle, isActive)

        @JvmStatic
        fun bindRecipeRow(
            binding: CameraUiContainerBinding,
            targetLogName: String?,
            activeLutName: String?
        ) = BentoDeckPreviewBinder.bindRecipeRow(binding, targetLogName, activeLutName)

        @JvmStatic
        fun styleHdrPlusTile(binding: CameraUiContainerBinding, isHdrPlusEnabled: Boolean) =
            BentoDeckStylingEngine.styleHdrPlusTile(binding, isHdrPlusEnabled)

        @JvmStatic
        fun styleBentoLensButton(button: MaterialButton, isActive: Boolean, isDigitalCrop: Boolean) =
            BentoDeckStylingEngine.styleBentoLensButton(button, isActive, isDigitalCrop)

        @JvmStatic
        @JvmOverloads
        fun styleBentoToggleTile(
            button: MaterialButton,
            isActive: Boolean,
            isTertiaryAccent: Boolean = false
        ) = BentoDeckStylingEngine.styleBentoToggleTile(button, isActive, isTertiaryAccent)

        @JvmStatic
        fun styleFocusPeakingBadge(button: MaterialButton, isActive: Boolean) =
            BentoDeckStylingEngine.styleFocusPeakingBadge(button, isActive)

        @JvmStatic
        fun styleModeSwitchButton(button: MaterialButton, mode: CaptureMode) =
            BentoDeckStylingEngine.styleModeSwitchButton(button, mode)

        @JvmStatic
        fun styleModeSwitchButton(button: MaterialButton, isSpecialMode: Boolean) =
            BentoDeckStylingEngine.styleModeSwitchButton(button, isSpecialMode)

        @JvmStatic
        fun styleDeckCard(card: View, mode: CaptureMode) =
            BentoDeckStylingEngine.styleDeckCard(card, mode)

        @JvmStatic
        @JvmOverloads
        fun formatRecipeLabel(rawName: String?, fallback: String = "None"): String =
            BentoDeckStylingEngine.formatRecipeLabel(rawName, fallback)

        @JvmStatic
        fun styleBentoProPill(button: MaterialButton, isTabActive: Boolean, isValueManual: Boolean) =
            BentoDeckStylingEngine.styleBentoProPill(button, isTabActive, isValueManual)

        @JvmStatic
        fun styleBentoProLink(button: MaterialButton, isLinked: Boolean) =
            BentoDeckStylingEngine.styleBentoProLink(button, isLinked)

        @JvmStatic
        @JvmOverloads
        fun formatProTelemetry(
            label: String,
            value: String,
            isTabActive: Boolean = false,
            isValueManual: Boolean = false,
            context: Context
        ): CharSequence = BentoDeckStylingEngine.formatProTelemetry(
            label = label,
            value = value,
            isTabActive = isTabActive,
            isValueManual = isValueManual,
            context = context
        )
    }
}
