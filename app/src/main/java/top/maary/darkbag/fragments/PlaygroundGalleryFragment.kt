package top.maary.darkbag.fragments

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.MarginLayoutParams
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.bumptech.glide.Glide
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.maary.darkbag.R
import top.maary.darkbag.databinding.FragmentPlaygroundGalleryBinding
import top.maary.darkbag.databinding.ItemPlaygroundImageBinding
import top.maary.darkbag.models.PlaygroundItem
import top.maary.darkbag.ui.bento.BentoContainerRole
import top.maary.darkbag.ui.bento.BentoTrayCard
import top.maary.darkbag.viewmodel.PlaygroundEffect
import top.maary.darkbag.viewmodel.PlaygroundGalleryUiState
import top.maary.darkbag.viewmodel.PlaygroundGalleryViewModel
import top.maary.darkbag.viewmodel.PlaygroundUserIntent
import java.io.File

class PlaygroundGalleryFragment : Fragment() {

    private var _binding: FragmentPlaygroundGalleryBinding? = null
    private val binding get() = _binding!!

    private val viewModel: PlaygroundGalleryViewModel by viewModels()
    private lateinit var adapter: PlaygroundAdapter

    private var prevSelectedFiles: Set<File> = emptySet()
    private var prevSelectionMode: Boolean = false

    private val importDngLauncher = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.processIntent(PlaygroundUserIntent.ImportDngs(uris, requireContext().contentResolver))
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPlaygroundGalleryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val dockClearancePx = (88 * resources.displayMetrics.density).toInt()
        val headerClearancePx = (68 * resources.displayMetrics.density).toInt()
        binding.appBarLayout.translationZ = 16f * resources.displayMetrics.density
        binding.recyclerView.updatePadding(
            top = headerClearancePx,
            bottom = dockClearancePx
        )
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.appBarLayout.updatePadding(top = systemBars.top)
            binding.recyclerView.updatePadding(
                top = systemBars.top + headerClearancePx,
                bottom = systemBars.bottom + dockClearancePx
            )

            binding.playgroundBentoDock.updateLayoutParams<MarginLayoutParams> {
                bottomMargin = systemBars.bottom + (12 * resources.displayMetrics.density).toInt()
            }
            binding.bottomAppBar.updatePadding(bottom = systemBars.bottom)

