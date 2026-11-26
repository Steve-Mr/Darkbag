package top.maary.darkbag.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import top.maary.darkbag.R
import top.maary.darkbag.databinding.FragmentPlaygroundGalleryBinding
import top.maary.darkbag.fragments.gallery.GalleryEdgeToEdgeHelper
import top.maary.darkbag.fragments.gallery.PlaygroundAdapter
import top.maary.darkbag.models.PlaygroundItem
import top.maary.darkbag.repository.PreferenceKeys
import top.maary.darkbag.repository.SettingsRepository
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

    private val selectionBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            viewModel.processIntent(PlaygroundUserIntent.ClearSelection)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        top.maary.darkbag.utils.PredictiveBackTransitions.apply(this)
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

        GalleryEdgeToEdgeHelper.applyFloatingGalleryInsets(
            rootView = binding.root,
            recyclerView = binding.recyclerView,
            topHeaderView = binding.appBarLayout,
            bottomDockView = binding.playgroundBentoDock,
            bottomAppBarView = binding.bottomAppBar,
            topClearanceDp = 68,
            bottomClearanceDp = 88,
            applyTopPaddingOnHeaderInsteadOfMargin = true,
            applyHorizontalInsets = false,
            elevateTopHeader = true
        )

        val currentPrefs = SettingsRepository.getInstance(requireContext()).current()
        val showToolbar = currentPrefs.showFloatingToolbar
        val defaultStartup = currentPrefs.defaultStartupPage
        val enableCamera = currentPrefs.enableCamera

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, selectionBackCallback)

        val navigateBackAction = {
            if (viewModel.uiState.value.isSelectionMode) {
                viewModel.processIntent(PlaygroundUserIntent.ClearSelection)
            } else if (!findNavController().navigateUp()) {
                requireActivity().finishAfterTransition()
            }
        }

        if (!showToolbar && defaultStartup == PreferenceKeys.STARTUP_CAMERA && enableCamera) {
            binding.toolbar.setNavigationIcon(R.drawable.ic_back)
            binding.toolbar.setNavigationOnClickListener {
                navigateBackAction()
            }
        } else {
            binding.toolbar.navigationIcon = null
        }

        val cameraMenu = binding.toolbar.menu.findItem(R.id.action_camera)
        cameraMenu?.isVisible = enableCamera

        binding.btnPlaygroundBack.visibility = if (enableCamera) View.VISIBLE else View.GONE
        binding.btnPlaygroundBack.setOnClickListener {
            navigateBackAction()
        }

        binding.btnPlaygroundCamera.visibility = if (enableCamera) View.VISIBLE else View.GONE
        binding.btnPlaygroundCamera.setOnClickListener {
            if (findNavController().currentDestination?.id == R.id.playground_gallery_fragment) {
                findNavController().navigate(R.id.action_playground_gallery_to_camera)
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
        selectionBackCallback.isEnabled = state.isSelectionMode
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

        binding.playgroundBentoDock.setContainerRole(state.containerRole, animated = true)
        binding.playgroundTopBentoCard.setContainerRole(state.containerRole, animated = true)

        val enableCamera = SettingsRepository.getInstance(requireContext()).current().enableCamera
        binding.btnPlaygroundBack.visibility = if (enableCamera || state.isSelectionMode || findNavController().previousBackStackEntry != null) View.VISIBLE else View.GONE
        binding.btnPlaygroundBack.setIconResource(if (state.isSelectionMode) R.drawable.ic_close else R.drawable.ic_back)

        if (state.isSelectionMode) {
            binding.btnPlaygroundCamera.visibility = View.GONE
            binding.btnPlaygroundSelectAll.visibility = View.VISIBLE
            val allFilesCount = state.items.size
            val isAllSelected = state.selectedFiles.size >= allFilesCount && allFilesCount > 0
            binding.btnPlaygroundSelectAll.setText(if (isAllSelected) R.string.gallery_deselect_all else R.string.gallery_select_all)
        } else {
            binding.btnPlaygroundCamera.visibility = if (enableCamera) View.VISIBLE else View.GONE
            binding.btnPlaygroundSelectAll.visibility = View.GONE
        }

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
