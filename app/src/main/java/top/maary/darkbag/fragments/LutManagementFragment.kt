package top.maary.darkbag.fragments

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.navigation.Navigation
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import top.maary.darkbag.R
import top.maary.darkbag.databinding.FragmentLutManagementBinding
import top.maary.darkbag.ui.BentoSelectionBottomSheet
import top.maary.darkbag.utils.LutManager
import java.io.File
import kotlin.math.roundToInt

class LutManagementFragment : Fragment() {

    private var _binding: FragmentLutManagementBinding? = null
    private val binding get() = _binding!!

    private lateinit var lutManager: LutManager
    private lateinit var prefs: SharedPreferences
    private lateinit var adapter: LutManagementAdapter

    private var pendingImportLog: String? = null

    private val supportedLogCurves by lazy {
        listOf(
            "F-Log2",
            "F-Log",
            "S-Log3",
            "S-Log3.Cine",
            "Arri LogC3",
            "Canon Log 2",
            "Canon Log 3",
            "V-Log",
            "N-Log",
            "D-Log",
            "Rec.709",
            getString(R.string.lut_log_universal)
        )
    }

    private val supportedLogDescriptions by lazy {
        listOf(
            getString(R.string.lut_desc_flog2),
            getString(R.string.lut_desc_flog),
            getString(R.string.lut_desc_slog3),
            getString(R.string.lut_desc_slog3cine),
            getString(R.string.lut_desc_arri),
            getString(R.string.lut_desc_clog2),
            getString(R.string.lut_desc_clog3),
            getString(R.string.lut_desc_vlog),
            getString(R.string.lut_desc_nlog),
            getString(R.string.lut_desc_dlog),
            getString(R.string.lut_desc_rec709),
            getString(R.string.lut_log_universal_desc)
        )
    }

