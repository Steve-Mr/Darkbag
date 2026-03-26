package top.maary.darkbag.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import android.content.Context
import android.graphics.BitmapFactory
import com.bumptech.glide.Glide
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

class ImageViewerFragment : Fragment() {

    private var _binding: FragmentImageViewerBinding? = null
    private var isUiVisible = true
    private val binding get() = _binding!!
    private val args: ImageViewerFragmentArgs by navArgs()
    private lateinit var repository: ImageRepository
    private lateinit var adapter: ImageViewerAdapter

    private var isAdjusted = false
    private var isEditingAdjustments = false
    private var systemTopInset = 0
    private var systemBottomInset = 0
    private var topBarHeight = 0
    private var configBeforeEditing: top.maary.darkbag.models.EditConfig? = null
    private var currentEditConfig: top.maary.darkbag.models.EditConfig? = null
    private var selectedDngIndex = 0 // 0 or 1 for half-frame
    private var sourceDngBytes: ByteArray? = null
    private var sourceDngBytes2: ByteArray? = null
    private var cachedBitmap1: android.graphics.Bitmap? = null
    private var cachedBitmap2: android.graphics.Bitmap? = null
    private var lastPreviewConfig: top.maary.darkbag.models.EditConfig? = null
    private var lastCompositeBitmap: android.graphics.Bitmap? = null
    private var isLongPressing = false
    private var savedMatrix = android.graphics.Matrix()
    private var savedScale = 1f

