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
import android.net.Uri
import android.view.MenuItem
import androidx.appcompat.widget.PopupMenu
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.viewpager2.widget.ViewPager2
import kotlinx.coroutines.*
import top.maary.darkbag.R
import top.maary.darkbag.databinding.FragmentImageViewerBinding
import top.maary.darkbag.models.ImageGroup
import top.maary.darkbag.repository.ImageRepository

class ImageViewerFragment : Fragment() {

    private var _binding: FragmentImageViewerBinding? = null
    private var isUiVisible = true
    private val binding get() = _binding!!
    private val args: ImageViewerFragmentArgs by navArgs()
    private lateinit var repository: ImageRepository
    private lateinit var adapter: ImageViewerAdapter

    private var isAdjusted = false
    private var isIndividualEditMode = false
    private var currentEditConfig: top.maary.darkbag.models.EditConfig? = null
    private var selectedDngIndex = 0 // 0 or 1 for half-frame
    private var sourceDngBytes: ByteArray? = null
    private var sourceDngBytes2: ByteArray? = null
    private var cachedBitmap1: android.graphics.Bitmap? = null
    private var cachedBitmap2: android.graphics.Bitmap? = null
    private var lastPreviewConfig: top.maary.darkbag.models.EditConfig? = null
    private var activeAdjustmentBinding: top.maary.darkbag.databinding.BottomSheetEditAdjustmentsBinding? = null

    private lateinit var lutManager: top.maary.darkbag.utils.LutManager
    private var previewJob: Job? = null

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

        childFragmentManager.setFragmentResultListener(HalfFrameShareSheet.REQUEST_KEY, viewLifecycleOwner) { _, bundle ->
            val uris = bundle.getParcelableArrayList<android.net.Uri>(HalfFrameShareSheet.BUNDLE_KEY_URIS)
            if (uris != null) {
                shareImages(uris)
            }
        }

