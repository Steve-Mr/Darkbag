package top.maary.darkbag.fragments

import android.util.Log
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
import top.maary.darkbag.processor.ColorProcessor
import top.maary.darkbag.repository.ImageRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.round

open class ImageViewerFragment : Fragment() {


    private suspend fun deleteUriSafe(context: Context, uri: Uri): Boolean {
        return withContext(Dispatchers.IO) {
            if (uri.scheme == "content" && DocumentsContract.isDocumentUri(context, uri)) {
                DocumentsContract.deleteDocument(context.contentResolver, uri)
            } else {
                context.contentResolver.delete(uri, null, null) > 0
            }
        }
    }
    private var pendingDeleteNextTargetUri: String? = null
    private val deleteLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            context?.let { Toast.makeText(it, "Failed to delete image", Toast.LENGTH_SHORT).show() }
        }
        lifecycleScope.launch {
            repository.invalidateCache()
            loadImages(pendingDeleteNextTargetUri, forceRefresh = true)
            pendingDeleteNextTargetUri = null
        }
    }

    protected var _binding: FragmentImageViewerBinding? = null
    private var isUiVisible = true
    protected val binding get() = _binding!!
    protected val args: ImageViewerFragmentArgs by navArgs()
    protected lateinit var repository: ImageRepository
    protected lateinit var adapter: ImageViewerAdapter
    protected lateinit var galleryAdapter: DarkbagGalleryGridAdapter
    protected var isGalleryMode = false

    var isMotionPhotoAutoPlay = true
    protected var hasAutoPlayedPosition = -1

    protected var isAdjusted = false
    protected var isEditingAdjustments = false
    private var systemTopInset = 0
    private var systemBottomInset = 0
    private var topBarHeight = 0
    private var configBeforeEditing: top.maary.darkbag.models.EditConfig? = null
    protected var currentEditConfig: top.maary.darkbag.models.EditConfig? = null
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

    protected lateinit var lutManager: top.maary.darkbag.utils.LutManager
    protected var previewJob: Job? = null
    private val adapterUpdateMutex = kotlinx.coroutines.sync.Mutex()
    protected val pageChangeCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) {
            updateGalleryPill(position, if (::adapter.isInitialized) adapter.itemCount else 0)
            if (::adapter.isInitialized) {
                adapter.stopAllMotionVideos()
            }
            val group = adapter.getGroup(position)
            if (!group.metadataLoaded) {
                lifecycleScope.launch {
                    val updatedGroup = repository.loadMetadata(group)
                    adapterUpdateMutex.withLock {
                        suspendCancellableCoroutine<Unit> { continuation ->
                            adapter.updateSingleGroup(updatedGroup) {
                                if (continuation.isActive) continuation.resume(Unit)
                            }
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
            if (isGalleryMode) {
                if (::galleryAdapter.isInitialized && galleryAdapter.isSelectionMode) {
                    galleryAdapter.setSelectionMode(false)
                    updateGallerySelectionUI(0)
                } else {
                    exitGalleryMode()
                }
            } else if (binding.lutListContainer.visibility == View.VISIBLE) {
                binding.lutListContainer.visibility = View.GONE
                binding.touchOverlay.visibility = View.GONE
                updateBackPressedCallbackState()
            } else if (isEditingAdjustments) {
                exitEditMode(apply = false)
            } else if (isAdjusted) {
                showDiscardChangesDialog()
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
        setupGalleryView()

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backPressedCallback)

        childFragmentManager.setFragmentResultListener(HalfFrameShareSheet.REQUEST_KEY, viewLifecycleOwner) { _, bundle ->
            val uris = bundle.getParcelableArrayList<android.net.Uri>(HalfFrameShareSheet.BUNDLE_KEY_URIS)
            if (uris != null) {
                shareImages(uris)
            }
        }

        binding.imagePager.registerOnPageChangeCallback(pageChangeCallback)
        loadImages(forceRefresh = true)

        viewLifecycleOwner.lifecycleScope.launch {
            ColorProcessor.backgroundSaveFlow.collect { event ->
                if (isAdjusted || !::adapter.isInitialized) return@collect
                handleBackgroundSaveEvent(event)
            }
        }
    }

    protected open fun loadImages(targetUri: String? = args.initialUri, forceRefresh: Boolean = false) {
        binding.initialLoadingIndicator.visibility = View.VISIBLE
        binding.imagePager.visibility = View.INVISIBLE

        lifecycleScope.launch {
            repository.getGroupedImagesFlow(targetUri).collect { groups ->
                if (groups.isEmpty()) {
                    if (isGalleryMode) {
                        exitGalleryMode()
                    }
                    findNavController().navigateUp()
                    return@collect
                }

                if (::galleryAdapter.isInitialized) {
                    galleryAdapter.submitList(groups)
                    if (isGalleryMode && !galleryAdapter.isSelectionMode) {
                        binding.galleryToolbar.title = "${getString(R.string.gallery_title)} (${groups.size})"
                    }
                }

                val isFirstLoad = !::adapter.isInitialized

                if (isFirstLoad) {
                    adapter = ImageViewerAdapter(groups, lifecycleScope, requireContext()).apply {
                        isMotionPhotoAutoPlay = this@ImageViewerFragment.isMotionPhotoAutoPlay
                        onImageTapped = { toggleUi() }
                        onZoomChanged = { isZoomed -> if (isZoomed) hideUi() else showUi() }
                        onLongPressStarted = { handleLongPressStarted(it) }
                        onLongPressEnded = { handleLongPressEnded(it) }
                        onMotionPhotoIndicatorTapped = { pos -> handleMotionPhotoIndicatorTapped(pos) }
                        onPinchToOverview = { enterGalleryMode() }
                        onMultiCameraLensChanged = { _, _ ->
                            updateSplitButtons()
                            updateToolbarIcon()
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

                    val initialPos = groups.indexOfFirst {
                        it.jpgUri?.toString() == targetUri ||
                                it.dngUri?.toString() == targetUri ||
                                it.dngUri1?.toString() == targetUri ||
                                it.dngUri2?.toString() == targetUri
                    }
                    if (initialPos != -1) {
                        binding.imagePager.setCurrentItem(initialPos, false)

                        // Load metadata for the initial image immediately
                        val initialGroup = groups[initialPos]
                        if (!initialGroup.metadataLoaded) {
                            val updatedGroup = repository.loadMetadata(initialGroup)
                            prepareEditConfig(updatedGroup)
                            adapterUpdateMutex.withLock {
                                suspendCancellableCoroutine<Unit> { continuation ->
                                    adapter.updateSingleGroup(updatedGroup) {
                                        if (continuation.isActive) continuation.resume(Unit)
                                    }
                                }
                                if (!isAdjusted && isMotionPhotoAutoPlay && updatedGroup.isMotionPhoto && hasAutoPlayedPosition != initialPos && binding.imagePager.currentItem == initialPos) {
                                    hasAutoPlayedPosition = initialPos
                                    adapter.playMotionVideoForPosition(initialPos)
                                }
                            }
                        } else {
                            prepareEditConfig(initialGroup)
                            if (!isAdjusted && isMotionPhotoAutoPlay && initialGroup.isMotionPhoto && hasAutoPlayedPosition != initialPos) {
                                hasAutoPlayedPosition = initialPos
                                adapter.playMotionVideoForPosition(initialPos)
                            }
                        }
                    } else {
                        val firstGroup = groups.firstOrNull()
                        if (!isAdjusted && isMotionPhotoAutoPlay && firstGroup != null && firstGroup.isMotionPhoto && hasAutoPlayedPosition != 0) {
                            hasAutoPlayedPosition = 0
                            adapter.playMotionVideoForPosition(0)
                        }
                    }
                    binding.imagePager.isUserInputEnabled = !isAdjusted
                    setupActionButtons()
                    updateControlsVisibility()

                    binding.imagePager.visibility = View.VISIBLE
                    binding.initialLoadingIndicator.visibility = View.GONE
                } else {
                    adapter.updateGroups(groups)
                    updateGalleryPill(binding.imagePager.currentItem, groups.size)
                    binding.imagePager.visibility = View.VISIBLE
                    binding.initialLoadingIndicator.visibility = View.GONE

                    val currentIndex = binding.imagePager.currentItem
                    if (currentIndex in groups.indices) {
                        val currentGroup = groups[currentIndex]
                        if (!currentGroup.metadataLoaded) {
                            lifecycleScope.launch {
                                val updatedGroup = repository.loadMetadata(currentGroup)
                                adapterUpdateMutex.withLock {
                                    suspendCancellableCoroutine<Unit> { continuation ->
                                        adapter.updateSingleGroup(updatedGroup) {
                                            if (continuation.isActive) continuation.resume(Unit)
                                        }
                                    }
                                    val index = adapter.getGroups().indexOfFirst { it.baseName == updatedGroup.baseName }
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

        val canEdit = (currentGroup.dngUri != null || currentGroup.dngUri1 != null || currentGroup.dngUri2 != null || currentGroup.multiDngUris.isNotEmpty()) && !currentGroup.isPartial

        val visibility = if (canEdit && !isEditingAdjustments) View.VISIBLE else View.GONE
        binding.bottomLeftControls.visibility = visibility
        binding.bottomRightControls.visibility = visibility
        binding.fabAdjust.visibility = visibility

        if (canEdit && currentEditConfig == null) {
            prepareEditConfig(currentGroup)
        }

        if (currentGroup.isHalfFrame() && !currentGroup.isPartial) {
            binding.hfExtraControls.visibility = View.VISIBLE
            updateEffectsButtons()
        } else {
            binding.hfExtraControls.visibility = View.GONE
        }

        updateSplitButtons()
        updateToolbarIcon()
        updateEditUi()
        updateEffectsButtons()
        updateSelectionFeedback()
        updateBackPressedCallbackState()
    }

    protected fun prepareEditConfig(group: ImageGroup) {
        // Reset cached data to prevent cross-contamination between different image groups
        sourceDngBytes = null
        sourceDngBytes2 = null
        cachedBitmap1 = null
        cachedBitmap2 = null
        lastPreviewConfig = null
        selectedDngIndex = 0
        previewJob?.cancel()

        currentEditConfig = group.editConfig?.let {
            if (it.exposure == 0f && it.adjustments == null) {
                val baseEv = if (it.digitalGain > 0f) kotlin.math.log2(it.digitalGain) else 0f
                it.copy(exposure = baseEv)
            } else if (it.adjustments != null) {
                val newAdjs = it.adjustments.map { adj ->
                    if (adj.exposure == 0f) {
                        val baseEv = if (adj.digitalGain > 0f) kotlin.math.log2(adj.digitalGain) else 0f
                        adj.copy(exposure = baseEv)
                    } else adj
                }
                it.copy(adjustments = newAdjs)
            } else it
        }?.copy() ?: top.maary.darkbag.models.EditConfig(
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

                // Fallback: Load digital gain from DNG EXIF only if missing from JPG
                val currentConfig = currentEditConfig
                if (currentConfig != null) {
                    if (currentConfig.digitalGain == 1.0f && currentConfig.adjustments == null && currentConfig.exposure == 0f) {
                        val primaryUri = dngUri1 ?: dngUri2 ?: return@withContext
                        repository.readDngBaselineExposure(primaryUri, false)?.let { configFromExif ->
                            withContext(Dispatchers.Main) {
                                if (currentEditConfig?.exposure == 0f) {
                                    currentEditConfig = currentEditConfig?.copy(
                                        digitalGain = configFromExif.digitalGain,
                                        exposure = if (configFromExif.digitalGain > 0f) kotlin.math.log2(configFromExif.digitalGain) else 0f
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
                                adjs[0] = adj
                                updated = true
                            }
                        }
                        if (dngUri2 != null && adjs.getOrNull(1)?.digitalGain == 1.0f && adjs.getOrNull(1)?.exposure == 0f) {
                            repository.readDngBaselineExposure(dngUri2, true, 1)?.adjustments?.get(1)?.let { adj ->
                                adjs[1] = adj
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
        if (isAdjusted || isEditingAdjustments) {
            binding.splitShare.visibility = View.GONE
            binding.splitSave.visibility = View.VISIBLE
        } else {
            binding.splitShare.visibility = View.VISIBLE
            binding.splitSave.visibility = View.GONE
        }
    }

    private fun updateToolbarIcon() {
        if (isAdjusted || isEditingAdjustments) {
            binding.btnNavigation.setIconResource(R.drawable.ic_close)
        } else {
            binding.btnNavigation.setIconResource(R.drawable.ic_back)
        }
    }

    protected open fun setupActionButtons() {
        binding.btnShareMain.setOnClickListener {
            performShare()
        }
        binding.btnShareMenu.setOnClickListener {
            binding.btnShareMenu.isCheckable = true
            binding.btnShareMenu.isChecked = true
            val currentGroup = adapter.getGroup(binding.imagePager.currentItem)
            val popup = PopupMenu(requireContext(), it)
            popup.menu.add(0, MENU_SHARE_TIFF, 0, getString(R.string.share_as_tiff)).apply {
                setIcon(R.drawable.ic_photo)
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
                    MENU_SHARE_TIFF -> performShareAsTiff()
                    MENU_COLLAGE -> showMultiCamCollageDialog(adapter.getGroup(binding.imagePager.currentItem))
                    MENU_DETAILS -> showImageDetails()
                    MENU_DELETE -> {
                        val groupToDelete = adapter.getGroup(binding.imagePager.currentItem)
                        showDeleteDialog(groupToDelete)
                    }
                }
                true
            }
            popup.setOnDismissListener { binding.btnShareMenu.isChecked = false }
            popup.show()
        }

        binding.btnSaveMain.setOnClickListener {
            saveEdit(isReplacement = true)
        }
        binding.btnSaveMenu.setOnClickListener {
            binding.btnSaveMenu.isCheckable = true
            binding.btnSaveMenu.isChecked = true
            val popup = PopupMenu(requireContext(), it)
            popup.menu.add(0, MENU_SAVE_AS, 0, "Save as new file").apply {
                setIcon(R.drawable.ic_save_as)
            }
            popup.menu.add(0, MENU_SHARE_TIFF, 0, getString(R.string.share_as_tiff)).apply {
                setIcon(R.drawable.ic_photo)
            }

            forceShowIcons(popup)

            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_SAVE_AS -> saveEdit(isReplacement = false)
                    MENU_SHARE_TIFF -> performShareAsTiff()
                }
                true
            }
            popup.setOnDismissListener { binding.btnSaveMenu.isChecked = false }
            popup.show()
        }

        binding.btnLogLut.setOnClickListener {
            showLutMenu()
        }

        binding.touchOverlay.setOnClickListener {
            binding.lutListContainer.visibility = View.GONE
            binding.touchOverlay.visibility = View.GONE
            updateBackPressedCallbackState()
        }

        binding.btnTimestamp.setOnClickListener {
            lifecycleScope.launch {
                ensureDngBytesLoaded()
                val current = currentEditConfig ?: return@launch
                currentEditConfig = current.copy(showTimestamp = !current.showTimestamp)
                markAdjusted()
                updateEffectsButtons()
                applyEditPreview()
            }
        }

        binding.btnFlare.setOnClickListener {
            lifecycleScope.launch {
                ensureDngBytesLoaded()
                val current = currentEditConfig ?: return@launch
                val nextFlare = when (current.flareType) {
                    -1 -> 1
                    1 -> 2
                    2 -> -1
                    else -> 1
                }
                currentEditConfig = current.copy(flareType = nextFlare)
                markAdjusted()
                updateEffectsButtons()
                applyEditPreview()
            }
        }

        binding.btnSwap.setOnClickListener {
            lifecycleScope.launch {
                ensureDngBytesLoaded()
                val current = currentEditConfig ?: return@launch
                currentEditConfig = current.copy(isSwapped = !current.isSwapped)
                markAdjusted()
                updateEffectsButtons()
                applyEditPreview()
            }
        }

        binding.fabAdjust.setOnClickListener {
            showAdjustmentsBottomSheet()
            lifecycleScope.launch {
                ensureDngBytesLoaded()
            }
        }

        binding.hfSelection1.setOnClickListener {
            if (selectedDngIndex != 0) {
                selectedDngIndex = 0
                updateSelectionFeedback()
                updateSlidersInPanel()
                applyEditPreview()
            }
        }

        binding.hfSelection2.setOnClickListener {
            if (selectedDngIndex != 1) {
                selectedDngIndex = 1
                updateSelectionFeedback()
                updateSlidersInPanel()
                applyEditPreview()
            }
        }

        binding.btnEditDone.setOnClickListener {
            exitEditMode(apply = true)
        }

        binding.btnEditCancel.setOnClickListener {
            exitEditMode(apply = false)
        }

        binding.editTabs.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab?) {
                updateSlidersInPanel()
            }
            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
        })

        val sliderChangeListener = com.google.android.material.slider.Slider.OnChangeListener { slider, value, fromUser ->
            if (fromUser) {
                val config = currentEditConfig ?: return@OnChangeListener
                val currentGroup = adapter.getGroup(binding.imagePager.currentItem)
                val tabPos = binding.editTabs.selectedTabPosition

                markAdjusted()

                val updateConfig: (top.maary.darkbag.models.BasicAdjustments) -> top.maary.darkbag.models.BasicAdjustments = { old ->
                    when (tabPos) {
                        0 -> when (slider.id) { // Light
                            R.id.slider_1 -> old.copy(exposure = value)
                            R.id.slider_2 -> old.copy(contrast = value)
                            else -> old
                        }
                        1 -> when (slider.id) { // Range
                            R.id.slider_1 -> old.copy(highlights = value)
                            R.id.slider_2 -> old.copy(shadows = value)
                            else -> old
                        }
                        2 -> when (slider.id) { // Tone
                            R.id.slider_1 -> old.copy(whites = value)
                            R.id.slider_2 -> old.copy(blacks = value)
                            else -> old
                        }
                        3 -> when (slider.id) { // Color
                            R.id.slider_1 -> old.copy(saturation = value)
                            else -> old
                        }
                        else -> old
                    }
                }

                if (currentGroup.isHalfFrame()) {
                    val adjs = config.adjustments?.toMutableList() ?: mutableListOf(top.maary.darkbag.models.BasicAdjustments(), top.maary.darkbag.models.BasicAdjustments())
                    val adjIndex = if (config.isSwapped) 1 - selectedDngIndex else selectedDngIndex
                    adjs[adjIndex] = updateConfig(adjs[adjIndex])
                    currentEditConfig = config.copy(adjustments = adjs)
                } else {
                    val basic = updateConfig(config.toBasic())
                    currentEditConfig = config.copy(
                        exposure = basic.exposure,
                        contrast = basic.contrast,
                        highlights = basic.highlights,
                        shadows = basic.shadows,
                        whites = basic.whites,
                        blacks = basic.blacks,
                        saturation = basic.saturation
                    )
                }

                if (slider.id == R.id.slider_1) {
                    binding.tvValue1.text = if (tabPos == 0) String.format("%.2f EV", value) else String.format("%.2f", value)
                } else {
                    binding.tvValue2.text = String.format("%.2f", value)
                }
                applyEditPreview()
            }
        }
        binding.slider1.addOnChangeListener(sliderChangeListener)
        binding.slider2.addOnChangeListener(sliderChangeListener)

        binding.groupFrameSelectionIntegrated.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                selectedDngIndex = if (checkedId == R.id.btn_select_frame1_integrated) 0 else 1
                updateSelectionFeedback()
                updateSlidersInPanel()
                applyEditPreview()
            }
        }
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

        binding.lutListContainer.visibility = View.GONE

        val currentIndex = binding.imagePager.currentItem
        adapter.notifyItemChanged(currentIndex)

        val currentGroup = adapter.getGroup(currentIndex)
        prepareEditConfig(currentGroup)
        updateControlsVisibility()
    }

    private fun performShare() {
        val currentIndex = binding.imagePager.currentItem
        val currentGroup = adapter.getGroup(currentIndex)
        val selectedFormat = adapter.getSelectedFormat(currentIndex)

        if (selectedFormat == "DNG" && currentGroup.isHalfFrame()) {
            showHalfFrameShareSheet(currentGroup)
        } else {
            val currentUri = when (selectedFormat) {
                "JPG" -> currentGroup.jpgUri
                "DNG" -> currentGroup.dngUri ?: currentGroup.dngUri1 ?: currentGroup.dngUri2
                else -> currentGroup.jpgUri ?: currentGroup.dngUri ?: currentGroup.dngUri1 ?: currentGroup.dngUri2
            }
            currentUri?.let { shareImages(listOf(it)) }
        }
    }


    private fun updateEditUi() {
        if (!::adapter.isInitialized || adapter.itemCount == 0) return
        val currentIndex = binding.imagePager.currentItem
        if (currentIndex !in 0 until adapter.itemCount) return
        val currentGroup = adapter.getGroup(currentIndex)

        if (!currentGroup.metadataLoaded) return

        val config = currentEditConfig
        if (config != null) {
            val lutName = if (config.lut == "None" || config.lut == null) null else config.lut.substringBeforeLast(".")
            val logName = if (config.log == "None" || config.log == null) null else config.log
            binding.btnLogLut.text = lutName ?: logName ?: "None"
        } else {
            binding.btnLogLut.text = "None"
        }
    }

    private fun updateEffectsButtons() {
        val config = currentEditConfig ?: return
        val iconRes = when (config.flareType) {
            1 -> R.drawable.ic_flare_side
            2 -> R.drawable.ic_flare_corner
            else -> R.drawable.ic_flare_none
        }
        val currentGroup = adapter.getGroup(binding.imagePager.currentItem)
        val activeLayout = config.hfLayout ?: currentGroup.hfLayout
        val isTB = activeLayout == "TB" || activeLayout?.contains("top", ignoreCase = true) == true

        binding.btnTimestamp.setIconTintResource(if (config.showTimestamp) R.color.vibrant_orange else android.R.color.white)
        binding.btnTimestamp.alpha = if (config.showTimestamp) 1.0f else 0.6f

        binding.btnFlare.setIconTintResource(if (config.flareType != -1) R.color.vibrant_pink else android.R.color.white)
        binding.btnFlare.setIconResource( iconRes )
        binding.btnFlare.alpha = if (config.flareType != -1) 1.0f else 0.6f

        binding.btnSwap.setIconResource(if (isTB) R.drawable.ic_swap_vert else R.drawable.ic_swap_horiz)
        binding.btnSwap.setIconTintResource(if (config.isSwapped) R.color.vibrant_cyan else android.R.color.white)
        binding.btnSwap.alpha = if (config.isSwapped) 1.0f else 0.6f
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
        if (isEditingAdjustments) return

        val currentLog = currentEditConfig?.log ?: "None"
        val currentLut = currentEditConfig?.lut?.substringBeforeLast(".") ?: "None"

        val items = mutableListOf<Pair<String, Boolean>>()
        items.add("Log: $currentLog" to true)
        if (currentLog == "None") {
            items.add("Select Log First" to false)
        } else {
            items.add("LUT: $currentLut" to true)
        }

        showPillPopup(items, autoDismiss = false) { item, _ ->
            if (item.startsWith("Log:")) {
                showLogSelectionMenu()
            } else {
                showLutSelectionMenu()
            }
        }
        updateBackPressedCallbackState()
    }

    private fun showLogSelectionMenu() {
        val items = mutableListOf("← Back" to true)
        SettingsFragment.LOG_CURVES.forEach { log ->
            items.add(log to true)
        }

        showPillPopup(items, autoDismiss = false) { selectedLog, position ->
            if (position == 0) {
                showLutMenu()
            } else {
                lifecycleScope.launch {
                    ensureDngBytesLoaded()
                    currentEditConfig = currentEditConfig?.copy(log = selectedLog)
                    if (selectedLog == "None") {
                        currentEditConfig = currentEditConfig?.copy(lut = "None")
                    }
                    markAdjusted()
                    updateEditUi()
                    showLutMenu()
                    applyEditPreview()
                }
            }
        }
    }

    private fun showLutSelectionMenu() {
        val luts = lutManager.getLuts()
        val items = mutableListOf("← Back" to true, "None" to true)
        luts.forEach { file ->
            items.add(file.nameWithoutExtension to true)
        }

        showPillPopup(items, autoDismiss = false) { selectedName, position ->
            if (position == 0) {
                showLutMenu()
            } else {
                lifecycleScope.launch {
                    ensureDngBytesLoaded()
                    if (position == 1) {
                        currentEditConfig = currentEditConfig?.copy(lut = "None")
                    } else {
                        val filename = luts[position - 2].name
                        currentEditConfig = currentEditConfig?.copy(lut = filename)
                    }
                    markAdjusted()
                    updateEditUi()
                    showLutMenu()
                    applyEditPreview()
                }
            }
        }
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
        isEditingAdjustments = true
        configBeforeEditing = currentEditConfig?.copy()

        binding.imagePager.isUserInputEnabled = false
        adapter.setFormatSwitcherPersistentHidden(true)

        updateSplitButtons()
        updateToolbarIcon()
        updateViewportPadding()

        // Hide standard controls
        binding.bottomLeftControls.visibility = View.GONE
        binding.bottomRightControls.visibility = View.GONE

        // Show edit panel with animation
        binding.editAdjustmentPanel.visibility = View.VISIBLE
        binding.editAdjustmentPanel.alpha = 0f

        val isLandscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        if (isLandscape) {
            binding.editAdjustmentPanel.translationX = 100f
            binding.editAdjustmentPanel.translationY = 0f
        } else {
            binding.editAdjustmentPanel.translationX = 0f
            binding.editAdjustmentPanel.translationY = 100f
        }

        binding.editAdjustmentPanel.animate()
            .alpha(1f)
            .translationX(0f)
            .translationY(0f)
            .setDuration(300)
            .start()

        // Wait for layout to ensure height is calculated for viewport padding
        binding.editAdjustmentPanel.post {
            updateViewportPadding()
        }

        val currentGroup = adapter.getGroup(binding.imagePager.currentItem)
        if (currentGroup.isHalfFrame()) {
            binding.groupFrameSelectionIntegrated.visibility = View.VISIBLE
            binding.groupFrameSelectionIntegrated.check(if (selectedDngIndex == 0) R.id.btn_select_frame1_integrated else R.id.btn_select_frame2_integrated)
        } else {
            binding.groupFrameSelectionIntegrated.visibility = View.GONE
        }

        updateSlidersInPanel()
        updateSelectionFeedback()
        updateBackPressedCallbackState()
    }

    private fun exitEditMode(apply: Boolean) {
        if (!isEditingAdjustments) return
        isEditingAdjustments = false

        if (!apply) {
            currentEditConfig = configBeforeEditing?.copy()
            applyEditPreview()
        }

        binding.imagePager.isUserInputEnabled = !isAdjusted

        updateViewportPadding()

        val isLandscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val anim = binding.editAdjustmentPanel.animate()
            .alpha(0f)
            .setDuration(200)
            .withEndAction {
                binding.editAdjustmentPanel.visibility = View.GONE
                updateControlsVisibility()
            }

        if (isLandscape) {
            anim.translationX(100f)
        } else {
            anim.translationY(100f)
        }
        anim.start()

        updateSelectionFeedback()
        updateBackPressedCallbackState()
    }

    private fun updateSlidersInPanel() {
        val config = currentEditConfig ?: return
        val currentGroup = adapter.getGroup(binding.imagePager.currentItem)
        val tabPos = binding.editTabs.selectedTabPosition

        val target = if (currentGroup.isHalfFrame()) {
            val adjIndex = if (config.isSwapped) 1 - selectedDngIndex else selectedDngIndex
            config.adjustments?.getOrNull(adjIndex) ?: top.maary.darkbag.models.BasicAdjustments()
        } else {
            config.toBasic()
        }

        when (tabPos) {
            0 -> { // Light
                binding.tvLabel1.text = "Exposure"
                binding.slider1.valueFrom = EXPOSURE_MIN
                binding.slider1.valueTo = EXPOSURE_MAX
                binding.slider1.value = target.exposure.coerceIn(EXPOSURE_MIN, EXPOSURE_MAX)
                binding.tvValue1.text = String.format("%.2f EV", target.exposure)

                binding.layoutSlider2.visibility = View.VISIBLE
                binding.tvLabel2.text = "Contrast"
                binding.slider2.valueFrom = ADJUSTMENT_MIN
                binding.slider2.valueTo = ADJUSTMENT_MAX
                binding.slider2.value = target.contrast.coerceIn(ADJUSTMENT_MIN, ADJUSTMENT_MAX)
                binding.tvValue2.text = String.format("%.2f", target.contrast)
            }
            1 -> { // Range
                binding.tvLabel1.text = "Highlights"
                binding.slider1.valueFrom = ADJUSTMENT_MIN
                binding.slider1.valueTo = ADJUSTMENT_MAX
                binding.slider1.value = target.highlights.coerceIn(ADJUSTMENT_MIN, ADJUSTMENT_MAX)
                binding.tvValue1.text = String.format("%.2f", target.highlights)

                binding.layoutSlider2.visibility = View.VISIBLE
                binding.tvLabel2.text = "Shadows"
                binding.slider2.valueFrom = ADJUSTMENT_MIN
                binding.slider2.valueTo = ADJUSTMENT_MAX
                binding.slider2.value = target.shadows.coerceIn(ADJUSTMENT_MIN, ADJUSTMENT_MAX)
                binding.tvValue2.text = String.format("%.2f", target.shadows)
            }
            2 -> { // Tone
                binding.tvLabel1.text = "Whites"
                binding.slider1.valueFrom = ADJUSTMENT_MIN
                binding.slider1.valueTo = ADJUSTMENT_MAX
                binding.slider1.value = target.whites.coerceIn(ADJUSTMENT_MIN, ADJUSTMENT_MAX)
                binding.tvValue1.text = String.format("%.2f", target.whites)

                binding.layoutSlider2.visibility = View.VISIBLE
                binding.tvLabel2.text = "Blacks"
                binding.slider2.valueFrom = ADJUSTMENT_MIN
                binding.slider2.valueTo = ADJUSTMENT_MAX
                binding.slider2.value = target.blacks.coerceIn(ADJUSTMENT_MIN, ADJUSTMENT_MAX)
                binding.tvValue2.text = String.format("%.2f", target.blacks)
            }
            3 -> { // Color
                binding.tvLabel1.text = "Saturation"
                binding.slider1.valueFrom = ADJUSTMENT_MIN
                binding.slider1.valueTo = ADJUSTMENT_MAX
                binding.slider1.value = target.saturation.coerceIn(ADJUSTMENT_MIN, ADJUSTMENT_MAX)
                binding.tvValue1.text = String.format("%.2f", target.saturation)

                binding.layoutSlider2.visibility = View.GONE
            }
        }
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

            // Save current zoom state
            savedMatrix.set(imageView.imageMatrix)
            savedScale = imageView.saveScale

            imageView.resetZoom()
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

            com.bumptech.glide.Glide.with(imageView).clear(imageView)
            if (lastCompositeBitmap != null) {
                imageView.setImageBitmap(lastCompositeBitmap)
            } else {
                val format = adapter.getSelectedFormat(currentIndex)
                adapter.setFormat(currentIndex, format)
            }

            // Restore zoom state
            imageView.restoreZoomState(savedMatrix, savedScale)
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
        val dngUri1 = currentGroup.dngUri ?: currentGroup.dngUri1
        val dngUri2 = currentGroup.dngUri2

        val currentIndex = binding.imagePager.currentItem
        val currentHolder = (binding.imagePager.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView)
            ?.findViewHolderForAdapterPosition(currentIndex) as? ImageViewerAdapter.ViewHolder

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
                    val logIndex = SettingsFragment.LOG_CURVES.indexOf(config.log)
                    val lutPath = if (config.lut != null && config.lut != "None") {
                        java.io.File(lutManager.lutDir, config.lut).absolutePath
                    } else null

                    fun processSingle(bytes: ByteArray?, uri: Uri, index: Int): android.graphics.Bitmap? {
                        val finalBytes = bytes ?: run {
                             context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                                java.io.FileInputStream(pfd.fileDescriptor).use { it.readBytes() }
                            }
                        } ?: return null

                        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(finalBytes, 0, finalBytes.size, options)
                        val ds = top.maary.darkbag.utils.ImageUtils.calculateInSampleSize(options, 1024, 1024)

                        val orientation = try {
                            context.contentResolver.openInputStream(uri)?.use { input ->
                                androidx.exifinterface.media.ExifInterface(input).getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL)
                            } ?: androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
                        } catch (e: Exception) { androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL }

                        val rotDegrees = when(orientation) {
                            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
                            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
                            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
                            else -> 0
                        }

                        val fullW = if (rotDegrees == 90 || rotDegrees == 270) options.outHeight / ds else options.outWidth / ds
                        val fullH = if (rotDegrees == 90 || rotDegrees == 270) options.outWidth / ds else options.outHeight / ds
                        val bmpW = (fullW / config.zoomFactor).toInt()
                        val bmpH = (fullH / config.zoomFactor).toInt()
                        val previewBitmap = android.graphics.Bitmap.createBitmap(bmpW, bmpH, android.graphics.Bitmap.Config.ARGB_8888)

                        val adj = if (currentGroup.isHalfFrame()) config.adjustments?.get(index) ?: top.maary.darkbag.models.BasicAdjustments() else config.toBasic()

                        val meta = repository.getCaptureMetadata(uri) ?: top.maary.darkbag.models.CaptureMetadata()
                        // Use a private JNI call or update current one to support metadata for TIFF?
                        // For now we use the existing one but we should ideally pass metadata.
                        top.maary.darkbag.processor.ColorProcessor.processRaw(
                            dngData = finalBytes,
                            targetLog = logIndex,
                            lutPath = lutPath,
                            exposure = adj.exposure,
                            contrast = adj.contrast,
                            saturation = adj.saturation,
                            highlights = adj.highlights,
                            shadows = adj.shadows,
                            whites = adj.whites,
                            blacks = adj.blacks,
                            digitalGain = 1.0f, // Gain is already in adj.exposure
                            outputJpgPath = null,
                            useGpu = false,
                            orientation = rotDegrees,
                            mirror = false,
                            outputBitmap = previewBitmap,
                            downsampleFactor = ds,
                            zoomFactor = config.zoomFactor
                        )
                        return previewBitmap
                    }

                    if (!currentGroup.isHalfFrame()) {
                        val primaryUri = dngUri1 ?: dngUri2 ?: return@withContext null
                        val primaryBytes = if (dngUri1 != null) sourceDngBytes else sourceDngBytes2
                        compositeBitmap = processSingle(primaryBytes, primaryUri!!, 0)
                    } else {
                        val forceUpdate1 = lastPreviewConfig == null ||
                                          lastPreviewConfig?.log != config.log ||
                                          lastPreviewConfig?.lut != config.lut ||
                                          lastPreviewConfig?.adjustments?.getOrNull(0) != config.adjustments?.getOrNull(0)

                        val forceUpdate2 = lastPreviewConfig == null ||
                                          lastPreviewConfig?.log != config.log ||
                                          lastPreviewConfig?.lut != config.lut ||
                                          lastPreviewConfig?.adjustments?.getOrNull(1) != config.adjustments?.getOrNull(1)

                        if (forceUpdate1 || cachedBitmap1 == null) {
                            cachedBitmap1 = dngUri1?.let { processSingle(sourceDngBytes, it, 0) }
                        }
                        if (forceUpdate2 || cachedBitmap2 == null) {
                            cachedBitmap2 = dngUri2?.let { processSingle(sourceDngBytes2, it, 1) }
                        }

                        val b1 = if (config.isSwapped) cachedBitmap2 else cachedBitmap1
                        val b2 = if (config.isSwapped) cachedBitmap1 else cachedBitmap2

                        if (b1 != null || b2 != null) {
                            val activeLayout = config.hfLayout ?: currentGroup.hfLayout
                            val isTB = activeLayout == "TB" || activeLayout?.contains("top", ignoreCase = true) == true
                            val isSBS = !isTB

                            val refW = b1?.width ?: b2?.width ?: 0
                            val refH = b1?.height ?: b2?.height ?: 0

                            // We need to ensure orientations are correct before composing
                            val oriented1 = b1?.let { top.maary.darkbag.utils.HalfFrameUtils.ensureOrientation(it, isSBS) }
                            val oriented2 = b2?.let { top.maary.darkbag.utils.HalfFrameUtils.ensureOrientation(it, isSBS) }

                            val w1 = oriented1?.width ?: refW
                            val h1 = oriented1?.height ?: refH
                            val w2 = oriented2?.width ?: refW
                            val h2 = oriented2?.height ?: refH
                            val gap = top.maary.darkbag.utils.HalfFrameUtils.calculateGap(maxOf(w1, h1)).toFloat()

                            // If one is missing, create a temporary black bitmap to avoid crashes
                            val tempB1 = oriented1 ?: android.graphics.Bitmap.createBitmap(w2, h2, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLACK) }
                            val tempB2 = oriented2 ?: android.graphics.Bitmap.createBitmap(w1, h1, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLACK) }

                            val composite = top.maary.darkbag.utils.HalfFrameUtils.composeBitmaps(tempB1, tempB2, isSBS)

                            val time1 = dngUri1?.let { repository.getCaptureMetadata(it)?.dateTimeOriginal } ?: currentGroup.captureTime
                            val time2 = dngUri2?.let { repository.getCaptureMetadata(it)?.dateTimeOriginal } ?: currentGroup.captureTime

                            // Swap timestamps if needed so they match the rendered positions
                            val t1 = if (config.isSwapped) time2 else time1
                            val t2 = if (config.isSwapped) time1 else time2

                            compositeBitmap = top.maary.darkbag.utils.HalfFrameUtils.addEffects(
                                composite,
                                config.showTimestamp,
                                config.flareType >= 0,
                                activeLayout ?: "SBS",
                                time1 = t1,
                                time2 = t2,
                                flareType = config.flareType
                            )
                            if (compositeBitmap != composite) {
                                composite.recycle()
                            }

                            if (oriented1 != b1) oriented1?.recycle()
                            if (oriented2 != b2) oriented2?.recycle()
                            if (tempB1 != oriented1) tempB1.recycle()
                            if (tempB2 != oriented2) tempB2.recycle()

                            if (isIndividual) {
                                selectedFrameBitmap = if (selectedDngIndex == 0) {
                                    android.graphics.Bitmap.createBitmap(compositeBitmap!!, 0, 0, w1, h1)
                                } else {
                                    if (isSBS) {
                                        android.graphics.Bitmap.createBitmap(compositeBitmap!!, (w1 + gap).toInt(), 0, w2, h2)
                                    } else {
                                        android.graphics.Bitmap.createBitmap(compositeBitmap!!, 0, (h1 + gap).toInt(), w2, h2)
                                    }
                                }
                            }

                            lastPreviewConfig = config.copy()
                        }
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
        val config = currentEditConfig ?: return
        val currentGroup = adapter.getGroup(binding.imagePager.currentItem)
        val finalConfig = config.copy(hfLayout = config.hfLayout ?: currentGroup.hfLayout)

        val dngUri1 = currentGroup.dngUri ?: currentGroup.dngUri1
        val dngUri2 = currentGroup.dngUri2

        previewJob?.cancel()
        binding.initialLoadingIndicator.visibility = View.VISIBLE
        binding.interactionBlocker?.visibility = View.VISIBLE

        val ctx = context ?: return
        val appContext = ctx.applicationContext
        lifecycleScope.launch {
            try {
                ensureDngBytesLoaded()
                withContext(Dispatchers.IO) {
                    try {
                        val context = appContext
                    val logIndex = SettingsFragment.LOG_CURVES.indexOf(config.log)
                    val lutPath = if (config.lut != null && config.lut != "None") {
                        java.io.File(lutManager.lutDir, config.lut).absolutePath
                    } else null

                    fun processFull(bytes: ByteArray?, uri: Uri, index: Int): android.graphics.Bitmap? {
                        val finalBytes = bytes ?: run {
                            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                                java.io.FileInputStream(pfd.fileDescriptor).use { it.readBytes() }
                            }
                        } ?: return null
                        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(finalBytes, 0, finalBytes.size, options)
                        val orientation = try {
                            context.contentResolver.openInputStream(uri)?.use { input ->
                                androidx.exifinterface.media.ExifInterface(input).getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL)
                            } ?: androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
                        } catch (e: Exception) { androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL }

                        val rotDegrees = when(orientation) {
                            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
                            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
                            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
                            else -> 0
                        }
                        val fullW = if (rotDegrees == 90 || rotDegrees == 270) options.outHeight else options.outWidth
                        val fullH = if (rotDegrees == 90 || rotDegrees == 270) options.outWidth else options.outHeight
                        val bmpW = (fullW / config.zoomFactor).toInt()
                        val bmpH = (fullH / config.zoomFactor).toInt()
                        val previewBitmap = android.graphics.Bitmap.createBitmap(bmpW, bmpH, android.graphics.Bitmap.Config.ARGB_8888)
                        val adj = if (currentGroup.isHalfFrame()) config.adjustments?.get(index) ?: top.maary.darkbag.models.BasicAdjustments() else config.toBasic()

                        val meta = repository.getCaptureMetadata(uri)
                        top.maary.darkbag.processor.ColorProcessor.processRaw(
                            dngData = finalBytes,
                            targetLog = logIndex,
                            lutPath = lutPath,
                            exposure = adj.exposure,
                            contrast = adj.contrast,
                            saturation = adj.saturation,
                            highlights = adj.highlights,
                            shadows = adj.shadows,
                            whites = adj.whites,
                            blacks = adj.blacks,
                            digitalGain = 1.0f, // Gain is already in adj.exposure
                            outputJpgPath = null,
                            useGpu = false,
                            orientation = rotDegrees,
                            mirror = false,
                            outputBitmap = previewBitmap,
                            downsampleFactor = 1,
                            zoomFactor = config.zoomFactor,
                            metadata = meta
                        )
                        return previewBitmap
                    }

                    val finalBitmap: android.graphics.Bitmap? = if (currentGroup.isMultiCamera) {
                        val lenses = if (currentGroup.multiCameraLenses.isNotEmpty()) currentGroup.multiCameraLenses else adapter.getMultiCameraLenses(currentGroup)
                        for (i in lenses.indices) {
                            val lens = lenses[i]
                            val dngUri = lens.dngUri ?: continue
                            val bmp = processFull(null, dngUri, i)
                            if (bmp != null) {
                                val jpgUri = lens.jpgUri
                                val fileName = if (jpgUri != null) getFileName(context, jpgUri).substringBeforeLast(".") else "${currentGroup.baseName}_MULTI_${lens.lensTag}"
                                val baseName = if (isReplacement) fileName else "${fileName}_edited_${System.currentTimeMillis()}"
                                val targetUri = if (isReplacement) jpgUri else null
                                val jpgFolderUri = context.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
                                    .getString(SettingsFragment.KEY_JPG_STORAGE_URI, null)
                                val captureMetadata = repository.getCaptureMetadata(dngUri)

                                top.maary.darkbag.utils.ImageSaver.saveProcessedImage(
                                    context = context,
                                    inputBitmap = bmp,
                                    bmpPath = null,
                                    rotationDegrees = 0,
                                    zoomFactor = 1.0f,
                                    baseName = baseName,
                                    linearDngPath = null,
                                    saveJpg = true,
                                    saveRaw = false,
                                    jpgFolderUri = jpgFolderUri,
                                    targetUri = targetUri,
                                    editConfig = config,
                                    captureMetadata = captureMetadata
                                )
                                bmp.recycle()
                            }
                        }
                        null
                    } else if (!currentGroup.isHalfFrame()) {
                        val primaryUri = dngUri1 ?: dngUri2 ?: return@withContext null
                        val primaryBytes = if (dngUri1 != null) sourceDngBytes else sourceDngBytes2
                        processFull(primaryBytes, primaryUri, 0)
                    } else {
                        val f1 = dngUri1?.let { processFull(sourceDngBytes, it, 0) }
                        val f2 = dngUri2?.let { processFull(sourceDngBytes2, it, 1) }

                        val b1 = if (finalConfig.isSwapped) f2 else f1
                        val b2 = if (finalConfig.isSwapped) f1 else f2

                        if (b1 != null || b2 != null) {
                            val activeLayout = config.hfLayout ?: currentGroup.hfLayout
                            val isTB = activeLayout == "TB" || activeLayout?.contains("top", ignoreCase = true) == true
                            val isSBS = !isTB

                            val refW = b1?.width ?: b2?.width ?: 0
                            val refH = b1?.height ?: b2?.height ?: 0

                            val oriented1 = b1?.let { top.maary.darkbag.utils.HalfFrameUtils.ensureOrientation(it, isSBS) }
                            val oriented2 = b2?.let { top.maary.darkbag.utils.HalfFrameUtils.ensureOrientation(it, isSBS) }

                            val w1 = oriented1?.width ?: refW
                            val h1 = oriented1?.height ?: refH
                            val w2 = oriented2?.width ?: refW
                            val h2 = oriented2?.height ?: refH

                            val tempB1 = oriented1 ?: android.graphics.Bitmap.createBitmap(w2, h2, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLACK) }
                            val tempB2 = oriented2 ?: android.graphics.Bitmap.createBitmap(w1, h1, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLACK) }

                            var composite = top.maary.darkbag.utils.HalfFrameUtils.composeBitmaps(tempB1, tempB2, isSBS)

                            val economical = top.maary.darkbag.utils.HalfFrameManager(context).downsample
                            if (economical) {
                                val scale = 0.707f
                                val scaledW = (composite.width * scale).toInt().coerceAtLeast(1)
                                val scaledH = (composite.height * scale).toInt().coerceAtLeast(1)
                                val scaled = android.graphics.Bitmap.createScaledBitmap(composite, scaledW, scaledH, true)
                                if (scaled != composite) {
                                    composite.recycle()
                                    composite = scaled
                                }
                            }

                            if (oriented1 != b1) oriented1?.recycle()
                            if (oriented2 != b2) oriented2?.recycle()
                            if (tempB1 != oriented1) tempB1.recycle()
                            if (tempB2 != oriented2) tempB2.recycle()

                            val time1 = dngUri1?.let { repository.getCaptureMetadata(it)?.dateTimeOriginal } ?: currentGroup.captureTime
                            val time2 = dngUri2?.let { repository.getCaptureMetadata(it)?.dateTimeOriginal } ?: currentGroup.captureTime

                            val t1 = if (finalConfig.isSwapped) time2 else time1
                            val t2 = if (finalConfig.isSwapped) time1 else time2

                            val finalComposite = top.maary.darkbag.utils.HalfFrameUtils.addEffects(
                                composite,
                                finalConfig.showTimestamp,
                                finalConfig.flareType >= 0,
                                activeLayout ?: "SBS",
                                time1 = t1,
                                time2 = t2,
                                flareType = finalConfig.flareType
                            )
                            if (finalComposite != composite) {
                                composite.recycle()
                            }
                            f1?.recycle()
                            f2?.recycle()
                            finalComposite
                        } else null
                    }

                    finalBitmap?.let { bitmap ->
                        val baseName = if (isReplacement) currentGroup.baseName else "${currentGroup.baseName}_edited_${System.currentTimeMillis()}"
                        val targetUri = if (isReplacement) currentGroup.jpgUri else null
                        val jpgFolderUri = context.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
                            .getString(SettingsFragment.KEY_JPG_STORAGE_URI, null)

                        val captureMetadata = (currentGroup.jpgUri ?: currentGroup.dngUri ?: currentGroup.dngUri1)?.let { repository.getCaptureMetadata(it) }

                        top.maary.darkbag.utils.ImageSaver.saveProcessedImage(
                            context = context,
                            inputBitmap = bitmap,
                            bmpPath = null,
                            rotationDegrees = 0,
                            zoomFactor = 1.0f,
                            baseName = baseName,
                            linearDngPath = null,
                            saveJpg = true,
                            saveRaw = false,
                            targetUri = targetUri,
                            jpgFolderUri = if (isReplacement) null else jpgFolderUri,
                            editConfig = finalConfig,
                            isAlreadyStitched = true, // Force true to avoid HalfFrameManager intercepting user's explicit save actions
                            captureMetadata = captureMetadata
                        )
                        bitmap.recycle()
                    }
                } catch (e: Exception) {
                    android.util.Log.e("ImageViewerFragment", "Failed to save edit", e)
                }
            }

                resetAdjustments()
                repository.invalidateCache()
                val updatedGroups = repository.getGroupedImages(forceRefresh = true)
                if (updatedGroups.isNotEmpty()) {
                    val targetBaseName = currentGroup.baseName
                    val newPos = updatedGroups.indexOfFirst { it.baseName == targetBaseName }.coerceAtLeast(0)
                    adapter = ImageViewerAdapter(updatedGroups, lifecycleScope, requireContext()).apply {
                        isMotionPhotoAutoPlay = this@ImageViewerFragment.isMotionPhotoAutoPlay
                        onImageTapped = { toggleUi() }
                        onZoomChanged = { isZoomed -> if (isZoomed) hideUi() else showUi() }
                        onLongPressStarted = { handleLongPressStarted(it) }
                        onLongPressEnded = { handleLongPressEnded(it) }
                        onMotionPhotoIndicatorTapped = { pos -> handleMotionPhotoIndicatorTapped(pos) }
                        onMultiCameraLensChanged = { _, _ ->
                            updateSplitButtons()
                            updateToolbarIcon()
                        }
                        setFormatSwitcherPersistentHidden(isAdjusted)
                        onCurrentListChanged = { previousList, currentList ->
                            val currentIndex = binding.imagePager.currentItem
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
                    binding.imagePager.setCurrentItem(newPos, false)
                    updateControlsVisibility()
                }
            } finally {
                binding.initialLoadingIndicator.visibility = View.GONE
                binding.interactionBlocker?.visibility = View.GONE
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

                    val logIndex = SettingsFragment.LOG_CURVES.indexOf(config.log)
                    val lutPath = if (config.lut != null && config.lut != "None") {
                        java.io.File(lutManager.lutDir, config.lut).absolutePath
                    } else null

                    fun processFullToTiff(bytes: ByteArray?, uri: Uri, index: Int, targetTiffPath: String?): android.graphics.Bitmap? {
                        val finalBytes = bytes ?: run {
                            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                                java.io.FileInputStream(pfd.fileDescriptor).use { it.readBytes() }
                            }
                        } ?: return null
                        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(finalBytes, 0, finalBytes.size, options)
                        val orientation = try {
                            context.contentResolver.openInputStream(uri)?.use { input ->
                                androidx.exifinterface.media.ExifInterface(input).getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL)
                            } ?: androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
                        } catch (e: Exception) { androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL }

                        val rotDegrees = when(orientation) {
                            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
                            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
                            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
                            else -> 0
                        }
                        val fullW = if (rotDegrees == 90 || rotDegrees == 270) options.outHeight else options.outWidth
                        val fullH = if (rotDegrees == 90 || rotDegrees == 270) options.outWidth else options.outHeight
                        val bmpW = (fullW / config.zoomFactor).toInt()
                        val bmpH = (fullH / config.zoomFactor).toInt()
                        val previewBitmap = android.graphics.Bitmap.createBitmap(bmpW, bmpH, android.graphics.Bitmap.Config.ARGB_8888)
                        val adj = if (currentGroup.isHalfFrame()) config.adjustments?.get(index) ?: top.maary.darkbag.models.BasicAdjustments() else config.toBasic()

                        val meta = repository.getCaptureMetadata(uri)
                        top.maary.darkbag.processor.ColorProcessor.processRaw(
                            dngData = finalBytes,
                            targetLog = logIndex,
                            lutPath = lutPath,
                            exposure = adj.exposure,
                            contrast = adj.contrast,
                            saturation = adj.saturation,
                            highlights = adj.highlights,
                            shadows = adj.shadows,
                            whites = adj.whites,
                            blacks = adj.blacks,
                            digitalGain = 1.0f,
                            outputJpgPath = null,
                            outputTiffPath = targetTiffPath,
                            useGpu = false,
                            orientation = rotDegrees,
                            mirror = false,
                            outputBitmap = if (targetTiffPath != null) null else previewBitmap,
                            downsampleFactor = 1,
                            zoomFactor = config.zoomFactor,
                            metadata = meta
                        )
                        return previewBitmap
                    }

                    if (!currentGroup.isHalfFrame()) {
                        val primaryUri = dngUri1 ?: dngUri2 ?: return@withContext null
                        val primaryBytes = if (dngUri1 != null) sourceDngBytes else sourceDngBytes2
                        processFullToTiff(primaryBytes, primaryUri, 0, tempTiff.absolutePath)?.recycle()
                    } else {
                        val f1 = dngUri1?.let { processFullToTiff(sourceDngBytes, it, 0, null) }
                        val f2 = dngUri2?.let { processFullToTiff(sourceDngBytes2, it, 1, null) }

                        val b1 = if (config.isSwapped) f2 else f1
                        val b2 = if (config.isSwapped) f1 else f2

                        if (b1 != null || b2 != null) {
                            val activeLayout = config.hfLayout ?: currentGroup.hfLayout
                            val isTB = activeLayout == "TB" || activeLayout?.contains("top", ignoreCase = true) == true
                            val isSBS = !isTB

                            val refW = b1?.width ?: b2?.width ?: 0
                            val refH = b1?.height ?: b2?.height ?: 0

                            val oriented1 = b1?.let { top.maary.darkbag.utils.HalfFrameUtils.ensureOrientation(it, isSBS) }
                            val oriented2 = b2?.let { top.maary.darkbag.utils.HalfFrameUtils.ensureOrientation(it, isSBS) }

                            val w1 = oriented1?.width ?: refW
                            val h1 = oriented1?.height ?: refH
                            val w2 = oriented2?.width ?: refW
                            val h2 = oriented2?.height ?: refH

                            val tempB1 = oriented1 ?: android.graphics.Bitmap.createBitmap(w2, h2, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLACK) }
                            val tempB2 = oriented2 ?: android.graphics.Bitmap.createBitmap(w1, h1, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLACK) }

                            var composite = top.maary.darkbag.utils.HalfFrameUtils.composeBitmaps(tempB1, tempB2, isSBS)

                            val economical = top.maary.darkbag.utils.HalfFrameManager(context).downsample
                            if (economical) {
                                val scale = 0.707f
                                val scaledW = (composite.width * scale).toInt().coerceAtLeast(1)
                                val scaledH = (composite.height * scale).toInt().coerceAtLeast(1)
                                val scaled = android.graphics.Bitmap.createScaledBitmap(composite, scaledW, scaledH, true)
                                if (scaled != composite) {
                                    composite.recycle()
                                    composite = scaled
                                }
                            }

                            if (oriented1 != b1) oriented1?.recycle()
                            if (oriented2 != b2) oriented2?.recycle()
                            if (tempB1 != oriented1) tempB1.recycle()
                            if (tempB2 != oriented2) tempB2.recycle()

                            val time1 = dngUri1?.let { repository.getCaptureMetadata(it)?.dateTimeOriginal } ?: currentGroup.captureTime
                            val time2 = dngUri2?.let { repository.getCaptureMetadata(it)?.dateTimeOriginal } ?: currentGroup.captureTime

                            val t1 = if (config.isSwapped) time2 else time1
                            val t2 = if (config.isSwapped) time1 else time2

                            val finalComposite = top.maary.darkbag.utils.HalfFrameUtils.addEffects(
                                composite,
                                config.showTimestamp,
                                config.flareType >= 0,
                                activeLayout ?: "SBS",
                                time1 = t1,
                                time2 = t2,
                                flareType = config.flareType
                            )

                            val primaryUri = currentGroup.dngUri ?: currentGroup.dngUri1 ?: currentGroup.dngUri2 ?: Uri.EMPTY
                            var meta = repository.getCaptureMetadata(primaryUri) ?: top.maary.darkbag.models.CaptureMetadata()
                            if (meta.dateTimeOriginal == null) {
                                val captureTime = System.currentTimeMillis()
                                val offset = java.text.SimpleDateFormat("XXX", java.util.Locale.US).format(java.util.Date(captureTime))
                                meta = meta.copy(
                                    dateTimeOriginal = captureTime,
                                    dateTimeDigitized = captureTime,
                                    offsetTime = offset,
                                    offsetTimeOriginal = offset,
                                    offsetTimeDigitized = offset
                                )
                            }
                            top.maary.darkbag.processor.ColorProcessor.saveBitmapToTiff(finalComposite, tempTiff.absolutePath, meta)

                            if (finalComposite != composite) {
                                composite.recycle()
                            }
                            finalComposite.recycle()
                            f1?.recycle()
                            f2?.recycle()
                        }
                    }

                    android.provider.DocumentsContract.buildDocumentUri(
                        top.maary.darkbag.provider.DarkbagDocumentsProvider.AUTHORITY,
                        "${top.maary.darkbag.provider.DarkbagDocumentsProvider.ROOT_ID_EXPORTS}:${tempTiff.name}"
                    )
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

    private fun shareTiff(uri: Uri) {
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "image/tiff"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
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
        val intent = if (uris.size == 1) {
            android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "image/*"
                putExtra(android.content.Intent.EXTRA_STREAM, uris[0])
            }
        } else {
            android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE).apply {
                type = "image/*"
                putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, ArrayList(uris))
            }
        }
        intent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(android.content.Intent.createChooser(intent, "Share Image"))
        } catch (e: android.content.ActivityNotFoundException) {
            android.widget.Toast.makeText(requireContext(), "No app found to share the image.", android.widget.Toast.LENGTH_SHORT).show()
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
        if (group.isSingleFormat()) {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.delete_image_title)
                .setMessage(R.string.delete_image_message)
                .setPositiveButton(R.string.delete_button_alt) { _, _ ->
                    deleteImage(group, true)
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }

        val options = arrayOf(getString(R.string.delete_this_format_only), getString(R.string.delete_entire_group))
        var checkedItem = 1
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_image_title)
            .setSingleChoiceItems(options, checkedItem) { _, which ->
                checkedItem = which
            }
            .setPositiveButton(R.string.delete_button_alt) { _, _ ->
                deleteImage(group, checkedItem == 1)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    protected open fun deleteImage(group: ImageGroup, deleteGroup: Boolean) {
        val context = context ?: return
        lifecycleScope.launch {
            var nextTargetUri: String? = null
            val currentIndex = binding.imagePager.currentItem
            val urisToDelete = mutableListOf<Uri>()

            if (deleteGroup) {
                group.jpgUri?.let { urisToDelete.add(it) }
                group.dngUri?.let { urisToDelete.add(it) }
                group.dngUri1?.let { urisToDelete.add(it) }
                group.dngUri2?.let { urisToDelete.add(it) }

                if (adapter.itemCount > 1) {
                    val nextIndex = if (currentIndex < adapter.itemCount - 1) currentIndex + 1 else currentIndex - 1
                    val nextGroup = adapter.getGroup(nextIndex)
                    nextTargetUri = (nextGroup.jpgUri ?: nextGroup.dngUri)?.toString()
                }
            } else {
                val selectedFormat = adapter.getSelectedFormat(binding.imagePager.currentItem)
                if (selectedFormat == "DNG" && group.isHalfFrame()) {
                     group.dngUri1?.let { urisToDelete.add(it) }
                     group.dngUri2?.let { urisToDelete.add(it) }
                } else {
                    val currentUri = when (selectedFormat) {
                        "JPG" -> group.jpgUri
                        "DNG" -> group.dngUri ?: group.dngUri1 ?: group.dngUri2
                        else -> group.jpgUri ?: group.dngUri ?: group.dngUri1 ?: group.dngUri2
                    }
                    currentUri?.let { urisToDelete.add(it) }
                }
            }

            val securityExceptionUris = mutableListOf<Uri>()
            for (uri in urisToDelete) {
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
                repository.invalidateCache()
                val remainingGroup = repository.getGroupedImages(forceRefresh = true).find { it.baseName == group.baseName }
                nextTargetUri = if (remainingGroup != null) {
                    (remainingGroup.jpgUri ?: remainingGroup.dngUri)?.toString()
                } else {
                    if (adapter.itemCount > 1) {
                        val nextIndex = if (currentIndex < adapter.itemCount - 1) currentIndex + 1 else currentIndex - 1
                        val nextGroup = adapter.getGroup(nextIndex)
                        (nextGroup.jpgUri ?: nextGroup.dngUri)?.toString()
                    } else null
                }
            }

            if (securityExceptionUris.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                pendingDeleteNextTargetUri = nextTargetUri
                try {
                    val deleteRequest = MediaStore.createDeleteRequest(context.contentResolver, securityExceptionUris)
                    deleteLauncher.launch(IntentSenderRequest.Builder(deleteRequest.intentSender).build())
                } catch (e: Exception) {
                    context.let { Toast.makeText(it, "Failed to launch delete prompt", Toast.LENGTH_SHORT).show() }
                    repository.invalidateCache()
                    loadImages(nextTargetUri, forceRefresh = true)
                }
            } else {
                repository.invalidateCache()
                loadImages(nextTargetUri, forceRefresh = true)
            }
        }
    }



    private fun setupToolbar() {
        binding.btnNavigation.setOnClickListener {
            if (isGalleryMode) {
                if (::galleryAdapter.isInitialized && galleryAdapter.isSelectionMode) {
                    galleryAdapter.setSelectionMode(false)
                    updateGallerySelectionUI(0)
                } else {
                    exitGalleryMode()
                }
            } else if (binding.lutListContainer.visibility == View.VISIBLE) {
                binding.lutListContainer.visibility = View.GONE
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

    protected fun setupGalleryView() {
        val isLandscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val spanCount = if (isLandscape) 5 else 3

        galleryAdapter = DarkbagGalleryGridAdapter(requireContext()).apply {
            onItemClick = { _, pos ->
                exitGalleryMode(pos)
            }
            onItemLongClick = { _, _ ->
                updateGallerySelectionUI(getSelectedCount())
            }
            onSelectionChanged = { count ->
                updateGallerySelectionUI(count)
            }
        }

        binding.galleryRecyclerView.layoutManager = GridLayoutManager(requireContext(), spanCount)
        binding.galleryRecyclerView.adapter = galleryAdapter

        binding.btnGalleryPill.setOnClickListener {
            enterGalleryMode()
        }

        binding.galleryToolbar.setNavigationOnClickListener {
            if (galleryAdapter.isSelectionMode) {
                galleryAdapter.setSelectionMode(false)
                updateGallerySelectionUI(0)
            } else {
                exitGalleryMode()
            }
        }

        binding.btnGalleryDelete.setOnClickListener {
            val selected = galleryAdapter.getSelectedGroups()
            if (selected.isNotEmpty()) {
                showBatchDeleteDialog(selected)
            }
        }
    }

    protected fun updateGalleryPill(position: Int, totalCount: Int) {
        if (totalCount <= 0) {
            binding.btnGalleryPill.visibility = View.GONE
        } else {
            val currentPos = (position + 1).coerceIn(1, totalCount)
            binding.btnGalleryPill.text = getString(R.string.gallery_pill_format, currentPos, totalCount)
            binding.btnGalleryPill.visibility = if (isUiVisible && !isEditingAdjustments && !isGalleryMode) View.VISIBLE else View.GONE
        }
    }

    fun enterGalleryMode(targetPosition: Int = binding.imagePager.currentItem) {
        if (isEditingAdjustments || isAdjusted) return
        if (isGalleryMode) return
        isGalleryMode = true

        if (::adapter.isInitialized) {
            adapter.stopAllMotionVideos()
        }

        binding.galleryContainer.visibility = View.VISIBLE
        binding.galleryContainer.alpha = 0f
        binding.galleryContainer.animate().alpha(1f).setDuration(200).start()

        binding.topBarContainer.visibility = View.GONE
        binding.bottomLeftControls.visibility = View.GONE
        binding.bottomRightControls.visibility = View.GONE
        binding.imagePager.visibility = View.INVISIBLE

        val currentCount = if (::galleryAdapter.isInitialized) galleryAdapter.itemCount else 0
        binding.galleryToolbar.title = "${getString(R.string.gallery_title)} ($currentCount)"
        updateGallerySelectionUI(0)

        if (targetPosition in 0 until (if (::galleryAdapter.isInitialized) galleryAdapter.itemCount else 0)) {
            binding.galleryRecyclerView.scrollToPosition(targetPosition)
        }

        updateBackPressedCallbackState()
    }

    fun exitGalleryMode(targetPosition: Int = -1) {
        if (!isGalleryMode) return
        isGalleryMode = false

        if (::galleryAdapter.isInitialized) {
            galleryAdapter.setSelectionMode(false)
        }

        binding.galleryContainer.animate().alpha(0f).setDuration(150).withEndAction {
            binding.galleryContainer.visibility = View.GONE
        }.start()

        binding.imagePager.visibility = View.VISIBLE
        showUi()

        if (targetPosition in 0 until (if (::adapter.isInitialized) adapter.itemCount else 0)) {
            binding.imagePager.setCurrentItem(targetPosition, false)
        }

        updateBackPressedCallbackState()
    }

    private fun updateGallerySelectionUI(selectedCount: Int) {
        if (!::galleryAdapter.isInitialized) return
        if (galleryAdapter.isSelectionMode) {
            binding.galleryToolbar.navigationIcon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_close)
            binding.galleryToolbar.title = getString(R.string.gallery_selected_count, selectedCount)
            binding.galleryBottomBar.visibility = if (selectedCount > 0) View.VISIBLE else View.GONE

            binding.galleryToolbar.menu.clear()
            val selectAllItem = binding.galleryToolbar.menu.add(0, 1001, 0, R.string.gallery_select_all)
            val allSelected = galleryAdapter.getSelectedCount() == galleryAdapter.itemCount && galleryAdapter.itemCount > 0
            selectAllItem.setIcon(if (allSelected) R.drawable.ic_deselect else R.drawable.ic_select_all)
            selectAllItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)

            binding.galleryToolbar.setOnMenuItemClickListener { item ->
                if (item.itemId == 1001) {
                    if (galleryAdapter.getSelectedCount() == galleryAdapter.itemCount && galleryAdapter.itemCount > 0) {
                        galleryAdapter.deselectAll()
                    } else {
                        galleryAdapter.selectAll()
                    }
                    true
                } else false
            }
        } else {
            binding.galleryToolbar.navigationIcon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_back)
            val count = galleryAdapter.itemCount
            binding.galleryToolbar.title = "${getString(R.string.gallery_title)} ($count)"
            binding.galleryBottomBar.visibility = View.GONE
            binding.galleryToolbar.menu.clear()
        }
    }

    private fun showBatchDeleteDialog(selectedGroups: List<ImageGroup>) {
        if (selectedGroups.isEmpty()) return

        val options = arrayOf(
            getString(R.string.gallery_delete_option_group),
            getString(R.string.gallery_delete_option_raw_only),
            getString(R.string.gallery_delete_option_jpg_only)
        )
        var checkedItem = 0

        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.gallery_delete_dialog_title)
            .setMessage(getString(R.string.gallery_delete_summary, selectedGroups.size))
            .setSingleChoiceItems(options, checkedItem) { _, which ->
                checkedItem = which
            }
            .setPositiveButton(R.string.delete_button_alt) { _, _ ->
                deleteBatchImages(selectedGroups, checkedItem)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun deleteBatchImages(selectedGroups: List<ImageGroup>, deleteMode: Int) {
        val context = context ?: return
        lifecycleScope.launch {
            val urisToDelete = mutableListOf<Uri>()

            for (group in selectedGroups) {
                when (deleteMode) {
                    0 -> { // Entire group
                        group.jpgUri?.let { urisToDelete.add(it) }
                        group.dngUri?.let { urisToDelete.add(it) }
                        group.dngUri1?.let { urisToDelete.add(it) }
                        group.dngUri2?.let { urisToDelete.add(it) }
                    }
                    1 -> { // RAW only
                        group.dngUri?.let { urisToDelete.add(it) }
                        group.dngUri1?.let { urisToDelete.add(it) }
                        group.dngUri2?.let { urisToDelete.add(it) }
                    }
                    2 -> { // JPG only
                        group.jpgUri?.let { urisToDelete.add(it) }
                    }
                }
            }

            val securityExceptionUris = mutableListOf<Uri>()
            for (uri in urisToDelete) {
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

            galleryAdapter.setSelectionMode(false)
            updateGallerySelectionUI(0)

            if (securityExceptionUris.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    val deleteRequest = MediaStore.createDeleteRequest(context.contentResolver, securityExceptionUris)
                    deleteLauncher.launch(IntentSenderRequest.Builder(deleteRequest.intentSender).build())
                } catch (e: Exception) {
                    context.let { Toast.makeText(it, "Failed to launch delete prompt", Toast.LENGTH_SHORT).show() }
                    repository.invalidateCache()
                    loadImages(forceRefresh = true)
                }
            } else {
                repository.invalidateCache()
                loadImages(forceRefresh = true)
            }
        }
    }

    protected fun toggleUi() {
        if (isEditingAdjustments || isGalleryMode) return
        if (isUiVisible) hideUi() else showUi()
    }

    protected fun showUi() {
        if (isGalleryMode) return
        if (isUiVisible) return
        isUiVisible = true

        binding.topBarContainer.visibility = View.VISIBLE
        updateSplitButtons()
        updateControlsVisibility()

        adapter.setUiVisibility(true)

        binding.topBarContainer.animate().translationY(0f).alpha(1f).setDuration(200).setListener(null).start()
        binding.bottomLeftControls.animate().translationY(0f).alpha(1f).setDuration(200).setListener(null).start()
        binding.bottomRightControls.animate().translationY(0f).alpha(1f).setDuration(200).setListener(null).start()
        updateViewportPadding()
    }

    protected fun hideUi() {
        if (isGalleryMode) return
        if (!isUiVisible) return
        isUiVisible = false

        adapter.setUiVisibility(false)

        val topShift = -(binding.topBarContainer.height + (binding.topBarContainer.layoutParams as ViewGroup.MarginLayoutParams).topMargin).toFloat()
        val bottomShift = (binding.bottomLeftControls.height + (binding.bottomLeftControls.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin).toFloat()

        binding.topBarContainer.animate().translationY(topShift).alpha(0f).setDuration(200)
            .withEndAction { binding.topBarContainer.visibility = View.GONE }.start()
        binding.bottomLeftControls.animate().translationY(bottomShift).alpha(0f).setDuration(200)
            .withEndAction { binding.bottomLeftControls.visibility = View.GONE }.start()
        binding.bottomRightControls.animate().translationY(bottomShift).alpha(0f).setDuration(200)
            .withEndAction { binding.bottomRightControls.visibility = View.GONE }.start()
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

        ViewCompat.setOnApplyWindowInsetsListener(binding.viewerRoot) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            systemTopInset = systemBars.top
            systemBottomInset = systemBars.bottom
            updateViewportPadding()

            binding.topBarContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = systemBars.top
            }
            binding.btnNavigation.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                leftMargin = marginMedium
            }
            binding.splitShare.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                rightMargin = systemBars.right + marginMedium
            }
            binding.splitSave.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                rightMargin = systemBars.right + marginMedium
            }
            binding.bottomLeftControls.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = systemBars.bottom + marginMedium
                leftMargin = systemBars.left + marginMedium
            }
            binding.bottomRightControls.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = systemBars.bottom
                rightMargin = systemBars.right
            }

            binding.galleryToolbar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = systemBars.top
                leftMargin = systemBars.left
                rightMargin = systemBars.right
            }
            binding.galleryBottomBar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = systemBars.bottom
                leftMargin = systemBars.left
                rightMargin = systemBars.right
            }

            val isLandscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            binding.editAdjustmentPanel.setPadding(
                0, 0,
                if (isLandscape) systemBars.right else 0,
                if (isLandscape) 0 else systemBars.bottom
            )

            insets
        }
    }

    private fun updateViewportPadding() {
        val isLandscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

        val topPadding: Int
        val bottomPadding: Int

        if (isEditingAdjustments) {
            topPadding = systemTopInset + topBarHeight
            if (isLandscape) {
                bottomPadding = topPadding
            } else {
                // In portrait edit mode, clear the adjustment panel (including its card margins and system insets)
                bottomPadding = binding.editAdjustmentPanel.height
            }
        } else if (isUiVisible) {
            topPadding = systemTopInset + topBarHeight
            bottomPadding = topPadding // SYMMETRIC CENTERING
        } else {
            topPadding = 0
            bottomPadding = 0
        }

        android.transition.TransitionManager.beginDelayedTransition(binding.viewerRoot, android.transition.AutoTransition().apply {
            duration = 200
            addTarget(binding.imagePager)
        })
        binding.imagePager.setPadding(0, topPadding, 0, bottomPadding)

        // Ensure half-frame masks match the visible viewport
        val currentGroup = if (::adapter.isInitialized && adapter.itemCount > 0) adapter.getGroup(binding.imagePager.currentItem) else null
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
        backPressedCallback.isEnabled = isGalleryMode || isAdjusted || isEditingAdjustments || binding.lutListContainer.visibility == View.VISIBLE
    }

    protected open fun showDiscardChangesDialog() {
        if (isEditingAdjustments) {
            exitEditMode(apply = false)
            return
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

    override fun onDestroyView() {
        if (::adapter.isInitialized) {
            adapter.stopAllMotionVideos()
        }
        binding.imagePager.unregisterOnPageChangeCallback(pageChangeCallback)
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val KEY_VIEWER_MOTION_PHOTO_AUTO_PLAY = "viewer_motion_photo_auto_play"

        private const val MENU_DETAILS = 1
        private const val MENU_DELETE = 2
        private const val MENU_SAVE_AS = 3
        private const val MENU_SHARE_TIFF = 4
        private const val MENU_COLLAGE = 5

        private const val EXPOSURE_MIN = -4f
        private const val EXPOSURE_MAX = 4f
        private const val ADJUSTMENT_MIN = -1f
        private const val ADJUSTMENT_MAX = 1f
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingDeleteNextTargetUri?.let { outState.putString("pendingDeleteNextTargetUri", it) }
    }

    protected suspend fun generateProcessedBitmap(config: top.maary.darkbag.models.EditConfig, currentGroup: ImageGroup): android.graphics.Bitmap? {
        val ctx = context ?: return null
        val appContext = ctx.applicationContext
        return withContext(Dispatchers.IO) {
            val context = appContext
            val dngUri1 = currentGroup.dngUri ?: currentGroup.dngUri1
            val dngUri2 = currentGroup.dngUri2
            val logIndex = SettingsFragment.LOG_CURVES.indexOf(config.log)
            val lutPath = if (config.lut != null && config.lut != "None") {
                java.io.File(lutManager.lutDir, config.lut).absolutePath
            } else null

            fun processFull(bytes: ByteArray?, uri: Uri, index: Int): android.graphics.Bitmap? {
                val finalBytes = bytes ?: run {
                    context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                        java.io.FileInputStream(pfd.fileDescriptor).use { it.readBytes() }
                    }
                } ?: return null
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(finalBytes, 0, finalBytes.size, options)
                val orientation = try {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        androidx.exifinterface.media.ExifInterface(input).getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL)
                    } ?: androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
                } catch (e: Exception) { androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL }

                val rotDegrees = when(orientation) {
                    androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
                val fullW = if (rotDegrees == 90 || rotDegrees == 270) options.outHeight else options.outWidth
                val fullH = if (rotDegrees == 90 || rotDegrees == 270) options.outWidth else options.outHeight
                val bmpW = (fullW / config.zoomFactor).toInt()
                val bmpH = (fullH / config.zoomFactor).toInt()
                val previewBitmap = android.graphics.Bitmap.createBitmap(bmpW, bmpH, android.graphics.Bitmap.Config.ARGB_8888)
                val adj = if (currentGroup.isHalfFrame()) config.adjustments?.get(index) ?: top.maary.darkbag.models.BasicAdjustments() else top.maary.darkbag.models.BasicAdjustments(config.exposure, config.contrast, config.saturation, config.highlights, config.shadows, config.whites, config.blacks)

                val meta = repository.getCaptureMetadata(uri)
                top.maary.darkbag.processor.ColorProcessor.processRaw(
                    dngData = finalBytes,
                    targetLog = logIndex,
                    lutPath = lutPath,
                    exposure = adj.exposure,
                    contrast = adj.contrast,
                    saturation = adj.saturation,
                    highlights = adj.highlights,
                    shadows = adj.shadows,
                    whites = adj.whites,
                    blacks = adj.blacks,
                    digitalGain = 1.0f,
                    outputJpgPath = null,
                    outputTiffPath = null,
                    useGpu = false,
                    orientation = rotDegrees,
                    mirror = false,
                    outputBitmap = previewBitmap,
                    downsampleFactor = 1,
                    zoomFactor = config.zoomFactor,
                    metadata = meta
                )
                return previewBitmap
            }

            if (!currentGroup.isHalfFrame()) {
                val primaryUri = dngUri1 ?: dngUri2 ?: return@withContext null
                val primaryBytes = if (dngUri1 != null) sourceDngBytes else sourceDngBytes2
                processFull(primaryBytes, primaryUri, 0)
            } else {
                val f1 = dngUri1?.let { processFull(sourceDngBytes, it, 0) }
                val f2 = dngUri2?.let { processFull(sourceDngBytes2, it, 1) }

                val b1 = if (config.isSwapped) f2 else f1
                val b2 = if (config.isSwapped) f1 else f2

                if (b1 != null || b2 != null) {
                    val activeLayout = config.hfLayout ?: currentGroup.hfLayout
                    val isTB = activeLayout == "TB" || activeLayout?.contains("top", ignoreCase = true) == true
                    val isSBS = !isTB

                    val refW = b1?.width ?: b2?.width ?: 0
                    val refH = b1?.height ?: b2?.height ?: 0

                    val oriented1 = b1?.let { top.maary.darkbag.utils.HalfFrameUtils.ensureOrientation(it, isSBS) }
                    val oriented2 = b2?.let { top.maary.darkbag.utils.HalfFrameUtils.ensureOrientation(it, isSBS) }

                    val w1 = oriented1?.width ?: refW
                    val h1 = oriented1?.height ?: refH
                    val w2 = oriented2?.width ?: refW
                    val h2 = oriented2?.height ?: refH

                    val tempB1 = oriented1 ?: android.graphics.Bitmap.createBitmap(w2, h2, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLACK) }
                    val tempB2 = oriented2 ?: android.graphics.Bitmap.createBitmap(w1, h1, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLACK) }

                    var composite = top.maary.darkbag.utils.HalfFrameUtils.composeBitmaps(tempB1, tempB2, isSBS)

                    val economical = top.maary.darkbag.utils.HalfFrameManager(context).downsample
                    if (economical) {
                        val scale = 0.707f
                        val scaledW = (composite.width * scale).toInt().coerceAtLeast(1)
                        val scaledH = (composite.height * scale).toInt().coerceAtLeast(1)
                        val scaled = android.graphics.Bitmap.createScaledBitmap(composite, scaledW, scaledH, true)
                        if (scaled != composite) {
                            composite.recycle()
                            composite = scaled
                        }
                    }

                    if (oriented1 != b1) oriented1?.recycle()
                    if (oriented2 != b2) oriented2?.recycle()
                    if (tempB1 != oriented1) tempB1.recycle()
                    if (tempB2 != oriented2) tempB2.recycle()

                    val time1 = dngUri1?.let { repository.getCaptureMetadata(it)?.dateTimeOriginal } ?: currentGroup.captureTime
                    val time2 = dngUri2?.let { repository.getCaptureMetadata(it)?.dateTimeOriginal } ?: currentGroup.captureTime

                    val t1 = if (config.isSwapped) time2 else time1
                    val t2 = if (config.isSwapped) time1 else time2

                    val finalComposite = top.maary.darkbag.utils.HalfFrameUtils.addEffects(
                        composite,
                        config.showTimestamp,
                        config.flareType >= 0,
                        activeLayout ?: "SBS",
                        time1 = t1,
                        time2 = t2,
                        flareType = config.flareType
                    )
                    if (finalComposite != composite) {
                        composite.recycle()
                    }
                    f1?.recycle()
                    f2?.recycle()
                    finalComposite
                } else null
            }
        }
    }
}
