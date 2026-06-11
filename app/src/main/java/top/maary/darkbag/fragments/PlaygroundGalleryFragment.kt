package top.maary.darkbag.fragments

import android.net.Uri
import top.maary.darkbag.models.ImageGroup
import top.maary.darkbag.processor.ColorProcessor
import top.maary.darkbag.fragments.SettingsFragment

import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import android.content.Context
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.view.updateLayoutParams
import android.view.ViewGroup.MarginLayoutParams
import androidx.navigation.Navigation
import com.google.android.material.button.MaterialButton

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import top.maary.darkbag.R
import top.maary.darkbag.databinding.FragmentPlaygroundGalleryBinding
import java.io.File
import java.io.FileOutputStream
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import top.maary.darkbag.databinding.ItemPlaygroundImageBinding
import top.maary.darkbag.utils.ImageSaver
import java.util.UUID


sealed class PlaygroundItem {
    abstract val mainFile: File
    abstract val aspectRatio: String?

    data class Single(
        val file: File,
        override val aspectRatio: String? = null
    ) : PlaygroundItem() {
        override val mainFile: File = file
    }

    data class Group(
        val jpgFile: File,
        val dng1: File,
        val dng2: File,
        var isExpanded: Boolean = false,
        override val aspectRatio: String? = null
    ) : PlaygroundItem() {
        override val mainFile: File = jpgFile
    }
}

class PlaygroundGalleryFragment : Fragment() {

    private var _binding: FragmentPlaygroundGalleryBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: PlaygroundAdapter

    private val selectedFiles = mutableSetOf<File>()
    private var isSelectionMode = false

