package com.android.example.cameraxbasic.fragments

import androidx.appcompat.app.AlertDialog
import android.content.Context
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

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

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
        setupMenus()
        setupCheckboxes()
        setupNavigation()
        updateDebugStats()
    }


    override fun onResume() {
        super.onResume()
        updateDebugStats()
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
        val savedBurst = prefs.getString(KEY_HDR_BURST_COUNT, "3")
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

        // Default Focal Length
        val focalAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, FOCAL_LENGTHS)
        binding.menuDefaultFocalLength.setAdapter(focalAdapter)
        val savedFocal = prefs.getString(KEY_DEFAULT_FOCAL_LENGTH, "24")
        binding.menuDefaultFocalLength.setText(savedFocal, false)
        binding.menuDefaultFocalLength.setOnItemClickListener { _, _, position, _ ->
            prefs.edit().putString(KEY_DEFAULT_FOCAL_LENGTH, FOCAL_LENGTHS[position]).apply()
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
        binding.switchUseGpu.isChecked = prefs.getBoolean(KEY_USE_GPU, false)
        binding.switchUseGpu.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_USE_GPU, isChecked).apply()
        }

        binding.switchLivePreview.isChecked = prefs.getBoolean(KEY_ENABLE_LUT_PREVIEW, true)
        binding.switchLivePreview.setOnCheckedChangeListener { _, isChecked ->
             prefs.edit().putBoolean(KEY_ENABLE_LUT_PREVIEW, isChecked).apply()
        }

        binding.cbSaveTiff.isChecked = prefs.getBoolean(KEY_SAVE_TIFF, true)
        binding.cbSaveJpg.isChecked = prefs.getBoolean(KEY_SAVE_JPG, true)

        binding.cbSaveTiff.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_SAVE_TIFF, isChecked).apply()
        }

        binding.cbSaveJpg.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_SAVE_JPG, isChecked).apply()
        }

        binding.switchManualControls.isChecked = prefs.getBoolean(KEY_MANUAL_CONTROLS, false)
        binding.switchManualControls.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_MANUAL_CONTROLS, isChecked).apply()
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
        const val KEY_USE_GPU = "use_gpu"
        const val KEY_MANUAL_CONTROLS = "enable_manual_controls"
        const val KEY_ENABLE_LUT_PREVIEW = "enable_lut_preview"
        const val KEY_DEFAULT_FOCAL_LENGTH = "default_focal_length"
        const val KEY_ANTIBANDING = "antibanding_mode"
        const val KEY_FLASH_MODE = "flash_mode"
        const val KEY_HDR_BURST_COUNT = "hdr_burst_count"
        const val KEY_HDR_UNDEREXPOSURE_MODE = "hdr_underexposure_mode"

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
