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
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.navigation.Navigation
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import top.maary.darkbag.R
import top.maary.darkbag.databinding.FragmentSettingsBinding
import top.maary.darkbag.utils.LutManager
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

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        cameraRepository = CameraRepository(requireContext())

        // Apply Edge-to-Edge Insets
        ViewCompat.setOnApplyWindowInsetsListener(binding.appBar) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, systemBars.top, v.paddingRight, v.paddingBottom)
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
        setupMenus()
        setupStartupSettings()
        setupCheckboxes()
        setupStoragePickers()
        setupCacheManagement()
        setupNavigation()
        updateDebugStats()
        updateDebugVisibility()
    }


    override fun onResume() {
        super.onResume()
        updateDebugStats()
        // Re-apply adapters to fix dropdown disappearance bug
        setupMenus()
        updateCheckboxStates()
        syncLocationSettingState()
        updateStorageVisibility()
        updateCacheSize()
    }

    private var aboutClickCount = 0
    private fun setupAboutSection() {
        try {
            val packageInfo = requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)
            binding.tvAppName.text = getString(R.string.app_name)
            binding.tvAppVersion.text = "Version ${packageInfo.versionName} (${packageInfo.versionCode})"
        } catch (e: Exception) {
            binding.tvAppName.text = "Camera"
            binding.tvAppVersion.text = "Version 1.0.0"
        }

        binding.cardAbout.setOnClickListener {
            aboutClickCount++
            if (aboutClickCount >= 5) {
                if (!prefs.getBoolean(KEY_DEBUG_ENABLED, false)) {
                    prefs.edit().putBoolean(KEY_DEBUG_ENABLED, true).apply()
                    updateDebugVisibility()
                    Toast.makeText(requireContext(), "Debug Mode Enabled", Toast.LENGTH_SHORT).show()
                }
                aboutClickCount = 0
            }
        }
    }

    private fun updateDebugVisibility() {
        val isDebug = prefs.getBoolean(KEY_DEBUG_ENABLED, false)
        binding.sectionDebug.visibility = if (isDebug) View.VISIBLE else View.GONE
        binding.switchCloseDebug.isChecked = false
    }

    private fun updateDebugStats() {
        val logs = DebugLogManager.getLogs()
        if (logs.isNotEmpty()) {
            binding.tvDebugStats.text = logs
        }
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
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

    private fun setupMenus() {
        // Target Log Curve
        val logAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, LOG_CURVES)
        binding.menuTargetLog.setAdapter(logAdapter)
        val savedLog = prefs.getString(KEY_TARGET_LOG, "None")
        binding.menuTargetLog.setText(savedLog, false)
        binding.menuTargetLog.setOnItemClickListener { _, _, position, _ ->
            val selectedLog = LOG_CURVES[position]
            val editor = prefs.edit().putString(KEY_TARGET_LOG, selectedLog)
            if (selectedLog == "None") {
                editor.remove(KEY_ACTIVE_LUT)
            }
            editor.apply()
            updateCheckboxStates()
            updateStorageVisibility()
        }

        // HDR+ Burst Frames
        val burstAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, BURST_SIZES)
        binding.menuHdrBurst.setAdapter(burstAdapter)
        val savedBurst = prefs.getString(KEY_HDR_BURST_COUNT, "5")
        binding.menuHdrBurst.setText(savedBurst, false)
        binding.menuHdrBurst.setOnItemClickListener { _, _, position, _ ->
            prefs.edit().putString(KEY_HDR_BURST_COUNT, BURST_SIZES[position]).apply()
        }

        // HDR+ Underexposure
        val underexposureAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, HDR_UNDEREXPOSURE_MODES)
        binding.menuHdrUnderexposure.setAdapter(underexposureAdapter)
        val savedUnderexposure = prefs.getString(KEY_HDR_UNDEREXPOSURE_MODE, "Dynamic")
        binding.menuHdrUnderexposure.setText(savedUnderexposure, false)
        binding.menuHdrUnderexposure.setOnItemClickListener { _, _, position, _ ->
            prefs.edit().putString(KEY_HDR_UNDEREXPOSURE_MODE, HDR_UNDEREXPOSURE_MODES[position]).apply()
        }

        // Default Lens (Startup)
        val lenses = cameraRepository.getAllFocalLengthPresets(emptySet())
        val lensDisplayNames = lenses.map { it.name }
        val lensAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, lensDisplayNames)
        binding.menuDefaultLens.setAdapter(lensAdapter)

        val savedLensId = prefs.getString(KEY_DEFAULT_LENS_ID, null)
        val initialLens = lenses.find { it.sensorId == savedLensId }
            ?: lenses.find { it.multiplier in 0.95f..1.05f && !it.isZoomPreset }
            ?: lenses.firstOrNull()

        if (initialLens != null) {
            val index = lenses.indexOf(initialLens)
            binding.menuDefaultLens.setText(lensDisplayNames[index], false)
        }

        binding.menuDefaultLens.setOnItemClickListener { _, _, position, _ ->
            val selected = lenses[position]
            prefs.edit().putString(KEY_DEFAULT_LENS_ID, selected.sensorId).apply()
        }

        // 1.0x Default Focal Length
        val mainWide = cameraRepository.getMainWideLens(emptySet())
        if (mainWide != null) {
            val presets1x = cameraRepository.get1xPresets(mainWide)
            val names1x = presets1x.map { it.name }
            val adapter1x = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, names1x)
            binding.menuDefaultFocal1x.setAdapter(adapter1x)

            val baseFocalName = presets1x.firstOrNull()?.name ?: "24mm"
            val savedFocal1x = prefs.getString(KEY_DEFAULT_FOCAL_1X, baseFocalName)
            binding.menuDefaultFocal1x.setText(savedFocal1x, false)
            binding.menuDefaultFocal1x.setOnItemClickListener { _, _, position, _ ->
                prefs.edit().putString(KEY_DEFAULT_FOCAL_1X, names1x[position]).apply()
            }
        }

        // Antibanding
        val antibandingAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, ANTIBANDING_MODES)
        binding.menuAntibanding.setAdapter(antibandingAdapter)
        val savedAntibanding = prefs.getString(KEY_ANTIBANDING, "Auto")
        binding.menuAntibanding.setText(savedAntibanding, false)
        binding.menuAntibanding.setOnItemClickListener { _, _, position, _ ->
            prefs.edit().putString(KEY_ANTIBANDING, ANTIBANDING_MODES[position]).apply()
        }

        // Half-frame Layout
        val halfFrameLayoutAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, HALF_FRAME_LAYOUTS)
        binding.menuHalfFrameLayout.setAdapter(halfFrameLayoutAdapter)
        val savedHalfFrameLayout = prefs.getString(KEY_HALF_FRAME_LAYOUT, HALF_FRAME_LAYOUT_SBS)
        binding.menuHalfFrameLayout.setText(savedHalfFrameLayout, false)
        binding.menuHalfFrameLayout.setOnItemClickListener { _, _, position, _ ->
            prefs.edit().putString(KEY_HALF_FRAME_LAYOUT, HALF_FRAME_LAYOUTS[position]).apply()
        }

        setupExternalViewerMenu()

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

        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, appNames)
        binding.menuExternalViewer.setAdapter(adapter)

        val savedName = prefs.getString(KEY_EXTERNAL_VIEWER_NAME, defaultName)
        binding.menuExternalViewer.setText(savedName, false)

        binding.menuExternalViewer.setOnItemClickListener { _, _, position, _ ->
            prefs.edit()
                .putString(KEY_EXTERNAL_VIEWER_NAME, appNames[position])
                .putString(KEY_EXTERNAL_VIEWER_PACKAGE, packageNames[position])
                .apply()
        }

        // Initial visibility
        binding.layoutExternalViewerMenu.visibility = if (prefs.getBoolean(KEY_USE_INTERNAL_VIEWER, true)) View.GONE else View.VISIBLE
    }

    private fun updateCheckboxStates() {
        val isLut = isLutActive()
        var isJpg = binding.cbSaveJpg.isChecked
        var isRaw = binding.cbSaveRaw.isChecked

        if (isLut) {
            // Requirement: JPG must be selected if LUT is active, as we don't save LUT to RAW.
            if (!isJpg) {
                isJpg = true
                binding.cbSaveJpg.isChecked = true
                prefs.edit().putBoolean(KEY_SAVE_JPG, true).apply()
            }
            binding.cbSaveJpg.isEnabled = false // Force JPG if LUT active
            binding.cbSaveRaw.isEnabled = true
        } else {
            // Requirement: at least one of (jpg, raw) selected.
            val count = (if (isJpg) 1 else 0) + (if (isRaw) 1 else 0)
            binding.cbSaveJpg.isEnabled = !(isJpg && count == 1)
            binding.cbSaveRaw.isEnabled = !(isRaw && count == 1)
        }
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

            val typedValue = android.util.TypedValue()
            requireContext().theme.resolveAttribute(com.google.android.material.R.attr.colorTertiaryContainer, typedValue, true)
            val primaryColor = typedValue.data

            val defaultColor = ContextCompat.getColor(requireContext(), android.R.color.transparent)

            if (defaultStartup == STARTUP_CAMERA) {
                binding.cardStartupCamera.strokeColor = primaryColor
                binding.cardStartupPlayground.strokeColor = defaultColor
            } else {
                binding.cardStartupCamera.strokeColor = defaultColor
                binding.cardStartupPlayground.strokeColor = primaryColor
            }
        }

        fun updateSwitchStates() {
            binding.switchEnableCamera.isChecked = enableCamera
            binding.switchEnablePlayground.isChecked = enablePlayground
            binding.switchShowFloatingToolbar.isChecked = prefs.getBoolean(KEY_SHOW_FLOATING_TOOLBAR, true)

            // At least one must be enabled
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

        binding.switchShowFloatingToolbar.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_SHOW_FLOATING_TOOLBAR, isChecked).apply()
        }
    }

    private fun setupCheckboxes() {
        setupSwitch(binding.switchLivePreview, KEY_ENABLE_LUT_PREVIEW)

        binding.cbSaveJpg.isChecked = prefs.getBoolean(KEY_SAVE_JPG, true)
        binding.cbSaveRaw.isChecked = prefs.getBoolean(KEY_SAVE_RAW, true)

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

        setupSwitch(binding.switchManualControls, KEY_MANUAL_CONTROLS, false)
        setupSwitch(binding.switchExpFocusPeaking, KEY_EXP_FOCUS_PEAKING, false)
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

        setupSwitch(binding.switchHalfFrameMode, KEY_HALF_FRAME_MODE, false)
        binding.switchHalfFrameMode.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_HALF_FRAME_MODE, isChecked).apply()
        }
        setupSwitch(binding.switchHalfFrameDownsample, KEY_HALF_FRAME_DOWNSAMPLE)
        setupSwitch(binding.switchHalfFrameDateStamp, KEY_HALF_FRAME_DATE_STAMP, false)
        setupSwitch(binding.switchHalfFrameLightLeak, KEY_HALF_FRAME_LIGHT_LEAK, false)
        setupSwitch(binding.switchHalfFrameAutoBurst, KEY_HALF_FRAME_AUTO_BURST, false)

        binding.cbHfSaveRaw.isChecked = prefs.getBoolean(KEY_HALF_FRAME_SAVE_RAW, false)
        binding.cbHfSaveRaw.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_HALF_FRAME_SAVE_RAW, isChecked).apply()
        }

        setupMultiCameraSettings()

        setupSwitch(binding.switchMirrorFront, KEY_MIRROR_FRONT_CAMERA)
        setupSwitch(binding.switchUseCamerax, KEY_USE_CAMERAX, false)
        setupSwitch(binding.switchHdrPlusOis, KEY_HDR_PLUS_OIS)

        binding.switchUseInternalViewer.isChecked = prefs.getBoolean(KEY_USE_INTERNAL_VIEWER, true)
        binding.switchUseInternalViewer.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_USE_INTERNAL_VIEWER, isChecked).apply()
            binding.layoutExternalViewerMenu.visibility = if (isChecked) View.GONE else View.VISIBLE
        }

        setupSwitch(binding.switchShowHdrPlusSwitch, KEY_SHOW_HDR_PLUS_SWITCH)
        setupSwitch(binding.switchShowUnderexposureButton, KEY_SHOW_HDR_UNDEREXPOSURE_BUTTON)
        setupSwitch(binding.switchShowSettingsButton, KEY_SHOW_SETTINGS_BUTTON)
        setupSwitch(binding.switchShowCameraSwitchButton, KEY_SHOW_CAMERA_SWITCH_BUTTON)
        setupSwitch(binding.switchShowModeSwitchButton, KEY_SHOW_MODE_SWITCH_BUTTON)
        setupSwitch(binding.switchShowLensControls, KEY_SHOW_LENS_CONTROLS)
        setupSwitch(binding.switchShowLutSwitcher, KEY_SHOW_LUT_SWITCHER)

        setupSwitch(binding.switchForce60fps, KEY_FORCE_60FPS, false)
        setupSwitch(binding.switchMemoryColorOptimization, KEY_MEMORY_COLOR_OPTIMIZATION, false)

        binding.switchCloseDebug.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                prefs.edit().putBoolean(KEY_DEBUG_ENABLED, false).apply()
                updateDebugVisibility()
                aboutClickCount = 0
            }
        }
    }

    private fun updateStorageVisibility() {
        binding.layoutJpgStorage.visibility = if (binding.cbSaveJpg.isChecked) View.VISIBLE else View.GONE
        binding.layoutRawStorage.visibility = if (binding.cbSaveRaw.isChecked) View.VISIBLE else View.GONE

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
            binding.layoutMultiCameraCount.isEnabled = false
            binding.layoutMultiCameraDualPair.isEnabled = false
            binding.cbMultiCameraSaveRaw.isEnabled = false
            return
        }

        val physLenses = multiCamInfo?.physicalLenses?.joinToString { "${it.name} (${it.type})" } ?: "None"
        binding.tvMultiCameraStatus.text = "$hwDesc\n检测到镜头: $physLenses"
        binding.switchMultiCameraMode.isEnabled = true

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
        val savedCountKey = prefs.getString(KEY_MULTI_CAMERA_COUNT_PREF, MultiCameraCountPreference.AUTO_MAX.key)
        val currentCountIndex = countKeys.indexOf(savedCountKey).coerceAtLeast(0)
        binding.menuMultiCameraCount.setText(countOptions[currentCountIndex], false)

        val countAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, countOptions)
        binding.menuMultiCameraCount.setAdapter(countAdapter)
        binding.menuMultiCameraCount.setOnItemClickListener { _, _, position, _ ->
            prefs.edit().putString(KEY_MULTI_CAMERA_COUNT_PREF, countKeys[position]).apply()
            updateMultiCameraVisibility()
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
        val savedPairKey = prefs.getString(KEY_MULTI_CAMERA_DUAL_PAIR, DualLensPairPreference.WIDE_ULTRAWIDE.key)
        val currentPairIndex = pairKeys.indexOf(savedPairKey).coerceAtLeast(0)
        binding.menuMultiCameraDualPair.setText(pairOptions[currentPairIndex], false)

        val pairAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, pairOptions)
        binding.menuMultiCameraDualPair.setAdapter(pairAdapter)
        binding.menuMultiCameraDualPair.setOnItemClickListener { _, _, position, _ ->
            prefs.edit().putString(KEY_MULTI_CAMERA_DUAL_PAIR, pairKeys[position]).apply()
        }

        binding.cbMultiCameraSaveRaw.isChecked = prefs.getBoolean(KEY_MULTI_CAMERA_SAVE_RAW, false)
        binding.cbMultiCameraSaveRaw.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_MULTI_CAMERA_SAVE_RAW, isChecked).apply()
        }

        updateMultiCameraVisibility()
    }

    private fun updateMultiCameraVisibility() {
        val isEnabled = prefs.getBoolean(KEY_MULTI_CAMERA_MODE, false)
        val countKey = prefs.getString(KEY_MULTI_CAMERA_COUNT_PREF, MultiCameraCountPreference.AUTO_MAX.key)
        binding.layoutMultiCameraCount.visibility = if (isEnabled) View.VISIBLE else View.GONE
        binding.layoutMultiCameraDualPair.visibility = if (isEnabled && countKey == MultiCameraCountPreference.DUAL.key) View.VISIBLE else View.GONE
        binding.cbMultiCameraSaveRaw.visibility = if (isEnabled) View.VISIBLE else View.GONE
    }

    private fun setupSwitch(switch: com.google.android.material.materialswitch.MaterialSwitch, key: String, defaultValue: Boolean = true) {
        switch.isChecked = prefs.getBoolean(key, defaultValue)
        switch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(key, isChecked).apply()
        }
    }

    private fun setupStoragePickers() {
        binding.layoutJpgStorage.setOnClickListener {
            jpgPicker.launch(null)
        }
        binding.layoutRawStorage.setOnClickListener {
            rawPicker.launch(null)
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
                    binding.btnClearCache.isEnabled = false
                    binding.tvCacheSize.text = getString(R.string.pref_cache_calculating)
                    lifecycleScope.launch(Dispatchers.IO) {
                        val freedBytes = CacheManager.clearCache(appContext)
                        val freedFormatted = CacheManager.formatSize(freedBytes)
                        withContext(Dispatchers.Main) {
                            _binding?.btnClearCache?.isEnabled = true
                            _binding?.tvCacheSize?.text = CacheManager.formatSize(0L)
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
        val appContext = context?.applicationContext ?: return
        binding.btnClearCache.isEnabled = false
        binding.tvCacheSize.text = getString(R.string.pref_cache_calculating)
        lifecycleScope.launch(Dispatchers.IO) {
            val size = CacheManager.calculateCacheSize(appContext)
            val formatted = CacheManager.formatSize(size)
            withContext(Dispatchers.Main) {
                _binding?.btnClearCache?.isEnabled = true
                _binding?.tvCacheSize?.text = formatted
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
        const val KEY_USE_CAMERAX = "use_camerax_engine"
        const val KEY_MIRROR_FRONT_CAMERA = "mirror_front_camera"
        const val KEY_HDR_PLUS_OIS = "hdr_plus_ois_enabled"
        const val KEY_FORCE_60FPS = "force_60fps"
        const val KEY_DEBUG_ENABLED = "debug_enabled"
        const val KEY_MOTION_PHOTO = "motion_photo_enabled"
        const val KEY_SAVE_LOCATION = "save_location_enabled"
        const val KEY_SAVE_RAW = "save_raw"
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

        const val KEY_ENABLE_CAMERA = "enable_camera"
        const val KEY_ENABLE_PLAYGROUND = "enable_playground"
        const val KEY_SHOW_FLOATING_TOOLBAR = "show_floating_toolbar"
        const val KEY_EXP_FOCUS_PEAKING = "exp_focus_peaking"
        const val KEY_MEMORY_COLOR_OPTIMIZATION = "memory_color_optimization"

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
}
