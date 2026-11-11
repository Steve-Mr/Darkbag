package top.maary.darkbag.fragments

import android.util.Log
import java.io.File
import android.os.Build
import android.provider.MediaStore
import android.app.Activity
import android.provider.DocumentsContract
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import android.app.RecoverableSecurityException
import android.widget.Toast
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import android.content.res.ColorStateList
import android.graphics.Color
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.widget.LinearLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import top.maary.darkbag.ui.CameraBentoDeckView
import top.maary.darkbag.ui.bento.BentoContainerRole
import top.maary.darkbag.ui.bento.BentoTrayCard
import androidx.recyclerview.widget.GridLayoutManager
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.view.MenuItem
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.activity.OnBackPressedCallback
import androidx.exifinterface.media.ExifInterface
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume
import top.maary.darkbag.R
import top.maary.darkbag.databinding.BottomSheetImageDetailsBinding
import top.maary.darkbag.databinding.FragmentImageViewerBinding
import top.maary.darkbag.databinding.ItemDetailRowBinding
import top.maary.darkbag.models.ImageGroup
import top.maary.darkbag.pipeline.composer.HalfFrameComposer
import top.maary.darkbag.processor.ColorProcessor
import top.maary.darkbag.repository.ImageRepository
import androidx.fragment.app.viewModels
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import top.maary.darkbag.pipeline.darkroom.DarkroomRenderEngine
import top.maary.darkbag.pipeline.darkroom.model.DarkroomRenderSpec
import top.maary.darkbag.pipeline.darkroom.model.DarkroomSource
import top.maary.darkbag.pipeline.darkroom.model.RenderOutputFormat
import top.maary.darkbag.pipeline.darkroom.model.normalizedForRender
import top.maary.darkbag.pipeline.darkroom.sink.BitmapPreviewSink
import top.maary.darkbag.pipeline.darkroom.sink.MediaStoreExportSink
import top.maary.darkbag.pipeline.darkroom.sink.ShareFileSink
import top.maary.darkbag.viewer.editor.DarkroomEditorController
import top.maary.darkbag.viewer.editor.DarkroomEditorUiState
import top.maary.darkbag.viewer.editor.DarkroomEditorUserIntent
import top.maary.darkbag.viewer.editor.DarkroomEditorViewModel
import top.maary.darkbag.viewer.strategy.ViewerMediaStrategy
import top.maary.darkbag.viewer.strategy.ViewerMediaStrategyFactory
import top.maary.darkbag.viewer.strategy.ViewerSaveAction
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.roundToInt

open class ImageViewerFragment : Fragment() {


