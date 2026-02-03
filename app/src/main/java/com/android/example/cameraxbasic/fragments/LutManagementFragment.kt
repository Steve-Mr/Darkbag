package com.android.example.cameraxbasic.fragments

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import com.android.example.cameraxbasic.databinding.FragmentLutManagementBinding
import com.android.example.cameraxbasic.utils.LutManager
import com.google.android.material.color.MaterialColors
import java.io.File

class LutManagementFragment : Fragment() {

    private var _binding: FragmentLutManagementBinding? = null
    private val binding get() = _binding!!

    private lateinit var lutManager: LutManager
    private lateinit var prefs: SharedPreferences
    private lateinit var adapter: LutManagementAdapter

    private val lutPicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isEmpty()) return@registerForActivityResult
        var imported = 0
        uris.forEach { if (lutManager.importLut(it)) imported++ }

        if (imported > 0) {
            Toast.makeText(context, "Imported $imported LUTs", Toast.LENGTH_SHORT).show()
            updateList()
        } else {
            Toast.makeText(context, "No valid LUTs imported", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLutManagementBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        lutManager = LutManager(requireContext())
        prefs = requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)

        // Apply Edge-to-Edge Insets
        ViewCompat.setOnApplyWindowInsetsListener(binding.appBar) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, systemBars.top, v.paddingRight, v.paddingBottom)
            insets
        }

        val initialBottom = binding.rvLutManagement.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.rvLutManagement) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, initialBottom + systemBars.bottom)
            insets
        }

        val initialFabMargin = resources.getDimensionPixelSize(R.dimen.margin_medium)
        ViewCompat.setOnApplyWindowInsetsListener(binding.fabImport) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val params = v.layoutParams as ViewGroup.MarginLayoutParams
            params.bottomMargin = initialFabMargin + systemBars.bottom
            params.rightMargin = initialFabMargin + systemBars.right
            v.layoutParams = params
            insets
        }

        binding.toolbar.setNavigationOnClickListener {
             Navigation.findNavController(requireActivity(), R.id.fragment_container).navigateUp()
        }

        binding.rvLutManagement.layoutManager = LinearLayoutManager(requireContext())
        adapter = LutManagementAdapter()
        binding.rvLutManagement.adapter = adapter

        binding.fabImport.setOnClickListener {
            lutPicker.launch(arrayOf("*/*"))
        }

        updateList()
    }

    private fun updateList() {
        val luts = lutManager.getLuts()
        val activeLut = prefs.getString(SettingsFragment.KEY_ACTIVE_LUT, null)
        adapter.submitList(luts, activeLut)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private inner class LutManagementAdapter : RecyclerView.Adapter<LutManagementAdapter.ViewHolder>() {
        private var luts: List<File> = emptyList()
        private var activeLut: String? = null

        fun submitList(list: List<File>, active: String?) {
            luts = list
            activeLut = active
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_lut, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val file = luts[position]
            val displayName = file.nameWithoutExtension
            val isActive = file.name == activeLut

            holder.tvName.text = displayName

            if (isActive) {
                val colorPrimary = MaterialColors.getColor(holder.itemView, com.google.android.material.R.attr.colorPrimary)
                holder.tvName.setTextColor(colorPrimary)
            } else {
                val colorOnSurface = MaterialColors.getColor(holder.itemView, com.google.android.material.R.attr.colorOnSurface)
                holder.tvName.setTextColor(colorOnSurface)
            }

            holder.itemView.setOnClickListener {
                if (isActive) {
                     prefs.edit().remove(SettingsFragment.KEY_ACTIVE_LUT).apply()
                } else {
                     prefs.edit().putString(SettingsFragment.KEY_ACTIVE_LUT, file.name).apply()
                }
                updateList()
            }

            holder.btnRename.setOnClickListener {
                showRenameDialog(file)
            }

            holder.btnDelete.setOnClickListener {
                showDeleteDialog(file)
            }
        }

        override fun getItemCount() = luts.size

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvName: TextView = view.findViewById(R.id.tv_lut_name)
            val btnRename: View = view.findViewById(R.id.btn_rename)
            val btnDelete: View = view.findViewById(R.id.btn_delete)
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
                    if (prefs.getString(SettingsFragment.KEY_ACTIVE_LUT, null) == file.name) {
                        prefs.edit().putString(SettingsFragment.KEY_ACTIVE_LUT, "$newName.cube").apply()
                    }
                    updateList()
                } else {
                    Toast.makeText(context, "Invalid name", Toast.LENGTH_SHORT).show()
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
                    if (prefs.getString(SettingsFragment.KEY_ACTIVE_LUT, null) == file.name) {
                        prefs.edit().remove(SettingsFragment.KEY_ACTIVE_LUT).apply()
                    }
                    updateList()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
