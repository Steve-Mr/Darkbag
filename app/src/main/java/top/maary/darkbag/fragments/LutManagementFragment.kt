package top.maary.darkbag.fragments

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
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
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
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
    private var selectedCategoryFilter: String = FILTER_ALL

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

        applyEdgeToEdgeInsets()
        setupHeaderActions()
        setupCategoryFilters()

        binding.rvLutManagement.layoutManager = LinearLayoutManager(requireContext())
        adapter = LutManagementAdapter()
        binding.rvLutManagement.adapter = adapter

        updateList()
    }

    private fun applyEdgeToEdgeInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.cardLutHeader) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val lp = v.layoutParams as? ViewGroup.MarginLayoutParams
            val defaultMarginTop = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 8f, resources.displayMetrics
            ).toInt()
            lp?.topMargin = defaultMarginTop + systemBars.top
            v.layoutParams = lp
            insets
        }

        val initialBottom = binding.rvLutManagement.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.rvLutManagement) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, initialBottom + systemBars.bottom)
            insets
        }
    }

    private fun setupHeaderActions() {
        binding.btnBack.setOnClickListener {
            Navigation.findNavController(requireActivity(), R.id.fragment_container).navigateUp()
        }

        val importAction = { showImportLogSelectionSheet() }
        binding.btnHeaderImport.setOnClickListener { importAction() }
        binding.btnTileImport.setOnClickListener { importAction() }
        binding.btnEmptyImport.setOnClickListener { importAction() }
    }

    private fun setupCategoryFilters() {
        val filterOptions = listOf(
            FILTER_ALL to getString(R.string.lut_mgmt_filter_all),
            "F-Log2" to "F-Log2",
            "F-Log" to "F-Log",
            "S-Log3" to "S-Log3",
            "Arri LogC3" to "Arri LogC3",
            "Canon Log 2" to "Canon Log 2",
            "Rec.709" to "Rec.709",
            LutManager.LOG_UNIVERSAL to getString(R.string.lut_badge_universal),
            FILTER_UNASSIGNED to getString(R.string.lut_badge_unassigned)
        )

        binding.layoutFilterContainer.removeAllViews()
        val density = resources.displayMetrics.density

        filterOptions.forEach { (filterKey, label) ->
            val btn = MaterialButton(
                requireContext(),
                null,
                com.google.android.material.R.attr.materialButtonStyle
            ).apply {
                val margin = (4 * density).roundToInt()
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    (34 * density).roundToInt()
                ).apply {
                    setMargins(margin, 0, margin, 0)
                }
                text = label
                tag = filterKey
                isAllCaps = false
                textSize = 12f
                insetTop = 0
                insetBottom = 0
                setPadding((12 * density).roundToInt(), 0, (12 * density).roundToInt(), 0)

                setOnClickListener {
                    selectedCategoryFilter = filterKey
                    updateFilterButtonStates()
                    updateList()
                }
            }
            binding.layoutFilterContainer.addView(btn)
        }
        updateFilterButtonStates()
    }

    private fun updateFilterButtonStates() {
        val density = resources.displayMetrics.density
        val colorPrimaryContainer = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorPrimaryContainer)
        val colorOnPrimaryContainer = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnPrimaryContainer)
        val colorSurfaceContainerHigh = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorSurfaceContainerHigh)
        val colorOnSurfaceVariant = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnSurfaceVariant)

        for (i in 0 until binding.layoutFilterContainer.childCount) {
            val btn = binding.layoutFilterContainer.getChildAt(i) as? MaterialButton ?: continue
            val filterKey = btn.tag as? String ?: continue
            val isSelected = filterKey == selectedCategoryFilter
            if (isSelected) {
                btn.backgroundTintList = ColorStateList.valueOf(colorPrimaryContainer)
                btn.setTextColor(colorOnPrimaryContainer)
                btn.cornerRadius = (10 * density).roundToInt()
            } else {
                btn.backgroundTintList = ColorStateList.valueOf(colorSurfaceContainerHigh)
                btn.setTextColor(colorOnSurfaceVariant)
                btn.cornerRadius = (17 * density).roundToInt()
            }
        }
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
        val allItems = lutManager.getAllLutItems()
        val activeLut = prefs.getString(SettingsFragment.KEY_ACTIVE_LUT, null)

        // 1. Update Bento Telemetry Tile
        val pairedCount = allItems.count { !it.isUniversal && !it.associatedLog.isNullOrEmpty() }
        binding.btnTileStats.text = "${getString(R.string.lut_mgmt_stats_label)}\n${getString(R.string.lut_mgmt_stats_format, allItems.size, pairedCount)}"

        // 2. Filter dataset according to selectedCategoryFilter
        val filteredList = when (selectedCategoryFilter) {
            FILTER_ALL -> allItems
            FILTER_UNASSIGNED -> allItems.filter { it.associatedLog.isNullOrEmpty() }
            LutManager.LOG_UNIVERSAL -> allItems.filter { it.isUniversal }
            else -> allItems.filter { it.associatedLog.equals(selectedCategoryFilter, ignoreCase = true) }
        }

        // 3. Toggle Empty State vs List
        if (filteredList.isEmpty()) {
            binding.cardEmptyState.visibility = View.VISIBLE
            binding.rvLutManagement.visibility = View.GONE
        } else {
            binding.cardEmptyState.visibility = View.GONE
            binding.rvLutManagement.visibility = View.VISIBLE
        }

        // 4. Submit immutable LutItem snapshot
        adapter.submitList(filteredList, activeLut)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private inner class LutManagementAdapter : RecyclerView.Adapter<LutManagementAdapter.ViewHolder>() {
        private var items: List<LutManager.LutItem> = emptyList()
        private var activeLut: String? = null

        fun submitList(list: List<LutManager.LutItem>, active: String?) {
            val oldItems = items
            val oldActiveLut = activeLut

            items = list
            activeLut = active

            val diffResult = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize(): Int = oldItems.size
                override fun getNewListSize(): Int = list.size

                override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                    return oldItems[oldItemPosition].file.absolutePath == list[newItemPosition].file.absolutePath
                }

                override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                    val oldItem = oldItems[oldItemPosition]
                    val newItem = list[newItemPosition]
                    val isOldActive = oldItem.name == oldActiveLut
                    val isNewActive = newItem.name == active
                    return oldItem.name == newItem.name &&
                           oldItem.associatedLog == newItem.associatedLog &&
                           isOldActive == isNewActive
                }
            })

            diffResult.dispatchUpdatesTo(this)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_lut, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            val displayName = item.file.nameWithoutExtension
            val isActive = item.name == activeLut
            val assocLog = item.associatedLog
            val density = holder.itemView.resources.displayMetrics.density

            holder.tvName.text = displayName

            // 1. Concentric Bento Card Squircle Morphing & Active Styling
            if (isActive) {
                holder.card.radius = 10f * density
                holder.card.strokeWidth = (1.5f * density).roundToInt()
                holder.card.strokeColor = MaterialColors.getColor(holder.itemView, android.R.attr.colorPrimary)
                holder.card.setCardBackgroundColor(MaterialColors.getColor(holder.itemView, com.google.android.material.R.attr.colorSecondaryContainer))
                holder.tvActiveBadge.visibility = View.VISIBLE
                holder.tvName.setTextColor(MaterialColors.getColor(holder.itemView, com.google.android.material.R.attr.colorOnSecondaryContainer))

                holder.iconContainer.background = GradientDrawable().apply {
                    cornerRadius = 8f * density
                    setColor(MaterialColors.getColor(holder.itemView, com.google.android.material.R.attr.colorPrimaryContainer))
                }
                holder.ivIcon.imageTintList = ColorStateList.valueOf(
                    MaterialColors.getColor(holder.itemView, com.google.android.material.R.attr.colorOnPrimaryContainer)
                )
            } else {
                holder.card.radius = 14f * density
                holder.card.strokeWidth = 0
                holder.card.strokeColor = Color.TRANSPARENT
                holder.card.setCardBackgroundColor(MaterialColors.getColor(holder.itemView, com.google.android.material.R.attr.colorSurface))
                holder.tvActiveBadge.visibility = View.GONE
                holder.tvName.setTextColor(MaterialColors.getColor(holder.itemView, com.google.android.material.R.attr.colorOnSurface))

                holder.iconContainer.background = GradientDrawable().apply {
                    cornerRadius = 8f * density
                    setColor(MaterialColors.getColor(holder.itemView, com.google.android.material.R.attr.colorSurfaceContainerHigh))
                }
                holder.ivIcon.imageTintList = ColorStateList.valueOf(
                    MaterialColors.getColor(holder.itemView, com.google.android.material.R.attr.colorOnSurfaceVariant)
                )
            }

            // 2. Interactive Bento Log Pill Styling
            val pillDrawable = GradientDrawable().apply {
                cornerRadius = 7f * density
                shape = GradientDrawable.RECTANGLE
            }

            when {
                assocLog.isNullOrEmpty() -> {
                    holder.tvLog.text = "${getString(R.string.lut_badge_unassigned)} ▾"
                    pillDrawable.setColor(MaterialColors.getColor(holder.itemView, com.google.android.material.R.attr.colorErrorContainer))
                    holder.tvLog.setTextColor(MaterialColors.getColor(holder.itemView, com.google.android.material.R.attr.colorOnErrorContainer))
                }
                assocLog == LutManager.LOG_UNIVERSAL -> {
                    holder.tvLog.text = "${getString(R.string.lut_badge_universal)} ▾"
                    pillDrawable.setColor(MaterialColors.getColor(holder.itemView, com.google.android.material.R.attr.colorSecondaryContainer))
                    holder.tvLog.setTextColor(MaterialColors.getColor(holder.itemView, com.google.android.material.R.attr.colorOnSecondaryContainer))
                }
                else -> {
                    holder.tvLog.text = "$assocLog ▾"
                    pillDrawable.setColor(MaterialColors.getColor(holder.itemView, com.google.android.material.R.attr.colorPrimaryContainer))
                    holder.tvLog.setTextColor(MaterialColors.getColor(holder.itemView, com.google.android.material.R.attr.colorOnPrimaryContainer))
                }
            }
            holder.tvLog.background = pillDrawable

            // 3. Click Listeners
            holder.card.setOnClickListener {
                if (isActive) {
                    prefs.edit().remove(SettingsFragment.KEY_ACTIVE_LUT).apply()
                } else {
                    val editor = prefs.edit().putString(SettingsFragment.KEY_ACTIVE_LUT, item.name)
                    if (!assocLog.isNullOrEmpty() && assocLog != LutManager.LOG_UNIVERSAL) {
                        val targetPrefLog = lutManager.toTargetLogPreference(assocLog)
                        editor.putString(SettingsFragment.KEY_TARGET_LOG, targetPrefLog)
                    }
                    editor.apply()
                }
                updateList()
            }

            holder.tvLog.setOnClickListener {
                showChangeLogSheet(item.file)
            }

            holder.btnRename.setOnClickListener {
                showRenameDialog(item.file)
            }

            holder.btnDelete.setOnClickListener {
                showDeleteDialog(item.file)
            }
        }

        override fun getItemCount(): Int = items.size

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val card: MaterialCardView = view.findViewById(R.id.card_lut_item)
            val iconContainer: FrameLayout = view.findViewById(R.id.layout_recipe_icon_container)
            val ivIcon: ImageView = view.findViewById(R.id.iv_recipe_icon)
            val tvName: TextView = view.findViewById(R.id.tv_lut_name)
            val tvLog: TextView = view.findViewById(R.id.tv_lut_log)
            val tvActiveBadge: TextView = view.findViewById(R.id.tv_active_badge)
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

    companion object {
        private const val FILTER_ALL = "FILTER_ALL"
        private const val FILTER_UNASSIGNED = "FILTER_UNASSIGNED"
    }
}
