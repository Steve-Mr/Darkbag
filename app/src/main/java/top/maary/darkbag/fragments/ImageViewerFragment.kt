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

data class AdjustmentConfig(
    val binding: (LayoutImageAdjustmentsBinding) -> ItemAdjustmentSliderBinding,
    val label: String,
    val range: ClosedFloatingPointRange<Float>,
    val getter: (DarkbagMetadata) -> Float,
    val updater: (DarkbagMetadata, Float) -> DarkbagMetadata
)

class ImageViewerFragment : Fragment() {

    private var _binding: FragmentImageViewerBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ImageViewerViewModel by viewModels()
    private lateinit var adapter: ImagePagerAdapter
    private lateinit var repository: ImageRepository
    private lateinit var lutManager: LutManager
    private var isUpdatingFormatFromModel = false

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

        binding.formatToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked && !isUpdatingFormatFromModel) {
                val format = when(checkedId) {
                    R.id.btn_format_jpg -> "JPG"
                    R.id.btn_format_tiff -> "TIFF"
                    R.id.btn_format_dng -> "DNG"
                    else -> null
                }
                format?.let { viewModel.setFormat(it) }
            }
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
        viewModel.currentMetadata.observe(viewLifecycleOwner) { meta ->
            binding.viewerLutSwitcher.text = meta.lutName?.substringBeforeLast(".") ?: "None"
            adapter.updateParams(meta, viewModel.selectedFormat.value ?: "JPG")
        }

        viewModel.selectedFormat.observe(viewLifecycleOwner) { format ->
            val image = viewModel.currentImage.value ?: return@observe
            updateUIForImage(image)
            adapter.updateParams(viewModel.currentMetadata.value ?: DarkbagMetadata(), format)

            val buttonId = when(format) {
                "JPG" -> R.id.btn_format_jpg
                "TIFF" -> R.id.btn_format_tiff
                "DNG" -> R.id.btn_format_dng
                else -> View.NO_ID
            }
            if (buttonId != View.NO_ID) {
                isUpdatingFormatFromModel = true
                binding.formatToggleGroup.check(buttonId)
                isUpdatingFormatFromModel = false
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
            viewModel.updateMetadata { it.copy(lutName = name) }
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

    private val adjustmentConfigs = listOf(
        AdjustmentConfig({ it.adjExposure }, "Exposure", -4f..4f, { it.exposure }, { m, v -> m.copy(exposure = v) }),
        AdjustmentConfig({ it.adjHighlights }, "Highlights", -1f..1f, { it.highlights }, { m, v -> m.copy(highlights = v) }),
        AdjustmentConfig({ it.adjShadows }, "Shadows", -1f..1f, { it.shadows }, { m, v -> m.copy(shadows = v) }),
        AdjustmentConfig({ it.adjWhites }, "Whites", -1f..1f, { it.whites }, { m, v -> m.copy(whites = v) }),
        AdjustmentConfig({ it.adjBlacks }, "Blacks", -1f..1f, { it.blacks }, { m, v -> m.copy(blacks = v) }),
        AdjustmentConfig({ it.adjContrast }, "Contrast", 0.5f..1.5f, { it.contrast }, { m, v -> m.copy(contrast = v) }),
        AdjustmentConfig({ it.adjSaturation }, "Saturation", 0f..2f, { it.saturation }, { m, v -> m.copy(saturation = v) })
    )

    private fun showAdjustmentsBottomSheet() {
        val dialog = BottomSheetDialog(requireContext(), R.style.BottomSheetDialogTheme_NoDim)
        val bsBinding = LayoutImageAdjustmentsBinding.inflate(layoutInflater)
        dialog.setContentView(bsBinding.root)
        dialog.window?.setDimAmount(0f)

        val meta = viewModel.currentMetadata.value ?: DarkbagMetadata()

        adjustmentConfigs.forEach { config ->
            setupSlider(config.binding(bsBinding), config.label, config.range, config.getter(meta)) { v ->
                viewModel.updateMetadata { config.updater(it, v) }
            }
        }

        bsBinding.btnReset.setOnClickListener {
            viewModel.resetMetadata()
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun setupSlider(sliderBinding: ItemAdjustmentSliderBinding, label: String, range: ClosedFloatingPointRange<Float>, initial: Float, onUpdate: (Float) -> Unit) {
        sliderBinding.label.text = label
        sliderBinding.value.text = "%.2f".format(initial)
        sliderBinding.slider.valueFrom = range.start
        sliderBinding.slider.valueTo = range.endInclusive
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
        val isJpg = viewModel.selectedFormat.value == "JPG"

        binding.fabAdjust.visibility = if (hasDng && isJpg) View.VISIBLE else View.GONE
        binding.viewerLutSwitcher.visibility = if (hasDng && isJpg) View.VISIBLE else View.GONE

        binding.btnFormatJpg.visibility = if (image.allUris.containsKey("JPG")) View.VISIBLE else View.GONE
        binding.btnFormatTiff.visibility = if (image.allUris.containsKey("TIFF")) View.VISIBLE else View.GONE
        binding.btnFormatDng.visibility = if (image.allUris.containsKey("DNG")) View.VISIBLE else View.GONE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
