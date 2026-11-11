package top.maary.darkbag.fragments

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.viewpager2.widget.ViewPager2
import kotlinx.coroutines.*
import top.maary.darkbag.R
import top.maary.darkbag.databinding.FragmentImageViewerBinding
import top.maary.darkbag.models.EditConfig
import top.maary.darkbag.models.ImageGroup
import top.maary.darkbag.pipeline.darkroom.DarkroomRenderEngine
import top.maary.darkbag.pipeline.darkroom.model.DarkroomRenderSpec
import top.maary.darkbag.pipeline.darkroom.model.RenderOutputFormat
import top.maary.darkbag.pipeline.darkroom.sink.BitmapPreviewSink
import top.maary.darkbag.processor.ColorProcessor
import top.maary.darkbag.repository.ImageRepository
import top.maary.darkbag.ui.ZoomableImageView
import top.maary.darkbag.viewer.bento.ViewerBentoDeckCoordinator
import top.maary.darkbag.viewer.deletion.ViewerDeletionController
import top.maary.darkbag.viewer.editor.DarkroomEditorPanelDelegate
import top.maary.darkbag.viewer.editor.DarkroomEditorUserIntent
import top.maary.darkbag.viewer.editor.DarkroomEditorViewModel
import top.maary.darkbag.viewer.export.ViewerExportCoordinator
import top.maary.darkbag.viewer.metadata.ViewerMetadataSheet
import top.maary.darkbag.viewer.strategy.ViewerMediaStrategy
import top.maary.darkbag.viewer.strategy.ViewerMediaStrategyFactory
import top.maary.darkbag.viewer.strategy.ViewerSaveAction
import java.io.FileInputStream

/**
 * 图像查看器 Fragment (ImageViewerFragment).
 * 统一承载单张大图、多焦段多摄、运动照片、RAW 视频及 CinemaDNG 序列的渲染播放。
 * 遵循 Invariant 1 至 Invariant 5，具体导出、元数据查看、删除管理、暗房调节与 Bento 底栏分别委托给专用控制器与代理。
 */
open class ImageViewerFragment : Fragment() {

    protected var _binding: FragmentImageViewerBinding? = null
    protected val binding get() = _binding!!
    protected val args: ImageViewerFragmentArgs by navArgs()
    protected val editorViewModel: DarkroomEditorViewModel by viewModels()

    protected lateinit var mediaStrategy: ViewerMediaStrategy
    protected lateinit var repository: ImageRepository
    protected lateinit var adapter: ImageViewerAdapter
    protected lateinit var lutManager: top.maary.darkbag.utils.LutManager

    lateinit var exportCoordinator: ViewerExportCoordinator
    lateinit var deletionController: ViewerDeletionController
    lateinit var editorPanelDelegate: DarkroomEditorPanelDelegate
    lateinit var bentoDeckCoordinator: ViewerBentoDeckCoordinator

    protected open val isGallerySupported: Boolean get() = if (::mediaStrategy.isInitialized) mediaStrategy.isGallerySupported else true
    private var imagesCollectJob: Job? = null

    var isMotionPhotoAutoPlay = true
    protected var hasAutoPlayedPosition = -1

    protected var isAdjusted = false
    protected var isEditingAdjustments = false
    private var isSaving = false
    protected var currentCinemaDngFrame: Pair<Int, Uri?>? = null

    private var isUiVisible = true
    private var systemTopInset = 0
    private var systemBottomInset = 0
    private var topBarHeight = 0
    private var bentoDeckHeight = 0

    protected var currentEditConfig: EditConfig? = null
    protected var selectedDngIndex = 0
    protected var sourceDngBytes: ByteArray? = null
    protected var sourceDngBytes2: ByteArray? = null
    private var cachedBitmap1: Bitmap? = null
    private var cachedBitmap2: Bitmap? = null
    private var lastPreviewConfig: EditConfig? = null
    private var lastCompositeBitmap: Bitmap? = null
    private var isLongPressing = false
    protected var previewJob: Job? = null

