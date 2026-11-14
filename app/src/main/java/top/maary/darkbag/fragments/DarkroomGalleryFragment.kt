package top.maary.darkbag.fragments

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.maary.darkbag.R
import top.maary.darkbag.databinding.FragmentDarkroomGalleryBinding
import top.maary.darkbag.models.ImageGroup
import top.maary.darkbag.repository.AlbumStatsCache
import top.maary.darkbag.repository.ImageRepository
import top.maary.darkbag.utils.GalleryCollageHelper
import top.maary.darkbag.utils.GalleryHomogeneityChecker
import top.maary.darkbag.utils.LutManager
import top.maary.darkbag.utils.MediaStoreUtils
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
        if (result.resultCode != Activity.RESULT_OK) {
            context?.let { Toast.makeText(it, R.string.gallery_delete_prompt_failed, Toast.LENGTH_SHORT).show() }
            hideGalleryDeletingIndicator()
            if (_binding != null) {
                binding.galleryRecyclerView.isEnabled = true
                updateGallerySelectionUI(galleryViewModel.uiState.value)
            }
        }
        lifecycleScope.launch {
            repository.invalidateCache(clearStale = true)
            galleryViewModel.processIntent(DarkroomGalleryUserIntent.LoadImages(null))
        }
    }

    private val importGalleryMediaLauncher = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) {
            lifecycleScope.launch {
                showGalleryLoadingIndicator()
                val importedCount = withContext(Dispatchers.IO) {
                    val cr = requireContext().contentResolver
                    var count = 0
                    for (uri in uris) {
                        try {
                            if (top.maary.darkbag.utils.DarkbagIdentity.isDarkbagUri(uri, cr)) {
                                count++
                            }
                        } catch (e: Exception) {
                            Log.e("DarkroomGallery", "Error inspecting import URI $uri", e)
                        }
                    }
                    count
                }
                hideGalleryLoadingIndicator()
                repository.invalidateCache(clearStale = true)
                galleryViewModel.processIntent(DarkroomGalleryUserIntent.LoadImages(null))
                Toast.makeText(requireContext(), getString(R.string.gallery_import_success, importedCount), Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requireActivity().onBackPressedDispatcher.addCallback(this, backPressedCallback)
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

        setupWindowInsets()
        setupGalleryView()
        observeViewModel()

        galleryViewModel.processIntent(DarkroomGalleryUserIntent.LoadImages(args.initialUri))
    }

    private fun setupWindowInsets() {
        val density = resources.displayMetrics.density
        val topClearance = (72 * density).toInt()
        val bottomClearance = (84 * density).toInt()

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.galleryHeaderCard.updatePadding(top = 0)
            binding.galleryRecyclerView.updatePadding(
                top = systemBars.top + topClearance,
                bottom = systemBars.bottom + bottomClearance
            )
            binding.galleryBottomBar.translationY = -systemBars.bottom.toFloat()
            insets
        }
    }

    private fun setupGalleryView() {
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        galleryViewModel.processIntent(DarkroomGalleryUserIntent.SyncOrientation(isLandscape))
        val currentLayout = galleryViewModel.uiState.value

        galleryAdapter = DarkbagGalleryGridAdapter(requireContext(), repository).apply {
            columnCount = currentLayout.columnCount
            isWaterfallMode = currentLayout.isWaterfallMode
            onItemClick = { group, pos, lensTag ->
                if (galleryViewModel.uiState.value.isSelectionMode) {
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
            onItemLongClick = { group, _, lensTag ->
                if (lensTag != null) {
                    val lens = group.multiCameraLenses.find { it.lensTag == lensTag }
                    if (lens != null) {
                        galleryViewModel.processIntent(DarkroomGalleryUserIntent.ToggleLensSelection(group, lens))
                    }
                } else {
                    galleryViewModel.processIntent(DarkroomGalleryUserIntent.ToggleSingleSelection(group))
                }
            }
            onSelectionChanged = {
                updateGallerySelectionUI(galleryViewModel.uiState.value)
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

        binding.btnGalleryDockLayout.text = getString(R.string.gallery_layout_cols, currentLayout.columnCount)
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
                performMergeCollage(selected)
            } else {
                Toast.makeText(requireContext(), R.string.gallery_merge_need_two, Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnGalleryDockBatchGrade.setOnClickListener {
            val selected = galleryViewModel.uiState.value.getSelectedItems()
            if (selected.isEmpty()) return@setOnClickListener
            if (checkHomogeneity(selected)) {
                performBatchGrade(selected)
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
                shareImages(uris)
            }
        }

        binding.btnGalleryDockDelete.setOnClickListener {
            val selected = galleryViewModel.uiState.value.getSelectedItems()
            if (selected.isNotEmpty()) {
                showBatchDeleteDialog(selected)
            }
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    galleryViewModel.uiState.collect { state ->
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

                        galleryAdapter.submitList(state.items) {
                            if (_binding != null) {
                                binding.tvGalleryHeaderSubtitle.text = getString(R.string.gallery_groups_count, state.items.size)
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

        if (state.isSelectionMode) {
            binding.galleryHeaderCard.setContainerRole(state.containerRole, animated = true)
            binding.btnGalleryHeaderAction.setIconResource(R.drawable.ic_close)
            binding.tvGalleryHeaderTitle.text = getString(R.string.gallery_album_darkroom)
            binding.tvGalleryHeaderSubtitle.text = getString(R.string.gallery_selected_count, state.selectedCount)
            binding.btnGalleryHeaderInfo.visibility = View.GONE
            binding.btnGalleryHeaderSelectAll.visibility = View.VISIBLE

            binding.btnGalleryHeaderSelectAll.setText(if (state.isAllSelected) R.string.gallery_deselect_all else R.string.gallery_select_all)

            binding.galleryBottomBar.visibility = View.VISIBLE
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

            binding.galleryBottomBar.visibility = View.VISIBLE
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

    private fun performMergeCollage(selected: List<GallerySelectedItem>) {
        if (selected.size != 2) return
        val options = listOf(
            getString(R.string.collage_layout_sbs),
            getString(R.string.collage_layout_tb)
        )
        top.maary.darkbag.ui.BentoSelectionBottomSheet.show(
            fragmentManager = childFragmentManager,
            title = getString(R.string.gallery_action_merge),
            options = options,
            selectedIndex = 0,
            badge = "COLLAGE"
        ) { which ->
            val isSideBySide = (which == 0)
            val ctx = context ?: return@show

            val item1 = selected[0]
            val item2 = selected[1]
            val uri1 = item1.specificLens?.jpgUri ?: item1.group.firstAvailableUri ?: item1.group.jpgUri
            val uri2 = item2.specificLens?.jpgUri ?: item2.group.firstAvailableUri ?: item2.group.jpgUri

            if (uri1 == null || uri2 == null) {
                Toast.makeText(ctx, R.string.gallery_collage_resolve_failed, Toast.LENGTH_SHORT).show()
                return@show
            }

            showGalleryDeletingIndicator(2)
            binding.galleryRecyclerView.isEnabled = false

            lifecycleScope.launch {
                val insertedUri = GalleryCollageHelper.createAndSaveCollage(
                    context = ctx,
                    uri1 = uri1,
                    uri2 = uri2,
                    isSideBySide = isSideBySide
                )

                hideGalleryDeletingIndicator()
                if (_binding != null) {
                    binding.galleryRecyclerView.isEnabled = true
                }

                if (insertedUri != null) {
                    repository.invalidateCache()
                    galleryViewModel.processIntent(DarkroomGalleryUserIntent.LoadImages(null))
                    galleryViewModel.processIntent(DarkroomGalleryUserIntent.ClearSelection)
                    galleryAdapter.setSelectionMode(false)
                    updateGallerySelectionUI(galleryViewModel.uiState.value)
                    Toast.makeText(ctx, R.string.gallery_merge_success, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(ctx, R.string.gallery_collage_create_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun performBatchGrade(selected: List<GallerySelectedItem>) {
        val lutManager = LutManager(requireContext())
        top.maary.darkbag.ui.ColorRecipeBottomSheet.show(requireContext(), lutManager) { selectedLog, selectedLut ->
            val ctx = context ?: return@show
            val appContext = ctx.applicationContext
            showGalleryDeletingIndicator(selected.size)
            binding.galleryRecyclerView.isEnabled = false

            lifecycleScope.launch {
                val gradedCount = withContext(Dispatchers.IO) {
                    var count = 0
                    for (item in selected) {
                        val group = item.group
                        val targetConfig = (group.editConfig ?: top.maary.darkbag.models.EditConfig()).copy(
                            log = selectedLog,
                            lut = selectedLut ?: "None"
                        )
                        val uriToSave = group.firstAvailableUri ?: group.jpgUri ?: group.dngUri
                        if (uriToSave != null) {
                            try {
                                repository.saveEditConfig(uriToSave, targetConfig)
                                count++
                            } catch (e: Exception) {
                                Log.e("DarkroomGallery", "Failed to save batch grade config for $uriToSave", e)
                            }
                        }
                    }
                    count
                }

                hideGalleryDeletingIndicator()
                if (_binding != null) {
                    binding.galleryRecyclerView.isEnabled = true
                }
                repository.invalidateCache()
                galleryViewModel.processIntent(DarkroomGalleryUserIntent.LoadImages(null))
                galleryViewModel.processIntent(DarkroomGalleryUserIntent.ClearSelection)
                galleryAdapter.setSelectionMode(false)
                updateGallerySelectionUI(galleryViewModel.uiState.value)
                Toast.makeText(appContext, getString(R.string.gallery_batch_grade_success, gradedCount), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun shareImages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = "image/*"
                putExtra(Intent.EXTRA_STREAM, uris[0])
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "image/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        startActivity(Intent.createChooser(intent, getString(R.string.gallery_action_share)))
    }

    private fun showBatchDeleteDialog(selectedItems: List<GallerySelectedItem>) {
        if (selectedItems.isEmpty()) return
        val hasAnyRaw = selectedItems.any { item ->
            if (item.specificLens != null) {
                item.specificLens.dngUri != null
            } else {
                item.group.hasMasterRaw
            }
        }
        val hasAnyDerivatives = selectedItems.any { item ->
            if (item.specificLens != null) {
                item.specificLens.jpgUri != null
            } else {
                item.group.hasDerivatives
            }
        }

        val hasAnyCinemaDng = selectedItems.any { item ->
            val g = item.group
            g.isCinemaDng || g.cinemaDngFolderUri != null || g.cinemaDngFirstFrameUri != null || g.cinemaDngFrameUris.isNotEmpty()
        }

        val sheet = DarkbagBatchDeleteSheet.newInstance(selectedItems.size, hasAnyRaw, hasAnyDerivatives, hasAnyCinemaDng)
        sheet.onDeleteConfirmed = { deleteMode ->
            deleteBatchImages(selectedItems, deleteMode)
        }
        sheet.show(childFragmentManager, DarkbagBatchDeleteSheet.TAG)
    }

    private fun deleteBatchImages(selectedItems: List<GallerySelectedItem>, deleteMode: Int) {
        val context = context ?: return
        showGalleryDeletingIndicator(selectedItems.size)
        binding.galleryRecyclerView.isEnabled = false
        binding.galleryBottomBar.visibility = View.GONE

        lifecycleScope.launch {
            val urisToDelete = mutableListOf<Uri>()

            for (item in selectedItems) {
                val group = item.group
                val lens = item.specificLens
                if (lens != null) {
                    when (deleteMode) {
                        DarkbagBatchDeleteSheet.MODE_ENTIRE_GROUP -> {
                            lens.jpgUri?.let { urisToDelete.add(it) }
                            lens.dngUri?.let { urisToDelete.add(it) }
                        }
                        DarkbagBatchDeleteSheet.MODE_RAW_ONLY -> {
                            lens.dngUri?.let { urisToDelete.add(it) }
                        }
                        DarkbagBatchDeleteSheet.MODE_DERIVATIVES_ONLY -> {
                            lens.jpgUri?.let { urisToDelete.add(it) }
                        }
                        DarkbagBatchDeleteSheet.MODE_CINEMADNG_ONLY -> {}
                    }
                } else {
                    when (deleteMode) {
                        DarkbagBatchDeleteSheet.MODE_ENTIRE_GROUP -> {
                            group.cinemaDngFolderUri?.let { urisToDelete.add(it) }
                            urisToDelete.addAll(group.allUris)
                        }
                        DarkbagBatchDeleteSheet.MODE_RAW_ONLY -> {
                            group.cinemaDngFolderUri?.let { urisToDelete.add(it) }
                            urisToDelete.addAll(group.allMasterRawUris)
                        }
                        DarkbagBatchDeleteSheet.MODE_DERIVATIVES_ONLY -> {
                            urisToDelete.addAll(group.allDerivativeUris)
                        }
                        DarkbagBatchDeleteSheet.MODE_CINEMADNG_ONLY -> {
                            group.cinemaDngFolderUri?.let { urisToDelete.add(it) }
                            urisToDelete.addAll(group.allCinemaDngUris)
                        }
                    }
                }
            }

            val securityExceptionUris = mutableListOf<Uri>()
            withContext(Dispatchers.IO) {
                for (uri in urisToDelete.distinct()) {
                    try {
                        MediaStoreUtils.deleteUriSafe(context, uri)
                    } catch (e: SecurityException) {
                        if (uri.scheme == "content" && uri.authority == MediaStore.AUTHORITY) {
                            securityExceptionUris.add(uri)
                        }
                    } catch (e: Exception) {
                        Log.e("DarkroomGallery", "Failed to delete $uri", e)
                    }
                }
            }

            galleryViewModel.processIntent(DarkroomGalleryUserIntent.ClearSelection)
            galleryAdapter.setSelectionMode(false)
            updateGallerySelectionUI(galleryViewModel.uiState.value)

            if (securityExceptionUris.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    val pendingIntent = MediaStore.createDeleteRequest(context.contentResolver, securityExceptionUris)
                    val request = androidx.activity.result.IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                    deleteLauncher.launch(request)
                    return@launch
                } catch (e: Exception) {
                    Log.e("DarkroomGallery", "Failed to request batch deletion via MediaStore", e)
                }
            }

            hideGalleryDeletingIndicator()
            if (_binding != null) {
                binding.galleryRecyclerView.isEnabled = true
                binding.galleryBottomBar.visibility = View.VISIBLE
            }
            repository.invalidateCache(clearStale = true)
            galleryViewModel.processIntent(DarkroomGalleryUserIntent.LoadImages(null))
        }
    }

    private fun showGalleryDeletingIndicator(count: Int) {
        if (_binding == null) return
        binding.tvGalleryDeletingStatus.text = if (count > 0) {
            getString(R.string.gallery_deleting_count, count)
        } else {
            getString(R.string.gallery_deleting)
        }
        binding.galleryDeletingIndicator.apply {
            animate().cancel()
            visibility = View.VISIBLE
            alpha = 0f
            translationY = -40f
            animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(200)
                .start()
        }
    }

    private fun hideGalleryDeletingIndicator() {
        if (_binding == null) return
        binding.galleryDeletingIndicator.apply {
            animate().cancel()
            animate()
                .alpha(0f)
                .translationY(-40f)
                .setDuration(200)
                .withEndAction {
                    visibility = View.GONE
                }
                .start()
        }
    }

    private fun showGalleryLoadingIndicator() {
        if (_binding == null) return
        binding.galleryLoadingIndicator.apply {
            animate().cancel()
            visibility = View.VISIBLE
            alpha = 0f
            scaleX = 0.9f
            scaleY = 0.9f
            animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(180)
                .start()
        }
    }

    private fun hideGalleryLoadingIndicator() {
        if (_binding == null) return
        binding.galleryLoadingIndicator.apply {
            if (visibility == View.VISIBLE) {
                animate().cancel()
                animate()
                    .alpha(0f)
                    .scaleX(0.9f)
                    .scaleY(0.9f)
                    .setDuration(150)
                    .withEndAction {
                        visibility = View.GONE
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
        _binding = null
    }
}