            insets
        }

        val prefs = requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        val showToolbar = prefs.getBoolean(SettingsFragment.KEY_SHOW_FLOATING_TOOLBAR, false)
        val defaultStartup = prefs.getString(SettingsFragment.KEY_DEFAULT_STARTUP, SettingsFragment.STARTUP_CAMERA)
        val enableCamera = prefs.getBoolean(SettingsFragment.KEY_ENABLE_CAMERA, true)

        val onBackPressedCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (viewModel.uiState.value.isSelectionMode) {
                    viewModel.processIntent(PlaygroundUserIntent.ClearSelection)
                } else if (enableCamera && defaultStartup == SettingsFragment.STARTUP_CAMERA) {
                    if (!findNavController().navigateUp()) {
                        requireActivity().finishAfterTransition()
                    }
                } else {
                    requireActivity().finishAfterTransition()
                }
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, onBackPressedCallback)

        if (!showToolbar && defaultStartup == SettingsFragment.STARTUP_CAMERA && enableCamera) {
            binding.toolbar.setNavigationIcon(R.drawable.ic_back)
            binding.toolbar.setNavigationOnClickListener {
                requireActivity().onBackPressedDispatcher.onBackPressed()
            }
        } else {
            binding.toolbar.navigationIcon = null
        }

        val cameraMenu = binding.toolbar.menu.findItem(R.id.action_camera)
        cameraMenu?.isVisible = enableCamera

        binding.btnPlaygroundBack.visibility = if (enableCamera) View.VISIBLE else View.GONE
        binding.btnPlaygroundBack.setOnClickListener {
            if (viewModel.uiState.value.isSelectionMode) {
                viewModel.processIntent(PlaygroundUserIntent.ClearSelection)
            } else if (enableCamera) {
                if (!findNavController().navigateUp()) {
                    if (findNavController().currentDestination?.id == R.id.playground_gallery_fragment) {
                        findNavController().navigate(R.id.action_playground_gallery_to_camera)
                    }
                }
            } else {
                requireActivity().onBackPressedDispatcher.onBackPressed()
            }
        }

        binding.btnPlaygroundSelectAll.setOnClickListener {
            viewModel.processIntent(PlaygroundUserIntent.ToggleSelectAll)
        }

        binding.btnPlaygroundSettings.setOnClickListener {
            if (findNavController().currentDestination?.id == R.id.playground_gallery_fragment) {
                findNavController().navigate(R.id.action_playground_gallery_to_settings)
            }
        }

        binding.toolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_settings -> {
                    if (findNavController().currentDestination?.id == R.id.playground_gallery_fragment) {
                        findNavController().navigate(R.id.action_playground_gallery_to_settings)
                    }
                    true
                }
                R.id.action_camera -> {
                    if (findNavController().currentDestination?.id == R.id.playground_gallery_fragment) {
                        findNavController().navigate(R.id.action_playground_gallery_to_camera)
                    }
                    true
                }
                else -> false
            }
        }

        adapter = PlaygroundAdapter(
            coroutineScope = viewLifecycleOwner.lifecycleScope,
            selectedFiles = { viewModel.uiState.value.selectedFiles },
            isSelectionMode = { viewModel.uiState.value.isSelectionMode },
            onItemClick = { file ->
                if (viewModel.uiState.value.isSelectionMode) {
                    viewModel.processIntent(PlaygroundUserIntent.ToggleSelection(file))
                } else {
                    openViewer(listOf(file.absolutePath))
                }
            },
            onItemLongClick = { file ->
                viewModel.processIntent(PlaygroundUserIntent.ToggleSelection(file))
            },
            onExpandClick = { group, position ->
                viewModel.processIntent(PlaygroundUserIntent.ToggleGroupExpansion(group))
            }
        )

        val currentSpanCount = if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) 3 else 2
        val staggeredLayoutManager = StaggeredGridLayoutManager(currentSpanCount, StaggeredGridLayoutManager.VERTICAL).apply {
            gapStrategy = StaggeredGridLayoutManager.GAP_HANDLING_NONE
        }
        binding.recyclerView.layoutManager = staggeredLayoutManager
        binding.recyclerView.adapter = adapter

        binding.btnPlaygroundDockLayout.setOnClickListener {
            viewModel.processIntent(PlaygroundUserIntent.ToggleLayout)
        }

        binding.btnPlaygroundDockSelectAll.setOnClickListener {
            viewModel.processIntent(PlaygroundUserIntent.ToggleSelectAll)
        }

        binding.fabAdd.setOnClickListener {
            importDngLauncher.launch("image/x-adobe-dng")
        }

        binding.btnExport.setOnClickListener {
            viewModel.processIntent(PlaygroundUserIntent.ExportSelected)
        }

        binding.btnMerge.setOnClickListener {
            val selectedFiles = viewModel.uiState.value.selectedFiles
            val allDngs = selectedFiles.isNotEmpty() && selectedFiles.all { it.extension.lowercase() == "dng" }
            if (selectedFiles.size == 2 && allDngs) {
                val paths = selectedFiles.map { it.absolutePath }
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.playground_merge_layout_title)
                    .setItems(arrayOf(getString(R.string.collage_layout_sbs), getString(R.string.collage_layout_tb))) { _, which ->
                        val layout = if (which == 0) "SBS" else "TB"
                        openViewer(paths, layout)
                        viewModel.processIntent(PlaygroundUserIntent.ClearSelection)
                    }
                    .show()
            } else {
                Toast.makeText(requireContext(), R.string.playground_merge_need_two, Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnDisband.setOnClickListener {
            viewModel.processIntent(PlaygroundUserIntent.DisbandSelected)
        }

        binding.btnDelete.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.playground_delete_title)
                .setMessage(R.string.playground_delete_message)
                .setPositiveButton(R.string.playground_btn_delete) { _, _ ->
                    viewModel.processIntent(PlaygroundUserIntent.DeleteSelected)
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    renderUiState(state)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.effects.collect { effect ->
                    when (effect) {
                        is PlaygroundEffect.ShowToast -> Toast.makeText(requireContext(), effect.message, Toast.LENGTH_SHORT).show()
                        is PlaygroundEffect.ShowToastRes -> Toast.makeText(requireContext(), getString(effect.resId, *effect.formatArgs.toTypedArray()), Toast.LENGTH_SHORT).show()
                        is PlaygroundEffect.OpenViewer -> openViewer(effect.paths, effect.hfLayout)
                        is PlaygroundEffect.RequestRefresh -> viewModel.loadFiles(forceRefresh = true)
                    }
                }
            }
        }
    }

    private fun renderUiState(state: PlaygroundGalleryUiState) {
        val hasSelection = state.isSelectionMode && state.selectedFiles.isNotEmpty()
        val titleText = getString(R.string.playground_title)
        val subtitleText = if (hasSelection) {
            getString(R.string.gallery_selected_count, state.selectedFiles.size)
        } else {
            getString(R.string.playground_subtitle_format, state.items.size)
        }
        binding.toolbar.title = titleText
        binding.toolbar.subtitle = subtitleText
        binding.tvPlaygroundTitle.text = titleText
        binding.tvPlaygroundSubtitle.text = subtitleText

        (binding.playgroundBentoDock as? BentoTrayCard)?.setContainerRole(state.containerRole, animated = true)
        (binding.playgroundTopBentoCard as? BentoTrayCard)?.setContainerRole(state.containerRole, animated = true)

        val prefs = requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        val enableCamera = prefs.getBoolean(SettingsFragment.KEY_ENABLE_CAMERA, true)
        binding.btnPlaygroundBack.visibility = if (enableCamera || state.isSelectionMode) View.VISIBLE else View.GONE
        binding.btnPlaygroundBack.setIconResource(if (state.isSelectionMode) R.drawable.ic_close else R.drawable.ic_back)

        val allFilesCount = state.items.size
        val isAllSelected = state.isSelectionMode && state.selectedFiles.size >= allFilesCount && allFilesCount > 0
        binding.btnPlaygroundSelectAll.setText(if (isAllSelected) R.string.gallery_deselect_all else R.string.gallery_select_all)

        binding.playgroundLoadingIndicator.visibility = if (state.isLoading && state.items.isEmpty()) View.VISIBLE else View.GONE
        binding.emptyStateText.visibility = if (state.items.isEmpty() && !state.isLoading) View.VISIBLE else View.GONE

        // Dock buttons layout
        if (hasSelection) {
            binding.layoutPlaygroundNormalActions.visibility = View.GONE
            binding.layoutPlaygroundSelectionActions.visibility = View.VISIBLE

            val allDngs = state.selectedFiles.all { it.extension.lowercase() == "dng" }
            val isMergeActive = state.selectedFiles.size == 2 && allDngs
            binding.btnMerge.isEnabled = isMergeActive && !state.isExporting && !state.isDeleting
            binding.btnMerge.alpha = if (isMergeActive && !state.isExporting && !state.isDeleting) 1.0f else 0.4f

            val allGroups = state.selectedFiles.all { selectedFile ->
                state.items.any { item -> item is PlaygroundItem.Group && item.jpgFile == selectedFile }
            }
            binding.btnDisband.visibility = if (allGroups) View.VISIBLE else View.GONE
            binding.btnDisband.isEnabled = !state.isExporting && !state.isDeleting
            binding.btnDisband.alpha = if (state.isExporting || state.isDeleting) 0.4f else 1.0f

            binding.btnExport.isEnabled = !state.isExporting && !state.isDeleting
            binding.btnExport.alpha = if (state.isExporting || state.isDeleting) 0.4f else 1.0f

            binding.btnDelete.isEnabled = !state.isDeleting && !state.isExporting
            binding.btnDelete.alpha = if (state.isDeleting || state.isExporting) 0.4f else 1.0f
        } else {
            binding.layoutPlaygroundNormalActions.visibility = View.VISIBLE
            binding.layoutPlaygroundSelectionActions.visibility = View.GONE
        }

        // Layout manager span count
        (binding.recyclerView.layoutManager as? StaggeredGridLayoutManager)?.let { lm ->
            if (lm.spanCount != state.columnCount) {
                lm.spanCount = state.columnCount
            }
        }
        binding.btnPlaygroundDockLayout.text = getString(R.string.gallery_layout_cols, state.columnCount)

        // Submit list to adapter
        val selectionChanged = prevSelectedFiles != state.selectedFiles || prevSelectionMode != state.isSelectionMode
        prevSelectedFiles = state.selectedFiles
        prevSelectionMode = state.isSelectionMode

        adapter.submitList(state.items) {
            if (_binding != null) {
                (binding.recyclerView.layoutManager as? StaggeredGridLayoutManager)?.invalidateSpanAssignments()
                if (selectionChanged) {
                    adapter.notifyItemRangeChanged(0, adapter.itemCount, "SELECTION_CHANGED")
                }
            }
        }
    }

    private fun openViewer(paths: List<String>, hfLayout: String? = null) {
        val currentItems = viewModel.uiState.value.items
        val finalPaths = mutableListOf<String>()
        for (path in paths) {
            val file = File(path)
            val item = currentItems.find {
                when (it) {
                    is PlaygroundItem.Single -> it.file == file
                    is PlaygroundItem.Group -> it.jpgFile == file
                }
            }
            if (item is PlaygroundItem.Group && file == item.jpgFile) {
                finalPaths.add(item.dng1.absolutePath)
                finalPaths.add(item.dng2.absolutePath)
            } else {
                finalPaths.add(path)
            }
        }

        val bundle = Bundle().apply {
            putStringArray("playground_dng_paths", finalPaths.toTypedArray())
            if (hfLayout != null) {
                putString("playground_hf_layout", hfLayout)
            }
        }
        findNavController().navigate(R.id.action_playground_to_image_viewer, bundle)
    }

    override fun onResume() {
        super.onResume()
        viewModel.loadFiles(forceRefresh = true)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val spanCount = viewModel.uiState.value.columnCount
        (binding.recyclerView.layoutManager as? StaggeredGridLayoutManager)?.spanCount = spanCount
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

class PlaygroundAdapter(
    private val coroutineScope: kotlinx.coroutines.CoroutineScope,
    private val selectedFiles: () -> Set<File>,
    private val isSelectionMode: () -> Boolean,
    private val onItemClick: (File) -> Unit,
    private val onItemLongClick: (File) -> Unit,
    private val onExpandClick: (PlaygroundItem.Group, Int) -> Unit
) : androidx.recyclerview.widget.ListAdapter<PlaygroundItem, PlaygroundAdapter.ViewHolder>(PlaygroundItemDiffCallback()) {

    class PlaygroundItemDiffCallback : androidx.recyclerview.widget.DiffUtil.ItemCallback<PlaygroundItem>() {
        override fun areItemsTheSame(oldItem: PlaygroundItem, newItem: PlaygroundItem): Boolean {
            return oldItem.mainFile.absolutePath == newItem.mainFile.absolutePath
        }

        override fun areContentsTheSame(oldItem: PlaygroundItem, newItem: PlaygroundItem): Boolean {
            if (oldItem.javaClass != newItem.javaClass) return false
            if (oldItem is PlaygroundItem.Group && newItem is PlaygroundItem.Group) {
                if (oldItem.isExpanded != newItem.isExpanded) return false
                if (oldItem.dng1.lastModified() != newItem.dng1.lastModified() || oldItem.dng2.lastModified() != newItem.dng2.lastModified()) return false
            }
            return oldItem.mainFile.lastModified() == newItem.mainFile.lastModified()
        }

        override fun getChangePayload(oldItem: PlaygroundItem, newItem: PlaygroundItem): Any? {
            val payloads = mutableListOf<String>()

            if (oldItem is PlaygroundItem.Group && newItem is PlaygroundItem.Group) {
                if (oldItem.isExpanded != newItem.isExpanded) {
                    payloads.add("EXPANSION_CHANGED")
                }
            }

            val isModified = oldItem.mainFile.lastModified() != newItem.mainFile.lastModified() ||
                (oldItem is PlaygroundItem.Group && newItem is PlaygroundItem.Group &&
                (oldItem.dng1.lastModified() != newItem.dng1.lastModified() || oldItem.dng2.lastModified() != newItem.dng2.lastModified()))

            if (isModified) {
                payloads.add("MODIFIED")
            }

            if (payloads.isNotEmpty()) {
                // Return just the first string if it's one, or we can handle it differently.
                // The onBindViewHolder expects String payloads, if we pass a list it might get wrapped in another list.
                // Let's just return the first string because onBindViewHolder currently only looks at payloads list string elements.
                // Or better, let's return a special string and handle both if needed.
                // Let's just check the most important or return all and let onBindViewHolder handle List inside payloads.
                // Actually, if we return multiple strings in a list, we can just return the payloads list.
                return payloads
            }

            return super.getChangePayload(oldItem, newItem)
        }
    }

    class ViewHolder(val binding: ItemPlaygroundImageBinding) : RecyclerView.ViewHolder(binding.root) {
        var jobMain: kotlinx.coroutines.Job? = null
        var bitmapMain: Bitmap? = null
        var jobSub1: kotlinx.coroutines.Job? = null
        var bitmapSub1: Bitmap? = null
        var jobSub2: kotlinx.coroutines.Job? = null
        var bitmapSub2: Bitmap? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemPlaygroundImageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
            return
        }

        // Flatten the payloads since getChangePayload might return a List<String>
        val flattenedPayloads = payloads.flatMap { if (it is List<*>) it else listOf(it) }

        val item = getItem(position)
        var handled = false

        if (flattenedPayloads.contains("SELECTION_CHANGED")) {
            val isMainSelected = selectedFiles().contains(item.mainFile)
            val selectionMode = isSelectionMode()
            holder.binding.selectionOverlay.visibility = if (isMainSelected) View.VISIBLE else View.GONE
            holder.binding.iconSelected.visibility = if (selectionMode) View.VISIBLE else View.GONE
            holder.binding.iconSelected.setImageResource(if (isMainSelected) R.drawable.ic_check_circle else R.drawable.ic_radio_button_unchecked)

            if (item is PlaygroundItem.Group) {
                val isDng1Selected = selectedFiles().contains(item.dng1)
                val isDng2Selected = selectedFiles().contains(item.dng2)
                holder.binding.subSelectionOverlay1.visibility = if (isDng1Selected) View.VISIBLE else View.GONE
                holder.binding.subIconSelected1.visibility = if (selectionMode) View.VISIBLE else View.GONE
                holder.binding.subIconSelected1.setImageResource(if (isDng1Selected) R.drawable.ic_check_circle else R.drawable.ic_radio_button_unchecked)

                holder.binding.subSelectionOverlay2.visibility = if (isDng2Selected) View.VISIBLE else View.GONE
                holder.binding.subIconSelected2.visibility = if (selectionMode) View.VISIBLE else View.GONE
                holder.binding.subIconSelected2.setImageResource(if (isDng2Selected) R.drawable.ic_check_circle else R.drawable.ic_radio_button_unchecked)
            }
            handled = true
        }

        if (flattenedPayloads.contains("EXPANSION_CHANGED")) {
            if (item is PlaygroundItem.Group) {
                holder.binding.subImagesContainer.visibility = if (item.isExpanded) View.VISIBLE else View.GONE
            }
            handled = true
        }

        if (flattenedPayloads.contains("MODIFIED")) {
            val context = holder.itemView.context
            when (item) {
                is PlaygroundItem.Single -> {
                    holder.jobMain?.cancel()
                    holder.jobMain = null
                    // Recycle the previous main bitmap to avoid memory leaks before loading the new one.
                    holder.binding.imageViewThumbnail.setImageDrawable(null)
                    holder.bitmapMain?.recycle()
                    holder.bitmapMain = null

                    val file = item.file
                    val jpgFile = java.io.File(file.parent, file.nameWithoutExtension + ".jpg")
                    if (jpgFile.exists()) {
                        loadThumbnail(context, jpgFile, holder.binding.imageViewThumbnail, holder, true)
                    } else {
                        loadThumbnail(context, file, holder.binding.imageViewThumbnail, holder, true)
                    }
                }
                is PlaygroundItem.Group -> {
                    holder.jobMain?.cancel()
                    holder.jobSub1?.cancel()
                    holder.jobSub2?.cancel()
                    holder.jobMain = null
                    holder.jobSub1 = null
                    holder.jobSub2 = null
                    holder.bitmapMain?.recycle()
                    holder.bitmapSub1?.recycle()
                    holder.bitmapSub2?.recycle()
                    holder.bitmapMain = null
                    holder.bitmapSub1 = null
                    holder.bitmapSub2 = null

                    loadThumbnail(context, item.jpgFile, holder.binding.imageViewThumbnail, holder, true)
                    loadThumbnail(context, item.dng1, holder.binding.subImageView1, holder, isMain = false, isSub1 = true)
                    loadThumbnail(context, item.dng2, holder.binding.subImageView2, holder, isMain = false, isSub2 = true)
                }
            }
            handled = true
        }

        if (!handled) {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    private fun loadThumbnail(
        context: android.content.Context,
        file: File,
        imageView: android.widget.ImageView,
        holder: ViewHolder,
        isMain: Boolean,
        isSub1: Boolean = false,
        isSub2: Boolean = false
    ) {
        val currentTag = file.absolutePath + "_" + file.lastModified()
        imageView.tag = currentTag

        // Use JPG directly if available
        if (file.extension.lowercase() == "jpg") {
            Glide.with(context).load(file).signature(com.bumptech.glide.signature.ObjectKey(file.lastModified())).into(imageView)
            return
        }

        // Otherwise, it's a DNG, decode it
        val job = coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            var decodedBitmap: Bitmap? = null
            var bitmapAssigned = false
            try {
                decodedBitmap = top.maary.darkbag.utils.GalleryThumbnailLoader.loadDngThumbnail(
                    context = context,
                    uri = Uri.fromFile(file),
                    reqWidth = 400,
                    reqHeight = 400
                )

                ensureActive()

                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    if (imageView.tag == currentTag) {
                        if (decodedBitmap != null) {
                            if (isMain) holder.bitmapMain = decodedBitmap
                            else if (isSub1) holder.bitmapSub1 = decodedBitmap
                            else if (isSub2) holder.bitmapSub2 = decodedBitmap

                            bitmapAssigned = true
                            imageView.setImageBitmap(decodedBitmap)
                        } else {
                            Glide.with(context).load(file).signature(com.bumptech.glide.signature.ObjectKey(file.lastModified())).into(imageView)
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("Playground", "Failed to load thumbnail for ${file.name}", e)
            } finally {
                // If the coroutine was cancelled or the tag changed before setting, recycle the bitmap
                if (decodedBitmap != null && !bitmapAssigned) {
                    decodedBitmap?.recycle()
                }
            }
        }

        if (isMain) holder.jobMain = job
        else if (isSub1) holder.jobSub1 = job
        else if (isSub2) holder.jobSub2 = job
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        val isMainSelected = selectedFiles().contains(item.mainFile)

        // Apply aspect ratio to pre-allocate exact height to prevent StaggeredGridLayoutManager jumping
        top.maary.darkbag.utils.GalleryAspectRatioHelper.applyAspectRatio(holder.binding.imageViewThumbnail, item.aspectRatio, fallbackRatio = "3:4")

        val selectionMode = isSelectionMode()
        holder.binding.selectionOverlay.visibility = if (isMainSelected) View.VISIBLE else View.GONE
        holder.binding.iconSelected.visibility = if (selectionMode) View.VISIBLE else View.GONE
        holder.binding.iconSelected.setImageResource(if (isMainSelected) R.drawable.ic_check_circle else R.drawable.ic_radio_button_unchecked)

        val context = holder.itemView.context
        Glide.with(context).clear(holder.binding.imageViewThumbnail)
        Glide.with(context).clear(holder.binding.subImageView1)
        Glide.with(context).clear(holder.binding.subImageView2)
        holder.binding.imageViewThumbnail.setImageDrawable(null)
        holder.binding.subImageView1.setImageDrawable(null)
        holder.binding.subImageView2.setImageDrawable(null)

        holder.jobMain?.cancel()
        holder.jobSub1?.cancel()
        holder.jobSub2?.cancel()
        holder.jobMain = null
        holder.jobSub1 = null
        holder.jobSub2 = null
        holder.bitmapMain?.recycle()
        holder.bitmapSub1?.recycle()
        holder.bitmapSub2?.recycle()
        holder.bitmapMain = null
        holder.bitmapSub1 = null
        holder.bitmapSub2 = null

        when (item) {
            is PlaygroundItem.Single -> {
                holder.binding.badgeFormat.text = if (item.file.extension.lowercase() == "dng") "EXT DNG" else "EXT JPG"
                holder.binding.iconLayer.visibility = View.GONE
                holder.binding.subImagesContainer.visibility = View.GONE

                val file = item.file
                val jpgFile = File(file.parent, file.nameWithoutExtension + ".jpg")
                if (jpgFile.exists()) {
                    loadThumbnail(context, jpgFile, holder.binding.imageViewThumbnail, holder, true)
                } else {
                    loadThumbnail(context, file, holder.binding.imageViewThumbnail, holder, true)
                }
            }
            is PlaygroundItem.Group -> {
                holder.binding.badgeFormat.text = "HF MERGED"
                holder.binding.iconLayer.visibility = View.VISIBLE
                holder.binding.subImagesContainer.visibility = if (item.isExpanded) View.VISIBLE else View.GONE

                loadThumbnail(context, item.jpgFile, holder.binding.imageViewThumbnail, holder, true)

                // Load sub images
                holder.binding.subImageView1.setImageDrawable(null)
                holder.binding.subImageView2.setImageDrawable(null)
                loadThumbnail(context, item.dng1, holder.binding.subImageView1, holder, isMain = false, isSub1 = true)
                loadThumbnail(context, item.dng2, holder.binding.subImageView2, holder, isMain = false, isSub2 = true)

                val isDng1Selected = selectedFiles().contains(item.dng1)
                val isDng2Selected = selectedFiles().contains(item.dng2)
                holder.binding.subSelectionOverlay1.visibility = if (isDng1Selected) View.VISIBLE else View.GONE
                holder.binding.subIconSelected1.visibility = if (selectionMode) View.VISIBLE else View.GONE
                holder.binding.subIconSelected1.setImageResource(if (isDng1Selected) R.drawable.ic_check_circle else R.drawable.ic_radio_button_unchecked)

                holder.binding.subSelectionOverlay2.visibility = if (isDng2Selected) View.VISIBLE else View.GONE
                holder.binding.subIconSelected2.visibility = if (selectionMode) View.VISIBLE else View.GONE
                holder.binding.subIconSelected2.setImageResource(if (isDng2Selected) R.drawable.ic_check_circle else R.drawable.ic_radio_button_unchecked)

                holder.binding.iconLayer.setOnClickListener {
                    onExpandClick(item, holder.bindingAdapterPosition)
                }

                holder.binding.subImageView1.setOnClickListener { onItemClick(item.dng1) }
                holder.binding.subImageView1.setOnLongClickListener {
                    onItemLongClick(item.dng1)
                    true
                }
                holder.binding.subImageView2.setOnClickListener { onItemClick(item.dng2) }
                holder.binding.subImageView2.setOnLongClickListener {
                    onItemLongClick(item.dng2)
                    true
                }
            }
        }

        holder.binding.imageViewThumbnail.setOnClickListener { onItemClick(item.mainFile) }
        holder.binding.imageViewThumbnail.setOnLongClickListener {
            onItemLongClick(item.mainFile)
            true
        }
    }



    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        val context = holder.itemView.context
        Glide.with(context).clear(holder.binding.imageViewThumbnail)
        Glide.with(context).clear(holder.binding.subImageView1)
        Glide.with(context).clear(holder.binding.subImageView2)
        holder.binding.imageViewThumbnail.setImageDrawable(null)
        holder.binding.subImageView1.setImageDrawable(null)
        holder.binding.subImageView2.setImageDrawable(null)

        holder.jobMain?.cancel()
        holder.jobSub1?.cancel()
        holder.jobSub2?.cancel()
        holder.jobMain = null
        holder.jobSub1 = null
        holder.jobSub2 = null
        holder.bitmapMain?.recycle()
        holder.bitmapSub1?.recycle()
        holder.bitmapSub2?.recycle()
        holder.bitmapMain = null
        holder.bitmapSub1 = null
        holder.bitmapSub2 = null
    }
}
