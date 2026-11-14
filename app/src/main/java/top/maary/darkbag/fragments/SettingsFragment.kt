package top.maary.darkbag.fragments

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Size
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.Navigation
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.maary.darkbag.R
import top.maary.darkbag.databinding.FragmentSettingsBinding
import top.maary.darkbag.fragments.settings.SettingsBentoDelegate
import top.maary.darkbag.fragments.settings.SettingsSheetDispatcher
import top.maary.darkbag.ui.CameraBentoDeckView
import top.maary.darkbag.ui.ViewfinderDeckCustomizerSheet
import top.maary.darkbag.utils.CacheManager
import top.maary.darkbag.utils.CameraRepository
import top.maary.darkbag.utils.DebugLogManager
import top.maary.darkbag.utils.LocationHelper
import top.maary.darkbag.utils.MediaStoreUtils
import top.maary.darkbag.utils.MultiCameraHelper
import top.maary.darkbag.viewmodel.CameraUiTier
import top.maary.darkbag.viewmodel.SettingsViewModel

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val settingsViewModel: SettingsViewModel by viewModels()
    private lateinit var cameraRepository: CameraRepository
    private lateinit var prefs: SharedPreferences

    private lateinit var bentoDelegate: SettingsBentoDelegate
    private lateinit var sheetDispatcher: SettingsSheetDispatcher

    private var isAdvancedExpanded = false
    private var aboutClickCount = 0

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[android.Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseGranted = permissions[android.Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fineGranted || coarseGranted) {
            settingsViewModel.updateSaveLocationEnabled(true)
        } else {
            settingsViewModel.updateSaveLocationEnabled(false)
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
            settingsViewModel.updateMotionPhotoAudio(false)
        }
    }

    private val jpgPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            requireContext().contentResolver.takePersistableUriPermission(it, takeFlags)
            val folderName = MediaStoreUtils.getFolderNameFromUri(requireContext(), it)
            settingsViewModel.updateStorageUri(isJpg = true, uri = it.toString(), name = folderName)
        }
    }

    private val rawPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            requireContext().contentResolver.takePersistableUriPermission(it, takeFlags)
            val folderName = MediaStoreUtils.getFolderNameFromUri(requireContext(), it)
            settingsViewModel.updateStorageUri(isJpg = false, uri = it.toString(), name = folderName)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()
        prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        cameraRepository = CameraRepository(ctx)
        bentoDelegate = SettingsBentoDelegate(ctx, cameraRepository)
        sheetDispatcher = SettingsSheetDispatcher(childFragmentManager, ctx, cameraRepository, settingsViewModel)

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
        setupCacheManagement()
        setupNavigation()
        setupMultiCameraSettings()
        updateDebugStats()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                settingsViewModel.preferences.collect { prefsState ->
                    bentoDelegate.renderPreferences(binding, prefsState)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateDebugStats()
        syncLocationSettingState()
        updateCacheSize()
    }

    private fun syncLocationSettingState() {
        val hasLocPerm = LocationHelper.hasPermission(requireContext())
        if (!hasLocPerm && settingsViewModel.preferences.value.saveLocationEnabled) {
            settingsViewModel.updateSaveLocationEnabled(false)
        }
        val isEnabled = settingsViewModel.preferences.value.saveLocationEnabled && hasLocPerm
        if (binding.switchSaveLocation.isChecked != isEnabled) {
            binding.switchSaveLocation.isChecked = isEnabled
        }
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
                if (!settingsViewModel.preferences.value.debugEnabled) {
                    settingsViewModel.updateDebugEnabled(true)
                    Toast.makeText(requireContext(), R.string.experimental_unlocked_toast, Toast.LENGTH_SHORT).show()
                }
                aboutClickCount = 0
            }
        }

        binding.btnReplayOnboarding.text = CameraBentoDeckView.formatProTelemetry(
            label = getString(R.string.settings_section_setup_wizard),
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

    private fun updateDebugStats() {
        val logs = DebugLogManager.getLogs()
        binding.tvDebugStats.text = if (logs.isNotBlank()) logs else "No timing stats logged yet."
    }

    private fun setupMenus() {
        binding.btnMenuDefaultLens.setOnClickListener { sheetDispatcher.showDefaultLensSheet() }
        binding.btnMenuDefaultFocal1x.setOnClickListener { sheetDispatcher.showDefaultFocal1xSheet() }
        binding.btnMenuTargetLog.setOnClickListener {
            sheetDispatcher.showTargetLogSheet { incompatibleLog ->
                Toast.makeText(context, getString(R.string.lut_reset_incompatible_toast, incompatibleLog), Toast.LENGTH_SHORT).show()
            }
        }
        binding.btnMenuHdrFusionMode.setOnClickListener { sheetDispatcher.showHdrFusionSheet() }
        binding.btnMenuHdrBurstMode.setOnClickListener { sheetDispatcher.showHdrBurstSheet() }
        binding.btnMenuUnderexposureMode.setOnClickListener { sheetDispatcher.showUnderexposureSheet() }
        binding.btnMenuColorEngine.setOnClickListener { sheetDispatcher.showColorEngineSheet() }
        binding.btnMenuAntibanding.setOnClickListener { sheetDispatcher.showAntibandingSheet() }
        binding.btnMenuBurstProcessingStrategy.setOnClickListener { sheetDispatcher.showBurstStrategySheet() }
        binding.btnMenuShutterLongPress.setOnClickListener {
            sheetDispatcher.showShutterLongPressSheet {
                if (ContextCompat.checkSelfPermission(
                        requireContext(),
                        android.Manifest.permission.RECORD_AUDIO
                    ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    audioPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                }
            }
        }
        binding.btnMenuRawVideo.setOnClickListener { sheetDispatcher.showRawVideoSettingsSheet() }
        binding.btnMenuExternalViewer.setOnClickListener { sheetDispatcher.showExternalViewerSheet() }
        binding.btnMenuDngCompression.setOnClickListener { sheetDispatcher.showDngCompressionSheet() }
        binding.btnMenuDngFormat.setOnClickListener { sheetDispatcher.showDngFormatSheet() }
        binding.btnMenuMultiCameraCount.setOnClickListener { sheetDispatcher.showMultiCameraCountSheet() }
        binding.btnMenuMultiCameraDualPair.setOnClickListener { sheetDispatcher.showMultiCameraDualPairSheet() }
    }

    private fun setupStartupSettings() {
        binding.cardStartupCamera.setOnClickListener {
            if (settingsViewModel.preferences.value.enableCamera) {
                settingsViewModel.updateDefaultStartupPage(STARTUP_CAMERA)
            }
        }

        binding.cardStartupPlayground.setOnClickListener {
            if (settingsViewModel.preferences.value.enablePlayground) {
                settingsViewModel.updateDefaultStartupPage(STARTUP_PLAYGROUND)
            }
        }

        binding.switchEnableCamera.setOnCheckedChangeListener { _, isChecked ->
            if (bentoDelegate.isRendering) return@setOnCheckedChangeListener
            settingsViewModel.updateEnableCamera(isChecked)
            if (!isChecked && settingsViewModel.preferences.value.defaultStartupPage == STARTUP_CAMERA) {
                settingsViewModel.updateDefaultStartupPage(STARTUP_PLAYGROUND)
            }
        }

        binding.switchEnablePlayground.setOnCheckedChangeListener { _, isChecked ->
            if (bentoDelegate.isRendering) return@setOnCheckedChangeListener
            settingsViewModel.updateEnablePlayground(isChecked)
            if (!isChecked && settingsViewModel.preferences.value.defaultStartupPage == STARTUP_PLAYGROUND) {
                settingsViewModel.updateDefaultStartupPage(STARTUP_CAMERA)
            }
        }
    }

    private fun setupCheckboxes() {
        binding.switchLivePreview.setOnCheckedChangeListener { _, isChecked ->
            if (bentoDelegate.isRendering) return@setOnCheckedChangeListener
            settingsViewModel.updateLivePreview(isChecked)
        }

        binding.tileFormatJpg.setOnClickListener {
            if (binding.cbSaveJpg.isEnabled) {
                settingsViewModel.updateSaveJpg(!binding.cbSaveJpg.isChecked)
            }
        }
        binding.tileFormatRaw.setOnClickListener {
            if (binding.cbSaveRaw.isEnabled) {
                settingsViewModel.updateSaveRaw(!binding.cbSaveRaw.isChecked)
            }
        }
        binding.cbSaveJpg.setOnCheckedChangeListener { _, isChecked ->
            if (bentoDelegate.isRendering) return@setOnCheckedChangeListener
            settingsViewModel.updateSaveJpg(isChecked)
        }
        binding.cbSaveRaw.setOnCheckedChangeListener { _, isChecked ->
            if (bentoDelegate.isRendering) return@setOnCheckedChangeListener
            settingsViewModel.updateSaveRaw(isChecked)
        }

        binding.switchMotionPhoto.setOnCheckedChangeListener { _, isChecked ->
            if (bentoDelegate.isRendering) return@setOnCheckedChangeListener
            settingsViewModel.updateMotionPhoto(isChecked)
        }
        binding.switchMotionPhotoAudio.setOnCheckedChangeListener { _, isChecked ->
            if (bentoDelegate.isRendering) return@setOnCheckedChangeListener
            if (isChecked && ContextCompat.checkSelfPermission(
                    requireContext(),
                    android.Manifest.permission.RECORD_AUDIO
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                audioPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
            }
            settingsViewModel.updateMotionPhotoAudio(isChecked)
        }

        binding.switchSaveLocation.setOnCheckedChangeListener { _, isChecked ->
            if (bentoDelegate.isRendering) return@setOnCheckedChangeListener
            if (isChecked) {
                if (LocationHelper.hasPermission(requireContext())) {
                    settingsViewModel.updateSaveLocationEnabled(true)
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
                settingsViewModel.updateSaveLocationEnabled(false)
            }
        }

        binding.switchHalfFrameMode.setOnCheckedChangeListener { _, isChecked ->
            if (bentoDelegate.isRendering) return@setOnCheckedChangeListener
            settingsViewModel.updateHalfFrameMode(isChecked)
        }
        binding.switchHalfFrameDownsample.setOnCheckedChangeListener { _, isChecked ->
            if (bentoDelegate.isRendering) return@setOnCheckedChangeListener
            settingsViewModel.updateHalfFrameDownsample(isChecked)
        }
        binding.switchHalfFrameDateStamp.setOnCheckedChangeListener { _, isChecked ->
            if (bentoDelegate.isRendering) return@setOnCheckedChangeListener
            settingsViewModel.updateHalfFrameDateStamp(isChecked)
        }
        binding.switchHalfFrameLightLeak.setOnCheckedChangeListener { _, isChecked ->
            if (bentoDelegate.isRendering) return@setOnCheckedChangeListener
            settingsViewModel.updateHalfFrameLightLeak(isChecked)
        }
        binding.cbHfSaveRaw.setOnCheckedChangeListener { _, isChecked ->
            if (bentoDelegate.isRendering) return@setOnCheckedChangeListener
            settingsViewModel.updateHalfFrameSaveRaw(isChecked)
        }
        binding.tileHfSaveRaw.setOnClickListener {
            if (binding.cbHfSaveRaw.isEnabled) {
                settingsViewModel.updateHalfFrameSaveRaw(!binding.cbHfSaveRaw.isChecked)
            }
        }

        binding.switchMirrorFront.setOnCheckedChangeListener { _, isChecked ->
            if (bentoDelegate.isRendering) return@setOnCheckedChangeListener
            settingsViewModel.updateMirrorFrontCamera(isChecked)
        }
        binding.switchHdrPlusOis.setOnCheckedChangeListener { _, isChecked ->
            if (bentoDelegate.isRendering) return@setOnCheckedChangeListener
            settingsViewModel.updateHdrPlusOis(isChecked)
        }
        binding.switchUseInternalViewer.setOnCheckedChangeListener { _, isChecked ->
            if (bentoDelegate.isRendering) return@setOnCheckedChangeListener
            settingsViewModel.updateUseInternalViewer(isChecked)
        }
        binding.switchForce60fps.setOnCheckedChangeListener { _, isChecked ->
            if (bentoDelegate.isRendering) return@setOnCheckedChangeListener
            settingsViewModel.updateForce60Fps(isChecked)
        }

        binding.toggleUiTiers.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (bentoDelegate.isRendering) return@addOnButtonCheckedListener
            if (isChecked) {
                val tier = when (checkedId) {
                    R.id.btn_tier_ps -> CameraUiTier.POINT_AND_SHOOT
                    R.id.btn_tier_film -> CameraUiTier.FILM
                    R.id.btn_tier_pro -> CameraUiTier.PRO
                    else -> CameraUiTier.CUSTOM
                }
                settingsViewModel.updateUiTier(tier.key)
            }
        }

        binding.btnOpenDeckCustomizer.text = CameraBentoDeckView.formatProTelemetry(
            label = getString(R.string.pref_deck_customizer_label),
            value = getString(R.string.viewfinder_customizer_btn_subtitle),
            context = requireContext()
        )
        binding.btnOpenDeckCustomizer.setOnClickListener {
            ViewfinderDeckCustomizerSheet.show(requireContext()) {}
        }

        binding.btnOpenLensCalibration.setOnClickListener {
            top.maary.darkbag.ui.LensCalibrationBottomSheet.show(childFragmentManager)
        }

        binding.btnCloseExperimental.setOnClickListener {
            settingsViewModel.updateDebugEnabled(false)
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
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Darkbag Timing Logs", logs))
                Toast.makeText(requireContext(), "Logs copied to clipboard", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), "No logs to copy", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupMultiCameraSettings() {
        val forceEnable = settingsViewModel.preferences.value.multiCameraForceEnable
        val isSupported = MultiCameraHelper.isMultiCameraSupported(requireContext(), forceEnable)
        val multiCamInfo = MultiCameraHelper.getLogicalMultiCameraInfo(requireContext())
        val hwDesc = MultiCameraHelper.getHardwareTypeDescription(requireContext())

        binding.switchMultiCameraForceEnable.setOnCheckedChangeListener { _, isChecked ->
            if (bentoDelegate.isRendering) return@setOnCheckedChangeListener
            settingsViewModel.updateMultiCameraForceEnable(isChecked)
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

        binding.switchMultiCameraMode.setOnCheckedChangeListener { _, isChecked ->
            if (bentoDelegate.isRendering) return@setOnCheckedChangeListener
            settingsViewModel.updateMultiCameraModeEnabled(isChecked)
        }

        binding.cbMultiCameraSaveRaw.setOnCheckedChangeListener { _, isChecked ->
            if (bentoDelegate.isRendering) return@setOnCheckedChangeListener
            settingsViewModel.updateMultiCameraSaveRaw(isChecked)
        }
        binding.tileMultiCameraSaveRaw.setOnClickListener {
            if (binding.cbMultiCameraSaveRaw.isEnabled) {
                settingsViewModel.updateMultiCameraSaveRaw(!binding.cbMultiCameraSaveRaw.isChecked)
            }
        }
    }

    private fun setupStoragePickers() {
        binding.layoutJpgStorage.setOnClickListener {
            jpgPicker.launch(null)
        }
        binding.layoutJpgStorage.setOnLongClickListener {
            val currentUri = settingsViewModel.preferences.value.jpgStorageUri
            if (currentUri != null) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.dialog_reset_storage_title)
                    .setMessage(R.string.dialog_reset_storage_jpg_message)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.btn_reset) { _, _ ->
                        settingsViewModel.updateStorageUri(isJpg = true, uri = null, name = null)
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
            val currentUri = settingsViewModel.preferences.value.rawStorageUri
            if (currentUri != null) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.dialog_reset_storage_title)
                    .setMessage(R.string.dialog_reset_storage_raw_message)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.btn_reset) { _, _ ->
                        settingsViewModel.updateStorageUri(isJpg = false, uri = null, name = null)
                        Toast.makeText(requireContext(), R.string.toast_storage_reset_default, Toast.LENGTH_SHORT).show()
                    }
                    .show()
            } else {
                Toast.makeText(requireContext(), R.string.toast_storage_already_default, Toast.LENGTH_SHORT).show()
            }
            true
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
                        label = getString(R.string.pref_clear_cache_label),
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
                                label = getString(R.string.pref_clear_cache_label),
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
            label = getString(R.string.pref_clear_cache_label),
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
                    label = getString(R.string.pref_clear_cache_label),
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
        const val KEY_MOTION_PHOTO_AUDIO = "motion_photo_audio_enabled"
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
        const val KEY_GALLERY_COLUMNS_PORTRAIT = "gallery_columns_portrait"
        const val KEY_GALLERY_COLUMNS_LANDSCAPE = "gallery_columns_landscape"
        const val KEY_GALLERY_WATERFALL = "gallery_waterfall"
        const val KEY_PLAYGROUND_COLUMNS = "playground_columns"

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
