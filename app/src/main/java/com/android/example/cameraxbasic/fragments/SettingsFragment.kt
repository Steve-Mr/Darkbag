package com.android.example.cameraxbasic.fragments

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.navigation.Navigation
import com.android.example.cameraxbasic.R
import com.android.example.cameraxbasic.databinding.FragmentSettingsBinding

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private lateinit var prefs: SharedPreferences

    private val lutPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let {
            // Check extension
            val path = it.toString()
            val filename = getFileName(it)
            if (filename != null && !filename.endsWith(".cube", ignoreCase = true)) {
                Toast.makeText(context, "Please select a valid .cube LUT file", Toast.LENGTH_SHORT).show()
                return@let
            }

            try {
                // Persist permission
                requireContext().contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                prefs.edit().putString(KEY_LUT_URI, path).apply()
                updateLutLabel(path)
            } catch (e: Exception) {
                Toast.makeText(context, "Failed to select LUT: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun getFileName(uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = requireContext().contentResolver.query(uri, null, null, null, null)
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) {
                        result = cursor.getString(index)
                    }
                }
            } finally {
                cursor?.close()
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/')
            if (cut != null && cut != -1) {
                result = result?.substring(cut + 1)
            }
        }
        return result
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        setupSpinner()
        setupLutPicker()
        setupCheckboxes()

        binding.btnBack.setOnClickListener {
            Navigation.findNavController(requireActivity(), R.id.fragment_container).navigateUp()
        }
    }

    private fun setupSpinner() {
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, LOG_CURVES)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerTargetLog.adapter = adapter

        val savedLog = prefs.getString(KEY_TARGET_LOG, "None")
        val position = LOG_CURVES.indexOf(savedLog)
        if (position >= 0) {
            binding.spinnerTargetLog.setSelection(position)
        }

        binding.spinnerTargetLog.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                prefs.edit().putString(KEY_TARGET_LOG, LOG_CURVES[position]).apply()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupLutPicker() {
        val savedLut = prefs.getString(KEY_LUT_URI, null)
        updateLutLabel(savedLut)

        binding.btnManageLuts.setOnClickListener {
            Navigation.findNavController(requireActivity(), R.id.fragment_container)
                .navigate(SettingsFragmentDirections.actionSettingsToLutManagement())
        }
    }

    private fun updateLutLabel(uriString: String?) {
        if (uriString == null) {
            binding.tvSelectedLut.text = "No LUT selected"
        } else {
            // Check if it's a file path or Uri
            val file = java.io.File(uriString)
            if (file.exists()) {
                 binding.tvSelectedLut.text = file.nameWithoutExtension
            } else {
                 binding.tvSelectedLut.text = try {
                     Uri.parse(uriString).lastPathSegment ?: uriString
                } catch (e: Exception) {
                    uriString
                }
            }
        }
    }

    private fun setupCheckboxes() {
        binding.switchUseGpu.isChecked = prefs.getBoolean(KEY_USE_GPU, false)
        binding.switchUseGpu.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_USE_GPU, isChecked).apply()
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
        const val KEY_SAVE_TIFF = "save_tiff"
        const val KEY_SAVE_JPG = "save_jpg"
        const val KEY_USE_GPU = "use_gpu"
        const val KEY_MANUAL_CONTROLS = "enable_manual_controls"

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