        loadImages()
    }

    private fun loadImages(targetUri: String? = args.initialUri) {
        lifecycleScope.launch {
            val groups = repository.getGroupedImages()
            if (groups.isEmpty()) {
                findNavController().navigateUp()
                return@launch
            }
            adapter = ImageViewerAdapter(groups, lifecycleScope).apply {
                onImageTapped = { toggleUi() }
                onZoomChanged = { isZoomed -> if (isZoomed) hideUi() else showUi() }
            }
            binding.imagePager.adapter = adapter

            val initialPos = groups.indexOfFirst {
                it.jpgUri?.toString() == targetUri ||
                it.dngUri?.toString() == targetUri ||
                it.dngUri1?.toString() == targetUri ||
                it.dngUri2?.toString() == targetUri ||
                it.tiffUri?.toString() == targetUri
            }
            if (initialPos != -1) {
                binding.imagePager.setCurrentItem(initialPos, false)
            }

            setupActionButtons()
            updateControlsVisibility()
        }

        binding.imagePager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                if (isAdjusted) {
                    resetAdjustments()
                } else {
                    currentEditConfig = null
                }
                updateControlsVisibility()
            }
        })
    }

    private fun updateControlsVisibility() {
        if (!::adapter.isInitialized || adapter.itemCount == 0) return
        val currentGroup = adapter.getGroup(binding.imagePager.currentItem)
        val canEdit = currentGroup.dngUri != null || currentGroup.dngUri1 != null

        val visibility = if (canEdit) View.VISIBLE else View.GONE
        binding.bottomLeftControls.visibility = visibility
        binding.fabAdjust.visibility = visibility

        if (canEdit && currentEditConfig == null) {
            prepareEditConfig(currentGroup)
        }

        if (currentGroup.isHalfFrame()) {
            binding.hfExtraControls.visibility = View.VISIBLE
            updateEffectsButtons()
        } else {
            binding.hfExtraControls.visibility = View.GONE
        }

        updateSplitButtons()
        updateToolbarIcon()
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
        updateEditUi()
        // DNG bytes and deep EXIF will be loaded on-demand when entering edit flow
    }

    private suspend fun ensureDngBytesLoaded() {
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
        if (isAdjusted) {
            binding.splitShare.visibility = View.GONE
            binding.splitSave.visibility = View.VISIBLE
        } else {
            binding.splitShare.visibility = View.VISIBLE
            binding.splitSave.visibility = View.GONE
        }
    }

    private fun updateToolbarIcon() {
        if (isAdjusted) {
            binding.toolbar.setNavigationIcon(R.drawable.ic_close)
        } else {
            binding.toolbar.setNavigationIcon(R.drawable.ic_back)
        }
        binding.toolbar.navigationIcon?.setTint(android.graphics.Color.WHITE)
    }

    private fun setupActionButtons() {
        binding.btnShareMain.setOnClickListener {
            performShare()
        }
        binding.btnShareMenu.setOnClickListener {
            val popup = PopupMenu(requireContext(), it)
            popup.menu.add(0, 1, 0, "Delete")
            popup.setOnMenuItemClickListener { item ->
                if (item.itemId == 1) {
                    val currentGroup = adapter.getGroup(binding.imagePager.currentItem)
                    showDeleteDialog(currentGroup)
                }
                true
            }
            popup.show()
        }

        binding.btnSaveMain.setOnClickListener {
            saveEdit(isReplacement = true)
        }
        binding.btnSaveMenu.setOnClickListener {
            val popup = PopupMenu(requireContext(), it)
            popup.menu.add(0, 1, 0, "Save as new file")
            popup.setOnMenuItemClickListener { item ->
                if (item.itemId == 1) {
                    saveEdit(isReplacement = false)
                }
                true
            }
            popup.show()
        }

        binding.btnLogLut.setOnClickListener {
            showLutMenu()
        }

        binding.touchOverlay.setOnClickListener {
            binding.lutListContainer.visibility = View.GONE
            binding.touchOverlay.visibility = View.GONE
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
                    -1 -> 0
                    0 -> 1
                    1 -> 2
                    2 -> -1
                    else -> -1
                }
                currentEditConfig = current.copy(flareType = nextFlare)
                markAdjusted()
                updateEffectsButtons()
                applyEditPreview()
            }
        }

        binding.fabAdjust.setOnClickListener {
            lifecycleScope.launch {
                ensureDngBytesLoaded()
                showAdjustmentsBottomSheet()
            }
        }

        binding.hfSelection1.setOnClickListener {
            if (selectedDngIndex != 0) {
                selectedDngIndex = 0
                updateSelectionFeedback()
                activeAdjustmentBinding?.let { updateSlidersInSheet(it) }
            }
        }

        binding.hfSelection2.setOnClickListener {
            if (selectedDngIndex != 1) {
                selectedDngIndex = 1
                updateSelectionFeedback()
                activeAdjustmentBinding?.let { updateSlidersInSheet(it) }
            }
        }

    }

    private fun markAdjusted() {
        if (!isAdjusted) {
            isAdjusted = true
            updateSplitButtons()
            updateToolbarIcon()
        }
    }

    private fun resetAdjustments() {
        isAdjusted = false
        isIndividualEditMode = false
        sourceDngBytes = null
        sourceDngBytes2 = null
        cachedBitmap1?.recycle()
        cachedBitmap1 = null
        cachedBitmap2?.recycle()
        cachedBitmap2 = null
        lastPreviewConfig = null
        previewJob?.cancel()
        currentEditConfig = null

        binding.hfSelection1.visibility = View.GONE
        binding.hfSelection2.visibility = View.GONE
        binding.lutListContainer.visibility = View.GONE

        val currentIndex = binding.imagePager.currentItem
        adapter.notifyItemChanged(currentIndex)

        val currentGroup = adapter.getGroup(currentIndex)
        prepareEditConfig(currentGroup)
        updateSplitButtons()
        updateToolbarIcon()
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
                "TIFF" -> currentGroup.tiffUri
                "DNG" -> currentGroup.dngUri ?: currentGroup.dngUri1 ?: currentGroup.dngUri2
                else -> currentGroup.jpgUri ?: currentGroup.dngUri ?: currentGroup.dngUri1 ?: currentGroup.dngUri2 ?: currentGroup.tiffUri
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
    }

    private fun updateSelectionFeedback() {
        val currentGroup = adapter.getGroup(binding.imagePager.currentItem)
        val isTB = currentGroup.hfLayout == "TB"

        val dimColor = 0x66000000.toInt()
        val activeColor = 0x00000000.toInt()

        if (isIndividualEditMode) {
            binding.hfSelection1.visibility = View.VISIBLE
            binding.hfSelection2.visibility = View.VISIBLE
            val dividerLp = binding.hfSelectionDivider.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
            dividerLp.orientation = if (isTB) androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.HORIZONTAL else androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.VERTICAL
            binding.hfSelectionDivider.layoutParams = dividerLp
        } else {
            binding.hfSelection1.visibility = View.GONE
            binding.hfSelection2.visibility = View.GONE
        }

        binding.hfSelection1.setBackgroundColor(if (isIndividualEditMode && selectedDngIndex == 0) activeColor else if (isIndividualEditMode) dimColor else activeColor)
        binding.hfSelection2.setBackgroundColor(if (isIndividualEditMode && selectedDngIndex == 1) activeColor else if (isIndividualEditMode) dimColor else activeColor)

        val lp1 = binding.hfSelection1.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
        val lp2 = binding.hfSelection2.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams

        if (isTB) {
            lp1.bottomToBottom = -1
            lp1.bottomToTop = R.id.hf_selection_divider
            lp1.endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            lp1.endToStart = -1

            lp2.topToTop = -1
            lp2.topToBottom = R.id.hf_selection_divider
            lp2.startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            lp2.startToEnd = -1
        } else {
            lp1.bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            lp1.bottomToTop = -1
            lp1.endToEnd = -1
            lp1.endToStart = R.id.hf_selection_divider

            lp2.topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            lp2.topToBottom = -1
            lp2.startToStart = -1
            lp2.startToEnd = R.id.hf_selection_divider
        }
        binding.hfSelection1.layoutParams = lp1
        binding.hfSelection2.layoutParams = lp2
    }

    private fun showLutMenu() {
        if (activeAdjustmentBinding != null) return

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
                    }
                }
            }
            override fun getItemCount() = items.size
        }
    }

    private fun showAdjustmentsBottomSheet() {
        val currentGroup = adapter.getGroup(binding.imagePager.currentItem)
        if (currentGroup.isHalfFrame() && !isIndividualEditMode) {
            isIndividualEditMode = true
            updateSelectionFeedback()
        }

        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(requireContext())
        val sheetBinding = top.maary.darkbag.databinding.BottomSheetEditAdjustmentsBinding.inflate(layoutInflater)
        activeAdjustmentBinding = sheetBinding
        dialog.setContentView(sheetBinding.root)

        val config = currentEditConfig ?: return

        if (currentGroup.isHalfFrame()) {
            sheetBinding.editPreviewCard.visibility = View.VISIBLE
            sheetBinding.groupFrameSelection.visibility = View.VISIBLE
            sheetBinding.groupFrameSelection.check(if (selectedDngIndex == 0) R.id.btn_select_frame1 else R.id.btn_select_frame2)

            sheetBinding.groupFrameSelection.addOnButtonCheckedListener { _, checkedId, isChecked ->
                if (isChecked) {
                    selectedDngIndex = if (checkedId == R.id.btn_select_frame1) 0 else 1
                    updateSelectionFeedback()
                    updateSlidersInSheet(sheetBinding)
                    applyEditPreview()
                }
            }
        }

        updateSlidersInSheet(sheetBinding)
        applyEditPreview()

        sheetBinding.editTabs.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab?) {
                sheetBinding.layoutLight.visibility = if (tab?.position == 0) View.VISIBLE else View.GONE
                sheetBinding.layoutColor.visibility = if (tab?.position == 1) View.VISIBLE else View.GONE
            }
            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
        })

        val changeListener = com.google.android.material.slider.Slider.OnChangeListener { slider, value, fromUser ->
            if (slider.id == R.id.slider_exposure) {
                sheetBinding.tvExposureValue.text = String.format("%.2f EV", value)
            }

            if (fromUser) {
                val current = currentEditConfig ?: return@OnChangeListener
                markAdjusted()
                if (currentGroup.isHalfFrame()) {
                    val adjs = current.adjustments?.toMutableList() ?: mutableListOf(top.maary.darkbag.models.BasicAdjustments(), top.maary.darkbag.models.BasicAdjustments())
                    val old = adjs[selectedDngIndex]
                    adjs[selectedDngIndex] = when (slider.id) {
                        R.id.slider_exposure -> old.copy(exposure = value)
                        R.id.slider_contrast -> old.copy(contrast = value)
                        R.id.slider_saturation -> old.copy(saturation = value)
                        R.id.slider_highlights -> old.copy(highlights = value)
                        R.id.slider_shadows -> old.copy(shadows = value)
                        R.id.slider_whites -> old.copy(whites = value)
                        R.id.slider_blacks -> old.copy(blacks = value)
                        else -> old
                    }
                    currentEditConfig = current.copy(adjustments = adjs)
                } else {
                    currentEditConfig = when (slider.id) {
                        R.id.slider_exposure -> currentEditConfig?.copy(exposure = value)
                        R.id.slider_contrast -> currentEditConfig?.copy(contrast = value)
                        R.id.slider_saturation -> currentEditConfig?.copy(saturation = value)
                        R.id.slider_highlights -> currentEditConfig?.copy(highlights = value)
                        R.id.slider_shadows -> currentEditConfig?.copy(shadows = value)
                        R.id.slider_whites -> currentEditConfig?.copy(whites = value)
                        R.id.slider_blacks -> currentEditConfig?.copy(blacks = value)
                        else -> currentEditConfig
                    }
                }
                applyEditPreview()
            }
        }

        sheetBinding.sliderExposure.addOnChangeListener(changeListener)
        sheetBinding.sliderContrast.addOnChangeListener(changeListener)
        sheetBinding.sliderSaturation.addOnChangeListener(changeListener)
        sheetBinding.sliderHighlights.addOnChangeListener(changeListener)
        sheetBinding.sliderShadows.addOnChangeListener(changeListener)
        sheetBinding.sliderWhites.addOnChangeListener(changeListener)
        sheetBinding.sliderBlacks.addOnChangeListener(changeListener)

        dialog.setOnDismissListener {
            isIndividualEditMode = false
            activeAdjustmentBinding = null
            updateSelectionFeedback()
            applyEditPreview()
        }
        dialog.show()
    }

    private fun updateSlidersInSheet(sheetBinding: top.maary.darkbag.databinding.BottomSheetEditAdjustmentsBinding) {
        val config = currentEditConfig ?: return
        val currentGroup = adapter.getGroup(binding.imagePager.currentItem)

        val target = if (currentGroup.isHalfFrame()) {
            config.adjustments?.getOrNull(selectedDngIndex) ?: top.maary.darkbag.models.BasicAdjustments()
        } else {
            top.maary.darkbag.models.BasicAdjustments(
                exposure = config.exposure,
                contrast = config.contrast,
                saturation = config.saturation,
                highlights = config.highlights,
                shadows = config.shadows,
                whites = config.whites,
                blacks = config.blacks
            )
        }
        sheetBinding.sliderExposure.value = target.exposure
        sheetBinding.tvExposureValue.text = String.format("%.2f EV", target.exposure)
        sheetBinding.sliderContrast.value = target.contrast
        sheetBinding.sliderSaturation.value = target.saturation
        sheetBinding.sliderHighlights.value = target.highlights
        sheetBinding.sliderShadows.value = target.shadows
        sheetBinding.sliderWhites.value = target.whites
        sheetBinding.sliderBlacks.value = target.blacks
    }

    private fun applyEditPreview() {
        val config = currentEditConfig ?: return
        lifecycleScope.launch {
            ensureDngBytesLoaded()
            applyEditPreviewInternal(config)
        }
    }

    private fun applyEditPreviewInternal(config: top.maary.darkbag.models.EditConfig) {
        val currentGroup = adapter.getGroup(binding.imagePager.currentItem)
        val dngUri1 = currentGroup.dngUri ?: currentGroup.dngUri1 ?: return
        val dngUri2 = currentGroup.dngUri2

        previewJob?.cancel()
        previewJob = lifecycleScope.launch {
            delay(150)
            val processOnlySelected = isIndividualEditMode && currentGroup.isHalfFrame()
            val bitmap = withContext(Dispatchers.IO) {
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

                        val bmpW = if (rotDegrees == 90 || rotDegrees == 270) options.outHeight / ds else options.outWidth / ds
                        val bmpH = if (rotDegrees == 90 || rotDegrees == 270) options.outWidth / ds else options.outHeight / ds
                        val previewBitmap = android.graphics.Bitmap.createBitmap(bmpW, bmpH, android.graphics.Bitmap.Config.ARGB_8888)

                        val adj = if (currentGroup.isHalfFrame()) config.adjustments?.get(index) ?: top.maary.darkbag.models.BasicAdjustments() else config.toBasic()

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
                            outputTiffPath = null,
                            outputJpgPath = null,
                            useGpu = false,
                            orientation = rotDegrees,
                            mirror = false,
                            outputBitmap = previewBitmap,
                            downsampleFactor = ds
                        )
                        return previewBitmap
                    }

                    if (!currentGroup.isHalfFrame()) {
                        processSingle(sourceDngBytes, dngUri1, 0)
                    } else if (processOnlySelected) {
                         if (selectedDngIndex == 0) {
                             if (lastPreviewConfig?.adjustments?.getOrNull(0) != config.adjustments?.getOrNull(0) ||
                                 lastPreviewConfig?.log != config.log || lastPreviewConfig?.lut != config.lut || cachedBitmap1 == null) {
                                 cachedBitmap1?.recycle()
                                 cachedBitmap1 = processSingle(sourceDngBytes, dngUri1, 0)
                             }
                             cachedBitmap1
                         } else {
                             if (lastPreviewConfig?.adjustments?.getOrNull(1) != config.adjustments?.getOrNull(1) ||
                                 lastPreviewConfig?.log != config.log || lastPreviewConfig?.lut != config.lut || cachedBitmap2 == null) {
                                 cachedBitmap2?.recycle()
                                 cachedBitmap2 = dngUri2?.let { processSingle(sourceDngBytes2, it, 1) }
                             }
                             cachedBitmap2
                         }
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
                                time1 = currentGroup.captureTime,
                                time2 = currentGroup.captureTime,
                                flareType = config.flareType
                            )
                            lastPreviewConfig = config.copy()
                            finalComposite
                        } else null
                    }
                } catch (e: Exception) {
                    android.util.Log.e("ImageViewerFragment", "Failed to generate edit preview", e)
                    null
                }
            }

            if (bitmap != null) {
                if (!processOnlySelected) {
                    val currentIndex = binding.imagePager.currentItem
                    val holder = (binding.imagePager.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView)
                        ?.findViewHolderForAdapterPosition(currentIndex) as? ImageViewerAdapter.ViewHolder
                    holder?.binding?.imageView?.setImageBitmap(bitmap)
                    adapter.setFormat(currentIndex, "DNG")
                }
                activeAdjustmentBinding?.editPreviewImage?.setImageBitmap(bitmap)
            }
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

        lifecycleScope.launch {
            ensureDngBytesLoaded()
            withContext(Dispatchers.IO) {
                try {
                    val context = requireContext()
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
                        val bmpW = if (rotDegrees == 90 || rotDegrees == 270) options.outHeight else options.outWidth
                        val bmpH = if (rotDegrees == 90 || rotDegrees == 270) options.outWidth else options.outHeight
                        val previewBitmap = android.graphics.Bitmap.createBitmap(bmpW, bmpH, android.graphics.Bitmap.Config.ARGB_8888)
                        val adj = if (currentGroup.isHalfFrame()) config.adjustments?.get(index) ?: top.maary.darkbag.models.BasicAdjustments() else config.toBasic()

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
                            outputTiffPath = null,
                            outputJpgPath = null,
                            useGpu = false,
                            orientation = rotDegrees,
                            mirror = false,
                            outputBitmap = previewBitmap,
                            downsampleFactor = 1
                        )
                        return previewBitmap
                    }

                    val finalBitmap: android.graphics.Bitmap? = if (!currentGroup.isHalfFrame()) {
                        processFull(sourceDngBytes, dngUri1, 0)
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
                                flareType = config.flareType
                            )
                            b1?.recycle()
                            b2?.recycle()
                            finalComposite
                        } else null
                    }

                    finalBitmap?.let { bitmap ->
                        val baseName = if (isReplacement) currentGroup.baseName else "${currentGroup.baseName}_edited_${System.currentTimeMillis()}"
                        val targetUri = if (isReplacement) currentGroup.jpgUri else null
                        val jpgFolderUri = context.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
                            .getString(SettingsFragment.KEY_JPG_STORAGE_URI, null)

                        top.maary.darkbag.utils.ImageSaver.saveProcessedImage(
                            context = context,
                            inputBitmap = bitmap,
                            bmpPath = null,
                            rotationDegrees = 0,
                            zoomFactor = 1.0f,
                            baseName = baseName,
                            linearDngPath = null,
                            tiffPath = null,
                            saveJpg = true,
                            saveTiff = false,
                            saveRaw = false,
                            targetUri = targetUri,
                            jpgFolderUri = if (isReplacement) null else jpgFolderUri,
                                editConfig = config,
                                isAlreadyStitched = currentGroup.isHalfFrame()
                        )
                    }
                } catch (e: Exception) {
                    android.util.Log.e("ImageViewerFragment", "Failed to save edit", e)
                }
            }

            resetAdjustments()
            val updatedGroups = repository.getGroupedImages()
            if (updatedGroups.isNotEmpty()) {
                val targetBaseName = currentGroup.baseName
                val newPos = updatedGroups.indexOfFirst { it.baseName == targetBaseName }.coerceAtLeast(0)
                adapter = ImageViewerAdapter(updatedGroups, lifecycleScope).apply {
                    onImageTapped = { toggleUi() }
                    onZoomChanged = { isZoomed -> if (isZoomed) hideUi() else showUi() }
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

    private fun showDeleteDialog(group: ImageGroup) {
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

            if (deleteGroup) {
                group.jpgUri?.let { context.contentResolver.delete(it, null, null) }
                group.tiffUri?.let { context.contentResolver.delete(it, null, null) }
                group.dngUri?.let { context.contentResolver.delete(it, null, null) }
                group.dngUri1?.let { context.contentResolver.delete(it, null, null) }
                group.dngUri2?.let { context.contentResolver.delete(it, null, null) }

                if (adapter.itemCount > 1) {
                    val nextIndex = if (currentIndex < adapter.itemCount - 1) currentIndex + 1 else currentIndex - 1
                    val nextGroup = adapter.getGroup(nextIndex)
                    nextTargetUri = (nextGroup.jpgUri ?: nextGroup.dngUri ?: nextGroup.tiffUri)?.toString()
                }
            } else {
                val selectedFormat = adapter.getSelectedFormat(binding.imagePager.currentItem)
                if (selectedFormat == "DNG" && group.isHalfFrame()) {
                     group.dngUri1?.let { context.contentResolver.delete(it, null, null) }
                     group.dngUri2?.let { context.contentResolver.delete(it, null, null) }
                } else {
                    val currentUri = when (selectedFormat) {
                        "JPG" -> group.jpgUri
                        "TIFF" -> group.tiffUri
                        "DNG" -> group.dngUri ?: group.dngUri1 ?: group.dngUri2
                        else -> group.jpgUri ?: group.dngUri ?: group.dngUri1 ?: group.dngUri2 ?: group.tiffUri
                    }
                    currentUri?.let { context.contentResolver.delete(it, null, null) }
                }

                val remainingGroup = repository.getGroupedImages().find { it.baseName == group.baseName }
                nextTargetUri = if (remainingGroup != null) {
                    (remainingGroup.jpgUri ?: remainingGroup.dngUri ?: remainingGroup.tiffUri)?.toString()
                } else {
                    if (adapter.itemCount > 1) {
                        val nextIndex = if (currentIndex < adapter.itemCount - 1) currentIndex + 1 else currentIndex - 1
                        val nextGroup = adapter.getGroup(nextIndex)
                        (nextGroup.jpgUri ?: nextGroup.dngUri ?: nextGroup.tiffUri)?.toString()
                    } else null
                }
            }
            loadImages(nextTargetUri)
        }
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            if (isAdjusted) {
                resetAdjustments()
            } else {
                findNavController().navigateUp()
            }
        }
    }

    private fun toggleUi() {
        if (isUiVisible) hideUi() else showUi()
    }

    private fun showUi() {
        if (isUiVisible) return
        isUiVisible = true

        binding.toolbar.visibility = View.VISIBLE
        binding.splitShare.visibility = if (isAdjusted) View.GONE else View.VISIBLE
        binding.splitSave.visibility = if (isAdjusted) View.VISIBLE else View.GONE
        binding.bottomLeftControls.visibility = View.VISIBLE
        binding.bottomRightControls.visibility = View.VISIBLE

        adapter.setUiVisibility(true)

        binding.toolbar.animate().translationY(0f).alpha(1f).setDuration(200).setListener(null).start()
        binding.splitShare.animate().translationY(0f).alpha(1f).setDuration(200).setListener(null).start()
        binding.splitSave.animate().translationY(0f).alpha(1f).setDuration(200).setListener(null).start()
        binding.bottomLeftControls.animate().translationY(0f).alpha(1f).setDuration(200).setListener(null).start()
        binding.bottomRightControls.animate().translationY(0f).alpha(1f).setDuration(200).setListener(null).start()
    }

    private fun hideUi() {
        if (!isUiVisible) return
        isUiVisible = false

        adapter.setUiVisibility(false)

        val topShift = -(binding.toolbar.height + (binding.toolbar.layoutParams as ViewGroup.MarginLayoutParams).topMargin).toFloat()
        val bottomShift = (binding.bottomLeftControls.height + (binding.bottomLeftControls.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin).toFloat()

        binding.toolbar.animate().translationY(topShift).alpha(0f).setDuration(200)
            .withEndAction { binding.toolbar.visibility = View.GONE }.start()
        binding.splitShare.animate().translationY(topShift).alpha(0f).setDuration(200)
            .withEndAction { binding.splitShare.visibility = View.GONE }.start()
        binding.splitSave.animate().translationY(topShift).alpha(0f).setDuration(200)
            .withEndAction { binding.splitSave.visibility = View.GONE }.start()
        binding.bottomLeftControls.animate().translationY(bottomShift).alpha(0f).setDuration(200)
            .withEndAction { binding.bottomLeftControls.visibility = View.GONE }.start()
        binding.bottomRightControls.animate().translationY(bottomShift).alpha(0f).setDuration(200)
            .withEndAction { binding.bottomRightControls.visibility = View.GONE }.start()
    }

    private fun setupEdgeToEdge() {
        val marginSmall = resources.getDimensionPixelSize(R.dimen.margin_small)
        ViewCompat.setOnApplyWindowInsetsListener(binding.viewerRoot) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())

            binding.toolbar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = systemBars.top
                leftMargin = systemBars.left
            }
            binding.splitShare.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = systemBars.top
                rightMargin = systemBars.right + marginSmall
            }
            binding.splitSave.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = systemBars.top
                rightMargin = systemBars.right + marginSmall
            }
            binding.bottomLeftControls.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = systemBars.bottom + marginSmall
                leftMargin = systemBars.left + marginSmall
            }
            binding.bottomRightControls.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = systemBars.bottom + marginSmall
                rightMargin = systemBars.right + marginSmall
            }
            insets
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