    private lateinit var lutManager: top.maary.darkbag.utils.LutManager
    private val previewMutex = kotlinx.coroutines.sync.Mutex()
    private var previewJob: Job? = null
    private var lastPageIndex = -1
    private val pageChangeCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) {
            val indexChanged = lastPageIndex != -1 && lastPageIndex != position
            lastPageIndex = position

            if (isAdjusted && indexChanged) {
                resetAdjustments()
            } else if (!isAdjusted) {
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

                cachedBitmap1?.recycle()
                cachedBitmap1 = null
                cachedBitmap2?.recycle()
                cachedBitmap2 = null
                lastCompositeBitmap?.recycle()
                lastCompositeBitmap = null
            }
            updateControlsVisibility()
        }
    }

    private val backPressedCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (binding.lutListContainer.visibility == View.VISIBLE) {
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

        repository = ImageRepository(requireContext())
        lutManager = top.maary.darkbag.utils.LutManager(requireContext())
        setupEdgeToEdge()
        setupToolbar()

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

    private fun loadImages(targetUri: String? = args.initialUri, forceRefresh: Boolean = false) {
        binding.initialLoadingIndicator.visibility = View.VISIBLE
        binding.imagePager.visibility = View.INVISIBLE

        lifecycleScope.launch {
            var groups = repository.getGroupedImages(forceRefresh = forceRefresh).toMutableList()

            if (args.isStudioMode) {
                groups = repository.getStudioGroups(forceRefresh = forceRefresh).toMutableList()
            } else if (args.onlyDarkbag) {
                groups = groups.filter {
                    it.baseName.startsWith(top.maary.darkbag.utils.DarkbagIdentity.FILE_PREFIX)
                }.toMutableList()
            }

            // Handle virtual groups from external URIs or stitching
            if (targetUri != null && targetUri.contains("|")) {
                val parts = targetUri.split("|")
                val u1 = Uri.parse(parts[0])
                val u2 = Uri.parse(parts[1])
                val layout = if (parts.size > 2) parts[2] else "SBS"
                val time1 = top.maary.darkbag.utils.ImageUtils.getCaptureTime(requireContext(), u1)
                val time2 = top.maary.darkbag.utils.ImageUtils.getCaptureTime(requireContext(), u2)
                val virtualGroup = ImageGroup(
                    baseName = "Stitched_" + System.currentTimeMillis(),
                    jpgUri = null,
                    dngUri = null,
                    dngUri1 = u1,
                    dngUri2 = u2,
                    hfLayout = layout,
                    width = 0,
                    height = 0,
                    captureTime = maxOf(time1, time2).takeIf { it > 0 } ?: System.currentTimeMillis(),
                    captureTime1 = time1,
                    captureTime2 = time2
                )
                groups.add(0, virtualGroup)
            } else if (targetUri != null && groups.none { it.jpgUri?.toString() == targetUri || it.dngUri?.toString() == targetUri || it.dngUri1?.toString() == targetUri }) {
                // External or recently captured image not yet grouped
                val u = Uri.parse(targetUri)
                val isDng = targetUri.endsWith(".dng", ignoreCase = true) || context?.contentResolver?.getType(u) == "image/x-adobe-dng"

                val name = repository.resolveFilename(u) ?: u.lastPathSegment ?: "External"
                val baseName = top.maary.darkbag.utils.ImageUtils.getBaseName(name)

                // Read EXIF to check for half-frame layout and capture time
                var hfLayout: String? = null
                var captureTime = System.currentTimeMillis()
                var editConfig: top.maary.darkbag.models.EditConfig? = null

                try {
                    requireContext().contentResolver.openFileDescriptor(u, "r")?.use { pfd ->
                        val exif = androidx.exifinterface.media.ExifInterface(pfd.fileDescriptor)
                        val comment = exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_USER_COMMENT)
                        editConfig = top.maary.darkbag.utils.ImageUtils.parseUserComment(comment)
                        hfLayout = editConfig?.hfLayout

                        val dateStr = exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_DATETIME_ORIGINAL) ?: exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_DATETIME)
                        if (dateStr != null) {
                            val sdf = java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.US)
                            captureTime = sdf.parse(dateStr)?.time ?: captureTime
                        }
                    }
                } catch (e: Exception) {}

                // If half-frame, try to find RAW siblings
                var hf1: Uri? = null
                var hf2: Uri? = null
                if (hfLayout != null) {
                    val siblings = repository.findSiblingsForUri(u, baseName)
                    hf1 = siblings.first
                    hf2 = siblings.second
                }

                val virtualGroup = ImageGroup(
                    baseName = baseName,
                    jpgUri = if (isDng) null else u,
                    dngUri = if (isDng) u else null,
                    dngUri1 = hf1,
                    dngUri2 = hf2,
                    hfLayout = hfLayout,
                    captureTime = captureTime,
                    editConfig = editConfig
                )
                groups.add(0, virtualGroup)
            }

            if (groups.isEmpty()) {
                findNavController().navigateUp()
                return@launch
            }
            adapter = ImageViewerAdapter(groups, lifecycleScope, requireContext()).apply {
                onImageTapped = { toggleUi() }
                onZoomChanged = { isZoomed -> if (isZoomed) hideUi() else showUi() }
                onLongPressStarted = { handleLongPressStarted(it) }
                onLongPressEnded = { handleLongPressEnded(it) }
                previewProvider = { pos -> if (pos == binding.imagePager.currentItem) lastCompositeBitmap else null }
                setFormatSwitcherPersistentHidden(isAdjusted)
            }
            binding.imagePager.adapter = adapter

            val initialPos = if (targetUri?.contains("|") == true || (targetUri != null && !groups.any { it.jpgUri?.toString() == targetUri || it.dngUri?.toString() == targetUri || it.dngUri1?.toString() == targetUri })) {
                0
            } else {
                groups.indexOfFirst {
                    it.jpgUri?.toString() == targetUri ||
                    it.dngUri?.toString() == targetUri ||
                    it.dngUri1?.toString() == targetUri ||
                    it.dngUri2?.toString() == targetUri
                }
            }

            if (initialPos != -1) {
                lastPageIndex = initialPos
                binding.imagePager.setCurrentItem(initialPos, false)
            }

            setupActionButtons()
            updateControlsVisibility()

            if (targetUri?.contains("|") == true) {
                markAdjusted()
                applyEditPreview()
            }

            binding.imagePager.isUserInputEnabled = !isAdjusted

            binding.imagePager.visibility = View.VISIBLE
            binding.initialLoadingIndicator.visibility = View.GONE
        }
    }

    private fun handleBackgroundSaveEvent(event: ColorProcessor.BackgroundSaveEvent) {
        val groups = adapter.getGroups().toMutableList()
        val index = adapter.findGroupIndex(event.baseName)

        if (index != -1) {
            val oldGroup = groups[index]
            val newJpgUri = event.targetUri?.let { Uri.parse(it) } ?: oldGroup.jpgUri
            val newDngUri = event.dngPath?.let { Uri.parse(it) } ?: oldGroup.dngUri

            val newGroup = oldGroup.copy(
                jpgUri = newJpgUri,
                dngUri = newDngUri,
                lastModified = System.currentTimeMillis()
            )
            groups[index] = newGroup
            adapter.updateGroups(groups)

            if (index == binding.imagePager.currentItem) {
                updateControlsVisibility()
            }
        } else {
            // New image group saved (maybe from background processing of a very recent shot)
            // Trigger a full repository refresh to include the new item
            lifecycleScope.launch {
                repository.invalidateCache()
                val newGroups = repository.getGroupedImages(forceRefresh = true)
                adapter.updateGroups(newGroups)
            }
        }
    }

    private fun updateControlsVisibility() {
        if (!::adapter.isInitialized || adapter.itemCount == 0) return
        val currentGroup = adapter.getGroup(binding.imagePager.currentItem)
        val canEdit = currentGroup.dngUri != null || currentGroup.dngUri1 != null

        val visibility = if (canEdit && !isEditingAdjustments) View.VISIBLE else View.GONE
        binding.bottomLeftControls.visibility = visibility
        binding.bottomRightControls.visibility = visibility
        binding.fabAdjust.visibility = visibility

        if (canEdit && currentEditConfig == null) {
            prepareEditConfig(currentGroup)
        }

        if (currentGroup.isHalfFrame() && (currentGroup.captureTime1 == 0L || currentGroup.captureTime2 == 0L)) {
            lifecycleScope.launch(Dispatchers.IO) {
                val t1 = currentGroup.dngUri1?.let { top.maary.darkbag.utils.ImageUtils.getCaptureTime(requireContext(), it) } ?: 0L
                val t2 = currentGroup.dngUri2?.let { top.maary.darkbag.utils.ImageUtils.getCaptureTime(requireContext(), it) } ?: 0L
                if (t1 > 0 || t2 > 0) {
                    val updatedGroup = currentGroup.copy(
                        captureTime1 = if (t1 > 0) t1 else currentGroup.captureTime1,
                        captureTime2 = if (t2 > 0) t2 else currentGroup.captureTime2,
                        captureTime = maxOf(t1, t2).takeIf { it > 0 } ?: currentGroup.captureTime
                    )
                    withContext(Dispatchers.Main) {
                        if (binding.imagePager.currentItem == adapter.getGroups().indexOf(currentGroup)) {
                            adapter.updateGroupAt(binding.imagePager.currentItem, updatedGroup)
                        }
                    }
                }
            }
        }

        if (currentGroup.isHalfFrame()) {
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

    private fun prepareEditConfig(group: ImageGroup) {
        // Reset cached data to prevent cross-contamination between different image groups
        sourceDngBytes = null
        sourceDngBytes2 = null
        cachedBitmap1?.recycle()
        cachedBitmap1 = null
        cachedBitmap2?.recycle()
        cachedBitmap2 = null
        lastPreviewConfig = null
        selectedDngIndex = 0
        previewJob?.cancel()

        val isNewStitch = args.initialUri?.contains("|") == true && group.baseName.startsWith("Stitched_")

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
        }?.copy() ?: run {
            val prefs = requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
            top.maary.darkbag.models.EditConfig(
                adjustments = if (group.isHalfFrame()) listOf(top.maary.darkbag.models.BasicAdjustments(), top.maary.darkbag.models.BasicAdjustments()) else null,
                showTimestamp = if (isNewStitch) prefs.getBoolean(SettingsFragment.KEY_HALF_FRAME_DATE_STAMP, false) else false,
                flareType = if (isNewStitch) {
                    if (prefs.getBoolean(SettingsFragment.KEY_HALF_FRAME_LIGHT_LEAK, false)) 0 else -1
                } else -1
            )
        }

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

    private suspend fun ensureDngBytesLoaded() = previewMutex.withLock {
        ensureDngBytesLoadedInternal()
    }

    private suspend fun ensureDngBytesLoadedInternal() {
        if (sourceDngBytes != null) return
        val group = adapter.getGroup(binding.imagePager.currentItem)
        val dngUri1 = group.dngUri ?: group.dngUri1 ?: return
        val dngUri2 = group.dngUri2
        val context = context ?: return

        withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openFileDescriptor(dngUri1, "r")?.use { pfd ->
                    sourceDngBytes = java.io.FileInputStream(pfd.fileDescriptor).use { it.readBytes() }
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
                        repository.readDngBaselineExposure(dngUri1, false)?.let { configFromExif ->
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
                        if (adjs.getOrNull(0)?.digitalGain == 1.0f && adjs.getOrNull(0)?.exposure == 0f) {
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

    private fun setupActionButtons() {
        binding.btnShareMain.setOnClickListener {
            performShare()
        }
        binding.btnShareMenu.setOnClickListener {
            binding.btnShareMenu.isCheckable = true
            binding.btnShareMenu.isChecked = true
            val popup = PopupMenu(requireContext(), it)
            popup.menu.add(0, MENU_DETAILS, 0, "Details").apply {
                setIcon(R.drawable.ic_info)
            }
            popup.menu.add(0, MENU_DELETE, 0, "Delete").apply {
                setIcon(R.drawable.ic_delete)
            }

            forceShowIcons(popup)

            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_DETAILS -> showImageDetails()
                    MENU_DELETE -> {
                        val currentGroup = adapter.getGroup(binding.imagePager.currentItem)
                        showDeleteDialog(currentGroup)
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

            forceShowIcons(popup)

            popup.setOnMenuItemClickListener { item ->
                if (item.itemId == MENU_SAVE_AS) {
                    saveEdit(isReplacement = false)
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
            val current = currentEditConfig ?: return@setOnClickListener
            currentEditConfig = current.copy(showTimestamp = !current.showTimestamp)
            markAdjusted()
            updateEffectsButtons()
            applyEditPreview()
        }

        binding.btnFlare.setOnClickListener {
            val current = currentEditConfig ?: return@setOnClickListener
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

        binding.btnSwapFrames.setOnClickListener {
            val group = adapter.getGroup(binding.imagePager.currentItem)
            if (!group.isHalfFrame()) return@setOnClickListener

            previewJob?.cancel()
            previewJob = lifecycleScope.launch {
                showProgress(true)
                previewMutex.withLock {
                    ensureDngBytesLoadedInternal()
                    val current = currentEditConfig ?: return@withLock

                    // Swap bytes
                    val tempBytes = sourceDngBytes
                    sourceDngBytes = sourceDngBytes2
                    sourceDngBytes2 = tempBytes

                    // Swap cached bitmaps
                    val tempBitmap = cachedBitmap1
                    cachedBitmap1 = cachedBitmap2
                    cachedBitmap2 = tempBitmap

                    // Swap adjustments in last preview config to match swapped cached bitmaps
                    lastPreviewConfig = lastPreviewConfig?.let { lp ->
                        val lpAdjs = lp.adjustments?.toMutableList()
                        if (lpAdjs != null && lpAdjs.size >= 2) {
                            val t = lpAdjs[0]
                            lpAdjs[0] = lpAdjs[1]
                            lpAdjs[1] = t
                        }
                        lp.copy(adjustments = lpAdjs)
                    }

                    // Swap adjustments in config
                    val adjs = current.adjustments?.toMutableList() ?: mutableListOf(top.maary.darkbag.models.BasicAdjustments(), top.maary.darkbag.models.BasicAdjustments())
                    if (adjs.size >= 2) {
                        val tempAdj = adjs[0]
                        adjs[0] = adjs[1]
                        adjs[1] = tempAdj
                    }

                    // Swap URIs and Timestamps in ImageGroup
                    val updatedGroup = group.copy(
                        dngUri1 = group.dngUri2,
                        dngUri2 = group.dngUri1,
                        captureTime1 = group.captureTime2,
                        captureTime2 = group.captureTime1
                    )

                    currentEditConfig = current.copy(adjustments = adjs)
                    markAdjusted()

                    // INSTANT FAST PATH: Update UI using existing cached bitmaps
                    val fastComposite = createCompositeFromCache(currentEditConfig!!, updatedGroup, cachedBitmap1, cachedBitmap2)
                    if (fastComposite != null) {
                        val old = lastCompositeBitmap
                        lastCompositeBitmap = fastComposite
                        // Do NOT recycle 'old' yet, it might still be in use by the view until the next frame.
                        // ImageViewerAdapter.setBitmapAndRecyclePrevious handles this more safely.

                        val pos = binding.imagePager.currentItem
                        adapter.cancelLoadJob(pos, clearView = false)
                        adapter.updateGroupAt(pos, updatedGroup, payload = "SWAP")

                        val holder = (binding.imagePager.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView)
                            ?.findViewHolderForAdapterPosition(pos) as? ImageViewerAdapter.ViewHolder

                        holder?.let {
                            Glide.with(it.binding.imageView).clear(it.binding.imageView)
                            val oldManual = it.manualBitmap
                            if (oldManual != null && oldManual !== fastComposite && !oldManual.isRecycled) {
                                oldManual.recycle()
                            }
                            it.manualBitmap = fastComposite
                            it.binding.imageView.setImageBitmap(fastComposite)
                        }
                    }

                    try {
                        // Silent update in background for HQ/State consistency
                        applyEditPreviewInternal(currentEditConfig!!)
                    } finally {
                        updateSlidersInPanel()
                        showProgress(false)
                    }
                }
            }
        }

        binding.fabAdjust.setOnClickListener {
            showAdjustmentsBottomSheet()
            lifecycleScope.launch {
                showProgress(true)
                try {
                    ensureDngBytesLoaded()
                } finally {
                    showProgress(false)
                }
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
                    adjs[selectedDngIndex] = updateConfig(adjs[selectedDngIndex])
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

    private fun markAdjusted() {
        if (!isAdjusted) {
            isAdjusted = true
            if (::adapter.isInitialized) {
                adapter.setFormatSwitcherPersistentHidden(true)
                adapter.setRenderLocked(true)
            }
            updateSplitButtons()
            updateToolbarIcon()
            updateBackPressedCallbackState()
        }
    }

    private fun resetAdjustments() {
        isAdjusted = false
        exitEditMode(apply = false)
        binding.imagePager.isUserInputEnabled = true
        adapter.setFormatSwitcherPersistentHidden(false)
        adapter.setRenderLocked(false)
        sourceDngBytes = null
        sourceDngBytes2 = null
        cachedBitmap1?.recycle()
        cachedBitmap1 = null
        cachedBitmap2?.recycle()
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
        currentEditConfig?.let { config ->
            val lutName = if (config.lut == "None" || config.lut == null) "None" else config.lut.substringBeforeLast(".")
            binding.btnLogLut.text = "Log: ${config.log} / LUT: $lutName"
        }
    }

    private fun updateEffectsButtons() {
        val config = currentEditConfig ?: return
        binding.btnTimestamp.setIconTintResource(if (config.showTimestamp) R.color.vibrant_orange else android.R.color.white)
        binding.btnTimestamp.alpha = if (config.showTimestamp) 1.0f else 0.6f

        binding.btnFlare.setIconTintResource(if (config.flareType != -1) R.color.vibrant_pink else android.R.color.white)
        binding.btnFlare.alpha = if (config.flareType != -1) 1.0f else 0.6f

        val currentGroup = adapter.getGroup(binding.imagePager.currentItem)
        if (currentGroup.hfLayout == "TB") {
            binding.btnSwapFrames.setIconResource(R.drawable.ic_swap_vert)
        } else {
            binding.btnSwapFrames.setIconResource(R.drawable.ic_swap_horiz)
        }
    }

    private fun updateSelectionFeedback() {
        val currentGroup = adapter.getGroup(binding.imagePager.currentItem)
        val isTB = currentGroup.hfLayout == "TB"

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

    private fun enterEditMode() {
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
            config.adjustments?.getOrNull(selectedDngIndex) ?: top.maary.darkbag.models.BasicAdjustments()
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
        previewJob?.cancel()
        previewJob = lifecycleScope.launch {
            delay(150) // Debounce slider movements
            previewMutex.withLock {
                applyEditPreviewInternal(config)
            }
        }
    }

    private fun handleLongPressStarted(imageView: top.maary.darkbag.ui.ZoomableImageView) {
        if (!isAdjusted) return
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
    }

    private fun handleLongPressEnded(imageView: top.maary.darkbag.ui.ZoomableImageView) {
        if (!isAdjusted) return
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
    }

    private fun showProgress(visible: Boolean, forceGlobal: Boolean = false) {
        val currentIndex = binding.imagePager.currentItem
        val currentHolder = (binding.imagePager.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView)
            ?.findViewHolderForAdapterPosition(currentIndex) as? ImageViewerAdapter.ViewHolder

        if (visible) {
            if (isEditingAdjustments || forceGlobal) {
                binding.initialLoadingIndicator.visibility = View.VISIBLE
            } else {
                currentHolder?.binding?.loadingIndicator?.visibility = View.VISIBLE
            }
        } else {
            binding.initialLoadingIndicator.visibility = View.GONE
            currentHolder?.binding?.loadingIndicator?.visibility = View.GONE
        }
    }

    private fun createCompositeFromCache(
        config: top.maary.darkbag.models.EditConfig,
        group: ImageGroup,
        b1: android.graphics.Bitmap?,
        b2: android.graphics.Bitmap?
    ): android.graphics.Bitmap? {
        if (b1 == null && b2 == null) return null

        val layout = group.hfLayout ?: "SBS"
        val wantPortrait = layout != "TB"
        fun orient(b: android.graphics.Bitmap?): android.graphics.Bitmap? {
            if (b == null) return null
            val isPortrait = b.height >= b.width
            if (isPortrait == wantPortrait) return b
            val degrees = if (wantPortrait) 90f else 270f
            val matrix = android.graphics.Matrix().apply { postRotate(degrees) }
            return android.graphics.Bitmap.createBitmap(b, 0, 0, b.width, b.height, matrix, true)
        }

        var ob1 = orient(b1)
        var ob2 = orient(b2)

        // Scaling logic for mismatched resolutions
        if (ob1 != null && ob2 != null) {
            if (layout == "TB") {
                if (ob1.width != ob2.width) {
                    if (ob1.width > ob2.width) {
                        val scale = ob2.width.toFloat() / ob1.width
                        val scaled = android.graphics.Bitmap.createScaledBitmap(ob1, ob2.width, (ob1.height * scale).toInt(), true)
                        if (scaled != ob1 && scaled != b1) ob1.recycle()
                        ob1 = scaled
                    } else {
                        val scale = ob1.width.toFloat() / ob2.width
                        val scaled = android.graphics.Bitmap.createScaledBitmap(ob2, ob1.width, (ob2.height * scale).toInt(), true)
                        if (scaled != ob2 && scaled != b2) ob2.recycle()
                        ob2 = scaled
                    }
                }
            } else {
                if (ob1.height != ob2.height) {
                    if (ob1.height > ob2.height) {
                        val scale = ob2.height.toFloat() / ob1.height
                        val scaled = android.graphics.Bitmap.createScaledBitmap(ob1, (ob1.width * scale).toInt(), ob2.height, true)
                        if (scaled != ob1 && scaled != b1) ob1.recycle()
                        ob1 = scaled
                    } else {
                        val scale = ob1.height.toFloat() / ob2.height
                        val scaled = android.graphics.Bitmap.createScaledBitmap(ob2, (ob2.width * scale).toInt(), ob1.height, true)
                        if (scaled != ob2 && scaled != b2) ob2.recycle()
                        ob2 = scaled
                    }
                }
            }
        }

        val w1 = ob1?.width ?: ob2?.width ?: 0
        val h1 = ob1?.height ?: ob2?.height ?: 0
        val w2 = ob2?.width ?: w1
        val h2 = ob2?.height ?: h1

        val isSBS = layout != "TB"
        val gap = top.maary.darkbag.utils.HalfFrameUtils.calculateGap(maxOf(w1, h1)).toFloat()
        val resW = if (isSBS) (w1 + gap + w2).toInt() else maxOf(w1, w2)
        val resH = if (isSBS) maxOf(h1, h2) else (h1 + gap + h2).toInt()

        val composite = android.graphics.Bitmap.createBitmap(resW, resH, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(composite)
        canvas.drawColor(android.graphics.Color.BLACK)
        ob1?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        ob2?.let {
            if (isSBS) canvas.drawBitmap(it, w1 + gap, 0f, null)
            else canvas.drawBitmap(it, 0f, h1 + gap, null)
        }

        if (ob1 != b1) ob1?.recycle()
        if (ob2 != b2) ob2?.recycle()

        val finalComposite = top.maary.darkbag.utils.HalfFrameUtils.addEffects(
            composite,
            config.showTimestamp,
            config.flareType >= 0,
            group.hfLayout ?: "SBS",
            time1 = group.captureTime1.takeIf { it > 0 } ?: group.captureTime,
            time2 = group.captureTime2.takeIf { it > 0 } ?: group.captureTime,
            flareType = config.flareType
        )
        if (finalComposite != composite) {
            composite.recycle()
        }
        return finalComposite
    }

    private suspend fun applyEditPreviewInternal(config: top.maary.darkbag.models.EditConfig) = coroutineScope {
        val currentGroup = adapter.getGroup(binding.imagePager.currentItem)
        val dngUri1 = currentGroup.dngUri ?: currentGroup.dngUri1 ?: return@coroutineScope
        val dngUri2 = currentGroup.dngUri2

        showProgress(true)
        val currentIndex = binding.imagePager.currentItem
        val currentHolder = (binding.imagePager.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView)
            ?.findViewHolderForAdapterPosition(currentIndex) as? ImageViewerAdapter.ViewHolder
        currentHolder?.binding?.imageView?.invalidateOutline()

        try {
            ensureDngBytesLoadedInternal()
            ensureActive()
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

                    suspend fun processSingle(bytes: ByteArray?, uri: Uri, index: Int): android.graphics.Bitmap? {
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

                        var rotDegrees = when(orientation) {
                            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
                            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
                            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
                            else -> 0
                        }

                        val exifWidth = try {
                            context.contentResolver.openInputStream(uri)?.use { input ->
                                ExifInterface(input).getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, options.outWidth)
                            } ?: options.outWidth
                        } catch (e: Exception) { options.outWidth }
                        val exifHeight = try {
                            context.contentResolver.openInputStream(uri)?.use { input ->
                                ExifInterface(input).getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, options.outHeight)
                            } ?: options.outHeight
                        } catch (e: Exception) { options.outHeight }

                        if (currentGroup.isHalfFrame()) {
                            rotDegrees = getAdjustedRotationForHalfFrame(rotDegrees, exifWidth, exifHeight, currentGroup)
                        }

                        val fullW = if (rotDegrees == 90 || rotDegrees == 270) exifHeight / ds else exifWidth / ds
                        val fullH = if (rotDegrees == 90 || rotDegrees == 270) exifWidth / ds else exifHeight / ds
                        val bmpW = (fullW / config.zoomFactor).toInt()
                        val bmpH = (fullH / config.zoomFactor).toInt()
                        val previewBitmap = android.graphics.Bitmap.createBitmap(bmpW, bmpH, android.graphics.Bitmap.Config.ARGB_8888)

                        val adj = if (currentGroup.isHalfFrame()) config.adjustments?.get(index) ?: top.maary.darkbag.models.BasicAdjustments() else config.toBasic()

                        ensureActive()
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
                        compositeBitmap = processSingle(sourceDngBytes, dngUri1, 0)
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
                            cachedBitmap1?.recycle()
                            cachedBitmap1 = processSingle(sourceDngBytes, dngUri1, 0)
                        }
                        if (forceUpdate2 || cachedBitmap2 == null) {
                            cachedBitmap2?.recycle()
                            cachedBitmap2 = dngUri2?.let { processSingle(sourceDngBytes2, it, 1) }
                        }

                        val b1 = cachedBitmap1
                        val b2 = cachedBitmap2

                        if (b1 != null || b2 != null) {
                            coroutineContext.ensureActive()
                            compositeBitmap = createCompositeFromCache(config, currentGroup, b1, b2)
                            ensureActive()

                            if (isIndividual && compositeBitmap != null) {
                                val isSBS = currentGroup.hfLayout != "TB"
                                val gap = top.maary.darkbag.utils.HalfFrameUtils.calculateGap(maxOf(b1?.width ?: 0, b1?.height ?: 0)).toFloat()
                                val w1 = b1?.width ?: b2?.width ?: 0
                                val h1 = b1?.height ?: b2?.height ?: 0
                                val w2 = b2?.width ?: w1
                                val h2 = b2?.height ?: h1

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
                ensureActive()
                    if (lastCompositeBitmap != compositeBitmap) {
                        val old = lastCompositeBitmap
                        lastCompositeBitmap = compositeBitmap
                        // Do NOT recycle 'old' yet, it's safer to let the adapter handle it via setBitmapAndRecyclePrevious
                        // or clean up when exiting the fragment/switching pages.
                    }
                    if (isLongPressing) return@coroutineScope

                    val pos = binding.imagePager.currentItem
                    val holder = (binding.imagePager.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView)
                        ?.findViewHolderForAdapterPosition(pos) as? ImageViewerAdapter.ViewHolder
                    holder?.let {
                        adapter.cancelLoadJob(pos, clearView = false)
                        val oldManual = it.manualBitmap
                        if (oldManual != null && oldManual !== compositeBitmap && !oldManual.isRecycled) {
                            oldManual.recycle()
                        }
                        it.manualBitmap = compositeBitmap
                        it.binding.imageView.setImageBitmap(compositeBitmap)
                    }
                }
        } finally {
            showProgress(false)
        }
    }

    private fun top.maary.darkbag.models.EditConfig.toBasic() = top.maary.darkbag.models.BasicAdjustments(
        exposure, contrast, saturation, highlights, shadows, whites, blacks
    )

    private fun saveEdit(isReplacement: Boolean) {
        val config = currentEditConfig ?: return
        val currentGroup = adapter.getGroup(binding.imagePager.currentItem)
        val dngUri1 = currentGroup.dngUri ?: currentGroup.dngUri1 ?: return
        val dngUri2 = currentGroup.dngUri2

        // Force "Save as New" for external images
        val isExternal = !args.isStudioMode && !currentGroup.baseName.startsWith(top.maary.darkbag.utils.DarkbagIdentity.FILE_PREFIX)
        val actualIsReplacement = if (isExternal) false else isReplacement

        lifecycleScope.launch {
            showProgress(true, forceGlobal = true)

            // Promotion for Studio: if we are saving a stitch for the first time, rename DNGs
            var effectiveBaseName = currentGroup.baseName
            val (finalDng1, finalDng2) = if (args.isStudioMode && currentGroup.isHalfFrame() && dngUri2 != null && currentGroup.baseName.startsWith("Stitched_")) {
                val newBaseName = "STUDIO_GROUP_${System.currentTimeMillis()}"
                effectiveBaseName = newBaseName
                repository.promoteToGroup(dngUri1, dngUri2, newBaseName) ?: (dngUri1 to dngUri2)
            } else {
                dngUri1 to (dngUri2 ?: dngUri1)
            }

            ensureDngBytesLoaded()
            withContext(Dispatchers.IO) {
                try {
                    val context = requireContext()
                    val logIndex = SettingsFragment.LOG_CURVES.indexOf(config.log)
                    val lutPath = if (config.lut != null && config.lut != "None") {
                        java.io.File(lutManager.lutDir, config.lut).absolutePath
                    } else null

                    suspend fun processFull(bytes: ByteArray?, uri: Uri, index: Int, targetJpgPath: String? = null): android.graphics.Bitmap? {
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

                        var rotDegrees = when(orientation) {
                            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
                            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
                            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
                            else -> 0
                        }

                        val exifWidth = try {
                            context.contentResolver.openInputStream(uri)?.use { input ->
                                ExifInterface(input).getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, options.outWidth)
                            } ?: options.outWidth
                        } catch (e: Exception) { options.outWidth }
                        val exifHeight = try {
                            context.contentResolver.openInputStream(uri)?.use { input ->
                                ExifInterface(input).getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, options.outHeight)
                            } ?: options.outHeight
                        } catch (e: Exception) { options.outHeight }

                        if (currentGroup.isHalfFrame()) {
                            rotDegrees = getAdjustedRotationForHalfFrame(rotDegrees, exifWidth, exifHeight, currentGroup)
                        }

                        val adj = if (currentGroup.isHalfFrame()) config.adjustments?.get(index) ?: top.maary.darkbag.models.BasicAdjustments() else config.toBasic()

                        if (targetJpgPath != null) {
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
                                outputJpgPath = targetJpgPath,
                                useGpu = false,
                                orientation = rotDegrees,
                                mirror = false,
                                outputBitmap = null,
                                downsampleFactor = 1,
                                zoomFactor = config.zoomFactor
                            )
                            return null
                        } else {
                            val fullW = if (rotDegrees == 90 || rotDegrees == 270) exifHeight else exifWidth
                            val fullH = if (rotDegrees == 90 || rotDegrees == 270) exifWidth else exifHeight
                            val bmpW = (fullW / config.zoomFactor).toInt()
                            val bmpH = (fullH / config.zoomFactor).toInt()
                            val previewBitmap = android.graphics.Bitmap.createBitmap(bmpW, bmpH, android.graphics.Bitmap.Config.ARGB_8888)

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
                                useGpu = false,
                                orientation = rotDegrees,
                                mirror = false,
                                outputBitmap = previewBitmap,
                                downsampleFactor = 1,
                                zoomFactor = config.zoomFactor
                            )
                            return previewBitmap
                        }
                    }

                    var tempJpgPath: String? = null
                    val finalBitmap: android.graphics.Bitmap? = if (!currentGroup.isHalfFrame()) {
                        val tempFile = java.io.File(context.cacheDir, "studio_edit_${System.currentTimeMillis()}.jpg")
                        tempJpgPath = tempFile.absolutePath
                        processFull(sourceDngBytes, dngUri1, 0, tempJpgPath)
                        null
                    } else {
                        val b1 = processFull(sourceDngBytes, dngUri1, 0)
                        val b2 = dngUri2?.let { processFull(sourceDngBytes2, it, 1) }

                        if (b1 != null || b2 != null) {
                            val isSBS = currentGroup.hfLayout != "TB"
                            val gap = top.maary.darkbag.utils.HalfFrameUtils.calculateGap(maxOf(b1?.width ?: 0, b1?.height ?: 0)).toFloat()
                            val w1 = b1?.width ?: b2?.width ?: 0
                            val h1 = b1?.height ?: b2?.height ?: 0
                            val w2 = b2?.width ?: w1
                            val h2 = b2?.height ?: h1
                            val resW = if (isSBS) (w1 + gap + w2).toInt() else maxOf(w1, w2)
                            val resH = if (isSBS) maxOf(h1, h2) else (h1 + gap + h2).toInt()

                            val composite = android.graphics.Bitmap.createBitmap(resW, resH, android.graphics.Bitmap.Config.ARGB_8888)
                            val canvas = android.graphics.Canvas(composite)
                            canvas.drawColor(android.graphics.Color.BLACK)
                            b1?.let { canvas.drawBitmap(it, 0f, 0f, null) }
                            b2?.let {
                                if (isSBS) canvas.drawBitmap(it, w1 + gap, 0f, null)
                                else canvas.drawBitmap(it, 0f, h1 + gap, null)
                            }
                            val finalComposite = top.maary.darkbag.utils.HalfFrameUtils.addEffects(
                                composite,
                                config.showTimestamp,
                                config.flareType >= 0,
                                currentGroup.hfLayout ?: "SBS",
                                time1 = currentGroup.captureTime1.takeIf { it > 0 } ?: currentGroup.captureTime,
                                time2 = currentGroup.captureTime2.takeIf { it > 0 } ?: currentGroup.captureTime,
                                flareType = config.flareType
                            )
                            if (finalComposite != composite) {
                                composite.recycle()
                            }
                            b1?.recycle()
                            b2?.recycle()
                            finalComposite
                        } else null
                    }

                    if (finalBitmap != null || tempJpgPath != null) {
                        var baseName = if (actualIsReplacement) currentGroup.baseName else "${currentGroup.baseName}_edited_${System.currentTimeMillis()}"

                        // Use the new baseName if we promoted the group
                        if (args.isStudioMode && currentGroup.isHalfFrame() && currentGroup.baseName.startsWith("Stitched_")) {
                             baseName = top.maary.darkbag.utils.ImageUtils.getBaseName(repository.resolveFilename(finalDng1) ?: "")
                        }

                        if (isExternal && !baseName.startsWith(top.maary.darkbag.utils.DarkbagIdentity.FILE_PREFIX)) {
                            baseName = top.maary.darkbag.utils.DarkbagIdentity.FILE_PREFIX + baseName
                        }
                        val targetUri = if (actualIsReplacement) currentGroup.jpgUri else null

                        val prefs = context.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
                        val jpgFolderUri = prefs.getString(SettingsFragment.KEY_JPG_STORAGE_URI, null)
                        val exportFolderUri = prefs.getString(SettingsFragment.KEY_EXPORT_STORAGE_URI, null)

                        // Use export folder for external images or if specifically set
                        val finalFolderUri = if (isExternal) {
                            exportFolderUri ?: jpgFolderUri
                        } else {
                             if (actualIsReplacement) null else (exportFolderUri ?: jpgFolderUri)
                        }

                        top.maary.darkbag.utils.ImageSaver.saveProcessedImage(
                            context = context,
                            inputBitmap = finalBitmap,
                            bmpPath = tempJpgPath,
                            rotationDegrees = 0,
                            zoomFactor = 1.0f,
                            baseName = baseName,
                            linearDngPath = null,
                            saveJpg = true,
                            saveRaw = false,
                            targetUri = targetUri,
                            jpgFolderUri = if (actualIsReplacement) null else finalFolderUri,
                                editConfig = config,
                                isAlreadyStitched = currentGroup.isHalfFrame(),
                                sourceDngUri = finalDng1,
                                isExternal = isExternal
                        )
                    }
                } catch (e: Exception) {
                    android.util.Log.e("ImageViewerFragment", "Failed to save edit", e)
                }
            }

            showProgress(false, forceGlobal = true)

            repository.invalidateCache()
            val updatedGroups = if (args.isStudioMode) repository.getStudioGroups(forceRefresh = true) else repository.getGroupedImages(forceRefresh = true)
            if (updatedGroups.isNotEmpty()) {
                // Determine which group to navigate to
                val targetBaseName = if (actualIsReplacement) {
                    effectiveBaseName
                } else {
                    // For "Save As", the new file is usually the most recent
                    updatedGroups.first().baseName
                }

                val newPos = updatedGroups.indexOfFirst { it.baseName == targetBaseName }.coerceAtLeast(0)

                isAdjusted = false
                isEditingAdjustments = false
            adapter = ImageViewerAdapter(updatedGroups, lifecycleScope, requireContext()).apply {
                    onImageTapped = { toggleUi() }
                    onZoomChanged = { isZoomed -> if (isZoomed) hideUi() else showUi() }
                    onLongPressStarted = { handleLongPressStarted(it) }
                    onLongPressEnded = { handleLongPressEnded(it) }
                    previewProvider = { pos -> if (pos == binding.imagePager.currentItem) lastCompositeBitmap else null }
                }
                binding.imagePager.adapter = adapter
                binding.imagePager.setCurrentItem(newPos, false)
                updateControlsVisibility()
            }
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

        val uri = when (selectedFormat) {
            "JPG" -> currentGroup.jpgUri
            "DNG" -> currentGroup.dngUri ?: currentGroup.dngUri1 ?: currentGroup.dngUri2
            else -> currentGroup.jpgUri ?: currentGroup.dngUri
        } ?: return

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
        if (args.isStudioMode) {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle("Remove from Studio")
                .setMessage("Are you sure you want to remove this item from Studio? The original RAW file will be deleted from internal storage, but any exported JPGs will remain in your gallery.")
                .setPositiveButton("Delete") { _, _ -> deleteImage(group, true) }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        val options = arrayOf("Delete this format only", "Delete entire group")
        var checkedItem = 1
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("Delete Image")
            .setSingleChoiceItems(options, checkedItem) { _, which ->
                checkedItem = which
            }
            .setPositiveButton("Delete") { _, _ ->
                deleteImage(group, checkedItem == 1)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteImage(group: ImageGroup, deleteGroup: Boolean) {
        val context = context ?: return
        lifecycleScope.launch {
            var nextTargetUri: String? = null
            val currentIndex = binding.imagePager.currentItem

            if (args.isStudioMode) {
                repository.deleteStudioGroup(group)
            } else if (deleteGroup) {
                group.jpgUri?.let { context.contentResolver.delete(it, null, null) }
                group.dngUri?.let { context.contentResolver.delete(it, null, null) }
                group.dngUri1?.let { context.contentResolver.delete(it, null, null) }
                group.dngUri2?.let { context.contentResolver.delete(it, null, null) }
            } else {
                val selectedFormat = adapter.getSelectedFormat(binding.imagePager.currentItem)
                if (selectedFormat == "DNG" && group.isHalfFrame()) {
                     group.dngUri1?.let { context.contentResolver.delete(it, null, null) }
                     group.dngUri2?.let { context.contentResolver.delete(it, null, null) }
                } else {
                    val currentUri = when (selectedFormat) {
                        "JPG" -> group.jpgUri
                        "DNG" -> group.dngUri ?: group.dngUri1 ?: group.dngUri2
                        else -> group.jpgUri ?: group.dngUri ?: group.dngUri1 ?: group.dngUri2
                    }
                    currentUri?.let { context.contentResolver.delete(it, null, null) }
                }
            }

            repository.invalidateCache()
            val remainingGroups = if (args.isStudioMode) repository.getStudioGroups(forceRefresh = true) else repository.getGroupedImages(forceRefresh = true)
            val remainingGroup = remainingGroups.find { it.baseName == group.baseName }

            nextTargetUri = if (remainingGroup != null) {
                (remainingGroup.jpgUri ?: remainingGroup.dngUri ?: remainingGroup.dngUri1)?.toString()
            } else {
                if (adapter.itemCount > 1) {
                    val nextIndex = if (currentIndex < adapter.itemCount - 1) currentIndex + 1 else currentIndex - 1
                    val nextGroup = adapter.getGroup(nextIndex)
                    (nextGroup.jpgUri ?: nextGroup.dngUri ?: nextGroup.dngUri1)?.toString()
                } else null
            }

            if (nextTargetUri == null) {
                findNavController().navigateUp()
            } else {
                loadImages(nextTargetUri, forceRefresh = true)
            }
        }
    }

    private fun setupToolbar() {
        binding.btnNavigation.setOnClickListener {
            if (binding.lutListContainer.visibility == View.VISIBLE) {
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

    private fun toggleUi() {
        if (isEditingAdjustments) return
        if (isUiVisible) hideUi() else showUi()
    }

    private fun showUi() {
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

    private fun hideUi() {
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
                bottomMargin = systemBars.bottom + marginMedium
                rightMargin = systemBars.right + marginMedium
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
        val isTB = currentGroup?.hfLayout == "TB"

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
        backPressedCallback.isEnabled = isAdjusted || isEditingAdjustments || binding.lutListContainer.visibility == View.VISIBLE
    }

    private fun showDiscardChangesDialog() {
        if (isEditingAdjustments) {
            exitEditMode(apply = false)
            return
        }

        val currentGroup = adapter.getGroup(binding.imagePager.currentItem)
        val isVirtual = currentGroup.baseName.startsWith("Stitched_") || (args.isStudioMode && isAdjusted)

        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (isVirtual) "Discard Changes?" else getString(R.string.discard_changes_title))
            .setMessage(if (isVirtual) "Are you sure you want to discard your edits and go back?" else getString(R.string.discard_changes_message))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.discard) { _, _ ->
                if (isVirtual) {
                    findNavController().navigateUp()
                } else {
                    resetAdjustments()
                }
            }
            .show()
    }

    private fun getAdjustedRotationForHalfFrame(
        currentRotation: Int,
        imageWidth: Int,
        imageHeight: Int,
        group: ImageGroup
    ): Int {
        val currentWidth = if (currentRotation == 90 || currentRotation == 270) imageHeight else imageWidth
        val currentHeight = if (currentRotation == 90 || currentRotation == 270) imageWidth else imageHeight
        val isPortrait = currentHeight >= currentWidth
        val wantPortrait = group.hfLayout != "TB"
        return if (isPortrait != wantPortrait) {
            (currentRotation + (if (wantPortrait) 90 else 270)) % 360
        } else {
            currentRotation
        }
    }

    private fun forceShowIcons(popup: PopupMenu) {
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

    override fun onDestroyView() {
        binding.imagePager.unregisterOnPageChangeCallback(pageChangeCallback)
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val MENU_DETAILS = 1
        private const val MENU_DELETE = 2
        private const val MENU_SAVE_AS = 3

        private const val EXPOSURE_MIN = -4f
        private const val EXPOSURE_MAX = 4f
        private const val ADJUSTMENT_MIN = -1f
        private const val ADJUSTMENT_MAX = 1f
    }
}