    private suspend fun deleteUriSafe(context: Context, uri: Uri): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                if (uri.scheme == "content" && DocumentsContract.isDocumentUri(context, uri)) {
                    DocumentsContract.deleteDocument(context.contentResolver, uri)
                } else if (uri.scheme == "content" && DocumentsContract.isTreeUri(uri)) {
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
                    DocumentsContract.deleteDocument(context.contentResolver, docUri)
                } else {
                    context.contentResolver.delete(uri, null, null) > 0
                }
            } catch (e: Exception) {
                try {
                    val docFile = androidx.documentfile.provider.DocumentFile.fromSingleUri(context, uri)
                        ?: androidx.documentfile.provider.DocumentFile.fromTreeUri(context, uri)
                    docFile?.delete() ?: false
                } catch (e2: Exception) {
                    throw e
                }
            }
        }
    }
    private var pendingDeleteNextTargetUri: String? = null
    private val deleteLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            context?.let { Toast.makeText(it, "Failed to delete image", Toast.LENGTH_SHORT).show() }
        }
        lifecycleScope.launch {
            repository.invalidateCache(clearStale = true)
            loadImages(pendingDeleteNextTargetUri, forceRefresh = true)
            pendingDeleteNextTargetUri = null
        }
    }

    protected var _binding: FragmentImageViewerBinding? = null
    private var isUiVisible = true
    protected val binding get() = _binding!!
    protected val args: ImageViewerFragmentArgs by navArgs()
    protected val editorViewModel: DarkroomEditorViewModel by viewModels()
    protected lateinit var mediaStrategy: ViewerMediaStrategy
    private var editorController: DarkroomEditorController? = null
    protected lateinit var repository: ImageRepository
    protected lateinit var adapter: ImageViewerAdapter
    protected open val isGallerySupported: Boolean get() = if (::mediaStrategy.isInitialized) mediaStrategy.isGallerySupported else true
    private var imagesCollectJob: Job? = null

    var isMotionPhotoAutoPlay = true
    protected var hasAutoPlayedPosition = -1

    protected var isAdjusted = false
    protected var isEditingAdjustments = false
    private var isSaving = false
    protected var currentCinemaDngFrame: Pair<Int, Uri?>? = null
    private var systemTopInset = 0
    private var systemBottomInset = 0
    private var topBarHeight = 0
    private var bentoDeckHeight = 0
    private var configBeforeEditing: top.maary.darkbag.models.EditConfig? = null
    protected var currentEditConfig: top.maary.darkbag.models.EditConfig? = null
    private var userExplicitlyChangedLog: Boolean = false
    protected var selectedDngIndex = 0 // 0 or 1 for half-frame
    protected var sourceDngBytes: ByteArray? = null
    protected var sourceDngBytes2: ByteArray? = null
    private var cachedBitmap1: android.graphics.Bitmap? = null
    private var cachedBitmap2: android.graphics.Bitmap? = null
    private var lastPreviewConfig: top.maary.darkbag.models.EditConfig? = null
    private var lastCompositeBitmap: android.graphics.Bitmap? = null
    private var isLongPressing = false
    private var savedMatrix = android.graphics.Matrix()
    private var savedScale = 1f
    private var pendingSingleDeleteGroup: ImageGroup? = null

    protected lateinit var lutManager: top.maary.darkbag.utils.LutManager
    protected var previewJob: Job? = null
    private suspend fun safeUpdateSingleGroup(updatedGroup: ImageGroup) {
        withContext(Dispatchers.Main) {
            if (_binding == null || !isAdded || !::adapter.isInitialized) return@withContext
            adapter.updateSingleGroup(updatedGroup)
        }
    }

    protected val pageChangeCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) {
            updateGalleryPill(position, if (::adapter.isInitialized) adapter.itemCount else 0)
            if (::adapter.isInitialized) {
                adapter.stopAllMotionVideos()
            }
            preloadAdjacentMetadata(position)

            val group = adapter.getGroup(position)
            val isCinemaDngGroup = group.isCinemaDng || group.cinemaDngFolderUri != null || group.cinemaDngFirstFrameUri != null || group.cinemaDngFrameUris.isNotEmpty()
            if (isCinemaDngGroup) {
                currentCinemaDngFrame = adapter.getActiveCinemaDngFrame(position)
            } else {
                currentCinemaDngFrame = null
            }

            if (!group.metadataLoaded) {
                lifecycleScope.launch(Dispatchers.IO) {
                    val updatedGroup = repository.loadMetadata(group)
                    withContext(Dispatchers.Main) {
                        if (_binding == null || !isAdded || !::adapter.isInitialized) return@withContext
                        safeUpdateSingleGroup(updatedGroup)

                        if (binding.imagePager.currentItem == position && !isAdjusted) {
                            prepareEditConfig(updatedGroup)
                            updateControlsVisibility()
                        }

                        val format = adapter.getSelectedFormat(position)
                        if (!isAdjusted && isMotionPhotoAutoPlay && hasAutoPlayedPosition != position && updatedGroup.isMotionPhoto && binding.imagePager.currentItem == position && format != ImageViewerAdapter.FORMAT_DNG) {
                            hasAutoPlayedPosition = position
                            adapter.playMotionVideoForPosition(position)
                        }
                    }
                }
            } else if (!isAdjusted && isMotionPhotoAutoPlay && hasAutoPlayedPosition != position && group.isMotionPhoto && adapter.getSelectedFormat(position) != ImageViewerAdapter.FORMAT_DNG) {
                hasAutoPlayedPosition = position
                adapter.playMotionVideoForPosition(position)
            }

            if (isAdjusted) {
                resetAdjustments()
            } else {
                currentEditConfig = null
                sourceDngBytes = null
                sourceDngBytes2 = null

                // Clear active view before recycling
                val currentIndex = binding.imagePager.currentItem
                val currentHolder = (binding.imagePager.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView)
                    ?.findViewHolderForAdapterPosition(currentIndex) as? ImageViewerAdapter.ViewHolder
                currentHolder?.binding?.imageView?.let { iv ->
                    if (iv.drawable is android.graphics.drawable.BitmapDrawable) {
                        val bmp = (iv.drawable as android.graphics.drawable.BitmapDrawable).bitmap
                        if (bmp == lastCompositeBitmap || bmp == cachedBitmap1 || bmp == cachedBitmap2) {
                            iv.setImageDrawable(null)
                        }
                    }
                }

                cachedBitmap1 = null
                cachedBitmap2 = null
                lastCompositeBitmap = null
            }
            updateControlsVisibility()
        }
    }

    private val backPressedCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (binding.lutListContainer?.visibility == View.VISIBLE) {
                binding.lutListContainer?.visibility = View.GONE
                binding.touchOverlay.visibility = View.GONE
                updateBackPressedCallbackState()
            } else if (isEditingAdjustments) {
                exitEditMode(apply = false)
            } else if (isAdjusted) {
                showDiscardChangesDialog()
            } else {
                isEnabled = false
                findNavController().navigateUp()
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentImageViewerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        savedInstanceState?.getString("pendingDeleteNextTargetUri")?.let { pendingDeleteNextTargetUri = it }

        val prefs = requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        isMotionPhotoAutoPlay = prefs.getBoolean(KEY_VIEWER_MOTION_PHOTO_AUTO_PLAY, true)

        repository = ImageRepository(requireContext())
        lutManager = top.maary.darkbag.utils.LutManager(requireContext())
        setupEdgeToEdge()
        setupToolbar()
        binding.btnGalleryPill.setOnClickListener {
            openDarkroomGallery()
        }

        if (savedInstanceState == null && args.startInGalleryMode) {
            openDarkroomGallery()
            return
        }

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backPressedCallback)

        childFragmentManager.setFragmentResultListener(HalfFrameShareSheet.REQUEST_KEY, viewLifecycleOwner) { _, bundle ->
            val uris = bundle.getParcelableArrayList<android.net.Uri>(HalfFrameShareSheet.BUNDLE_KEY_URIS)
            if (uris != null) {
                shareImages(uris)
            }
        }

        childFragmentManager.setFragmentResultListener(DarkbagBatchDeleteSheet.REQUEST_KEY, viewLifecycleOwner) { _, bundle ->
            val mode = bundle.getInt(DarkbagBatchDeleteSheet.BUNDLE_KEY_DELETE_MODE, 0)
            pendingSingleDeleteGroup?.let { group ->
                executeSingleGroupDelete(group, mode)
                pendingSingleDeleteGroup = null
            }
        }

        childFragmentManager.setFragmentResultListener(RawVideoExportSheet.REQUEST_KEY, viewLifecycleOwner) { _, bundle ->
            val log = bundle.getString(RawVideoExportSheet.RESULT_LOG) ?: "None"
            val lut = bundle.getString(RawVideoExportSheet.RESULT_LUT) ?: "None"
            val resolution = bundle.getInt(RawVideoExportSheet.RESULT_RESOLUTION, 1080)
            val action = bundle.getString(RawVideoExportSheet.RESULT_ACTION)
            val frameIndex = bundle.getInt(RawVideoExportSheet.RESULT_FRAME_INDEX, 0)
            val currentGroup = adapter.getGroup(binding.imagePager.currentItem)
            val config = (currentEditConfig ?: currentGroup.editConfig ?: top.maary.darkbag.models.EditConfig()).copy(log = log, lut = lut)

            when (action) {
                RawVideoExportSheet.ACTION_EXPORT_MP4 -> {
                    performExportMp4(currentGroup, config, resolution)
                }
                RawVideoExportSheet.ACTION_EXPORT_CINEMADNG -> {
                    performExportCinemaDng(currentGroup)
                }
                RawVideoExportSheet.ACTION_FRAME_JPG -> {
                    exportCinemaDngFrame(
                        group = currentGroup,
                        exportType = top.maary.darkbag.rawvideo.RawVideoExporter.FrameExportType.GRADED_JPG_ONLY,
                        editConfig = config,
                        targetFrameIndex = frameIndex
                    )
                }
                RawVideoExportSheet.ACTION_FRAME_DNG -> {
                    exportCinemaDngFrame(
                        group = currentGroup,
                        exportType = top.maary.darkbag.rawvideo.RawVideoExporter.FrameExportType.RAW_DNG_ONLY,
                        editConfig = null,
                        targetFrameIndex = frameIndex
                    )
                }
                RawVideoExportSheet.ACTION_FRAME_PAIR -> {
                    exportCinemaDngFrame(
                        group = currentGroup,
                        exportType = top.maary.darkbag.rawvideo.RawVideoExporter.FrameExportType.RAW_AND_GRADED_PAIR,
                        editConfig = config,
                        targetFrameIndex = frameIndex
                    )
                }
                else -> {
                    val exportMode = bundle.getInt(RawVideoExportSheet.RESULT_EXPORT_MODE, RawVideoExportSheet.MODE_VIDEO)
                    when (exportMode) {
                        RawVideoExportSheet.MODE_VIDEO -> {
                            performExportMp4(currentGroup, config, resolution)
                        }
                        RawVideoExportSheet.MODE_SINGLE_FRAME_JPG -> {
                            exportCinemaDngFrame(
                                group = currentGroup,
                                exportType = top.maary.darkbag.rawvideo.RawVideoExporter.FrameExportType.GRADED_JPG_ONLY,
                                editConfig = config,
                                targetFrameIndex = frameIndex
                            )
                        }
                        RawVideoExportSheet.MODE_SINGLE_FRAME_PAIR -> {
                            exportCinemaDngFrame(
                                group = currentGroup,
                                exportType = top.maary.darkbag.rawvideo.RawVideoExporter.FrameExportType.RAW_AND_GRADED_PAIR,
                                editConfig = config,
                                targetFrameIndex = frameIndex
                            )
                        }
                    }
                }
            }
        }

        mediaStrategy = ViewerMediaStrategyFactory.create(arguments)
        val editor = DarkroomEditorController(
            binding = binding,
            viewModel = editorViewModel,
            onViewportPaddingRequested = { updateViewportPadding() },
            onEditStateChanged = { isEditing, isAdjusted ->
                this.isEditingAdjustments = isEditing
                this.isAdjusted = isAdjusted
                if (::adapter.isInitialized) {
                    binding.imagePager.isUserInputEnabled = !isEditing && !isAdjusted
                    adapter.setFormatSwitcherPersistentHidden(isEditing || isAdjusted)
                }
                updateSplitButtons()
                updateToolbarIcon()
                updateBackPressedCallbackState()
            }
        )
        editor.setup()
        editorController = editor

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                editorViewModel.uiState.collect { state ->
                    editorController?.render(state)
                    if (state.activeConfig != null) {
                        currentEditConfig = state.activeConfig
                    }
                    if (state.previewBitmap != null && state.previewBitmap != lastCompositeBitmap) {
                        lastCompositeBitmap = state.previewBitmap
                        if (!isLongPressing && ::adapter.isInitialized) {
                            val currentIndex = binding.imagePager.currentItem
                            if (currentIndex in 0 until adapter.itemCount) {
                                adapter.setManualBitmap(currentIndex, state.previewBitmap)
                            }
                        }
                    }
                    if (state.activeConfig != null && ::adapter.isInitialized && adapter.itemCount > 0) {
                        val currentIndex = binding.imagePager.currentItem
                        if (currentIndex in 0 until adapter.itemCount) {
                            val currentGroup = adapter.getGroup(currentIndex)
                            if (currentGroup.isRawVideo || currentGroup.rawVideoUri != null) {
                                adapter.updateRawVideoAdjustments(currentIndex, state.activeConfig)
                            }
                        }
                    }
                    updateEditUi()
                    updateEffectsButtons()
                }
            }
        }

        binding.imagePager.registerOnPageChangeCallback(pageChangeCallback)
        loadImages(forceRefresh = false)

        viewLifecycleOwner.lifecycleScope.launch {
            ColorProcessor.backgroundSaveFlow.collect { event ->
                if (isAdjusted || !::adapter.isInitialized) return@collect
                handleBackgroundSaveEvent(event)
            }
        }
    }

    protected open fun loadImages(targetUri: String? = args.initialUri, forceRefresh: Boolean = false) {
        if (::mediaStrategy.isInitialized && mediaStrategy.isSandbox) {
            binding.initialLoadingIndicator.visibility = View.VISIBLE
            binding.imagePager.visibility = View.INVISIBLE

            viewLifecycleOwner.lifecycleScope.launch {
                val groups = mediaStrategy.loadInitialGroups(requireContext(), arguments, repository)
                if (!groups.isNullOrEmpty()) {
                    if (binding.imagePager.adapter == null) {
                        adapter = ImageViewerAdapter(groups, lifecycleScope, requireContext()).apply {
                            isMotionPhotoAutoPlay = this@ImageViewerFragment.isMotionPhotoAutoPlay
                            onImageTapped = { toggleUi() }
                            onZoomChanged = { isZoomed -> if (isZoomed) hideUi() else showUi() }
                            onLongPressStarted = { handleLongPressStarted(it) }
                            onLongPressEnded = { handleLongPressEnded(it) }
                            onMotionPhotoIndicatorTapped = { pos -> handleMotionPhotoIndicatorTapped(pos) }
                            onFormatChanged = { _, _ -> updateControlsVisibility() }
                            onDerivativeVersionChanged = { _, _ -> updateControlsVisibility() }
                            setFormatSwitcherPersistentHidden(isAdjusted)
                            onCurrentListChanged = { previousList, currentList ->
                                val currentIndex = binding.imagePager.currentItem
                                if (currentIndex in currentList.indices) {
                                    val currentGroup = currentList[currentIndex]
                                    val prevGroup = previousList.getOrNull(currentIndex)
                                    if (prevGroup != null && !prevGroup.metadataLoaded && currentGroup.metadataLoaded) {
                                        if (currentGroup.editConfig != null) {
                                            prepareEditConfig(currentGroup)
                                        }
                                    }
                                    updateControlsVisibility()
                                }
                            }
                        }
                        binding.imagePager.adapter = adapter
                        binding.imagePager.isUserInputEnabled = !isAdjusted
                        setupActionButtons()

                        val initialGroup = groups[0]
                        val playgroundPaths = arguments?.getStringArray("playground_dng_paths")
                        if (groups.size == 1 && playgroundPaths != null && playgroundPaths.size == 2 && initialGroup.jpgUri == null) {
                            isAdjusted = true
                            adapter.setFormatSwitcherPersistentHidden(true)
                            updateControlsVisibility()
                        }

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

        binding.initialLoadingIndicator.visibility = View.VISIBLE
        binding.imagePager.visibility = View.INVISIBLE

        if (forceRefresh) {
            repository.invalidateCache(clearStale = true)
        }

        var hasNavigatedToTargetUri = false

        imagesCollectJob?.cancel()
        imagesCollectJob = lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.getGroupedImagesFlow(targetUri).collect { groups ->
                    if (groups.isEmpty()) {
                        findNavController().navigateUp()
                        return@collect
                    }

                    val isFirstLoad = !::adapter.isInitialized

                    val targetBaseName = targetUri?.let { top.maary.darkbag.utils.ImageUtils.getBaseName(it) }
                    val targetPos = if (args.targetPosition >= 0 && isFirstLoad) {
                        args.targetPosition
                    } else if (targetUri != null) {
                        val pos = groups.indexOfFirst {
                            (targetBaseName != null && it.baseName == targetBaseName) ||
                                    it.rawVideoUri?.toString() == targetUri ||
                                    it.mp4VideoUri?.toString() == targetUri ||
                                    it.jpgUri?.toString() == targetUri ||
                                    it.dngUri?.toString() == targetUri ||
                                    it.dngUri1?.toString() == targetUri ||
                                    it.dngUri2?.toString() == targetUri ||
                                    it.derivativeJpgUris.any { u -> u.toString() == targetUri } ||
                                    it.derivativeMp4Uris.any { u -> u.toString() == targetUri } ||
                                    it.multiJpgUris.any { u -> u.toString() == targetUri } ||
                                    it.multiDngUris.any { u -> u.toString() == targetUri }
                        }
                        if (pos != -1) pos else {
                            groups.indexOfFirst { it.baseName.isNotEmpty() && targetUri.contains(it.baseName) }
                        }
                    } else -1

                    if (isFirstLoad) {
                        adapter = ImageViewerAdapter(groups, lifecycleScope, requireContext()).apply {
                            isMotionPhotoAutoPlay = this@ImageViewerFragment.isMotionPhotoAutoPlay
                            onImageTapped = { toggleUi() }
                            onZoomChanged = { isZoomed -> if (isZoomed) hideUi() else showUi() }
                            onLongPressStarted = { handleLongPressStarted(it) }
                            onLongPressEnded = { handleLongPressEnded(it) }
                            onMotionPhotoIndicatorTapped = { pos -> handleMotionPhotoIndicatorTapped(pos) }
                            onPinchToOverview = { openDarkroomGallery() }
                            onMultiCameraLensChanged = { _, _ ->
                                updateSplitButtons()
                                updateToolbarIcon()
                                updateControlsVisibility()
                            }
                            onFormatChanged = { _, _ ->
                                updateControlsVisibility()
                            }
                            onDerivativeVersionChanged = { _, _ ->
                                updateControlsVisibility()
                            }
                            onCinemaDngFrameSelected = { group, index, uri ->
                                val currentPosition = binding.imagePager.currentItem
                                if (currentPosition in adapter.getGroups().indices && adapter.getGroup(currentPosition).baseName == group.baseName) {
                                    currentCinemaDngFrame = Pair(index, uri)
                                    binding.filmstripBentoSeekbar?.setProgress(index, group.cinemaDngFrameUris.size.takeIf { it > 0 } ?: 1)
                                }
                            }
                            onVideoPlaybackStateChanged = { position, isPlaying, currentMs, totalMs ->
                                if (_binding != null && ::adapter.isInitialized && position == binding.imagePager.currentItem) {
                                    updateVideoPlaybackButton(isPlaying, currentMs, totalMs)
                                }
                            }
                            setFormatSwitcherPersistentHidden(isAdjusted)
                            onCurrentListChanged = { previousList, currentList ->
                                val currentIndex = binding.imagePager.currentItem
                                updateGalleryPill(currentIndex, currentList.size)
                                if (currentIndex in currentList.indices) {
                                    val currentGroup = currentList[currentIndex]
                                    val prevGroup = previousList.getOrNull(currentIndex)

                                    if ((prevGroup == null || !prevGroup.metadataLoaded) && currentGroup.metadataLoaded) {
                                        if (currentGroup.editConfig != null) {
                                            prepareEditConfig(currentGroup)
                                        }
                                    }

                                    updateControlsVisibility()
                                }
                            }
                        }
                        binding.imagePager.adapter = adapter

                        val initialIndex = if (targetPos != -1) targetPos else 0
                        if (targetPos != -1) {
                            hasNavigatedToTargetUri = true
                        }
                        binding.imagePager.setCurrentItem(initialIndex, false)
                        if (args.targetLensTag != null && initialIndex in groups.indices) {
                            val lenses = adapter.getMultiCameraLenses(groups[initialIndex])
                            val lensIndex = lenses.indexOfFirst { it.lensTag == args.targetLensTag }
                            if (lensIndex != -1) {
                                adapter.setSelectedLensIndex(initialIndex, lensIndex)
                            }
                        }

                        // Load metadata for the initial image asynchronously without blocking collector
                        val initialGroup = groups.getOrNull(initialIndex)
                        if (initialGroup != null) {
                            if (!initialGroup.metadataLoaded) {
                                lifecycleScope.launch(Dispatchers.IO) {
                                    val updatedGroup = repository.loadMetadata(initialGroup)
                                    withContext(Dispatchers.Main) {
                                        if (_binding == null || !isAdded || !::adapter.isInitialized) return@withContext
                                        prepareEditConfig(updatedGroup)
                                        safeUpdateSingleGroup(updatedGroup)
                                        if (binding.imagePager.currentItem == initialIndex && !isAdjusted) {
                                            updateControlsVisibility()
                                        }
                                        if (!isAdjusted && isMotionPhotoAutoPlay && updatedGroup.isMotionPhoto && hasAutoPlayedPosition != initialIndex && binding.imagePager.currentItem == initialIndex) {
                                            hasAutoPlayedPosition = initialIndex
                                            adapter.playMotionVideoForPosition(initialIndex)
                                        }
                                    }
                                }
                            } else {
                                prepareEditConfig(initialGroup)
                                if (!isAdjusted && isMotionPhotoAutoPlay && initialGroup.isMotionPhoto && hasAutoPlayedPosition != initialIndex) {
                                    hasAutoPlayedPosition = initialIndex
                                    adapter.playMotionVideoForPosition(initialIndex)
                                }
                            }
                        }

                        binding.imagePager.isUserInputEnabled = !isAdjusted
                        setupActionButtons()
                        updateControlsVisibility()
                        updateGalleryPill(binding.imagePager.currentItem, groups.size)
                        preloadAdjacentMetadata(binding.imagePager.currentItem)

                        binding.imagePager.visibility = View.VISIBLE
                        binding.initialLoadingIndicator.visibility = View.GONE
                    } else {
                        adapter.updateGroups(groups)
                        if (targetUri != null && !hasNavigatedToTargetUri && targetPos != -1) {
                            hasNavigatedToTargetUri = true
                            binding.imagePager.setCurrentItem(targetPos, false)
                        }
                        updateGalleryPill(binding.imagePager.currentItem, groups.size)
                        preloadAdjacentMetadata(binding.imagePager.currentItem)
                        binding.imagePager.visibility = View.VISIBLE
                        binding.initialLoadingIndicator.visibility = View.GONE

                        val currentIndex = binding.imagePager.currentItem
                        if (currentIndex in groups.indices) {
                            val currentGroup = groups[currentIndex]
                            if (!currentGroup.metadataLoaded) {
                                lifecycleScope.launch(Dispatchers.IO) {
                                    val updatedGroup = repository.loadMetadata(currentGroup)
                                    withContext(Dispatchers.Main) {
                                        if (_binding == null || !isAdded || !::adapter.isInitialized) return@withContext
                                        safeUpdateSingleGroup(updatedGroup)
                                        val index = adapter.getGroups().indexOfFirst { it.baseName == updatedGroup.baseName }
                                        if (index == binding.imagePager.currentItem && !isAdjusted) {
                                            updateControlsVisibility()
                                        }
                                        if (!isAdjusted && isMotionPhotoAutoPlay && hasAutoPlayedPosition != index && updatedGroup.isMotionPhoto && binding.imagePager.currentItem == index) {
                                            hasAutoPlayedPosition = index
                                            adapter.playMotionVideoForPosition(index)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

    private fun handleBackgroundSaveEvent(event: ColorProcessor.BackgroundSaveEvent) {
        // Trigger a full repository refresh to include the new item
        lifecycleScope.launch {
            repository.invalidateCache()
            val newGroups = repository.getGroupedImages(forceRefresh = true)
            adapter.updateGroups(newGroups)
            // Force update the current edit config from the newly loaded metadata
            val currentIndex = binding.imagePager.currentItem
            if (currentIndex in newGroups.indices) {
                prepareEditConfig(newGroups[currentIndex])
            }
        }
    }

    protected fun updateControlsVisibility() {
        if (!::adapter.isInitialized || adapter.itemCount == 0) return
        val currentGroup = adapter.getGroup(binding.imagePager.currentItem)
        val isRawVideo = currentGroup.isRawVideo || currentGroup.rawVideoUri != null
        val canEdit = ((currentGroup.dngUri != null || currentGroup.dngUri1 != null || currentGroup.dngUri2 != null || currentGroup.multiDngUris.isNotEmpty()) && !currentGroup.isPartial) || isRawVideo

        val showDeck = isUiVisible && !isEditingAdjustments
        binding.viewerBentoDeck.visibility = if (showDeck) View.VISIBLE else View.GONE
        binding.btnLogCurve.isEnabled = canEdit
        binding.btnLogCurve.isClickable = canEdit
        binding.btnLogCurve.alpha = if (canEdit) 1.0f else 0.65f
        binding.btn3dLut.isEnabled = canEdit
        binding.btn3dLut.isClickable = canEdit
        binding.btn3dLut.alpha = if (canEdit) 1.0f else 0.65f
        binding.btnAdjustTofu.isEnabled = canEdit
        binding.btnAdjustTofu.isClickable = canEdit
        binding.btnAdjustTofu.alpha = if (canEdit) 1.0f else 0.65f

        if (canEdit && currentEditConfig == null) {
            prepareEditConfig(currentGroup)
        }

        if (canEdit && currentGroup.isHalfFrame() && !currentGroup.isPartial) {
            binding.hfExtraControls.visibility = View.VISIBLE
            updateEffectsButtons()
        } else {
            binding.hfExtraControls.visibility = View.GONE
        }

        updateFormatBentoRow()
        updateSplitButtons()
        updateToolbarIcon()
        updateEditUi()
        updateEffectsButtons()
        updateSelectionFeedback()
        updateBackPressedCallbackState()
    }

    protected fun updateFormatBentoRow() {
        val currentBinding = _binding ?: return
        if (!::adapter.isInitialized || adapter.itemCount == 0) return
        val currentIndex = currentBinding.imagePager.currentItem
        if (currentIndex !in 0 until adapter.itemCount) return
        val currentGroup = adapter.getGroup(currentIndex)
        val ctx = context ?: return
        val density = ctx.resources.displayMetrics.density

        (currentBinding.viewerBentoDeck as? BentoTrayCard)?.let { deckCard ->
            val role = when {
                currentGroup.isHalfFrame() -> BentoContainerRole.TERTIARY_CONTAINER
                currentGroup.isMultiCamera -> BentoContainerRole.SECONDARY_CONTAINER
                else -> BentoContainerRole.SURFACE_CONTAINER_LOW
            }
            deckCard.setContainerRole(role, animated = true)
        }

        val currentFormat = adapter.getSelectedFormat(currentIndex)
        val isRawSelected = currentFormat == ImageViewerAdapter.FORMAT_DNG

        val lenses = if (currentGroup.isMultiCamera) adapter.getMultiCameraLenses(currentGroup) else emptyList()
        val activeLensIndex = if (currentGroup.isMultiCamera && lenses.isNotEmpty()) {
            adapter.getSelectedLensIndex(currentIndex).coerceIn(0, lenses.size - 1)
        } else 0
        val activeLens = if (currentGroup.isMultiCamera && lenses.isNotEmpty()) {
            lenses[activeLensIndex]
        } else null
        val activeLensTag = activeLens?.lensTag?.ifEmpty {
            String.format(java.util.Locale.US, "%.1fx", activeLens.multiplier)
        } ?: "1.0x"

        val hasDng = if (currentGroup.isMultiCamera) {
            activeLens?.dngUri != null
        } else {
            currentGroup.hasMasterRaw
        }

        val derivatives = adapter.getDerivativeUris(currentGroup)
        val hasDerivatives = if (currentGroup.isMultiCamera) {
            activeLens?.jpgUri != null
        } else {
            derivatives.isNotEmpty()
        }

        val isRawVideo = currentGroup.isRawVideo || currentGroup.rawVideoUri != null
        val isCinemaDng = currentGroup.isCinemaDng || currentGroup.cinemaDngFolderUri != null || currentGroup.cinemaDngFirstFrameUri != null || currentGroup.cinemaDngFrameUris.isNotEmpty()
        val activeDerivIdx = adapter.getSelectedDerivativeIndex(currentGroup.baseName).coerceIn(0, (derivatives.size - 1).coerceAtLeast(0))
        val activeDerivativeUri = derivatives.getOrNull(activeDerivIdx) ?: currentGroup.jpgUri ?: currentGroup.mp4VideoUri
        val isMp4Active = !isRawSelected && activeDerivativeUri != null && (activeDerivativeUri.toString().endsWith(".mp4", ignoreCase = true) || currentGroup.isMp4Video)

        val colorPrimary = com.google.android.material.color.MaterialColors.getColor(ctx, android.R.attr.colorPrimary, android.graphics.Color.WHITE)
        val colorPrimaryContainer = com.google.android.material.color.MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorPrimaryContainer, android.graphics.Color.DKGRAY)
        val colorOnPrimaryContainer = com.google.android.material.color.MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOnPrimaryContainer, android.graphics.Color.WHITE)
        val colorSecondaryContainer = com.google.android.material.color.MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorSecondaryContainer, android.graphics.Color.DKGRAY)
        val colorOnSecondaryContainer = com.google.android.material.color.MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOnSecondaryContainer, android.graphics.Color.WHITE)
        val colorSurfaceContainerHigh = com.google.android.material.color.MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorSurfaceContainerHigh, android.graphics.Color.GRAY)
        val colorOnSurfaceVariant = com.google.android.material.color.MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOnSurfaceVariant, android.graphics.Color.LTGRAY)
        val colorOutlineVariant = com.google.android.material.color.MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOutlineVariant, android.graphics.Color.GRAY)

        val showDerivCycle = !currentGroup.isMultiCamera && !isRawSelected && derivatives.size >= 2
        val showMotionLive = !currentGroup.isMultiCamera && currentGroup.isMotionPhoto && !isRawSelected && !isAdjusted
        val hasContextualAddons = showMotionLive

        if (hasContextualAddons) {
            currentBinding.viewerFormatRow?.visibility = View.VISIBLE
            currentBinding.btnFormatSwitch?.visibility = View.VISIBLE
            currentBinding.btnFormatSwitchCompact?.visibility = View.GONE
            currentBinding.btnDerivativeCycleCompact?.visibility = View.GONE
        } else {
            currentBinding.viewerFormatRow?.visibility = View.GONE
            currentBinding.btnFormatSwitchCompact?.visibility = View.VISIBLE
            currentBinding.btnDerivativeCycleCompact?.visibility = if (showDerivCycle) View.VISIBLE else View.GONE
        }

        currentBinding.btnFormatSwitch?.let { btn ->
            when {
                hasDng && hasDerivatives -> {
                    btn.isEnabled = !isAdjusted
                    btn.isClickable = !isAdjusted
                    if (currentGroup.isMultiCamera) {
                        val line1 = "$activeLensTag · ${if (isRawSelected) "DNG" else "JPG"}"
                        val line2 = "JPG ⇄ DNG"
                        btn.text = top.maary.darkbag.ui.CameraBentoDeckView.formatProTelemetry(
                            label = line1,
                            value = line2,
                            isTabActive = isRawSelected,
                            isValueManual = false,
                            context = ctx
                        )
                    } else if (isRawSelected) {
                        val line1 = when {
                            isRawVideo -> "MASTER · RAWVID"
                            isCinemaDng -> "MASTER · CDNG"
                            else -> "MASTER · DNG"
                        }
                        val line2 = if (isRawVideo || currentGroup.mp4VideoUri != null) "⇄ SWITCH MP4" else "⇄ SWITCH JPG"
                        btn.text = top.maary.darkbag.ui.CameraBentoDeckView.formatProTelemetry(
                            label = line1,
                            value = line2,
                            isTabActive = true,
                            isValueManual = false,
                            context = ctx
                        )
                    } else {
                        val line1 = if (isMp4Active) "VIEW · MP4" else "VIEW · JPG"
                        val line2 = when {
                            isRawVideo -> "⇄ SWITCH RAWVID"
                            isCinemaDng -> "⇄ SWITCH CDNG"
                            else -> "⇄ SWITCH DNG"
                        }
                        btn.text = top.maary.darkbag.ui.CameraBentoDeckView.formatProTelemetry(
                            label = line1,
                            value = line2,
                            isTabActive = false,
                            isValueManual = false,
                            context = ctx
                        )
                    }

                    if (isRawSelected) {
                        btn.backgroundTintList = android.content.res.ColorStateList.valueOf(colorPrimaryContainer)
                        btn.setTextColor(colorOnPrimaryContainer)
                        btn.strokeColor = android.content.res.ColorStateList.valueOf(colorPrimary)
                        btn.strokeWidth = (1.5f * density).toInt()
                        btn.cornerRadius = (10f * density).toInt()
                    } else {
                        btn.backgroundTintList = android.content.res.ColorStateList.valueOf(colorSecondaryContainer)
                        btn.setTextColor(colorOnSecondaryContainer)
                        btn.strokeColor = android.content.res.ColorStateList.valueOf(colorOutlineVariant)
                        btn.strokeWidth = (1f * density).toInt()
                        btn.cornerRadius = (14f * density).toInt()
                    }
                }
                hasDng && !hasDerivatives -> {
                    btn.isEnabled = false
                    btn.isClickable = false
                    val line1 = if (currentGroup.isMultiCamera) "$activeLensTag · DNG" else "FORMAT"
                    val line2 = when {
                        isRawVideo -> "RAWVID ONLY"
                        isCinemaDng -> "CDNG ONLY"
                        else -> "DNG ONLY"
                    }
                    btn.text = top.maary.darkbag.ui.CameraBentoDeckView.formatProTelemetry(
                        label = line1,
                        value = line2,
                        isTabActive = false,
                        isValueManual = false,
                        context = ctx
                    )
                    btn.backgroundTintList = android.content.res.ColorStateList.valueOf(colorSurfaceContainerHigh)
                    btn.strokeColor = android.content.res.ColorStateList.valueOf(colorOutlineVariant)
                    btn.strokeWidth = (1f * density).toInt()
                    btn.cornerRadius = (14f * density).toInt()
                }
                else -> {
                    btn.isEnabled = false
                    btn.isClickable = false
                    val line1 = if (currentGroup.isMultiCamera) "$activeLensTag · JPG" else "FORMAT"
                    val line2 = if (isMp4Active) "MP4 ONLY" else "JPG ONLY"
                    btn.text = top.maary.darkbag.ui.CameraBentoDeckView.formatProTelemetry(
                        label = line1,
                        value = line2,
                        isTabActive = false,
                        isValueManual = false,
                        context = ctx
                    )
                    btn.backgroundTintList = android.content.res.ColorStateList.valueOf(colorSurfaceContainerHigh)
                    btn.strokeColor = android.content.res.ColorStateList.valueOf(colorOutlineVariant)
                    btn.strokeWidth = (1f * density).toInt()
                    btn.cornerRadius = (14f * density).toInt()
                }
            }
        }

        currentBinding.btnFormatSwitchCompact?.let { btn ->
            val isRawAvailable = hasDng
            val rawLabel = when {
                isRawVideo -> "RAWVID"
                isCinemaDng -> "CDNG"
                else -> "RAW"
            }
            val nonRawLabel = if (isMp4Active) "MP4" else "JPG"

            if (isRawSelected) {
                btn.isEnabled = !isAdjusted
                btn.isClickable = !isAdjusted
                btn.text = rawLabel
                btn.backgroundTintList = android.content.res.ColorStateList.valueOf(colorPrimaryContainer)
                btn.setTextColor(colorOnPrimaryContainer)
                btn.strokeColor = android.content.res.ColorStateList.valueOf(colorPrimary)
                btn.strokeWidth = (1.5f * density).toInt()
                btn.cornerRadius = (14f * density).toInt()
            } else if (isRawAvailable) {
                btn.isEnabled = !isAdjusted
                btn.isClickable = !isAdjusted
                btn.text = rawLabel
                btn.backgroundTintList = android.content.res.ColorStateList.valueOf(colorSurfaceContainerHigh)
                btn.setTextColor(colorOnSurfaceVariant)
                btn.strokeColor = android.content.res.ColorStateList.valueOf(colorOutlineVariant)
                btn.strokeWidth = (1f * density).toInt()
                btn.cornerRadius = (14f * density).toInt()
            } else {
                btn.isEnabled = false
                btn.isClickable = false
                btn.text = nonRawLabel
                btn.backgroundTintList = android.content.res.ColorStateList.valueOf(colorSurfaceContainerHigh)
                btn.setTextColor(androidx.core.graphics.ColorUtils.setAlphaComponent(colorOnSurfaceVariant, 120))
                btn.strokeWidth = 0
                btn.cornerRadius = (14f * density).toInt()
            }
        }

        currentBinding.btnDerivativeCycleCompact?.let { btn ->
            val showCompactLook = !hasContextualAddons && showDerivCycle
            btn.visibility = if (showCompactLook) View.VISIBLE else View.GONE
            if (showCompactLook) {
                val dotColor = LOOK_DOT_COLORS[activeDerivIdx % LOOK_DOT_COLORS.size]
                val dotColorAlpha = androidx.core.graphics.ColorUtils.setAlphaComponent(dotColor, 200)

                val text = "V${activeDerivIdx + 1} ●"
                val spannable = android.text.SpannableStringBuilder(text)
                val dotIdx = text.indexOf("●")
                if (dotIdx >= 0) {
                    spannable.setSpan(
                        android.text.style.ForegroundColorSpan(dotColor),
                        dotIdx,
                        dotIdx + 1,
                        android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                btn.text = spannable
                btn.icon = null

                btn.backgroundTintList = android.content.res.ColorStateList.valueOf(colorSurfaceContainerHigh)
                btn.setTextColor(colorOnSurfaceVariant)
                btn.strokeColor = android.content.res.ColorStateList.valueOf(dotColorAlpha)
                btn.strokeWidth = (1.5f * density).toInt()
                btn.cornerRadius = (14f * density).toInt()
            }
        }

        currentBinding.btnDerivativeCycle?.let { btn ->
            val showExpandedLook = hasContextualAddons && showDerivCycle
            btn.visibility = if (showExpandedLook) View.VISIBLE else View.GONE
            if (showExpandedLook) {
                val dotColor = LOOK_DOT_COLORS[activeDerivIdx % LOOK_DOT_COLORS.size]
                val dotColorAlpha = androidx.core.graphics.ColorUtils.setAlphaComponent(dotColor, 200)

                val line1 = "LOOK"
                val line2 = "V${activeDerivIdx + 1} / V${derivatives.size} ●"
                val formatted = top.maary.darkbag.ui.CameraBentoDeckView.formatProTelemetry(
                    label = line1,
                    value = line2,
                    isTabActive = false,
                    isValueManual = true,
                    context = ctx
                )
                val spannable = formatted as? android.text.SpannableStringBuilder ?: android.text.SpannableStringBuilder(formatted)
                val dotIdx = spannable.indexOf("●")
                if (dotIdx >= 0) {
                    spannable.setSpan(
                        android.text.style.ForegroundColorSpan(dotColor),
                        dotIdx,
                        dotIdx + 1,
                        android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                btn.text = spannable
                btn.icon = null

                btn.backgroundTintList = android.content.res.ColorStateList.valueOf(colorSurfaceContainerHigh)
                btn.strokeColor = android.content.res.ColorStateList.valueOf(dotColorAlpha)
                btn.strokeWidth = (1.5f * density).toInt()
                btn.cornerRadius = (14f * density).toInt()
            }
        }

        currentBinding.btnMotionLive?.let { btn ->
            btn.visibility = if (showMotionLive) View.VISIBLE else View.GONE
            if (showMotionLive) {
                val line1 = "LIVE PHOTO"
                val line2 = if (isMotionPhotoAutoPlay) "AUTO ON" else "PAUSED"
                val liveText = top.maary.darkbag.ui.CameraBentoDeckView.formatProTelemetry(
                    label = line1,
                    value = line2,
                    isTabActive = isMotionPhotoAutoPlay,
                    isValueManual = isMotionPhotoAutoPlay,
                    context = ctx
                )
                btn.text = liveText
                if (isMotionPhotoAutoPlay) {
                    btn.backgroundTintList = android.content.res.ColorStateList.valueOf(colorPrimaryContainer)
                    btn.setTextColor(colorOnPrimaryContainer)
                    btn.strokeColor = android.content.res.ColorStateList.valueOf(colorPrimary)
                    btn.strokeWidth = (1.5f * density).toInt()
                    btn.cornerRadius = (12f * density).toInt()
                } else {
                    btn.backgroundTintList = android.content.res.ColorStateList.valueOf(colorSurfaceContainerHigh)
                    btn.setTextColor(colorOnSurfaceVariant)
                    btn.strokeColor = android.content.res.ColorStateList.valueOf(colorOutlineVariant)
                    btn.strokeWidth = (1f * density).toInt()
                    btn.cornerRadius = (12f * density).toInt()
                }
            }
        }

        if (currentGroup.isMultiCamera && lenses.isNotEmpty()) {
            currentBinding.viewerMulticamRow?.visibility = View.VISIBLE
            currentBinding.multicamLensContainer?.let { container ->
                container.removeAllViews()
                for ((idx, lens) in lenses.withIndex()) {
                    val isSelected = (idx == activeLensIndex)
                    val baseTag = if (lens.lensTag.isNotBlank()) lens.lensTag else String.format(java.util.Locale.US, "%.1fx", lens.multiplier)
                    val lensLabel = when {
                        baseTag.contains("(") -> baseTag
                        lens.multiplier < 0.9f -> "$baseTag (UW)"
                        lens.multiplier in 0.9f..1.5f -> "$baseTag (W)"
                        lens.multiplier > 1.5f -> "$baseTag (T)"
                        else -> baseTag
                    }

                    val lensBtn = com.google.android.material.button.MaterialButton(
                        ctx,
                        null,
                        com.google.android.material.R.attr.materialButtonTonalStyle
                    ).apply {
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            0,
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            1.0f
                        ).apply {
                            if (idx > 0) marginStart = (2f * density).roundToInt()
                            if (idx < lenses.size - 1) marginEnd = (2f * density).roundToInt()
                        }
                        text = lensLabel
                        textSize = 10.5f
                        isSingleLine = true
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        gravity = android.view.Gravity.CENTER
                        insetTop = 0
                        insetBottom = 0
                        setPadding(0, 0, 0, 0)
                        minWidth = 0
                        minimumWidth = 0

                        if (isSelected) {
                            cornerRadius = (10f * density).roundToInt()
                            backgroundTintList = android.content.res.ColorStateList.valueOf(colorPrimaryContainer)
                            setTextColor(colorOnPrimaryContainer)
                            strokeColor = android.content.res.ColorStateList.valueOf(colorPrimary)
                            strokeWidth = (1.5f * density).roundToInt()
                        } else {
                            cornerRadius = (14f * density).roundToInt()
                            backgroundTintList = android.content.res.ColorStateList.valueOf(colorSurfaceContainerHigh)
                            setTextColor(colorOnSurfaceVariant)
                            strokeColor = android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
                            strokeWidth = 0
                        }

                        setOnClickListener {
                            it.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                            val curPos = currentBinding.imagePager.currentItem
                            if (adapter.getSelectedLensIndex(curPos) != idx) {
                                adapter.setSelectedLensIndex(curPos, idx)
                                updateControlsVisibility()
                            }
                        }
                    }
                    container.addView(lensBtn)
                }
            }

            currentBinding.btnMulticamCollage?.apply {
                text = CameraBentoDeckView.formatProTelemetry(
                    label = "⊞ COLLAGE",
                    value = "多摄拼图",
                    isTabActive = true,
                    isValueManual = false,
                    context = ctx
                )
                setOnClickListener {
                    it.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                    val curPos = currentBinding.imagePager.currentItem
                    val grp = adapter.getGroup(curPos)
                    if (grp.isMultiCamera) {
                        showMultiCamCollageDialog(grp)
                    }
                }
            }
        } else {
            currentBinding.viewerMulticamRow?.visibility = View.GONE
            currentBinding.multicamLensContainer?.removeAllViews()
        }

        val isVideoActive = isRawVideo || isCinemaDng || currentGroup.isMp4Video || currentGroup.mp4VideoUri != null || isMp4Active

        if (isVideoActive) {
            currentBinding.viewerVideoDeck?.visibility = View.VISIBLE
            if (isCinemaDng) {
                currentBinding.filmstripBentoContainer?.visibility = View.VISIBLE
                val frameUris = if (currentGroup.cinemaDngFrameUris.isNotEmpty()) {
                    currentGroup.cinemaDngFrameUris
                } else if (currentGroup.cinemaDngFirstFrameUri != null) {
                    listOf(currentGroup.cinemaDngFirstFrameUri)
                } else {
                    emptyList()
                }
                val activeIndex = currentCinemaDngFrame?.first ?: adapter.getActiveCinemaDngFrame(currentIndex)?.first ?: 0
                currentBinding.filmstripBentoSeekbar?.setFrames(frameUris, activeIndex)
            } else {
                currentBinding.filmstripBentoContainer?.visibility = View.GONE
            }

            val exportSub = if (isCinemaDng) "CDNG / MP4 / FRAME" else "MP4 / FRAME / DNG"
            currentBinding.btnVideoExportHub?.text = top.maary.darkbag.ui.CameraBentoDeckView.formatProTelemetry(
                label = "↗ EXPORT HUB",
                value = exportSub,
                isTabActive = false,
                isValueManual = false,
                context = ctx
            )

            val isPlaying = adapter.isVideoPlaying(currentIndex)
            val curMs = adapter.getVideoCurrentPosition(currentIndex)
            val totalMs = adapter.getVideoDuration(currentIndex)
            updateVideoPlaybackButton(isPlaying, curMs, totalMs)
        } else {
            currentBinding.viewerVideoDeck?.visibility = View.GONE
        }
    }

    protected fun updateVideoPlaybackButton(isPlaying: Boolean, currentMs: Long, totalMs: Long) {
        val currentBinding = _binding ?: return
        val btn = currentBinding.btnVideoPlayback ?: return
        val ctx = context ?: return
        val density = ctx.resources.displayMetrics.density

        val colorPrimary = com.google.android.material.color.MaterialColors.getColor(ctx, android.R.attr.colorPrimary, android.graphics.Color.WHITE)
        val colorPrimaryContainer = com.google.android.material.color.MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorPrimaryContainer, android.graphics.Color.DKGRAY)
        val colorOnPrimaryContainer = com.google.android.material.color.MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOnPrimaryContainer, android.graphics.Color.WHITE)
        val colorOutlineVariant = com.google.android.material.color.MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOutlineVariant, android.graphics.Color.GRAY)

        val line1 = if (isPlaying) "❚❚ PAUSE" else "▶ PLAY"
        val line2 = if (totalMs > 0) {
            "${formatVideoTime(currentMs)} / ${formatVideoTime(totalMs)}"
        } else {
            val currentIndex = currentBinding.imagePager.currentItem
            if (::adapter.isInitialized && currentIndex in adapter.getGroups().indices) {
                val group = adapter.getGroup(currentIndex)
                if (group.isCinemaDng || group.cinemaDngFrameUris.isNotEmpty()) {
                    val count = group.cinemaDngFrameUris.size.takeIf { it > 0 } ?: group.cinemaDngFrameCount
                    val sec = (count / 24.0f).roundToInt()
                    "24 FPS · ${sec}s"
                } else if (group.isRawVideo) {
                    val fps = group.rawVideoFps.takeIf { it > 0 } ?: 24.0f
                    val sec = (group.rawVideoFrameCount / fps).roundToInt()
                    "${fps.toInt()} FPS · ${sec}s"
                } else {
                    "00:00 / 00:00"
                }
            } else {
                "00:00 / 00:00"
            }
        }

        btn.text = top.maary.darkbag.ui.CameraBentoDeckView.formatProTelemetry(
            label = line1,
            value = line2,
            isTabActive = isPlaying,
            isValueManual = false,
            context = ctx
        )

        btn.backgroundTintList = android.content.res.ColorStateList.valueOf(colorPrimaryContainer)
        btn.setTextColor(colorOnPrimaryContainer)
        if (isPlaying) {
            btn.strokeColor = android.content.res.ColorStateList.valueOf(colorPrimary)
            btn.strokeWidth = (1.5f * density).toInt()
            btn.cornerRadius = (14f * density).toInt()
        } else {
            btn.strokeColor = android.content.res.ColorStateList.valueOf(colorOutlineVariant)
            btn.strokeWidth = (1f * density).toInt()
            btn.cornerRadius = (18f * density).toInt()
        }
    }

    private fun formatVideoTime(millis: Long): String {
        val totalSec = (millis / 1000).coerceAtLeast(0)
        val minutes = totalSec / 60
        val seconds = totalSec % 60
        return String.format(java.util.Locale.US, "%02d:%02d", minutes, seconds)
    }

    protected fun prepareEditConfig(group: ImageGroup) {
        // Reset cached data to prevent cross-contamination between different image groups
        sourceDngBytes = null
        sourceDngBytes2 = null
        cachedBitmap1 = null
        cachedBitmap2 = null
        lastPreviewConfig = null
        selectedDngIndex = 0
        userExplicitlyChangedLog = false
        previewJob?.cancel()

        currentEditConfig = group.editConfig
            ?.normalizedForRender()
            ?.copy()
            ?: top.maary.darkbag.models.EditConfig(
                adjustments = if (group.isHalfFrame()) listOf(top.maary.darkbag.models.BasicAdjustments(), top.maary.darkbag.models.BasicAdjustments()) else null
            )

        // Solidify "Random" flare type to prevent jumping during edits
        currentEditConfig = currentEditConfig?.let { config ->
            if (config.flareType == 0) {
                val resolved = java.util.Random().nextInt(2) + 1
                config.copy(flareType = resolved)
            } else config
        }

        updateEditUi()
        updateEffectsButtons()
        editorViewModel.processIntent(DarkroomEditorUserIntent.SetActiveGroup(group))
        // DNG bytes and deep EXIF will be loaded on-demand when entering edit flow
    }

    protected open suspend fun ensureDngBytesLoaded() {
        if (sourceDngBytes != null) return
        val group = adapter.getGroup(binding.imagePager.currentItem)
        val dngUri1 = if (group.isMultiCamera) {
            val idx = adapter.getSelectedLensIndex(binding.imagePager.currentItem)
            group.multiDngUris.getOrNull(idx) ?: group.multiDngUris.firstOrNull()
        } else {
            group.dngUri ?: group.dngUri1
        }
        val dngUri2 = group.dngUri2
        val context = context ?: return

        withContext(Dispatchers.IO) {
            try {
                dngUri1?.let { uri ->
                    context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                        sourceDngBytes = java.io.FileInputStream(pfd.fileDescriptor).use { it.readBytes() }
                    }
                }
                dngUri2?.let { uri ->
                    context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                        sourceDngBytes2 = java.io.FileInputStream(pfd.fileDescriptor).use { it.readBytes() }
                    }
                }
                if (sourceDngBytes != null || sourceDngBytes2 != null) {
                    editorViewModel.processIntent(DarkroomEditorUserIntent.SetDngBytes(sourceDngBytes, sourceDngBytes2))
                }

                // Fallback: Load digital gain from DNG EXIF only if missing from JPG
                val currentConfig = currentEditConfig
                if (currentConfig != null && group.editConfig == null) {
                    if (currentConfig.digitalGain == 1.0f && currentConfig.adjustments == null && currentConfig.exposure == 0f) {
                        val primaryUri = dngUri1 ?: dngUri2 ?: return@withContext
                        repository.readDngBaselineExposure(primaryUri, false)?.let { configFromExif ->
                            val normExif = configFromExif.normalizedForRender()
                            withContext(Dispatchers.Main) {
                                if (currentEditConfig?.exposure == 0f) {
                                    currentEditConfig = currentEditConfig?.copy(
                                        digitalGain = 1.0f,
                                        exposure = normExif.exposure
                                    )
                                    updateEditUi()
                                    if (isEditingAdjustments) updateSlidersInPanel()
                                }
                            }
                        }
                    } else if (currentConfig.adjustments != null) {
                        val adjs = currentConfig.adjustments.toMutableList()
                        var updated = false
                        if (dngUri1 != null && adjs.getOrNull(0)?.digitalGain == 1.0f && adjs.getOrNull(0)?.exposure == 0f) {
                            repository.readDngBaselineExposure(dngUri1, true, 0)?.adjustments?.get(0)?.let { adj ->
                                adjs[0] = adj.normalizedForRender()
                                updated = true
                            }
                        }
                        if (dngUri2 != null && adjs.getOrNull(1)?.digitalGain == 1.0f && adjs.getOrNull(1)?.exposure == 0f) {
                            repository.readDngBaselineExposure(dngUri2, true, 1)?.adjustments?.get(1)?.let { adj ->
                                adjs[1] = adj.normalizedForRender()
                                updated = true
                            }
                        }
                        if (updated) {
                            withContext(Dispatchers.Main) {
                                currentEditConfig = currentEditConfig?.copy(adjustments = adjs)
                                updateEditUi()
                                if (isEditingAdjustments) updateSlidersInPanel()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("ImageViewerFragment", "Failed to load source DNG bytes", e)
            }
        }
    }

    private fun updateSplitButtons() {
        if (isEditingAdjustments || isAdjusted) {
            binding.btnActionMain?.setIconResource(R.drawable.ic_save)
            binding.btnActionMain?.text = getString(R.string.save).uppercase()
            binding.btnActionMain?.contentDescription = getString(R.string.save)
        } else {
            if (::adapter.isInitialized && adapter.itemCount > 0) {
                val currentIndex = binding.imagePager.currentItem
                val currentGroup = adapter.getGroup(currentIndex)
                val isUnrenderedRawVideoOrCinemaDng = (currentGroup.isRawVideo || currentGroup.rawVideoUri != null || currentGroup.isCinemaDng || currentGroup.cinemaDngFolderUri != null || currentGroup.cinemaDngFirstFrameUri != null || currentGroup.cinemaDngFrameUris.isNotEmpty()) && currentGroup.mp4VideoUri == null

                if (isUnrenderedRawVideoOrCinemaDng) {
                    binding.btnActionMain?.setIconResource(R.drawable.ic_save)
                    binding.btnActionMain?.text = "EXPORT"
                    binding.btnActionMain?.contentDescription = getString(R.string.action_export_main)
                } else {
                    binding.btnActionMain?.setIconResource(R.drawable.ic_share)
                    binding.btnActionMain?.text = "SHARE"
                    binding.btnActionMain?.contentDescription = getString(R.string.share_button_alt)
                }
            } else {
                binding.btnActionMain?.setIconResource(R.drawable.ic_share)
                binding.btnActionMain?.text = "SHARE"
                binding.btnActionMain?.contentDescription = getString(R.string.share_button_alt)
            }
        }
    }

    private fun updateToolbarIcon() {
        if (isAdjusted || isEditingAdjustments) {
            binding.btnBack.setIconResource(R.drawable.ic_close)
        } else {
            binding.btnBack.setIconResource(R.drawable.ic_back)
        }
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
                    val isUnrenderedRawVideoOrCinemaDng = (currentGroup.isRawVideo || currentGroup.rawVideoUri != null || currentGroup.isCinemaDng || currentGroup.cinemaDngFolderUri != null || currentGroup.cinemaDngFirstFrameUri != null || currentGroup.cinemaDngFrameUris.isNotEmpty()) && currentGroup.mp4VideoUri == null
                    if (isUnrenderedRawVideoOrCinemaDng) {
                        openExportHub()
                    } else {
                        performShare()
                    }
                } else {
                    performShare()
                }
            }
        }

        binding.btnActionMain?.setOnLongClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            if (isEditingAdjustments || isAdjusted) {
                showSaveMenu(it)
            } else {
                showViewMenu(it)
            }
            true
        }

        binding.btnActionMenu?.setOnClickListener {
            binding.btnActionMenu?.isCheckable = true
            binding.btnActionMenu?.isChecked = true
            if (isEditingAdjustments || isAdjusted) {
                showSaveMenu(it)
            } else {
                showViewMenu(it)
            }
        }

        binding.btnLogCurve.setOnClickListener {
            showLutMenu()
        }

        binding.btn3dLut.setOnClickListener {
            showLutMenu()
        }

        binding.btnEditRecipe?.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            showLutMenu()
        }

        binding.btnFormatSwitch?.setOnClickListener {
            if (!::adapter.isInitialized || adapter.itemCount == 0) return@setOnClickListener
            it.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            adapter.cycleOrToggleFormat(binding.imagePager.currentItem)
        }

        binding.btnFormatSwitchCompact?.setOnClickListener {
            if (!::adapter.isInitialized || adapter.itemCount == 0) return@setOnClickListener
            it.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            adapter.cycleOrToggleFormat(binding.imagePager.currentItem)
        }

        binding.btnDerivativeCycle?.setOnClickListener {
            if (!::adapter.isInitialized || adapter.itemCount == 0) return@setOnClickListener
            it.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            val curPos = binding.imagePager.currentItem
            adapter.cycleDerivativeVersion(curPos)
            updateFormatBentoRow()
        }

        binding.btnDerivativeCycleCompact?.setOnClickListener {
            if (!::adapter.isInitialized || adapter.itemCount == 0) return@setOnClickListener
            it.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            val curPos = binding.imagePager.currentItem
            adapter.cycleDerivativeVersion(curPos)
            updateFormatBentoRow()
        }

        binding.btnMulticamCollage?.setOnClickListener {
            if (!::adapter.isInitialized || adapter.itemCount == 0) return@setOnClickListener
            it.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            val currentGroup = adapter.getGroup(binding.imagePager.currentItem)
            if (currentGroup.isMultiCamera) {
                showMultiCamCollageDialog(currentGroup)
            }
        }

        binding.btnMotionLive?.setOnClickListener {
            if (!::adapter.isInitialized || adapter.itemCount == 0) return@setOnClickListener
            handleMotionPhotoIndicatorTapped(binding.imagePager.currentItem)
        }

        binding.btnVideoPlayback?.setOnClickListener {
            if (!::adapter.isInitialized || adapter.itemCount == 0) return@setOnClickListener
            it.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            val currentIndex = binding.imagePager.currentItem
            if (currentIndex !in adapter.getGroups().indices) return@setOnClickListener
            val currentGroup = adapter.getGroup(currentIndex)
            val currentFormat = adapter.getSelectedFormat(currentIndex)
            val isRawSelected = currentFormat == ImageViewerAdapter.FORMAT_DNG
            val derivatives = adapter.getDerivativeUris(currentGroup)
            val activeDerivIdx = adapter.getSelectedDerivativeIndex(currentGroup.baseName).coerceIn(0, (derivatives.size - 1).coerceAtLeast(0))
            val activeDerivativeUri = derivatives.getOrNull(activeDerivIdx) ?: currentGroup.jpgUri ?: currentGroup.mp4VideoUri
            val isMp4Active = !isRawSelected && activeDerivativeUri != null && (activeDerivativeUri.toString().endsWith(".mp4", ignoreCase = true) || currentGroup.isMp4Video)
            val isCinemaDng = currentGroup.isCinemaDng || currentGroup.cinemaDngFolderUri != null || currentGroup.cinemaDngFirstFrameUri != null || currentGroup.cinemaDngFrameUris.isNotEmpty()

            when {
                currentGroup.isRawVideo || currentGroup.rawVideoUri != null -> {
                    adapter.toggleRawVideoForPosition(currentIndex)
                }
                isMp4Active || currentGroup.isMp4Video || currentGroup.mp4VideoUri != null -> {
                    adapter.toggleMp4VideoForPosition(currentIndex)
                }
                isCinemaDng -> {
                    if (currentGroup.mp4VideoUri != null && !isRawSelected) {
                        adapter.toggleMp4VideoForPosition(currentIndex)
                    } else {
                        adapter.toggleCinemaDngForPosition(currentIndex)
                    }
                }
            }
        }

        binding.btnVideoExportHub?.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            openExportHub()
        }

        binding.filmstripBentoSeekbar?.onFrameSelected = { frameIndex, uri ->
            if (::adapter.isInitialized && adapter.itemCount > 0) {
                val currentIndex = binding.imagePager.currentItem
                if (currentIndex in adapter.getGroups().indices) {
                    val group = adapter.getGroup(currentIndex)
                    val frameUris = if (group.cinemaDngFrameUris.isNotEmpty()) {
                        group.cinemaDngFrameUris
                    } else if (group.cinemaDngFirstFrameUri != null) {
                        listOf(group.cinemaDngFirstFrameUri)
                    } else {
                        emptyList()
                    }
                    val targetUri = uri ?: frameUris.getOrNull(frameIndex)
                    currentCinemaDngFrame = Pair(frameIndex, targetUri)
                    adapter.selectCinemaDngFrame(currentIndex, frameIndex, targetUri)
                }
            }
        }

        binding.btnDelete.setOnClickListener {
            if (::adapter.isInitialized && adapter.itemCount > 0) {
                val currentGroup = adapter.getGroup(binding.imagePager.currentItem)
                showDeleteDialog(currentGroup)
            }
        }

        binding.btnSeparateDng.setOnClickListener {
            if (::adapter.isInitialized && adapter.itemCount > 0) {
                val currentGroup = adapter.getGroup(binding.imagePager.currentItem)
                showHalfFrameShareSheet(currentGroup)
            }
        }

        binding.touchOverlay.setOnClickListener {
            binding.lutListContainer?.visibility = View.GONE
            binding.touchOverlay.visibility = View.GONE
            updateBackPressedCallbackState()
        }

        binding.btnTimestamp.setOnClickListener {
            lifecycleScope.launch {
                ensureDngBytesLoaded()
                editorViewModel.processIntent(DarkroomEditorUserIntent.ToggleTimestamp)
                markAdjusted()
            }
        }

        binding.btnFlare.setOnClickListener {
            lifecycleScope.launch {
                ensureDngBytesLoaded()
                val current = currentEditConfig
                val nextFlare = when (current?.flareType) {
                    -1 -> 1
                    1 -> 2
                    2 -> -1
                    else -> 1
                }
                editorViewModel.processIntent(DarkroomEditorUserIntent.SetFlareType(nextFlare))
                markAdjusted()
            }
        }

        binding.btnSwap.setOnClickListener {
            lifecycleScope.launch {
                ensureDngBytesLoaded()
                editorViewModel.processIntent(DarkroomEditorUserIntent.ToggleSwap)
                markAdjusted()
            }
        }

        binding.btnAdjustTofu.setOnClickListener {
            showAdjustmentsBottomSheet()
            lifecycleScope.launch {
                ensureDngBytesLoaded()
            }
        }
    }

    protected open fun showSaveMenu(anchor: View) {
        if (!::adapter.isInitialized || adapter.itemCount == 0) {
            binding.btnActionMenu?.isChecked = false
            return
        }
        val currentGroup = adapter.getGroup(binding.imagePager.currentItem)
        mediaStrategy.showSaveMenu(requireContext(), anchor, currentGroup) { action ->
            when (action) {
                ViewerSaveAction.SAVE_REPLACE -> saveEdit(isReplacement = true)
                ViewerSaveAction.SAVE_AS_NEW -> saveEdit(isReplacement = false)
                ViewerSaveAction.SHARE_TIFF -> performShareAsTiff()
                ViewerSaveAction.EXPORT_JPG_TO_PICTURES -> exportToJpg()
            }
        }
    }

    private fun exportToJpg() {
        val context = context ?: return

        if (isEditingAdjustments) {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
                .setTitle(R.string.export_to_jpg)
                .setMessage(R.string.export_jpg_dialog_message)
                .setPositiveButton(R.string.save_and_export) { _, _ ->
                    saveEdit(isReplacement = true)
                    processAndExportJpg()
                }
                .setNegativeButton(R.string.export_without_saving) { _, _ ->
                    processAndExportJpg()
                }
                .setNeutralButton(R.string.cancel, null)
                .show()
        } else {
            processAndExportJpg()
        }
    }

    private fun processAndExportJpg() {
        val context = context ?: return
        val appContext = context.applicationContext
        val config = currentEditConfig ?: return
        val currentGroup = adapter.getGroup(binding.imagePager.currentItem)
        binding.initialLoadingIndicator.visibility = View.VISIBLE
        binding.interactionBlocker?.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                ensureDngBytesLoaded()
                withContext(Dispatchers.IO) {
                    val mediaStoreSink = top.maary.darkbag.pipeline.darkroom.sink.MediaStoreExportSink(
                        baseName = currentGroup.baseName,
                        isReplacement = false
                    )
                    val spec = top.maary.darkbag.pipeline.darkroom.model.DarkroomRenderSpec.fromImageGroup(
                        context = appContext,
                        group = currentGroup,
                        editConfig = config,
                        sink = mediaStoreSink,
                        outputFormat = top.maary.darkbag.pipeline.darkroom.model.RenderOutputFormat.JPEG,
                        cachedBytes1 = sourceDngBytes,
                        cachedBytes2 = sourceDngBytes2,
                        downsampleHalfFrame = top.maary.darkbag.utils.HalfFrameManager(appContext).downsample,
                        repository = repository
                    )
                    top.maary.darkbag.pipeline.darkroom.DarkroomRenderEngine.render(appContext, spec)
                }
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(appContext, getString(R.string.toast_export_jpg_success), android.widget.Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                android.util.Log.e("ImageViewerFragment", "Failed to export JPG", e)
            } finally {
                binding.initialLoadingIndicator.visibility = View.GONE
                binding.interactionBlocker?.visibility = View.GONE
            }
        }
    }

    protected open fun showViewMenu(anchor: View) {
        if (!::adapter.isInitialized || adapter.itemCount == 0) {
            binding.btnActionMenu?.isChecked = false
            return
        }
        val currentGroup = adapter.getGroup(binding.imagePager.currentItem)
        val popup = PopupMenu(requireContext(), anchor)
        val isUnrenderedRawVideoOrCinemaDng = (currentGroup.isRawVideo || currentGroup.rawVideoUri != null || currentGroup.isCinemaDng || currentGroup.cinemaDngFolderUri != null || currentGroup.cinemaDngFirstFrameUri != null || currentGroup.cinemaDngFrameUris.isNotEmpty()) && currentGroup.mp4VideoUri == null

        if (isUnrenderedRawVideoOrCinemaDng) {
            popup.menu.add(0, MENU_SHARE, 0, getString(R.string.share_button_alt)).apply {
                setIcon(R.drawable.ic_share)
            }
        } else {
            popup.menu.add(0, MENU_EXPORT_HUB, 0, getString(R.string.action_export)).apply {
                setIcon(R.drawable.ic_save)
            }
            if (currentGroup.dngUri != null || currentGroup.dngUri1 != null || currentGroup.dngUri2 != null) {
                popup.menu.add(0, MENU_SHARE_TIFF, 0, getString(R.string.share_as_tiff)).apply {
                    setIcon(R.drawable.ic_photo)
                }
            }
        }
        if (currentGroup.isMultiCamera) {
            popup.menu.add(0, MENU_COLLAGE, 0, getString(R.string.create_multi_cam_collage)).apply {
                setIcon(R.drawable.ic_photo)
            }
        }
        popup.menu.add(0, MENU_DETAILS, 0, "Details").apply {
            setIcon(R.drawable.ic_info)
        }
        popup.menu.add(0, MENU_DELETE, 0, "Delete").apply {
            setIcon(R.drawable.ic_delete)
        }

        forceShowIcons(popup)

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_SHARE -> performShare()
                MENU_EXPORT_HUB -> openExportHub()
                MENU_SHARE_TIFF -> performShareAsTiff()
                MENU_EXPORT_CINEMADNG_FRAME -> openExportHub()
                MENU_EXPORT_CINEMA_DNG -> performExportCinemaDng(adapter.getGroup(binding.imagePager.currentItem))
                MENU_EXPORT_MP4 -> openExportHub()
                MENU_COLLAGE -> showMultiCamCollageDialog(adapter.getGroup(binding.imagePager.currentItem))
                MENU_DETAILS -> showImageDetails()
                MENU_DELETE -> {
                    val groupToDelete = adapter.getGroup(binding.imagePager.currentItem)
                    showDeleteDialog(groupToDelete)
                }
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
        exitEditMode(apply = false)
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

        binding.lutListContainer?.visibility = View.GONE

        val currentIndex = binding.imagePager.currentItem
        adapter.notifyItemChanged(currentIndex)

        val currentGroup = adapter.getGroup(currentIndex)
        prepareEditConfig(currentGroup)
        updateControlsVisibility()
    }

    private fun openExportHub() {
        if (!::adapter.isInitialized || adapter.itemCount == 0) return
        val currentIndex = binding.imagePager.currentItem
        val currentGroup = adapter.getGroup(currentIndex)
        lifecycleScope.launch {
            val fullyLoadedGroup = if (!currentGroup.metadataLoaded) {
                repository.loadMetadata(currentGroup)
            } else {
                currentGroup
            }
            val config = currentEditConfig ?: fullyLoadedGroup.editConfig
            val frameInfo = currentCinemaDngFrame ?: adapter.getActiveCinemaDngFrame(currentIndex)
            val frameIndex = frameInfo?.first ?: 0
            val sheet = RawVideoExportSheet.newInstance(
                group = fullyLoadedGroup,
                currentLog = config?.log,
                currentLut = config?.lut,
                frameIndex = frameIndex
            )
            sheet.show(childFragmentManager, RawVideoExportSheet.TAG)
        }
    }

    private fun performShare() {
        if (!::adapter.isInitialized || adapter.itemCount == 0) return
        val currentIndex = binding.imagePager.currentItem
        val currentGroup = adapter.getGroup(currentIndex)
        val selectedFormat = adapter.getSelectedFormat(currentIndex)

        if (selectedFormat == "DNG" && currentGroup.isHalfFrame()) {
            showHalfFrameShareSheet(currentGroup)
            return
        }

        val currentUri = when (selectedFormat) {
            "JPG" -> currentGroup.jpgUri
            "MP4" -> currentGroup.mp4VideoUri
            "DNG" -> currentGroup.dngUri ?: currentGroup.dngUri1 ?: currentGroup.dngUri2 ?: currentGroup.cinemaDngFirstFrameUri
            else -> currentGroup.jpgUri ?: currentGroup.mp4VideoUri ?: currentGroup.dngUri ?: currentGroup.dngUri1 ?: currentGroup.dngUri2 ?: currentGroup.rawVideoUri ?: currentGroup.cinemaDngFirstFrameUri ?: currentGroup.cinemaDngAudioUri
        }
        currentUri?.let {
            shareImages(listOf(it))
        } ?: run {
            val fallbackUri = currentGroup.firstAvailableUri
            if (fallbackUri != null) {
                shareImages(listOf(fallbackUri))
            } else {
                Toast.makeText(requireContext(), R.string.error_no_external_viewer, Toast.LENGTH_SHORT).show()
            }
        }
    }


    private var preloadJob: kotlinx.coroutines.Job? = null

    private fun preloadAdjacentMetadata(position: Int) {
        if (!::adapter.isInitialized) return
        val currentGroups = adapter.getGroups()
        if (currentGroups.isEmpty()) return

        val indicesToPreload = listOf(
            position,
            position + 1,
            position - 1,
            position + 2,
            position - 2
        ).filter { it in currentGroups.indices }

        preloadJob?.cancel()
        preloadJob = lifecycleScope.launch(Dispatchers.IO) {
            for (index in indicesToPreload) {
                if (!currentCoroutineContext().isActive) break
                val groups = withContext(Dispatchers.Main) {
                    if (::adapter.isInitialized) adapter.getGroups() else emptyList()
                }
                if (index !in groups.indices) continue
                val g = groups[index]
                if (!g.metadataLoaded) {
                    val updated = repository.loadMetadata(g)
                    if (!currentCoroutineContext().isActive) break
                    safeUpdateSingleGroup(updated)
                }
            }
        }
    }

    private fun updateEditUi() {
        if (!::adapter.isInitialized || adapter.itemCount == 0) return
        val currentIndex = binding.imagePager.currentItem
        if (currentIndex !in 0 until adapter.itemCount) return
        val currentGroup = adapter.getGroup(currentIndex)

        if (!currentGroup.metadataLoaded) return

        val ctx = context ?: return
        val config = currentEditConfig ?: currentGroup.editConfig
        val logName = if (config != null && config.log != null && config.log != "None") config.log else "None"
        val lutName = if (config != null && config.lut != null && config.lut != "None") config.lut.substringBeforeLast(".") else "None"

        binding.btn3dLut.text = top.maary.darkbag.ui.CameraBentoDeckView.formatProTelemetry(
            label = "LUT",
            value = lutName,
            isTabActive = false,
            isValueManual = lutName != "None",
            context = ctx
        )
        binding.btnAdjustTofu.text = top.maary.darkbag.ui.CameraBentoDeckView.formatProTelemetry(
            label = "ADJUST",
            value = if (isAdjusted) "ACTIVE" else "LIGHT & COLOR",
            isTabActive = false,
            isValueManual = isAdjusted,
            context = ctx
        )
        binding.btnEditRecipe?.text = top.maary.darkbag.ui.CameraBentoDeckView.formatProTelemetry(
            label = "RECIPE",
            value = if (lutName != "None") lutName else logName,
            isTabActive = false,
            isValueManual = (logName != "None" || lutName != "None"),
            context = ctx
        )
        binding.btnSeparateDng.text = top.maary.darkbag.ui.CameraBentoDeckView.formatProTelemetry(
            label = "SPLIT",
            value = "HALF FRAME",
            isTabActive = false,
            isValueManual = false,
            context = ctx
        )
    }

    private fun updateEffectsButtons() {
        val config = currentEditConfig ?: return
        val ctx = context ?: return
        val iconRes = when (config.flareType) {
            1 -> R.drawable.ic_flare_side
            2 -> R.drawable.ic_flare_corner
            else -> R.drawable.ic_flare_none
        }
        val currentGroup = adapter.getGroup(binding.imagePager.currentItem)
        val activeLayout = config.hfLayout ?: currentGroup.hfLayout
        val isTB = activeLayout == "TB" || activeLayout?.contains("top", ignoreCase = true) == true

        binding.btnTimestamp.text = top.maary.darkbag.ui.CameraBentoDeckView.formatProTelemetry(
            label = "DATE",
            value = if (config.showTimestamp) "ON" else "OFF",
            isTabActive = false,
            isValueManual = config.showTimestamp,
            context = ctx
        )
        binding.btnTimestamp.setIconTintResource(if (config.showTimestamp) R.color.vibrant_orange else android.R.color.white)
        binding.btnTimestamp.alpha = if (config.showTimestamp) 1.0f else 0.6f

        val flareSub = when (config.flareType) {
            1 -> "LEAK #1"
            2 -> "LEAK #2"
            else -> "OFF"
        }
        binding.btnFlare.text = top.maary.darkbag.ui.CameraBentoDeckView.formatProTelemetry(
            label = "FLARE",
            value = flareSub,
            isTabActive = false,
            isValueManual = config.flareType != -1,
            context = ctx
        )
        binding.btnFlare.setIconTintResource(if (config.flareType != -1) R.color.vibrant_pink else android.R.color.white)
        binding.btnFlare.setIconResource(iconRes)
        binding.btnFlare.alpha = if (config.flareType != -1) 1.0f else 0.6f

        binding.btnSwap.text = top.maary.darkbag.ui.CameraBentoDeckView.formatProTelemetry(
            label = "⇄ SWAP",
            value = if (isTB) "T / B" else "F1 / F2",
            isTabActive = false,
            isValueManual = config.isSwapped,
            context = ctx
        )
        binding.btnSwap.setIconResource(if (isTB) R.drawable.ic_swap_vert else R.drawable.ic_swap_horiz)
        binding.btnSwap.setIconTintResource(if (config.isSwapped) R.color.vibrant_cyan else android.R.color.white)
        binding.btnSwap.alpha = if (config.isSwapped) 1.0f else 0.6f
    }

    private fun selectAdjustmentTab(index: Int) {
        binding.editTabs.getTabAt(index)?.select()
        updateAdjustmentTabButtons(index)
        updateSlidersInPanel()
    }

    private fun updateAdjustmentTabButtons(selectedTab: Int) {
        val ctx = context ?: return
        val density = ctx.resources.displayMetrics.density
        val colorPrimaryContainer = com.google.android.material.color.MaterialColors.getColor(
            ctx, com.google.android.material.R.attr.colorPrimaryContainer, 0xFF334433.toInt()
        )
        val colorOnPrimaryContainer = com.google.android.material.color.MaterialColors.getColor(
            ctx, com.google.android.material.R.attr.colorOnPrimaryContainer, 0xFFFFFFFF.toInt()
        )
        val colorPrimary = com.google.android.material.color.MaterialColors.getColor(
            ctx, androidx.appcompat.R.attr.colorPrimary, 0xFF88CC88.toInt()
        )
        val colorSurfaceContainerHigh = com.google.android.material.color.MaterialColors.getColor(
            ctx, com.google.android.material.R.attr.colorSurfaceContainerHigh, 0xFF2B2B2B.toInt()
        )
        val colorOnSurfaceVariant = com.google.android.material.color.MaterialColors.getColor(
            ctx, com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFFCCCCCC.toInt()
        )
        val colorOutlineVariant = com.google.android.material.color.MaterialColors.getColor(
            ctx, com.google.android.material.R.attr.colorOutlineVariant, 0xFF444444.toInt()
        )

        val tabs = listOf(
            binding.btnTabLight,
            binding.btnTabRange,
            binding.btnTabTone,
            binding.btnTabColor
        )
        tabs.forEachIndexed { idx, btn ->
            btn ?: return@forEachIndexed
            val isSelected = idx == selectedTab
            if (isSelected) {
                btn.backgroundTintList = android.content.res.ColorStateList.valueOf(colorPrimaryContainer)
                btn.setTextColor(colorOnPrimaryContainer)
                btn.strokeColor = android.content.res.ColorStateList.valueOf(colorPrimary)
                btn.strokeWidth = (1.5f * density).toInt()
                btn.cornerRadius = (10f * density).toInt()
            } else {
                btn.backgroundTintList = android.content.res.ColorStateList.valueOf(colorSurfaceContainerHigh)
                btn.setTextColor(colorOnSurfaceVariant)
                btn.strokeColor = android.content.res.ColorStateList.valueOf(colorOutlineVariant)
                btn.strokeWidth = (1f * density).toInt()
                btn.cornerRadius = (14f * density).toInt()
            }
        }
    }

    private fun updateSelectionFeedback() {
        val currentGroup = adapter.getGroup(binding.imagePager.currentItem)
        val activeLayout = currentEditConfig?.hfLayout ?: currentGroup.hfLayout
        val isTB = activeLayout == "TB" || activeLayout?.contains("top", ignoreCase = true) == true

        if (isEditingAdjustments && currentGroup.isHalfFrame()) {
            binding.hfSelection1.visibility = if (selectedDngIndex == 1) View.VISIBLE else View.GONE
            binding.hfSelection2.visibility = if (selectedDngIndex == 0) View.VISIBLE else View.GONE

            val lp1 = binding.hfSelection1.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
            val lp2 = binding.hfSelection2.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams

            if (isTB) {
                lp1.bottomToBottom = -1
                lp1.bottomToTop = R.id.hf_selection_divider
                lp1.endToEnd = R.id.image_pager
                lp1.endToStart = -1

                lp2.topToTop = -1
                lp2.topToBottom = R.id.hf_selection_divider
                lp2.startToStart = R.id.image_pager
                lp2.startToEnd = -1
            } else {
                lp1.bottomToBottom = R.id.image_pager
                lp1.bottomToTop = -1
                lp1.endToEnd = -1
                lp1.endToStart = R.id.hf_selection_divider

                lp2.topToTop = R.id.image_pager
                lp2.topToBottom = -1
                lp2.startToStart = -1
                lp2.startToEnd = R.id.hf_selection_divider
            }
            binding.hfSelection1.layoutParams = lp1
            binding.hfSelection2.layoutParams = lp2
        } else {
            binding.hfSelection1.visibility = View.GONE
            binding.hfSelection2.visibility = View.GONE
        }
    }

    private fun showLutMenu() {
        val lutManager = top.maary.darkbag.utils.LutManager(requireContext())
        val initialGroup = adapter.getGroup(binding.imagePager.currentItem)
        val activeConfig = currentEditConfig ?: initialGroup.editConfig
        val initialLog = activeConfig?.log ?: "None"
        val initialLut = activeConfig?.lut
        val baselineLog = initialGroup.editConfig?.log ?: initialLog

        top.maary.darkbag.ui.ColorRecipeBottomSheet.show(
            context = requireContext(),
            lutManager = lutManager,
            initialLog = initialLog,
            initialLut = initialLut,
            baselineLog = baselineLog,
            userExplicitlySelectedLog = userExplicitlyChangedLog,
            persistToPrefs = false,
            onLogExplicitlyChanged = { explicitlyChanged ->
                userExplicitlyChangedLog = explicitlyChanged
            }
        ) { selectedLog, selectedLut ->
            lifecycleScope.launch {
                ensureDngBytesLoaded()
                editorViewModel.processIntent(DarkroomEditorUserIntent.SetLog(selectedLog))
                editorViewModel.processIntent(DarkroomEditorUserIntent.SetLut(selectedLut))
                markAdjusted()
            }
        }
    }

    private fun showLogSelectionMenu() {
        showLutMenu()
    }

    private fun showLutSelectionMenu() {
        showLutMenu()
    }

    private fun showPillPopup(items: List<Pair<String, Boolean>>, autoDismiss: Boolean = true, onSelected: (String, Int) -> Unit) {
        val container = binding.lutListContainer
        val rv = binding.lutList

        binding.touchOverlay.visibility = View.VISIBLE
        container.visibility = View.VISIBLE
        rv.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(requireContext())
        rv.adapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>() {
            inner class PillViewHolder(val btn: com.google.android.material.button.MaterialButton) :
                androidx.recyclerview.widget.RecyclerView.ViewHolder(btn)

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): androidx.recyclerview.widget.RecyclerView.ViewHolder {
                val btn = LayoutInflater.from(parent.context).inflate(R.layout.item_popup_pill, parent, false) as com.google.android.material.button.MaterialButton
                return PillViewHolder(btn)
            }

            override fun onBindViewHolder(holder: androidx.recyclerview.widget.RecyclerView.ViewHolder, position: Int) {
                val pillHolder = holder as PillViewHolder
                val (title, isEnabled) = items[position]
                pillHolder.btn.text = title
                pillHolder.btn.isEnabled = isEnabled
                pillHolder.btn.alpha = if (isEnabled) 1.0f else 0.5f
                pillHolder.btn.setOnClickListener {
                    onSelected(title, position)
                    if (autoDismiss) {
                        container.visibility = View.GONE
                        binding.touchOverlay.visibility = View.GONE
                        updateBackPressedCallbackState()
                    }
                }
            }
            override fun getItemCount() = items.size
        }
    }

    private fun showAdjustmentsBottomSheet() {
        enterEditMode()
    }

    protected fun enterEditMode() {
        if (!isUiVisible) showUi()
        if (isEditingAdjustments) return
        val currentGroup = adapter.getGroup(binding.imagePager.currentItem)
        lifecycleScope.launch {
            ensureDngBytesLoaded()
            binding.viewerBentoDeck.visibility = View.GONE
            editorViewModel.processIntent(DarkroomEditorUserIntent.EnterEditMode(currentGroup, sourceDngBytes, sourceDngBytes2))
        }
    }

    private fun exitEditMode(apply: Boolean) {
        editorViewModel.processIntent(DarkroomEditorUserIntent.ExitEditMode(apply))
    }

    private fun updateSlidersInPanel() {
        editorController?.render(editorViewModel.uiState.value)
    }

    private fun applyEditPreview() {
        val config = currentEditConfig ?: return
        lifecycleScope.launch {
            ensureDngBytesLoaded()
            applyEditPreviewInternal(config)
        }
    }

    protected fun handleLongPressStarted(imageView: top.maary.darkbag.ui.ZoomableImageView) {
        if (isAdjusted) {
            view?.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            val currentIndex = binding.imagePager.currentItem
            val currentGroup = adapter.getGroup(currentIndex)
            val jpgUri = currentGroup.jpgUri ?: return

            isLongPressing = true

            imageView.setMaintainZoomOnNextImage(true)
            adapter.cancelLoadJob(currentIndex, clearView = false)
            com.bumptech.glide.Glide.with(imageView)
                .asBitmap()
                .load(jpgUri)
                .placeholder(imageView.drawable)
                .dontAnimate()
                .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.NONE)
                .skipMemoryCache(true)
                .into(imageView)
            return
        }

        // Browse mode: Temporary motion photo video playback (only in JPG mode)
        val currentIndex = binding.imagePager.currentItem
        if (::adapter.isInitialized && currentIndex in adapter.getGroups().indices) {
            val selectedFormat = adapter.getSelectedFormat(currentIndex)
            if (selectedFormat != ImageViewerAdapter.FORMAT_DNG) {
                val currentGroup = adapter.getGroup(currentIndex)
                if (currentGroup.isMotionPhoto && currentGroup.jpgUri != null) {
                    view?.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                    adapter.playMotionVideoForPosition(currentIndex)
                }
            }
        }
    }

    protected fun handleLongPressEnded(imageView: top.maary.darkbag.ui.ZoomableImageView) {
        if (isAdjusted) {
            isLongPressing = false
            val currentIndex = binding.imagePager.currentItem

            imageView.setMaintainZoomOnNextImage(true)
            com.bumptech.glide.Glide.with(imageView).clear(imageView)
            if (lastCompositeBitmap != null) {
                imageView.setImageBitmap(lastCompositeBitmap)
            } else {
                val format = adapter.getSelectedFormat(currentIndex)
                adapter.setFormat(currentIndex, format)
            }
            return
        }

        // Browse mode: Stop temporary motion photo playback
        val currentIndex = binding.imagePager.currentItem
        if (::adapter.isInitialized) {
            adapter.stopMotionVideoForPosition(currentIndex)
        }
    }

    protected fun handleMotionPhotoIndicatorTapped(position: Int) {
        isMotionPhotoAutoPlay = !isMotionPhotoAutoPlay
        val prefs = requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_VIEWER_MOTION_PHOTO_AUTO_PLAY, isMotionPhotoAutoPlay).apply()
        updateFormatBentoRow()
        view?.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
        adapter.setMotionPhotoAutoPlayEnabled(isMotionPhotoAutoPlay)

        if (isMotionPhotoAutoPlay) {
            val currentPos = binding.imagePager.currentItem
            hasAutoPlayedPosition = currentPos
            adapter.playMotionVideoForPosition(currentPos)
        } else {
            adapter.stopAllMotionVideos()
        }
    }

    private fun applyEditPreviewInternal(config: top.maary.darkbag.models.EditConfig) {
        val currentGroup = adapter.getGroup(binding.imagePager.currentItem)
        val dngUri1 = if (currentGroup.isMultiCamera) {
            val lenses = if (currentGroup.multiCameraLenses.isNotEmpty()) currentGroup.multiCameraLenses else adapter.getMultiCameraLenses(currentGroup)
            val idx = adapter.getSelectedLensIndex(binding.imagePager.currentItem).coerceIn(0, (lenses.size - 1).coerceAtLeast(0))
            lenses.getOrNull(idx)?.dngUri ?: currentGroup.multiDngUris.getOrNull(idx) ?: currentGroup.multiDngUris.firstOrNull()
        } else {
            currentGroup.dngUri ?: currentGroup.dngUri1
        }
        val dngUri2 = currentGroup.dngUri2

        val currentIndex = binding.imagePager.currentItem
        val currentHolder = (binding.imagePager.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView)
            ?.findViewHolderForAdapterPosition(currentIndex) as? ImageViewerAdapter.ViewHolder

        if (currentGroup.isRawVideo || currentGroup.rawVideoUri != null) {
            adapter.updateRawVideoAdjustments(currentIndex, config)
            binding.initialLoadingIndicator.visibility = View.GONE
            currentHolder?.binding?.loadingIndicator?.visibility = View.GONE
            return
        }

        if (isEditingAdjustments) {
            binding.initialLoadingIndicator.visibility = View.VISIBLE
        } else {
            currentHolder?.binding?.loadingIndicator?.visibility = View.VISIBLE
        }
        currentHolder?.binding?.imageView?.invalidateOutline()

        previewJob?.cancel()
        previewJob = lifecycleScope.launch {
            delay(150)
            val isIndividual = isEditingAdjustments && currentGroup.isHalfFrame()

            var compositeBitmap: android.graphics.Bitmap? = null
            var selectedFrameBitmap: android.graphics.Bitmap? = null

            withContext(Dispatchers.IO) {
                try {
                    val context = requireContext()
                    val forceUpdate1 = lastPreviewConfig == null ||
                                      lastPreviewConfig?.log != config.log ||
                                      lastPreviewConfig?.lut != config.lut ||
                                      lastPreviewConfig?.adjustments?.getOrNull(0) != config.adjustments?.getOrNull(0)

                    val forceUpdate2 = lastPreviewConfig == null ||
                                      lastPreviewConfig?.log != config.log ||
                                      lastPreviewConfig?.lut != config.lut ||
                                      lastPreviewConfig?.adjustments?.getOrNull(1) != config.adjustments?.getOrNull(1)

                    val previewSink = BitmapPreviewSink()
                    val spec = DarkroomRenderSpec.fromImageGroup(
                        context = context,
                        group = currentGroup,
                        editConfig = config,
                        sink = previewSink,
                        outputFormat = RenderOutputFormat.BITMAP,
                        downsampleFactor = 0,
                        cachedBytes1 = sourceDngBytes,
                        cachedBytes2 = sourceDngBytes2,
                        cachedFrame1Bitmap = if (forceUpdate1) null else cachedBitmap1,
                        cachedFrame2Bitmap = if (forceUpdate2) null else cachedBitmap2,
                        downsampleHalfFrame = false,
                        repository = repository
                    )
                    val result = DarkroomRenderEngine.render(context, spec)
                    if (result.success && result.bitmap != null) {
                        compositeBitmap = result.bitmap
                        if (result.frame1Bitmap != null) cachedBitmap1 = result.frame1Bitmap
                        if (result.frame2Bitmap != null) cachedBitmap2 = result.frame2Bitmap
                        lastPreviewConfig = config.copy()
                    }
                } catch (e: Exception) {
                    android.util.Log.e("ImageViewerFragment", "Failed to generate edit preview", e)
                }
            }

            if (compositeBitmap != null) {
                if (lastCompositeBitmap != compositeBitmap) {
                    val old = lastCompositeBitmap
                    lastCompositeBitmap = compositeBitmap

                    }
                if (isLongPressing) return@launch

                val currentIndex = binding.imagePager.currentItem
                adapter.cancelLoadJob(currentIndex, clearView = true)
                val holder = (binding.imagePager.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView)
                    ?.findViewHolderForAdapterPosition(currentIndex) as? ImageViewerAdapter.ViewHolder
                compositeBitmap?.let { adapter.setManualBitmap(currentIndex, it) }
                holder?.binding?.loadingIndicator?.visibility = View.GONE
            }

            val finalHolder = (binding.imagePager.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView)
                ?.findViewHolderForAdapterPosition(currentIndex) as? ImageViewerAdapter.ViewHolder
            finalHolder?.binding?.loadingIndicator?.visibility = View.GONE
            binding.initialLoadingIndicator.visibility = View.GONE
        }
    }

    private fun top.maary.darkbag.models.EditConfig.toBasic() = top.maary.darkbag.models.BasicAdjustments(
        exposure, contrast, saturation, highlights, shadows, whites, blacks
    )

    protected open fun saveEdit(isReplacement: Boolean) {
        if (isSaving) return
        val config = currentEditConfig ?: return
        val currentGroup = adapter.getGroup(binding.imagePager.currentItem)
        val context = context ?: return
        val appContext = context.applicationContext
        isSaving = true

        binding.initialLoadingIndicator.visibility = View.VISIBLE
        binding.interactionBlocker?.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                ensureDngBytesLoaded()
                val (cached1, cached2) = editorViewModel.getCachedDngBytes()
                val success = mediaStrategy.saveEdit(
                    context = appContext,
                    group = currentGroup,
                    config = config,
                    isReplacement = isReplacement,
                    repository = repository,
                    cachedBytes1 = cached1 ?: sourceDngBytes,
                    cachedBytes2 = cached2 ?: sourceDngBytes2
                )

                if (success) {
                    resetAdjustments()
                    if (mediaStrategy.isSandbox) {
                        val updatedGroups = mediaStrategy.loadInitialGroups(appContext, arguments, repository)
                        if (updatedGroups != null) {
                            adapter.updateGroups(updatedGroups)
                            binding.root.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
                        }
                    } else {
                        val targetUriStr = if (isReplacement) currentGroup.jpgUri?.toString() else null
                        repository.invalidateCache(clearStale = true)
                        loadImages(targetUriStr, forceRefresh = true)
                        binding.root.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
                    }
                }
            } catch (e: Throwable) {
                Log.e("ImageViewerFragment", "Failed to save edit", e)
            } finally {
                binding.initialLoadingIndicator.visibility = View.GONE
                binding.interactionBlocker?.visibility = View.GONE
                isSaving = false
            }
        }
    }

    protected open fun performShareAsTiff() {
        if (isEditingAdjustments) {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.share_as_tiff_dialog_title)
                .setMessage(R.string.share_as_tiff_dialog_message)
                .setPositiveButton(R.string.save_and_share) { _, _ ->
                    saveEdit(isReplacement = true)
                    processAndShareTiff()
                }
                .setNegativeButton(R.string.share_without_saving) { _, _ ->
                    processAndShareTiff()
                }
                .setNeutralButton(R.string.cancel, null)
                .show()
        } else {
            processAndShareTiff()
        }
    }

    protected open fun processAndShareTiff() {
        val config = currentEditConfig ?: return
        val currentGroup = adapter.getGroup(binding.imagePager.currentItem)
        val dngUri1 = currentGroup.dngUri ?: currentGroup.dngUri1
        val dngUri2 = currentGroup.dngUri2

        binding.initialLoadingIndicator.visibility = View.VISIBLE

        val ctx = context ?: return
        val appContext = ctx.applicationContext
        lifecycleScope.launch {
            ensureDngBytesLoaded()
            val tiffUri = withContext(Dispatchers.IO) {
                try {
                    val context = appContext
                    val exportDir = java.io.File(context.filesDir, "shared_exports")
                    if (!exportDir.exists()) exportDir.mkdirs()

                    // Single file policy: reuse same filename to avoid storage bloat
                    val tempTiff = java.io.File(exportDir, "latest_share_export.tif")
                    val shareSink = ShareFileSink(tempTiff, mimeType = "image/tiff")
                    val spec = DarkroomRenderSpec.fromImageGroup(
                        context = context,
                        group = currentGroup,
                        editConfig = config,
                        sink = shareSink,
                        outputFormat = RenderOutputFormat.TIFF,
                        cachedBytes1 = sourceDngBytes,
                        cachedBytes2 = sourceDngBytes2,
                        customTiffPath = tempTiff.absolutePath,
                        repository = repository
                    )
                    val result = DarkroomRenderEngine.render(context, spec)
                    if (result.success && tempTiff.exists()) {
                        android.provider.DocumentsContract.buildDocumentUri(
                            top.maary.darkbag.provider.DarkbagDocumentsProvider.AUTHORITY,
                            "${top.maary.darkbag.provider.DarkbagDocumentsProvider.ROOT_ID_EXPORTS}:${tempTiff.name}"
                        )
                    } else {
                        null
                    }
                } catch (e: Exception) {
                    android.util.Log.e("ImageViewerFragment", "Failed to process TIFF for sharing", e)
                    null
                }
            }

            binding.initialLoadingIndicator.visibility = View.GONE
            tiffUri?.let { uri ->
                // Notify the system that the "Exports" root has changed
                val childrenUri = android.provider.DocumentsContract.buildChildDocumentsUri(
                    top.maary.darkbag.provider.DarkbagDocumentsProvider.AUTHORITY,
                    top.maary.darkbag.provider.DarkbagDocumentsProvider.ROOT_ID_EXPORTS
                )
                appContext.contentResolver.notifyChange(childrenUri, null)

                shareTiff(uri)
            }
        }
    }

    private fun toShareableUri(uri: Uri): Uri {
        if (uri.scheme == "file") {
            val file = java.io.File(uri.path ?: return uri)
            return try {
                androidx.core.content.FileProvider.getUriForFile(
                    requireContext().applicationContext,
                    "${requireContext().packageName}.fileprovider",
                    file
                )
            } catch (e: Exception) {
                android.util.Log.e("ImageViewerFragment", "Failed to convert file URI to content URI: $uri", e)
                uri
            }
        }
        return uri
    }

    private fun shareTiff(uri: Uri) {
        val shareUri = toShareableUri(uri)
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "image/tiff"
            putExtra(android.content.Intent.EXTRA_STREAM, shareUri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(android.content.Intent.createChooser(intent, "Share TIFF"))
        } catch (e: android.content.ActivityNotFoundException) {
            context?.let { android.widget.Toast.makeText(it, "No app found to share TIFF.", android.widget.Toast.LENGTH_SHORT).show() }
        }
    }

    private fun shareImages(uris: List<android.net.Uri>) {
        if (uris.isEmpty()) return
        val shareableUris = uris.map { toShareableUri(it) }
        val mimeType = if (shareableUris.size == 1) {
            val u = shareableUris[0]
            val type = context?.contentResolver?.getType(u)
            type ?: when {
                u.toString().endsWith(".dng", true) -> "image/x-adobe-dng"
                u.toString().endsWith(".mp4", true) -> "video/mp4"
                u.toString().endsWith(".wav", true) -> "audio/wav"
                else -> "image/jpeg"
            }
        } else {
            val types = shareableUris.mapNotNull { context?.contentResolver?.getType(it) ?: (if (it.toString().endsWith(".dng", true)) "image/x-adobe-dng" else "image/jpeg") }
            if (types.all { it.startsWith("image/") }) "image/*" else "*/*"
        }
        val intent = if (shareableUris.size == 1) {
            android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(android.content.Intent.EXTRA_STREAM, shareableUris[0])
            }
        } else {
            android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE).apply {
                type = mimeType
                putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, ArrayList(shareableUris))
            }
        }
        intent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(android.content.Intent.createChooser(intent, "Share"))
        } catch (e: android.content.ActivityNotFoundException) {
            android.widget.Toast.makeText(requireContext(), "No app found to share.", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun showCinemaDngShareExportDialog(group: top.maary.darkbag.models.ImageGroup) {
        openExportHub()
    }

    private fun exportCinemaDngFrame(
        group: top.maary.darkbag.models.ImageGroup,
        exportType: top.maary.darkbag.rawvideo.RawVideoExporter.FrameExportType,
        editConfig: top.maary.darkbag.models.EditConfig? = null,
        targetFrameIndex: Int? = null
    ) {
        val currentIndex = binding.imagePager.currentItem
        val frameInfo = currentCinemaDngFrame ?: adapter.getActiveCinemaDngFrame(currentIndex)
        val frameIndex = targetFrameIndex ?: frameInfo?.first ?: 0
        val frameUri = if (targetFrameIndex != null && group.cinemaDngFrameUris.indices.contains(targetFrameIndex)) {
            group.cinemaDngFrameUris[targetFrameIndex]
        } else {
            frameInfo?.second
                ?: group.cinemaDngFrameUris.getOrNull(frameIndex)
                ?: group.cinemaDngFirstFrameUri
                ?: group.dngUri
        }

        if (frameUri == null) {
            Toast.makeText(requireContext(), R.string.cinemadng_export_failed, Toast.LENGTH_SHORT).show()
            return
        }

        binding.initialLoadingIndicator.visibility = View.VISIBLE
        binding.interactionBlocker?.visibility = View.VISIBLE
        Toast.makeText(requireContext(), R.string.cinemadng_exporting_frame, Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val fullyLoadedGroup = if (!group.metadataLoaded) {
                    repository.loadMetadata(group)
                } else {
                    group
                }
                val config = editConfig ?: currentEditConfig ?: fullyLoadedGroup.editConfig
                top.maary.darkbag.rawvideo.RawVideoExporter.exportSingleFrameFromCinemaDng(
                    context = requireContext().applicationContext,
                    frameDngUri = frameUri,
                    baseName = group.baseName,
                    frameIndex = frameIndex,
                    editConfig = config,
                    exportType = exportType
                )
            }

            binding.initialLoadingIndicator.visibility = View.GONE
            binding.interactionBlocker?.visibility = View.GONE

            val (rawUri, jpgUri) = result
            if (rawUri != null || jpgUri != null) {
                Toast.makeText(requireContext(), R.string.cinemadng_export_success, Toast.LENGTH_SHORT).show()
                refreshCurrentGroupAndAdapter(group.baseName)
            } else {
                Toast.makeText(requireContext(), R.string.cinemadng_export_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun handleCinemaDngFullVideoExport(group: top.maary.darkbag.models.ImageGroup) {
        if (group.isRawVideo || group.rawVideoUri != null) {
            openExportHub()
        } else if (group.mp4VideoUri != null) {
            shareImages(listOf(group.mp4VideoUri))
        } else if (group.cinemaDngAudioUri != null) {
            shareImages(listOf(group.cinemaDngAudioUri))
        } else {
            performExportCinemaDng(group)
        }
    }

    private fun showHalfFrameShareSheet(group: ImageGroup) {
        HalfFrameShareSheet.newInstance(group.dngUri1, group.dngUri2)
            .show(childFragmentManager, HalfFrameShareSheet.TAG)
    }

    private fun showImageDetails() {
        val viewerBinding = _binding ?: return
        val currentIndex = binding.imagePager.currentItem
        val currentGroup = adapter.getGroup(currentIndex)
        val selectedFormat = adapter.getSelectedFormat(currentIndex)

        val uri = adapter.getCurrentUri(currentIndex) ?: return

        val dialog = BottomSheetDialog(requireContext())
        val detailsBinding = BottomSheetImageDetailsBinding.inflate(layoutInflater)
        dialog.setContentView(detailsBinding.root)

        // Ensure edge-to-edge support for the BottomSheet
        dialog.window?.let { window ->
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                window.setDecorFitsSystemWindows(false)
            }
        }

        ViewCompat.setOnApplyWindowInsetsListener(detailsBinding.root) { v, insets ->
            val navigationBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, navigationBars.bottom + resources.getDimensionPixelSize(R.dimen.margin_medium))
            insets
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val context = requireContext()
            val details = mutableListOf<Pair<String, String>>()
            val techDetails = mutableListOf<Pair<String, String>>()
            val configDetails = mutableListOf<Pair<String, String>>()

            try {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    val exif = ExifInterface(pfd.fileDescriptor)

                    // Basic Info
                    val fileName = getFileName(context, uri)
                    details.add("File Name" to fileName)

                    val fileSize = getFileSize(context, uri)
                    details.add("File Size" to fileSize)

                    val width = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
                    val height = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)
                    if (width > 0 && height > 0) {
                        details.add("Resolution" to "${width}x${height} (${String.format("%.1f MP", width * height / 1000000f)})")
                    }

                    val dateStr = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
                    dateStr?.let { details.add("Date & Time" to it) }

                    details.add("Path" to (uri.path ?: "Unknown"))

                    // Technical Info
                    val make = exif.getAttribute(ExifInterface.TAG_MAKE)
                    val model = exif.getAttribute(ExifInterface.TAG_MODEL)
                    if (make != null || model != null) {
                        techDetails.add("Device" to "${make ?: ""} ${model ?: ""}".trim())
                    }

                    val iso = exif.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS)
                    iso?.let { techDetails.add("ISO" to it) }

                    val exposureTime = exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0)
                    if (exposureTime > 0) {
                        val expStr = if (exposureTime < 1.0) {
                            "1/${round(1.0 / exposureTime).toInt()}s"
                        } else {
                            "${String.format("%.1f", exposureTime)}s"
                        }
                        techDetails.add("Shutter Speed" to expStr)
                    }

                    val fNumber = exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0)
                    if (fNumber > 0) techDetails.add("Aperture" to "f/${fNumber}")

                    val focalLength = exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0)
                    if (focalLength > 0) techDetails.add("Focal Length" to "${focalLength}mm")

                    // Config Info (UserComment)
                    val comment = exif.getAttribute(ExifInterface.TAG_USER_COMMENT)
                    if (comment?.startsWith("{") == true) {
                        try {
                            val json = org.json.JSONObject(comment)
                            val keys = json.keys()
                            while (keys.hasNext()) {
                                val key = keys.next()
                                if (key == "adjustments") continue
                                val value = json.get(key)
                                val label = key.replace("_", " ").replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
                                configDetails.add(label to value.toString())
                            }
                        } catch (e: Exception) {
                            configDetails.add("Custom Info" to comment)
                        }
                    } else if (comment != null) {
                        configDetails.add("Custom Info" to comment)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("ImageViewerFragment", "Error reading details", e)
            }

            withContext(Dispatchers.Main) {
                details.forEach { addDetailRow(detailsBinding.layoutBasicInfo, it.first, it.second) }
                techDetails.forEach { addDetailRow(detailsBinding.layoutTechInfo, it.first, it.second) }
                if (configDetails.isEmpty()) {
                    detailsBinding.cardConfigInfo.visibility = android.view.View.GONE
                    detailsBinding.tvConfigHeader.visibility = android.view.View.GONE
                } else {
                    configDetails.forEach { addDetailRow(detailsBinding.layoutConfigInfo, it.first, it.second) }
                }
            }
        }
        dialog.show()
    }

    private fun addDetailRow(container: ViewGroup, label: String, value: String) {
        val rowBinding = ItemDetailRowBinding.inflate(layoutInflater, container, true)
        rowBinding.tvLabel.text = label
        rowBinding.tvValue.text = value
    }

    private fun queryContentResolver(context: Context, uri: Uri, columnName: String): String? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(columnName)
                    if (index != -1) cursor.getString(index) else null
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun getFileName(context: Context, uri: Uri): String {
        return queryContentResolver(context, uri, android.provider.OpenableColumns.DISPLAY_NAME)
            ?: uri.lastPathSegment ?: "Unknown"
    }

    private fun getFileSize(context: Context, uri: Uri): String {
        val sizeStr = queryContentResolver(context, uri, android.provider.OpenableColumns.SIZE)
        return sizeStr?.toLongOrNull()?.let { formatFileSize(it) } ?: "Unknown"
    }

    private fun formatFileSize(size: Long): String {
        if (size <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (log10(size.toDouble()) / log10(1024.0)).toInt()
        return String.format("%.1f %s", size / 1024.0.pow(digitGroups.toDouble()), units[digitGroups])
    }

    private fun showDeleteDialog(group: ImageGroup) {
        val hasCdng = group.isCinemaDng || group.cinemaDngFolderUri != null || group.cinemaDngFirstFrameUri != null || group.cinemaDngFrameUris.isNotEmpty()
        val hasRawVid = group.isRawVideo || group.rawVideoUri != null
        val derivatives = adapter.getDerivativeUris(group)
        val hasDerivatives = derivatives.isNotEmpty()
        val hasPhotoDng = group.dngUri != null || group.dngUri1 != null || group.dngUri2 != null || group.multiDngUris.isNotEmpty()
        val hasRaw = hasPhotoDng || hasRawVid

        val activeDerivIdx = adapter.getSelectedDerivativeIndex(group.baseName).coerceIn(0, (derivatives.size - 1).coerceAtLeast(0))
        val currentVersionName = if (derivatives.size >= 2) "V${activeDerivIdx + 1}" else null
        val derivativeCount = derivatives.size

        val componentCount = (if (hasRaw) 1 else 0) + (if (hasDerivatives) 1 else 0) + (if (hasCdng) 1 else 0)
        val hasMultipleComponents = componentCount > 1 || (derivatives.size >= 2)

        if (hasMultipleComponents) {
            pendingSingleDeleteGroup = group
            DarkbagBatchDeleteSheet.newInstance(
                selectedCount = 1,
                hasRaw = hasRaw,
                hasDerivatives = hasDerivatives,
                hasCinemaDng = hasCdng,
                currentVersionName = currentVersionName,
                derivativeCount = derivativeCount
            ).show(childFragmentManager, DarkbagBatchDeleteSheet.TAG)
        } else {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.delete_image_title)
                .setMessage(R.string.delete_image_message)
                .setPositiveButton(R.string.delete_button_alt) { _, _ ->
                    deleteImage(group, group.allUris, true)
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun executeSingleGroupDelete(group: ImageGroup, deleteMode: Int) {
        val urisToDelete = mutableListOf<Uri>()
        var isEntireGroup = false
        when (deleteMode) {
            DarkbagBatchDeleteSheet.MODE_CURRENT_DERIVATIVE_ONLY -> {
                val derivatives = adapter.getDerivativeUris(group)
                val activeDerivIdx = adapter.getSelectedDerivativeIndex(group.baseName).coerceIn(0, (derivatives.size - 1).coerceAtLeast(0))
                val activeUri = derivatives.getOrNull(activeDerivIdx) ?: group.jpgUri ?: group.mp4VideoUri
                activeUri?.let { urisToDelete.add(it) }
                val remainingUris = group.allUris.filter { !urisToDelete.contains(it) }
                isEntireGroup = remainingUris.isEmpty()
            }
            DarkbagBatchDeleteSheet.MODE_ENTIRE_GROUP -> {
                group.cinemaDngFolderUri?.let { urisToDelete.add(it) }
                urisToDelete.addAll(group.allUris)
                isEntireGroup = true
            }
            DarkbagBatchDeleteSheet.MODE_RAW_ONLY -> {
                if (group.isCinemaDng || group.cinemaDngFolderUri != null) {
                    group.cinemaDngFolderUri?.let { urisToDelete.add(it) }
                }
                urisToDelete.addAll(group.allMasterRawUris)
                val remainingUris = group.allUris.filter { !urisToDelete.contains(it) }
                isEntireGroup = remainingUris.isEmpty()
            }
            DarkbagBatchDeleteSheet.MODE_DERIVATIVES_ONLY -> {
                urisToDelete.addAll(group.allDerivativeUris)
                val remainingUris = group.allUris.filter { !urisToDelete.contains(it) }
                isEntireGroup = remainingUris.isEmpty()
            }
            DarkbagBatchDeleteSheet.MODE_CINEMADNG_ONLY -> {
                group.cinemaDngFolderUri?.let { urisToDelete.add(it) }
                urisToDelete.addAll(group.allCinemaDngUris)
                val remainingUris = group.allUris.filter { !urisToDelete.contains(it) }
                isEntireGroup = remainingUris.isEmpty()
            }
        }
        deleteImage(group, urisToDelete, isEntireGroup)
    }

    protected open fun deleteImage(group: ImageGroup, deleteGroup: Boolean) {
        deleteImage(group, if (deleteGroup) group.allUris else null, deleteGroup)
    }

    protected open fun deleteImage(group: ImageGroup, targetUris: List<Uri>?, deleteGroup: Boolean) {
        val context = context ?: return
        if (mediaStrategy.isSandbox) {
            lifecycleScope.launch {
                val success = mediaStrategy.deleteImage(context, group, targetUris, deleteGroup, repository)
                if (success) {
                    if (deleteGroup) {
                        val currentList = adapter.getGroups().toMutableList()
                        currentList.removeAll { it.baseName == group.baseName }
                        if (currentList.isEmpty()) {
                            findNavController().navigateUp()
                        } else {
                            adapter.updateGroups(currentList.toList())
                            updateControlsVisibility()
                        }
                    } else {
                        val updatedGroups = mediaStrategy.loadInitialGroups(context.applicationContext, arguments, repository)
                        if (updatedGroups != null) {
                            adapter.updateGroups(updatedGroups)
                            updateControlsVisibility()
                        }
                    }
                }
            }
            return
        }
        lifecycleScope.launch {
            var nextTargetUri: String? = null
            val currentIndex = binding.imagePager.currentItem
            val urisToDelete = mutableListOf<Uri>()

            if (targetUris != null) {
                urisToDelete.addAll(targetUris)
            } else if (deleteGroup) {
                urisToDelete.addAll(group.allUris)
            } else {
                val selectedFormat = adapter.getSelectedFormat(currentIndex)
                val isCdng = selectedFormat == "CDNG" || ((selectedFormat == ImageViewerAdapter.FORMAT_DNG || selectedFormat == "RAW") && (group.isCinemaDng || group.cinemaDngFolderUri != null || group.cinemaDngFirstFrameUri != null || group.cinemaDngFrameUris.isNotEmpty()))
                val isRawVid = selectedFormat == "RAWVID" || ((selectedFormat == ImageViewerAdapter.FORMAT_DNG || selectedFormat == "RAW") && (group.isRawVideo || group.rawVideoUri != null))
                val isPhotoDng = selectedFormat == ImageViewerAdapter.FORMAT_DNG || selectedFormat == "DNG" || selectedFormat == "RAW"

                when {
                    isCdng -> {
                        urisToDelete.addAll(group.allCinemaDngUris)
                    }
                    isRawVid -> {
                        urisToDelete.addAll(group.rawVideoMasterUris)
                    }
                    isPhotoDng -> {
                        urisToDelete.addAll(group.photoMasterUris)
                    }
                    else -> {
                        val derivatives = adapter.getDerivativeUris(group)
                        val activeIndex = adapter.getSelectedDerivativeIndex(group.baseName).coerceIn(0, (derivatives.size - 1).coerceAtLeast(0))
                        val activeUri = derivatives.getOrNull(activeIndex) ?: group.jpgUri ?: group.mp4VideoUri
                        activeUri?.let { urisToDelete.add(it) }
                    }
                }
            }

            // Ensure CinemaDNG folder is included if sequence frames are deleted
            if (urisToDelete.any { group.allCinemaDngUris.contains(it) } && group.cinemaDngFolderUri != null) {
                if (!urisToDelete.contains(group.cinemaDngFolderUri)) {
                    group.cinemaDngFolderUri?.let { urisToDelete.add(it) }
                }
            }

            if (deleteGroup) {
                if (adapter.itemCount > 1) {
                    val nextIndex = if (currentIndex < adapter.itemCount - 1) currentIndex + 1 else currentIndex - 1
                    val nextGroup = adapter.getGroup(nextIndex)
                    nextTargetUri = nextGroup.firstAvailableUri?.toString()
                }
            }

            val securityExceptionUris = mutableListOf<Uri>()
            for (uri in urisToDelete.distinct()) {
                try {
                    deleteUriSafe(context, uri)
                } catch (e: SecurityException) {
                    if (uri.scheme == "content" && uri.authority == MediaStore.AUTHORITY) {
                        securityExceptionUris.add(uri)
                    }
                } catch (e: Exception) {
                    Log.e("ImageViewerFragment", "Failed to delete $uri", e)
                }
            }

            // Calculate nextTargetUri for single file deletion after successful deletion attempts
            if (!deleteGroup) {
                repository.invalidateCache(clearStale = true)
                val remainingGroup = repository.getGroupedImages(forceRefresh = true).find { it.baseName == group.baseName }
                nextTargetUri = if (remainingGroup != null && remainingGroup.hasAny()) {
                    remainingGroup.firstAvailableUri?.toString()
                } else {
                    if (adapter.itemCount > 1) {
                        val nextIndex = if (currentIndex < adapter.itemCount - 1) currentIndex + 1 else currentIndex - 1
                        val nextGroup = adapter.getGroup(nextIndex)
                        nextGroup.firstAvailableUri?.toString()
                    } else null
                }
            }

            if (securityExceptionUris.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                pendingDeleteNextTargetUri = nextTargetUri
                try {
                    val deleteRequest = MediaStore.createDeleteRequest(context.contentResolver, securityExceptionUris)
                    deleteLauncher.launch(IntentSenderRequest.Builder(deleteRequest.intentSender).build())
                } catch (e: Exception) {
                    context.let { Toast.makeText(it, R.string.gallery_delete_prompt_failed, Toast.LENGTH_SHORT).show() }
                    repository.invalidateCache(clearStale = true)
                    loadImages(nextTargetUri, forceRefresh = true)
                }
            } else {
                repository.invalidateCache(clearStale = true)
                loadImages(nextTargetUri, forceRefresh = true)
            }
        }
    }



    private fun setupToolbar() {
        updateSplitButtons()
        updateToolbarIcon()
        binding.btnBack.setOnClickListener {
            if (binding.lutListContainer?.visibility == View.VISIBLE) {
                binding.lutListContainer?.visibility = View.GONE
                binding.touchOverlay.visibility = View.GONE
                updateBackPressedCallbackState()
            } else if (isEditingAdjustments) {
                exitEditMode(apply = false)
            } else if (isAdjusted) {
                showDiscardChangesDialog()
            } else {
                findNavController().navigateUp()
            }
        }
    }

    private fun openDarkroomGallery() {
        if (!isGallerySupported) return
        val currentGroup = if (::adapter.isInitialized && adapter.itemCount > 0) {
            val pos = binding.imagePager.currentItem
            if (pos in adapter.getGroups().indices) adapter.getGroup(pos) else null
        } else null
        val uri = currentGroup?.firstAvailableUri ?: currentGroup?.jpgUri ?: currentGroup?.dngUri
        val action = ImageViewerFragmentDirections.actionImageViewerToDarkroomGallery(
            initialUri = uri?.toString()
        )
        findNavController().navigate(action)
    }

    protected fun updateGalleryPill(position: Int, totalCount: Int) {
        if (!isGallerySupported || totalCount <= 0) {
            binding.btnGalleryPill.visibility = View.GONE
        } else {
            val currentPos = (position + 1).coerceIn(1, totalCount)
            val posStr = if (totalCount >= 10) String.format("%02d", currentPos) else "$currentPos"
            val countStr = if (totalCount >= 10) String.format("%02d", totalCount) else "$totalCount"
            binding.btnGalleryPill.text = "▦ $posStr / $countStr"
            binding.btnGalleryPill.visibility = if (isUiVisible && !isEditingAdjustments) View.VISIBLE else View.GONE
        }
    }

    protected fun toggleUi() {
        if (isEditingAdjustments) return
        if (isUiVisible) hideUi() else showUi()
    }

    protected fun showUi() {
        if (isUiVisible) return
        isUiVisible = true

        binding.topBarContainer.visibility = View.VISIBLE
        updateSplitButtons()
        updateControlsVisibility()
        updateGalleryPill(binding.imagePager.currentItem, if (::adapter.isInitialized) adapter.itemCount else 0)

        adapter.setUiVisibility(true)

        binding.topBarContainer.animate().cancel()
        binding.viewerBentoDeck.animate().cancel()

        binding.topBarContainer.animate().translationY(0f).alpha(1f).setDuration(200).setListener(null).start()
        binding.viewerBentoDeck.animate().translationY(0f).alpha(1f).setDuration(200).setListener(null).start()
        updateViewportPadding()
    }

    protected fun hideUi() {
        if (!isUiVisible) return
        isUiVisible = false

        adapter.setUiVisibility(false)

        binding.topBarContainer.animate().cancel()
        binding.viewerBentoDeck.animate().cancel()

        val topShift = -(binding.topBarContainer.height + (binding.topBarContainer.layoutParams as ViewGroup.MarginLayoutParams).topMargin).toFloat()
        val bottomShift = (binding.viewerBentoDeck.height + (binding.viewerBentoDeck.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin).toFloat()

        binding.topBarContainer.animate().translationY(topShift).alpha(0f).setDuration(200)
            .withEndAction { binding.topBarContainer.visibility = View.GONE }.start()
        binding.viewerBentoDeck.animate().translationY(bottomShift).alpha(0f).setDuration(200)
            .withEndAction { binding.viewerBentoDeck.visibility = View.GONE }.start()
        updateViewportPadding()
    }

    private fun showMultiCamCollageDialog(group: ImageGroup) {
        val sheet = MultiCameraCollageSheet.newInstance(group)
        sheet.show(childFragmentManager, MultiCameraCollageSheet.TAG)
    }

    private fun setupEdgeToEdge() {
        val marginMedium = resources.getDimensionPixelSize(R.dimen.margin_medium)

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

        ViewCompat.setOnApplyWindowInsetsListener(binding.viewerRoot) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            systemTopInset = systemBars.top
            systemBottomInset = systemBars.bottom
            updateViewportPadding()

            val density = resources.displayMetrics.density
            val bentoMarginH = (12 * density).toInt()
            val bentoMarginTop = (8 * density).toInt()

            binding.topBarContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = systemBars.top + bentoMarginTop
                leftMargin = systemBars.left + bentoMarginH
                rightMargin = systemBars.right + bentoMarginH
            }
            binding.viewerBentoDeck.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = systemBars.bottom + bentoMarginH
                leftMargin = systemBars.left + bentoMarginH
                rightMargin = systemBars.right + bentoMarginH
            }



            val isLandscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            binding.editAdjustmentPanel.setPadding(
                if (isLandscape) systemBars.left else 0,
                0,
                if (isLandscape) systemBars.right else 0,
                systemBars.bottom
            )

            insets
        }
    }

    private fun updateViewportPadding() {
        val topPadding: Int
        val bottomPadding: Int

        if (isEditingAdjustments) {
            topPadding = systemTopInset + topBarHeight
            bottomPadding = binding.editAdjustmentPanel.height
        } else if (isUiVisible) {
            topPadding = systemTopInset + topBarHeight
            val density = resources.displayMetrics.density
            val bentoMargin = (8 * density).toInt()
            val deckH = if (binding.viewerBentoDeck.visibility == View.VISIBLE) {
                if (bentoDeckHeight > 0) bentoDeckHeight else binding.viewerBentoDeck.height
            } else 0
            bottomPadding = if (deckH > 0) deckH + systemBottomInset + bentoMargin else topPadding
        } else {
            topPadding = 0
            bottomPadding = 0
        }

        val currentGroup = if (::adapter.isInitialized && adapter.itemCount > 0 && binding.imagePager.currentItem in 0 until adapter.itemCount) adapter.getGroup(binding.imagePager.currentItem) else null
        val isVideo = currentGroup?.isRawVideo == true || currentGroup?.mp4VideoUri != null || (binding.imagePager.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView)?.findViewHolderForAdapterPosition(binding.imagePager.currentItem)?.let { (it as? ImageViewerAdapter.ViewHolder)?.isPlayingVideo } == true

        if (!isVideo) {
            android.transition.TransitionManager.beginDelayedTransition(binding.viewerRoot, android.transition.AutoTransition().apply {
                duration = 200
                addTarget(binding.imagePager)
            })
        }
        binding.imagePager.setPadding(0, topPadding, 0, bottomPadding)

        // Ensure half-frame masks match the visible viewport
        val activeLayout = currentEditConfig?.hfLayout ?: currentGroup?.hfLayout
        val isTB = activeLayout == "TB" || activeLayout?.contains("top", ignoreCase = true) == true

        val lp1 = binding.hfSelection1.layoutParams as ViewGroup.MarginLayoutParams
        val lp2 = binding.hfSelection2.layoutParams as ViewGroup.MarginLayoutParams
        val lpDiv = binding.hfSelectionDivider.layoutParams as ViewGroup.MarginLayoutParams
        val lpProgress = binding.initialLoadingIndicator.layoutParams as ViewGroup.MarginLayoutParams

        lp1.topMargin = topPadding
        lp1.bottomMargin = if (isTB) 0 else bottomPadding
        lp1.leftMargin = 0
        lp1.rightMargin = 0

        lp2.topMargin = if (isTB) 0 else topPadding
        lp2.bottomMargin = bottomPadding
        lp2.leftMargin = 0
        lp2.rightMargin = 0

        lpDiv.topMargin = topPadding
        lpDiv.bottomMargin = bottomPadding

        lpProgress.topMargin = topPadding
        lpProgress.bottomMargin = bottomPadding

        binding.hfSelection1.layoutParams = lp1
        binding.hfSelection2.layoutParams = lp2
        binding.hfSelectionDivider.layoutParams = lpDiv
        binding.initialLoadingIndicator.layoutParams = lpProgress
    }

    private fun updateBackPressedCallbackState() {
        backPressedCallback.isEnabled = isAdjusted || isEditingAdjustments || (binding.lutListContainer?.visibility == View.VISIBLE)
    }

    protected open fun showDiscardChangesDialog() {
        if (isEditingAdjustments) {
            exitEditMode(apply = false)
            return
        }
        if (::adapter.isInitialized && adapter.itemCount > 0) {
            val currentGroup = adapter.getGroup(binding.imagePager.currentItem)
            if (mediaStrategy.handleDiscard(currentGroup, adapter.getGroups(), arguments)) {
                findNavController().navigateUp()
                return
            }
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.discard_changes_title)
            .setMessage(R.string.discard_changes_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.discard) { _, _ ->
                resetAdjustments()
            }
            .show()
    }

    protected fun forceShowIcons(popup: PopupMenu) {
        val colorOnSurface = com.google.android.material.color.MaterialColors.getColor(requireContext(), com.google.android.material.R.attr.colorOnSurface, android.graphics.Color.WHITE)
        for (i in 0 until popup.menu.size()) {
            popup.menu.getItem(i).icon?.setTint(colorOnSurface)
        }
        try {
            val field = popup.javaClass.getDeclaredField("mPopup")
            field.isAccessible = true
            val menuHelper = field.get(popup)
            val setForceShowIcon = menuHelper.javaClass.getDeclaredMethod("setForceShowIcon", Boolean::class.java)
            setForceShowIcon.invoke(menuHelper, true)
        } catch (e: NoSuchFieldException) {
            android.util.Log.e("ImageViewerFragment", "mPopup field not found in PopupMenu", e)
        } catch (e: NoSuchMethodException) {
            android.util.Log.e("ImageViewerFragment", "setForceShowIcon method not found", e)
        } catch (e: Exception) {
            android.util.Log.e("ImageViewerFragment", "Unexpected error forcing icons", e)
        }
    }

    override fun onPause() {
        super.onPause()
        if (::adapter.isInitialized) {
            adapter.stopAllMotionVideos()
        }
    }

    private var activeExportDialog: androidx.appcompat.app.AlertDialog? = null

    override fun onDestroyView() {
        activeExportDialog?.let {
            if (it.isShowing) {
                try { it.dismiss() } catch (_: Exception) {}
            }
        }
        activeExportDialog = null
        editorController = null
        if (::adapter.isInitialized) {
            adapter.stopAllMotionVideos()
        }
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
            if (autoSelectFormat != null) {
                adapter.setFormatForGroup(targetBaseName, autoSelectFormat)
            }
            val currentIndex = binding.imagePager.currentItem
            if (currentIndex in newGroups.indices) {
                val currentGroup = newGroups[currentIndex]
                if (currentGroup.baseName == targetBaseName) {
                    adapter.notifyItemChanged(currentIndex)
                    prepareEditConfig(currentGroup)
                    updateSplitButtons()
                    updateControlsVisibility()
                    updateToolbarIcon()
                }
            }
        }

    }

    private fun showExportProgressDialog(
        title: String,
        onCancel: (() -> Unit)? = null
    ): Pair<androidx.appcompat.app.AlertDialog, (Int, Int) -> Unit> {
        val dialogView = layoutInflater.inflate(R.layout.layout_export_progress_dialog, null)
        val titleView = dialogView.findViewById<TextView>(R.id.export_dialog_title)
        val progressBar = dialogView.findViewById<com.google.android.material.progressindicator.LinearProgressIndicator>(R.id.export_progress_bar)
        val progressText = dialogView.findViewById<TextView>(R.id.export_progress_text)

        titleView.text = title
        progressBar.isIndeterminate = false
        progressBar.progress = 0
        progressText.text = getString(R.string.export_progress_preparing)

        val builder = com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setView(dialogView)
            .setCancelable(false)

        if (onCancel != null) {
            builder.setNegativeButton(R.string.cancel) { _, _ ->
                onCancel()
            }
        }

        val dialog = builder.create()
        dialog.show()
        activeExportDialog = dialog

        val updateProgress: (Int, Int) -> Unit = { current, total ->
            if (total > 0) {
                val percent = (current * 100) / total
                lifecycleScope.launch(Dispatchers.Main) {
                    if (dialog.isShowing) {
                        progressBar.progress = percent
                        progressText.text = getString(R.string.export_progress_format, percent, current, total)
                    }
                }
            }
        }
        return Pair(dialog, updateProgress)
    }

    private fun performExportCinemaDng(group: top.maary.darkbag.models.ImageGroup) {
        val uri = group.rawVideoUri ?: return
        val context = requireContext()
        val prefs = context.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        val rawFolderUriStr = prefs.getString(SettingsFragment.KEY_RAW_STORAGE_URI, null)
        val clipName = "CDNG_${group.baseName}"

        val isUserCancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        var exportJob: kotlinx.coroutines.Job? = null

        val (progressDialog, updateProgress) = showExportProgressDialog(
            title = getString(R.string.export_progress_exporting_cdng),
            onCancel = {
                isUserCancelled.set(true)
                exportJob?.cancel()
            }
        )

        exportJob = lifecycleScope.launch {
            var tempExportDir: File? = null
            var destDir: File? = null
            try {
                if (rawFolderUriStr != null) {
                    // 路径 B（配置了 SAF Tree URI）：向 cacheDir 临时输出并流式复制到 SAF DocumentFile
                    val tempDir = File(context.cacheDir, "cdng_${System.currentTimeMillis()}").apply { mkdirs() }
                    tempExportDir = tempDir

                    val resultDir = top.maary.darkbag.rawvideo.RawVideoExporter.exportToCinemaDng(
                        context = context,
                        rawVideoUri = uri,
                        outputDir = tempDir,
                        clipName = clipName,
                        isCancelled = { isUserCancelled.get() || !isActive },
                        onProgress = updateProgress
                    )

                    if (isUserCancelled.get() || !isActive) {
                        withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                            tempDir.deleteRecursively()
                        }
                        Toast.makeText(context, R.string.toast_export_cancelled, Toast.LENGTH_SHORT).show()
                        return@launch
                    }

                    if (resultDir != null && resultDir.exists()) {
                        val exportedClipName = resultDir.name
                        withContext(Dispatchers.IO) {
                            try {
                                val treeUri = Uri.parse(rawFolderUriStr)
                                val treeDoc = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, treeUri)
                                val clipDirDoc = treeDoc?.createDirectory(exportedClipName)
                                if (clipDirDoc != null) {
                                    resultDir.listFiles()?.forEach { file ->
                                        if (isUserCancelled.get() || !isActive) return@forEach
                                        val mime = if (file.name.endsWith(".wav")) "audio/wav" else "image/x-adobe-dng"
                                        val targetDoc = clipDirDoc.createFile(mime, file.name)
                                        if (targetDoc != null) {
                                            context.contentResolver.openOutputStream(targetDoc.uri)?.use { out ->
                                                file.inputStream().use { it.copyTo(out) }
                                            }
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                android.util.Log.e("ImageViewerFragment", "Failed to copy CinemaDNG to SAF folder", e)
                            } finally {
                                tempDir.deleteRecursively()
                            }
                        }

                        if (isUserCancelled.get() || !isActive) {
                            Toast.makeText(context, R.string.toast_export_cancelled, Toast.LENGTH_SHORT).show()
                            return@launch
                        }

                        refreshCurrentGroupAndAdapter(group.baseName)
                        Toast.makeText(context, "CinemaDNG exported: $exportedClipName", Toast.LENGTH_LONG).show()
                    } else {
                        withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                            tempDir.deleteRecursively()
                        }
                        Toast.makeText(context, "CinemaDNG export failed", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    // 路径 A（未配置 SAF，保存到公共 Pictures 目录）：直接写出，避免双重写入
                    val picturesDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES)
                    val targetDir = File(picturesDir, "Darkbag/CinemaDNG/$clipName").apply { mkdirs() }
                    destDir = targetDir

                    val resultDir = top.maary.darkbag.rawvideo.RawVideoExporter.exportToCinemaDng(
                        context = context,
                        rawVideoUri = uri,
                        outputDir = targetDir,
                        clipName = clipName,
                        isCancelled = { isUserCancelled.get() || !isActive },
                        onProgress = updateProgress
                    )

                    if (isUserCancelled.get() || !isActive) {
                        withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                            targetDir.deleteRecursively()
                        }
                        Toast.makeText(context, R.string.toast_export_cancelled, Toast.LENGTH_SHORT).show()
                        return@launch
                    }

                    if (resultDir != null && resultDir.exists()) {
                        withContext(Dispatchers.IO) {
                            val filePaths = resultDir.listFiles()?.map { it.absolutePath }?.toTypedArray()
                            if (!filePaths.isNullOrEmpty()) {
                                android.media.MediaScannerConnection.scanFile(context, filePaths, null, null)
                            }
                        }
                        refreshCurrentGroupAndAdapter(group.baseName)
                        Toast.makeText(context, "CinemaDNG exported: $clipName", Toast.LENGTH_LONG).show()
                    } else {
                        withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                            targetDir.deleteRecursively()
                        }
                        Toast.makeText(context, "CinemaDNG export failed", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: java.util.concurrent.CancellationException) {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                    tempExportDir?.deleteRecursively()
                    destDir?.deleteRecursively()
                }
                Toast.makeText(context, R.string.toast_export_cancelled, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                    tempExportDir?.deleteRecursively()
                    destDir?.deleteRecursively()
                }
                if (isUserCancelled.get()) {
                    Toast.makeText(context, R.string.toast_export_cancelled, Toast.LENGTH_SHORT).show()
                } else {
                    android.util.Log.e("ImageViewerFragment", "CinemaDNG export error", e)
                    Toast.makeText(context, "CinemaDNG export error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } finally {
                if (progressDialog.isShowing) {
                    try { progressDialog.dismiss() } catch (_: Exception) {}
                }
                if (activeExportDialog === progressDialog) {
                    activeExportDialog = null
                }
            }
        }
    }

    private fun performExportMp4(
        group: top.maary.darkbag.models.ImageGroup,
        config: top.maary.darkbag.models.EditConfig = currentEditConfig ?: group.editConfig ?: top.maary.darkbag.models.EditConfig(),
        targetResolution: Int = 1080
    ) {
        val uri = group.rawVideoUri ?: return
        val context = requireContext()
        val prefs = context.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        val mediaFolderUri = prefs.getString(SettingsFragment.KEY_JPG_STORAGE_URI, null)

        val tempMp4File = File(context.cacheDir, "${group.baseName}_graded.mp4")

        val isUserCancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        var exportJob: kotlinx.coroutines.Job? = null

        val (progressDialog, updateProgress) = showExportProgressDialog(
            title = getString(R.string.export_progress_exporting_mp4),
            onCancel = {
                isUserCancelled.set(true)
                exportJob?.cancel()
            }
        )

        exportJob = lifecycleScope.launch {
            try {
                val success = top.maary.darkbag.rawvideo.RawVideoExporter.exportToMp4(
                    context = context,
                    rawVideoUri = uri,
                    outputFile = tempMp4File,
                    editConfig = config,
                    targetResolution = targetResolution,
                    isCancelled = { isUserCancelled.get() || !isActive },
                    onProgress = updateProgress
                )

                if (isUserCancelled.get() || !isActive) {
                    withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                        tempMp4File.delete()
                    }
                    Toast.makeText(context, R.string.toast_export_cancelled, Toast.LENGTH_SHORT).show()
                    return@launch
                }

                if (success && tempMp4File.exists() && tempMp4File.length() > 0) {
                    val (savedUri, _) = top.maary.darkbag.utils.ImageSaver.saveMp4Video(
                        context = context,
                        mp4File = tempMp4File,
                        baseName = "${group.baseName}_graded",
                        mediaFolderUri = mediaFolderUri
                    )
                    if (savedUri != null) {
                        refreshCurrentGroupAndAdapter(group.baseName, autoSelectFormat = ImageViewerAdapter.FORMAT_JPG)
                        Toast.makeText(context, "Graded MP4 saved: ${group.baseName}_graded.mp4", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(context, "Failed to save MP4 to storage", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                        tempMp4File.delete()
                    }
                    Toast.makeText(context, "MP4 export failed", Toast.LENGTH_SHORT).show()
                }
            } catch (e: java.util.concurrent.CancellationException) {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                    tempMp4File.delete()
                }
                Toast.makeText(context, R.string.toast_export_cancelled, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                    tempMp4File.delete()
                }
                if (isUserCancelled.get()) {
                    Toast.makeText(context, R.string.toast_export_cancelled, Toast.LENGTH_SHORT).show()
                } else {
                    android.util.Log.e("ImageViewerFragment", "MP4 export error", e)
                    Toast.makeText(context, "MP4 export error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } finally {
                if (progressDialog.isShowing) {
                    try { progressDialog.dismiss() } catch (_: Exception) {}
                }
                if (activeExportDialog === progressDialog) {
                    activeExportDialog = null
                }
            }
        }
    }

    companion object {
        const val KEY_VIEWER_MOTION_PHOTO_AUTO_PLAY = "viewer_motion_photo_auto_play"

        val LOOK_DOT_COLORS = intArrayOf(
            Color.parseColor("#FFD0BC"), // Soft Coral / Primary
            Color.parseColor("#80D8FF"), // Soft Cyan
            Color.parseColor("#FFE57F"), // Soft Amber / Classic Chrome
            Color.parseColor("#B9F6CA"), // Soft Mint / Emerald
            Color.parseColor("#EA80FC"), // Soft Lavender
            Color.parseColor("#FF8A80")  // Soft Red
        )

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

        private const val EXPOSURE_MIN = -4f
        private const val EXPOSURE_MAX = 4f
        private const val ADJUSTMENT_MIN = -1f
        private const val ADJUSTMENT_MAX = 1f
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingDeleteNextTargetUri?.let { outState.putString("pendingDeleteNextTargetUri", it) }
        outState.putBoolean("startedInGalleryMode", startedInGalleryMode)
    }

    protected suspend fun generateProcessedBitmap(config: top.maary.darkbag.models.EditConfig, currentGroup: ImageGroup): android.graphics.Bitmap? {
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
