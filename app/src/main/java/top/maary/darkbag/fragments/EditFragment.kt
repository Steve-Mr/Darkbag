package top.maary.darkbag.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.widget.PopupMenu
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.launch
import top.maary.darkbag.MainApplication
import top.maary.darkbag.R
import top.maary.darkbag.databinding.FragmentEditBinding
import top.maary.darkbag.persistence.ImageEntity
import top.maary.darkbag.ui.LutAdapter
import top.maary.darkbag.utils.LutManager
import top.maary.darkbag.viewmodels.EditViewModel
import top.maary.darkbag.viewmodels.EditViewModelFactory

class EditFragment : Fragment() {

    private var _binding: FragmentEditBinding? = null
    private val binding get() = _binding!!

    private val viewModel: EditViewModel by viewModels {
        EditViewModelFactory((requireActivity().application as MainApplication).imageRepository)
    }

    private lateinit var lutManager: LutManager

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentEditBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        lutManager = LutManager(requireContext())

        val imageId = arguments?.getString("imageId")
        val image1Id = arguments?.getString("image1Id")
        val image2Id = arguments?.getString("image2Id")
        val isNewStitch = arguments?.getBoolean("isNewStitch") ?: false

        lifecycleScope.launch {
            val repo = (requireActivity().application as MainApplication).imageRepository
            if (isNewStitch && image1Id != null && image2Id != null) {
                val i1 = repo.getImageById(image1Id)
                val i2 = repo.getImageById(image2Id)
                if (i1 != null && i2 != null) viewModel.initStitch(i1, i2)
            } else if (imageId != null) {
                val image = repo.getImageById(imageId)
                image?.let { viewModel.setImage(it) }
            }
        }

        setupUI()
        observeViewModel()
    }

    private fun setupUI() {
        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }

        binding.lutSwitcher.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.lutSwitcher.adapter = LutAdapter(lutManager.getLuts()) { file ->
            viewModel.setLut(file?.absolutePath)
        }

        binding.fabAdjust.setOnClickListener {
            val state = viewModel.uiState.value
            state.image?.let { image ->
                val bottomSheet = AdjustmentsBottomSheet(image, state.selectedIndex == 1) { type, value ->
                    viewModel.updateAdjustment(type, value)
                }
                bottomSheet.show(childFragmentManager, "adjustments")
            }
        }

        binding.previewCard1.setOnClickListener { viewModel.setSelectedIndex(0) }
        binding.previewCard2.setOnClickListener { viewModel.setSelectedIndex(1) }

        binding.btnSave.setOnClickListener {
            viewModel.saveChanges()
            viewModel.export(requireContext(), false)
        }

        binding.btnSaveOptions.setOnClickListener { showSaveOptions(it) }

        binding.btnStitchLayout.setOnClickListener {
            viewModel.toggleLayout()
        }

        binding.btnStitchOptions.setOnClickListener {
            val state = viewModel.uiState.value
            state.image?.let { image ->
                val bottomSheet = EffectsBottomSheet(image) { type ->
                    viewModel.toggleEffect(type)
                }
                bottomSheet.show(childFragmentManager, "effects")
            }
        }
    }

    private fun showSaveOptions(view: View) {
        val popup = PopupMenu(requireContext(), view)
        popup.menuInflater.inflate(R.menu.save_options_menu, popup.menu)
        popup.setOnMenuItemClickListener {
            if (it.itemId == R.id.action_save_as) {
                viewModel.export(requireContext(), true)
                true
            } else false
        }
        popup.show()
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                val image = state.image ?: return@collect

                if (image.isStitched) {
                    binding.previewImage.visibility = View.GONE
                    binding.stitchPreviewContainer.visibility = View.VISIBLE
                    binding.btnStitchLayout.visibility = View.VISIBLE
                    binding.btnStitchOptions.visibility = View.VISIBLE
                    binding.fabAdjust.visibility = if (image.isRaw) View.VISIBLE else View.GONE
                    binding.lutSwitcher.visibility = if (image.isRaw) View.VISIBLE else View.GONE

                    binding.previewImage1.setImageBitmap(state.previewBitmap1)
                    binding.previewImage2.setImageBitmap(state.previewBitmap2)

                    binding.previewCard1.strokeWidth = if (state.selectedIndex == 0) 4 else 0
                    binding.previewCard2.strokeWidth = if (state.selectedIndex == 1) 4 else 0

                    binding.previewImage1.alpha = if (state.selectedIndex == 0) 1.0f else 0.5f
                    binding.previewImage2.alpha = if (state.selectedIndex == 1) 1.0f else 0.5f

                    binding.stitchPreviewContainer.orientation =
                        if (image.layout == "Side-by-side") LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
                    binding.btnStitchLayout.text = image.layout
                } else {
                    binding.previewImage.visibility = View.VISIBLE
                    binding.stitchPreviewContainer.visibility = View.GONE
                    binding.btnStitchLayout.visibility = View.GONE
                    binding.btnStitchOptions.visibility = View.GONE
                    binding.previewImage.setImageBitmap(state.previewBitmap1)
                }

                binding.processingOverlay.visibility = if (state.isProcessing) View.VISIBLE else View.GONE
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
