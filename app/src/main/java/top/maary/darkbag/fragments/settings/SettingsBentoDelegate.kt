package top.maary.darkbag.fragments.settings

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.View
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.shape.ShapeAppearanceModel
import top.maary.darkbag.R
import top.maary.darkbag.databinding.FragmentSettingsBinding
import top.maary.darkbag.fragments.SettingsFragment
import top.maary.darkbag.repository.DarkbagPreferences
import top.maary.darkbag.ui.CameraBentoDeckView
import top.maary.darkbag.ui.bento.BentoContainerRole
import top.maary.darkbag.utils.CameraRepository
import top.maary.darkbag.utils.DualLensPairPreference
import top.maary.darkbag.utils.LutManager
import top.maary.darkbag.utils.MultiCameraCountPreference
import top.maary.darkbag.viewmodel.CameraUiTier

/**
 * Delegate responsible for rendering DarkbagPreferences into Bento Matrix tiles,
 * switch controls, and expressive Material 3 card styling.
 */
class SettingsBentoDelegate(
    private val context: Context,
    private val cameraRepository: CameraRepository
) {
    var isRendering: Boolean = false
        private set

    fun renderPreferences(binding: FragmentSettingsBinding, state: DarkbagPreferences) {
        isRendering = true
        try {
            renderTelemetryTiles(binding, state)
            renderSwitchesAndCheckboxes(binding, state)
            renderStylingAndVisibility(binding, state)
        } finally {
            isRendering = false
        }
    }

    private fun renderTelemetryTiles(binding: FragmentSettingsBinding, state: DarkbagPreferences) {
        // 1. Default Lens
        val lenses = cameraRepository.getAllFocalLengthPresets()
        val currentLens = lenses.find { it.sensorId == state.defaultLensId }
            ?: lenses.find { it.multiplier in 0.95f..1.05f && !it.isZoomPreset }
            ?: lenses.firstOrNull()
        binding.btnMenuDefaultLens.text = CameraBentoDeckView.formatProTelemetry(
            label = context.getString(R.string.pref_default_lens_label),
            value = currentLens?.name ?: "Wide (1.0x)",
            isTabActive = false,
            isValueManual = true,
            context = context
        )

        // 2. Default Focal 1.0x
        val mainWide = cameraRepository.getMainWideLens()
        val presets1x = if (mainWide != null) cameraRepository.get1xPresets(mainWide) else emptyList()
        val baseFocalName = presets1x.firstOrNull()?.name ?: "24mm"
        binding.btnMenuDefaultFocal1x.text = CameraBentoDeckView.formatProTelemetry(
            label = context.getString(R.string.pref_default_focal_1x_label),
            value = state.defaultFocal1xName ?: baseFocalName,
            isTabActive = false,
            isValueManual = true,
            context = context
        )

        // 3. Target Log Curve
        val isLogActive = state.targetLog != "None"
        val rawLog = state.targetLog.replace(" (Rec.709)", "").replace(" (BT.709)", "")
        val localizedLog = if (!isLogActive) context.getString(R.string.pref_option_none) else rawLog
        val displayLog = if (localizedLog.length > 14) localizedLog.take(13) + "…" else localizedLog
        binding.btnMenuTargetLog.text = CameraBentoDeckView.formatProTelemetry(
            label = context.getString(R.string.pref_target_log_label),
            value = displayLog,
            isTabActive = isLogActive,
            isValueManual = isLogActive,
            context = context
        )

        // 4. Active LUT
        val activeLut = state.activeLut.takeIf { it != "None" }
        val rawLutName = activeLut?.removeSuffix(".cube")?.removeSuffix(".CUBE") ?: "None"
        val assocLog = activeLut?.let { LutManager(context).getAssociatedLog(it) }
        val displayLut = if (activeLut != null) {
            val label = when {
                !assocLog.isNullOrEmpty() && assocLog != LutManager.LOG_UNIVERSAL -> "$rawLutName ($assocLog)"
                assocLog == LutManager.LOG_UNIVERSAL -> "$rawLutName (Universal)"
                else -> rawLutName
            }
            if (label.length > 18) label.take(17) + "…" else label
        } else {
            "None"
        }
        binding.btnManageLuts.text = CameraBentoDeckView.formatProTelemetry(
            label = context.getString(R.string.pref_manage_luts_label),
            value = displayLut,
            isTabActive = activeLut != null,
            isValueManual = true,
            context = context
        )

        // 5. HDR+ Fusion Mode
        val fusionDisplay = when (state.hdrFusionMode) {
            SettingsFragment.HDR_FUSION_SABRE -> context.getString(R.string.pref_fusion_sabre)
            SettingsFragment.HDR_FUSION_SPATIAL_RCD -> context.getString(R.string.pref_fusion_spatial)
            SettingsFragment.HDR_FUSION_CLASSIC -> context.getString(R.string.pref_fusion_classic)
            SettingsFragment.HDR_FUSION_AUTO -> context.getString(R.string.pref_fusion_auto)
            else -> state.hdrFusionMode
        }
        binding.btnMenuHdrFusionMode.text = CameraBentoDeckView.formatProTelemetry(
            label = context.getString(R.string.pref_hdr_fusion_label),
            value = fusionDisplay,
            isTabActive = true,
            isValueManual = true,
            context = context
        )

        // 6. HDR+ Burst Frames
        binding.btnMenuHdrBurstMode.text = CameraBentoDeckView.formatProTelemetry(
            label = context.getString(R.string.pref_burst_frames_label),
            value = context.getString(R.string.pref_burst_frames_count_format, state.hdrBurstCount.toString()),
            isTabActive = false,
            isValueManual = true,
            context = context
        )

        // 7. HDR+ Underexposure
        val savedUe = state.hdrUnderexposureMode
        val displayUe = when {
            savedUe.endsWith("EV") -> savedUe
            savedUe == "Off" || savedUe.startsWith("Off") -> context.getString(R.string.pref_option_off)
            savedUe.startsWith("Dynamic") -> context.getString(R.string.pref_option_dynamic)
            else -> "$savedUe EV"
        }
        binding.btnMenuUnderexposureMode.text = CameraBentoDeckView.formatProTelemetry(
            label = context.getString(R.string.pref_underexposure_label),
            value = displayUe,
            isTabActive = false,
            isValueManual = savedUe != "Off",
            context = context
        )

        // 8. Color Engine Mode
        val cIdx = state.colorEngineMode.coerceIn(0, SettingsFragment.COLOR_ENGINE_MODES.size - 1)
        val ceName = when (cIdx) {
            0 -> "Khronos Neutral"
            1 -> "Natural Filmic"
            2 -> "Sony Uchimura"
            3 -> "Legacy ACES"
            else -> SettingsFragment.COLOR_ENGINE_MODES[cIdx]
        }
        binding.btnMenuColorEngine.text = CameraBentoDeckView.formatProTelemetry(
            label = context.getString(R.string.pref_color_engine_label),
            value = ceName,
            isTabActive = false,
            isValueManual = true,
            context = context
        )

        // 9. Antibanding Mode
        val abDisplay = when (state.antibandingMode) {
            "Auto" -> context.getString(R.string.pref_option_auto)
            "Off" -> context.getString(R.string.pref_option_off)
            else -> state.antibandingMode
        }
        binding.btnMenuAntibanding.text = CameraBentoDeckView.formatProTelemetry(
            label = context.getString(R.string.pref_antibanding_label),
            value = abDisplay,
            isTabActive = false,
            isValueManual = state.antibandingMode != "Off",
            context = context
        )

        // 10. Burst Strategy
        val bsDisplay = if (state.burstProcessingStrategy == SettingsFragment.BURST_STRATEGY_BALANCED) {
            context.getString(R.string.pref_burst_strategy_balanced)
        } else {
            context.getString(R.string.pref_burst_strategy_rapid)
        }
        binding.btnMenuBurstProcessingStrategy.text = CameraBentoDeckView.formatProTelemetry(
            label = context.getString(R.string.pref_burst_strategy_label),
            value = bsDisplay,
            isTabActive = false,
            isValueManual = true,
            context = context
        )

        // 11. Shutter Long-Press
        val slpDisplay = when (state.shutterLongPressAction) {
            SettingsFragment.SHUTTER_LONG_PRESS_MP4 -> context.getString(R.string.pref_shutter_action_mp4)
            SettingsFragment.SHUTTER_LONG_PRESS_RAW_VIDEO -> context.getString(R.string.pref_shutter_action_raw_video)
            else -> context.getString(R.string.pref_option_disabled)
        }
        binding.btnMenuShutterLongPress.text = CameraBentoDeckView.formatProTelemetry(
            label = context.getString(R.string.pref_shutter_hold_label),
            value = slpDisplay,
            isTabActive = false,
            isValueManual = state.shutterLongPressAction != SettingsFragment.SHUTTER_LONG_PRESS_DISABLED,
            context = context
        )

        // 12. RAW Video
        val shortRes = when {
            state.rawVideoResolution.contains("4K") -> "4K UHD"
            state.rawVideoResolution.contains("2K") -> "2K Open Gate"
            state.rawVideoResolution.contains("1080p") -> "1080p"
            else -> "Max Native"
        }
        binding.btnMenuRawVideo.text = CameraBentoDeckView.formatProTelemetry(
            label = context.getString(R.string.pref_raw_video_label),
            value = "${state.rawVideoFps} FPS · $shortRes",
            isTabActive = false,
            isValueManual = true,
            context = context
        )

        // 13. External Viewer
        val defaultEvName = context.getString(R.string.pref_external_viewer_default)
        val evName = state.externalViewerName ?: defaultEvName
        binding.btnMenuExternalViewer.text = CameraBentoDeckView.formatProTelemetry(
            label = context.getString(R.string.pref_external_viewer_label),
            value = evName,
            isTabActive = false,
            isValueManual = evName != defaultEvName,
            context = context
        )
        binding.btnMenuExternalViewer.visibility = if (state.useInternalViewer) View.GONE else View.VISIBLE

        // 14. DNG Compression & Format
        val compName = if (state.dngCompressionMode == 0) {
            context.getString(R.string.pref_dng_compress_lossless)
        } else {
            context.getString(R.string.pref_dng_compress_uncompressed)
        }
        binding.btnMenuDngCompression.text = CameraBentoDeckView.formatProTelemetry(
            label = context.getString(R.string.pref_dng_compression_label),
            value = compName,
            isTabActive = true,
            isValueManual = true,
            context = context
        )

        val fmtName = if (state.rawOutputFormatIndex == 0) {
            context.getString(R.string.pref_cfa_bayer)
        } else {
            context.getString(R.string.pref_cfa_linear)
        }
        binding.btnMenuDngFormat.text = CameraBentoDeckView.formatProTelemetry(
            label = context.getString(R.string.pref_dng_format_label),
            value = fmtName,
            isTabActive = false,
            isValueManual = true,
            context = context
        )

        // 15. Multi-Camera Count & Dual Pair
        val countPref = MultiCameraCountPreference.fromKey(state.multiCameraCountPref)
        binding.btnMenuMultiCameraCount.text = CameraBentoDeckView.formatProTelemetry(
            label = context.getString(R.string.pref_multi_camera_count_label),
            value = countPref.getDisplayName(context),
            isTabActive = false,
            isValueManual = true,
            context = context
        )

        val pairPref = DualLensPairPreference.fromKey(state.multiCameraDualPair)
        binding.btnMenuMultiCameraDualPair.text = CameraBentoDeckView.formatProTelemetry(
            label = context.getString(R.string.pref_multi_camera_dual_pair_label),
            value = pairPref.getDisplayName(context),
            isTabActive = false,
            isValueManual = true,
            context = context
        )
    }

    private fun renderSwitchesAndCheckboxes(binding: FragmentSettingsBinding, state: DarkbagPreferences) {
        binding.switchLivePreview.isChecked = state.enableLutPreview
        binding.cbSaveJpg.isChecked = state.saveJpg
        binding.cbSaveRaw.isChecked = state.saveRaw
        binding.switchMotionPhoto.isChecked = state.motionPhotoEnabled
        binding.switchMotionPhotoAudio.isChecked = state.motionPhotoAudioEnabled
        binding.switchMotionPhotoAudio.visibility = if (state.motionPhotoEnabled) View.VISIBLE else View.GONE
        binding.switchMirrorFront.isChecked = state.mirrorFrontCamera
        binding.switchHdrPlusOis.isChecked = state.hdrPlusOis
        binding.switchUseInternalViewer.isChecked = state.useInternalViewer
        binding.switchForce60fps.isChecked = state.force60Fps

        // Half Frame
        binding.switchHalfFrameMode.isChecked = state.halfFrameMode
        binding.layoutHalfFrameOptions.visibility = if (state.halfFrameMode) View.VISIBLE else View.GONE
        binding.switchHalfFrameDownsample.isChecked = state.halfFrameDownsample
        binding.switchHalfFrameDateStamp.isChecked = state.halfFrameDateStamp
        binding.switchHalfFrameLightLeak.isChecked = state.halfFrameLightLeak
        binding.cbHfSaveRaw.isChecked = state.halfFrameSaveRaw

        // Multi Camera
        binding.switchMultiCameraForceEnable.isChecked = state.multiCameraForceEnable
        binding.switchMultiCameraMode.isChecked = state.multiCameraModeEnabled
        binding.cbMultiCameraSaveRaw.isChecked = state.multiCameraSaveRaw
    }

    private fun renderStylingAndVisibility(binding: FragmentSettingsBinding, state: DarkbagPreferences) {
        // Multi-Camera visibility
        val mcEnabled = state.multiCameraModeEnabled
        binding.btnMenuMultiCameraCount.visibility = if (mcEnabled) View.VISIBLE else View.GONE
        binding.btnMenuMultiCameraDualPair.visibility = if (mcEnabled && state.multiCameraCountPref == MultiCameraCountPreference.DUAL.key) View.VISIBLE else View.GONE
        binding.tileMultiCameraSaveRaw.visibility = if (mcEnabled) View.VISIBLE else View.GONE

        // Storage visibility & paths
        binding.layoutJpgStorage.visibility = if (state.saveJpg) View.VISIBLE else View.GONE
        binding.layoutRawStorage.visibility = if (state.saveRaw) View.VISIBLE else View.GONE
        binding.btnMenuDngCompression.visibility = if (state.saveRaw) View.VISIBLE else View.GONE
        binding.btnMenuDngFormat.visibility = if (state.saveRaw) View.VISIBLE else View.GONE
        binding.tvJpgPath.text = state.jpgStorageUriName ?: "Default (Pictures/Darkbag)"
        binding.tvRawPath.text = state.rawStorageUriName ?: "Default (Pictures/Darkbag)"

        // Format card constraints & styling
        val isLut = (state.targetLog != "None") || (state.activeLut != "None")
        if (isLut) {
            binding.cbSaveJpg.isEnabled = false
            binding.cbSaveRaw.isEnabled = true
        } else {
            val count = (if (state.saveJpg) 1 else 0) + (if (state.saveRaw) 1 else 0)
            binding.cbSaveJpg.isEnabled = !(state.saveJpg && count == 1)
            binding.cbSaveRaw.isEnabled = !(state.saveRaw && count == 1)
        }
        updateSaveFormatCardStyling(binding, state.saveJpg, state.saveRaw)

        // Half frame card styling
        updateHalfFrameCardStyling(binding, state.halfFrameMode, animated = false)

        // Startup Cards styling
        updateStartupCards(binding, state.defaultStartupPage, state.enableCamera, state.enablePlayground)

        // UI Tier buttons
        syncTierSelection(binding, state.uiTier)

        // Debug section
        binding.sectionDebug.visibility = if (state.debugEnabled) View.VISIBLE else View.GONE
    }

    fun updateSaveFormatCardStyling(binding: FragmentSettingsBinding, isJpg: Boolean, isRaw: Boolean) {
        val colorPrimary = MaterialColors.getColor(binding.root, android.R.attr.colorPrimary, Color.WHITE)
        val colorPrimaryContainer = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorPrimaryContainer, Color.DKGRAY)
        val colorSecondary = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorSecondary, colorPrimary)
        val colorSecondaryContainer = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorSecondaryContainer, colorPrimaryContainer)
        val colorSurfaceContainerHigh = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorSurfaceContainerHigh, Color.DKGRAY)
        val colorOutlineVariant = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOutlineVariant, Color.GRAY)
        val density = context.resources.displayMetrics.density
        val stroke1_5dp = (1.5f * density).toInt()
        val stroke1dp = (1f * density).toInt()

        binding.tileFormatJpg.setCardBackgroundColor(ColorStateList.valueOf(if (isJpg) colorSecondaryContainer else colorSurfaceContainerHigh))
        binding.tileFormatJpg.strokeColor = if (isJpg) colorSecondary else colorOutlineVariant
        binding.tileFormatJpg.strokeWidth = if (isJpg) stroke1_5dp else stroke1dp
        binding.tileFormatJpg.radius = (if (isJpg) 10f else 14f) * density

        binding.tileFormatRaw.setCardBackgroundColor(ColorStateList.valueOf(if (isRaw) colorPrimaryContainer else colorSurfaceContainerHigh))
        binding.tileFormatRaw.strokeColor = if (isRaw) colorPrimary else colorOutlineVariant
        binding.tileFormatRaw.strokeWidth = if (isRaw) stroke1_5dp else stroke1dp
        binding.tileFormatRaw.radius = (if (isRaw) 10f else 14f) * density
    }

    fun updateHalfFrameCardStyling(binding: FragmentSettingsBinding, enabled: Boolean, animated: Boolean = true) {
        val colorOnTertiaryContainer = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnTertiaryContainer, Color.WHITE)
        val colorTertiary = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorTertiary, Color.WHITE)
        val colorOnSurfaceVariant = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY)

        if (enabled) {
            binding.cardTrayHalfFrame.setContainerRole(BentoContainerRole.TERTIARY_CONTAINER, animated)
            binding.txtTitleHalfFrame.setTextColor(colorOnTertiaryContainer)
            binding.txtSubHalfFrame.setTextColor(colorOnTertiaryContainer)
        } else {
            binding.cardTrayHalfFrame.setContainerRole(BentoContainerRole.SURFACE_CONTAINER_LOW, animated)
            binding.txtTitleHalfFrame.setTextColor(colorTertiary)
            binding.txtSubHalfFrame.setTextColor(colorOnSurfaceVariant)
        }
        binding.cardTrayHalfFrame.strokeWidth = 0
    }

    fun updateStartupCards(binding: FragmentSettingsBinding, defaultStartup: String, enableCamera: Boolean, enablePlayground: Boolean) {
        val colorPrimary = MaterialColors.getColor(binding.root, android.R.attr.colorPrimary, Color.WHITE)
        val colorPrimaryContainer = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorPrimaryContainer, Color.DKGRAY)
        val colorTertiary = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorTertiary, colorPrimary)
        val colorTertiaryContainer = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorTertiaryContainer, colorPrimaryContainer)
        val colorSurface = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorSurface, Color.WHITE)
        val colorOutlineVariant = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOutlineVariant, Color.GRAY)
        val colorOnSurface = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnSurface, Color.WHITE)
        val colorOnPrimaryContainer = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnPrimaryContainer, colorPrimary)
        val colorOnTertiaryContainer = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnTertiaryContainer, colorTertiary)
        val density = context.resources.displayMetrics.density
        val stroke1dp = (1f * density).toInt()
        val stroke2dp = (2f * density).toInt()
        val radius10dp = (10f * density).toInt()
        val radius14dp = (14f * density).toInt()

        val isCameraActive = defaultStartup == SettingsFragment.STARTUP_CAMERA

        binding.cardStartupCamera.radius = (if (isCameraActive) radius10dp else radius14dp).toFloat()
        binding.cardStartupCamera.setCardBackgroundColor(ColorStateList.valueOf(if (isCameraActive) colorPrimaryContainer else colorSurface))
        binding.cardStartupCamera.strokeColor = if (isCameraActive) colorPrimary else colorOutlineVariant
        binding.cardStartupCamera.strokeWidth = if (isCameraActive) stroke2dp else stroke1dp
        binding.txtStartupCameraTitle.setTextColor(if (isCameraActive) colorOnPrimaryContainer else colorOnSurface)

        binding.cardStartupPlayground.radius = (if (!isCameraActive) radius10dp else radius14dp).toFloat()
        binding.cardStartupPlayground.setCardBackgroundColor(ColorStateList.valueOf(if (!isCameraActive) colorTertiaryContainer else colorSurface))
        binding.cardStartupPlayground.strokeColor = if (!isCameraActive) colorTertiary else colorOutlineVariant
        binding.cardStartupPlayground.strokeWidth = if (!isCameraActive) stroke2dp else stroke1dp
        binding.txtStartupPlaygroundTitle.setTextColor(if (!isCameraActive) colorOnTertiaryContainer else colorOnSurface)

        binding.switchEnableCamera.isChecked = enableCamera
        binding.switchEnablePlayground.isChecked = enablePlayground

        if (enableCamera && !enablePlayground) {
            binding.switchEnableCamera.isEnabled = false
            binding.switchEnablePlayground.isEnabled = true
        } else if (!enableCamera && enablePlayground) {
            binding.switchEnableCamera.isEnabled = true
            binding.switchEnablePlayground.isEnabled = false
        } else {
            binding.switchEnableCamera.isEnabled = true
            binding.switchEnablePlayground.isEnabled = true
        }
    }

    fun styleTierButton(btn: MaterialButton, title: String, subtitle: String, isActive: Boolean) {
        btn.setPadding(0, 0, 0, 0)
        btn.insetTop = 0
        btn.insetBottom = 0
        btn.iconPadding = 0
        btn.minWidth = 0
        btn.minimumWidth = 0
        btn.isSingleLine = false
        btn.maxLines = 2
        btn.text = CameraBentoDeckView.formatCompactTierLabel(
            context = context,
            title = title,
            subtitle = subtitle,
            isActive = isActive
        )
        val density = context.resources.displayMetrics.density
        val stroke1dp = (1f * density).toInt()
        val stroke1_5dp = (1.5f * density).toInt()
        val colorPrimary = MaterialColors.getColor(btn, android.R.attr.colorPrimary, Color.WHITE)
        val colorPrimaryContainer = MaterialColors.getColor(btn, com.google.android.material.R.attr.colorPrimaryContainer, Color.DKGRAY)
        val colorSurfaceContainerHigh = MaterialColors.getColor(btn, com.google.android.material.R.attr.colorSurfaceContainerHigh, Color.GRAY)
        val colorOutlineVariant = MaterialColors.getColor(btn, com.google.android.material.R.attr.colorOutlineVariant, Color.DKGRAY)
        val targetRadius = if (isActive) 10f * density else 14f * density
        btn.shapeAppearanceModel = ShapeAppearanceModel.builder().setAllCornerSizes(targetRadius).build()

        if (isActive) {
            btn.backgroundTintList = ColorStateList.valueOf(colorPrimaryContainer)
            btn.strokeColor = ColorStateList.valueOf(colorPrimary)
            btn.strokeWidth = stroke1_5dp
            btn.cornerRadius = targetRadius.toInt()
        } else {
            btn.backgroundTintList = ColorStateList.valueOf(colorSurfaceContainerHigh)
            btn.strokeColor = ColorStateList.valueOf(colorOutlineVariant)
            btn.strokeWidth = stroke1dp
            btn.cornerRadius = targetRadius.toInt()
        }
    }

    fun updateUiTierButtons(binding: FragmentSettingsBinding, checkedId: Int) {
        styleTierButton(binding.btnTierPs, context.getString(R.string.settings_tier_ps_tag), context.getString(R.string.settings_tier_ps_sub), checkedId == R.id.btn_tier_ps)
        styleTierButton(binding.btnTierFilm, context.getString(R.string.settings_tier_film_tag), context.getString(R.string.settings_tier_film_sub), checkedId == R.id.btn_tier_film)
        styleTierButton(binding.btnTierPro, context.getString(R.string.settings_tier_pro_tag), context.getString(R.string.settings_tier_pro_sub), checkedId == R.id.btn_tier_pro)
        styleTierButton(binding.btnTierCustom, context.getString(R.string.settings_tier_custom_tag), context.getString(R.string.settings_tier_custom_sub), checkedId == R.id.btn_tier_custom)
    }

    fun syncTierSelection(binding: FragmentSettingsBinding, tierKey: String) {
        val tier = CameraUiTier.fromKey(tierKey)
        val checkId = when (tier) {
            CameraUiTier.POINT_AND_SHOOT -> R.id.btn_tier_ps
            CameraUiTier.FILM -> R.id.btn_tier_film
            CameraUiTier.PRO -> R.id.btn_tier_pro
            CameraUiTier.CUSTOM -> R.id.btn_tier_custom
        }
        if (binding.toggleUiTiers.checkedButtonId != checkId) {
            binding.toggleUiTiers.check(checkId)
        }
        updateUiTierButtons(binding, checkId)
        binding.toggleUiTiers.enforceConcentricShapes()
    }
}
