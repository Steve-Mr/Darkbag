package top.maary.darkbag.ui

import android.content.Context
import android.transition.ChangeBounds
import android.transition.Fade
import android.transition.TransitionManager
import android.transition.TransitionSet
import android.view.LayoutInflater
import android.view.View
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import top.maary.darkbag.databinding.CameraUiContainerBinding
import top.maary.darkbag.databinding.SheetViewfinderCustomizerBinding
import top.maary.darkbag.fragments.SettingsFragment
import top.maary.darkbag.viewmodel.CameraUiTier

/**
 * Dedicated Material 3 BottomSheet dialog allowing photographers to customize
 * camera viewfinder deck controls with a live WYSIWYG preview widget and base template resets.
 */
object ViewfinderDeckCustomizerSheet {

    fun show(context: Context, onUpdated: () -> Unit) {
        val dialog = BottomSheetDialog(context)
        val inflater = LayoutInflater.from(context)
        val binding = SheetViewfinderCustomizerBinding.inflate(inflater)
        val deckBinding = CameraUiContainerBinding.inflate(inflater, binding.previewDeckHost)
        dialog.setContentView(binding.root)

        val prefs = context.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        var activePreviewTier = CameraUiTier.fromKey(
            prefs.getString(CameraBentoDeckView.PREF_KEY_UI_TIER, CameraUiTier.FILM.key)
        )
        val initialSpec = CameraBentoDeckView.resolveVisibilitySpec(prefs, activePreviewTier)

        fun buildCurrentSpec(): CameraBentoDeckView.DeckVisibilitySpec {
            return CameraBentoDeckView.DeckVisibilitySpec(
                tier = activePreviewTier,
                showProModule = binding.switchManualControls.isChecked,
                showLogAndLut = binding.switchLutSwitcher.isChecked,
                showHdrPlusPill = binding.switchHdrPlus.isChecked,
                showUnderexposure = binding.switchUnderexposure.isChecked,
                showMotionPhoto = true,
                showModeSwitch = binding.switchModeSwitch.isChecked,
                showLensControls = binding.switchLensControls.isChecked,
                showCameraSwitch = binding.switchCameraSwitch.isChecked,
                showSettingsButton = binding.switchSettingsButton.isChecked,
                enableFocusPeaking = binding.switchFocusPeaking.isChecked
            )
        }

        fun updatePreview(animate: Boolean) {
            if (animate) {
                val transition = TransitionSet().apply {
                    ordering = TransitionSet.ORDERING_TOGETHER
                    addTransition(ChangeBounds())
                    addTransition(Fade())
                    duration = 180
                }
                TransitionManager.beginDelayedTransition(binding.containerPreviewContent, transition)
            }

            val spec = buildCurrentSpec()
            CameraBentoDeckView.bindAuthenticPreview(
                binding = deckBinding,
                context = context,
                prefs = prefs,
                spec = spec
            )
            binding.previewFocusPeakingBadge.visibility = if (spec.enableFocusPeaking) View.VISIBLE else View.GONE
        }

        fun persistCurrentSwitchesAsCustom() {
            activePreviewTier = CameraUiTier.CUSTOM
            prefs.edit()
                .putBoolean(SettingsFragment.KEY_SHOW_LENS_CONTROLS, binding.switchLensControls.isChecked)
                .putBoolean(SettingsFragment.KEY_SHOW_CAMERA_SWITCH_BUTTON, binding.switchCameraSwitch.isChecked)
                .putBoolean(SettingsFragment.KEY_SHOW_SETTINGS_BUTTON, binding.switchSettingsButton.isChecked)
                .putBoolean(SettingsFragment.KEY_SHOW_LUT_SWITCHER, binding.switchLutSwitcher.isChecked)
                .putBoolean(SettingsFragment.KEY_SHOW_HDR_PLUS_SWITCH, binding.switchHdrPlus.isChecked)
                .putBoolean(SettingsFragment.KEY_SHOW_HDR_UNDEREXPOSURE_BUTTON, binding.switchUnderexposure.isChecked)
                .putBoolean(SettingsFragment.KEY_SHOW_MODE_SWITCH_BUTTON, binding.switchModeSwitch.isChecked)
                .putBoolean(SettingsFragment.KEY_MANUAL_CONTROLS, binding.switchManualControls.isChecked)
                .putBoolean(SettingsFragment.KEY_EXP_FOCUS_PEAKING, binding.switchFocusPeaking.isChecked)
                .putString(CameraBentoDeckView.PREF_KEY_UI_TIER, CameraUiTier.CUSTOM.key)
                .apply()
            updatePreview(animate = true)
            onUpdated()
        }

        // Initialize switch states from the user's current active tier spec
        binding.switchLensControls.isChecked = initialSpec.showLensControls
        binding.switchCameraSwitch.isChecked = initialSpec.showCameraSwitch
        binding.switchSettingsButton.isChecked = initialSpec.showSettingsButton
        binding.switchLutSwitcher.isChecked = initialSpec.showLogAndLut
        binding.switchHdrPlus.isChecked = initialSpec.showHdrPlusPill
        binding.switchUnderexposure.isChecked = initialSpec.showUnderexposure
        binding.switchModeSwitch.isChecked = initialSpec.showModeSwitch
        binding.switchManualControls.isChecked = initialSpec.showProModule
        binding.switchFocusPeaking.isChecked = initialSpec.enableFocusPeaking

        updatePreview(animate = false)

        var isBulkUpdating = false
        fun wireSwitch(switchTile: top.maary.darkbag.ui.bento.BentoSwitchTile) {
            switchTile.setOnCheckedChangeListener { _, _ ->
                if (!isBulkUpdating) {
                    persistCurrentSwitchesAsCustom()
                }
            }
        }

        wireSwitch(binding.switchLensControls)
        wireSwitch(binding.switchCameraSwitch)
        wireSwitch(binding.switchSettingsButton)
        wireSwitch(binding.switchLutSwitcher)
        wireSwitch(binding.switchHdrPlus)
        wireSwitch(binding.switchUnderexposure)
        wireSwitch(binding.switchModeSwitch)
        wireSwitch(binding.switchManualControls)
        wireSwitch(binding.switchFocusPeaking)

        // Template Resets (canonical preset definitions)
        fun applyTemplateFromTier(templateTier: CameraUiTier) {
            isBulkUpdating = true
            try {
                val templateSpec = CameraBentoDeckView.resolveVisibilitySpec(prefs, templateTier)
                binding.switchLensControls.isChecked = templateSpec.showLensControls
                binding.switchCameraSwitch.isChecked = templateSpec.showCameraSwitch
                binding.switchSettingsButton.isChecked = templateSpec.showSettingsButton
                binding.switchLutSwitcher.isChecked = templateSpec.showLogAndLut
                binding.switchHdrPlus.isChecked = templateSpec.showHdrPlusPill
                binding.switchUnderexposure.isChecked = templateSpec.showUnderexposure
                binding.switchModeSwitch.isChecked = templateSpec.showModeSwitch
                binding.switchManualControls.isChecked = templateSpec.showProModule
                binding.switchFocusPeaking.isChecked = templateSpec.enableFocusPeaking
            } finally {
                isBulkUpdating = false
            }

            persistCurrentSwitchesAsCustom()
        }

        binding.btnTemplatePs.setOnClickListener {
            applyTemplateFromTier(CameraUiTier.POINT_AND_SHOOT)
        }

        binding.btnTemplateFilm.setOnClickListener {
            applyTemplateFromTier(CameraUiTier.FILM)
        }

        binding.btnTemplatePro.setOnClickListener {
            applyTemplateFromTier(CameraUiTier.PRO)
        }

        binding.btnCloseCustomizer.setOnClickListener {
            dialog.dismiss()
        }

        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        dialog.show()
    }
}
