package top.maary.darkbag.fragments

import top.maary.darkbag.utils.MediaStoreUtils
import top.maary.darkbag.utils.DebugLogManager
import top.maary.darkbag.utils.CameraRepository
import top.maary.darkbag.utils.CacheManager
import top.maary.darkbag.utils.MultiCameraHelper
import top.maary.darkbag.utils.MultiCameraCountPreference
import top.maary.darkbag.utils.DualLensPairPreference
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.shape.ShapeAppearanceModel
import top.maary.darkbag.ui.BentoSelectionBottomSheet
import top.maary.darkbag.ui.RawVideoSettingsBottomSheet
import top.maary.darkbag.ui.ViewfinderDeckCustomizerSheet
import top.maary.darkbag.viewmodel.CameraUiTier
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Build
import android.util.Size
import android.util.TypedValue
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.navigation.Navigation
import top.maary.darkbag.R
import top.maary.darkbag.databinding.FragmentSettingsBinding
import top.maary.darkbag.ui.CameraBentoDeckView
import com.google.android.material.color.MaterialColors
import java.io.File

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private lateinit var prefs: SharedPreferences
    private lateinit var cameraRepository: CameraRepository

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[android.Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseGranted = permissions[android.Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fineGranted || coarseGranted) {
            prefs.edit().putBoolean(KEY_SAVE_LOCATION, true).apply()
            syncLocationSettingState()
        } else {
            prefs.edit().putBoolean(KEY_SAVE_LOCATION, false).apply()
            syncLocationSettingState()
            Toast.makeText(context, R.string.location_permission_denied, Toast.LENGTH_LONG).show()
        }
    }

    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Toast.makeText(context, R.string.toast_audio_permission_granted, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, R.string.error_mic_permission_silent_video, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        cameraRepository = CameraRepository(requireContext())

        // Apply Edge-to-Edge Insets
        ViewCompat.setOnApplyWindowInsetsListener(binding.cardSettingsHeader) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val lp = v.layoutParams as? ViewGroup.MarginLayoutParams
            val defaultMarginTop = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8f, resources.displayMetrics).toInt()
            lp?.topMargin = defaultMarginTop + systemBars.top
            v.layoutParams = lp
            insets
        }

        val initialBottom = resources.getDimensionPixelSize(R.dimen.margin_xlarge)
        ViewCompat.setOnApplyWindowInsetsListener(binding.nestedScrollView) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, initialBottom + systemBars.bottom)
            insets
        }

        setupToolbar()
        setupAboutSection()
        setupAdvancedSection()
        setupMenus()
        setupStartupSettings()
        setupCheckboxes()
        setupStoragePickers()
        setupStorageSettings()
        setupCacheManagement()
        setupNavigation()
        updateDebugStats()
        updateDebugVisibility()
        updateAllBentoTiles()
    }

    private var isAdvancedExpanded = false
    private fun setupAdvancedSection() {
        binding.headerAdvancedToggle.setOnClickListener {
            isAdvancedExpanded = !isAdvancedExpanded
            binding.containerAdvancedContent.visibility = if (isAdvancedExpanded) View.VISIBLE else View.GONE
            binding.ivAdvancedArrow.animate()
                .rotation(if (isAdvancedExpanded) 180f else 0f)
                .setDuration(200)
                .start()
        }
    }

    override fun onResume() {
        super.onResume()
        updateDebugStats()
        updateAllBentoTiles()
        updateCheckboxStates()
        syncLocationSettingState()
        updateStorageVisibility()
        updateCacheSize()
    }

    private var aboutClickCount = 0
    private fun setupAboutSection() {
        val versionName = try {
            val packageInfo = requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)
            packageInfo.versionName
        } catch (_: Exception) {
            "1.0.0"
        }

        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        binding.tvAppVersion.text = "v$versionName ($abi)"
        binding.txtArchInfo.visibility = View.GONE

        binding.cardAbout.setOnClickListener {
            aboutClickCount++
            if (aboutClickCount >= 5) {
                if (!prefs.getBoolean(KEY_DEBUG_ENABLED, false)) {
                    prefs.edit().putBoolean(KEY_DEBUG_ENABLED, true).apply()
                    updateDebugVisibility()
                    Toast.makeText(requireContext(), R.string.experimental_unlocked_toast, Toast.LENGTH_SHORT).show()
                }
                aboutClickCount = 0
            }
        }

        binding.btnReplayOnboarding.text = CameraBentoDeckView.formatProTelemetry(
            label = "SETUP WIZARD",
            value = getString(R.string.settings_btn_replay_onboarding),
            isTabActive = false,
            context = requireContext()
        )
        binding.btnReplayOnboarding.setOnClickListener {
            val bundle = Bundle().apply {
                putBoolean("force_onboarding", true)
            }
            Navigation.findNavController(requireActivity(), R.id.fragment_container)
                .navigate(R.id.action_settings_to_permissions, bundle)
        }
    }

    private fun updateDebugVisibility() {
        val isDebug = prefs.getBoolean(KEY_DEBUG_ENABLED, false)
        binding.sectionDebug.visibility = if (isDebug) View.VISIBLE else View.GONE
    }

    private fun updateDebugStats() {
        val logs = DebugLogManager.getLogs()
        binding.tvDebugStats.text = if (logs.isNotBlank()) logs else "No timing stats logged yet."
    }

    private fun setupToolbar() {
        binding.btnSettingsBack.setOnClickListener {
            Navigation.findNavController(requireActivity(), R.id.fragment_container).navigateUp()
        }
    }

    private fun setupNavigation() {
        binding.btnManageLuts.setOnClickListener {
            Navigation.findNavController(requireActivity(), R.id.fragment_container)
                .navigate(SettingsFragmentDirections.actionSettingsToLutManagement())
        }
    }

    private fun isLutActive(): Boolean {
        val log = prefs.getString(KEY_TARGET_LOG, "None")
        val lut = prefs.getString(KEY_ACTIVE_LUT, null)
        return (log != null && log != "None") || lut != null
    }

    // =========================================================================
    // Bento Tile Telemetry Updaters
    // =========================================================================

    private fun updateDefaultLensTile() {
        val ctx = context ?: return
        val lenses = cameraRepository.getAllFocalLengthPresets()
        val savedLensId = prefs.getString(KEY_DEFAULT_LENS_ID, null)
        val currentLens = lenses.find { it.sensorId == savedLensId }
            ?: lenses.find { it.multiplier in 0.95f..1.05f && !it.isZoomPreset }
            ?: lenses.firstOrNull()
        val lensName = currentLens?.name ?: "Wide (1.0x)"
        binding.btnMenuDefaultLens.text = CameraBentoDeckView.formatProTelemetry(
            label = "DEFAULT LENS",
            value = lensName,
            isTabActive = false,
            isValueManual = true,
            context = ctx
        )
    }

    private fun updateDefaultFocal1xTile() {
        val ctx = context ?: return
        val mainWide = cameraRepository.getMainWideLens()
        val presets1x = if (mainWide != null) cameraRepository.get1xPresets(mainWide) else emptyList()
        val baseFocalName = presets1x.firstOrNull()?.name ?: "24mm"
        val savedFocal = prefs.getString(KEY_DEFAULT_FOCAL_1X, baseFocalName) ?: baseFocalName
        binding.btnMenuDefaultFocal1x.text = CameraBentoDeckView.formatProTelemetry(
            label = "1.0X FOCAL BASE",
            value = savedFocal,
            isTabActive = false,
            isValueManual = true,
            context = ctx
        )
    }

    private fun updateTargetLogTile() {
        val ctx = context ?: return
        val savedLog = prefs.getString(KEY_TARGET_LOG, "None") ?: "None"
        val isLogActive = savedLog != "None"
        val rawLog = savedLog.replace(" (Rec.709)", "").replace(" (BT.709)", "")
        val displayLog = if (rawLog.length > 14) {
            rawLog.take(13) + "…"
        } else {
            rawLog
        }
        binding.btnMenuTargetLog.text = CameraBentoDeckView.formatProTelemetry(
            label = "TARGET LOG",
            value = displayLog,
            isTabActive = isLogActive,
            isValueManual = isLogActive,
            context = ctx
        )
    }

    private fun updateLutsTile() {
        val ctx = context ?: return
        val activeLut = prefs.getString(KEY_ACTIVE_LUT, null)
        val rawName = activeLut?.removeSuffix(".cube")?.removeSuffix(".CUBE") ?: "Classic Neg"
        val displayLut = if (rawName.length > 14) {
            rawName.take(13) + "…"
        } else {
            rawName
        }
        binding.btnManageLuts.text = CameraBentoDeckView.formatProTelemetry(
            label = "3D LUT RECIPES",
            value = displayLut,
            isTabActive = activeLut != null,
            isValueManual = true,
            context = ctx
        )
    }

    private fun updateHdrFusionTile() {
        val ctx = context ?: return
        val savedFusion = prefs.getString(KEY_HDR_FUSION_MODE, HDR_FUSION_SABRE) ?: HDR_FUSION_SABRE
        val displayLabel = when (savedFusion) {
            HDR_FUSION_SABRE -> "Sabre (Default)"
            HDR_FUSION_SPATIAL_RCD -> "Spatial Merge + RCD"
            HDR_FUSION_CLASSIC -> "Classic (Wiener)"
            HDR_FUSION_AUTO -> "Auto (Sabre Zoom)"
            else -> savedFusion
        }
        binding.btnMenuHdrFusionMode.text = CameraBentoDeckView.formatProTelemetry(
            label = "HDR+ MERGE",
            value = displayLabel,
            isTabActive = true,
            isValueManual = true,
            context = ctx
        )
    }

    private fun updateHdrBurstTile() {
        val ctx = context ?: return
        val savedBurst = prefs.getString(KEY_HDR_BURST_COUNT, "5") ?: "5"
        binding.btnMenuHdrBurstMode.text = CameraBentoDeckView.formatProTelemetry(
            label = "BURST FRAMES",
            value = "$savedBurst Frames",
            isTabActive = false,
            isValueManual = true,
            context = ctx
        )
    }

    private fun updateUnderexposureTile() {
        val ctx = context ?: return
        val saved = prefs.getString(KEY_HDR_UNDEREXPOSURE_MODE, "Dynamic") ?: "Dynamic"
        val displayValue = when {
            saved.endsWith("EV") -> saved
            saved == "Off" || saved.startsWith("Off") -> saved
            saved.startsWith("Dynamic") -> "Dynamic"
            else -> "$saved EV"
        }
        binding.btnMenuUnderexposureMode.text = CameraBentoDeckView.formatProTelemetry(
            label = "UNDEREXPOSURE",
            value = displayValue,
            isTabActive = false,
            isValueManual = saved != "Off",
            context = ctx
        )
    }

    private fun updateColorEngineTile() {
        val ctx = context ?: return
        val savedIndex = prefs.getInt(KEY_COLOR_ENGINE_MODE, 2).coerceIn(0, COLOR_ENGINE_MODES.size - 1)
        val name = when (savedIndex) {
            0 -> "Khronos Neutral"
            1 -> "Natural Filmic"
            2 -> "Sony Uchimura"
            3 -> "Legacy ACES"
            else -> COLOR_ENGINE_MODES[savedIndex]
        }
        binding.btnMenuColorEngine.text = CameraBentoDeckView.formatProTelemetry(
            label = "COLOR ENGINE",
            value = name,
            isTabActive = false,
            isValueManual = true,
            context = ctx
        )
    }

    private fun updateAntibandingTile() {
        val ctx = context ?: return
        val saved = prefs.getString(KEY_ANTIBANDING, "Auto") ?: "Auto"
        val display = saved
        binding.btnMenuAntibanding.text = CameraBentoDeckView.formatProTelemetry(
            label = "ANTIBANDING",
            value = display,
            isTabActive = false,
            isValueManual = saved != "Off",
            context = ctx
        )
    }

    private fun updateBurstStrategyTile() {
        val ctx = context ?: return
        val saved = prefs.getString(KEY_BURST_PROCESSING_STRATEGY, BURST_STRATEGY_BALANCED) ?: BURST_STRATEGY_BALANCED
        val display = if (saved == BURST_STRATEGY_BALANCED) "Balanced" else "Rapid Burst (Max)"
        binding.btnMenuBurstProcessingStrategy.text = CameraBentoDeckView.formatProTelemetry(
            label = "BURST STRATEGY",
            value = display,
            isTabActive = false,
            isValueManual = true,
            context = ctx
        )
    }

    private fun updateShutterLongPressTile() {
        val ctx = context ?: return
        val saved = prefs.getString(KEY_SHUTTER_LONG_PRESS_ACTION, SHUTTER_LONG_PRESS_MP4) ?: SHUTTER_LONG_PRESS_MP4
        val display = when (saved) {
            SHUTTER_LONG_PRESS_MP4 -> "Standard MP4"
            SHUTTER_LONG_PRESS_RAW_VIDEO -> "RAW Video (10-bit)"
            else -> "Disabled"
        }
        binding.btnMenuShutterLongPress.text = CameraBentoDeckView.formatProTelemetry(
            label = "SHUTTER HOLD ACTION",
            value = display,
            isTabActive = false,
            isValueManual = saved != SHUTTER_LONG_PRESS_DISABLED,
            context = ctx
        )
    }

    private fun updateRawVideoTile() {
        val ctx = context ?: return
        val savedFps = prefs.getString(KEY_RAW_VIDEO_FPS, "24") ?: "24"
        val savedRes = prefs.getString(KEY_RAW_VIDEO_RESOLUTION, DEFAULT_RAW_VIDEO_RESOLUTION) ?: DEFAULT_RAW_VIDEO_RESOLUTION
        val shortRes = when {
            savedRes.contains("4K") -> "4K UHD"
            savedRes.contains("2K") -> "2K Open Gate"
            savedRes.contains("1080p") -> "1080p"
            else -> "Max Native"
        }
        binding.btnMenuRawVideo.text = CameraBentoDeckView.formatProTelemetry(
            label = "RAW VIDEO",
            value = "$savedFps FPS · $shortRes",
            isTabActive = false,
            isValueManual = true,
            context = ctx
        )
    }

    private fun updateExternalViewerTile() {
        val ctx = context ?: return
        val defaultName = getString(R.string.pref_external_viewer_default)
        val savedName = prefs.getString(KEY_EXTERNAL_VIEWER_NAME, defaultName) ?: defaultName
        binding.btnMenuExternalViewer.text = CameraBentoDeckView.formatProTelemetry(
            label = "EXTERNAL VIEWER",
            value = savedName,
            isTabActive = false,
            isValueManual = savedName != defaultName,
            context = ctx
        )
    }

    private fun updateDngCompressionTile() {
        val ctx = context ?: return
        val savedDngIndex = prefs.getInt(KEY_DNG_COMPRESSION_MODE, 0).coerceIn(0, DNG_COMPRESSION_MODES.size - 1)
        val modeName = when (savedDngIndex) {
            0 -> "16-bit Lossless JPEG"
            else -> "Uncompressed"
        }
        binding.btnMenuDngCompression.text = CameraBentoDeckView.formatProTelemetry(
            label = "DNG COMPRESS",
            value = modeName,
            isTabActive = true,
            isValueManual = true,
            context = ctx
        )
    }

    private fun updateDngFormatTile() {
        val ctx = context ?: return
        val savedFormatIndex = prefs.getInt(KEY_RAW_OUTPUT_TYPE, 0).coerceIn(0, RAW_OUTPUT_TYPES.size - 1)
        val formatName = when (savedFormatIndex) {
            0 -> "Bayer CFA"
            else -> "Linear RGB"
        }
        binding.btnMenuDngFormat.text = CameraBentoDeckView.formatProTelemetry(
            label = "CFA FORMAT",
            value = formatName,
            isTabActive = false,
            isValueManual = true,
            context = ctx
        )
    }

    private fun updateMultiCameraCountTile() {
        val ctx = context ?: return
        val countOptions = listOf(
            MultiCameraCountPreference.AUTO_MAX.title,
            MultiCameraCountPreference.DUAL.title,
            MultiCameraCountPreference.TRIPLE.title
        )
        val countKeys = listOf(
            MultiCameraCountPreference.AUTO_MAX.key,
            MultiCameraCountPreference.DUAL.key,
            MultiCameraCountPreference.TRIPLE.key
        )
        val savedCountKey = prefs.getString(KEY_MULTI_CAMERA_COUNT_PREF, MultiCameraCountPreference.AUTO_MAX.key)
        val currentCountIndex = countKeys.indexOf(savedCountKey).coerceAtLeast(0)
        binding.btnMenuMultiCameraCount.text = CameraBentoDeckView.formatProTelemetry(
            label = "CAMERA COUNT",
            value = countOptions[currentCountIndex],
            isTabActive = false,
            isValueManual = true,
            context = ctx
        )
    }

    private fun updateMultiCameraDualPairTile() {
        val ctx = context ?: return
        val pairOptions = listOf(
            DualLensPairPreference.WIDE_ULTRAWIDE.title,
            DualLensPairPreference.WIDE_TELE.title,
            DualLensPairPreference.ULTRAWIDE_TELE.title
        )
        val pairKeys = listOf(
            DualLensPairPreference.WIDE_ULTRAWIDE.key,
            DualLensPairPreference.WIDE_TELE.key,
            DualLensPairPreference.ULTRAWIDE_TELE.key
        )
        val savedPairKey = prefs.getString(KEY_MULTI_CAMERA_DUAL_PAIR, DualLensPairPreference.WIDE_ULTRAWIDE.key)
        val currentPairIndex = pairKeys.indexOf(savedPairKey).coerceAtLeast(0)
        binding.btnMenuMultiCameraDualPair.text = CameraBentoDeckView.formatProTelemetry(
            label = "MULTI-CAMERA",
            value = pairOptions[currentPairIndex],
            isTabActive = false,
            isValueManual = true,
            context = ctx
        )
    }

    private fun updateAllBentoTiles() {
        updateDefaultLensTile()
        updateDefaultFocal1xTile()
        updateTargetLogTile()
        updateLutsTile()
        updateHdrFusionTile()
        updateHdrBurstTile()
        updateUnderexposureTile()
        updateColorEngineTile()
        updateAntibandingTile()
        updateBurstStrategyTile()
        updateShutterLongPressTile()
        updateRawVideoTile()
        updateExternalViewerTile()
        updateDngCompressionTile()
        updateDngFormatTile()
        updateMultiCameraCountTile()
        updateMultiCameraDualPairTile()
    }

    // =========================================================================
    // Click Listeners Setup
    // =========================================================================

    private fun setupMenus() {
        // 1. Default Lens
        binding.btnMenuDefaultLens.setOnClickListener {
            val lenses = cameraRepository.getAllFocalLengthPresets()
            val names = lenses.map { it.name }
            val savedLensId = prefs.getString(KEY_DEFAULT_LENS_ID, null)
            val selectedIndex = lenses.indexOfFirst { it.sensorId == savedLensId }.coerceAtLeast(0)
            BentoSelectionBottomSheet.show(
                fragmentManager = childFragmentManager,
                title = getString(R.string.pref_default_lens),
                options = names,
                selectedIndex = selectedIndex,
                badge = "LENS"
            ) { which ->
                prefs.edit()
                    .putString(KEY_DEFAULT_LENS_ID, lenses[which].sensorId)
                    .remove(CameraFragment.KEY_SELECTED_LENS_ID)
                    .apply()
                updateDefaultLensTile()
            }
        }

        // 2. 1.0x Focal Base
        binding.btnMenuDefaultFocal1x.setOnClickListener {
            val mainWide = cameraRepository.getMainWideLens()
            val presets1x = if (mainWide != null) cameraRepository.get1xPresets(mainWide) else emptyList()
            val names1x = presets1x.map { it.name }
            if (names1x.isNotEmpty()) {
                val savedFocal = prefs.getString(KEY_DEFAULT_FOCAL_1X, names1x.first())
                val selectedIndex = names1x.indexOf(savedFocal).coerceAtLeast(0)
                BentoSelectionBottomSheet.show(
                    fragmentManager = childFragmentManager,
                    title = getString(R.string.pref_default_focal_1x),
                    options = names1x,
                    selectedIndex = selectedIndex,
                    badge = "1.0X"
                ) { which ->
                    prefs.edit().putString(KEY_DEFAULT_FOCAL_1X, names1x[which]).apply()
                    updateDefaultFocal1xTile()
                }
            }
        }

        // 3. Target Log Curve
        binding.btnMenuTargetLog.setOnClickListener {
            val currentLog = prefs.getString(KEY_TARGET_LOG, "None") ?: "None"
            val selectedIndex = LOG_CURVES.indexOf(currentLog).coerceAtLeast(0)
            BentoSelectionBottomSheet.show(
                fragmentManager = childFragmentManager,
                title = getString(R.string.target_log_hint),
                options = LOG_CURVES,
                selectedIndex = selectedIndex,
                badge = "LOG"
            ) { which ->
                val selected = LOG_CURVES[which]
                val editor = prefs.edit().putString(KEY_TARGET_LOG, selected)
                if (selected == "None") {
                    editor.remove(KEY_ACTIVE_LUT)
                }
                editor.apply()
                updateTargetLogTile()
                updateLutsTile()
                updateCheckboxStates()
                updateStorageVisibility()
            }
        }

        // 4. HDR+ Fusion Engine
        binding.btnMenuHdrFusionMode.setOnClickListener {
            val savedFusion = prefs.getString(KEY_HDR_FUSION_MODE, HDR_FUSION_SABRE) ?: HDR_FUSION_SABRE
            val selectedIndex = HDR_FUSION_MODES.indexOf(savedFusion).coerceAtLeast(0)
            val descriptions = listOf(
                "Adaptive spatial merge or Sabre super-resolution",
                "High detail spatial alignment with RCD demosaicing",
                "Super-resolution & optical subpixel alignment",
                "Traditional Wiener filter HDR+ multi-frame blending"
            )
            BentoSelectionBottomSheet.show(
                fragmentManager = childFragmentManager,
                title = getString(R.string.pref_hdr_fusion_mode_title),
                options = HDR_FUSION_MODES,
                selectedIndex = selectedIndex,
                badge = "COMPUTE",
                descriptions = descriptions
            ) { which ->
                prefs.edit().putString(KEY_HDR_FUSION_MODE, HDR_FUSION_MODES[which]).apply()
                updateHdrFusionTile()
            }
        }

        // 5. HDR+ Burst Frames
        binding.btnMenuHdrBurstMode.setOnClickListener {
            val savedBurst = prefs.getString(KEY_HDR_BURST_COUNT, "5") ?: "5"
            val selectedIndex = BURST_SIZES.indexOf(savedBurst).coerceAtLeast(0)
            val options = BURST_SIZES.map { "$it Frames" }
            BentoSelectionBottomSheet.show(
                fragmentManager = childFragmentManager,
                title = getString(R.string.pref_burst_frames),
                options = options,
                selectedIndex = selectedIndex,
                badge = "BURST"
            ) { which ->
                prefs.edit().putString(KEY_HDR_BURST_COUNT, BURST_SIZES[which]).apply()
                updateHdrBurstTile()
            }
        }

        // 6. HDR+ Underexposure
        binding.btnMenuUnderexposureMode.setOnClickListener {
            val saved = prefs.getString(KEY_HDR_UNDEREXPOSURE_MODE, "Dynamic") ?: "Dynamic"
            val selectedIndex = HDR_UNDEREXPOSURE_MODES.indexOf(saved).coerceAtLeast(0)
            BentoSelectionBottomSheet.show(
                fragmentManager = childFragmentManager,
                title = getString(R.string.pref_hdr_underexposure),
                options = HDR_UNDEREXPOSURE_MODES,
                selectedIndex = selectedIndex,
                badge = "EXPOSURE"
            ) { which ->
                prefs.edit().putString(KEY_HDR_UNDEREXPOSURE_MODE, HDR_UNDEREXPOSURE_MODES[which]).apply()
                updateUnderexposureTile()
            }
        }

        // 7. Color Engine Mode
        binding.btnMenuColorEngine.setOnClickListener {
            val savedIndex = prefs.getInt(KEY_COLOR_ENGINE_MODE, 2).coerceIn(0, COLOR_ENGINE_MODES.size - 1)
            BentoSelectionBottomSheet.show(
                fragmentManager = childFragmentManager,
                title = getString(R.string.pref_color_engine_mode),
                options = COLOR_ENGINE_MODES,
                selectedIndex = savedIndex,
                badge = "MATRIX"
            ) { which ->
                prefs.edit().putInt(KEY_COLOR_ENGINE_MODE, which).apply()
                updateColorEngineTile()
            }
        }

        // 8. Antibanding
        binding.btnMenuAntibanding.setOnClickListener {
            val saved = prefs.getString(KEY_ANTIBANDING, "Auto") ?: "Auto"
            val selectedIndex = ANTIBANDING_MODES.indexOf(saved).coerceAtLeast(0)
            BentoSelectionBottomSheet.show(
                fragmentManager = childFragmentManager,
                title = getString(R.string.pref_antibanding),
                options = ANTIBANDING_MODES,
                selectedIndex = selectedIndex,
                badge = "FLICKER"
            ) { which ->
                prefs.edit().putString(KEY_ANTIBANDING, ANTIBANDING_MODES[which]).apply()
                updateAntibandingTile()
            }
        }

        // 9. Burst Processing Strategy
        binding.btnMenuBurstProcessingStrategy.setOnClickListener {
            val saved = prefs.getString(KEY_BURST_PROCESSING_STRATEGY, BURST_STRATEGY_BALANCED) ?: BURST_STRATEGY_BALANCED
            val selectedIndex = BURST_PROCESSING_STRATEGIES.indexOf(saved).coerceAtLeast(0)
            BentoSelectionBottomSheet.show(
                fragmentManager = childFragmentManager,
                title = getString(R.string.pref_burst_processing_strategy),
                options = BURST_PROCESSING_STRATEGIES,
                selectedIndex = selectedIndex,
                badge = "QUEUE"
            ) { which ->
                prefs.edit().putString(KEY_BURST_PROCESSING_STRATEGY, BURST_PROCESSING_STRATEGIES[which]).apply()
                updateBurstStrategyTile()
            }
        }

        // 10. Shutter Long-Press Action
        binding.btnMenuShutterLongPress.setOnClickListener {
            val saved = prefs.getString(KEY_SHUTTER_LONG_PRESS_ACTION, SHUTTER_LONG_PRESS_MP4) ?: SHUTTER_LONG_PRESS_MP4
            val selectedIndex = SHUTTER_LONG_PRESS_OPTIONS.indexOf(saved).coerceAtLeast(0)
            BentoSelectionBottomSheet.show(
                fragmentManager = childFragmentManager,
                title = getString(R.string.pref_shutter_long_press_action),
                options = SHUTTER_LONG_PRESS_OPTIONS,
                selectedIndex = selectedIndex,
                badge = "SHUTTER"
            ) { which ->
                val selectedAction = SHUTTER_LONG_PRESS_OPTIONS[which]
                prefs.edit().putString(KEY_SHUTTER_LONG_PRESS_ACTION, selectedAction).apply()
                updateShutterLongPressTile()
                if (selectedAction == SHUTTER_LONG_PRESS_MP4 || selectedAction == SHUTTER_LONG_PRESS_RAW_VIDEO) {
                    if (ContextCompat.checkSelfPermission(
                            requireContext(),
                            android.Manifest.permission.RECORD_AUDIO
                        ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                    ) {
                        audioPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                    }
                }
            }
        }

        setupRawVideoSettings()
        setupExternalViewerMenu()
    }

    private fun setupRawVideoSettings() {
        binding.btnMenuRawVideo.setOnClickListener {
            RawVideoSettingsBottomSheet.show(childFragmentManager) {
                updateRawVideoTile()
            }
        }
    }

    private fun setupExternalViewerMenu() {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse("content://dummy/image.jpg"), "image/*")
        }
        val resolveInfos = requireContext().packageManager.queryIntentActivities(intent, 0)
        val defaultName = getString(R.string.pref_external_viewer_default)
        val appNames = mutableListOf(defaultName)
        val packageNames = mutableListOf("")

        for (resolveInfo in resolveInfos) {
            val appName = resolveInfo.loadLabel(requireContext().packageManager).toString()
            val packageName = resolveInfo.activityInfo.packageName
            appNames.add(appName)
            packageNames.add(packageName)
        }

        binding.btnMenuExternalViewer.setOnClickListener {
            val savedName = prefs.getString(KEY_EXTERNAL_VIEWER_NAME, defaultName) ?: defaultName
            val selectedIndex = appNames.indexOf(savedName).coerceAtLeast(0)
            BentoSelectionBottomSheet.show(
                fragmentManager = childFragmentManager,
                title = getString(R.string.settings_ui_external_viewer),
                options = appNames,
                selectedIndex = selectedIndex,
                badge = "VIEWER"
            ) { which ->
                prefs.edit()
                    .putString(KEY_EXTERNAL_VIEWER_NAME, appNames[which])
                    .putString(KEY_EXTERNAL_VIEWER_PACKAGE, packageNames[which])
                    .apply()
                updateExternalViewerTile()
            }
        }

        binding.btnMenuExternalViewer.visibility = if (prefs.getBoolean(KEY_USE_INTERNAL_VIEWER, true)) View.GONE else View.VISIBLE
    }

    private fun updateCheckboxStates() {
        val isLut = isLutActive()
        var isJpg = binding.cbSaveJpg.isChecked
        var isRaw = binding.cbSaveRaw.isChecked

        if (isLut) {
            if (!isJpg) {
                isJpg = true
                binding.cbSaveJpg.isChecked = true
                prefs.edit().putBoolean(KEY_SAVE_JPG, true).apply()
            }
            binding.cbSaveJpg.isEnabled = false
            binding.cbSaveRaw.isEnabled = true
        } else {
            val count = (if (isJpg) 1 else 0) + (if (isRaw) 1 else 0)
            binding.cbSaveJpg.isEnabled = !(isJpg && count == 1)
            binding.cbSaveRaw.isEnabled = !(isRaw && count == 1)
        }

        val colorPrimary = MaterialColors.getColor(binding.root, android.R.attr.colorPrimary, android.graphics.Color.WHITE)
        val colorPrimaryContainer = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorPrimaryContainer, android.graphics.Color.DKGRAY)
        val colorSecondary = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorSecondary, colorPrimary)
        val colorSecondaryContainer = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorSecondaryContainer, colorPrimaryContainer)
        val colorSurfaceContainerHigh = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorSurfaceContainerHigh, android.graphics.Color.DKGRAY)
        val colorOutlineVariant = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOutlineVariant, android.graphics.Color.GRAY)
        val density = resources.displayMetrics.density
        val stroke1_5dp = (1.5f * density).toInt()
        val stroke1dp = (1f * density).toInt()

        // JPG Card dynamic expressive morphing (SecondaryContainer when selected)
        binding.tileFormatJpg.setCardBackgroundColor(android.content.res.ColorStateList.valueOf(if (isJpg) colorSecondaryContainer else colorSurfaceContainerHigh))
        binding.tileFormatJpg.strokeColor = if (isJpg) colorSecondary else colorOutlineVariant
        binding.tileFormatJpg.strokeWidth = if (isJpg) stroke1_5dp else stroke1dp
        binding.tileFormatJpg.radius = (if (isJpg) 10f else 14f) * density

        // DNG Card dynamic expressive morphing (PrimaryContainer when selected)
        binding.tileFormatRaw.setCardBackgroundColor(android.content.res.ColorStateList.valueOf(if (isRaw) colorPrimaryContainer else colorSurfaceContainerHigh))
        binding.tileFormatRaw.strokeColor = if (isRaw) colorPrimary else colorOutlineVariant
        binding.tileFormatRaw.strokeWidth = if (isRaw) stroke1_5dp else stroke1dp
        binding.tileFormatRaw.radius = (if (isRaw) 10f else 14f) * density
    }

    private fun updateHalfFrameCardStyling(enabled: Boolean, animated: Boolean = true) {
        val colorOnTertiaryContainer = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnTertiaryContainer, android.graphics.Color.WHITE)
        val colorTertiary = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorTertiary, android.graphics.Color.WHITE)
        val colorOnSurfaceVariant = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnSurfaceVariant, android.graphics.Color.GRAY)

        if (enabled) {
            binding.cardTrayHalfFrame.setContainerRole(top.maary.darkbag.ui.bento.BentoContainerRole.TERTIARY_CONTAINER, animated)
            binding.txtTitleHalfFrame.setTextColor(colorOnTertiaryContainer)
            binding.txtSubHalfFrame.setTextColor(colorOnTertiaryContainer)
        } else {
            binding.cardTrayHalfFrame.setContainerRole(top.maary.darkbag.ui.bento.BentoContainerRole.SURFACE_CONTAINER_LOW, animated)
            binding.txtTitleHalfFrame.setTextColor(colorTertiary)
            binding.txtSubHalfFrame.setTextColor(colorOnSurfaceVariant)
        }
        binding.cardTrayHalfFrame.strokeWidth = 0
    }

    private fun syncLocationSettingState() {
        val hasLocPerm = top.maary.darkbag.utils.LocationHelper.hasPermission(requireContext())
        if (!hasLocPerm && prefs.getBoolean(KEY_SAVE_LOCATION, false)) {
            prefs.edit().putBoolean(KEY_SAVE_LOCATION, false).apply()
        }
        val isEnabled = prefs.getBoolean(KEY_SAVE_LOCATION, false) && hasLocPerm
        if (binding.switchSaveLocation.isChecked != isEnabled) {
            binding.switchSaveLocation.isChecked = isEnabled
        }
    }

    private fun setupStartupSettings() {
        var defaultStartup = prefs.getString(KEY_DEFAULT_STARTUP, STARTUP_CAMERA) ?: STARTUP_CAMERA
        var enableCamera = prefs.getBoolean(KEY_ENABLE_CAMERA, true)
        var enablePlayground = prefs.getBoolean(KEY_ENABLE_PLAYGROUND, true)

        fun updateStartupCards() {
            val colorPrimary = MaterialColors.getColor(binding.root, android.R.attr.colorPrimary, android.graphics.Color.WHITE)
            val colorPrimaryContainer = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorPrimaryContainer, android.graphics.Color.DKGRAY)
            val colorTertiary = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorTertiary, colorPrimary)
            val colorTertiaryContainer = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorTertiaryContainer, colorPrimaryContainer)
            val colorSurface = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorSurface, android.graphics.Color.WHITE)
            val colorOutlineVariant = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOutlineVariant, android.graphics.Color.GRAY)
            val colorOnSurface = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnSurface, android.graphics.Color.WHITE)
            val colorOnPrimaryContainer = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnPrimaryContainer, colorPrimary)
            val colorOnTertiaryContainer = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnTertiaryContainer, colorTertiary)
            val density = resources.displayMetrics.density
            val stroke1dp = (1f * density).toInt()
            val stroke2dp = (2f * density).toInt()
            val radius10dp = (10f * density).toInt()
            val radius14dp = (14f * density).toInt()

            val isCameraActive = defaultStartup == STARTUP_CAMERA

            binding.cardStartupCamera.radius = (if (isCameraActive) radius10dp else radius14dp).toFloat()
            binding.cardStartupCamera.setCardBackgroundColor(android.content.res.ColorStateList.valueOf(if (isCameraActive) colorPrimaryContainer else colorSurface))
            binding.cardStartupCamera.strokeColor = if (isCameraActive) colorPrimary else colorOutlineVariant
            binding.cardStartupCamera.strokeWidth = if (isCameraActive) stroke2dp else stroke1dp
            binding.txtStartupCameraTitle.setTextColor(if (isCameraActive) colorOnPrimaryContainer else colorOnSurface)

            binding.cardStartupPlayground.radius = (if (!isCameraActive) radius10dp else radius14dp).toFloat()
            binding.cardStartupPlayground.setCardBackgroundColor(android.content.res.ColorStateList.valueOf(if (!isCameraActive) colorTertiaryContainer else colorSurface))
            binding.cardStartupPlayground.strokeColor = if (!isCameraActive) colorTertiary else colorOutlineVariant
            binding.cardStartupPlayground.strokeWidth = if (!isCameraActive) stroke2dp else stroke1dp
            binding.txtStartupPlaygroundTitle.setTextColor(if (!isCameraActive) colorOnTertiaryContainer else colorOnSurface)
        }

        fun updateSwitchStates() {
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

            updateStartupCards()
        }

        updateSwitchStates()

        binding.cardStartupCamera.setOnClickListener {
            if (enableCamera) {
                defaultStartup = STARTUP_CAMERA
                prefs.edit().putString(KEY_DEFAULT_STARTUP, defaultStartup).apply()
                updateStartupCards()
            }
        }

        binding.cardStartupPlayground.setOnClickListener {
            if (enablePlayground) {
                defaultStartup = STARTUP_PLAYGROUND
                prefs.edit().putString(KEY_DEFAULT_STARTUP, defaultStartup).apply()
                updateStartupCards()
            }
        }

        binding.switchEnableCamera.setOnCheckedChangeListener { _, isChecked ->
            enableCamera = isChecked
            prefs.edit().putBoolean(KEY_ENABLE_CAMERA, isChecked).apply()
            if (!isChecked && defaultStartup == STARTUP_CAMERA) {
                defaultStartup = STARTUP_PLAYGROUND
                prefs.edit().putString(KEY_DEFAULT_STARTUP, defaultStartup).apply()
            }
            updateSwitchStates()
        }

        binding.switchEnablePlayground.setOnCheckedChangeListener { _, isChecked ->
            enablePlayground = isChecked
            prefs.edit().putBoolean(KEY_ENABLE_PLAYGROUND, isChecked).apply()
            if (!isChecked && defaultStartup == STARTUP_PLAYGROUND) {
                defaultStartup = STARTUP_CAMERA
                prefs.edit().putString(KEY_DEFAULT_STARTUP, defaultStartup).apply()
            }
            updateSwitchStates()
        }
    }

    private fun setupCheckboxes() {
        setupSwitch(binding.switchLivePreview, KEY_ENABLE_LUT_PREVIEW)

        binding.cbSaveJpg.isChecked = prefs.getBoolean(KEY_SAVE_JPG, true)
        binding.cbSaveRaw.isChecked = prefs.getBoolean(KEY_SAVE_RAW, true)

        binding.tileFormatJpg.setOnClickListener {
            if (binding.cbSaveJpg.isEnabled) {
                binding.cbSaveJpg.toggle()
            }
        }
        binding.tileFormatRaw.setOnClickListener {
            if (binding.cbSaveRaw.isEnabled) {
                binding.cbSaveRaw.toggle()
            }
        }

        binding.cbSaveJpg.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_SAVE_JPG, isChecked).apply()
            updateCheckboxStates()
            updateStorageVisibility()
        }

        binding.cbSaveRaw.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_SAVE_RAW, isChecked).apply()
            updateCheckboxStates()
            updateStorageVisibility()
        }

        updateCheckboxStates()
        updateStorageVisibility()

        setupSwitch(binding.switchMotionPhoto, KEY_MOTION_PHOTO, false)

        syncLocationSettingState()
        binding.switchSaveLocation.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (top.maary.darkbag.utils.LocationHelper.hasPermission(requireContext())) {
                    prefs.edit().putBoolean(KEY_SAVE_LOCATION, true).apply()
                } else {
                    val perms = mutableListOf(
                        android.Manifest.permission.ACCESS_FINE_LOCATION,
                        android.Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        perms.add(android.Manifest.permission.ACCESS_MEDIA_LOCATION)
                    }
                    locationPermissionLauncher.launch(perms.toTypedArray())
                }
            } else {
                prefs.edit().putBoolean(KEY_SAVE_LOCATION, false).apply()
            }
        }

        val isHf = prefs.getBoolean(KEY_HALF_FRAME_MODE, false)
        setupSwitch(binding.switchHalfFrameMode, KEY_HALF_FRAME_MODE, false)
        binding.layoutHalfFrameOptions.visibility = if (isHf) View.VISIBLE else View.GONE
        updateHalfFrameCardStyling(isHf, animated = false)
        binding.switchHalfFrameMode.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_HALF_FRAME_MODE, isChecked).apply()
            binding.layoutHalfFrameOptions.visibility = if (isChecked) View.VISIBLE else View.GONE
            updateHalfFrameCardStyling(isChecked)
        }
        setupSwitch(binding.switchHalfFrameDownsample, KEY_HALF_FRAME_DOWNSAMPLE)
        setupSwitch(binding.switchHalfFrameDateStamp, KEY_HALF_FRAME_DATE_STAMP, false)
        setupSwitch(binding.switchHalfFrameLightLeak, KEY_HALF_FRAME_LIGHT_LEAK, false)

        binding.cbHfSaveRaw.isChecked = prefs.getBoolean(KEY_HALF_FRAME_SAVE_RAW, false)
        binding.cbHfSaveRaw.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_HALF_FRAME_SAVE_RAW, isChecked).apply()
        }
        binding.tileHfSaveRaw.setOnClickListener {
            if (binding.cbHfSaveRaw.isEnabled) {
                binding.cbHfSaveRaw.toggle()
            }
        }

        setupMultiCameraSettings()

        setupSwitch(binding.switchMirrorFront, KEY_MIRROR_FRONT_CAMERA)
        setupSwitch(binding.switchHdrPlusOis, KEY_HDR_PLUS_OIS)

        binding.switchUseInternalViewer.isChecked = prefs.getBoolean(KEY_USE_INTERNAL_VIEWER, true)
        binding.switchUseInternalViewer.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_USE_INTERNAL_VIEWER, isChecked).apply()
            binding.btnMenuExternalViewer.visibility = if (isChecked) View.GONE else View.VISIBLE
        }

        fun styleTierButton(btn: com.google.android.material.button.MaterialButton, title: String, subtitle: String, isActive: Boolean) {
            val ctx = requireContext()
            btn.setPadding(0, 0, 0, 0)
            btn.insetTop = 0
            btn.insetBottom = 0
            btn.iconPadding = 0
            btn.minWidth = 0
            btn.minimumWidth = 0
            btn.isSingleLine = false
            btn.maxLines = 2
            btn.text = top.maary.darkbag.ui.CameraBentoDeckView.formatCompactTierLabel(
                context = ctx,
                enLabel = title,
                zhSub = subtitle,
                isActive = isActive
            )
            val density = resources.displayMetrics.density
            val stroke1dp = (1f * density).toInt()
            val stroke1_5dp = (1.5f * density).toInt()

            val colorPrimary = MaterialColors.getColor(btn, android.R.attr.colorPrimary, android.graphics.Color.WHITE)
            val colorPrimaryContainer = MaterialColors.getColor(btn, com.google.android.material.R.attr.colorPrimaryContainer, android.graphics.Color.DKGRAY)
            val colorSurfaceContainerHigh = MaterialColors.getColor(btn, com.google.android.material.R.attr.colorSurfaceContainerHigh, android.graphics.Color.GRAY)
            val colorOutlineVariant = MaterialColors.getColor(btn, com.google.android.material.R.attr.colorOutlineVariant, android.graphics.Color.DKGRAY)
            val targetRadius = if (isActive) 10f * density else 14f * density
            btn.shapeAppearanceModel = ShapeAppearanceModel.builder()
                .setAllCornerSizes(targetRadius)
                .build()

            if (isActive) {
                btn.backgroundTintList = android.content.res.ColorStateList.valueOf(colorPrimaryContainer)
                btn.strokeColor = android.content.res.ColorStateList.valueOf(colorPrimary)
                btn.strokeWidth = stroke1_5dp
                btn.cornerRadius = targetRadius.toInt()
            } else {
                btn.backgroundTintList = android.content.res.ColorStateList.valueOf(colorSurfaceContainerHigh)
                btn.strokeColor = android.content.res.ColorStateList.valueOf(colorOutlineVariant)
                btn.strokeWidth = stroke1dp
                btn.cornerRadius = targetRadius.toInt()
            }
        }

        fun updateUiTierButtons(checkedId: Int) {
            styleTierButton(binding.btnTierPs, "P&S", "极简", checkedId == R.id.btn_tier_ps)
            styleTierButton(binding.btnTierFilm, "FILM", "胶片", checkedId == R.id.btn_tier_film)
            styleTierButton(binding.btnTierPro, "PRO", "专业", checkedId == R.id.btn_tier_pro)
            styleTierButton(binding.btnTierCustom, "CUSTOM", "自定义", checkedId == R.id.btn_tier_custom)
        }

        var isSyncingTierSelection = false

        fun syncTierSelectionFromPrefs() {
            isSyncingTierSelection = true
            try {
                val currentTierKey = prefs.getString(CameraBentoDeckView.PREF_KEY_UI_TIER, CameraUiTier.FILM.key)
                val tier = CameraUiTier.fromKey(currentTierKey)
                val checkId = when (tier) {
                    CameraUiTier.POINT_AND_SHOOT -> R.id.btn_tier_ps
                    CameraUiTier.FILM -> R.id.btn_tier_film
                    CameraUiTier.PRO -> R.id.btn_tier_pro
                    CameraUiTier.CUSTOM -> R.id.btn_tier_custom
                }
                binding.toggleUiTiers.check(checkId)
                updateUiTierButtons(checkId)
                binding.toggleUiTiers.enforceConcentricShapes()
            } finally {
                isSyncingTierSelection = false
            }
        }

        syncTierSelectionFromPrefs()

        binding.toggleUiTiers.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isSyncingTierSelection) return@addOnButtonCheckedListener
            if (isChecked) {
                val tier = when (checkedId) {
                    R.id.btn_tier_ps -> CameraUiTier.POINT_AND_SHOOT
                    R.id.btn_tier_film -> CameraUiTier.FILM
                    R.id.btn_tier_pro -> CameraUiTier.PRO
                    else -> CameraUiTier.CUSTOM
                }
                prefs.edit().putString(CameraBentoDeckView.PREF_KEY_UI_TIER, tier.key).apply()
                updateUiTierButtons(checkedId)
                binding.toggleUiTiers.enforceConcentricShapes()
            }
        }

        binding.btnOpenDeckCustomizer.text = CameraBentoDeckView.formatProTelemetry(
            label = "VIEWFINDER CUSTOMIZER",
            value = getString(R.string.viewfinder_customizer_btn_subtitle),
            context = requireContext()
        )
        binding.btnOpenDeckCustomizer.setOnClickListener {
            ViewfinderDeckCustomizerSheet.show(requireContext()) {
                syncTierSelectionFromPrefs()
            }
        }

        setupSwitch(binding.switchForce60fps, KEY_FORCE_60FPS, false)

        binding.btnCloseExperimental.setOnClickListener {
            prefs.edit().putBoolean(KEY_DEBUG_ENABLED, false).apply()
            updateDebugVisibility()
            aboutClickCount = 0
            Toast.makeText(requireContext(), R.string.experimental_locked_toast, Toast.LENGTH_SHORT).show()
        }

        binding.btnClearDebugLogs.setOnClickListener {
            DebugLogManager.clearLogs()
            updateDebugStats()
            Toast.makeText(requireContext(), "Debug logs cleared", Toast.LENGTH_SHORT).show()
        }

        binding.btnCopyDebugLogs.setOnClickListener {
            val logs = DebugLogManager.getLogs()
            if (logs.isNotBlank() && logs != "No logs yet.") {
                val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("Darkbag Timing Logs", logs)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(requireContext(), "Logs copied to clipboard", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), "No logs to copy", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateStorageVisibility() {
        binding.layoutJpgStorage.visibility = if (binding.cbSaveJpg.isChecked) View.VISIBLE else View.GONE
        binding.layoutRawStorage.visibility = if (binding.cbSaveRaw.isChecked) View.VISIBLE else View.GONE
        binding.btnMenuDngCompression.visibility = if (binding.cbSaveRaw.isChecked) View.VISIBLE else View.GONE
        binding.btnMenuDngFormat.visibility = if (binding.cbSaveRaw.isChecked) View.VISIBLE else View.GONE

        binding.tvJpgPath.text = prefs.getString(KEY_JPG_STORAGE_URI_NAME, "Default (Pictures/Darkbag)")
        binding.tvRawPath.text = prefs.getString(KEY_RAW_STORAGE_URI_NAME, "Default (Pictures/Darkbag)")
    }

    private val jpgPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            requireContext().contentResolver.takePersistableUriPermission(it, takeFlags)

            val folderName = MediaStoreUtils.getFolderNameFromUri(requireContext(), it)
            prefs.edit()
                .putString(KEY_JPG_STORAGE_URI, it.toString())
                .putString(KEY_JPG_STORAGE_URI_NAME, folderName)
                .apply()
            updateStorageVisibility()
        }
    }

    private val rawPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            requireContext().contentResolver.takePersistableUriPermission(it, takeFlags)

            val folderName = MediaStoreUtils.getFolderNameFromUri(requireContext(), it)
            prefs.edit()
                .putString(KEY_RAW_STORAGE_URI, it.toString())
                .putString(KEY_RAW_STORAGE_URI_NAME, folderName)
                .apply()
            updateStorageVisibility()
        }
    }

    private fun setupMultiCameraSettings() {
        val forceEnable = prefs.getBoolean(KEY_MULTI_CAMERA_FORCE_ENABLE, false)
        val isSupported = MultiCameraHelper.isMultiCameraSupported(requireContext(), forceEnable)
        val multiCamInfo = MultiCameraHelper.getLogicalMultiCameraInfo(requireContext())
        val hwDesc = MultiCameraHelper.getHardwareTypeDescription(requireContext())

        setupSwitch(binding.switchMultiCameraForceEnable, KEY_MULTI_CAMERA_FORCE_ENABLE, false)
        binding.switchMultiCameraForceEnable.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_MULTI_CAMERA_FORCE_ENABLE, isChecked).apply()
            setupMultiCameraSettings()
        }

        if (!isSupported && multiCamInfo == null) {
            binding.tvMultiCameraStatus.text = getString(R.string.multi_camera_status_unsupported)
            binding.switchMultiCameraMode.isEnabled = false
            binding.switchMultiCameraMode.isChecked = false
            binding.btnMenuMultiCameraCount.isEnabled = false
            binding.btnMenuMultiCameraDualPair.isEnabled = false
            binding.cbMultiCameraSaveRaw.isEnabled = false
            binding.tileMultiCameraSaveRaw.isEnabled = false
            return
        }

        val physLenses = multiCamInfo?.physicalLenses?.joinToString { "${it.name} (${it.type})" } ?: "None"
        binding.tvMultiCameraStatus.text = "$hwDesc\n" + getString(R.string.multi_camera_detected_lenses, physLenses)
        binding.switchMultiCameraMode.isEnabled = true
        binding.cbMultiCameraSaveRaw.isEnabled = true
        binding.tileMultiCameraSaveRaw.isEnabled = true

        setupSwitch(binding.switchMultiCameraMode, KEY_MULTI_CAMERA_MODE, false)
        binding.switchMultiCameraMode.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_MULTI_CAMERA_MODE, isChecked).apply()
            updateMultiCameraVisibility()
        }

        val countOptions = listOf(
            MultiCameraCountPreference.AUTO_MAX.title,
            MultiCameraCountPreference.DUAL.title,
            MultiCameraCountPreference.TRIPLE.title
        )
        val countKeys = listOf(
            MultiCameraCountPreference.AUTO_MAX.key,
            MultiCameraCountPreference.DUAL.key,
            MultiCameraCountPreference.TRIPLE.key
        )

        binding.btnMenuMultiCameraCount.setOnClickListener {
            val savedCountKey = prefs.getString(KEY_MULTI_CAMERA_COUNT_PREF, MultiCameraCountPreference.AUTO_MAX.key)
            val currentCountIndex = countKeys.indexOf(savedCountKey).coerceAtLeast(0)
            BentoSelectionBottomSheet.show(
                fragmentManager = childFragmentManager,
                title = getString(R.string.pref_multi_camera_count),
                options = countOptions,
                selectedIndex = currentCountIndex,
                badge = "MULTI-CAM"
            ) { which ->
                prefs.edit().putString(KEY_MULTI_CAMERA_COUNT_PREF, countKeys[which]).apply()
                updateMultiCameraCountTile()
                updateMultiCameraVisibility()
            }
        }

        val pairOptions = listOf(
            DualLensPairPreference.WIDE_ULTRAWIDE.title,
            DualLensPairPreference.WIDE_TELE.title,
            DualLensPairPreference.ULTRAWIDE_TELE.title
        )
        val pairKeys = listOf(
            DualLensPairPreference.WIDE_ULTRAWIDE.key,
            DualLensPairPreference.WIDE_TELE.key,
            DualLensPairPreference.ULTRAWIDE_TELE.key
        )

        binding.btnMenuMultiCameraDualPair.setOnClickListener {
            val savedPairKey = prefs.getString(KEY_MULTI_CAMERA_DUAL_PAIR, DualLensPairPreference.WIDE_ULTRAWIDE.key)
            val currentPairIndex = pairKeys.indexOf(savedPairKey).coerceAtLeast(0)
            BentoSelectionBottomSheet.show(
                fragmentManager = childFragmentManager,
                title = getString(R.string.pref_multi_camera_dual_pair),
                options = pairOptions,
                selectedIndex = currentPairIndex,
                badge = "DUAL"
            ) { which ->
                prefs.edit().putString(KEY_MULTI_CAMERA_DUAL_PAIR, pairKeys[which]).apply()
                updateMultiCameraDualPairTile()
            }
        }

        binding.cbMultiCameraSaveRaw.isChecked = prefs.getBoolean(KEY_MULTI_CAMERA_SAVE_RAW, false)
        binding.cbMultiCameraSaveRaw.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_MULTI_CAMERA_SAVE_RAW, isChecked).apply()
        }
        binding.tileMultiCameraSaveRaw.setOnClickListener {
            if (binding.cbMultiCameraSaveRaw.isEnabled) {
                binding.cbMultiCameraSaveRaw.toggle()
            }
        }

        updateMultiCameraVisibility()
    }

    private fun updateMultiCameraVisibility() {
        val isEnabled = prefs.getBoolean(KEY_MULTI_CAMERA_MODE, false)
        val countKey = prefs.getString(KEY_MULTI_CAMERA_COUNT_PREF, MultiCameraCountPreference.AUTO_MAX.key)
        binding.btnMenuMultiCameraCount.visibility = if (isEnabled) View.VISIBLE else View.GONE
        binding.btnMenuMultiCameraDualPair.visibility = if (isEnabled && countKey == MultiCameraCountPreference.DUAL.key) View.VISIBLE else View.GONE
        binding.tileMultiCameraSaveRaw.visibility = if (isEnabled) View.VISIBLE else View.GONE
    }

    private fun setupSwitch(switch: com.google.android.material.materialswitch.MaterialSwitch, key: String, defaultValue: Boolean = true) {
        switch.isChecked = prefs.getBoolean(key, defaultValue)
        switch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(key, isChecked).apply()
        }
    }

    private fun setupSwitch(switch: top.maary.darkbag.ui.bento.BentoSwitchTile, key: String, defaultValue: Boolean = true) {
        switch.isChecked = prefs.getBoolean(key, defaultValue)
        switch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(key, isChecked).apply()
        }
    }

    private fun setupStoragePickers() {
        binding.layoutJpgStorage.setOnClickListener {
            jpgPicker.launch(null)
        }
        binding.layoutJpgStorage.setOnLongClickListener {
            val currentUri = prefs.getString(KEY_JPG_STORAGE_URI, null)
            if (currentUri != null) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.dialog_reset_storage_title)
                    .setMessage(R.string.dialog_reset_storage_jpg_message)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.btn_reset) { _, _ ->
                        prefs.edit()
                            .remove(KEY_JPG_STORAGE_URI)
                            .remove(KEY_JPG_STORAGE_URI_NAME)
                            .apply()
                        updateStorageVisibility()
                        Toast.makeText(requireContext(), R.string.toast_storage_reset_default, Toast.LENGTH_SHORT).show()
                    }
                    .show()
            } else {
                Toast.makeText(requireContext(), R.string.toast_storage_already_default, Toast.LENGTH_SHORT).show()
            }
            true
        }

        binding.layoutRawStorage.setOnClickListener {
            rawPicker.launch(null)
        }
        binding.layoutRawStorage.setOnLongClickListener {
            val currentUri = prefs.getString(KEY_RAW_STORAGE_URI, null)
            if (currentUri != null) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.dialog_reset_storage_title)
                    .setMessage(R.string.dialog_reset_storage_raw_message)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.btn_reset) { _, _ ->
                        prefs.edit()
                            .remove(KEY_RAW_STORAGE_URI)
                            .remove(KEY_RAW_STORAGE_URI_NAME)
                            .apply()
                        updateStorageVisibility()
                        Toast.makeText(requireContext(), R.string.toast_storage_reset_default, Toast.LENGTH_SHORT).show()
                    }
                    .show()
            } else {
                Toast.makeText(requireContext(), R.string.toast_storage_already_default, Toast.LENGTH_SHORT).show()
            }
            true
        }
    }

    private fun setupStorageSettings() {
        binding.btnMenuDngCompression.setOnClickListener {
            val savedIndex = prefs.getInt(KEY_DNG_COMPRESSION_MODE, 0).coerceIn(0, DNG_COMPRESSION_MODES.size - 1)
            BentoSelectionBottomSheet.show(
                fragmentManager = childFragmentManager,
                title = getString(R.string.pref_dng_compression),
                options = DNG_COMPRESSION_MODES,
                selectedIndex = savedIndex,
                badge = "CODEC"
            ) { which ->
                prefs.edit().putInt(KEY_DNG_COMPRESSION_MODE, which).apply()
                updateDngCompressionTile()
            }
        }

        binding.btnMenuDngFormat.setOnClickListener {
            val rawOutputOptions = listOf(
                getString(R.string.raw_output_type_bayer),
                getString(R.string.raw_output_type_linear)
            )
            val savedFormatIndex = prefs.getInt(KEY_RAW_OUTPUT_TYPE, 0).coerceIn(0, rawOutputOptions.size - 1)
            BentoSelectionBottomSheet.show(
                fragmentManager = childFragmentManager,
                title = getString(R.string.setting_raw_format_hint),
                options = rawOutputOptions,
                selectedIndex = savedFormatIndex,
                badge = "CFA"
            ) { which ->
                prefs.edit().putInt(KEY_RAW_OUTPUT_TYPE, which).apply()
                updateDngFormatTile()
            }
        }
    }

    private fun setupCacheManagement() {
        binding.btnClearCache.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.clear_cache_dialog_title)
                .setMessage(R.string.clear_cache_dialog_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.btn_clear_cache) { _, _ ->
                    val appContext = requireContext().applicationContext
                    val ctx = requireContext()
                    binding.btnClearCache.isEnabled = false
                    binding.btnClearCache.text = CameraBentoDeckView.formatProTelemetry(
                        label = "CLEAR CACHE",
                        value = getString(R.string.pref_cache_calculating),
                        isTabActive = false,
                        isValueManual = false,
                        context = ctx
                    )
                    lifecycleScope.launch(Dispatchers.IO) {
                        val freedBytes = CacheManager.clearCache(appContext)
                        val freedFormatted = CacheManager.formatSize(freedBytes)
                        withContext(Dispatchers.Main) {
                            _binding?.btnClearCache?.isEnabled = true
                            _binding?.btnClearCache?.text = CameraBentoDeckView.formatProTelemetry(
                                label = "CLEAR CACHE",
                                value = CacheManager.formatSize(0L),
                                isTabActive = false,
                                isValueManual = false,
                                context = _binding?.btnClearCache?.context ?: ctx
                            )
                            if (freedBytes > 0) {
                                Toast.makeText(
                                    appContext,
                                    getString(R.string.clear_cache_success_toast, freedFormatted),
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                Toast.makeText(
                                    appContext,
                                    R.string.clear_cache_empty_toast,
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                }
                .show()
        }
    }

    private fun updateCacheSize() {
        val ctx = context ?: return
        val appContext = ctx.applicationContext
        binding.btnClearCache.isEnabled = false
        binding.btnClearCache.text = CameraBentoDeckView.formatProTelemetry(
            label = "CLEAR CACHE",
            value = getString(R.string.pref_cache_calculating),
            isTabActive = false,
            isValueManual = false,
            context = ctx
        )
        lifecycleScope.launch(Dispatchers.IO) {
            val size = CacheManager.calculateCacheSize(appContext)
            val formatted = CacheManager.formatSize(size)
            withContext(Dispatchers.Main) {
                _binding?.btnClearCache?.isEnabled = true
                _binding?.btnClearCache?.text = CameraBentoDeckView.formatProTelemetry(
                    label = "CLEAR CACHE",
                    value = formatted,
                    isTabActive = false,
                    isValueManual = false,
                    context = _binding?.btnClearCache?.context ?: ctx
                )
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val PREFS_NAME = "camera_settings"
        const val KEY_TARGET_LOG = "target_log"
        const val KEY_LUT_URI = "lut_uri"
        const val KEY_ACTIVE_LUT = "active_lut_filename"
        const val KEY_SAVE_JPG = "save_jpg"
        const val KEY_USE_GPU = "use_gpu"
        const val KEY_MANUAL_CONTROLS = "enable_manual_controls"
        const val KEY_ENABLE_LUT_PREVIEW = "enable_lut_preview"
        const val KEY_DEFAULT_LENS_ID = "default_lens_id"
        const val KEY_DEFAULT_FOCAL_1X = "default_focal_1x"
        const val KEY_ANTIBANDING = "antibanding_mode"
        const val KEY_FLASH_MODE = "flash_mode"
        const val KEY_HDR_BURST_COUNT = "hdr_burst_count"
        const val KEY_HDR_UNDEREXPOSURE_MODE = "hdr_underexposure_mode"
        const val KEY_BURST_PROCESSING_STRATEGY = "burst_processing_strategy"
        const val BURST_STRATEGY_BALANCED = "Balanced (Smooth UI)"
        const val BURST_STRATEGY_AGGRESSIVE = "Rapid Burst (Maximum Shots)"
        val BURST_PROCESSING_STRATEGIES = listOf(BURST_STRATEGY_BALANCED, BURST_STRATEGY_AGGRESSIVE)
        const val KEY_HDR_FUSION_MODE = "hdr_fusion_mode"
        const val HDR_FUSION_AUTO = "Auto (Spatial / Sabre Zoom)"
        const val HDR_FUSION_SPATIAL_RCD = "Spatial Merge + RCD"
        const val HDR_FUSION_SABRE = "Sabre (Super-Resolution)"
        const val HDR_FUSION_CLASSIC = "Classic HDR+ (Wiener)"
        val HDR_FUSION_MODES = listOf(
            HDR_FUSION_AUTO,
            HDR_FUSION_SPATIAL_RCD,
            HDR_FUSION_SABRE,
            HDR_FUSION_CLASSIC
        )
        const val KEY_SHOW_HDR_UNDEREXPOSURE_BUTTON = "show_hdr_underexposure_button"
        const val KEY_SHOW_HDR_PLUS_SWITCH = "show_hdr_plus_switch"
        const val KEY_USE_INTERNAL_VIEWER = "use_internal_viewer"
        const val KEY_EXTERNAL_VIEWER_PACKAGE = "external_viewer_package"
        const val KEY_EXTERNAL_VIEWER_NAME = "external_viewer_name"
        const val KEY_SHOW_SETTINGS_BUTTON = "show_settings_button"
        const val KEY_SHOW_CAMERA_SWITCH_BUTTON = "show_camera_switch_button"
        const val KEY_SHOW_MODE_SWITCH_BUTTON = "show_mode_switch_button"
        const val KEY_SHOW_LENS_CONTROLS = "show_lens_controls"
        const val KEY_SHOW_LUT_SWITCHER = "show_lut_switcher"
        const val KEY_MIRROR_FRONT_CAMERA = "mirror_front_camera"
        const val KEY_HDR_PLUS_OIS = "hdr_plus_ois_enabled"
        const val KEY_FORCE_60FPS = "force_60fps"
        const val KEY_DEBUG_ENABLED = "debug_enabled"
        const val KEY_MOTION_PHOTO = "motion_photo_enabled"
        const val KEY_SAVE_LOCATION = "save_location_enabled"
        const val KEY_SAVE_RAW = "save_raw"
        const val KEY_RAW_OUTPUT_TYPE = "raw_output_type"
        val RAW_OUTPUT_TYPES = listOf("Bayer CFA RAW (~12MB, Default)", "Linear RGB RAW (~50MB)")
        const val KEY_DNG_COMPRESSION_MODE = "dng_compression_mode"
        val DNG_COMPRESSION_MODES = listOf("Lossless JPEG (Default)", "Uncompressed")
        const val KEY_JPG_STORAGE_URI = "jpg_storage_uri"
        const val KEY_JPG_STORAGE_URI_NAME = "jpg_storage_uri_name"
        const val KEY_RAW_STORAGE_URI = "raw_storage_uri"
        const val KEY_RAW_STORAGE_URI_NAME = "raw_storage_uri_name"
        const val KEY_LAST_CAPTURE_URI = "last_capture_uri"
        const val KEY_HALF_FRAME_MODE = "half_frame_mode"
        const val KEY_HALF_FRAME_LAYOUT = "half_frame_layout"
        const val KEY_HALF_FRAME_DOWNSAMPLE = "half_frame_downsample"
        const val KEY_HALF_FRAME_STEP = "half_frame_step"
        const val KEY_HALF_FRAME_TEMP_PATH = "half_frame_temp_path"
        const val KEY_HALF_FRAME_DATE_STAMP = "half_frame_date_stamp"
        const val KEY_HALF_FRAME_LIGHT_LEAK = "half_frame_light_leak"
        const val KEY_HALF_FRAME_AUTO_BURST = "half_frame_auto_burst"
        const val KEY_HALF_FRAME_SAVE_JPG = "half_frame_save_jpg"
        const val KEY_HALF_FRAME_SAVE_RAW = "half_frame_save_raw"
        const val KEY_HALF_FRAME_BASE_NAME = "half_frame_base_name"
        const val KEY_ACTIVE_CAPTURE_MODE = "active_capture_mode"
        const val MODE_NORMAL = "normal"
        const val MODE_HALF_FRAME_SBS = "half_frame_sbs"
        const val MODE_HALF_FRAME_TB = "half_frame_tb"
        const val MODE_MULTI_CAMERA = "multi_camera"

        const val KEY_MULTI_CAMERA_MODE = "multi_camera_mode_enabled"
        const val KEY_MULTI_CAMERA_FORCE_ENABLE = "multi_camera_force_enable"
        const val KEY_MULTI_CAMERA_COUNT_PREF = "multi_camera_count_pref"
        const val KEY_MULTI_CAMERA_DUAL_PAIR = "multi_camera_dual_pair"
        const val KEY_MULTI_CAMERA_SAVE_RAW = "multi_camera_save_raw"

        const val KEY_DEFAULT_STARTUP = "default_startup_page"
        const val STARTUP_CAMERA = "camera"
        const val STARTUP_PLAYGROUND = "playground"

        const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"

        const val KEY_ENABLE_CAMERA = "enable_camera"
        const val KEY_ENABLE_PLAYGROUND = "enable_playground"
        const val KEY_SHOW_FLOATING_TOOLBAR = "show_floating_toolbar"
        const val KEY_EXP_FOCUS_PEAKING = "exp_focus_peaking"
        const val KEY_FOCUS_PEAKING_ACTIVE = "pref_focus_peaking_active"
        const val KEY_COLOR_ENGINE_MODE = "color_engine_mode"

        const val KEY_SHUTTER_LONG_PRESS_ACTION = "shutter_long_press_action"
        const val SHUTTER_LONG_PRESS_MP4 = "Standard MP4 (Default)"
        const val SHUTTER_LONG_PRESS_RAW_VIDEO = "RAW Video (Bayer 10-bit)"
        const val SHUTTER_LONG_PRESS_DISABLED = "Disabled"
        val SHUTTER_LONG_PRESS_OPTIONS = listOf(
            SHUTTER_LONG_PRESS_MP4,
            SHUTTER_LONG_PRESS_RAW_VIDEO,
            SHUTTER_LONG_PRESS_DISABLED
        )

        const val KEY_RAW_VIDEO_FPS = "raw_video_fps"
        val RAW_VIDEO_FPS_OPTIONS = listOf("24", "30", "60")

        const val KEY_RAW_VIDEO_RESOLUTION = "raw_video_resolution"
        const val DEFAULT_RAW_VIDEO_RESOLUTION = "Max Native (4:3)"
        val RAW_VIDEO_RESOLUTION_OPTIONS = listOf("Max Native (4:3)", "2K Open Gate (4:3)", "4K UHD (16:9)", "1080p (16:9)")

        fun selectRawVideoSize(preference: String, availableSizes: Array<android.util.Size>): android.util.Size {
            if (availableSizes.isEmpty()) {
                return android.util.Size(4000, 3000)
            }
            return when {
                preference.contains("1080p") -> {
                    val exact = availableSizes.find { it.width == 1920 && it.height == 1080 }
                    if (exact != null) {
                        exact
                    } else {
                        val candidate16x9 = availableSizes
                            .filter { it.width <= 1920 && kotlin.math.abs((it.width.toFloat() / it.height.toFloat()) - (16.0f / 9.0f)) < 0.05f }
                            .maxByOrNull { it.width * it.height }
                        candidate16x9
                            ?: availableSizes.minByOrNull { kotlin.math.abs(it.width * it.height - 1920 * 1080) }
                            ?: (availableSizes.maxByOrNull { it.width * it.height } ?: android.util.Size(4000, 3000))
                    }
                }
                preference.contains("4K") -> {
                    val exact = availableSizes.find { it.width == 3840 && it.height == 2160 }
                    if (exact != null) {
                        exact
                    } else {
                        val candidate16x9 = availableSizes
                            .filter { kotlin.math.abs((it.width.toFloat() / it.height.toFloat()) - (16.0f / 9.0f)) < 0.05f }
                            .minByOrNull { kotlin.math.abs(it.width * it.height - 3840 * 2160) }
                        candidate16x9
                            ?: availableSizes.minByOrNull { kotlin.math.abs(it.width * it.height - 3840 * 2160) }
                            ?: (availableSizes.maxByOrNull { it.width * it.height } ?: android.util.Size(4000, 3000))
                    }
                }
                preference.contains("2K Open Gate") || preference.contains("Max Native") -> {
                    availableSizes.maxByOrNull { it.width * it.height } ?: android.util.Size(4000, 3000)
                }
                else -> {
                    availableSizes.maxByOrNull { it.width * it.height } ?: android.util.Size(4000, 3000)
                }
            }
        }

        val COLOR_ENGINE_MODES = listOf(
            "Khronos PBR Neutral",
            "Natural Filmic (Leica/Hasselblad)",
            "Sony Uchimura (GT7, Default)",
            "Legacy ACES Filmic (Original)"
        )

        val FOCAL_LENGTHS = listOf("24", "28", "35")
        val ANTIBANDING_MODES = listOf("Auto", "50Hz", "60Hz", "Off")
        val BURST_SIZES = listOf("3", "4", "5", "6", "7", "8")
        val HDR_UNDEREXPOSURE_MODES = listOf("Off", "-1 EV", "-2 EV", "Dynamic (Experimental)")

        const val HALF_FRAME_LAYOUT_SBS = "Side-by-side"
        const val HALF_FRAME_LAYOUT_TB = "Top-bottom"
        val HALF_FRAME_LAYOUTS = listOf(HALF_FRAME_LAYOUT_SBS, HALF_FRAME_LAYOUT_TB)

        val LOG_CURVES = listOf(
            "None",
            "Arri LogC3",
            "F-Log",
            "F-Log2",
            "F-Log2 C",
            "S-Log3",
            "S-Log3.Cine",
            "V-Log",
            "Canon Log 2",
            "Canon Log 3",
            "N-Log",
            "D-Log",
            "Log3G10"
        )
    }

    object HardwareCapabilities {
        fun getMaxSupportedFps(chars: CameraCharacteristics?): Int {
            if (chars == null) return 30
            val ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: return 30
            return ranges.maxOfOrNull { it.upper } ?: 30
        }

        fun is4kCropSupported(chars: CameraCharacteristics?): Boolean {
            if (chars == null) return false
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return false
            val rawSizes = map.getOutputSizes(ImageFormat.RAW_SENSOR) ?: return false
            val maxRaw = rawSizes.maxByOrNull { it.width * it.height } ?: return false
            return maxRaw.width >= 3840 && maxRaw.height >= 2160
        }

        fun getMaxRawSize(chars: CameraCharacteristics?): Size? {
            if (chars == null) return null
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
            val rawSizes = map.getOutputSizes(ImageFormat.RAW_SENSOR) ?: return null
            return rawSizes.maxByOrNull { it.width * it.height }
        }
    }
}
