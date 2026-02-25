package top.maary.darkbag.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.google.android.material.carousel.CarouselLayoutManager
import top.maary.darkbag.MainApplication
import top.maary.darkbag.R
import top.maary.darkbag.databinding.FragmentGalleryBinding
import top.maary.darkbag.ui.GalleryAdapter
import top.maary.darkbag.viewmodels.GalleryViewModel
import top.maary.darkbag.viewmodels.GalleryViewModelFactory

class GalleryFragment : Fragment() {

    private var _binding: FragmentGalleryBinding? = null
    private val binding get() = _binding!!

    private val viewModel: GalleryViewModel by viewModels {
        GalleryViewModelFactory((requireActivity().application as MainApplication).imageRepository)
    }

    private val importLauncher = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        uris.forEach { viewModel.importImage(requireContext(), it) }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentGalleryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerViews()
        setupListeners()
        observeViewModel()
    }

    private fun setupRecyclerViews() {
        binding.rvDarkbag.layoutManager = CarouselLayoutManager()
        val darkbagAdapter = GalleryAdapter(isImportSection = false, onImageClick = { image ->
            val bundle = Bundle().apply { putString("imageId", image.id) }
            findNavController().navigate(R.id.action_gallery_fragment_to_edit_fragment, bundle)
        })
        binding.rvDarkbag.adapter = darkbagAdapter

        binding.rvImported.layoutManager = CarouselLayoutManager()
        val importedAdapter = GalleryAdapter(
            isImportSection = true,
            onImageClick = { image ->
                val bundle = Bundle().apply { putString("imageId", image.id) }
                findNavController().navigate(R.id.action_gallery_fragment_to_edit_fragment, bundle)
            },
            onImportClick = {
                importLauncher.launch("image/*")
            }
        )
        binding.rvImported.adapter = importedAdapter
    }

    private fun setupListeners() {
        binding.fabCamera.setOnClickListener {
            findNavController().navigate(R.id.action_gallery_fragment_to_camera_fragment)
        }
        binding.toolbar.setOnMenuItemClickListener {
            if (it.itemId == R.id.action_settings) {
                findNavController().navigate(R.id.action_gallery_fragment_to_settings_fragment)
                true
            } else false
        }
        binding.expandDarkbag.setOnClickListener {
            val bundle = Bundle().apply { putBoolean("showImported", false) }
            findNavController().navigate(R.id.action_gallery_fragment_to_full_gallery_fragment, bundle)
        }
        binding.expandImported.setOnClickListener {
            val bundle = Bundle().apply { putBoolean("showImported", true) }
            findNavController().navigate(R.id.action_gallery_fragment_to_full_gallery_fragment, bundle)
        }
    }

    private fun observeViewModel() {
        viewModel.darkbagRecent.observe(viewLifecycleOwner) {
            (binding.rvDarkbag.adapter as GalleryAdapter).submitList(it.take(10))
        }
        viewModel.importedRecent.observe(viewLifecycleOwner) {
            (binding.rvImported.adapter as GalleryAdapter).submitList(it.take(10))
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