    private val deleteLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (::deletionController.isInitialized) deletionController.handleDeleteResult(result.resultCode)
    }

    protected val pageChangeCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) {
            super.onPageSelected(position)
            if (!::adapter.isInitialized || position >= adapter.itemCount) return
            currentCinemaDngFrame = null
            lastCompositeBitmap = null
            lastPreviewConfig = null
            sourceDngBytes = null
            sourceDngBytes2 = null
            cachedBitmap1 = null
            cachedBitmap2 = null

            val currentGroup = adapter.getGroup(position)
            prepareEditConfig(currentGroup)
            updateSplitButtons()
            updateToolbarIcon()
            updateGalleryPill(position, adapter.itemCount)

            adapter.stopAllMotionVideos()
            if (isMotionPhotoAutoPlay && position != hasAutoPlayedPosition) {
                hasAutoPlayedPosition = position
                if (adapter.getSelectedFormat(position) != ImageViewerAdapter.FORMAT_DNG) {
                    adapter.playMotionVideoForPosition(position)
                }
            }

            lifecycleScope.launch {
                val fullyLoaded = repository.loadMetadata(currentGroup)
                if (fullyLoaded != currentGroup && _binding != null && ::adapter.isInitialized) {
                    val curIdx = binding.imagePager.currentItem
                    if (curIdx in 0 until adapter.itemCount && adapter.getGroup(curIdx).baseName == fullyLoaded.baseName) {
                        adapter.updateSingleGroup(fullyLoaded) {
                            if (_binding == null || !::adapter.isInitialized) return@updateSingleGroup
                            prepareEditConfig(fullyLoaded)
                            updateControlsVisibility()
                            updateSplitButtons()
                            updateToolbarIcon()
                        }
                    }
                }
            }
            updateControlsVisibility()
            preloadAdjacentMetadata(position)
        }

        override fun onPageScrollStateChanged(state: Int) {
            super.onPageScrollStateChanged(state)
            if (state == ViewPager2.SCROLL_STATE_DRAGGING && ::adapter.isInitialized) adapter.stopAllMotionVideos()
        }
    }

    private val backPressedCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (::editorPanelDelegate.isInitialized && editorPanelDelegate.dismissPillPopup()) return
            if (isEditingAdjustments) exitEditMode(apply = false) else if (isAdjusted) showDiscardChangesDialog()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentImageViewerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val prefs = requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        isMotionPhotoAutoPlay = prefs.getBoolean(KEY_VIEWER_MOTION_PHOTO_AUTO_PLAY, true)

        repository = ImageRepository(requireContext())
        lutManager = top.maary.darkbag.utils.LutManager(requireContext())
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
            onMultiCamCollageRequested = { grp -> showMultiCamCollageDialog(grp) },
            onOpenExportHubRequested = { openExportHub() },
            onFormatToggleRequested = {
                adapter.cycleOrToggleFormat(binding.imagePager.currentItem)
                updateControlsVisibility()
            },
            onDerivativeCycleRequested = {
                adapter.cycleDerivativeVersion(binding.imagePager.currentItem)
                updateControlsVisibility()
            },
            onMotionLiveToggleRequested = { handleMotionPhotoIndicatorTapped(binding.imagePager.currentItem) }
        )

        editorPanelDelegate = DarkroomEditorPanelDelegate(
            binding = binding,
            viewModel = editorViewModel,
            layoutInflater = layoutInflater,
            lifecycleScope = viewLifecycleOwner.lifecycleScope,
            ensureDngBytesLoaded = { ensureDngBytesLoaded() },
            onMarkAdjusted = { markAdjusted() },
            onViewportPaddingRequested = { updateViewportPadding() },
            onEditStateChanged = { isEditing, isAdjustedState ->
                this.isEditingAdjustments = isEditing
                this.isAdjusted = isAdjustedState
                if (::adapter.isInitialized) {
                    binding.imagePager.isUserInputEnabled = !isEditing && !isAdjustedState
                    adapter.setFormatSwitcherPersistentHidden(isEditing || isAdjustedState)
                }
                updateSplitButtons()
                updateToolbarIcon()
                updateBackPressedCallbackState()
            },
            onBackPressedCallbackStateChanged = { updateBackPressedCallbackState() }
        )

        bentoDeckCoordinator.setupListeners()
        editorPanelDelegate.setup()
        setupEdgeToEdge()
        setupToolbar()

        binding.btnGalleryPill.setOnClickListener { openDarkroomGallery() }

        if (savedInstanceState == null && args.startInGalleryMode) {
            openDarkroomGallery()
            return
        }

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backPressedCallback)
        setupResultListeners()

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

        binding.imagePager.registerOnPageChangeCallback(pageChangeCallback)
        loadImages(forceRefresh = false)

        viewLifecycleOwner.lifecycleScope.launch {
            ColorProcessor.backgroundSaveFlow.collect { event ->
                if (!isAdjusted && ::adapter.isInitialized) handleBackgroundSaveEvent(event)
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
                onStartExport = {
                    binding.initialLoadingIndicator.visibility = View.VISIBLE
                    binding.interactionBlocker?.visibility = View.VISIBLE
                },
                onEndExport = {
                    binding.initialLoadingIndicator.visibility = View.GONE
                    binding.interactionBlocker?.visibility = View.GONE
                },
                onRefreshGroup = { refreshCurrentGroupAndAdapter(it) }
            )
        }
    }

    private fun setupViewerAdapter(groups: List<ImageGroup>): ImageViewerAdapter {
        return ImageViewerAdapter(groups, lifecycleScope, requireContext()).apply {
            isMotionPhotoAutoPlay = this@ImageViewerFragment.isMotionPhotoAutoPlay
            onImageTapped = { toggleUi() }
            onZoomChanged = { isZoomed -> if (isZoomed) hideUi() else showUi() }
            onLongPressStarted = { handleLongPressStarted(it) }
            onLongPressEnded = { handleLongPressEnded(it) }
            onMotionPhotoIndicatorTapped = { pos -> handleMotionPhotoIndicatorTapped(pos) }
            onPinchToOverview = { openDarkroomGallery() }
            onMultiCameraLensChanged = { _, _ -> updateSplitButtons(); updateToolbarIcon(); updateControlsVisibility() }
            onFormatChanged = { _, _ -> updateControlsVisibility() }
            onDerivativeVersionChanged = { _, _ -> updateControlsVisibility() }
            setFormatSwitcherPersistentHidden(isAdjusted)
            onVideoPlaybackStateChanged = { _, isPlaying, curMs, totalMs -> bentoDeckCoordinator.updateVideoPlaybackButton(isPlaying, curMs, totalMs) }
            onCinemaDngFrameSelected = { group, frameIndex, uri ->
                val frameUris = if (group.cinemaDngFrameUris.isNotEmpty()) group.cinemaDngFrameUris else if (group.cinemaDngFirstFrameUri != null) listOf(group.cinemaDngFirstFrameUri) else emptyList()
                val targetUri = uri ?: frameUris.getOrNull(frameIndex)
                currentCinemaDngFrame = Pair(frameIndex, targetUri)
                adapter.selectCinemaDngFrame(binding.imagePager.currentItem, frameIndex, targetUri)
            }
            onCurrentListChanged = { previousList, currentList ->
                val cur = binding.imagePager.currentItem
                if (cur in currentList.indices) {
                    val curGroup = currentList[cur]
                    val prev = previousList.getOrNull(cur)
                    if (prev != null && !prev.metadataLoaded && curGroup.metadataLoaded && curGroup.editConfig != null) prepareEditConfig(curGroup)
                    updateControlsVisibility()
                }
            }
        }
    }

    private fun ImageGroup.matchesTarget(uri: String?, base: String?): Boolean {
        if (uri == null) return false
        return (base != null && baseName == base) || rawVideoUri?.toString() == uri || mp4VideoUri?.toString() == uri ||
            jpgUri?.toString() == uri || dngUri?.toString() == uri || dngUri1?.toString() == uri || dngUri2?.toString() == uri ||
            derivativeJpgUris.any { it.toString() == uri } || derivativeMp4Uris.any { it.toString() == uri } ||
            multiJpgUris.any { it.toString() == uri } || multiDngUris.any { it.toString() == uri }
    }

    protected open fun loadImages(targetUri: String? = args.initialUri, forceRefresh: Boolean = false) {
        binding.initialLoadingIndicator.visibility = View.VISIBLE
        binding.imagePager.visibility = View.INVISIBLE

        if (::mediaStrategy.isInitialized && mediaStrategy.isSandbox) {
            viewLifecycleOwner.lifecycleScope.launch {
                val groups = mediaStrategy.loadInitialGroups(requireContext(), arguments, repository)
                if (!groups.isNullOrEmpty()) {
                    if (binding.imagePager.adapter == null) {
                        adapter = setupViewerAdapter(groups)
                        binding.imagePager.adapter = adapter
                        binding.imagePager.isUserInputEnabled = !isAdjusted
                        setupActionButtons()
                        binding.initialLoadingIndicator.visibility = View.GONE
                        binding.imagePager.visibility = View.VISIBLE
                        updateControlsVisibility()
                    } else {
                        adapter.updateGroups(groups)
                        binding.initialLoadingIndicator.visibility = View.GONE
                        binding.imagePager.visibility = View.VISIBLE
                    }
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
                    val targetBase = targetUri?.let { top.maary.darkbag.utils.ImageUtils.getBaseName(it) }
                    val targetPosArg = arguments?.getInt("target_position", -1) ?: -1
                    val targetPos = if (targetPosArg >= 0 && isFirst) targetPosArg else groups.indexOfFirst { it.matchesTarget(targetUri, targetBase) }

                    if (isFirst) {
                        adapter = setupViewerAdapter(groups)
                        binding.imagePager.adapter = adapter
                        setupActionButtons()
                        if (targetPos in groups.indices) {
                            binding.imagePager.setCurrentItem(targetPos, false)
                            hasNavigated = true
                        }
                        val initPos = binding.imagePager.currentItem
                        if (initPos in groups.indices) {
                            prepareEditConfig(groups[initPos])
                            updateGalleryPill(initPos, groups.size)
                        }
                        binding.initialLoadingIndicator.visibility = View.GONE
                        binding.imagePager.visibility = View.VISIBLE
                        updateControlsVisibility()
                    } else {
                        val curBase = if (binding.imagePager.currentItem in adapter.getGroups().indices) adapter.getGroup(binding.imagePager.currentItem).baseName else null
                        adapter.updateGroups(groups) {
                            if (_binding == null) return@updateGroups
                            updateGalleryPill(binding.imagePager.currentItem, groups.size)
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
                        binding.initialLoadingIndicator.visibility = View.GONE
                        binding.imagePager.visibility = View.VISIBLE
                    }
                }
            }
        }
    }

    private fun handleBackgroundSaveEvent(event: ColorProcessor.BackgroundSaveEvent) {
        lifecycleScope.launch {
            repository.invalidateCache()
            val newGroups = repository.getGroupedImages(forceRefresh = true)
            adapter.updateGroups(newGroups)
            val currentIndex = binding.imagePager.currentItem
            if (currentIndex in newGroups.indices) {
                prepareEditConfig(newGroups[currentIndex])
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
        updateSplitButtons()
        updateToolbarIcon()
    }

    protected fun updateFormatBentoRow() {
        if (::bentoDeckCoordinator.isInitialized) bentoDeckCoordinator.updateFormatBentoRow(isAdjusted, isMotionPhotoAutoPlay, currentCinemaDngFrame)
    }

    protected fun updateVideoPlaybackButton(isPlaying: Boolean, currentMs: Long, totalMs: Long) {
        if (::bentoDeckCoordinator.isInitialized) bentoDeckCoordinator.updateVideoPlaybackButton(isPlaying, currentMs, totalMs)
    }

    protected fun prepareEditConfig(group: ImageGroup) {
        currentEditConfig = group.editConfig ?: EditConfig(log = "None", lut = null, hfLayout = group.hfLayout)
        editorViewModel.processIntent(DarkroomEditorUserIntent.SetActiveGroup(group))
    }

    private fun readUriBytes(uri: Uri): ByteArray? = runCatching {
        requireContext().contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            FileInputStream(pfd.fileDescriptor).use { it.readBytes() }
        }
    }.getOrNull()

    protected open suspend fun ensureDngBytesLoaded() {
        val cur = binding.imagePager.currentItem
        if (!::adapter.isInitialized || cur !in adapter.getGroups().indices) return
        val group = adapter.getGroup(cur)

        withContext(Dispatchers.IO) {
            if (!group.isHalfFrame()) {
                if (sourceDngBytes == null) {
                    val dngUri = if (group.isMultiCamera) {
                        val lenses = adapter.getMultiCameraLenses(group)
                        val idx = adapter.getSelectedLensIndex(cur).coerceIn(0, (lenses.size - 1).coerceAtLeast(0))
                        lenses.getOrNull(idx)?.dngUri ?: group.multiDngUris.getOrNull(idx) ?: group.dngUri
                    } else group.dngUri ?: group.cinemaDngFirstFrameUri
                    dngUri?.let {
                        sourceDngBytes = readUriBytes(it)
                        editorViewModel.processIntent(DarkroomEditorUserIntent.SetDngBytes(sourceDngBytes, null))
                    }
                }
            } else if (sourceDngBytes == null || sourceDngBytes2 == null) {
                val u1 = group.dngUri1 ?: group.dngUri
                val u2 = group.dngUri2
                if (u1 != null && sourceDngBytes == null) sourceDngBytes = readUriBytes(u1)
                if (u2 != null && sourceDngBytes2 == null) sourceDngBytes2 = readUriBytes(u2)
                editorViewModel.processIntent(DarkroomEditorUserIntent.SetDngBytes(sourceDngBytes, sourceDngBytes2))
            }
        }
    }

    private fun updateSplitButtons() {
        if (isEditingAdjustments || isAdjusted) {
            binding.btnActionMain?.setIconResource(R.drawable.ic_save)
            binding.btnActionMain?.text = getString(R.string.save).uppercase()
            binding.btnActionMain?.contentDescription = getString(R.string.save)
        } else {
            val currentIndex = if (_binding != null) binding.imagePager.currentItem else 0
            val currentGroup = if (::adapter.isInitialized && currentIndex in adapter.getGroups().indices) adapter.getGroup(currentIndex) else null
            val isUnrendered = currentGroup != null && (currentGroup.isRawVideo || currentGroup.rawVideoUri != null || currentGroup.isCinemaDng || currentGroup.cinemaDngFolderUri != null || currentGroup.cinemaDngFirstFrameUri != null || currentGroup.cinemaDngFrameUris.isNotEmpty()) && currentGroup.mp4VideoUri == null

            if (isUnrendered) {
                binding.btnActionMain?.setIconResource(R.drawable.ic_save)
                binding.btnActionMain?.text = "EXPORT"
                binding.btnActionMain?.contentDescription = getString(R.string.action_export_main)
            } else {
                binding.btnActionMain?.setIconResource(R.drawable.ic_share)
                binding.btnActionMain?.text = "SHARE"
                binding.btnActionMain?.contentDescription = getString(R.string.share_button_alt)
            }
        }
    }

    private fun updateToolbarIcon() {
        binding.btnBack.setIconResource(if (isAdjusted || isEditingAdjustments) R.drawable.ic_close else R.drawable.ic_back)
    }

    protected open fun setupActionButtons() {
        binding.btnActionMain?.setOnClickListener {
            if (isSaving) return@setOnClickListener
            it.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)

            if (isEditingAdjustments || isAdjusted) {
                saveEdit(isReplacement = true)
            } else {
                if (::adapter.isInitialized && adapter.itemCount > 0) {
                    val currentGroup = adapter.getGroup(binding.imagePager.currentItem)
                    val isUnrendered = (currentGroup.isRawVideo || currentGroup.rawVideoUri != null || currentGroup.isCinemaDng || currentGroup.cinemaDngFolderUri != null || currentGroup.cinemaDngFirstFrameUri != null || currentGroup.cinemaDngFrameUris.isNotEmpty()) && currentGroup.mp4VideoUri == null
                    if (isUnrendered) openExportHub() else performShare()
                } else {
                    performShare()
                }
            }
        }

        binding.btnActionMain?.setOnLongClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            if (isEditingAdjustments || isAdjusted) showSaveMenu(it) else showViewMenu(it)
            true
        }

        binding.btnActionMenu?.setOnClickListener {
            binding.btnActionMenu?.isCheckable = true
            binding.btnActionMenu?.isChecked = true
            if (isEditingAdjustments || isAdjusted) showSaveMenu(it) else showViewMenu(it)
        }

        binding.btnDelete.setOnClickListener {
            if (::adapter.isInitialized && adapter.itemCount > 0) showDeleteDialog(adapter.getGroup(binding.imagePager.currentItem))
        }

        binding.btnSeparateDng.setOnClickListener {
            if (::adapter.isInitialized && adapter.itemCount > 0) showHalfFrameShareSheet(adapter.getGroup(binding.imagePager.currentItem))
        }

        binding.btnLogCurve.setOnClickListener {
            if (::adapter.isInitialized && adapter.itemCount > 0) editorPanelDelegate.showLutMenu(adapter.getGroup(binding.imagePager.currentItem))
        }

        binding.btn3dLut.setOnClickListener {
            if (::adapter.isInitialized && adapter.itemCount > 0) editorPanelDelegate.showLutMenu(adapter.getGroup(binding.imagePager.currentItem))
        }

        binding.btnAdjustTofu.setOnClickListener { enterEditMode() }

        binding.btnVideoPlayback?.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            val curPos = binding.imagePager.currentItem
            val grp = adapter.getGroup(curPos)
            val fmt = adapter.getSelectedFormat(curPos)
            val isRawVideo = (fmt == ImageViewerAdapter.FORMAT_DNG || fmt == "RAW" || fmt == "RAWVID") && (grp.isRawVideo || grp.rawVideoUri != null)
            val isCdng = (fmt == ImageViewerAdapter.FORMAT_DNG || fmt == "RAW" || fmt == "CDNG") && (grp.isCinemaDng || grp.cinemaDngFolderUri != null || grp.cinemaDngFrameUris.isNotEmpty())
            when {
                isCdng -> adapter.toggleCinemaDngForPosition(curPos)
                isRawVideo -> adapter.toggleRawVideoForPosition(curPos)
                else -> adapter.toggleMp4VideoForPosition(curPos)
            }
        }
    }

    protected open fun showSaveMenu(anchor: View) {
        if (!::adapter.isInitialized || adapter.itemCount == 0) {
            binding.btnActionMenu?.isChecked = false
            return
        }
        mediaStrategy.showSaveMenu(requireContext(), anchor, adapter.getGroup(binding.imagePager.currentItem)) { action ->
            when (action) {
                ViewerSaveAction.SAVE_REPLACE -> saveEdit(isReplacement = true)
                ViewerSaveAction.SAVE_AS_NEW -> saveEdit(isReplacement = false)
                ViewerSaveAction.SHARE_TIFF -> performShareAsTiff()
                ViewerSaveAction.EXPORT_JPG_TO_PICTURES -> exportToJpg()
            }
        }
    }

    private fun exportToJpg() {
        val config = currentEditConfig ?: return
        exportCoordinator.exportToJpg(
            group = adapter.getGroup(binding.imagePager.currentItem),
            config = config,
            isEditingAdjustments = isEditingAdjustments,
            cachedBytes1 = sourceDngBytes,
            cachedBytes2 = sourceDngBytes2,
            repository = repository,
            onStartExport = {
                binding.initialLoadingIndicator.visibility = View.VISIBLE
                binding.interactionBlocker?.visibility = View.VISIBLE
            },
            onEndExport = {
                binding.initialLoadingIndicator.visibility = View.GONE
                binding.interactionBlocker?.visibility = View.GONE
            },
            onSaveReplacement = { saveEdit(isReplacement = true) }
        )
    }

    protected open fun showViewMenu(anchor: View) {
        if (!::adapter.isInitialized || adapter.itemCount == 0) {
            binding.btnActionMenu?.isChecked = false
            return
        }
        val grp = adapter.getGroup(binding.imagePager.currentItem)
        val popup = PopupMenu(requireContext(), anchor)
        val isUnrendered = (grp.isRawVideo || grp.rawVideoUri != null || grp.isCinemaDng || grp.cinemaDngFolderUri != null || grp.cinemaDngFirstFrameUri != null || grp.cinemaDngFrameUris.isNotEmpty()) && grp.mp4VideoUri == null

        if (isUnrendered) {
            popup.menu.add(0, MENU_SHARE, 0, getString(R.string.share_button_alt)).setIcon(R.drawable.ic_share)
        } else {
            popup.menu.add(0, MENU_EXPORT_HUB, 0, getString(R.string.action_export)).setIcon(R.drawable.ic_save)
            if (grp.dngUri != null || grp.dngUri1 != null || grp.dngUri2 != null) popup.menu.add(0, MENU_SHARE_TIFF, 0, getString(R.string.share_as_tiff)).setIcon(R.drawable.ic_photo)
        }
        if (grp.isMultiCamera) popup.menu.add(0, MENU_COLLAGE, 0, getString(R.string.create_multi_cam_collage)).setIcon(R.drawable.ic_photo)
        popup.menu.add(0, MENU_DETAILS, 0, "Details").setIcon(R.drawable.ic_info)
        popup.menu.add(0, MENU_DELETE, 0, "Delete").setIcon(R.drawable.ic_delete)

        forceShowIcons(popup)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_SHARE -> performShare()
                MENU_EXPORT_HUB -> openExportHub()
                MENU_SHARE_TIFF -> performShareAsTiff()
                MENU_COLLAGE -> showMultiCamCollageDialog(adapter.getGroup(binding.imagePager.currentItem))
                MENU_DETAILS -> showImageDetails()
                MENU_DELETE -> showDeleteDialog(adapter.getGroup(binding.imagePager.currentItem))
            }
            true
        }
        popup.setOnDismissListener { binding.btnActionMenu?.isChecked = false }
        popup.show()
    }

    protected fun markAdjusted() {
        if (!isAdjusted) {
            isAdjusted = true
            adapter.setFormatSwitcherPersistentHidden(true)
            updateSplitButtons()
            updateToolbarIcon()
            updateBackPressedCallbackState()
        }
    }

    protected fun resetAdjustments() {
        isAdjusted = false
        editorPanelDelegate.exitEditMode(apply = false)
        binding.imagePager.isUserInputEnabled = true
        adapter.setFormatSwitcherPersistentHidden(false)
        sourceDngBytes = null
        sourceDngBytes2 = null
        cachedBitmap1 = null
        cachedBitmap2 = null
        lastPreviewConfig = null
        lastCompositeBitmap = null
        previewJob?.cancel()
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
        val currentGroup = adapter.getGroup(cur)
        lifecycleScope.launch {
            val fullyLoaded = if (!currentGroup.metadataLoaded) repository.loadMetadata(currentGroup) else currentGroup
            val config = currentEditConfig ?: fullyLoaded.editConfig
            val frameInfo = currentCinemaDngFrame ?: adapter.getActiveCinemaDngFrame(cur)
            RawVideoExportSheet.newInstance(fullyLoaded, config?.log, config?.lut, frameInfo?.first ?: 0).show(childFragmentManager, RawVideoExportSheet.TAG)
        }
    }

    private fun performShare() {
        if (!::adapter.isInitialized || adapter.itemCount == 0) return
        val cur = binding.imagePager.currentItem
        val currentGroup = adapter.getGroup(cur)
        val format = adapter.getSelectedFormat(cur)

        if (format == "DNG" && currentGroup.isHalfFrame()) {
            showHalfFrameShareSheet(currentGroup)
            return
        }
        val uri = when (format) {
            "JPG" -> currentGroup.jpgUri
            "MP4" -> currentGroup.mp4VideoUri
            "DNG" -> currentGroup.dngUri ?: currentGroup.dngUri1 ?: currentGroup.dngUri2 ?: currentGroup.cinemaDngFirstFrameUri
            else -> currentGroup.firstAvailableUri
        }
        uri?.let { exportCoordinator.shareImages(listOf(it)) } ?: Toast.makeText(requireContext(), R.string.error_no_external_viewer, Toast.LENGTH_SHORT).show()
    }

    private fun preloadAdjacentMetadata(position: Int) {
        if (!::adapter.isInitialized) return
        lifecycleScope.launch(Dispatchers.IO) {
            val groups = adapter.getGroups()
            val p1 = position - 1
            val p2 = position + 1
            if (p1 in groups.indices && !groups[p1].metadataLoaded) repository.loadMetadata(groups[p1])
            if (p2 in groups.indices && !groups[p2].metadataLoaded) repository.loadMetadata(groups[p2])
        }
    }

    protected fun enterEditMode() {
        if (!isUiVisible) showUi()
        if (!isEditingAdjustments) {
            val currentGroup = if (::adapter.isInitialized && binding.imagePager.currentItem in adapter.getGroups().indices) adapter.getGroup(binding.imagePager.currentItem) else null
            editorPanelDelegate.enterEditMode(currentGroup)
        }
    }

    private fun exitEditMode(apply: Boolean) = editorPanelDelegate.exitEditMode(apply)

    protected fun handleLongPressStarted(imageView: ZoomableImageView) {
        if (isAdjusted) {
            view?.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            val jpgUri = adapter.getGroup(binding.imagePager.currentItem).jpgUri ?: return
            isLongPressing = true
            imageView.setMaintainZoomOnNextImage(true)
            adapter.cancelLoadJob(binding.imagePager.currentItem, clearView = false)
            com.bumptech.glide.Glide.with(imageView).asBitmap().load(jpgUri).placeholder(imageView.drawable).dontAnimate().skipMemoryCache(true).into(imageView)
            return
        }
        val cur = binding.imagePager.currentItem
        if (::adapter.isInitialized && cur in adapter.getGroups().indices && adapter.getSelectedFormat(cur) != ImageViewerAdapter.FORMAT_DNG) {
            val g = adapter.getGroup(cur)
            if (g.isMotionPhoto && g.jpgUri != null) {
                view?.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                adapter.playMotionVideoForPosition(cur)
            }
        }
    }

    protected fun handleLongPressEnded(imageView: ZoomableImageView) {
        if (isAdjusted) {
            isLongPressing = false
            view?.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            imageView.setMaintainZoomOnNextImage(true)
            lastCompositeBitmap?.let { adapter.setManualBitmap(binding.imagePager.currentItem, it) }
            return
        }
        val cur = binding.imagePager.currentItem
        if (::adapter.isInitialized && cur in adapter.getGroups().indices && adapter.getSelectedFormat(cur) != ImageViewerAdapter.FORMAT_DNG) {
            if (adapter.getGroup(cur).isMotionPhoto) adapter.stopMotionVideoForPosition(cur)
        }
    }

    protected fun handleMotionPhotoIndicatorTapped(position: Int) {
        isMotionPhotoAutoPlay = !isMotionPhotoAutoPlay
        requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_VIEWER_MOTION_PHOTO_AUTO_PLAY, isMotionPhotoAutoPlay).apply()
        updateFormatBentoRow()
        view?.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
        adapter.setMotionPhotoAutoPlayEnabled(isMotionPhotoAutoPlay)
        if (isMotionPhotoAutoPlay) {
            hasAutoPlayedPosition = position
            adapter.playMotionVideoForPosition(position)
        } else {
            adapter.stopAllMotionVideos()
        }
    }

    protected open fun saveEdit(isReplacement: Boolean) {
        if (isSaving) return
        val config = currentEditConfig ?: return
        val (c1, c2) = editorViewModel.getCachedDngBytes()
        isSaving = true

        exportCoordinator.saveEdit(
            group = adapter.getGroup(binding.imagePager.currentItem),
            config = config,
            isReplacement = isReplacement,
            cachedBytes1 = c1 ?: sourceDngBytes,
            cachedBytes2 = c2 ?: sourceDngBytes2,
            repository = repository,
            mediaStrategy = mediaStrategy,
            onStartSave = {
                binding.initialLoadingIndicator.visibility = View.VISIBLE
                binding.interactionBlocker?.visibility = View.VISIBLE
            },
            onEndSave = {
                binding.initialLoadingIndicator.visibility = View.GONE
                binding.interactionBlocker?.visibility = View.GONE
                isSaving = false
            },
            onSuccess = {
                resetAdjustments()
                binding.root.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
                if (mediaStrategy.isSandbox) {
                    val updated = mediaStrategy.loadInitialGroups(requireContext().applicationContext, arguments, repository)
                    if (updated != null) adapter.updateGroups(updated)
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
            group = adapter.getGroup(binding.imagePager.currentItem),
            config = config,
            isEditingAdjustments = isEditingAdjustments,
            cachedBytes1 = sourceDngBytes,
            cachedBytes2 = sourceDngBytes2,
            repository = repository,
            onStartRender = { binding.initialLoadingIndicator.visibility = View.VISIBLE },
            onEndRender = { binding.initialLoadingIndicator.visibility = View.GONE },
            onSaveReplacement = { saveEdit(isReplacement = true) }
        )
    }

    protected open fun processAndShareTiff() {
        val config = currentEditConfig ?: return
        exportCoordinator.processAndShareTiff(
            group = adapter.getGroup(binding.imagePager.currentItem),
            config = config,
            cachedBytes1 = sourceDngBytes,
            cachedBytes2 = sourceDngBytes2,
            repository = repository,
            onStartRender = { binding.initialLoadingIndicator.visibility = View.VISIBLE },
            onEndRender = { binding.initialLoadingIndicator.visibility = View.GONE }
        )
    }

    private fun showHalfFrameShareSheet(group: ImageGroup) {
        HalfFrameShareSheet.newInstance(group.dngUri1, group.dngUri2).show(childFragmentManager, HalfFrameShareSheet.TAG)
    }

    private fun showImageDetails() {
        val uri = adapter.getCurrentUri(binding.imagePager.currentItem) ?: return
        ViewerMetadataSheet.show(childFragmentManager, uri)
    }

    private fun showDeleteDialog(group: ImageGroup) {
        val derivatives = adapter.getDerivativeUris(group)
        val activeIdx = adapter.getSelectedDerivativeIndex(group.baseName).coerceIn(0, (derivatives.size - 1).coerceAtLeast(0))
        deletionController.showDeleteDialog(group, derivatives, activeIdx, childFragmentManager)
    }

    protected open fun deleteImage(group: ImageGroup, deleteGroup: Boolean) =
        deletionController.deleteImage(group, if (deleteGroup) group.allUris else null, deleteGroup)

    protected open fun deleteImage(group: ImageGroup, targetUris: List<Uri>?, deleteGroup: Boolean) =
        deletionController.deleteImage(group, targetUris, deleteGroup)

    private fun setupToolbar() {
        updateSplitButtons()
        updateToolbarIcon()
        binding.btnBack.setOnClickListener {
            if (::editorPanelDelegate.isInitialized && editorPanelDelegate.dismissPillPopup()) return@setOnClickListener
            if (isEditingAdjustments) exitEditMode(apply = false) else if (isAdjusted) showDiscardChangesDialog() else findNavController().navigateUp()
        }
    }

    private fun openDarkroomGallery() {
        if (!isGallerySupported) return
        val pos = if (::adapter.isInitialized && adapter.itemCount > 0) binding.imagePager.currentItem else 0
        val currentGroup = if (::adapter.isInitialized && pos in adapter.getGroups().indices) adapter.getGroup(pos) else null
        val uri = currentGroup?.firstAvailableUri ?: currentGroup?.jpgUri ?: currentGroup?.dngUri
        val action = ImageViewerFragmentDirections.actionImageViewerToDarkroomGallery(initialUri = uri?.toString())
        findNavController().navigate(action)
    }

    protected fun updateGalleryPill(position: Int, totalCount: Int) {
        if (!isGallerySupported) {
            binding.btnGalleryPill.visibility = View.GONE
            return
        }
        binding.btnGalleryPill.visibility = View.VISIBLE
        binding.btnGalleryPill.text = "${position + 1} / $totalCount"
    }

    protected fun toggleUi() { if (isUiVisible) hideUi() else showUi() }

    protected fun showUi() {
        if (isUiVisible) return
        isUiVisible = true
        binding.topBarContainer.animate().alpha(1f).translationY(0f).setDuration(200).withStartAction { binding.topBarContainer.visibility = View.VISIBLE }.start()
        binding.viewerBentoDeck.animate().alpha(1f).translationY(0f).setDuration(200).withStartAction { binding.viewerBentoDeck.visibility = View.VISIBLE }.start()
        binding.btnGalleryPill.animate().alpha(1f).translationY(0f).setDuration(200).withStartAction { binding.btnGalleryPill.visibility = View.VISIBLE }.start()
        adapter.setUiVisibility(true)
        updateViewportPadding()
    }

    protected fun hideUi() {
        if (!isUiVisible) return
        isUiVisible = false
        binding.topBarContainer.animate().alpha(0f).translationY(-topBarHeight.toFloat()).setDuration(200).withEndAction { binding.topBarContainer.visibility = View.GONE }.start()
        binding.viewerBentoDeck.animate().alpha(0f).translationY(bentoDeckHeight.toFloat()).setDuration(200).withEndAction { binding.viewerBentoDeck.visibility = View.GONE }.start()
        binding.btnGalleryPill.animate().alpha(0f).translationY(bentoDeckHeight.toFloat()).setDuration(200).withEndAction { binding.btnGalleryPill.visibility = View.GONE }.start()
        adapter.setUiVisibility(false)
        updateViewportPadding()
    }

    private fun showMultiCamCollageDialog(group: ImageGroup) {
        MultiCameraCollageSheet.newInstance(group).show(childFragmentManager, MultiCameraCollageSheet.TAG)
    }

    private fun setupEdgeToEdge() {
        binding.topBarContainer.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val newHeight = bottom - top
            if (newHeight > 0 && newHeight != topBarHeight) {
                topBarHeight = newHeight
                updateViewportPadding()
            }
        }
        binding.viewerBentoDeck.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val newHeight = bottom - top
            if (newHeight > 0 && newHeight != bentoDeckHeight) {
                bentoDeckHeight = newHeight
                updateViewportPadding()
            }
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val statusBarInsets = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBarInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            systemTopInset = statusBarInsets.top
            systemBottomInset = navBarInsets.bottom

            binding.topBarContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> { topMargin = systemTopInset }
            binding.viewerBentoDeck.updateLayoutParams<ViewGroup.MarginLayoutParams> { bottomMargin = systemBottomInset }
            binding.editAdjustmentPanel.updateLayoutParams<ViewGroup.MarginLayoutParams> { bottomMargin = systemBottomInset }
            updateViewportPadding()
            insets
        }
    }

    private fun updateViewportPadding() {
        val topPadding = if (isUiVisible) systemTopInset + topBarHeight else 0
        val bottomPadding = if (isEditingAdjustments) {
            val panelHeight = binding.editAdjustmentPanel.height
            if (panelHeight > 0) systemBottomInset + panelHeight else systemBottomInset + 200
        } else if (isUiVisible) {
            systemBottomInset + bentoDeckHeight
        } else 0
        binding.imagePager.setPadding(0, topPadding, 0, bottomPadding)
    }

    private fun updateBackPressedCallbackState() {
        backPressedCallback.isEnabled = isAdjusted || isEditingAdjustments || (::editorPanelDelegate.isInitialized && binding.lutListContainer.visibility == View.VISIBLE)
    }

    protected open fun showDiscardChangesDialog() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.discard_changes_title)
            .setMessage(R.string.discard_changes_message)
            .setPositiveButton(R.string.discard) { _, _ ->
                resetAdjustments()
                findNavController().navigateUp()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    protected fun forceShowIcons(popup: PopupMenu) {
        try {
            val fields = popup.javaClass.declaredFields
            for (field in fields) {
                if (field.name == "mPopup") {
                    field.isAccessible = true
                    val menuPopupHelper = field.get(popup)
                    val classPopupHelper = Class.forName(menuPopupHelper.javaClass.name)
                    val setForceIcons = classPopupHelper.getMethod("setForceShowIcon", Boolean::class.javaPrimitiveType)
                    setForceIcons.invoke(menuPopupHelper, true)
                    break
                }
            }
        } catch (e: Exception) {
            Log.e("ImageViewerFragment", "Error forcing menu icons", e)
        }
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

    private suspend fun refreshCurrentGroupAndAdapter(targetBaseName: String, autoSelectFormat: String? = null) {
        repository.invalidateCache()
        val newGroups = repository.getGroupedImages(forceRefresh = true)
        if (_binding == null || !::adapter.isInitialized) return
        adapter.updateGroups(newGroups) {
            if (_binding == null || !::adapter.isInitialized) return@updateGroups
            if (autoSelectFormat != null) adapter.setFormatForGroup(targetBaseName, autoSelectFormat)
            val cur = binding.imagePager.currentItem
            if (cur in newGroups.indices && newGroups[cur].baseName == targetBaseName) {
                adapter.notifyItemChanged(cur)
                prepareEditConfig(newGroups[cur])
                updateSplitButtons()
                updateControlsVisibility()
                updateToolbarIcon()
            }
        }
    }

    companion object {
        const val KEY_VIEWER_MOTION_PHOTO_AUTO_PLAY = "viewer_motion_photo_auto_play"
        val LOOK_DOT_COLORS = ViewerBentoDeckCoordinator.LOOK_DOT_COLORS

        private const val MENU_DETAILS = 1
        private const val MENU_DELETE = 2
        private const val MENU_SAVE_AS = 3
        private const val MENU_SHARE_TIFF = 4
        private const val MENU_COLLAGE = 5
        private const val MENU_EXPORT_CINEMA_DNG = 6
        private const val MENU_EXPORT_MP4 = 7
        private const val MENU_EXPORT_CINEMADNG_FRAME = 8
        private const val MENU_SHARE = 9
        private const val MENU_EXPORT_HUB = 10
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        deletionController.pendingDeleteNextTargetUri?.let { outState.putString("pendingDeleteNextTargetUri", it) }
    }

    protected suspend fun generateProcessedBitmap(config: EditConfig, currentGroup: ImageGroup): Bitmap? {
        val ctx = context ?: return null
        val appContext = ctx.applicationContext
        val previewSink = BitmapPreviewSink()
        val spec = DarkroomRenderSpec.fromImageGroup(
            context = appContext,
            group = currentGroup,
            editConfig = config,
            sink = previewSink,
            outputFormat = RenderOutputFormat.BITMAP,
            downsampleFactor = 1,
            cachedBytes1 = sourceDngBytes,
            cachedBytes2 = sourceDngBytes2,
            downsampleHalfFrame = top.maary.darkbag.utils.HalfFrameManager(appContext).downsample,
            repository = repository
        )
        val result = DarkroomRenderEngine.render(appContext, spec)
        return if (result.success) result.bitmap else null
    }
}