    private val importDngLauncher = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) {
            importDngs(uris)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPlaygroundGalleryBinding.inflate(inflater, container, false)

        // Observe toolbar height for dynamic padding
        val mainActivity = activity as? top.maary.darkbag.MainActivity
        mainActivity?.let {
            viewLifecycleOwner.lifecycleScope.launch {
                viewLifecycleOwner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                    it.toolbarHeightFlow.collect { height ->
                        binding.recyclerView.setPadding(
                            binding.recyclerView.paddingLeft,
                            binding.recyclerView.paddingTop,
                            binding.recyclerView.paddingRight,
                            height + 16 // 16px extra clearance
                        )
                    }
                }
            }
        }
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val initialPaddingBottom = binding.recyclerView.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.appBarLayout.updatePadding(top = systemBars.top)
            binding.recyclerView.updatePadding(bottom = systemBars.bottom + initialPaddingBottom)

            binding.fabAdd.updateLayoutParams<MarginLayoutParams> {
                bottomMargin = systemBars.bottom + (16 * resources.displayMetrics.density).toInt()
            }
            binding.bottomAppBar.updatePadding(bottom = systemBars.bottom)

            insets
        }

        val prefs = requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        val showToolbar = prefs.getBoolean(SettingsFragment.KEY_SHOW_FLOATING_TOOLBAR, true)
        val defaultStartup = prefs.getString(SettingsFragment.KEY_DEFAULT_STARTUP, SettingsFragment.STARTUP_CAMERA)
        val enableCamera = prefs.getBoolean(SettingsFragment.KEY_ENABLE_CAMERA, true)

        val onBackPressedCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isSelectionMode) {
                    clearSelection()
                } else if (enableCamera && defaultStartup == SettingsFragment.STARTUP_CAMERA) {
                    if (!findNavController().navigateUp()) {
                        requireActivity().finishAfterTransition()
                    }
                } else {
                    requireActivity().finishAfterTransition()
                }
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, onBackPressedCallback)

        if (!showToolbar && defaultStartup == SettingsFragment.STARTUP_CAMERA && enableCamera) {
            binding.toolbar.setNavigationIcon(R.drawable.ic_back)
            binding.toolbar.setNavigationOnClickListener {
                requireActivity().onBackPressedDispatcher.onBackPressed()
            }
        } else {
            binding.toolbar.navigationIcon = null
        }

        val cameraMenu = binding.toolbar.menu.findItem(R.id.action_camera)
        if (!showToolbar && defaultStartup == SettingsFragment.STARTUP_PLAYGROUND && enableCamera) {
            cameraMenu?.isVisible = true
        } else {
            cameraMenu?.isVisible = false
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
            selectedFiles = selectedFiles,
            isSelectionMode = { isSelectionMode },
            onItemClick = { file ->
                if (isSelectionMode) {
                    toggleSelection(file)
                } else {
                    openViewer(listOf(file.absolutePath))
                }
            },
            onItemLongClick = { file ->
                val wasSelectionMode = isSelectionMode
                if (!isSelectionMode) {
                    isSelectionMode = true
                }
                toggleSelection(file)

                if (!wasSelectionMode && isSelectionMode) {
                    // Transitioned into selection mode, notify all items to show unchecked circles
                    adapter.notifyItemRangeChanged(0, adapter.currentList.size, "SELECTION_CHANGED")
                }
            },
            onExpandClick = { group, position ->
                if (position != RecyclerView.NO_POSITION) {
                    group.isExpanded = !group.isExpanded
                    adapter.notifyItemChanged(position, "EXPANSION_CHANGED")
                }
            }
        )

        binding.recyclerView.layoutManager = StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL)
        binding.recyclerView.adapter = adapter
        val mainActivity = activity as? top.maary.darkbag.MainActivity
        mainActivity?.let {
            viewLifecycleOwner.lifecycleScope.launch {
                viewLifecycleOwner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                    it.toolbarHeightFlow.collect { height ->
                        binding.recyclerView.setPadding(
                            binding.recyclerView.paddingLeft,
                            binding.recyclerView.paddingTop,
                            binding.recyclerView.paddingRight,
                            height + 16
                        )
                    }
                }
            }
        }

        binding.fabAdd.setOnClickListener {
            importDngLauncher.launch("image/x-adobe-dng")
        }

        binding.btnExport.setOnClickListener {
            exportSelected()
        }

        binding.btnMerge.setOnClickListener {
            if (selectedFiles.size == 2) {
                val paths = selectedFiles.map { it.absolutePath }
                com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Select Merge Layout")
                    .setItems(arrayOf("Side-by-side", "Top-bottom")) { _, which ->
                        val layout = if (which == 0) "SBS" else "TB"
                        openViewer(paths, layout)
                        clearSelection()
                    }
                    .show()
            } else {
                Toast.makeText(requireContext(), "Select exactly 2 images to merge", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnDisband.setOnClickListener {
            val targets = selectedFiles.mapNotNull { file ->
                val item = adapter.currentList.find { it is PlaygroundItem.Group && it.jpgFile == file }
                if (item is PlaygroundItem.Group) item.jpgFile else null
            }
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                targets.forEach { it.delete() }
                withContext(Dispatchers.Main) {
                    clearSelectionAndReload()
                }
            }
        }

        binding.btnDelete.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.playground_delete_title)
                .setMessage(R.string.playground_delete_message)
                .setPositiveButton(R.string.playground_btn_delete) { _, _ ->
                    val filesToDelete = mutableListOf<File>()
                    selectedFiles.forEach { file ->
                        val item = adapter.currentList.find {
                            when (it) {
                                is PlaygroundItem.Single -> it.file == file
                                is PlaygroundItem.Group -> it.jpgFile == file || it.dng1 == file || it.dng2 == file
                            }
                        }

                        when (item) {
                            is PlaygroundItem.Single -> {
                                filesToDelete.add(item.file)
                            }
                            is PlaygroundItem.Group -> {
                                if (file == item.jpgFile) {
                                    filesToDelete.add(item.jpgFile)
                                    filesToDelete.add(item.dng1)
                                    filesToDelete.add(item.dng2)
                                } else {
                                    filesToDelete.add(file)
                                    filesToDelete.add(item.jpgFile)
                                }
                            }
                            else -> {}
                        }
                    }

                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                        filesToDelete.distinct().forEach { it.delete() }
                        withContext(Dispatchers.Main) {
                            clearSelectionAndReload()
                        }
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        loadFiles()
    }

    private fun getPlaygroundDir(): File {
        val dir = File(requireContext().filesDir, "playground_dngs")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    private fun loadFiles() {
        lifecycleScope.launch(Dispatchers.IO) {
            val dir = getPlaygroundDir()
            val files = dir.listFiles { file -> file.extension.lowercase() == "dng" || file.extension.lowercase() == "jpg" } ?: emptyArray()

            val dngMap = files.filter { it.extension.lowercase() == "dng" }.groupBy { it.nameWithoutExtension }
            val jpgMap = files.filter { it.extension.lowercase() == "jpg" }.associateBy { it.nameWithoutExtension }

            val newItems = mutableListOf<PlaygroundItem>()
            val processedBases = mutableSetOf<String>()

            fun calculateAspectRatio(f: File): String? {
                try {
                    val exifInterface = ExifInterface(f.absolutePath)
                    var width = exifInterface.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
                    var height = exifInterface.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)

                    if (width == 0 || height == 0) {
                        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(f.absolutePath, options)
                        width = options.outWidth
                        height = options.outHeight
                    }

                    if (width > 0 && height > 0) {
                        val orientation = exifInterface.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                        val swapDims = orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
                                       orientation == ExifInterface.ORIENTATION_ROTATE_270 ||
                                       orientation == ExifInterface.ORIENTATION_TRANSPOSE ||
                                       orientation == ExifInterface.ORIENTATION_TRANSVERSE

                        return if (swapDims) "$height:$width" else "$width:$height"
                    }
                } catch (e: Exception) {
                    Log.w("Playground", "Failed to calculate aspect ratio for ${f.name}", e)
                }
                return null
            }

            for (file in files) {
                val baseName = file.nameWithoutExtension
                if (processedBases.contains(baseName)) continue

                // Check if it's a part of a group (ends with _1 or _2)
                var groupBase = baseName
                if (baseName.endsWith("_1")) groupBase = baseName.removeSuffix("_1")
                if (baseName.endsWith("_2")) groupBase = baseName.removeSuffix("_2")

                if (processedBases.contains(groupBase)) continue

                val dng1 = dngMap["${groupBase}_1"]?.firstOrNull()
                val dng2 = dngMap["${groupBase}_2"]?.firstOrNull()
                val groupJpg = jpgMap[groupBase]

                if (dng1 != null && dng2 != null && groupJpg != null) {
                    val ratio = calculateAspectRatio(groupJpg)
                    newItems.add(PlaygroundItem.Group(groupJpg, dng1, dng2, aspectRatio = ratio))
                    processedBases.add(groupBase)
                    processedBases.add("${groupBase}_1")
                    processedBases.add("${groupBase}_2")
                } else if (file.extension.lowercase() == "dng") {
                     // Only add as single if it's a DNG and wasn't processed as part of a group
                     if (!processedBases.contains(baseName)) {
                         // Check if there is an existing JPG to get aspect ratio from to be faster/consistent
                         val singleJpg = jpgMap[baseName]
                         val ratioFile = singleJpg ?: file
                         val ratio = calculateAspectRatio(ratioFile)
                         newItems.add(PlaygroundItem.Single(file, aspectRatio = ratio))
                         processedBases.add(baseName)
                     }
                }
            }

            val sortedItems = newItems.sortedByDescending { it.mainFile.lastModified() }

            withContext(Dispatchers.Main) {
                val currentList = adapter.currentList
                sortedItems.forEach { newItem ->
                    if (newItem is PlaygroundItem.Group) {
                        val oldItem = currentList.find { it is PlaygroundItem.Group && it.mainFile.absolutePath == newItem.mainFile.absolutePath } as? PlaygroundItem.Group
                        if (oldItem != null) {
                            newItem.isExpanded = oldItem.isExpanded
                        }
                    }
                }
                adapter.submitList(sortedItems) {
                    updateEmptyState()
                }
            }
        }
    }

    private fun updateEmptyState() {
        binding.emptyStateText.visibility = if (adapter.currentList.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun toggleSelection(file: File) {
        if (selectedFiles.contains(file)) {
            selectedFiles.remove(file)
        } else {
            selectedFiles.add(file)
        }

        if (selectedFiles.isEmpty()) {
            isSelectionMode = false
        }

        // Find the index of the item that contains this file
        val index = adapter.currentList.indexOfFirst { item ->
            when (item) {
                is PlaygroundItem.Single -> item.file == file
                is PlaygroundItem.Group -> item.jpgFile == file || item.dng1 == file || item.dng2 == file
            }
        }

        if (index != -1) {
            val item = adapter.currentList[index]
            if (item is PlaygroundItem.Group) {
                if (item.jpgFile == file && selectedFiles.contains(file)) {
                    // If the user long clicked the group main image to select it, auto-expand so they see the sub-items too
                    if (!item.isExpanded) {
                        item.isExpanded = true
                        adapter.notifyItemChanged(index, "EXPANSION_CHANGED")
                    }
                } else if (!selectedFiles.contains(item.jpgFile) && !selectedFiles.contains(item.dng1) && !selectedFiles.contains(item.dng2)) {
                    // If no items in this group are selected anymore, auto-collapse it
                    if (item.isExpanded) {
                        item.isExpanded = false
                        adapter.notifyItemChanged(index, "EXPANSION_CHANGED")
                    }
                }
            }
            adapter.notifyItemChanged(index, "SELECTION_CHANGED")
        }

        if (selectedFiles.isEmpty()) {
            // Need to notify all items since they might need to hide the unchecked radio button icons
            adapter.currentList.indices.forEach { i ->
                val itm = adapter.currentList[i]
                if (itm is PlaygroundItem.Group && itm.isExpanded) {
                    itm.isExpanded = false
                    adapter.notifyItemChanged(i, "EXPANSION_CHANGED")
                }
                adapter.notifyItemChanged(i, "SELECTION_CHANGED")
            }
        }
        updateBottomBar()
    }

    private fun clearSelection() {
        val oldSelections = selectedFiles.toList()
        selectedFiles.clear()
        isSelectionMode = false

        adapter.currentList.indices.forEach { index ->
            val item = adapter.currentList[index]
            if (item is PlaygroundItem.Group && item.isExpanded) {
                item.isExpanded = false
                adapter.notifyItemChanged(index, "EXPANSION_CHANGED")
            }
            adapter.notifyItemChanged(index, "SELECTION_CHANGED")
        }

        updateBottomBar()
    }

    private fun updateBottomBar() {
        if (isSelectionMode) {
            binding.bottomAppBar.visibility = View.VISIBLE
            (activity as? top.maary.darkbag.MainActivity)?.setFloatingToolbarForcedHidden(true)
            binding.fabAdd.visibility = View.GONE

            // Only allow merge if exactly 2 individual DNGs are selected
            val allDngs = selectedFiles.all { it.extension.lowercase() == "dng" }
            binding.btnMerge.visibility = if (selectedFiles.size == 2 && allDngs) View.VISIBLE else View.GONE

            // Delete button logic
            binding.btnDelete.visibility = if (selectedFiles.isNotEmpty()) View.VISIBLE else View.GONE

            // Disband button logic
            val allGroups = selectedFiles.isNotEmpty() && selectedFiles.all { selectedFile ->
                adapter.currentList.any { item -> item is PlaygroundItem.Group && item.jpgFile == selectedFile }
            }
            binding.btnDisband.visibility = if (allGroups) View.VISIBLE else View.GONE

            binding.toolbar.title = "${selectedFiles.size} selected"
        } else {
            binding.bottomAppBar.visibility = View.GONE
            (activity as? top.maary.darkbag.MainActivity)?.setFloatingToolbarForcedHidden(false)
            binding.fabAdd.visibility = View.VISIBLE
            binding.toolbar.title = "Playground"
        }
    }

    private fun clearSelectionAndReload() {
        selectedFiles.clear()
        isSelectionMode = false

        adapter.currentList.indices.forEach { index ->
            val item = adapter.currentList[index]
            if (item is PlaygroundItem.Group && item.isExpanded) {
                item.isExpanded = false
                adapter.notifyItemChanged(index, "EXPANSION_CHANGED")
            }
            adapter.notifyItemChanged(index, "SELECTION_CHANGED")
        }

        updateBottomBar()
        loadFiles()
    }

    private fun openViewer(paths: List<String>, hfLayout: String? = null) {
        // Find if the path belongs to a Group. If it's a group's JPG, pass all its DNGs to the viewer.
        val finalPaths = mutableListOf<String>()
        for (path in paths) {
            val file = File(path)
            val item = adapter.currentList.find {
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

    private fun importDngs(uris: List<Uri>) {
        val ctx = context ?: return
        val appContext = ctx.applicationContext
        lifecycleScope.launch(Dispatchers.IO) {
            val dir = getPlaygroundDir()
            var importedCount = 0
            for (uri in uris) {
                try {
                    val fileName = getFileName(appContext, uri) ?: "imported_${UUID.randomUUID()}.dng"
                    val destFile = File(dir, fileName)
                    appContext.contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(destFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    importedCount++
                } catch (e: Exception) {
                    Log.e("Playground", "Failed to import URI: $uri", e)
                }
            }
            withContext(Dispatchers.Main) {
                context?.let { Toast.makeText(it, "Imported $importedCount files", Toast.LENGTH_SHORT).show() }
                loadFiles()
            }
        }
    }

    private fun getFileName(context: android.content.Context, uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index != -1) {
                        result = cursor.getString(index)
                    }
                }
            }
        }
        if (result == null) {
            result = uri.path?.let { path -> File(path).name }
        }
        return result
    }

    private fun exportSelected() {
        val filesToExport = selectedFiles.toList()
        if (filesToExport.isEmpty()) return

        android.widget.Toast.makeText(requireContext(), "Exporting ${filesToExport.size} files...", android.widget.Toast.LENGTH_SHORT).show()

        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            var successCount = 0
            val ctx = context ?: return@launch
            val appContext = ctx.applicationContext
            val repository = top.maary.darkbag.repository.ImageRepository(appContext)

            val itemsSnapshot = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { adapter.currentList.toList() }
            val processedGroups = mutableSetOf<String>()

            for (file in filesToExport) {
                try {
                    // Find which item this file belongs to
                    val item = itemsSnapshot.find {
                        when (it) {
                            is PlaygroundItem.Single -> it.file == file
                            is PlaygroundItem.Group -> it.jpgFile == file || it.dng1 == file || it.dng2 == file
                        }
                    }

                    if (item == null) continue

                    when (item) {
                        is PlaygroundItem.Single -> {
                            val baseName = item.file.nameWithoutExtension
                            val dngUri = Uri.fromFile(item.file)

                            val jpgFile = java.io.File(item.file.parent, "$baseName.jpg")
                            val jpgUri = if (jpgFile.exists()) Uri.fromFile(jpgFile) else null

                            val group = ImageGroup(
                                baseName = baseName,
                                dngUri = dngUri,
                                jpgUri = jpgUri,
                                captureTime = item.file.lastModified(),
                                lastModified = item.file.lastModified()
                            )
                            val loadedGroup = repository.loadMetadata(group)
                            val config = loadedGroup.editConfig ?: top.maary.darkbag.models.EditConfig()

                            val logIndex = top.maary.darkbag.fragments.SettingsFragment.LOG_CURVES.indexOf(config.log)
                            val lutManager = top.maary.darkbag.utils.LutManager(appContext)
                            val lutPath = if (config.lut != null && config.lut != "None") {
                                java.io.File(lutManager.lutDir, config.lut).absolutePath
                            } else null

                            if (item.file.extension.lowercase() == "jpg") {
                                // If the user selected an already processed JPG, we just decode and save it
                                // rather than trying to process it as a RAW byte array
                                val decodeOpts = android.graphics.BitmapFactory.Options().apply {
                                    inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
                                }
                                val inputBitmap = android.graphics.BitmapFactory.decodeFile(item.file.absolutePath, decodeOpts)

                                val uri = ImageSaver.saveProcessedImage(
                                    context = appContext,
                                    inputBitmap = inputBitmap,
                                    bmpPath = null,
                                    rotationDegrees = 0,
                                    zoomFactor = config.zoomFactor,
                                    baseName = baseName,
                                    linearDngPath = null,
                                    saveJpg = true,
                                    saveRaw = false,
                                    editConfig = config,
                                    isAlreadyStitched = true,
                                    captureMetadata = repository.getCaptureMetadata(Uri.fromFile(item.file))
                                )
                                if (uri != null) successCount++
                                inputBitmap?.recycle()
                            } else {
                                val finalBytes = java.io.FileInputStream(item.file).use { it.readBytes() }
                                val orientation = try {
                                    appContext.contentResolver.openInputStream(dngUri)?.use { input ->
                                        androidx.exifinterface.media.ExifInterface(input).getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL)
                                    } ?: androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
                                } catch (e: Exception) { androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL }

                                val rotDegrees = when(orientation) {
                                    androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
                                    androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
                                    androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
                                    else -> 0
                                }

                                val adj = config.adjustments?.get(0) ?: top.maary.darkbag.models.BasicAdjustments(config.exposure, config.contrast, config.saturation, config.highlights, config.shadows, config.whites, config.blacks, config.digitalGain)
                                val meta = repository.getCaptureMetadata(dngUri)

                                val tempJpgFile = java.io.File(appContext.cacheDir, "temp_export_${System.currentTimeMillis()}.jpg")

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
                                    digitalGain = adj.digitalGain,
                                    outputJpgPath = tempJpgFile.absolutePath,
                                    useGpu = false,
                                    orientation = rotDegrees,
                                    mirror = false,
                                    outputBitmap = null,
                                    downsampleFactor = 1,
                                    zoomFactor = config.zoomFactor,
                                    metadata = meta
                                )

                                if (tempJpgFile.exists()) {
                                    val uri = ImageSaver.saveProcessedImage(
                                        context = appContext,
                                        inputBitmap = null,
                                        bmpPath = tempJpgFile.absolutePath,
                                        rotationDegrees = 0,
                                        zoomFactor = 1.0f,
                                        baseName = baseName,
                                        linearDngPath = null,
                                        saveJpg = true,
                                        saveRaw = false,
                                        editConfig = config,
                                        isAlreadyStitched = true,
                                        captureMetadata = meta
                                    )
                                    if (uri != null) successCount++
                                    tempJpgFile.delete()
                                }
                            }
                        }
                        is PlaygroundItem.Group -> {
                            val baseName = item.jpgFile.nameWithoutExtension

                            // Prevent exporting the same group multiple times if multiple sub-files are selected
                            if (processedGroups.contains(baseName)) continue
                            processedGroups.add(baseName)

                            val dngUri1 = Uri.fromFile(item.dng1)
                            val dngUri2 = Uri.fromFile(item.dng2)
                            val jpgUri = Uri.fromFile(item.jpgFile)

                            val group = ImageGroup(
                                baseName = baseName,
                                dngUri1 = dngUri1,
                                dngUri2 = dngUri2,
                                jpgUri = jpgUri,
                                captureTime = item.jpgFile.lastModified(),
                                lastModified = item.jpgFile.lastModified()
                            )

                            val loadedGroup = repository.loadMetadata(group)
                            val config = loadedGroup.editConfig ?: top.maary.darkbag.models.EditConfig()

                            val logIndex = top.maary.darkbag.fragments.SettingsFragment.LOG_CURVES.indexOf(config.log)
                            val lutManager = top.maary.darkbag.utils.LutManager(appContext)
                            val lutPath = if (config.lut != null && config.lut != "None") {
                                java.io.File(lutManager.lutDir, config.lut).absolutePath
                            } else null

                            fun processFullSafe(targetFile: java.io.File?, uri: Uri?, index: Int): android.graphics.Bitmap? {
                                if (targetFile == null || !targetFile.exists() || uri == null) return null
                                val finalBytes = java.io.FileInputStream(targetFile).use { it.readBytes() }

                                val orientation = try {
                                    appContext.contentResolver.openInputStream(uri)?.use { input ->
                                        androidx.exifinterface.media.ExifInterface(input).getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL)
                                    } ?: androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
                                } catch (e: Exception) { androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL }

                                val rotDegrees = when(orientation) {
                                    androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
                                    androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
                                    androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
                                    else -> 0
                                }

                                val adj = config.adjustments?.get(index) ?: top.maary.darkbag.models.BasicAdjustments(config.exposure, config.contrast, config.saturation, config.highlights, config.shadows, config.whites, config.blacks, config.digitalGain)
                                val meta = repository.getCaptureMetadata(uri)

                                val tempJpgFile = java.io.File(appContext.cacheDir, "temp_export_${System.currentTimeMillis()}_$index.jpg")

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
                                    digitalGain = adj.digitalGain,
                                    outputJpgPath = tempJpgFile.absolutePath,
                                    useGpu = false,
                                    orientation = rotDegrees,
                                    mirror = false,
                                    outputBitmap = null,
                                    downsampleFactor = 1,
                                    zoomFactor = config.zoomFactor,
                                    metadata = meta
                                )

                                if (!tempJpgFile.exists()) return null

                                val decodeOpts = android.graphics.BitmapFactory.Options().apply {
                                    inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
                                }
                                val bitmap = android.graphics.BitmapFactory.decodeFile(tempJpgFile.absolutePath, decodeOpts)
                                tempJpgFile.delete()
                                return bitmap
                            }

                            val f1 = processFullSafe(item.dng1, dngUri1, 0)
                            val f2 = processFullSafe(item.dng2, dngUri2, 1)

                            val b1 = if (config.isSwapped) f2 else f1
                            val b2 = if (config.isSwapped) f1 else f2

                            val finalCompositeBitmap: android.graphics.Bitmap? = if (b1 != null || b2 != null) {
                                val isSBS = config.hfLayout ?: "SBS" != "TB"

                                val refW = b1?.width ?: b2?.width ?: 0
                                val refH = b1?.height ?: b2?.height ?: 0

                                val oriented1 = b1?.let { top.maary.darkbag.utils.HalfFrameUtils.ensureOrientation(it, isSBS) }
                                val oriented2 = b2?.let { top.maary.darkbag.utils.HalfFrameUtils.ensureOrientation(it, isSBS) }

                                // Recycle original un-oriented bitmaps immediately to save memory
                                if (oriented1 != b1) b1?.recycle()
                                if (oriented2 != b2) b2?.recycle()

                                val w1 = oriented1?.width ?: refW
                                val h1 = oriented1?.height ?: refH
                                val w2 = oriented2?.width ?: refW
                                val h2 = oriented2?.height ?: refH

                                val tempB1 = oriented1 ?: android.graphics.Bitmap.createBitmap(w2, h2, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLACK) }
                                val tempB2 = oriented2 ?: android.graphics.Bitmap.createBitmap(w1, h1, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLACK) }

                                var composite = top.maary.darkbag.utils.HalfFrameUtils.composeBitmaps(tempB1, tempB2, isSBS)

                                // Recycle oriented/temp bitmaps immediately after compose
                                if (tempB1 != oriented1) tempB1.recycle()
                                if (tempB2 != oriented2) tempB2.recycle()
                                oriented1?.recycle()
                                oriented2?.recycle()

                                val economical = top.maary.darkbag.utils.HalfFrameManager(appContext).downsample
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

                                val time1 = repository.getCaptureMetadata(dngUri1)?.dateTimeOriginal ?: item.dng1.lastModified()
                                val time2 = repository.getCaptureMetadata(dngUri2)?.dateTimeOriginal ?: item.dng2.lastModified()
                                val t1 = if (config.isSwapped) time2 else time1
                                val t2 = if (config.isSwapped) time1 else time2

                                val finalComposite = top.maary.darkbag.utils.HalfFrameUtils.addEffects(
                                    composite,
                                    config.showTimestamp,
                                    config.flareType >= 0,
                                    config.hfLayout ?: "SBS",
                                    time1 = t1,
                                    time2 = t2,
                                    flareType = config.flareType
                                )
                                if (finalComposite != composite) {
                                    composite.recycle()
                                }
                                finalComposite
                            } else null

                            finalCompositeBitmap?.let { bitmap ->
                                val captureMetadata = repository.getCaptureMetadata(dngUri1) ?: repository.getCaptureMetadata(jpgUri)

                                val uri = ImageSaver.saveProcessedImage(
                                    context = appContext,
                                    inputBitmap = bitmap,
                                    bmpPath = null,
                                    rotationDegrees = 0,
                                    zoomFactor = 1.0f,
                                    baseName = baseName,
                                    linearDngPath = null,
                                    saveJpg = true,
                                    saveRaw = false,
                                    editConfig = config,
                                    isAlreadyStitched = true,
                                    captureMetadata = captureMetadata
                                )
                                if (uri != null) successCount++
                                bitmap.recycle()
                            }
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("Playground", "Failed to export ${file.name}", e)
                }
            }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                android.widget.Toast.makeText(requireContext(), "Exported $successCount files", android.widget.Toast.LENGTH_SHORT).show()
                clearSelection()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Trigger a fresh load to catch any changes from PlaygroundViewerFragment
        loadFiles()
        // Re-synchronize the bottom bar state which updates the forced hidden state
        // of the floating toolbar based on selection mode.
        updateBottomBar()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

