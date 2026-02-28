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
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.viewpager2.widget.ViewPager2
import kotlinx.coroutines.launch
import top.maary.darkbag.R
import top.maary.darkbag.databinding.FragmentImageViewerBinding
import top.maary.darkbag.models.ImageGroup
import top.maary.darkbag.repository.ImageRepository

class ImageViewerFragment : Fragment() {

    private var _binding: FragmentImageViewerBinding? = null
    private val binding get() = _binding!!
    private val args: ImageViewerFragmentArgs by navArgs()
    private lateinit var repository: ImageRepository
    private lateinit var adapter: ImageViewerAdapter

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
        setupEdgeToEdge()
        setupToolbar()

        loadImages()
    }

    private fun loadImages(targetUri: String? = args.initialUri) {
        lifecycleScope.launch {
            val groups = repository.getGroupedImages()
            if (groups.isEmpty()) {
                findNavController().navigateUp()
                return@launch
            }
            adapter = ImageViewerAdapter(groups, lifecycleScope)
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
        }
    }

    private fun setupActionButtons() {
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
        HalfFrameShareSheet(group.dngUri1, group.dngUri2) { uris ->
            shareImages(uris)
        }.show(childFragmentManager, HalfFrameShareSheet.TAG)
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
                group.jpgUri?.let { context?.contentResolver?.delete(it, null, null) }
                group.tiffUri?.let { context?.contentResolver?.delete(it, null, null) }
                group.dngUri?.let { context?.contentResolver?.delete(it, null, null) }
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
