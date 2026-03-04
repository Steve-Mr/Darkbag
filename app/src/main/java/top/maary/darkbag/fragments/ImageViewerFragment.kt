package top.maary.darkbag.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
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

    private var isEditMode = false
    private var currentEditConfig: top.maary.darkbag.models.EditConfig? = null
    private var selectedDngIndex = 0 // 0 or 1 for half-frame
    private var sourceDngBytes: ByteArray? = null
    private var sourceDngBytes2: ByteArray? = null
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
            } else if (targetUri != null) {
                // If the target image was deleted, stay at current or nearest index
                // ViewPager2 handles this somewhat, but let's be explicit if needed.
            }

            setupActionButtons()
            updateEditButtonVisibility()
        }

        binding.imagePager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateEditButtonVisibility()
            }
        })
    }

    private fun updateEditButtonVisibility() {
        if (!::adapter.isInitialized || adapter.itemCount == 0) return
        val currentGroup = adapter.getGroup(binding.imagePager.currentItem)
        binding.btnEdit.visibility = if (currentGroup.dngUri != null || currentGroup.dngUri1 != null) View.VISIBLE else View.GONE
    }

    private fun setupActionButtons() {
        binding.btnEdit.setOnClickListener {
            enterEditMode()
        }

        binding.editControlsRoot.setOnClickListener {
            if (binding.editLutListContainer.visibility == View.VISIBLE) {
                binding.editLutListContainer.visibility = View.GONE
            }
        }

        binding.btnEditSaveMain.setOnClickListener {
            saveEdit(isReplacement = true)
        }

        binding.btnEditSaveAlt.setOnClickListener {
            val popup = android.widget.PopupMenu(requireContext(), it)
            popup.menu.add("Save as new file")
            popup.setOnMenuItemClickListener {
                saveEdit(isReplacement = false)
                true
            }
            popup.show()
        }

        binding.btnShare.setOnClickListener {
            val currentIndex = binding.imagePager.currentItem
            val currentGroup = adapter.getGroup(currentIndex)

            val holder = (binding.imagePager.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView)
                ?.findViewHolderForAdapterPosition(currentIndex) as? ImageViewerAdapter.ViewHolder

            if (holder?.binding?.formatToggleGroup?.checkedButtonId == R.id.btn_dng && currentGroup.isHalfFrame()) {
                showHalfFrameShareSheet(currentGroup)
            } else {
                val currentUri = when (holder?.binding?.formatToggleGroup?.checkedButtonId) {
                    R.id.btn_jpg -> currentGroup.jpgUri
                    R.id.btn_tiff -> currentGroup.tiffUri
                    R.id.btn_dng -> currentGroup.dngUri ?: currentGroup.dngUri1 ?: currentGroup.dngUri2
                    else -> currentGroup.jpgUri ?: currentGroup.dngUri ?: currentGroup.dngUri1 ?: currentGroup.dngUri2 ?: currentGroup.tiffUri
                }
                currentUri?.let { shareImages(listOf(it)) }
            }
        }

        binding.btnDelete.setOnClickListener {
            val currentGroup = adapter.getGroup(binding.imagePager.currentItem)
            showDeleteDialog(currentGroup)
        }

        // Edit Mode Buttons
        binding.btnEditCancel.setOnClickListener {
            exitEditMode()
        }

        binding.btnEditLogLut.setOnClickListener {
            showLutMenu()
        }

        binding.hfSelection1.setOnClickListener {
            if (selectedDngIndex != 0) {
                selectedDngIndex = 0
                updateSelectionFeedback()
            }
        }

        binding.hfSelection2.setOnClickListener {
            if (selectedDngIndex != 1) {
                selectedDngIndex = 1
                updateSelectionFeedback()
            }
        }

        binding.fabAdjust.setOnClickListener {
            showAdjustmentsBottomSheet()
        }
    }

    private fun enterEditMode() {
        val currentGroup = adapter.getGroup(binding.imagePager.currentItem)
        val dngUri1 = currentGroup.dngUri ?: currentGroup.dngUri1 ?: return
        val dngUri2 = currentGroup.dngUri2

        isEditMode = true
        selectedDngIndex = 0
        hideUi()
        binding.editControlsRoot.visibility = View.VISIBLE
        binding.imagePager.isUserInputEnabled = false

        currentEditConfig = currentGroup.editConfig?.copy() ?: top.maary.darkbag.models.EditConfig(
            adjustments = if (currentGroup.isHalfFrame()) listOf(top.maary.darkbag.models.BasicAdjustments(), top.maary.darkbag.models.BasicAdjustments()) else null
        )

        if (currentGroup.isHalfFrame()) {
            binding.hfSelection1.visibility = View.VISIBLE
            binding.hfSelection2.visibility = View.VISIBLE

            // Adjust guideline based on layout
            val lp = binding.hfSelectionDivider.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
            if (currentGroup.hfLayout == "TB") {
                lp.orientation = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.HORIZONTAL
            } else {
                lp.orientation = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.VERTICAL
            }
            binding.hfSelectionDivider.layoutParams = lp
            updateSelectionFeedback()
        } else {
            binding.hfSelection1.visibility = View.GONE
            binding.hfSelection2.visibility = View.GONE
        }

        updateEditUi()

        // Load DNG bytes
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                requireContext().contentResolver.openFileDescriptor(dngUri1, "r")?.use { pfd ->
                    sourceDngBytes = java.io.FileInputStream(pfd.fileDescriptor).use { it.readBytes() }
                }
                dngUri2?.let { uri ->
                    requireContext().contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                        sourceDngBytes2 = java.io.FileInputStream(pfd.fileDescriptor).use { it.readBytes() }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("ImageViewerFragment", "Failed to load source DNG bytes", e)
            }
        }
    }

    private fun exitEditMode() {
        isEditMode = false
        sourceDngBytes = null
        sourceDngBytes2 = null
        previewJob?.cancel()
        binding.editControlsRoot.visibility = View.GONE
        binding.hfSelection1.visibility = View.GONE
        binding.hfSelection2.visibility = View.GONE
        binding.editLutListContainer.visibility = View.GONE
        binding.imagePager.isUserInputEnabled = true
        showUi()

        // Restore original image preview
        val currentIndex = binding.imagePager.currentItem
        adapter.notifyItemChanged(currentIndex)
    }

    private fun updateEditUi() {
        currentEditConfig?.let { config ->
            binding.btnEditLogLut.text = "Log: ${config.log} / LUT: ${config.lut?.substringBeforeLast(".")}"
        }
    }

    private fun updateSelectionFeedback() {
        // Find if BottomSheet is showing and update its sliders
        // This is complex as sheetBinding is local to showAdjustmentsBottomSheet
        // For now, selecting a part just triggers a preview refresh.
        // If the user then opens the FAB, it will show correct values.
        applyEditPreview()
    }

    private fun showLutMenu() {
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
                currentEditConfig = currentEditConfig?.copy(log = selectedLog)
                if (selectedLog == "None") {
                    currentEditConfig = currentEditConfig?.copy(lut = "None")
                }
                updateEditUi()
                showLutMenu()
                applyEditPreview()
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
                if (position == 1) {
                    currentEditConfig = currentEditConfig?.copy(lut = "None")
                } else {
                    val filename = luts[position - 2].name
                    currentEditConfig = currentEditConfig?.copy(lut = filename)
                }
                updateEditUi()
                showLutMenu()
                applyEditPreview()
            }
        }
    }

    private fun showPillPopup(items: List<Pair<String, Boolean>>, autoDismiss: Boolean = true, onSelected: (String, Int) -> Unit) {
        val container = binding.editLutListContainer
        val rv = binding.editLutList

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
                    }
                }
            }

            override fun getItemCount() = items.size
        }
    }

    private fun showAdjustmentsBottomSheet() {
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(requireContext())
        val sheetBinding = top.maary.darkbag.databinding.BottomSheetEditAdjustmentsBinding.inflate(layoutInflater)
        dialog.setContentView(sheetBinding.root)

        val config = currentEditConfig ?: return
        val currentGroup = adapter.getGroup(binding.imagePager.currentItem)

        // Effects tab visibility
        if (!currentGroup.isHalfFrame()) {
             sheetBinding.editTabs.getTabAt(2)?.view?.visibility = View.GONE
        } else {
             sheetBinding.switchTimestamp.isChecked = config.showTimestamp
             val checkedFlareId = when(config.flareType) {
                 -1 -> R.id.btn_flare_none
                 0 -> R.id.btn_flare_random
                 1 -> R.id.btn_flare_v
                 2 -> R.id.btn_flare_c
                 else -> R.id.btn_flare_none
             }
             sheetBinding.groupFlare.check(checkedFlareId)
        }

        // Initialize values based on current selection
        fun updateSliders() {
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
            sheetBinding.sliderContrast.value = target.contrast
            sheetBinding.sliderSaturation.value = target.saturation
            sheetBinding.sliderHighlights.value = target.highlights
            sheetBinding.sliderShadows.value = target.shadows
            sheetBinding.sliderWhites.value = target.whites
            sheetBinding.sliderBlacks.value = target.blacks
        }
        updateSliders()

        sheetBinding.editTabs.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab?) {
                sheetBinding.layoutLight.visibility = if (tab?.position == 0) View.VISIBLE else View.GONE
                sheetBinding.layoutColor.visibility = if (tab?.position == 1) View.VISIBLE else View.GONE
                sheetBinding.layoutEffects.visibility = if (tab?.position == 2) View.VISIBLE else View.GONE
            }
            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
        })

        val changeListener = com.google.android.material.slider.Slider.OnChangeListener { slider, value, fromUser ->
            if (fromUser) {
                val current = currentEditConfig ?: return@OnChangeListener
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

        sheetBinding.switchTimestamp.setOnCheckedChangeListener { _, isChecked ->
            currentEditConfig = currentEditConfig?.copy(showTimestamp = isChecked)
            applyEditPreview()
        }

        sheetBinding.groupFlare.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val type = when(checkedId) {
                    R.id.btn_flare_none -> -1
                    R.id.btn_flare_random -> 0
                    R.id.btn_flare_v -> 1
                    R.id.btn_flare_c -> 2
                    else -> -1
                }
                currentEditConfig = currentEditConfig?.copy(flareType = type)
                applyEditPreview()
            }
        }

        dialog.show()
    }

    private fun applyEditPreview() {
        val config = currentEditConfig ?: return
        val currentGroup = adapter.getGroup(binding.imagePager.currentItem)
        val dngUri1 = currentGroup.dngUri ?: currentGroup.dngUri1 ?: return
        val dngUri2 = currentGroup.dngUri2

        previewJob?.cancel()
        previewJob = lifecycleScope.launch {
            delay(150) // Debounce
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
                            outputTiffPath = null,
                            outputJpgPath = null,
                            useGpu = false,
                            orientation = rotDegrees,
                            mirror = false,
                            outputBitmap = previewBitmap,
                            downsampleFactor = ds
                        )

                        // Apply selection feedback (gray out if not selected)
                        if (currentGroup.isHalfFrame() && selectedDngIndex != index) {
                             val canvas = android.graphics.Canvas(previewBitmap)
                             val paint = android.graphics.Paint()
                             val cm = android.graphics.ColorMatrix()
                             cm.setSaturation(0f)
                             paint.colorFilter = android.graphics.ColorMatrixColorFilter(cm)
                             paint.alpha = 150
                             canvas.drawBitmap(previewBitmap, 0f, 0f, paint)
                        } else if (currentGroup.isHalfFrame() && selectedDngIndex == index) {
                             // Draw subtle border
                             val canvas = android.graphics.Canvas(previewBitmap)
                             val paint = android.graphics.Paint().apply {
                                 color = android.graphics.Color.WHITE
                                 style = android.graphics.Paint.Style.STROKE
                                 strokeWidth = 10f
                                 alpha = 180
                             }
                             canvas.drawRect(0f, 0f, previewBitmap.width.toFloat(), previewBitmap.height.toFloat(), paint)
                        }

                        return previewBitmap
                    }

                    if (!currentGroup.isHalfFrame()) {
                        processSingle(sourceDngBytes, dngUri1, 0)
                    } else {
                        val b1 = processSingle(sourceDngBytes, dngUri1, 0)
                        val b2 = dngUri2?.let { processSingle(sourceDngBytes2, it, 1) }

                        if (b1 != null || b2 != null) {
                            // Manual Stitching for preview
                            val isSBS = currentGroup.hfLayout != "TB"
                            val gap = top.maary.darkbag.utils.HalfFrameUtils.calculateGap(maxOf(b1?.width ?: 0, b1?.height ?: 0)).toFloat() / 4 // Scale gap for preview

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

                            // Apply global effects (Timestamp, Flare)
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
                } catch (e: Exception) {
                    android.util.Log.e("ImageViewerFragment", "Failed to generate edit preview", e)
                    null
                }
            }

            if (bitmap != null) {
                val holder = (binding.imagePager.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView)
                    ?.findViewHolderForAdapterPosition(binding.imagePager.currentItem) as? ImageViewerAdapter.ViewHolder
                holder?.binding?.imageView?.setImageBitmap(bitmap)
            }
        }
    }

    private fun top.maary.darkbag.models.EditConfig.toBasic() = top.maary.darkbag.models.BasicAdjustments(
        exposure, contrast, saturation, highlights, shadows, whites, blacks
    )

    private fun saveEdit(isReplacement: Boolean) {
        val config = currentEditConfig ?: return
        val currentIndex = binding.imagePager.currentItem
        val currentGroup = adapter.getGroup(currentIndex)
        val dngUri1 = currentGroup.dngUri ?: currentGroup.dngUri1 ?: return
        val dngUri2 = currentGroup.dngUri2

        lifecycleScope.launch {
            binding.editControlsRoot.visibility = View.GONE
            // Show some loading indicator if needed

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
                            editConfig = config
                        )
                    }
                } catch (e: Exception) {
                    android.util.Log.e("ImageViewerFragment", "Failed to save edit", e)
                }
            }

            exitEditMode()
            // After saving, we need to refresh the group information from repository
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
                updateEditButtonVisibility()
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
        var checkedItem = 1 // Default to "Delete entire group"

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
        lifecycleScope.launch {
            val context = this@ImageViewerFragment.context ?: return@launch
            var nextTargetUri: String? = null
            val currentIndex = binding.imagePager.currentItem

            if (deleteGroup) {
                group.jpgUri?.let { context.contentResolver.delete(it, null, null) }
                group.tiffUri?.let { context.contentResolver.delete(it, null, null) }
                group.dngUri?.let { context.contentResolver.delete(it, null, null) }
                group.dngUri1?.let { context.contentResolver.delete(it, null, null) }
                group.dngUri2?.let { context.contentResolver.delete(it, null, null) }

                // Determine next image to focus on
                if (adapter.itemCount > 1) {
                    val nextIndex = if (currentIndex < adapter.itemCount - 1) currentIndex + 1 else currentIndex - 1
                    val nextGroup = adapter.getGroup(nextIndex)
                    nextTargetUri = (nextGroup.jpgUri ?: nextGroup.dngUri ?: nextGroup.tiffUri)?.toString()
                }
            } else {
                // Determine current format from ViewPager state
                val holder = (binding.imagePager.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView)
                    ?.findViewHolderForAdapterPosition(currentIndex) as? ImageViewerAdapter.ViewHolder

                if (holder?.binding?.formatToggleGroup?.checkedButtonId == R.id.btn_dng && group.isHalfFrame()) {
                     group.dngUri1?.let { context.contentResolver.delete(it, null, null) }
                     group.dngUri2?.let { context.contentResolver.delete(it, null, null) }
                } else {
                    val currentUri = when (holder?.binding?.formatToggleGroup?.checkedButtonId) {
                        R.id.btn_jpg -> group.jpgUri
                        R.id.btn_tiff -> group.tiffUri
                        R.id.btn_dng -> group.dngUri
                        else -> group.jpgUri ?: group.dngUri ?: group.dngUri1 ?: group.dngUri2 ?: group.tiffUri
                    }
                    currentUri?.let { context.contentResolver.delete(it, null, null) }
                }

                // If we deleted the last format of this group, we need to find next group
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
            // Reload with target uri to preserve position
            loadImages(nextTargetUri)
        }
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }
    }

    private fun toggleUi() {
        if (isUiVisible) hideUi() else showUi()
    }

    private fun showUi() {
        if (isUiVisible) return
        isUiVisible = true
        binding.appBar.visibility = View.VISIBLE
        binding.bottomControls.visibility = View.VISIBLE
        binding.appBar.animate().translationY(0f).setDuration(200).setListener(null).start()
        binding.bottomControls.animate().translationY(0f).setDuration(200).setListener(null).start()
    }

    private fun hideUi() {
        if (!isUiVisible) return
        isUiVisible = false
        binding.appBar.animate().translationY(-binding.appBar.height.toFloat())
            .setDuration(200)
            .setListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (!isUiVisible) binding.appBar.visibility = View.GONE
                }
            }).start()
        binding.bottomControls.animate().translationY(binding.bottomControls.height.toFloat())
            .setDuration(200)
            .setListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (!isUiVisible) binding.bottomControls.visibility = View.GONE
                }
            }).start()
    }

    private fun setupEdgeToEdge() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.appBar.updatePadding(
                top = systemBars.top,
                left = systemBars.left,
                right = systemBars.right
            )
            binding.bottomControls.updatePadding(
                bottom = systemBars.bottom,
                left = systemBars.left,
                right = systemBars.right
            )
            insets
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