class PlaygroundAdapter(
    private val coroutineScope: kotlinx.coroutines.CoroutineScope,
    private val selectedFiles: Set<File>,
    private val isSelectionMode: () -> Boolean,
    private val onItemClick: (File) -> Unit,
    private val onItemLongClick: (File) -> Unit,
    private val onExpandClick: (PlaygroundItem.Group, Int) -> Unit
) : androidx.recyclerview.widget.ListAdapter<PlaygroundItem, PlaygroundAdapter.ViewHolder>(PlaygroundItemDiffCallback()) {

    class PlaygroundItemDiffCallback : androidx.recyclerview.widget.DiffUtil.ItemCallback<PlaygroundItem>() {
        override fun areItemsTheSame(oldItem: PlaygroundItem, newItem: PlaygroundItem): Boolean {
            return oldItem.mainFile.absolutePath == newItem.mainFile.absolutePath
        }

        override fun areContentsTheSame(oldItem: PlaygroundItem, newItem: PlaygroundItem): Boolean {
            if (oldItem.javaClass != newItem.javaClass) return false
            if (oldItem is PlaygroundItem.Group && newItem is PlaygroundItem.Group) {
                if (oldItem.isExpanded != newItem.isExpanded) return false
                if (oldItem.dng1.lastModified() != newItem.dng1.lastModified() || oldItem.dng2.lastModified() != newItem.dng2.lastModified()) return false
            }
            return oldItem.mainFile.lastModified() == newItem.mainFile.lastModified()
        }

        override fun getChangePayload(oldItem: PlaygroundItem, newItem: PlaygroundItem): Any? {
            val payloads = mutableListOf<String>()

            if (oldItem is PlaygroundItem.Group && newItem is PlaygroundItem.Group) {
                if (oldItem.isExpanded != newItem.isExpanded) {
                    payloads.add("EXPANSION_CHANGED")
                }
            }

            val isModified = oldItem.mainFile.lastModified() != newItem.mainFile.lastModified() ||
                (oldItem is PlaygroundItem.Group && newItem is PlaygroundItem.Group &&
                (oldItem.dng1.lastModified() != newItem.dng1.lastModified() || oldItem.dng2.lastModified() != newItem.dng2.lastModified()))

            if (isModified) {
                payloads.add("MODIFIED")
            }

            if (payloads.isNotEmpty()) {
                // Return just the first string if it's one, or we can handle it differently.
                // The onBindViewHolder expects String payloads, if we pass a list it might get wrapped in another list.
                // Let's just return the first string because onBindViewHolder currently only looks at payloads list string elements.
                // Or better, let's return a special string and handle both if needed.
                // Let's just check the most important or return all and let onBindViewHolder handle List inside payloads.
                // Actually, if we return multiple strings in a list, we can just return the payloads list.
                return payloads
            }

            return super.getChangePayload(oldItem, newItem)
        }
    }

    class ViewHolder(val binding: ItemPlaygroundImageBinding) : RecyclerView.ViewHolder(binding.root) {
        var jobMain: kotlinx.coroutines.Job? = null
        var bitmapMain: Bitmap? = null
        var jobSub1: kotlinx.coroutines.Job? = null
        var bitmapSub1: Bitmap? = null
        var jobSub2: kotlinx.coroutines.Job? = null
        var bitmapSub2: Bitmap? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemPlaygroundImageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
            return
        }

        // Flatten the payloads since getChangePayload might return a List<String>
        val flattenedPayloads = payloads.flatMap { if (it is List<*>) it else listOf(it) }

        val item = getItem(position)
        var handled = false

        if (flattenedPayloads.contains("SELECTION_CHANGED")) {
            val isMainSelected = selectedFiles.contains(item.mainFile)
            val selectionMode = isSelectionMode()
            holder.binding.selectionOverlay.visibility = if (isMainSelected) View.VISIBLE else View.GONE
            holder.binding.iconSelected.visibility = if (selectionMode) View.VISIBLE else View.GONE
            holder.binding.iconSelected.setImageResource(if (isMainSelected) R.drawable.ic_check_circle else R.drawable.ic_radio_button_unchecked)

            if (item is PlaygroundItem.Group) {
                val isDng1Selected = selectedFiles.contains(item.dng1)
                val isDng2Selected = selectedFiles.contains(item.dng2)
                holder.binding.subSelectionOverlay1.visibility = if (isDng1Selected) View.VISIBLE else View.GONE
                holder.binding.subIconSelected1.visibility = if (selectionMode) View.VISIBLE else View.GONE
                holder.binding.subIconSelected1.setImageResource(if (isDng1Selected) R.drawable.ic_check_circle else R.drawable.ic_radio_button_unchecked)

                holder.binding.subSelectionOverlay2.visibility = if (isDng2Selected) View.VISIBLE else View.GONE
                holder.binding.subIconSelected2.visibility = if (selectionMode) View.VISIBLE else View.GONE
                holder.binding.subIconSelected2.setImageResource(if (isDng2Selected) R.drawable.ic_check_circle else R.drawable.ic_radio_button_unchecked)
            }
            handled = true
        }

        if (flattenedPayloads.contains("EXPANSION_CHANGED")) {
            if (item is PlaygroundItem.Group) {
                holder.binding.subImagesContainer.visibility = if (item.isExpanded) View.VISIBLE else View.GONE
            }
            handled = true
        }

        if (flattenedPayloads.contains("MODIFIED")) {
            val context = holder.itemView.context
            when (item) {
                is PlaygroundItem.Single -> {
                    holder.jobMain?.cancel()
                    holder.jobMain = null
                    // Do not recycle bitmap to keep it as placeholder! Wait, the review says:
                    // "without cancelling any active coroutine jobs ... or recycling the existing bitmaps ... old bitmaps are overwritten and never recycled".
                    // Let's recycle it here before we load the new one, but if we recycle it, we get a blank screen.
                    // Actually, if we don't recycle it, when we assign a new bitmap, we lose the reference to the old one.
                    // The old bitmap should be recycled when the new bitmap arrives.
                    // In loadThumbnail, we have: `if (rotatedBitmap != decodedBitmap) decodedBitmap.recycle()`.
                    // And in the final UI thread: `holder.bitmapMain = decodedBitmap`.
                    // So we must recycle the previous `holder.bitmapMain`.
                    // But if we recycle it right here, the screen turns blank before the new thumbnail is generated.
                    // Let's just follow the PR review recommendation literally.
                    holder.bitmapMain?.recycle()
                    holder.bitmapMain = null

                    val file = item.file
                    val jpgFile = java.io.File(file.parent, file.nameWithoutExtension + ".jpg")
                    if (jpgFile.exists()) {
                        loadThumbnail(context, jpgFile, holder.binding.imageViewThumbnail, holder, true)
                    } else {
                        loadThumbnail(context, file, holder.binding.imageViewThumbnail, holder, true)
                    }
                }
                is PlaygroundItem.Group -> {
                    holder.jobMain?.cancel()
                    holder.jobSub1?.cancel()
                    holder.jobSub2?.cancel()
                    holder.jobMain = null
                    holder.jobSub1 = null
                    holder.jobSub2 = null
                    holder.bitmapMain?.recycle()
                    holder.bitmapSub1?.recycle()
                    holder.bitmapSub2?.recycle()
                    holder.bitmapMain = null
                    holder.bitmapSub1 = null
                    holder.bitmapSub2 = null

                    loadThumbnail(context, item.jpgFile, holder.binding.imageViewThumbnail, holder, true)
                    loadThumbnail(context, item.dng1, holder.binding.subImageView1, holder, isMain = false, isSub1 = true)
                    loadThumbnail(context, item.dng2, holder.binding.subImageView2, holder, isMain = false, isSub2 = true)
                }
            }
            handled = true
        }

        if (!handled) {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    private fun loadThumbnail(
        context: android.content.Context,
        file: File,
        imageView: android.widget.ImageView,
        holder: ViewHolder,
        isMain: Boolean,
        isSub1: Boolean = false,
        isSub2: Boolean = false
    ) {
        val currentTag = file.absolutePath + "_" + file.lastModified()
        imageView.tag = currentTag

        // Use JPG directly if available
        if (file.extension.lowercase() == "jpg") {
            Glide.with(context).load(file).signature(com.bumptech.glide.signature.ObjectKey(file.lastModified())).into(imageView)
            return
        }

        // Otherwise, it's a DNG, decode it
        val job = coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            var decodedBitmap: Bitmap? = null
            var bitmapAssigned = false
            try {
                val exifInterface = ExifInterface(file.absolutePath)
                if (exifInterface.hasThumbnail()) {
                    val thumbnailBytes = exifInterface.thumbnailBytes
                    if (thumbnailBytes != null) {
                        decodedBitmap = BitmapFactory.decodeByteArray(thumbnailBytes, 0, thumbnailBytes.size)
                    }
                }

                if (decodedBitmap == null) {
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.absolutePath, options)
                    var inSampleSize = 1
                    val maxDimension = 400
                    while ((options.outWidth / inSampleSize) > maxDimension || (options.outHeight / inSampleSize) > maxDimension) {
                        inSampleSize *= 2
                    }
                    val decodeOpts = BitmapFactory.Options().apply {
                        this.inSampleSize = inSampleSize
                        inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
                    }
                    decodedBitmap = BitmapFactory.decodeFile(file.absolutePath, decodeOpts)
                }

                ensureActive()

                // Fix orientation issues for extracted thumbnails or downsampled decodes
                if (decodedBitmap != null) {
                    val orientation = try {
                        ExifInterface(file.absolutePath).getAttributeInt(
                            ExifInterface.TAG_ORIENTATION,
                            ExifInterface.ORIENTATION_NORMAL
                        )
                    } catch (e: Exception) {
                        ExifInterface.ORIENTATION_NORMAL
                    }

                    val rotationDegrees = when (orientation) {
                        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                        else -> 0f
                    }

                    if (rotationDegrees != 0f) {
                        val matrix = android.graphics.Matrix()
                        matrix.postRotate(rotationDegrees)
                        val rotatedBitmap = android.graphics.Bitmap.createBitmap(
                            decodedBitmap!!, 0, 0, decodedBitmap!!.width, decodedBitmap!!.height, matrix, true
                        )
                        if (rotatedBitmap != decodedBitmap) {
                            decodedBitmap!!.recycle()
                            decodedBitmap = rotatedBitmap
                        }
                    }
                }

                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    if (imageView.tag == currentTag) {
                        if (decodedBitmap != null) {
                            if (isMain) holder.bitmapMain = decodedBitmap
                            else if (isSub1) holder.bitmapSub1 = decodedBitmap
                            else if (isSub2) holder.bitmapSub2 = decodedBitmap

                            bitmapAssigned = true
                            imageView.setImageBitmap(decodedBitmap)
                        } else {
                            Glide.with(context).load(file).signature(com.bumptech.glide.signature.ObjectKey(file.lastModified())).into(imageView)
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("Playground", "Failed to load thumbnail for ${file.name}", e)
            } finally {
                // If the coroutine was cancelled or the tag changed before setting, recycle the bitmap
                if (decodedBitmap != null && !bitmapAssigned) {
                    decodedBitmap?.recycle()
                }
            }
        }

        if (isMain) holder.jobMain = job
        else if (isSub1) holder.jobSub1 = job
        else if (isSub2) holder.jobSub2 = job
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        val isMainSelected = selectedFiles.contains(item.mainFile)

        // Apply aspect ratio to pre-allocate exact height to prevent StaggeredGridLayoutManager jumping
        val layoutParams = holder.binding.imageViewThumbnail.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
        if (item.aspectRatio != null) {
            layoutParams.dimensionRatio = item.aspectRatio
            layoutParams.height = 0 // 0dp means match_constraint in ConstraintLayout
        } else {
            // Fallback for extremely rare case where we couldn't parse bounds: allow it to wrap
            layoutParams.dimensionRatio = null
            layoutParams.height = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.WRAP_CONTENT
        }
        holder.binding.imageViewThumbnail.layoutParams = layoutParams

        val selectionMode = isSelectionMode()
        holder.binding.selectionOverlay.visibility = if (isMainSelected) View.VISIBLE else View.GONE
        holder.binding.iconSelected.visibility = if (selectionMode) View.VISIBLE else View.GONE
        holder.binding.iconSelected.setImageResource(if (isMainSelected) R.drawable.ic_check_circle else R.drawable.ic_radio_button_unchecked)

        holder.binding.imageViewThumbnail.setImageDrawable(null)
        val context = holder.itemView.context

        holder.jobMain?.cancel()
        holder.jobSub1?.cancel()
        holder.jobSub2?.cancel()
        holder.jobMain = null
        holder.jobSub1 = null
        holder.jobSub2 = null
        holder.bitmapMain?.recycle()
        holder.bitmapSub1?.recycle()
        holder.bitmapSub2?.recycle()
        holder.bitmapMain = null
        holder.bitmapSub1 = null
        holder.bitmapSub2 = null

        when (item) {
            is PlaygroundItem.Single -> {
                holder.binding.iconLayer.visibility = View.GONE
                holder.binding.subImagesContainer.visibility = View.GONE

                val file = item.file
                val jpgFile = File(file.parent, file.nameWithoutExtension + ".jpg")
                if (jpgFile.exists()) {
                    loadThumbnail(context, jpgFile, holder.binding.imageViewThumbnail, holder, true)
                } else {
                    loadThumbnail(context, file, holder.binding.imageViewThumbnail, holder, true)
                }
            }
            is PlaygroundItem.Group -> {
                holder.binding.iconLayer.visibility = View.VISIBLE
                holder.binding.subImagesContainer.visibility = if (item.isExpanded) View.VISIBLE else View.GONE

                loadThumbnail(context, item.jpgFile, holder.binding.imageViewThumbnail, holder, true)

                // Load sub images
                holder.binding.subImageView1.setImageDrawable(null)
                holder.binding.subImageView2.setImageDrawable(null)
                loadThumbnail(context, item.dng1, holder.binding.subImageView1, holder, isMain = false, isSub1 = true)
                loadThumbnail(context, item.dng2, holder.binding.subImageView2, holder, isMain = false, isSub2 = true)

                val isDng1Selected = selectedFiles.contains(item.dng1)
                val isDng2Selected = selectedFiles.contains(item.dng2)
                holder.binding.subSelectionOverlay1.visibility = if (isDng1Selected) View.VISIBLE else View.GONE
                holder.binding.subIconSelected1.visibility = if (selectionMode) View.VISIBLE else View.GONE
                holder.binding.subIconSelected1.setImageResource(if (isDng1Selected) R.drawable.ic_check_circle else R.drawable.ic_radio_button_unchecked)

                holder.binding.subSelectionOverlay2.visibility = if (isDng2Selected) View.VISIBLE else View.GONE
                holder.binding.subIconSelected2.visibility = if (selectionMode) View.VISIBLE else View.GONE
                holder.binding.subIconSelected2.setImageResource(if (isDng2Selected) R.drawable.ic_check_circle else R.drawable.ic_radio_button_unchecked)

                holder.binding.iconLayer.setOnClickListener {
                    onExpandClick(item, holder.bindingAdapterPosition)
                }

                holder.binding.subImageView1.setOnClickListener { onItemClick(item.dng1) }
                holder.binding.subImageView1.setOnLongClickListener {
                    onItemLongClick(item.dng1)
                    true
                }
                holder.binding.subImageView2.setOnClickListener { onItemClick(item.dng2) }
                holder.binding.subImageView2.setOnLongClickListener {
                    onItemLongClick(item.dng2)
                    true
                }
            }
        }

        holder.binding.imageViewThumbnail.setOnClickListener { onItemClick(item.mainFile) }
        holder.binding.imageViewThumbnail.setOnLongClickListener {
            onItemLongClick(item.mainFile)
            true
        }
    }



    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        val context = holder.itemView.context
        Glide.with(context).clear(holder.binding.imageViewThumbnail)
        Glide.with(context).clear(holder.binding.subImageView1)
        Glide.with(context).clear(holder.binding.subImageView2)
        holder.binding.imageViewThumbnail.setImageDrawable(null)
        holder.binding.subImageView1.setImageDrawable(null)
        holder.binding.subImageView2.setImageDrawable(null)

        holder.jobMain?.cancel()
        holder.jobSub1?.cancel()
        holder.jobSub2?.cancel()
        holder.jobMain = null
        holder.jobSub1 = null
        holder.jobSub2 = null
        holder.bitmapMain?.recycle()
        holder.bitmapSub1?.recycle()
        holder.bitmapSub2?.recycle()
        holder.bitmapMain = null
        holder.bitmapSub1 = null
        holder.bitmapSub2 = null
    }
}
