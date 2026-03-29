package top.maary.darkbag.fragments

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.bumptech.glide.Glide
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.*
import top.maary.darkbag.R
import top.maary.darkbag.databinding.FragmentStudioBinding
import top.maary.darkbag.databinding.ItemStudioImageBinding
import top.maary.darkbag.models.ImageGroup
import top.maary.darkbag.repository.ImageRepository

class StudioFragment : Fragment() {

    private var _binding: FragmentStudioBinding? = null
    private val binding get() = _binding!!
    private lateinit var repository: ImageRepository
    private lateinit var studioAdapter: StudioAdapter
    private val selectedItems = mutableListOf<ImageGroup>()
    private var isSelectionMode = false

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        if (uris.size > 2) {
            Toast.makeText(requireContext(), "You can only select up to 2 files", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        handleImportedUris(uris)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentStudioBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        repository = ImageRepository(requireContext())
        setupEdgeToEdge()
        setupRecyclerView()
        setupButtons()
    }

    override fun onResume() {
        super.onResume()
        clearSelection()
        loadImages()
    }

    private fun setupEdgeToEdge() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.appBar) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, systemBars.top, 0, 0)
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.rvStudio) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val navView = requireActivity().findViewById<View>(R.id.nav_view)
            val navHeight = if (navView?.visibility == View.VISIBLE) navView.height else 0

            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, systemBars.bottom + navHeight + resources.getDimensionPixelSize(R.dimen.margin_xlarge))
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.cvStudioActions) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val navView = requireActivity().findViewById<View>(R.id.nav_view)
            val navHeight = if (navView?.visibility == View.VISIBLE) navView.height else 0

            v.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = systemBars.bottom + navHeight + resources.getDimensionPixelSize(R.dimen.margin_large)
            }
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.fabImport) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val navView = requireActivity().findViewById<View>(R.id.nav_view)
            val navHeight = if (navView?.visibility == View.VISIBLE) navView.height else 0

            v.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = systemBars.bottom + navHeight + resources.getDimensionPixelSize(R.dimen.margin_large)
                rightMargin = systemBars.right + resources.getDimensionPixelSize(R.dimen.margin_medium)
            }
            insets
        }
    }

    private fun setupRecyclerView() {
        binding.rvStudio.layoutManager = StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL).apply {
            // Prevent aggressive item moves that cause visible waterfall "jumping".
            gapStrategy = StaggeredGridLayoutManager.GAP_HANDLING_NONE
        }
        binding.rvStudio.setHasFixedSize(true)
        binding.rvStudio.itemAnimator = null
        studioAdapter = StudioAdapter()
        binding.rvStudio.adapter = studioAdapter
    }

    private fun loadImages() {
        binding.loadingIndicator.visibility = View.VISIBLE
        lifecycleScope.launch {
            val groups = repository.getStudioGroups(forceRefresh = true)
            studioAdapter.submitGroups(groups)
            binding.loadingIndicator.visibility = View.GONE

            val isEmpty = groups.isEmpty()
            binding.emptyState.visibility = if (isEmpty) View.VISIBLE else View.GONE
            binding.fabImport.visibility = if (isEmpty) View.GONE else View.VISIBLE

            binding.root.requestApplyInsets()
        }
    }

    private fun setupButtons() {
        binding.btnImportEmpty.setOnClickListener { importLauncher.launch(arrayOf("image/*", "application/octet-stream")) }
        binding.fabImport.setOnClickListener { importLauncher.launch(arrayOf("image/*", "application/octet-stream")) }

        binding.btnStitch?.setOnClickListener {
            if (selectedItems.size == 2) {
                showStitchModeSelection(selectedItems[0].dngUri!!, selectedItems[1].dngUri!!)
            }
        }

        binding.btnDelete.setOnClickListener {
            if (selectedItems.isEmpty()) return@setOnClickListener
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Delete Images")
                .setMessage("Are you sure you want to remove these ${selectedItems.size} items from Studio? (Original files will not be deleted from your phone)")
                .setPositiveButton("Delete") { _, _ ->
                    selectedItems.forEach { repository.deleteStudioGroup(it) }
                    clearSelection()
                    loadImages()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun handleImportedUris(uris: List<Uri>) {
        lifecycleScope.launch {
            binding.loadingIndicator.visibility = View.VISIBLE
            val importedUris = mutableListOf<Uri>()

            withContext(Dispatchers.IO) {
                uris.forEachIndexed { index, uri ->
                    val baseName = "STUDIO_${System.currentTimeMillis()}_$index"
                    repository.importToStudio(uri, baseName)?.let { importedUris.add(it) }
                }
            }

            binding.loadingIndicator.visibility = View.GONE
            if (importedUris.size == uris.size) {
                if (uris.size == 1) {
                    navigateToViewer(importedUris[0].toString())
                } else if (uris.size == 2) {
                    showStitchOrIndependentOption(importedUris[0], importedUris[1])
                } else {
                    Toast.makeText(requireContext(), "Imported ${uris.size} images", Toast.LENGTH_SHORT).show()
                    loadImages()
                }
            } else {
                Toast.makeText(requireContext(), "Failed to import some files", Toast.LENGTH_SHORT).show()
                loadImages()
            }
        }
    }

    private fun showStitchOrIndependentOption(u1: Uri, u2: Uri) {
        val options = arrayOf("Stitch and Edit", "Import Independently")
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Import Options")
            .setItems(options) { _, which ->
                if (which == 0) {
                    showStitchModeSelection(u1, u2)
                } else {
                    loadImages()
                }
            }
            .setCancelable(false)
            .show()
    }

    private fun showStitchModeSelection(u1: Uri, u2: Uri) {
        val bottomSheet = StitchLayoutBottomSheet.newInstance()
        bottomSheet.onLayoutSelected = { layout ->
            navigateToViewer("$u1|$u2|$layout")
        }
        bottomSheet.show(childFragmentManager, StitchLayoutBottomSheet.TAG)
    }

    private fun navigateToViewer(uri: String) {
        val action = StudioFragmentDirections.actionStudioToImageViewer(
            initialUri = uri,
            isStudioMode = true
        )
        findNavController().navigate(action)
    }

    private fun clearSelection() {
        selectedItems.clear()
        isSelectionMode = false
        binding.cvStudioActions.visibility = View.GONE
        updateStitchVisibility()
        if (::studioAdapter.isInitialized) studioAdapter.notifyDataSetChanged()
    }

    private fun toggleSelection(group: ImageGroup) {
        if (selectedItems.contains(group)) {
            selectedItems.remove(group)
        } else {
            if (selectedItems.size < 2) {
                selectedItems.add(group)
            }
        }

        isSelectionMode = selectedItems.isNotEmpty()
        binding.cvStudioActions.visibility = if (isSelectionMode) View.VISIBLE else View.GONE
        updateStitchVisibility()
        if (::studioAdapter.isInitialized) studioAdapter.notifyDataSetChanged()
    }

    private fun updateStitchVisibility() {
        val canStitch = selectedItems.size == 2 && selectedItems.all { it.dngUri != null && !it.isHalfFrame() }
        binding.btnStitch?.visibility = if (canStitch) View.VISIBLE else View.GONE
    }

    inner class StudioAdapter :
        RecyclerView.Adapter<StudioAdapter.ViewHolder>() {
        private val groups = mutableListOf<ImageGroup>()

        init {
            setHasStableIds(true)
        }

        fun submitGroups(newGroups: List<ImageGroup>) {
            groups.clear()
            groups.addAll(newGroups)
            notifyDataSetChanged()
        }

        inner class ViewHolder(val binding: ItemStudioImageBinding) :
            RecyclerView.ViewHolder(binding.root) {
            var loadJob: Job? = null
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemStudioImageBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.loadJob?.cancel()
            val group = groups[position]

            // Stability: Set Dimension Ratio if metadata exists
            if (group.width > 0 && group.height > 0) {
                holder.binding.ivThumbnail.updateLayoutParams<androidx.constraintlayout.widget.ConstraintLayout.LayoutParams> {
                    dimensionRatio = "${group.width}:${group.height}"
                }
            } else {
                holder.binding.ivThumbnail.updateLayoutParams<androidx.constraintlayout.widget.ConstraintLayout.LayoutParams> {
                    dimensionRatio = "1:1"
                }
            }

            // Thumbnail loading
            holder.binding.ivThumbnail.setImageDrawable(null)
            holder.loadJob = lifecycleScope.launch {
                val thumb = withContext(Dispatchers.IO) {
                    val uri = group.jpgUri ?: group.dngUri ?: group.dngUri1
                    if (uri != null) {
                        if (uri.toString().endsWith(".dng", ignoreCase = true) || uri.scheme == "file") {
                             top.maary.darkbag.utils.ImageUtils.decodeDngThumbnail(requireContext(), uri, 1.0f)
                        } else null
                    } else null
                }

                if (thumb != null) {
                    if (holder.bindingAdapterPosition != position) return@launch
                    holder.binding.ivThumbnail.setImageBitmap(thumb)
                } else {
                    if (holder.bindingAdapterPosition != position) return@launch
                    Glide.with(holder.binding.ivThumbnail)
                        .load(group.jpgUri ?: group.dngUri ?: group.dngUri1)
                        .placeholder(R.drawable.ic_photo)
                        .error(R.drawable.ic_close)
                        .override(512, 512)
                        .into(holder.binding.ivThumbnail)
                }
            }

            holder.binding.ivHalfFrameIndicator.visibility = if (group.isHalfFrame()) View.VISIBLE else View.GONE

            val isSelected = selectedItems.contains(group)
            holder.binding.vSelectionOverlay.visibility = if (isSelected) View.VISIBLE else View.GONE
            holder.binding.tvSelectionIndex.visibility = if (isSelected) View.VISIBLE else View.GONE
            if (isSelected) {
                holder.binding.tvSelectionIndex.text = (selectedItems.indexOf(group) + 1).toString()
            }

            holder.itemView.setOnClickListener {
                if (isSelectionMode) {
                    toggleSelection(group)
                } else {
                    navigateToViewer((group.dngUri ?: group.dngUri1 ?: group.jpgUri).toString())
                }
            }

            holder.itemView.setOnLongClickListener {
                if (!isSelectionMode) {
                    toggleSelection(group)
                    true
                } else false
            }
        }

        override fun getItemCount() = groups.size

        override fun getItemId(position: Int): Long {
            return groups[position].baseName.hashCode().toLong()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