    private val lutPicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isEmpty()) return@registerForActivityResult
        var imported = 0
        val targetLogToBind = pendingImportLog
        uris.forEach {
            if (lutManager.importLut(it, targetLogToBind) != null) imported++
        }

        if (imported > 0) {
            val isUniversal = targetLogToBind == null ||
                              targetLogToBind == LutManager.LOG_UNIVERSAL ||
                              targetLogToBind == getString(R.string.lut_log_universal)
            val msg = if (isUniversal) {
                getString(R.string.lut_import_success_universal, imported)
            } else {
                getString(R.string.lut_import_success_paired, imported, targetLogToBind)
            }
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            updateList()
        } else {
            Toast.makeText(context, getString(R.string.lut_import_no_valid), Toast.LENGTH_SHORT).show()
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
            showImportLogSelectionSheet()
        }

        updateList()
    }

    private fun showImportLogSelectionSheet() {
        BentoSelectionBottomSheet.show(
            fragmentManager = childFragmentManager,
            title = getString(R.string.lut_import_select_log_title),
            options = supportedLogCurves,
            selectedIndex = 0,
            badge = "LOG PAIR",
            descriptions = supportedLogDescriptions
        ) { which ->
            val chosen = supportedLogCurves[which]
            pendingImportLog = if (chosen == getString(R.string.lut_log_universal)) {
                LutManager.LOG_UNIVERSAL
            } else {
                chosen
            }
            lutPicker.launch(arrayOf("*/*"))
        }
    }

    private fun showChangeLogSheet(file: File) {
        val currentAssoc = lutManager.getAssociatedLog(file.name)
        val selectedIndex = when {
            currentAssoc.isNullOrEmpty() || currentAssoc == LutManager.LOG_UNIVERSAL -> supportedLogCurves.size - 1
            else -> supportedLogCurves.indexOf(currentAssoc).takeIf { it >= 0 } ?: (supportedLogCurves.size - 1)
        }

        BentoSelectionBottomSheet.show(
            fragmentManager = childFragmentManager,
            title = getString(R.string.lut_assoc_change_title),
            options = supportedLogCurves,
            selectedIndex = selectedIndex,
            badge = "ASSOCIATION",
            descriptions = supportedLogDescriptions
        ) { which ->
            val chosen = supportedLogCurves[which]
            val newLogToSet = if (chosen == getString(R.string.lut_log_universal)) {
                LutManager.LOG_UNIVERSAL
            } else {
                chosen
            }
            lutManager.setAssociatedLog(file.name, newLogToSet)

            // If currently active, sync target log safely using toTargetLogPreference
            val activeLut = prefs.getString(SettingsFragment.KEY_ACTIVE_LUT, null)
            if (file.name == activeLut && newLogToSet != LutManager.LOG_UNIVERSAL) {
                val targetPrefLog = lutManager.toTargetLogPreference(newLogToSet)
                prefs.edit().putString(SettingsFragment.KEY_TARGET_LOG, targetPrefLog).apply()
            }
            updateList()
        }
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
            val oldLuts = luts
            val oldActiveLut = activeLut

            luts = list
            activeLut = active

            val diffResult = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize(): Int = oldLuts.size
                override fun getNewListSize(): Int = list.size

                override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                    return oldLuts[oldItemPosition].absolutePath == list[newItemPosition].absolutePath
                }

                override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                    val oldFile = oldLuts[oldItemPosition]
                    val newFile = list[newItemPosition]
                    val oldLog = lutManager.getAssociatedLog(oldFile.name)
                    val newLog = lutManager.getAssociatedLog(newFile.name)
                    return oldFile.name == newFile.name &&
                           (oldFile.name == oldActiveLut) == (newFile.name == active) &&
                           oldLog == newLog
                }
            })

            diffResult.dispatchUpdatesTo(this)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_lut, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val file = luts[position]
            val displayName = file.nameWithoutExtension
            val isActive = file.name == activeLut
            val assocLog = lutManager.getAssociatedLog(file.name)
            val density = holder.itemView.resources.displayMetrics.density

            holder.tvName.text = displayName

            // Log association badge styling
            val badgeDrawable = GradientDrawable().apply {
                cornerRadius = 6f * density
                shape = GradientDrawable.RECTANGLE
            }

            when {
                assocLog.isNullOrEmpty() -> {
                    holder.tvLog.text = getString(R.string.lut_badge_unassigned)
                    badgeDrawable.setColor(MaterialColors.getColor(holder.itemView, com.google.android.material.R.attr.colorSurfaceContainerHighest))
                    holder.tvLog.setTextColor(MaterialColors.getColor(holder.itemView, com.google.android.material.R.attr.colorOnSurfaceVariant))
                }
                assocLog == LutManager.LOG_UNIVERSAL -> {
                    holder.tvLog.text = getString(R.string.lut_badge_universal)
                    badgeDrawable.setColor(MaterialColors.getColor(holder.itemView, com.google.android.material.R.attr.colorSecondaryContainer))
                    holder.tvLog.setTextColor(MaterialColors.getColor(holder.itemView, com.google.android.material.R.attr.colorOnSecondaryContainer))
                }
                else -> {
                    holder.tvLog.text = assocLog
                    badgeDrawable.setColor(MaterialColors.getColor(holder.itemView, com.google.android.material.R.attr.colorPrimaryContainer))
                    holder.tvLog.setTextColor(MaterialColors.getColor(holder.itemView, com.google.android.material.R.attr.colorOnPrimaryContainer))
                }
            }
            holder.tvLog.background = badgeDrawable

            if (isActive) {
                val colorPrimary = MaterialColors.getColor(holder.itemView, android.R.attr.colorPrimary)
                holder.tvName.setTextColor(colorPrimary)
            } else {
                val colorOnSurface = MaterialColors.getColor(holder.itemView, com.google.android.material.R.attr.colorOnSurface)
                holder.tvName.setTextColor(colorOnSurface)
            }

            holder.itemView.setOnClickListener {
                if (isActive) {
                    prefs.edit().remove(SettingsFragment.KEY_ACTIVE_LUT).apply()
                } else {
                    val editor = prefs.edit().putString(SettingsFragment.KEY_ACTIVE_LUT, file.name)
                    if (!assocLog.isNullOrEmpty() && assocLog != LutManager.LOG_UNIVERSAL) {
                        val targetPrefLog = lutManager.toTargetLogPreference(assocLog)
                        editor.putString(SettingsFragment.KEY_TARGET_LOG, targetPrefLog)
                    }
                    editor.apply()
                }
                updateList()
            }

            holder.tvLog.setOnClickListener {
                showChangeLogSheet(file)
            }

            holder.btnChangeLog.setOnClickListener {
                showChangeLogSheet(file)
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
            val tvLog: TextView = view.findViewById(R.id.tv_lut_log)
            val btnChangeLog: View = view.findViewById(R.id.btn_change_log)
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
