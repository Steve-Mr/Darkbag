package top.maary.darkbag.fragments

import android.graphics.Bitmap
import android.net.Uri
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
import androidx.navigation.fragment.navArgs
import androidx.viewpager2.widget.ViewPager2
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import top.maary.darkbag.R
import top.maary.darkbag.databinding.FragmentImageViewerBinding
import top.maary.darkbag.models.EditConfig
import top.maary.darkbag.models.ImageGroup
import top.maary.darkbag.processor.ColorProcessor
import top.maary.darkbag.repository.ImageRepository
import top.maary.darkbag.repository.SettingsRepository
import top.maary.darkbag.utils.ImageUtils
import top.maary.darkbag.utils.LutManager
import top.maary.darkbag.viewer.ViewerMediaPreloadDelegate
import top.maary.darkbag.viewer.bento.ViewerBentoDeckCoordinator
import top.maary.darkbag.viewer.chrome.ViewerChromeAndInsetsDelegate
import top.maary.darkbag.viewer.deletion.ViewerDeletionController
import top.maary.darkbag.viewer.editor.DarkroomEditorPanelDelegate
import top.maary.darkbag.viewer.editor.DarkroomEditorUserIntent
import top.maary.darkbag.viewer.editor.DarkroomEditorViewModel
import top.maary.darkbag.viewer.export.ViewerExportCoordinator
import top.maary.darkbag.viewer.strategy.ViewerMediaStrategy
import top.maary.darkbag.viewer.strategy.ViewerMediaStrategyFactory
import top.maary.darkbag.viewer.strategy.ViewerSaveAction

/**
 * 图像查看器 Fragment (ImageViewerFragment).
 * 统一承载单张大图、多焦段多摄、运动照片、RAW 视频及 CinemaDNG 序列的渲染播放。
 * 遵循 Invariant 1 至 Invariant 5，具体导出、元数据查看、删除管理、暗房调节、Bento 底栏、
 * Edge-to-Edge 外观栏及媒体预加载分别委托给专用控制器与代理。
 */
open class ImageViewerFragment : Fragment() {

    protected var _binding: FragmentImageViewerBinding? = null
    protected val binding get() = _binding!!
    protected val args: ImageViewerFragmentArgs by navArgs()
    protected val editorViewModel: DarkroomEditorViewModel by viewModels()

    protected lateinit var mediaStrategy: ViewerMediaStrategy
    protected lateinit var repository: ImageRepository
    protected lateinit var adapter: ImageViewerAdapter
    protected lateinit var lutManager: LutManager

    lateinit var exportCoordinator: ViewerExportCoordinator
    lateinit var deletionController: ViewerDeletionController
    lateinit var editorPanelDelegate: DarkroomEditorPanelDelegate
    lateinit var bentoDeckCoordinator: ViewerBentoDeckCoordinator
    lateinit var chromeDelegate: ViewerChromeAndInsetsDelegate
    lateinit var mediaPreloadDelegate: ViewerMediaPreloadDelegate

    protected open val isGallerySupported: Boolean
        get() = if (::mediaStrategy.isInitialized) mediaStrategy.isGallerySupported else true
    private var imagesCollectJob: Job? = null

    var isMotionPhotoAutoPlay = true
    protected var hasAutoPlayedPosition = -1

    protected var isAdjusted = false
    protected var isEditingAdjustments = false
    private var isSaving = false
    protected var currentCinemaDngFrame: Pair<Int, Uri?>? = null

    protected var currentEditConfig: EditConfig? = null
    protected var sourceDngBytes: ByteArray? = null
    protected var sourceDngBytes2: ByteArray? = null
    private var lastCompositeBitmap: Bitmap? = null
    private var isLongPressing = false

