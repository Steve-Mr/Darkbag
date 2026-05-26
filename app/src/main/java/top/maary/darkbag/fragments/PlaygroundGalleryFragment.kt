package top.maary.darkbag.fragments

import android.net.Uri
import android.os.Bundle
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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

class PlaygroundGalleryFragment : Fragment() {

    private var _binding: FragmentPlaygroundGalleryBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: PlaygroundAdapter
    private val dngFiles = mutableListOf<File>()
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
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }

        adapter = PlaygroundAdapter(
            files = dngFiles,
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
                    toggleSelection(file)
                }
            }
        )

        binding.recyclerView.layoutManager = GridLayoutManager(requireContext(), 3)
        binding.recyclerView.adapter = adapter

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
            val files = dir.listFiles { file -> file.extension.lowercase() == "dng" }?.sortedByDescending { it.lastModified() } ?: emptyList()

            withContext(Dispatchers.Main) {
                dngFiles.clear()
                dngFiles.addAll(files)
                adapter.notifyDataSetChanged()
                updateEmptyState()
            }
        }
    }

    private fun updateEmptyState() {
        binding.emptyStateText.visibility = if (dngFiles.isEmpty()) View.VISIBLE else View.GONE
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

        adapter.notifyDataSetChanged()
        updateBottomBar()
    }

    private fun clearSelection() {
        selectedFiles.clear()
        isSelectionMode = false
        adapter.notifyDataSetChanged()
        updateBottomBar()
    }

    private fun updateBottomBar() {
        if (isSelectionMode) {
            binding.bottomAppBar.visibility = View.VISIBLE
            binding.fabAdd.visibility = View.GONE
            binding.btnMerge.visibility = if (selectedFiles.size == 2) View.VISIBLE else View.GONE
            binding.toolbar.title = "${selectedFiles.size} selected"
        } else {
            binding.bottomAppBar.visibility = View.GONE
            binding.fabAdd.visibility = View.VISIBLE
            binding.toolbar.title = "Playground"
        }
    }

    private fun openViewer(paths: List<String>, hfLayout: String? = null) {
        val bundle = Bundle().apply {
            putStringArray("playground_dng_paths", paths.toTypedArray())
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
                    val exifInterface = ExifInterface(file.absolutePath)
                    var bitmap: Bitmap? = null
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

class PlaygroundAdapter(
    private val files: List<File>,
    private val selectedFiles: Set<File>,
    private val onItemClick: (File) -> Unit,
    private val onItemLongClick: (File) -> Unit
) : RecyclerView.Adapter<PlaygroundAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemPlaygroundImageBinding) : RecyclerView.ViewHolder(binding.root) {
        var job: kotlinx.coroutines.Job? = null
        var bitmap: Bitmap? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemPlaygroundImageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val file = files[position]
        val isSelected = selectedFiles.contains(file)

        holder.binding.selectionOverlay.visibility = if (isSelected) View.VISIBLE else View.GONE
        holder.binding.iconSelected.visibility = if (isSelected) View.VISIBLE else View.GONE

        // Extract and load EXIF thumbnail for DNG
        holder.binding.imageViewThumbnail.setImageDrawable(null)
        val context = holder.itemView.context
        val currentTag = file.absolutePath
        holder.binding.imageViewThumbnail.tag = currentTag

        holder.job?.cancel()
        holder.bitmap?.recycle()
        holder.bitmap = null
        holder.job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
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
                    // Fallback: Decode a small version directly from DNG
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
            } catch (e: Exception) {
                Log.e("Playground", "Failed to load thumbnail for ${file.name}", e)
            }

            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (holder.binding.imageViewThumbnail.tag == currentTag) {
                    if (bitmap != null) {
                        holder.bitmap = bitmap
                        holder.binding.imageViewThumbnail.setImageBitmap(bitmap)
                    } else {
                        // Ultimate fallback
                        Glide.with(context).load(file).into(holder.binding.imageViewThumbnail)
                    }
                } else {
                    bitmap?.recycle()
                }
            }
        }

        holder.itemView.setOnClickListener { onItemClick(file) }
        holder.itemView.setOnLongClickListener {
            onItemLongClick(file)
            true
        }
    }

    override fun getItemCount() = files.size

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        holder.job?.cancel()
        Glide.with(holder.itemView.context).clear(holder.binding.imageViewThumbnail)
        holder.binding.imageViewThumbnail.setImageDrawable(null)
        holder.bitmap?.recycle()
        holder.bitmap = null
    }
}
