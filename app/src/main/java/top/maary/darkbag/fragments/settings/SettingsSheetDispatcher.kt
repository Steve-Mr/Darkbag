package top.maary.darkbag.fragments.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.fragment.app.FragmentManager
import top.maary.darkbag.R
import top.maary.darkbag.fragments.SettingsFragment
import top.maary.darkbag.ui.BentoSelectionBottomSheet
import top.maary.darkbag.ui.RawVideoSettingsBottomSheet
import top.maary.darkbag.utils.CameraRepository
import top.maary.darkbag.utils.DualLensPairPreference
import top.maary.darkbag.utils.LutManager
import top.maary.darkbag.utils.MultiCameraCountPreference
import top.maary.darkbag.viewmodel.SettingsViewModel

/**
 * Dispatcher responsible for launching selection sheets and bottom dialogs from the settings interface,
 * updating SettingsViewModel upon user selections.
 */
class SettingsSheetDispatcher(
    private val fragmentManager: FragmentManager,
    private val context: Context,
    private val cameraRepository: CameraRepository,
    private val viewModel: SettingsViewModel
) {

    fun showDefaultLensSheet() {
        val lenses = cameraRepository.getAllFocalLengthPresets()
        val names = lenses.map { it.name }
        val savedLensId = viewModel.preferences.value.defaultLensId
        val selectedIndex = lenses.indexOfFirst { it.sensorId == savedLensId }.coerceAtLeast(0)
        BentoSelectionBottomSheet.show(
            fragmentManager = fragmentManager,
            title = context.getString(R.string.pref_default_lens),
            options = names,
            selectedIndex = selectedIndex,
            badge = "LENS"
        ) { which ->
            viewModel.updateDefaultLensId(lenses[which].sensorId)
        }
    }

    fun showDefaultFocal1xSheet() {
        val mainWide = cameraRepository.getMainWideLens()
        val presets1x = if (mainWide != null) cameraRepository.get1xPresets(mainWide) else emptyList()
        val names1x = presets1x.map { it.name }
        if (names1x.isNotEmpty()) {
            val savedFocal = viewModel.preferences.value.defaultFocal1xName ?: names1x.first()
            val selectedIndex = names1x.indexOf(savedFocal).coerceAtLeast(0)
            BentoSelectionBottomSheet.show(
                fragmentManager = fragmentManager,
                title = context.getString(R.string.pref_default_focal_1x),
                options = names1x,
                selectedIndex = selectedIndex,
                badge = "1.0X"
            ) { which ->
                viewModel.updateDefaultFocal1x(names1x[which])
            }
        }
    }

    fun showTargetLogSheet(onLutIncompatible: (String) -> Unit) {
        val currentLog = viewModel.preferences.value.targetLog
        val selectedIndex = SettingsFragment.LOG_CURVES.indexOf(currentLog).coerceAtLeast(0)
        val logOptions = SettingsFragment.LOG_CURVES.map { curve ->
            if (curve == "None") context.getString(R.string.pref_option_none) else curve
        }
        BentoSelectionBottomSheet.show(
            fragmentManager = fragmentManager,
            title = context.getString(R.string.target_log_hint),
            options = logOptions,
            selectedIndex = selectedIndex,
            badge = "LOG"
        ) { which ->
            val selected = SettingsFragment.LOG_CURVES[which]
            val activeLut = viewModel.preferences.value.activeLut.takeIf { it != "None" }
            val lutMgr = LutManager(context)
            if (activeLut != null && !lutMgr.isLutCompatibleWithLog(activeLut, selected)) {
                viewModel.updateActiveLut("None", null)
                onLutIncompatible(selected)
            }
            viewModel.updateTargetLog(selected)
        }
    }

    fun showHdrFusionSheet() {
        val savedFusion = viewModel.preferences.value.hdrFusionMode
        val selectedIndex = SettingsFragment.HDR_FUSION_MODES.indexOf(savedFusion).coerceAtLeast(0)
        BentoSelectionBottomSheet.show(
            fragmentManager = fragmentManager,
            title = context.getString(R.string.pref_hdr_fusion_mode_title),
            options = listOf(
                context.getString(R.string.pref_fusion_auto),
                context.getString(R.string.pref_fusion_spatial),
                context.getString(R.string.pref_fusion_sabre),
                context.getString(R.string.pref_fusion_classic)
            ),
            selectedIndex = selectedIndex,
            badge = "COMPUTE",
            descriptions = listOf(
                context.getString(R.string.pref_fusion_auto_desc),
                context.getString(R.string.pref_fusion_spatial_desc),
                context.getString(R.string.pref_fusion_sabre_desc),
                context.getString(R.string.pref_fusion_classic_desc)
            )
        ) { which ->
            viewModel.updateHdrFusionMode(SettingsFragment.HDR_FUSION_MODES[which])
        }
    }

    fun showHdrBurstSheet() {
        val savedBurst = viewModel.preferences.value.hdrBurstCount.toString()
        val selectedIndex = SettingsFragment.BURST_SIZES.indexOf(savedBurst).coerceAtLeast(0)
        BentoSelectionBottomSheet.show(
            fragmentManager = fragmentManager,
            title = context.getString(R.string.pref_burst_frames),
            options = SettingsFragment.BURST_SIZES.map { context.getString(R.string.pref_burst_frames_count_format, it) },
            selectedIndex = selectedIndex,
            badge = "BURST"
        ) { which ->
            viewModel.updateHdrBurstCount(SettingsFragment.BURST_SIZES[which].toIntOrNull() ?: 5)
        }
    }

    fun showUnderexposureSheet() {
        val saved = viewModel.preferences.value.hdrUnderexposureMode
        val selectedIndex = SettingsFragment.HDR_UNDEREXPOSURE_MODES.indexOf(saved).coerceAtLeast(0)
        BentoSelectionBottomSheet.show(
            fragmentManager = fragmentManager,
            title = context.getString(R.string.pref_hdr_underexposure),
            options = SettingsFragment.HDR_UNDEREXPOSURE_MODES.map { mode ->
                when {
                    mode == "Off" -> context.getString(R.string.pref_option_off)
                    mode.startsWith("Dynamic") -> context.getString(R.string.pref_option_dynamic)
                    else -> mode
                }
            },
            selectedIndex = selectedIndex,
            badge = "EXPOSURE"
        ) { which ->
            viewModel.updateHdrUnderexposureMode(SettingsFragment.HDR_UNDEREXPOSURE_MODES[which])
        }
    }

    fun showColorEngineSheet() {
        val savedIndex = viewModel.preferences.value.colorEngineMode.coerceIn(0, SettingsFragment.COLOR_ENGINE_MODES.size - 1)
        BentoSelectionBottomSheet.show(
            fragmentManager = fragmentManager,
            title = context.getString(R.string.pref_color_engine_mode),
            options = SettingsFragment.COLOR_ENGINE_MODES,
            selectedIndex = savedIndex,
            badge = "MATRIX"
        ) { which ->
            viewModel.updateColorEngineMode(which)
        }
    }

    fun showAntibandingSheet() {
        val saved = viewModel.preferences.value.antibandingMode
        val selectedIndex = SettingsFragment.ANTIBANDING_MODES.indexOf(saved).coerceAtLeast(0)
        BentoSelectionBottomSheet.show(
            fragmentManager = fragmentManager,
            title = context.getString(R.string.pref_antibanding),
            options = SettingsFragment.ANTIBANDING_MODES.map { mode ->
                when (mode) {
                    "Auto" -> context.getString(R.string.pref_option_auto)
                    "Off" -> context.getString(R.string.pref_option_off)
                    else -> mode
                }
            },
            selectedIndex = selectedIndex,
            badge = "FLICKER"
        ) { which ->
            viewModel.updateAntibandingMode(SettingsFragment.ANTIBANDING_MODES[which])
        }
    }

    fun showBurstStrategySheet() {
        val saved = viewModel.preferences.value.burstProcessingStrategy
        val selectedIndex = SettingsFragment.BURST_PROCESSING_STRATEGIES.indexOf(saved).coerceAtLeast(0)
        BentoSelectionBottomSheet.show(
            fragmentManager = fragmentManager,
            title = context.getString(R.string.pref_burst_processing_strategy),
            options = listOf(
                context.getString(R.string.pref_burst_strategy_balanced),
                context.getString(R.string.pref_burst_strategy_rapid)
            ),
            selectedIndex = selectedIndex,
            badge = "QUEUE"
        ) { which ->
            viewModel.updateBurstProcessingStrategy(SettingsFragment.BURST_PROCESSING_STRATEGIES[which])
        }
    }

    fun showShutterLongPressSheet(onAudioPermissionNeeded: () -> Unit) {
        val saved = viewModel.preferences.value.shutterLongPressAction
        val selectedIndex = SettingsFragment.SHUTTER_LONG_PRESS_OPTIONS.indexOf(saved).coerceAtLeast(0)
        BentoSelectionBottomSheet.show(
            fragmentManager = fragmentManager,
            title = context.getString(R.string.pref_shutter_long_press_action),
            options = listOf(
                context.getString(R.string.pref_shutter_action_mp4),
                context.getString(R.string.pref_shutter_action_raw_video),
                context.getString(R.string.pref_option_disabled)
            ),
            selectedIndex = selectedIndex,
            badge = "SHUTTER"
        ) { which ->
            val selectedAction = SettingsFragment.SHUTTER_LONG_PRESS_OPTIONS[which]
            viewModel.updateShutterLongPressAction(selectedAction)
            if (selectedAction == SettingsFragment.SHUTTER_LONG_PRESS_MP4 || selectedAction == SettingsFragment.SHUTTER_LONG_PRESS_RAW_VIDEO) {
                onAudioPermissionNeeded()
            }
        }
    }

    fun showRawVideoSettingsSheet() {
        RawVideoSettingsBottomSheet.show(fragmentManager) {}
    }

    fun showExternalViewerSheet() {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse("content://dummy/image.jpg"), "image/*")
        }
        val resolveInfos = context.packageManager.queryIntentActivities(intent, 0)
        val defaultName = context.getString(R.string.pref_external_viewer_default)
        val appNames = mutableListOf(defaultName)
        val packageNames = mutableListOf("")

        for (resolveInfo in resolveInfos) {
            appNames.add(resolveInfo.loadLabel(context.packageManager).toString())
            packageNames.add(resolveInfo.activityInfo.packageName)
        }

        val savedName = viewModel.preferences.value.externalViewerName ?: defaultName
        val selectedIndex = appNames.indexOf(savedName).coerceAtLeast(0)
        BentoSelectionBottomSheet.show(
            fragmentManager = fragmentManager,
            title = context.getString(R.string.settings_ui_external_viewer),
            options = appNames,
            selectedIndex = selectedIndex,
            badge = "VIEWER"
        ) { which ->
            viewModel.updateExternalViewer(appNames[which], packageNames[which])
        }
    }

    fun showDngCompressionSheet() {
        val savedIndex = viewModel.preferences.value.dngCompressionMode.coerceIn(0, SettingsFragment.DNG_COMPRESSION_MODES.size - 1)
        BentoSelectionBottomSheet.show(
            fragmentManager = fragmentManager,
            title = context.getString(R.string.pref_dng_compression),
            options = listOf(
                context.getString(R.string.pref_dng_compress_lossless),
                context.getString(R.string.pref_dng_compress_uncompressed)
            ),
            selectedIndex = savedIndex,
            badge = "CODEC"
        ) { which ->
            viewModel.updateDngCompressionMode(which)
        }
    }

    fun showDngFormatSheet() {
        val rawOutputOptions = listOf(
            context.getString(R.string.raw_output_type_bayer),
            context.getString(R.string.raw_output_type_linear)
        )
        val savedFormatIndex = viewModel.preferences.value.rawOutputFormatIndex.coerceIn(0, rawOutputOptions.size - 1)
        BentoSelectionBottomSheet.show(
            fragmentManager = fragmentManager,
            title = context.getString(R.string.setting_raw_format_hint),
            options = rawOutputOptions,
            selectedIndex = savedFormatIndex,
            badge = "CFA"
        ) { which ->
            viewModel.updateRawOutputFormatIndex(which)
        }
    }

    fun showMultiCameraCountSheet() {
        val countOptions = listOf(
            MultiCameraCountPreference.AUTO_MAX.getDisplayName(context),
            MultiCameraCountPreference.DUAL.getDisplayName(context),
            MultiCameraCountPreference.TRIPLE.getDisplayName(context)
        )
        val countKeys = listOf(
            MultiCameraCountPreference.AUTO_MAX.key,
            MultiCameraCountPreference.DUAL.key,
            MultiCameraCountPreference.TRIPLE.key
        )
        val savedCountKey = viewModel.preferences.value.multiCameraCountPref
        val currentCountIndex = countKeys.indexOf(savedCountKey).coerceAtLeast(0)
        BentoSelectionBottomSheet.show(
            fragmentManager = fragmentManager,
            title = context.getString(R.string.pref_multi_camera_count),
            options = countOptions,
            selectedIndex = currentCountIndex,
            badge = "MULTI-CAM"
        ) { which ->
            viewModel.updateMultiCameraCountPref(countKeys[which])
        }
    }

    fun showMultiCameraDualPairSheet() {
        val pairOptions = listOf(
            DualLensPairPreference.WIDE_ULTRAWIDE.getDisplayName(context),
            DualLensPairPreference.WIDE_TELE.getDisplayName(context),
            DualLensPairPreference.ULTRAWIDE_TELE.getDisplayName(context)
        )
        val pairKeys = listOf(
            DualLensPairPreference.WIDE_ULTRAWIDE.key,
            DualLensPairPreference.WIDE_TELE.key,
            DualLensPairPreference.ULTRAWIDE_TELE.key
        )
        val savedPairKey = viewModel.preferences.value.multiCameraDualPair
        val currentPairIndex = pairKeys.indexOf(savedPairKey).coerceAtLeast(0)
        BentoSelectionBottomSheet.show(
            fragmentManager = fragmentManager,
            title = context.getString(R.string.pref_multi_camera_dual_pair),
            options = pairOptions,
            selectedIndex = currentPairIndex,
            badge = "DUAL"
        ) { which ->
            viewModel.updateMultiCameraDualPair(pairKeys[which])
        }
    }
}
