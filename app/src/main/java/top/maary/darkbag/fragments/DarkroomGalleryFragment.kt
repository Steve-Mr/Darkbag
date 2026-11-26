package top.maary.darkbag.fragments

import android.app.Activity
import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
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
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.SimpleItemAnimator
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import kotlinx.coroutines.launch
import top.maary.darkbag.R
import top.maary.darkbag.databinding.FragmentDarkroomGalleryBinding
import top.maary.darkbag.fragments.gallery.GalleryBatchActionHelper
import top.maary.darkbag.fragments.gallery.GalleryEdgeToEdgeHelper
import top.maary.darkbag.models.ImageGroup
import top.maary.darkbag.repository.AlbumStatsCache
import top.maary.darkbag.repository.ImageRepository
import top.maary.darkbag.utils.GalleryHomogeneityChecker
import top.maary.darkbag.viewer.deletion.GalleryBatchDeletionCoordinator
import top.maary.darkbag.viewmodel.DarkroomGalleryEffect
import top.maary.darkbag.viewmodel.DarkroomGalleryUiState
import top.maary.darkbag.viewmodel.DarkroomGalleryUserIntent
import top.maary.darkbag.viewmodel.DarkroomGalleryViewModel

/**
 * Dedicated Darkroom Gallery Fragment for browsing, selecting, batch grading,
 * collaging, and managing Darkbag captures.
 *
 * Physically decoupled from ImageViewerFragment to enforce single-responsibility
 * and eliminate dual-mode layout/lifecycle entanglements.
 */
class DarkroomGalleryFragment : Fragment() {

    private var _binding: FragmentDarkroomGalleryBinding? = null
    private val binding get() = _binding!!

    private val galleryViewModel: DarkroomGalleryViewModel by viewModels()
    private val repository by lazy { ImageRepository(requireContext().applicationContext) }
    private val args: DarkroomGalleryFragmentArgs by navArgs()

    private lateinit var galleryAdapter: DarkbagGalleryGridAdapter
    private lateinit var batchDeletionCoordinator: GalleryBatchDeletionCoordinator
    private lateinit var batchActionHelper: GalleryBatchActionHelper
    private var lastSubmittedItems: List<ImageGroup>? = null
    private var isDeletingIndicatorVisible = false
    private var isLoadingIndicatorVisible = false

