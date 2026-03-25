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
        binding.rvStudio.layoutManager = StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL)
    }

    private fun loadImages() {
        binding.loadingIndicator.visibility = View.VISIBLE
        lifecycleScope.launch {
            val groups = repository.getStudioGroups(forceRefresh = true)
            binding.rvStudio.adapter = StudioAdapter(groups)
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
            val baseName = "STUDIO_${System.currentTimeMillis()}"

            withContext(Dispatchers.IO) {
                if (uris.size == 1) {
                    repository.importToStudio(uris[0], baseName)?.let { importedUris.add(it) }
                } else {
                    repository.importToStudio(uris[0], baseName, "_HF1")?.let { importedUris.add(it) }
                    repository.importToStudio(uris[1], baseName, "_HF2")?.let { importedUris.add(it) }
                }
            }

            binding.loadingIndicator.visibility = View.GONE
            if (importedUris.size == uris.size) {
                if (uris.size == 1) {
                    navigateToViewer(importedUris[0].toString())
                } else {
                    showStitchModeSelection(importedUris[0], importedUris[1])
                }
            } else {
                Toast.makeText(requireContext(), "Failed to import some files", Toast.LENGTH_SHORT).show()
                loadImages()
            }
        }
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
        binding.rvStudio.adapter?.notifyDataSetChanged()
    }

    private fun toggleSelection(group: ImageGroup) {
        if (selectedItems.contains(group)) {
            selectedItems.remove(group)
        } else {
            selectedItems.add(group)
        }

        isSelectionMode = selectedItems.isNotEmpty()
        binding.cvStudioActions.visibility = if (isSelectionMode) View.VISIBLE else View.GONE
        binding.rvStudio.adapter?.notifyDataSetChanged()
    }

    inner class StudioAdapter(private val groups: List<ImageGroup>) :
        RecyclerView.Adapter<StudioAdapter.ViewHolder>() {

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
                    holder.binding.ivThumbnail.setImageBitmap(thumb)
                } else {
                    Glide.with(holder.binding.ivThumbnail)
                        .load(group.jpgUri ?: group.dngUri ?: group.dngUri1)
                        .placeholder(R.drawable.ic_photo)
                        .error(R.drawable.ic_close)
                        .into(holder.binding.ivThumbnail)
                }
            }

            holder.binding.tvName.text = group.baseName
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
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
