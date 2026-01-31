package com.android.example.cameraxbasic.fragments

import androidx.appcompat.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.Navigation
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.android.example.cameraxbasic.R
import com.android.example.cameraxbasic.databinding.FragmentSettingsBinding
import com.android.example.cameraxbasic.utils.LutManager
import java.io.File

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private lateinit var prefs: SharedPreferences
    private lateinit var lutManager: LutManager
    private lateinit var lutAdapter: LutAdapter

    private val lutPicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isEmpty()) return@registerForActivityResult
        var imported = 0
        uris.forEach { if (lutManager.importLut(it)) imported++ }

        if (imported > 0) {
            Toast.makeText(context, "Imported $imported LUTs", Toast.LENGTH_SHORT).show()
            updateLutList()
        } else {
            Toast.makeText(context, "No valid LUTs imported", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        lutManager = LutManager(requireContext())

        setupSpinner()
        setupLutList()
        setupCheckboxes()

        binding.btnBack.setOnClickListener {
            Navigation.findNavController(requireActivity(), R.id.fragment_container).navigateUp()
        }
    }

    private fun setupLutList() {
        binding.rvLuts.layoutManager = LinearLayoutManager(context)
        lutAdapter = LutAdapter()
        binding.rvLuts.adapter = lutAdapter
        updateLutList()

        binding.btnImportLut.setOnClickListener {
            lutPicker.launch(arrayOf("*/*"))
        }

        binding.switchLivePreview.visibility = View.GONE
    }

    private fun updateLutList() {
        val luts = lutManager.getLuts()
        val activeLut = prefs.getString(KEY_ACTIVE_LUT, null)
        lutAdapter.submitList(luts, activeLut)
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

    private inner class LutAdapter : RecyclerView.Adapter<LutAdapter.LutViewHolder>() {
        private var luts: List<File> = emptyList()
        private var activeLutName: String? = null

        fun submitList(newList: List<File>, active: String?) {
            luts = newList
            activeLutName = active
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LutViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_lut, parent, false)
            return LutViewHolder(view)
        }

        override fun onBindViewHolder(holder: LutViewHolder, position: Int) {
            val file = luts[position]
            val displayName = file.nameWithoutExtension

            holder.tvName.text = displayName

            val isActive = file.name == activeLutName
            if (isActive) {
                // Use a theme color or a standard material color
                val typedValue = android.util.TypedValue()
                val theme = holder.itemView.context.theme
                theme.resolveAttribute(android.R.attr.colorPrimary, typedValue, true)
                holder.tvName.setTextColor(typedValue.data)
            } else {
                holder.tvName.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.white))
            }

            holder.itemView.setOnClickListener {
                if (isActive) {
                    // Deselect
                    prefs.edit().remove(KEY_ACTIVE_LUT).apply()
                } else {
                    prefs.edit().putString(KEY_ACTIVE_LUT, file.name).apply()
                }
                updateLutList()
            }

            holder.btnRename.setOnClickListener {
                showRenameDialog(file)
            }

            holder.btnDelete.setOnClickListener {
                showDeleteDialog(file)
            }
        }

        override fun getItemCount() = luts.size

        inner class LutViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val tvName: TextView = itemView.findViewById(R.id.tv_lut_name)
            val btnRename: Button = itemView.findViewById(R.id.btn_rename)
            val btnDelete: Button = itemView.findViewById(R.id.btn_delete)
        }
    }

    private fun showRenameDialog(file: File) {
        val input = EditText(context)
        input.setText(file.nameWithoutExtension)
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.lut_rename_title)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newName = input.text.toString()
                if (lutManager.renameLut(file, newName)) {
                    // If active, update pref
                    if (prefs.getString(KEY_ACTIVE_LUT, null) == file.name) {
                        prefs.edit().putString(KEY_ACTIVE_LUT, "$newName.cube").apply()
                    }
                    updateLutList()
                } else {
                    Toast.makeText(context, "Invalid name or file exists", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showDeleteDialog(file: File) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.lut_delete_title)
            .setMessage(getString(R.string.lut_delete_message, file.nameWithoutExtension))
            .setPositiveButton(R.string.btn_delete) { _, _ ->
                if (lutManager.deleteLut(file)) {
                    if (prefs.getString(KEY_ACTIVE_LUT, null) == file.name) {
                        prefs.edit().remove(KEY_ACTIVE_LUT).apply()
                    }
                    updateLutList()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    companion object {
        const val PREFS_NAME = "camera_settings"
        const val KEY_TARGET_LOG = "target_log"
        const val KEY_LUT_URI = "lut_uri" // Legacy, keeping to avoid errors if referenced elsewhere
        const val KEY_ACTIVE_LUT = "active_lut_filename" // New key for filename
        const val KEY_SAVE_TIFF = "save_tiff"
        const val KEY_SAVE_JPG = "save_jpg"
        const val KEY_USE_GPU = "use_gpu"
        const val KEY_MANUAL_CONTROLS = "enable_manual_controls"
        const val KEY_ENABLE_LUT_PREVIEW = "enable_lut_preview"

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
