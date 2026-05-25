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
                openViewer(selectedFiles.map { it.absolutePath })
                clearSelection()
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

    private fun openViewer(paths: List<String>) {
        val bundle = Bundle().apply {
            putStringArray("playground_dng_paths", paths.toTypedArray())
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
                        bitmap = BitmapFactory.decodeFile(file.absolutePath)
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

    class ViewHolder(val binding: ItemPlaygroundImageBinding) : RecyclerView.ViewHolder(binding.root)

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
        try {
            val exifInterface = ExifInterface(file.absolutePath)
            if (exifInterface.hasThumbnail()) {
                val thumbnailBytes = exifInterface.thumbnailBytes
                if (thumbnailBytes != null) {
                    Glide.with(holder.itemView.context)
                        .load(thumbnailBytes)
                        .into(holder.binding.imageViewThumbnail)
                }
            } else {
                // Fallback: Just let Glide try to read it (might be slow)
                Glide.with(holder.itemView.context)
                    .load(file)
                    .override(300, 400)
                    .into(holder.binding.imageViewThumbnail)
            }
        } catch (e: Exception) {
            Glide.with(holder.itemView.context).load(file).into(holder.binding.imageViewThumbnail)
        }

        holder.itemView.setOnClickListener { onItemClick(file) }
        holder.itemView.setOnLongClickListener {
            onItemLongClick(file)
            true
        }
    }

    override fun getItemCount() = files.size
}
