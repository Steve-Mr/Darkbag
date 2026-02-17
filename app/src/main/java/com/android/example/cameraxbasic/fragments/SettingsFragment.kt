package com.android.example.cameraxbasic.fragments

import androidx.appcompat.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
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
import com.android.example.cameraxbasic.R
import com.android.example.cameraxbasic.databinding.FragmentSettingsBinding
import com.android.example.cameraxbasic.utils.LutManager
import com.google.android.material.color.MaterialColors
import java.io.File

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private lateinit var prefs: SharedPreferences
    private lateinit var cameraRepository: com.android.example.cameraxbasic.utils.CameraRepository

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        cameraRepository = com.android.example.cameraxbasic.utils.CameraRepository(requireContext())

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
        setupCheckboxes()
        setupStoragePickers()
        setupNavigation()
        updateDebugStats()
        updateDebugVisibility()
    }


    override fun onResume() {
        super.onResume()
        updateDebugStats()
        // Re-apply adapters to fix dropdown disappearance bug
        setupMenus()
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
        val logs = com.android.example.cameraxbasic.utils.DebugLogManager.getLogs()
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

    private fun setupMenus() {
        // Target Log Curve
        val logAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, LOG_CURVES)
        binding.menuTargetLog.setAdapter(logAdapter)
        val savedLog = prefs.getString(KEY_TARGET_LOG, "None")
        binding.menuTargetLog.setText(savedLog, false)
        binding.menuTargetLog.setOnItemClickListener { _, _, position, _ ->
            prefs.edit().putString(KEY_TARGET_LOG, LOG_CURVES[position]).apply()
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
        val lenses = cameraRepository.getFocalLengthPresets(emptySet())
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

            val savedFocal1x = prefs.getString(KEY_DEFAULT_FOCAL_1X, "24mm")
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
    }

    private fun setupCheckboxes() {
        binding.switchLivePreview.isChecked = prefs.getBoolean(KEY_ENABLE_LUT_PREVIEW, true)
        binding.switchLivePreview.setOnCheckedChangeListener { _, isChecked ->
             prefs.edit().putBoolean(KEY_ENABLE_LUT_PREVIEW, isChecked).apply()
        }

        binding.cbSaveTiff.isChecked = prefs.getBoolean(KEY_SAVE_TIFF, true)
        binding.cbSaveJpg.isChecked = prefs.getBoolean(KEY_SAVE_JPG, true)
        binding.cbSaveRaw.isChecked = prefs.getBoolean(KEY_SAVE_RAW, true)

        binding.cbSaveTiff.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_SAVE_TIFF, isChecked).apply()
            updateStorageVisibility()
        }

        binding.cbSaveJpg.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_SAVE_JPG, isChecked).apply()
            updateStorageVisibility()
        }

        binding.cbSaveRaw.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_SAVE_RAW, isChecked).apply()
            updateStorageVisibility()
        }

        updateStorageVisibility()

        binding.switchHqBackgroundExport.isChecked = prefs.getBoolean(KEY_HQ_BACKGROUND_EXPORT, false)
        binding.switchHqBackgroundExport.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_HQ_BACKGROUND_EXPORT, isChecked).apply()
        }

        binding.switchManualControls.isChecked = prefs.getBoolean(KEY_MANUAL_CONTROLS, false)
        binding.switchManualControls.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_MANUAL_CONTROLS, isChecked).apply()
        }

        binding.switchMirrorFront.isChecked = prefs.getBoolean(KEY_MIRROR_FRONT_CAMERA, true)
        binding.switchMirrorFront.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_MIRROR_FRONT_CAMERA, isChecked).apply()
        }

        binding.switchUseCamerax.isChecked = prefs.getBoolean(KEY_USE_CAMERAX, false)
        binding.switchUseCamerax.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_USE_CAMERAX, isChecked).apply()
        }

        binding.switchHdrPlusOis.isChecked = prefs.getBoolean(KEY_HDR_PLUS_OIS, true)
        binding.switchHdrPlusOis.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_HDR_PLUS_OIS, isChecked).apply()
        }

        binding.switchForce60fps.isChecked = prefs.getBoolean(KEY_FORCE_60FPS, false)
        binding.switchForce60fps.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_FORCE_60FPS, isChecked).apply()
        }

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
        binding.layoutTiffStorage.visibility = if (binding.cbSaveTiff.isChecked) View.VISIBLE else View.GONE
        binding.layoutRawStorage.visibility = if (binding.cbSaveRaw.isChecked) View.VISIBLE else View.GONE

        binding.tvTiffPath.text = prefs.getString(KEY_TIFF_STORAGE_URI_NAME, "Default")
        binding.tvRawPath.text = prefs.getString(KEY_RAW_STORAGE_URI_NAME, "Default")
    }

    private val tiffPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            requireContext().contentResolver.takePersistableUriPermission(it, takeFlags)

            val folderName = com.android.example.cameraxbasic.utils.MediaStoreUtils.getFolderNameFromUri(requireContext(), it)
            prefs.edit()
                .putString(KEY_TIFF_STORAGE_URI, it.toString())
                .putString(KEY_TIFF_STORAGE_URI_NAME, folderName)
                .apply()
            updateStorageVisibility()
        }
    }

    private val rawPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            requireContext().contentResolver.takePersistableUriPermission(it, takeFlags)

            val folderName = com.android.example.cameraxbasic.utils.MediaStoreUtils.getFolderNameFromUri(requireContext(), it)
            prefs.edit()
                .putString(KEY_RAW_STORAGE_URI, it.toString())
                .putString(KEY_RAW_STORAGE_URI_NAME, folderName)
                .apply()
            updateStorageVisibility()
        }
    }

    private fun setupStoragePickers() {
        binding.tvTiffPath.setOnClickListener {
            tiffPicker.launch(null)
        }
        binding.tvRawPath.setOnClickListener {
            rawPicker.launch(null)
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
        const val KEY_SAVE_TIFF = "save_tiff"
        const val KEY_SAVE_JPG = "save_jpg"
        const val KEY_HQ_BACKGROUND_EXPORT = "hq_background_export"
        const val KEY_USE_GPU = "use_gpu"
        const val KEY_MANUAL_CONTROLS = "enable_manual_controls"
        const val KEY_ENABLE_LUT_PREVIEW = "enable_lut_preview"
        const val KEY_DEFAULT_LENS_ID = "default_lens_id"
        const val KEY_DEFAULT_FOCAL_1X = "default_focal_1x"
        const val KEY_ANTIBANDING = "antibanding_mode"
        const val KEY_FLASH_MODE = "flash_mode"
        const val KEY_HDR_BURST_COUNT = "hdr_burst_count"
        const val KEY_HDR_UNDEREXPOSURE_MODE = "hdr_underexposure_mode"
        const val KEY_USE_CAMERAX = "use_camerax_engine"
        const val KEY_MIRROR_FRONT_CAMERA = "mirror_front_camera"
        const val KEY_HDR_PLUS_OIS = "hdr_plus_ois_enabled"
        const val KEY_FORCE_60FPS = "force_60fps"
        const val KEY_DEBUG_ENABLED = "debug_enabled"
        const val KEY_SAVE_RAW = "save_raw"
        const val KEY_TIFF_STORAGE_URI = "tiff_storage_uri"
        const val KEY_TIFF_STORAGE_URI_NAME = "tiff_storage_uri_name"
        const val KEY_RAW_STORAGE_URI = "raw_storage_uri"
        const val KEY_RAW_STORAGE_URI_NAME = "raw_storage_uri_name"

        val FOCAL_LENGTHS = listOf("24", "28", "35")
        val ANTIBANDING_MODES = listOf("Auto", "50Hz", "60Hz", "Off")
        val BURST_SIZES = listOf("3", "4", "5", "6", "7", "8")
        val HDR_UNDEREXPOSURE_MODES = listOf("0 EV", "-1 EV", "-2 EV", "Dynamic (Experimental)")

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
