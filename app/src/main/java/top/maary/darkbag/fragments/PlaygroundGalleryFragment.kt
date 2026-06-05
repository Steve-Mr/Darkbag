package top.maary.darkbag.fragments

import android.net.Uri
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
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
import top.maary.darkbag.databinding.ItemPlaygroundImageBinding
import top.maary.darkbag.utils.ImageSaver
import java.util.UUID


sealed class PlaygroundItem {
    abstract val mainFile: File

    data class Single(val file: File) : PlaygroundItem() {
        override val mainFile: File = file
    }

    data class Group(
        val jpgFile: File,
        val dng1: File,
        val dng2: File,
        var isExpanded: Boolean = false
    ) : PlaygroundItem() {
        override val mainFile: File = jpgFile
    }
}

class PlaygroundGalleryFragment : Fragment() {

    private var _binding: FragmentPlaygroundGalleryBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: PlaygroundAdapter
    private val items = mutableListOf<PlaygroundItem>()
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

        binding.toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }

        adapter = PlaygroundAdapter(
            coroutineScope = viewLifecycleOwner.lifecycleScope,
            items = items,
            selectedFiles = selectedFiles,
            onItemClick = { file ->
                if (isSelectionMode) {
                    toggleSelection(file)
                } else {
                    openViewer(listOf(file.absolutePath))
                }
            },
            onItemLongClick = { file ->
                if (!isSelectionMode) {
                    isSelectionMode = true
                }
                toggleSelection(file)
            },
            onExpandClick = { group, position ->
                if (position != RecyclerView.NO_POSITION) {
                    group.isExpanded = !group.isExpanded
                    adapter.notifyItemChanged(position, "EXPAND_CHANGED")
                }
            }
        )

        binding.recyclerView.layoutManager = StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL)
        binding.recyclerView.adapter = adapter
        val mainActivity = activity as? top.maary.darkbag.MainActivity
        mainActivity?.let {
            viewLifecycleOwner.lifecycleScope.launch {
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
                    newItems.add(PlaygroundItem.Group(groupJpg, dng1, dng2))
                    processedBases.add(groupBase)
                    processedBases.add("${groupBase}_1")
                    processedBases.add("${groupBase}_2")
                } else if (file.extension.lowercase() == "dng") {
                     // Only add as single if it's a DNG and wasn't processed as part of a group
                     if (!processedBases.contains(baseName)) {
                         newItems.add(PlaygroundItem.Single(file))
                         processedBases.add(baseName)
                     }
                }
            }

            val sortedItems = newItems.sortedByDescending { it.mainFile.lastModified() }

            withContext(Dispatchers.Main) {
                items.clear()
                items.addAll(sortedItems)
                adapter.notifyDataSetChanged()
                updateEmptyState()
            }
        }
    }

    private fun updateEmptyState() {
        binding.emptyStateText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
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
        val index = items.indexOfFirst { item ->
            when (item) {
                is PlaygroundItem.Single -> item.file == file
                is PlaygroundItem.Group -> item.jpgFile == file || item.dng1 == file || item.dng2 == file
            }
        }

        if (index != -1) {
            val item = items[index]
            if (item is PlaygroundItem.Group && item.jpgFile == file && selectedFiles.contains(file)) {
                // If the user long clicked the group main image to select it, auto-expand so they see the sub-items too
                if (!item.isExpanded) {
                    item.isExpanded = true
                    adapter.notifyItemChanged(index, "EXPAND_CHANGED")
                }
            }
            adapter.notifyItemChanged(index, "SELECTION_CHANGED")
        }
        updateBottomBar()
    }

    private fun clearSelection() {
        val oldSelections = selectedFiles.toList()
        selectedFiles.clear()
        isSelectionMode = false
        oldSelections.forEach { file ->
            val index = items.indexOfFirst { item ->
                when (item) {
                    is PlaygroundItem.Single -> item.file == file
                    is PlaygroundItem.Group -> item.jpgFile == file || item.dng1 == file || item.dng2 == file
                }
            }
            if (index != -1) {
                adapter.notifyItemChanged(index, "SELECTION_CHANGED")
            }
        }
        updateBottomBar()
    }

    private fun updateBottomBar() {
        if (isSelectionMode) {
            binding.bottomAppBar.visibility = View.VISIBLE
        view?.findViewById<android.view.View>(R.id.floating_toolbar)?.visibility = View.GONE
            binding.fabAdd.visibility = View.GONE

            // Only allow merge if exactly 2 individual DNGs are selected
            val allDngs = selectedFiles.all { it.extension.lowercase() == "dng" }
            binding.btnMerge.visibility = if (selectedFiles.size == 2 && allDngs) View.VISIBLE else View.GONE

            binding.toolbar.title = "${selectedFiles.size} selected"
        } else {
            binding.bottomAppBar.visibility = View.GONE
        val prefs = requireContext().getSharedPreferences(top.maary.darkbag.fragments.SettingsFragment.PREFS_NAME, android.content.Context.MODE_PRIVATE)
        val showToolbar = prefs.getBoolean(top.maary.darkbag.fragments.SettingsFragment.KEY_SHOW_FLOATING_TOOLBAR, true)
        val enableCamera = prefs.getBoolean(top.maary.darkbag.fragments.SettingsFragment.KEY_ENABLE_CAMERA, true)
        val enablePlayground = prefs.getBoolean(top.maary.darkbag.fragments.SettingsFragment.KEY_ENABLE_PLAYGROUND, true)
        view?.findViewById<android.view.View>(R.id.floating_toolbar)?.visibility = if (showToolbar && enableCamera && enablePlayground) View.VISIBLE else View.GONE
            binding.fabAdd.visibility = View.VISIBLE
            binding.toolbar.title = "Playground"
        }
    }

    private fun openViewer(paths: List<String>, hfLayout: String? = null) {
        // Find if the path belongs to a Group. If it's a group's JPG, pass all its DNGs to the viewer.
        val finalPaths = mutableListOf<String>()
        for (path in paths) {
            val file = File(path)
            val item = items.find {
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
        lifecycleScope.launch(Dispatchers.IO) {
            val dir = getPlaygroundDir()
            var importedCount = 0
            for (uri in uris) {
                try {
                    val fileName = getFileName(uri) ?: "imported_${UUID.randomUUID()}.dng"
                    val destFile = File(dir, fileName)
                    requireContext().contentResolver.openInputStream(uri)?.use { input ->
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
                Toast.makeText(requireContext(), "Imported $importedCount files", Toast.LENGTH_SHORT).show()
                loadFiles()
            }
        }
    }

    private fun getFileName(uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            requireContext().contentResolver.query(uri, null, null, null, null)?.use { cursor ->
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

        Toast.makeText(requireContext(), "Exporting ${filesToExport.size} files...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch(Dispatchers.IO) {
            var successCount = 0
            for (file in filesToExport) {
                try {
                    var bitmap: Bitmap? = null

                    // If it's a JPG (e.g. from a HalfFrameGroup), try decoding it directly first
                    if (file.extension.lowercase() == "jpg") {
                         val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                         BitmapFactory.decodeFile(file.absolutePath, bounds)
                         var inSampleSize = 1
                         val maxDimension = 2048
                         while ((bounds.outWidth / inSampleSize) > maxDimension || (bounds.outHeight / inSampleSize) > maxDimension) {
                             inSampleSize *= 2
                         }
                         val decodeOpts = BitmapFactory.Options().apply {
                             this.inSampleSize = inSampleSize
                             inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
                         }
                         bitmap = BitmapFactory.decodeFile(file.absolutePath, decodeOpts)
                    } else {
                        // Original DNG thumbnail extraction logic
                        val exifInterface = ExifInterface(file.absolutePath)
                        if (exifInterface.hasThumbnail()) {
                            val thumbnailBytes = exifInterface.thumbnailBytes
                            if (thumbnailBytes != null) {
                                bitmap = BitmapFactory.decodeByteArray(thumbnailBytes, 0, thumbnailBytes.size)
                            }
                        }
                        if (bitmap == null) {
                            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeFile(file.absolutePath, bounds)

                            var inSampleSize = 1
                            val maxDimension = 2048
                            while ((bounds.outWidth / inSampleSize) > maxDimension || (bounds.outHeight / inSampleSize) > maxDimension) {
                                inSampleSize *= 2
                            }

                            val decodeOpts = BitmapFactory.Options().apply {
                                this.inSampleSize = inSampleSize
                                inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
                            }
                            bitmap = BitmapFactory.decodeFile(file.absolutePath, decodeOpts)
                        }
                    }

                    if (bitmap != null) {
                        val uri = ImageSaver.saveProcessedImage(
                            context = requireContext(),
                            inputBitmap = bitmap,
                            bmpPath = null,
                            rotationDegrees = 0,
                            zoomFactor = 1.0f,
                            baseName = file.nameWithoutExtension,
                            linearDngPath = null,
                            saveJpg = true,
                            saveRaw = false
                        )
                        if (uri != null) successCount++
                        bitmap.recycle()
                    }
                } catch (e: Exception) {
                    Log.e("Playground", "Failed to export ${file.name}", e)
                }
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(requireContext(), "Exported $successCount files", Toast.LENGTH_SHORT).show()
                clearSelection()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Trigger a fresh load to catch any changes from PlaygroundViewerFragment
        loadFiles()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

class PlaygroundAdapter(
    private val coroutineScope: kotlinx.coroutines.CoroutineScope,
    private val items: List<PlaygroundItem>,
    private val selectedFiles: Set<File>,
    private val onItemClick: (File) -> Unit,
    private val onItemLongClick: (File) -> Unit,
    private val onExpandClick: (PlaygroundItem.Group, Int) -> Unit
) : RecyclerView.Adapter<PlaygroundAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemPlaygroundImageBinding) : RecyclerView.ViewHolder(binding.root) {
        var job: kotlinx.coroutines.Job? = null
        var bitmap: Bitmap? = null
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

        val item = items[position]
        var handled = false

        if (payloads.contains("SELECTION_CHANGED")) {
            val isMainSelected = selectedFiles.contains(item.mainFile)
            holder.binding.selectionOverlay.visibility = if (isMainSelected) View.VISIBLE else View.GONE
            holder.binding.iconSelected.visibility = if (isMainSelected) View.VISIBLE else View.GONE

            if (item is PlaygroundItem.Group) {
                val isDng1Selected = selectedFiles.contains(item.dng1)
                val isDng2Selected = selectedFiles.contains(item.dng2)
                holder.binding.subSelectionOverlay1.visibility = if (isDng1Selected) View.VISIBLE else View.GONE
                holder.binding.subIconSelected1.visibility = if (isDng1Selected) View.VISIBLE else View.GONE
                holder.binding.subSelectionOverlay2.visibility = if (isDng2Selected) View.VISIBLE else View.GONE
                holder.binding.subIconSelected2.visibility = if (isDng2Selected) View.VISIBLE else View.GONE
            }
            handled = true
        }

        if (payloads.contains("EXPAND_CHANGED")) {
            if (item is PlaygroundItem.Group) {
                holder.binding.subImagesContainer.visibility = if (item.isExpanded) View.VISIBLE else View.GONE
            }
            handled = true
        }

        if (!handled) {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    private fun loadThumbnail(context: android.content.Context, file: File, imageView: android.widget.ImageView, holder: ViewHolder) {
        val currentTag = file.absolutePath
        imageView.tag = currentTag

        // Use JPG directly if available
        if (file.extension.lowercase() == "jpg") {
            Glide.with(context).load(file).into(imageView)
            return
        }

        // Otherwise, it's a DNG, decode it
        holder.job = coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            var bitmap: Bitmap? = null
            try {
                val exifInterface = ExifInterface(file.absolutePath)
                if (exifInterface.hasThumbnail()) {
                    val thumbnailBytes = exifInterface.thumbnailBytes
                    if (thumbnailBytes != null) {
                        bitmap = BitmapFactory.decodeByteArray(thumbnailBytes, 0, thumbnailBytes.size)
                    }
                }

                if (bitmap == null) {
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
                    bitmap = BitmapFactory.decodeFile(file.absolutePath, decodeOpts)
                }

                ensureActive()

                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    if (imageView.tag == currentTag) {
                        if (bitmap != null) {
                            // Can't reliably keep reference for multiple views in one holder,
                            // so we rely on Glide/ImageView to handle if we overwrite
                            imageView.setImageBitmap(bitmap)
                        } else {
                            Glide.with(context).load(file).into(imageView)
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("Playground", "Failed to load thumbnail for ${file.name}", e)
            } finally {
                // If we didn't set it to view (handled by imageView internally), we'd recycle, but actually we don't want to break it if it's set.
                // In a robust implementation, we'd cache these properly.
            }
        }
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val isMainSelected = selectedFiles.contains(item.mainFile)

        holder.binding.selectionOverlay.visibility = if (isMainSelected) View.VISIBLE else View.GONE
        holder.binding.iconSelected.visibility = if (isMainSelected) View.VISIBLE else View.GONE

        holder.binding.imageViewThumbnail.setImageDrawable(null)
        val context = holder.itemView.context

        holder.job?.cancel()
        holder.bitmap?.recycle()
        holder.bitmap = null

        when (item) {
            is PlaygroundItem.Single -> {
                holder.binding.iconLayer.visibility = View.GONE
                holder.binding.subImagesContainer.visibility = View.GONE

                val file = item.file
                val jpgFile = File(file.parent, file.nameWithoutExtension + ".jpg")
                if (jpgFile.exists()) {
                    loadThumbnail(context, jpgFile, holder.binding.imageViewThumbnail, holder)
                } else {
                    loadThumbnail(context, file, holder.binding.imageViewThumbnail, holder)
                }
            }
            is PlaygroundItem.Group -> {
                holder.binding.iconLayer.visibility = View.VISIBLE
                holder.binding.subImagesContainer.visibility = if (item.isExpanded) View.VISIBLE else View.GONE

                loadThumbnail(context, item.jpgFile, holder.binding.imageViewThumbnail, holder)

                // Load sub images
                holder.binding.subImageView1.setImageDrawable(null)
                holder.binding.subImageView2.setImageDrawable(null)
                loadThumbnail(context, item.dng1, holder.binding.subImageView1, holder)
                loadThumbnail(context, item.dng2, holder.binding.subImageView2, holder)

                val isDng1Selected = selectedFiles.contains(item.dng1)
                val isDng2Selected = selectedFiles.contains(item.dng2)
                holder.binding.subSelectionOverlay1.visibility = if (isDng1Selected) View.VISIBLE else View.GONE
                holder.binding.subIconSelected1.visibility = if (isDng1Selected) View.VISIBLE else View.GONE
                holder.binding.subSelectionOverlay2.visibility = if (isDng2Selected) View.VISIBLE else View.GONE
                holder.binding.subIconSelected2.visibility = if (isDng2Selected) View.VISIBLE else View.GONE

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

    override fun getItemCount() = items.size

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        holder.job?.cancel()
        Glide.with(holder.itemView.context).clear(holder.binding.imageViewThumbnail)
        holder.binding.imageViewThumbnail.setImageDrawable(null)
        holder.bitmap?.recycle()
        holder.bitmap = null
    }
}
