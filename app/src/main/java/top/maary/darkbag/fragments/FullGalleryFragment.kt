package top.maary.darkbag.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.maary.darkbag.MainApplication
import top.maary.darkbag.R
import top.maary.darkbag.databinding.FragmentFullGalleryBinding
import top.maary.darkbag.ui.StaggeredGalleryAdapter
import top.maary.darkbag.viewmodels.GalleryViewModel
import top.maary.darkbag.viewmodels.GalleryViewModelFactory
import java.io.File

class FullGalleryFragment : Fragment() {

    private var _binding: FragmentFullGalleryBinding? = null
    private val binding get() = _binding!!

    private val viewModel: GalleryViewModel by viewModels {
        GalleryViewModelFactory((requireActivity().application as MainApplication).imageRepository)
    }

    private lateinit var adapter: StaggeredGalleryAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentFullGalleryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val showImported = arguments?.getBoolean("showImported") ?: false
        binding.toolbar.title = if (showImported) "Imported" else "Darkbag"

        setupRecyclerView()
        setupListeners()
        observeViewModel(showImported)
    }

    private fun setupRecyclerView() {
        adapter = StaggeredGalleryAdapter(
            onImageClick = { image ->
                val bundle = Bundle().apply { putString("imageId", image.id) }
                findNavController().navigate(R.id.action_full_gallery_fragment_to_edit_fragment, bundle)
            },
            onLongClick = { image ->
                binding.bottomAppBar.visibility = View.VISIBLE
            },
            onSelectionChange = { selectedIds ->
                if (selectedIds.isEmpty()) {
                    binding.bottomAppBar.visibility = View.GONE
                    adapter.isSelectionMode = false
                } else {
                    binding.btnStitch.visibility = if (selectedIds.size == 2) View.VISIBLE else View.GONE
                }
            }
        )
        binding.rvFullGallery.layoutManager = StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL)
        binding.rvFullGallery.adapter = adapter
    }

    private fun setupListeners() {
        binding.toolbar.setNavigationOnClickListener {
            if (adapter.isSelectionMode) {
                adapter.isSelectionMode = false
                binding.bottomAppBar.visibility = View.GONE
            } else {
                findNavController().navigateUp()
            }
        }

        binding.btnStitch.setOnClickListener {
            val selected = adapter.getSelectedImages()
            if (selected.size == 2) {
                val bundle = Bundle().apply {
                    putString("image1Id", selected[0].id)
                    putString("image2Id", selected[1].id)
                    putBoolean("isNewStitch", true)
                }
                findNavController().navigate(R.id.action_full_gallery_fragment_to_edit_fragment, bundle)
            }
        }

        binding.btnBatchExport.setOnClickListener {
            val selected = adapter.getSelectedImages()

            lifecycleScope.launch(Dispatchers.IO) {
                selected.forEach { image ->
                    top.maary.darkbag.utils.ImageExporter.exportImage(requireContext(), image, true)
                }
                withContext(Dispatchers.Main) {
                    adapter.isSelectionMode = false
                    binding.bottomAppBar.visibility = View.GONE
                }
            }
        }

        binding.btnBatchDelete.setOnClickListener {
            val selected = adapter.getSelectedImages()
            lifecycleScope.launch {
                selected.forEach { image ->
                    val mainApp = requireActivity().application as MainApplication
                    mainApp.imageRepository.delete(image)
                    // If it's an imported file in private storage, delete it
                    if (image.isImported) {
                        File(image.path).delete()
                    }
                    // For MediaStore images, we might want to delete from MediaStore too,
                    // but usually apps only delete their own reference unless requested.
                    // Given the prompt, I'll stick to deleting our record and private files.
                }
                adapter.isSelectionMode = false
                binding.bottomAppBar.visibility = View.GONE
            }
        }
    }

    private fun observeViewModel(showImported: Boolean) {
        if (showImported) {
            viewModel.importedRecent.observe(viewLifecycleOwner) {
                adapter.submitList(it)
            }
        } else {
            viewModel.darkbagRecent.observe(viewLifecycleOwner) {
                adapter.submitList(it)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