    private val deleteLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (::deletionController.isInitialized) deletionController.handleDeleteResult(result.resultCode)
    }

    protected val pageChangeCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) {
            super.onPageSelected(position)
            if (!::adapter.isInitialized || position >= adapter.itemCount) return
            currentCinemaDngFrame = null
            lastCompositeBitmap = null
            sourceDngBytes = null
            sourceDngBytes2 = null
            mediaPreloadDelegate.handlePageSelected(position) { pos, total ->
                chromeDelegate.updateGalleryPill(pos, total)
            }
        }

        override fun onPageScrollStateChanged(state: Int) {
            super.onPageScrollStateChanged(state)
            if (state == ViewPager2.SCROLL_STATE_DRAGGING && ::adapter.isInitialized) adapter.stopAllMotionVideos()
        }
    }

    private val backPressedCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (::editorPanelDelegate.isInitialized && editorPanelDelegate.dismissPillPopup()) return
            if (isEditingAdjustments) exitEditMode(apply = false) else if (isAdjusted) chromeDelegate.showDiscardChangesDialog()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentImageViewerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        isMotionPhotoAutoPlay = SettingsRepository.getInstance(requireContext()).current().viewerMotionPhotoAutoPlay

        repository = ImageRepository(requireContext())
        lutManager = LutManager(requireContext())
        mediaStrategy = ViewerMediaStrategyFactory.create(arguments)

        exportCoordinator = ViewerExportCoordinator(requireContext(), viewLifecycleOwner.lifecycleScope, layoutInflater)
        deletionController = ViewerDeletionController(
            context = requireContext(),
            scope = viewLifecycleOwner.lifecycleScope,
            repository = repository,
            mediaStrategy = mediaStrategy,
            launchDeleteRequest = { deleteLauncher.launch(it) },
            onNavigateUp = { findNavController().navigateUp() },
            onReloadImages = { nextUri -> loadImages(nextUri, forceRefresh = true) },
            onUpdateAdapterGroups = { list ->
                adapter.updateGroups(list)
                updateControlsVisibility()
            },
            getAdapterItemCount = { if (::adapter.isInitialized) adapter.itemCount else 0 },
            getAdapterGroupAt = { idx -> if (::adapter.isInitialized && idx in 0 until adapter.itemCount) adapter.getGroup(idx) else null },
            getCurrentItemIndex = { if (_binding != null) binding.imagePager.currentItem else 0 }
        )

        bentoDeckCoordinator = ViewerBentoDeckCoordinator(
            binding = binding,
            getAdapter = { if (::adapter.isInitialized) adapter else null },
            getCurrentItemIndex = { if (_binding != null) binding.imagePager.currentItem else 0 },
            onControlsVisibilityRequested = { updateControlsVisibility() },
            onMultiCamCollageRequested = { grp -> chromeDelegate.showMultiCamCollageDialog(grp) },
            onOpenExportHubRequested = { openExportHub() },
            onFormatToggleRequested = {
                adapter.cycleOrToggleFormat(binding.imagePager.currentItem)
                updateControlsVisibility()
            },
            onDerivativeCycleRequested = {
                adapter.cycleDerivativeVersion(binding.imagePager.currentItem)
                updateControlsVisibility()
            },
            onMotionLiveToggleRequested = { mediaPreloadDelegate.handleMotionPhotoIndicatorTapped(binding.imagePager.currentItem) }
        )

        chromeDelegate = ViewerChromeAndInsetsDelegate(
            context = requireContext(), binding = binding, fragmentManager = childFragmentManager,
            getAdapter = { if (::adapter.isInitialized) adapter else null },
            isGallerySupported = { isGallerySupported }, isAdjusted = { isAdjusted },
            isEditingAdjustments = { isEditingAdjustments }, isSaving = { isSaving },
            isLutListVisible = { ::editorPanelDelegate.isInitialized && binding.lutListContainer.visibility == View.VISIBLE },
            onBackNavigate = { findNavController().navigateUp() },
            onDismissPillPopup = { ::editorPanelDelegate.isInitialized && editorPanelDelegate.dismissPillPopup() },
            onExitEditMode = { exitEditMode(it) }, onEnterEditMode = { enterEditMode() },
            onResetAdjustments = { resetAdjustments() }, onSaveEdit = { saveEdit(it) },
            onShowSaveMenu = { showSaveMenu(it) }, onOpenExportHub = { openExportHub() },
            onPerformShare = { performShare() }, onPerformShareAsTiff = { performShareAsTiff() },
            onShowLutMenu = { editorPanelDelegate.showLutMenu(it) },
            onShowDeleteDialog = { showDeleteDialog(it) },
            onUpdateBackPressedEnabled = { backPressedCallback.isEnabled = it }
        )

        mediaPreloadDelegate = ViewerMediaPreloadDelegate(
            context = requireContext(),
            binding = binding,
            lifecycleScope = viewLifecycleOwner.lifecycleScope,
            repository = repository,
            editorViewModel = editorViewModel,
            bentoDeckCoordinator = bentoDeckCoordinator,
            chromeDelegate = chromeDelegate,
            getAdapter = { if (::adapter.isInitialized) adapter else null },
            isBindingAlive = { _binding != null && ::adapter.isInitialized },
            isMotionPhotoAutoPlay = { isMotionPhotoAutoPlay },
            setMotionPhotoAutoPlay = { isMotionPhotoAutoPlay = it },
            getHasAutoPlayedPosition = { hasAutoPlayedPosition },
            setHasAutoPlayedPosition = { hasAutoPlayedPosition = it },
            isAdjusted = { isAdjusted },
            getLastCompositeBitmap = { lastCompositeBitmap },
            setLongPressing = { isLongPressing = it },
            getSourceDngBytes = { Pair(sourceDngBytes, sourceDngBytes2) },
            setSourceDngBytes = { b1, b2 -> sourceDngBytes = b1; sourceDngBytes2 = b2 },
            setCurrentCinemaDngFrame = { currentCinemaDngFrame = it },
            onPrepareEditConfig = { prepareEditConfig(it) },
            onUpdateControlsVisibility = { updateControlsVisibility() },
            onUpdateFormatBentoRow = { updateFormatBentoRow() },
            onOpenDarkroomGallery = { openDarkroomGallery() }
        )

        editorPanelDelegate = DarkroomEditorPanelDelegate(
            binding = binding,
            viewModel = editorViewModel,
            layoutInflater = layoutInflater,
            lifecycleScope = viewLifecycleOwner.lifecycleScope,
            ensureDngBytesLoaded = { mediaPreloadDelegate.ensureDngBytesLoaded() },
            onMarkAdjusted = { markAdjusted() },
            onViewportPaddingRequested = { chromeDelegate.updateViewportPadding() },
            onEditStateChanged = { isEditing, isAdjustedState ->
                this.isEditingAdjustments = isEditing
                this.isAdjusted = isAdjustedState
                if (::adapter.isInitialized) {
                    binding.imagePager.isUserInputEnabled = !isEditing && !isAdjustedState
                    adapter.setFormatSwitcherPersistentHidden(isEditing || isAdjustedState)
                }
                chromeDelegate.updateSplitButtons()
                chromeDelegate.updateToolbarIcon()
                chromeDelegate.updateBackPressedCallbackState()
            },
            onBackPressedCallbackStateChanged = { chromeDelegate.updateBackPressedCallbackState() }
        )

        bentoDeckCoordinator.setupListeners()
        editorPanelDelegate.setup()
        chromeDelegate.setupEdgeToEdge()
        chromeDelegate.setupToolbar()

        binding.btnGalleryPill.setOnClickListener { openDarkroomGallery() }

        if (savedInstanceState == null && args.startInGalleryMode) {
            openDarkroomGallery()
            return
        }

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backPressedCallback)
        setupResultListeners()
        observeEditorUiState()

        binding.imagePager.registerOnPageChangeCallback(pageChangeCallback)
        loadImages(forceRefresh = false)

        viewLifecycleOwner.lifecycleScope.launch {
            ColorProcessor.backgroundSaveFlow.collect { event ->
                if (!isAdjusted && ::adapter.isInitialized) {
                    mediaPreloadDelegate.handleBackgroundSaveEvent(event)
                } else if (!isAdjusted && !::adapter.isInitialized) {
                    loadImages(targetUri = event.targetUri ?: args.initialUri, forceRefresh = true)
                }
            }
        }
    }

    private fun observeEditorUiState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                editorViewModel.uiState.collect { state ->
                    editorPanelDelegate.render(state)
                    if (state.activeConfig != null) currentEditConfig = state.activeConfig
                    if (state.previewBitmap != null && state.previewBitmap != lastCompositeBitmap) {
                        lastCompositeBitmap = state.previewBitmap
                        if (!isLongPressing && ::adapter.isInitialized) {
                            val cur = binding.imagePager.currentItem
                            if (cur in 0 until adapter.itemCount) adapter.setManualBitmap(cur, state.previewBitmap)
                        }
                    }
                    if (state.activeConfig != null && ::adapter.isInitialized && adapter.itemCount > 0) {
                        val cur = binding.imagePager.currentItem
                        if (cur in 0 until adapter.itemCount) {
                            val g = adapter.getGroup(cur)
                            if (g.isRawVideo || g.rawVideoUri != null) adapter.updateRawVideoAdjustments(cur, state.activeConfig)
                        }
                    }
                }
            }
        }
    }

    private fun setupResultListeners() {
        childFragmentManager.setFragmentResultListener(HalfFrameShareSheet.REQUEST_KEY, viewLifecycleOwner) { _, bundle ->
            bundle.getParcelableArrayList<Uri>(HalfFrameShareSheet.BUNDLE_KEY_URIS)?.let { exportCoordinator.shareImages(it) }
        }

        childFragmentManager.setFragmentResultListener(DarkbagBatchDeleteSheet.REQUEST_KEY, viewLifecycleOwner) { _, bundle ->
            val mode = bundle.getInt(DarkbagBatchDeleteSheet.BUNDLE_KEY_DELETE_MODE, 0)
            deletionController.pendingSingleDeleteGroup?.let { group ->
                val derivatives = adapter.getDerivativeUris(group)
                val activeIdx = adapter.getSelectedDerivativeIndex(group.baseName).coerceIn(0, (derivatives.size - 1).coerceAtLeast(0))
                deletionController.executeSingleGroupDelete(group, mode, derivatives, activeIdx)
                deletionController.pendingSingleDeleteGroup = null
            }
        }

        childFragmentManager.setFragmentResultListener(RawVideoExportSheet.REQUEST_KEY, viewLifecycleOwner) { _, bundle ->
            val curGroup = adapter.getGroup(binding.imagePager.currentItem)
            val curFrame = currentCinemaDngFrame ?: adapter.getActiveCinemaDngFrame(binding.imagePager.currentItem)
            exportCoordinator.handleExportSheetResult(
                bundle = bundle,
                currentGroup = curGroup,
                currentEditConfig = currentEditConfig,
                frameInfo = curFrame,
                repository = repository,
                onStartExport = { setBusyLoading(true) },
                onEndExport = { setBusyLoading(false) },
                onRefreshGroup = { mediaPreloadDelegate.refreshCurrentGroupAndAdapter(it) }
            )
        }
    }

    private fun attachInitialAdapter(groups: List<ImageGroup>, disableInputWhenAdjusted: Boolean = false) {
        adapter = mediaPreloadDelegate.createViewerAdapter(groups)
        binding.imagePager.adapter = adapter
        if (disableInputWhenAdjusted) binding.imagePager.isUserInputEnabled = !isAdjusted
        chromeDelegate.setupActionButtons()
    }

    private fun showLoadedPager() {
        binding.initialLoadingIndicator.visibility = View.GONE
        binding.imagePager.visibility = View.VISIBLE
    }

    protected open fun loadImages(targetUri: String? = args.initialUri, forceRefresh: Boolean = false) {
        binding.initialLoadingIndicator.visibility = View.VISIBLE
        binding.imagePager.visibility = View.INVISIBLE

        if (::mediaStrategy.isInitialized && mediaStrategy.isSandbox) {
            viewLifecycleOwner.lifecycleScope.launch {
                val groups = mediaStrategy.loadInitialGroups(requireContext(), arguments, repository)
                if (!groups.isNullOrEmpty()) {
                    if (binding.imagePager.adapter == null) {
                        attachInitialAdapter(groups, disableInputWhenAdjusted = true)
                        updateControlsVisibility()
                    } else {
                        adapter.updateGroups(groups)
                    }
                    showLoadedPager()
                }
            }
            return
        }

        if (forceRefresh) repository.invalidateCache(clearStale = true)
        var hasNavigated = false
        imagesCollectJob?.cancel()
        imagesCollectJob = lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.getGroupedImagesFlow(targetUri).collect { groups ->
                    if (groups.isEmpty()) {
                        findNavController().navigateUp()
                        return@collect
                    }
                    val isFirst = !::adapter.isInitialized
                    val targetBase = targetUri?.let { ImageUtils.getBaseName(it) }
                    val targetPosArg = arguments?.getInt("target_position", -1) ?: -1
                    val targetPos = if (targetPosArg >= 0 && isFirst) targetPosArg else groups.indexOfFirst { mediaPreloadDelegate.matchesTarget(it, targetUri, targetBase) }

                    if (isFirst) {
                        attachInitialAdapter(groups)
                        if (targetPos in groups.indices) {
                            binding.imagePager.setCurrentItem(targetPos, false)
                            hasNavigated = true
                        }
                        val initPos = binding.imagePager.currentItem
                        if (initPos in groups.indices) {
                            prepareEditConfig(groups[initPos])
                            chromeDelegate.updateGalleryPill(initPos, groups.size)
                        }
                        showLoadedPager()
                        updateControlsVisibility()
                    } else {
                        val curBase = if (binding.imagePager.currentItem in adapter.getGroups().indices) adapter.getGroup(binding.imagePager.currentItem).baseName else null
                        adapter.updateGroups(groups) {
                            if (_binding == null) return@updateGroups
                            chromeDelegate.updateGalleryPill(binding.imagePager.currentItem, groups.size)
                            if (targetUri != null && !hasNavigated && targetPos in groups.indices) {
                                binding.imagePager.setCurrentItem(targetPos, false)
                                hasNavigated = true
                            } else if (curBase != null) {
                                val np = groups.indexOfFirst { it.baseName == curBase }
                                if (np != -1 && np != binding.imagePager.currentItem) binding.imagePager.setCurrentItem(np, false)
                            }
                            if (binding.imagePager.currentItem in groups.indices) prepareEditConfig(groups[binding.imagePager.currentItem])
                            updateControlsVisibility()
                        }
                        showLoadedPager()
                    }
                }
            }
        }
    }

    protected fun updateControlsVisibility() {
        if (!::adapter.isInitialized || adapter.itemCount == 0) return
        val cur = binding.imagePager.currentItem
        if (cur !in 0 until adapter.itemCount) return
        val currentGroup = adapter.getGroup(cur)

        updateFormatBentoRow()
        val isHalfFrame = currentGroup.isHalfFrame()
        binding.btnSeparateDng.visibility = if (isHalfFrame && adapter.getSelectedFormat(cur) == "DNG") View.VISIBLE else View.GONE
        binding.btnAdjustTofu.visibility = if (isAdjusted || !isHalfFrame) View.GONE else View.VISIBLE
        chromeDelegate.updateSplitButtons()
        chromeDelegate.updateToolbarIcon()
    }

    protected fun updateFormatBentoRow() {
        if (::bentoDeckCoordinator.isInitialized) bentoDeckCoordinator.updateFormatBentoRow(isAdjusted, isMotionPhotoAutoPlay, currentCinemaDngFrame)
    }

    protected fun prepareEditConfig(group: ImageGroup) {
        currentEditConfig = group.editConfig ?: EditConfig(log = "None", lut = null, hfLayout = group.hfLayout)
        editorViewModel.processIntent(DarkroomEditorUserIntent.SetActiveGroup(group))
    }

    protected open fun showSaveMenu(anchor: View) {
        chromeDelegate.showSaveMenu(anchor, mediaStrategy) { action ->
            when (action) {
                ViewerSaveAction.SAVE_REPLACE -> saveEdit(isReplacement = true)
                ViewerSaveAction.SAVE_AS_NEW -> saveEdit(isReplacement = false)
                ViewerSaveAction.SHARE_TIFF -> performShareAsTiff()
                ViewerSaveAction.EXPORT_JPG_TO_PICTURES -> exportToJpg()
            }
        }
    }

    private fun setBusyLoading(busy: Boolean, blockInteraction: Boolean = true) {
        binding.initialLoadingIndicator.visibility = if (busy) View.VISIBLE else View.GONE
        if (blockInteraction) {
            binding.interactionBlocker?.visibility = if (busy) View.VISIBLE else View.GONE
        }
    }

    private fun exportToJpg() {
        val config = currentEditConfig ?: return
        exportCoordinator.exportToJpg(
            group = adapter.getGroup(binding.imagePager.currentItem), config = config,
            isEditingAdjustments = isEditingAdjustments, cachedBytes1 = sourceDngBytes, cachedBytes2 = sourceDngBytes2,
            repository = repository, onStartExport = { setBusyLoading(true) },
            onEndExport = { setBusyLoading(false) }, onSaveReplacement = { saveEdit(isReplacement = true) }
        )
    }

    protected fun markAdjusted() {
        if (!isAdjusted) {
            isAdjusted = true
            adapter.setFormatSwitcherPersistentHidden(true)
            chromeDelegate.updateSplitButtons()
            chromeDelegate.updateToolbarIcon()
            chromeDelegate.updateBackPressedCallbackState()
        }
    }

    protected fun resetAdjustments() {
        isAdjusted = false
        editorPanelDelegate.exitEditMode(apply = false)
        binding.imagePager.isUserInputEnabled = true
        adapter.setFormatSwitcherPersistentHidden(false)
        sourceDngBytes = null
        sourceDngBytes2 = null
        lastCompositeBitmap = null
        currentEditConfig = null
        editorViewModel.processIntent(DarkroomEditorUserIntent.ResetAdjustments)

        val cur = binding.imagePager.currentItem
        adapter.notifyItemChanged(cur)
        prepareEditConfig(adapter.getGroup(cur))
        updateControlsVisibility()
    }

    private fun openExportHub() {
        if (!::adapter.isInitialized || adapter.itemCount == 0) return
        val cur = binding.imagePager.currentItem
        val frameInfo = currentCinemaDngFrame ?: adapter.getActiveCinemaDngFrame(cur)
        exportCoordinator.openExportHub(adapter.getGroup(cur), currentEditConfig, frameInfo?.first ?: 0, repository, childFragmentManager)
    }

    private fun performShare() {
        if (!::adapter.isInitialized || adapter.itemCount == 0) return
        val cur = binding.imagePager.currentItem
        exportCoordinator.shareGroupMedia(adapter.getGroup(cur), adapter.getSelectedFormat(cur)) {
            chromeDelegate.showHalfFrameShareSheet(it)
        }
    }

    protected fun enterEditMode() {
        if (!chromeDelegate.isUiVisible) chromeDelegate.showUi()
        if (!isEditingAdjustments) {
            val currentGroup = if (::adapter.isInitialized && binding.imagePager.currentItem in adapter.getGroups().indices) adapter.getGroup(binding.imagePager.currentItem) else null
            editorPanelDelegate.enterEditMode(currentGroup)
        }
    }

    private fun exitEditMode(apply: Boolean) = editorPanelDelegate.exitEditMode(apply)

    protected open fun saveEdit(isReplacement: Boolean) {
        if (isSaving) return
        val config = currentEditConfig ?: return
        val (c1, c2) = editorViewModel.getCachedDngBytes()
        isSaving = true

        exportCoordinator.saveEdit(
            group = adapter.getGroup(binding.imagePager.currentItem), config = config,
            isReplacement = isReplacement, cachedBytes1 = c1 ?: sourceDngBytes, cachedBytes2 = c2 ?: sourceDngBytes2,
            repository = repository, mediaStrategy = mediaStrategy,
            onStartSave = { setBusyLoading(true) },
            onEndSave = { setBusyLoading(false); isSaving = false },
            onSuccess = {
                resetAdjustments()
                binding.root.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
                if (mediaStrategy.isSandbox) {
                    mediaStrategy.loadInitialGroups(requireContext().applicationContext, arguments, repository)?.let { adapter.updateGroups(it) }
                } else {
                    val targetUriStr = if (isReplacement) adapter.getGroup(binding.imagePager.currentItem).jpgUri?.toString() else null
                    repository.invalidateCache(clearStale = true)
                    loadImages(targetUriStr, forceRefresh = true)
                }
            }
        )
    }

    protected open fun performShareAsTiff() {
        val config = currentEditConfig ?: return
        exportCoordinator.performShareAsTiff(
            group = adapter.getGroup(binding.imagePager.currentItem), config = config,
            isEditingAdjustments = isEditingAdjustments, cachedBytes1 = sourceDngBytes, cachedBytes2 = sourceDngBytes2,
            repository = repository, onStartRender = { setBusyLoading(true, blockInteraction = false) },
            onEndRender = { setBusyLoading(false, blockInteraction = false) }, onSaveReplacement = { saveEdit(isReplacement = true) }
        )
    }

    private fun showDeleteDialog(group: ImageGroup) {
        val derivatives = adapter.getDerivativeUris(group)
        val activeIdx = adapter.getSelectedDerivativeIndex(group.baseName).coerceIn(0, (derivatives.size - 1).coerceAtLeast(0))
        deletionController.showDeleteDialog(group, derivatives, activeIdx, childFragmentManager)
    }

    private fun openDarkroomGallery() {
        if (!isGallerySupported) return
        val pos = if (::adapter.isInitialized && adapter.itemCount > 0) binding.imagePager.currentItem else 0
        val currentGroup = if (::adapter.isInitialized && pos in adapter.getGroups().indices) adapter.getGroup(pos) else null
        val uri = currentGroup?.firstAvailableUri ?: currentGroup?.jpgUri ?: currentGroup?.dngUri
        val action = ImageViewerFragmentDirections.actionImageViewerToDarkroomGallery(initialUri = uri?.toString())
        findNavController().navigate(action)
    }

    override fun onPause() {
        super.onPause()
        if (::adapter.isInitialized) adapter.stopAllMotionVideos()
    }

    override fun onDestroyView() {
        exportCoordinator.dismissActiveDialog()
        editorPanelDelegate.onDestroy()
        if (::adapter.isInitialized) adapter.stopAllMotionVideos()
        binding.imagePager.unregisterOnPageChangeCallback(pageChangeCallback)
        super.onDestroyView()
        _binding = null
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        deletionController.pendingDeleteNextTargetUri?.let { outState.putString("pendingDeleteNextTargetUri", it) }
    }

    companion object {
        val LOOK_DOT_COLORS = ViewerBentoDeckCoordinator.LOOK_DOT_COLORS
    }
}
