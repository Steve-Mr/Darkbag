package top.maary.darkbag.fragments

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
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
import top.maary.darkbag.camera.zsl.ZslCapabilityDetector
import top.maary.darkbag.camera.zsl.ZslDebugFormatter
import top.maary.darkbag.camera.zsl.ZslDebugLabels
import top.maary.darkbag.camera.zsl.ZslDebugRegistry
import top.maary.darkbag.camera.zsl.ZslDebugState
import top.maary.darkbag.camera.zsl.ZslReason
import top.maary.darkbag.camera.zsl.ZslRoute
import top.maary.darkbag.databinding.FragmentSettingsBinding
import top.maary.darkbag.fragments.settings.SettingsBentoDelegate
import top.maary.darkbag.fragments.settings.SettingsSheetDispatcher
import top.maary.darkbag.repository.PreferenceKeys
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

    private lateinit var bentoDelegate: SettingsBentoDelegate
    private lateinit var sheetDispatcher: SettingsSheetDispatcher

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        top.maary.darkbag.utils.PredictiveBackTransitions.apply(this)
    }

    private val expandedSectionIds = mutableSetOf<Int>()
    private var aboutClickCount = 0
    private var zslToggleGuard = false

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[android.Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseGranted = permissions[android.Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fineGranted || coarseGranted) {
            settingsViewModel.updateSaveLocationEnabled(true)
        } else {
            settingsViewModel.updateSaveLocationEnabled(false)
            top.maary.darkbag.ui.DarkbagFeedback.showSnackbar(
                view,
                R.string.location_permission_denied,
                com.google.android.material.snackbar.Snackbar.LENGTH_LONG
            )
        }
    }

    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            top.maary.darkbag.ui.DarkbagFeedback.showSnackbar(view, R.string.error_mic_permission_silent_video)
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
        setupCollapsibleSections()
        setupMenus()
        setupStartupSettings()
        setupCheckboxes()
        setupZslSettings()
        setupStoragePickers()
        setupCacheManagement()
        setupNavigation()
        setupMultiCameraSettings()
        updateDebugStats()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                settingsViewModel.preferences.collect { prefsState ->
                    bentoDelegate.renderPreferences(binding, prefsState)
                    renderZslDebug(ZslDebugRegistry.state.value)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                ZslDebugRegistry.state.collect { state ->
                    renderZslDebug(state)
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

    private fun setupCollapsibleSections() {
        bindCollapsibleSection(
            header = binding.headerViewfinderToggle,
            content = binding.containerViewfinderContent,
            arrow = binding.ivViewfinderArrow,
            sectionId = R.id.header_viewfinder_toggle
        )
        bindCollapsibleSection(
            header = binding.headerComputeToggle,
            content = binding.containerComputeContent,
            arrow = binding.ivComputeArrow,
            sectionId = R.id.header_compute_toggle
        )
        bindCollapsibleSection(
            header = binding.headerVideoToggle,
            content = binding.containerVideoContent,
            arrow = binding.ivVideoArrow,
            sectionId = R.id.header_video_toggle
        )
        bindCollapsibleSection(
            header = binding.headerColorEngineToggle,
            content = binding.containerColorEngineContent,
            arrow = binding.ivColorEngineArrow,
            sectionId = R.id.header_color_engine_toggle
        )
        bindCollapsibleSection(
            header = binding.headerOutputStorageToggle,
            content = binding.containerOutputStorageContent,
            arrow = binding.ivOutputStorageArrow,
            sectionId = R.id.header_output_storage_toggle
        )
    }

    private fun bindCollapsibleSection(
        header: View,
        content: View,
        arrow: ImageView,
        sectionId: Int
    ) {
        val isInitiallyExpanded = expandedSectionIds.contains(sectionId)
        content.visibility = if (isInitiallyExpanded) View.VISIBLE else View.GONE
        arrow.rotation = if (isInitiallyExpanded) 180f else 0f

        header.setOnClickListener {
            val isNowExpanded = if (expandedSectionIds.contains(sectionId)) {
                expandedSectionIds.remove(sectionId)
                false
            } else {
                expandedSectionIds.add(sectionId)
                true
            }
            content.visibility = if (isNowExpanded) View.VISIBLE else View.GONE
            arrow.animate()
                .rotation(if (isNowExpanded) 180f else 0f)
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

        binding.btnGithubRepo.text = CameraBentoDeckView.formatProTelemetry(
            label = getString(R.string.pref_github_repo_label),
            value = getString(R.string.pref_github_repo_value),
            isTabActive = false,
            context = requireContext()
        )
        binding.btnGithubRepo.setOnClickListener {
            val url = getString(R.string.github_repo_url)
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }.onFailure {
                top.maary.darkbag.ui.DarkbagFeedback.showSnackbar(
                    view,
                    R.string.error_no_browser_app,
                    isError = true
                )
            }
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
                top.maary.darkbag.ui.DarkbagFeedback.showSnackbar(
                    view,
                    getString(R.string.lut_reset_incompatible_toast, incompatibleLog)
                )
            }
        }
        binding.btnMenuHdrFusionMode.setOnClickListener { sheetDispatcher.showHdrFusionSheet() }
        binding.btnMenuHdrBurstMode.setOnClickListener { sheetDispatcher.showHdrBurstSheet() }
        binding.btnMenuUnderexposureMode.setOnClickListener { sheetDispatcher.showUnderexposureSheet() }
        binding.btnMenuColorEngine.setOnClickListener { sheetDispatcher.showColorEngineSheet() }
        binding.btnMenuWbStrategy.setOnClickListener { sheetDispatcher.showWbStrategySheet() }
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
        binding.btnMenuRawMotionQuality.setOnClickListener { sheetDispatcher.showExpRawMotionQualitySheet() }
    }

    private fun setupStartupSettings() {
        binding.cardStartupCamera.setOnClickListener {
            if (settingsViewModel.preferences.value.enableCamera) {
                settingsViewModel.updateDefaultStartupPage(PreferenceKeys.STARTUP_CAMERA)
            }
        }

        binding.cardStartupPlayground.setOnClickListener {
            if (settingsViewModel.preferences.value.enablePlayground) {
                settingsViewModel.updateDefaultStartupPage(PreferenceKeys.STARTUP_PLAYGROUND)
            }
        }

        binding.switchEnableCamera.setOnCheckedChangeListener { _, isChecked ->
            if (bentoDelegate.isRendering) return@setOnCheckedChangeListener
            settingsViewModel.updateEnableCamera(isChecked)
            if (!isChecked && settingsViewModel.preferences.value.defaultStartupPage == PreferenceKeys.STARTUP_CAMERA) {
                settingsViewModel.updateDefaultStartupPage(PreferenceKeys.STARTUP_PLAYGROUND)
            }
        }

        binding.switchEnablePlayground.setOnCheckedChangeListener { _, isChecked ->
            if (bentoDelegate.isRendering) return@setOnCheckedChangeListener
            settingsViewModel.updateEnablePlayground(isChecked)
            if (!isChecked && settingsViewModel.preferences.value.defaultStartupPage == PreferenceKeys.STARTUP_PLAYGROUND) {
                settingsViewModel.updateDefaultStartupPage(PreferenceKeys.STARTUP_CAMERA)
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
        binding.switchExpMulticamColorHarmonization.setOnCheckedChangeListener { _, isChecked ->
            if (bentoDelegate.isRendering) return@setOnCheckedChangeListener
            settingsViewModel.updateExpMultiCamColorHarmonization(isChecked)
        }
        binding.switchExpRawMotionPhoto.setOnCheckedChangeListener { _, isChecked ->
            if (bentoDelegate.isRendering) return@setOnCheckedChangeListener
            settingsViewModel.updateExpRawMotionPhoto(isChecked)
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
        }

        binding.btnClearDebugLogs.setOnClickListener {
            DebugLogManager.clearLogs()
            updateDebugStats()
        }

        binding.btnCopyDebugLogs.setOnClickListener {
            val logs = DebugLogManager.getLogs()
            if (logs.isNotBlank() && logs != "No logs yet.") {
                val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Darkbag Timing Logs", logs))
                top.maary.darkbag.ui.DarkbagFeedback.showSnackbar(view, R.string.toast_debug_logs_copied)
            } else {
                top.maary.darkbag.ui.DarkbagFeedback.showSnackbar(view, R.string.toast_no_logs_to_copy)
            }
        }
    }

    private fun setupZslSettings() {
        binding.switchZsl.setOnCheckedChangeListener { _, isChecked ->
            if (bentoDelegate.isRendering || zslToggleGuard) return@setOnCheckedChangeListener
            if (!isChecked) {
                settingsViewModel.updateZslEnabled(false)
                return@setOnCheckedChangeListener
            }
            viewLifecycleOwner.lifecycleScope.launch {
                val report = withContext(Dispatchers.Default) {
                    ZslCapabilityDetector.inspectDefaultBackCamera(requireContext().applicationContext)
                }
                val hdrPlus = settingsViewModel.preferences.value.hdrPlusEnabled
                when {
                    report.route == ZslRoute.RAW_RING && hdrPlus && !report.supportsHdrPlusZsl -> {
                        revertZslToggle()
                        top.maary.darkbag.ui.DarkbagFeedback.showSnackbar(view, R.string.zsl_reason_hdr_unsupported)
                    }

                    report.route == ZslRoute.RAW_RING -> {
                        settingsViewModel.updateZslEnabled(true)
                        settingsViewModel.updateZslCapability(
                            report.route.key,
                            if (report.supportsHdrPlusZsl) report.reason.key else ZslReason.HDR_UNSUPPORTED.key
                        )
                    }

                    report.route == ZslRoute.REPROCESS_RAW -> {
                        revertZslToggle()
                        top.maary.darkbag.ui.DarkbagFeedback.showSnackbar(view, R.string.zsl_toast_reprocess_not_enabled)
                    }

                    else -> {
                        revertZslToggle()
                        top.maary.darkbag.ui.DarkbagFeedback.showSnackbar(view, zslReasonText(report.reason))
                    }
                }
            }
        }
        binding.switchZslAdaptive.setOnCheckedChangeListener { _, isChecked ->
            if (bentoDelegate.isRendering) return@setOnCheckedChangeListener
            settingsViewModel.updateZslAdaptive(isChecked)
        }
        binding.switchZslLazyRing.setOnCheckedChangeListener { _, isChecked ->
            if (bentoDelegate.isRendering) return@setOnCheckedChangeListener
            settingsViewModel.updateZslLazyRing(isChecked)
        }
    }

    private fun revertZslToggle() {
        settingsViewModel.updateZslEnabled(false)
        zslToggleGuard = true
        binding.switchZsl.isChecked = false
        zslToggleGuard = false
    }

    private fun zslReasonText(reason: ZslReason): String = when (reason) {
        ZslReason.NO_RAW -> getString(R.string.zsl_reason_no_raw)
        ZslReason.RAW_TOO_LARGE -> getString(R.string.zsl_reason_raw_too_large)
        ZslReason.NO_READ_SENSOR_SETTINGS -> getString(R.string.zsl_reason_no_sensor_settings)
        ZslReason.LOW_RAM -> getString(R.string.zsl_reason_low_ram)
        ZslReason.NO_CAMERA -> getString(R.string.zsl_reason_no_camera)
        ZslReason.UNKNOWN -> getString(R.string.zsl_reason_unknown)
        else -> getString(R.string.zsl_toast_unavailable)
    }

    private fun renderZslDebug(state: ZslDebugState) {
        if (!settingsViewModel.preferences.value.zslEnabled) {
            binding.cardZslDebug.visibility = View.GONE
            binding.tvZslDebug.visibility = View.GONE
            return
        }
        binding.cardZslDebug.visibility = View.VISIBLE
        binding.tvZslDebug.visibility = View.VISIBLE
        binding.tvZslDebug.text = ZslDebugFormatter.format(
            state,
            ZslDebugLabels(
                armed = getString(R.string.zsl_debug_armed),
                off = getString(R.string.zsl_debug_off),
                stateFormat = getString(R.string.zsl_debug_state),
                reasonFormat = getString(R.string.zsl_debug_reason),
                routeFormat = getString(R.string.zsl_debug_route),
                ringFormat = getString(R.string.zsl_debug_ring),
                ringFpsFormat = getString(R.string.zsl_debug_ring_fps),
                lastFormat = getString(R.string.zsl_debug_last),
                waiting = getString(R.string.zsl_debug_waiting),
                profileHdrPlus = getString(R.string.zsl_debug_profile_hdr_plus),
                profileSingle = getString(R.string.zsl_debug_profile_single),
                profileOff = getString(R.string.zsl_debug_profile_off)
            )
        )
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
        binding.btnMenuMultiCameraCount.isEnabled = true
        binding.btnMenuMultiCameraDualPair.isEnabled = true
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
                    }
                    .show()
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
                    }
                    .show()
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
                                top.maary.darkbag.ui.DarkbagFeedback.showSnackbar(
                                    view,
                                    getString(R.string.clear_cache_success_toast, freedFormatted)
                                )
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
}
