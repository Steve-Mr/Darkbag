package top.maary.darkbag.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.launch
import top.maary.darkbag.R
import top.maary.darkbag.databinding.FragmentImageViewerBinding
import top.maary.darkbag.databinding.LayoutImageAdjustmentsBinding
import top.maary.darkbag.databinding.ItemAdjustmentSliderBinding
import top.maary.darkbag.utils.DarkbagImage
import top.maary.darkbag.utils.DarkbagMetadata
import top.maary.darkbag.utils.ImageRepository
import top.maary.darkbag.utils.LutManager
import java.io.File

class ImageViewerFragment : Fragment() {

    private var _binding: FragmentImageViewerBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ImageViewerViewModel by viewModels()
    private lateinit var adapter: ImagePagerAdapter
    private lateinit var repository: ImageRepository
    private lateinit var lutManager: LutManager

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentImageViewerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        repository = ImageRepository(requireContext())
        lutManager = LutManager(requireContext())

        setupPager()
        setupControls()
        setupObservers()
        applyInsets()

        loadImages()
        listenToCaptureEvents()
    }

    private fun listenToCaptureEvents() {
        lifecycleScope.launch {
            top.maary.darkbag.processor.ColorProcessor.backgroundSaveFlow.collect {
                loadImages()
            }
        }
    }

    private fun setupPager() {
        adapter = ImagePagerAdapter(emptyList())
        binding.imagePager.adapter = adapter
        binding.imagePager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                adapter.getImage(position)?.let {
                    viewModel.setImage(it)
                    updateUIForImage(it)
                }
            }
        })
    }

    private fun setupControls() {
        binding.btnBack.setOnClickListener {
            findNavController().navigateUp()
        }

        binding.btnSave.setOnClickListener {
            save(overwrite = true)
        }

        binding.btnSaveMore.setOnClickListener {
            val popup = android.widget.PopupMenu(requireContext(), it)
            popup.menu.add("Save as copy")
            popup.setOnMenuItemClickListener { item ->
                if (item.title == "Save as copy") {
                    save(overwrite = false)
                }
                true
            }
            popup.show()
        }

        binding.fabAdjust.setOnClickListener {
            showAdjustmentsBottomSheet()
        }

        binding.viewerLutSwitcher.setOnClickListener {
            toggleLutList()
        }

        setupLutList()
    }

    private fun setupObservers() {
        viewModel.previewBitmap.observe(viewLifecycleOwner) { bitmap ->
            val currentPos = binding.imagePager.currentItem
            val rv = binding.imagePager.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView
            val holder = rv?.findViewHolderForAdapterPosition(currentPos) as? ImagePagerAdapter.ViewHolder

            if (bitmap != null) {
                holder?.binding?.ivDisplay?.setImageBitmap(bitmap)
            } else {
                adapter.getImage(currentPos)?.let {
                    holder?.binding?.ivDisplay?.let { iv ->
                        com.bumptech.glide.Glide.with(this).load(it.primaryUri).into(iv)
                    }
                }
            }
        }

        viewModel.isModified.observe(viewLifecycleOwner) { modified ->
            binding.saveButtonContainer.visibility = if (modified) View.VISIBLE else View.GONE
        }

        viewModel.currentMetadata.observe(viewLifecycleOwner) { meta ->
            binding.viewerLutSwitcher.text = meta.lutName?.substringBeforeLast(".") ?: "None"
        }
    }

    private fun setupLutList() {
        binding.viewerLutList.layoutManager = LinearLayoutManager(requireContext())
        val luts = lutManager.getLuts()
        val lutAdapter = LutAdapter(luts, viewModel.currentMetadata.value?.lutName) { name ->
            val currentMeta = viewModel.currentMetadata.value ?: DarkbagMetadata()
            viewModel.updateMetadata(currentMeta.copy(lutName = name))
            binding.viewerLutListContainer.visibility = View.GONE
        }
        binding.viewerLutList.adapter = lutAdapter

        viewModel.currentMetadata.observe(viewLifecycleOwner) {
            (binding.viewerLutList.adapter as? LutAdapter)?.updateCurrent(it.lutName)
        }
    }

    private fun toggleLutList() {
        binding.viewerLutListContainer.visibility = if (binding.viewerLutListContainer.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    private fun showAdjustmentsBottomSheet() {
        val dialog = BottomSheetDialog(requireContext(), R.style.BottomSheetDialogTheme_NoDim)
        val bsBinding = LayoutImageAdjustmentsBinding.inflate(layoutInflater)
        dialog.setContentView(bsBinding.root)
        dialog.window?.setDimAmount(0f)

        val meta = viewModel.currentMetadata.value ?: DarkbagMetadata()

        setupSlider(bsBinding.adjExposure, "Exposure", -4f, 4f, meta.exposure) { v ->
            viewModel.updateMetadata(viewModel.currentMetadata.value?.copy(exposure = v) ?: DarkbagMetadata(exposure = v))
        }
        setupSlider(bsBinding.adjHighlights, "Highlights", -1f, 1f, meta.highlights) { v ->
            viewModel.updateMetadata(viewModel.currentMetadata.value?.copy(highlights = v) ?: DarkbagMetadata(highlights = v))
        }
        setupSlider(bsBinding.adjShadows, "Shadows", -1f, 1f, meta.shadows) { v ->
            viewModel.updateMetadata(viewModel.currentMetadata.value?.copy(shadows = v) ?: DarkbagMetadata(shadows = v))
        }
        setupSlider(bsBinding.adjWhites, "Whites", -1f, 1f, meta.whites) { v ->
            viewModel.updateMetadata(viewModel.currentMetadata.value?.copy(whites = v) ?: DarkbagMetadata(whites = v))
        }
        setupSlider(bsBinding.adjBlacks, "Blacks", -1f, 1f, meta.blacks) { v ->
            viewModel.updateMetadata(viewModel.currentMetadata.value?.copy(blacks = v) ?: DarkbagMetadata(blacks = v))
        }
        setupSlider(bsBinding.adjContrast, "Contrast", 0.5f, 1.5f, meta.contrast) { v ->
            viewModel.updateMetadata(viewModel.currentMetadata.value?.copy(contrast = v) ?: DarkbagMetadata(contrast = v))
        }
        setupSlider(bsBinding.adjSaturation, "Saturation", 0f, 2f, meta.saturation) { v ->
            viewModel.updateMetadata(viewModel.currentMetadata.value?.copy(saturation = v) ?: DarkbagMetadata(saturation = v))
        }

        bsBinding.btnReset.setOnClickListener {
            viewModel.resetMetadata()
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun setupSlider(sliderBinding: ItemAdjustmentSliderBinding, label: String, from: Float, to: Float, initial: Float, onUpdate: (Float) -> Unit) {
        sliderBinding.label.text = label
        sliderBinding.value.text = "%.2f".format(initial)
        sliderBinding.slider.valueFrom = from
        sliderBinding.slider.valueTo = to
        sliderBinding.slider.value = initial
        sliderBinding.slider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                sliderBinding.value.text = "%.2f".format(value)
                onUpdate(value)
            }
        }
    }

    private fun save(overwrite: Boolean) {
        viewModel.saveImage(overwrite) { uri ->
            if (uri != null) {
                Toast.makeText(requireContext(), if (overwrite) "Saved" else "Copy saved", Toast.LENGTH_SHORT).show()
                if (!overwrite) {
                    loadImages()
                }
            } else {
                Toast.makeText(requireContext(), "Save failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.topControls) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, systemBars.top, v.paddingRight, v.paddingBottom)
            insets
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.bottomControls) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, systemBars.bottom)
            insets
        }
    }

    private fun loadImages() {
        lifecycleScope.launch {
            val images = repository.getImages()
            adapter.updateImages(images)
            if (images.isNotEmpty()) {
                viewModel.setImage(images[0])
                updateUIForImage(images[0])
            }
        }
    }

    private fun updateUIForImage(image: DarkbagImage) {
        val hasDng = image.allUris.containsKey("DNG")
        binding.fabAdjust.visibility = if (hasDng) View.VISIBLE else View.GONE
        binding.viewerLutSwitcher.visibility = if (hasDng) View.VISIBLE else View.GONE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
