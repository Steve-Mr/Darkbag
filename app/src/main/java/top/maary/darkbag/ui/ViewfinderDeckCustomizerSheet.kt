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
        val binding = SheetViewfinderCustomizerBinding.inflate(LayoutInflater.from(context))
        dialog.setContentView(binding.root)

        val prefs = context.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)

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

            val spec = CameraBentoDeckView.DeckVisibilitySpec(
                tier = CameraUiTier.CUSTOM,
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

            // Row A: Pro Module
            binding.previewProModule.visibility = if (spec.showProModule) View.VISIBLE else View.GONE

            // Row B: Film Recipe & Switches
            binding.previewFilmRow.visibility = if (spec.showFilmRow) View.VISIBLE else View.GONE
            binding.previewLogButton.visibility = if (spec.showLogAndLut) View.VISIBLE else View.GONE
            binding.previewLutButton.visibility = if (spec.showLogAndLut) View.VISIBLE else View.GONE
            binding.previewHdrPlusPill.visibility = if (spec.showHdrPlusPill) View.VISIBLE else View.GONE
            binding.previewUnderexposure.visibility = if (spec.showUnderexposure) View.VISIBLE else View.GONE

            // Row C: Lens Controls
            binding.previewLensRow.visibility = if (spec.showLensRow) View.VISIBLE else View.GONE
            binding.previewModeSwitch.visibility = if (spec.showModeSwitch) View.VISIBLE else View.GONE
            binding.previewLensControls.visibility = if (spec.showLensControls) View.VISIBLE else View.GONE
            binding.previewCameraSwitch.visibility = if (spec.showCameraSwitch) View.VISIBLE else View.GONE

            // Row D: Shutter & Right Stack
            binding.previewSettingsButton.visibility = if (spec.showSettingsButton) View.VISIBLE else View.GONE

            // Viewfinder Stage: Focus Peaking
            binding.previewFocusPeakingBadge.visibility = if (spec.enableFocusPeaking) View.VISIBLE else View.GONE
        }

        // Initialize switch states from SharedPreferences
        binding.switchLensControls.isChecked = prefs.getBoolean(SettingsFragment.KEY_SHOW_LENS_CONTROLS, true)
        binding.switchCameraSwitch.isChecked = prefs.getBoolean(SettingsFragment.KEY_SHOW_CAMERA_SWITCH_BUTTON, true)
        binding.switchSettingsButton.isChecked = prefs.getBoolean(SettingsFragment.KEY_SHOW_SETTINGS_BUTTON, true)
        binding.switchLutSwitcher.isChecked = prefs.getBoolean(SettingsFragment.KEY_SHOW_LUT_SWITCHER, true)
        binding.switchHdrPlus.isChecked = prefs.getBoolean(SettingsFragment.KEY_SHOW_HDR_PLUS_SWITCH, true)
        binding.switchUnderexposure.isChecked = prefs.getBoolean(SettingsFragment.KEY_SHOW_HDR_UNDEREXPOSURE_BUTTON, true)
        binding.switchModeSwitch.isChecked = prefs.getBoolean(SettingsFragment.KEY_SHOW_MODE_SWITCH_BUTTON, true)
        binding.switchManualControls.isChecked = prefs.getBoolean(SettingsFragment.KEY_MANUAL_CONTROLS, false)
        binding.switchFocusPeaking.isChecked = prefs.getBoolean(SettingsFragment.KEY_EXP_FOCUS_PEAKING, false)

        updatePreview(animate = false)

        fun wireSwitch(switchTile: top.maary.darkbag.ui.bento.BentoSwitchTile, prefKey: String) {
            switchTile.setOnCheckedChangeListener { _, isChecked ->
                prefs.edit()
                    .putBoolean(prefKey, isChecked)
                    .putString(CameraBentoDeckView.PREF_KEY_UI_TIER, CameraUiTier.CUSTOM.key)
                    .apply()
                updatePreview(animate = true)
                onUpdated()
            }
        }

        wireSwitch(binding.switchLensControls, SettingsFragment.KEY_SHOW_LENS_CONTROLS)
        wireSwitch(binding.switchCameraSwitch, SettingsFragment.KEY_SHOW_CAMERA_SWITCH_BUTTON)
        wireSwitch(binding.switchSettingsButton, SettingsFragment.KEY_SHOW_SETTINGS_BUTTON)
        wireSwitch(binding.switchLutSwitcher, SettingsFragment.KEY_SHOW_LUT_SWITCHER)
        wireSwitch(binding.switchHdrPlus, SettingsFragment.KEY_SHOW_HDR_PLUS_SWITCH)
        wireSwitch(binding.switchUnderexposure, SettingsFragment.KEY_SHOW_HDR_UNDEREXPOSURE_BUTTON)
        wireSwitch(binding.switchModeSwitch, SettingsFragment.KEY_SHOW_MODE_SWITCH_BUTTON)
        wireSwitch(binding.switchManualControls, SettingsFragment.KEY_MANUAL_CONTROLS)
        wireSwitch(binding.switchFocusPeaking, SettingsFragment.KEY_EXP_FOCUS_PEAKING)

        // Template Resets
        fun applyTemplate(
            lens: Boolean,
            camSwitch: Boolean,
            settings: Boolean,
            lut: Boolean,
            hdr: Boolean,
            underexposure: Boolean,
            modeSwitch: Boolean,
            manual: Boolean,
            peaking: Boolean
        ) {
            binding.switchLensControls.isChecked = lens
            binding.switchCameraSwitch.isChecked = camSwitch
            binding.switchSettingsButton.isChecked = settings
            binding.switchLutSwitcher.isChecked = lut
            binding.switchHdrPlus.isChecked = hdr
            binding.switchUnderexposure.isChecked = underexposure
            binding.switchModeSwitch.isChecked = modeSwitch
            binding.switchManualControls.isChecked = manual
            binding.switchFocusPeaking.isChecked = peaking

            prefs.edit()
                .putBoolean(SettingsFragment.KEY_SHOW_LENS_CONTROLS, lens)
                .putBoolean(SettingsFragment.KEY_SHOW_CAMERA_SWITCH_BUTTON, camSwitch)
                .putBoolean(SettingsFragment.KEY_SHOW_SETTINGS_BUTTON, settings)
                .putBoolean(SettingsFragment.KEY_SHOW_LUT_SWITCHER, lut)
                .putBoolean(SettingsFragment.KEY_SHOW_HDR_PLUS_SWITCH, hdr)
                .putBoolean(SettingsFragment.KEY_SHOW_HDR_UNDEREXPOSURE_BUTTON, underexposure)
                .putBoolean(SettingsFragment.KEY_SHOW_MODE_SWITCH_BUTTON, modeSwitch)
                .putBoolean(SettingsFragment.KEY_MANUAL_CONTROLS, manual)
                .putBoolean(SettingsFragment.KEY_EXP_FOCUS_PEAKING, peaking)
                .putString(CameraBentoDeckView.PREF_KEY_UI_TIER, CameraUiTier.CUSTOM.key)
                .apply()

            updatePreview(animate = true)
            onUpdated()
        }

        binding.btnTemplatePs.setOnClickListener {
            applyTemplate(
                lens = true,
                camSwitch = true,
                settings = true,
                lut = false,
                hdr = false,
                underexposure = false,
                modeSwitch = false,
                manual = false,
                peaking = false
            )
        }

        binding.btnTemplateFilm.setOnClickListener {
            applyTemplate(
                lens = true,
                camSwitch = true,
                settings = true,
                lut = true,
                hdr = true,
                underexposure = true,
                modeSwitch = true,
                manual = false,
                peaking = false
            )
        }

        binding.btnTemplatePro.setOnClickListener {
            applyTemplate(
                lens = true,
                camSwitch = true,
                settings = true,
                lut = true,
                hdr = true,
                underexposure = true,
                modeSwitch = true,
                manual = true,
                peaking = true
            )
        }

        binding.btnCloseCustomizer.setOnClickListener {
            dialog.dismiss()
        }

        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        dialog.show()
    }
}