    private val backPressedCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (galleryViewModel.uiState.value.isSelectionMode) {
                galleryViewModel.processIntent(DarkroomGalleryUserIntent.ClearSelection)
            } else {
                isEnabled = false
                findNavController().navigateUp()
            }
        }
    }

    private val deleteLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        Log.d(TAG, "deleteLauncher resultCode=${result.resultCode}")
        if (result.resultCode != Activity.RESULT_OK) {
            context?.let { Toast.makeText(it, R.string.gallery_delete_prompt_failed, Toast.LENGTH_SHORT).show() }
        }
        galleryViewModel.processIntent(DarkroomGalleryUserIntent.SetDeleting(false, 0))
        lifecycleScope.launch {
            repository.invalidateCache(clearStale = true)
            galleryViewModel.processIntent(DarkroomGalleryUserIntent.LoadImages(null))
        }
    }

    private val importGalleryMediaLauncher = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) {
            lifecycleScope.launch {
                galleryViewModel.processIntent(DarkroomGalleryUserIntent.SetLoading(true))
                val importedCount = batchActionHelper.inspectImportedDarkbagUris(requireContext(), uris)
                galleryViewModel.processIntent(DarkroomGalleryUserIntent.SetLoading(false))
                repository.invalidateCache(clearStale = true)
                galleryViewModel.processIntent(DarkroomGalleryUserIntent.LoadImages(null))
                Toast.makeText(requireContext(), getString(R.string.gallery_import_success, importedCount), Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        top.maary.darkbag.utils.PredictiveBackTransitions.apply(this)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDarkroomGalleryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backPressedCallback)

        batchDeletionCoordinator = GalleryBatchDeletionCoordinator(
            context = requireContext(),
            scope = viewLifecycleOwner.lifecycleScope,
            launchDeleteRequest = { deleteLauncher.launch(it) },
            onBeforeDelete = { count ->
                Log.d(TAG, "onBeforeDelete: count=$count")
                galleryViewModel.processIntent(DarkroomGalleryUserIntent.SetDeleting(true, count))
            },
            onClearSelection = {
                galleryViewModel.processIntent(DarkroomGalleryUserIntent.ClearSelection)
                galleryAdapter.syncSelection(emptySet(), false)
                updateGallerySelectionUI(galleryViewModel.uiState.value)
            },
            onAfterDeleteComplete = {
                Log.d(TAG, "onAfterDeleteComplete")
                galleryViewModel.processIntent(DarkroomGalleryUserIntent.SetDeleting(false, 0))
                repository.invalidateCache(clearStale = true)
                galleryViewModel.processIntent(DarkroomGalleryUserIntent.LoadImages(null))
            },
            onDeletePromptFailed = {
                context?.let { Toast.makeText(it, R.string.gallery_delete_prompt_failed, Toast.LENGTH_SHORT).show() }
            }
        )

        (childFragmentManager.findFragmentByTag(DarkbagBatchDeleteSheet.TAG) as? DarkbagBatchDeleteSheet)?.let { existingSheet ->
            if (existingSheet.onDeleteConfirmed == null) {
                existingSheet.onDeleteConfirmed = { deleteMode ->
                    val currentSelected = galleryViewModel.uiState.value.getSelectedItems()
                    if (currentSelected.isNotEmpty()) {
                        batchDeletionCoordinator.deleteBatchImages(currentSelected, deleteMode)
                    }
                }
            }
        }

        batchActionHelper = GalleryBatchActionHelper(
            contextProvider = { context },
            scope = viewLifecycleOwner.lifecycleScope,
            repository = repository,
            onStartWork = { count ->
                galleryViewModel.processIntent(DarkroomGalleryUserIntent.SetDeleting(true, count))
            },
            onEndWork = {
                galleryViewModel.processIntent(DarkroomGalleryUserIntent.SetDeleting(false, 0))
            },
            onRefreshAfterSuccess = {
                galleryViewModel.processIntent(DarkroomGalleryUserIntent.LoadImages(null))
                galleryViewModel.processIntent(DarkroomGalleryUserIntent.ClearSelection)
                galleryAdapter.syncSelection(emptySet(), false)
                updateGallerySelectionUI(galleryViewModel.uiState.value)
            }
        )

        GalleryEdgeToEdgeHelper.applyFloatingGalleryInsets(
            rootView = binding.root,
            recyclerView = binding.galleryRecyclerView,
            topHeaderView = binding.galleryHeaderCard,
            bottomDockView = binding.galleryBottomBar,
            topClearanceDp = 76,
            bottomClearanceDp = 88,
            applyTopPaddingOnHeaderInsteadOfMargin = false,
            applyHorizontalInsets = true,
            elevateTopHeader = false
        )
        setupGalleryView()
        observeViewModel()

        galleryViewModel.processIntent(DarkroomGalleryUserIntent.LoadImages(args.initialUri))
    }

    private fun setupGalleryView() {
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        galleryViewModel.processIntent(DarkroomGalleryUserIntent.SyncOrientation(isLandscape))
        val currentLayout = galleryViewModel.uiState.value

        galleryAdapter = DarkbagGalleryGridAdapter(requireContext(), repository).apply {
            columnCount = currentLayout.columnCount
            isWaterfallMode = currentLayout.isWaterfallMode
            onItemClick = { group, pos, lensTag ->
                val state = galleryViewModel.uiState.value
                if (!state.isDeleting) {
                    if (state.isSelectionMode) {
                        if (lensTag != null) {
                            val lens = group.multiCameraLenses.find { it.lensTag == lensTag }
                            if (lens != null) {
                                galleryViewModel.processIntent(DarkroomGalleryUserIntent.ToggleLensSelection(group, lens))
                            }
                        } else {
                            galleryViewModel.processIntent(DarkroomGalleryUserIntent.ToggleSingleSelection(group))
                        }
                    } else {
                        navigateToViewer(group, pos, lensTag)
                    }
                }
            }
            onItemLongClick = { group, _, lensTag ->
                if (!galleryViewModel.uiState.value.isDeleting) {
                    if (lensTag != null) {
                        val lens = group.multiCameraLenses.find { it.lensTag == lensTag }
                        if (lens != null) {
                            galleryViewModel.processIntent(DarkroomGalleryUserIntent.ToggleLensSelection(group, lens))
                        }
                    } else {
                        galleryViewModel.processIntent(DarkroomGalleryUserIntent.ToggleSingleSelection(group))
                    }
                }
            }
        }

        binding.galleryRecyclerView.adapter = galleryAdapter
        binding.galleryRecyclerView.setHasFixedSize(true)
        binding.galleryRecyclerView.setItemViewCacheSize(20)
        (binding.galleryRecyclerView.itemAnimator as? SimpleItemAnimator)?.apply {
            removeDuration = 250
            moveDuration = 250
        }
        applyGalleryLayout(currentLayout.columnCount, currentLayout.isWaterfallMode)

        // Header Action: Back (Normal) / Close Selection (Selection)
        binding.btnGalleryHeaderAction.setOnClickListener {
            if (galleryViewModel.uiState.value.isSelectionMode) {
                galleryViewModel.processIntent(DarkroomGalleryUserIntent.ClearSelection)
            } else {
                findNavController().navigateUp()
            }
        }

        // Header Info
        binding.btnGalleryHeaderInfo.setOnClickListener {
            showAlbumInfoSheet()
        }

        // Header Select All / Deselect All Toggle (Selection Mode)
        binding.btnGalleryHeaderSelectAll.setOnClickListener {
            galleryViewModel.processIntent(DarkroomGalleryUserIntent.ToggleSelectAll)
        }

        // Normal Dock Actions
        binding.btnGalleryDockSelectAll.setOnClickListener {
            galleryViewModel.processIntent(DarkroomGalleryUserIntent.SetSelectionMode(true))
            galleryViewModel.processIntent(DarkroomGalleryUserIntent.ToggleSelectAll)
        }

        binding.btnGalleryDockLayout.setOnClickListener {
            cycleGalleryLayout()
        }

        binding.btnGalleryDockImport.setOnClickListener {
            importGalleryMediaLauncher.launch("*/*")
        }

        // Selection Dock Actions
        binding.btnGalleryDockMerge.setOnClickListener {
            val selected = galleryViewModel.uiState.value.getSelectedItems()
            if (selected.size == 2) {
                batchActionHelper.performMergeCollage(selected, childFragmentManager)
            } else {
                Toast.makeText(requireContext(), R.string.gallery_merge_need_two, Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnGalleryDockBatchGrade.setOnClickListener {
            val selected = galleryViewModel.uiState.value.getSelectedItems()
            if (selected.isEmpty()) return@setOnClickListener
            if (checkHomogeneity(selected)) {
                batchActionHelper.performBatchGrade(selected)
            } else {
                Toast.makeText(requireContext(), R.string.gallery_batch_grade_mismatch, Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnGalleryDockShare.setOnClickListener {
            val selected = galleryViewModel.uiState.value.getSelectedItems()
            val uris = selected.mapNotNull { item ->
                if (item.specificLens != null) {
                    item.specificLens.jpgUri ?: item.specificLens.dngUri
                } else {
                    val g = item.group
                    g.firstAvailableUri ?: g.jpgUri ?: g.mp4VideoUri ?: g.cinemaDngFirstFrameUri ?: g.dngUri
                }
            }
            if (uris.isNotEmpty()) {
                val intent = batchActionHelper.buildShareIntent(requireContext(), uris)
                startActivity(android.content.Intent.createChooser(intent, getString(R.string.gallery_action_share)))
            }
        }

        binding.btnGalleryDockDelete.setOnClickListener {
            val state = galleryViewModel.uiState.value
            if (!isAdded || isStateSaved || state.isDeleting) return@setOnClickListener
            val selected = state.getSelectedItems()
            Log.d(
                TAG,
                "btnGalleryDockDelete clicked: vmSelectedCount=${selected.size}, vmKeys=${state.selectedKeys}, adapterSelectedCount=${galleryAdapter.getSelectedCount()}"
            )
            if (selected.isNotEmpty()) {
                batchDeletionCoordinator.showBatchDeleteDialog(selected, childFragmentManager)
            }
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    galleryViewModel.uiState.collect { state ->
                        galleryAdapter.syncSelection(state.selectedKeys, state.isSelectionMode)
                        updateGallerySelectionUI(state)
                        if (state.isLoading) {
                            showGalleryLoadingIndicator()
                        } else {
                            hideGalleryLoadingIndicator()
                        }
                        if (state.isDeleting) {
                            showGalleryDeletingIndicator(state.deletingCount)
                        } else {
                            hideGalleryDeletingIndicator()
                        }

                        if (state.items !== lastSubmittedItems) {
                            lastSubmittedItems = state.items
                            val wasAtTop = !binding.galleryRecyclerView.canScrollVertically(-1)
                            galleryAdapter.submitList(state.items) {
                                if (_binding != null) {
                                    val latestState = galleryViewModel.uiState.value
                                    galleryAdapter.syncSelection(latestState.selectedKeys, latestState.isSelectionMode)
                                    if (!latestState.isSelectionMode) {
                                        binding.tvGalleryHeaderSubtitle.text =
                                            getString(R.string.gallery_groups_count, latestState.items.size)
                                    }
                                    if (wasAtTop && latestState.items.isNotEmpty() && !latestState.isSelectionMode) {
                                        binding.galleryRecyclerView.scrollToPosition(0)
                                    }
                                }
                            }
                        }
                    }
                }
                launch {
                    galleryViewModel.effects.collect { effect ->
                        when (effect) {
                            is DarkroomGalleryEffect.ShowToast -> Toast.makeText(context, effect.message, Toast.LENGTH_SHORT).show()
                            is DarkroomGalleryEffect.ShowToastRes -> {
                                val msg = getString(effect.resId, *effect.formatArgs.toTypedArray())
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            }
                            is DarkroomGalleryEffect.ScrollToPosition -> {
                                binding.galleryRecyclerView.post {
                                    binding.galleryRecyclerView.scrollToPosition(effect.position)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun navigateToViewer(group: ImageGroup, position: Int, lensTag: String?) {
        val initialUri = (group.firstAvailableUri ?: group.jpgUri ?: group.dngUri)?.toString()
        val action = DarkroomGalleryFragmentDirections.actionDarkroomGalleryToImageViewer(
            initialUri = initialUri,
            startInGalleryMode = false,
            targetPosition = position,
            targetLensTag = lensTag
        )
        findNavController().navigate(action)
    }

    private fun updateGallerySelectionUI(state: DarkroomGalleryUiState) {
        if (!::galleryAdapter.isInitialized || _binding == null) return
        backPressedCallback.isEnabled = state.isSelectionMode
        binding.galleryRecyclerView.isEnabled = !state.isDeleting

        if (state.isSelectionMode) {
            binding.galleryHeaderCard.setContainerRole(state.containerRole, animated = true)
            binding.btnGalleryHeaderAction.setIconResource(R.drawable.ic_close)
            binding.tvGalleryHeaderTitle.text = getString(R.string.gallery_album_darkroom)
            binding.tvGalleryHeaderSubtitle.text = getString(R.string.gallery_selected_count, state.selectedCount)
            binding.btnGalleryHeaderInfo.visibility = View.GONE
            binding.btnGalleryHeaderSelectAll.visibility = View.VISIBLE

            binding.btnGalleryHeaderSelectAll.setText(if (state.isAllSelected) R.string.gallery_deselect_all else R.string.gallery_select_all)

            binding.galleryBottomBar.visibility = if (state.isDeleting) View.GONE else View.VISIBLE
            binding.galleryBottomBar.setContainerRole(state.containerRole, animated = true)
            binding.layoutGalleryNormalActions.visibility = View.GONE
            binding.layoutGallerySelectionActions.visibility = View.VISIBLE

            val canMerge = state.selectedCount == 2
            binding.btnGalleryDockMerge.isEnabled = canMerge
            binding.btnGalleryDockMerge.alpha = if (canMerge) 1.0f else 0.4f

            val selected = state.getSelectedItems()
            val canGrade = selected.isNotEmpty() && checkHomogeneity(selected)
            binding.btnGalleryDockBatchGrade.isEnabled = canGrade
            binding.btnGalleryDockBatchGrade.alpha = if (canGrade) 1.0f else 0.4f

            val hasSelection = state.selectedCount > 0
            binding.btnGalleryDockShare.isEnabled = hasSelection
            binding.btnGalleryDockShare.alpha = if (hasSelection) 1.0f else 0.4f

            binding.btnGalleryDockDelete.isEnabled = hasSelection
            binding.btnGalleryDockDelete.alpha = if (hasSelection) 1.0f else 0.4f
        } else {
            binding.galleryHeaderCard.setContainerRole(state.containerRole, animated = true)
            binding.btnGalleryHeaderAction.setIconResource(R.drawable.ic_back)
            val count = state.items.size
            binding.tvGalleryHeaderTitle.text = getString(R.string.gallery_album_darkroom)
            binding.tvGalleryHeaderSubtitle.text = getString(R.string.gallery_groups_count, count)
            binding.btnGalleryHeaderInfo.visibility = View.VISIBLE
            binding.btnGalleryHeaderSelectAll.visibility = View.GONE

            binding.galleryBottomBar.visibility = if (state.isDeleting) View.GONE else View.VISIBLE
            binding.galleryBottomBar.setContainerRole(state.containerRole, animated = true)
            binding.layoutGalleryNormalActions.visibility = View.VISIBLE
            binding.layoutGallerySelectionActions.visibility = View.GONE
        }
    }

    private fun createGridLayoutManager(cols: Int): GridLayoutManager {
        val lm = GridLayoutManager(requireContext(), cols)
        lm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                if (position in 0 until galleryAdapter.itemCount) {
                    val item = galleryAdapter.getItem(position)
                    if (item.isMultiCamera && item.multiCameraLenses.isNotEmpty()) {
                        return lm.spanCount
                    }
                }
                return 1
            }
        }
        return lm
    }

    private fun applyGalleryLayout(columnCount: Int, isWaterfallMode: Boolean) {
        if (!::galleryAdapter.isInitialized || _binding == null) return
        galleryAdapter.columnCount = columnCount
        galleryAdapter.isWaterfallMode = isWaterfallMode
        if (isWaterfallMode) {
            val spanCount = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 3 else 2
            val staggeredLm = StaggeredGridLayoutManager(spanCount, StaggeredGridLayoutManager.VERTICAL).apply {
                gapStrategy = StaggeredGridLayoutManager.GAP_HANDLING_NONE
            }
            binding.galleryRecyclerView.layoutManager = staggeredLm
            binding.btnGalleryDockLayout.text = getString(R.string.gallery_layout_waterfall)
        } else {
            binding.galleryRecyclerView.layoutManager = createGridLayoutManager(columnCount)
            binding.btnGalleryDockLayout.text = if (columnCount == 1) {
                getString(R.string.gallery_layout_1_col)
            } else {
                getString(R.string.gallery_layout_cols, columnCount)
            }
        }
    }

    private fun cycleGalleryLayout() {
        if (!::galleryAdapter.isInitialized) return
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        galleryViewModel.processIntent(DarkroomGalleryUserIntent.CycleLayout(isLandscape))
        val next = galleryViewModel.uiState.value
        applyGalleryLayout(next.columnCount, next.isWaterfallMode)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::galleryAdapter.isInitialized && _binding != null) {
            val isLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
            galleryViewModel.processIntent(DarkroomGalleryUserIntent.SyncOrientation(isLandscape))
            val currentLayout = galleryViewModel.uiState.value
            applyGalleryLayout(currentLayout.columnCount, currentLayout.isWaterfallMode)
        }
    }

    private fun showAlbumInfoSheet() {
        if (!isAdded || isStateSaved) return
        if (childFragmentManager.isStateSaved || childFragmentManager.findFragmentByTag(GalleryAlbumInfoSheet.TAG) != null) return

        val items = if (::galleryAdapter.isInitialized) galleryAdapter.currentList else emptyList()
        val stats = AlbumStatsCache.getOrCompute(items)

        GalleryAlbumInfoSheet.newInstance(stats.totalCount, stats.totalSizeBytes, stats.lastCaptureTime)
            .show(childFragmentManager, GalleryAlbumInfoSheet.TAG)
    }

    private fun checkHomogeneity(selected: List<GallerySelectedItem>): Boolean =
        GalleryHomogeneityChecker.isHomogeneous(selected)

    private fun showGalleryDeletingIndicator(count: Int) {
        if (_binding == null) return
        binding.tvGalleryDeletingStatus.text = if (count > 0) {
            getString(R.string.gallery_deleting_count, count)
        } else {
            getString(R.string.gallery_deleting)
        }
        if (isDeletingIndicatorVisible) return
        isDeletingIndicatorVisible = true
        binding.galleryDeletingIndicator.apply {
            animate().cancel()
            visibility = View.VISIBLE
            alpha = 0f
            translationY = -40f
            animate().alpha(1f).translationY(0f).setDuration(200).start()
        }
    }

    private fun hideGalleryDeletingIndicator() {
        if (_binding == null || !isDeletingIndicatorVisible) return
        isDeletingIndicatorVisible = false
        binding.galleryDeletingIndicator.apply {
            animate().cancel()
            animate().alpha(0f).translationY(-40f).setDuration(200)
                .withEndAction {
                    if (_binding != null && !isDeletingIndicatorVisible) {
                        visibility = View.GONE
                    }
                }
                .start()
        }
    }

    private fun showGalleryLoadingIndicator() {
        if (_binding == null || isLoadingIndicatorVisible) return
        isLoadingIndicatorVisible = true
        binding.galleryLoadingIndicator.apply {
            animate().cancel()
            visibility = View.VISIBLE
            alpha = 0f
            scaleX = 0.9f
            scaleY = 0.9f
            animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).start()
        }
    }

    private fun hideGalleryLoadingIndicator() {
        if (_binding == null || !isLoadingIndicatorVisible) return
        isLoadingIndicatorVisible = false
        binding.galleryLoadingIndicator.apply {
            if (visibility == View.VISIBLE) {
                animate().cancel()
                animate().alpha(0f).scaleX(0.9f).scaleY(0.9f).setDuration(150)
                    .withEndAction {
                        if (_binding != null && !isLoadingIndicatorVisible) {
                            visibility = View.GONE
                        }
                    }
                    .start()
            }
        }
    }

    fun getGalleryItems(): List<top.maary.darkbag.models.ImageGroup> {
        return if (::galleryAdapter.isInitialized) galleryAdapter.currentList else emptyList()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        lastSubmittedItems = null
        isDeletingIndicatorVisible = false
        isLoadingIndicatorVisible = false
        _binding = null
    }

    companion object {
        private const val TAG = "DarkroomGalleryFragment"
    }
}
