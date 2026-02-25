package top.maary.darkbag.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import top.maary.darkbag.databinding.BottomSheetAdjustmentsBinding
import top.maary.darkbag.databinding.ViewAdjustmentSliderBinding
import top.maary.darkbag.persistence.ImageEntity

class AdjustmentsBottomSheet(
    private val image: ImageEntity,
    private val isSecond: Boolean,
    private val onAdjustmentChanged: (String, Float) -> Unit
) : BottomSheetDialogFragment() {

    private var _binding: BottomSheetAdjustmentsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = BottomSheetAdjustmentsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupSlider(binding.adjustExposure, "Exposure", if (isSecond) image.exposure2 else image.exposure, -5f, 5f) { onAdjustmentChanged("exposure", it) }
        setupSlider(binding.adjustContrast, "Contrast", if (isSecond) image.contrast2 else image.contrast, -1f, 1f) { onAdjustmentChanged("contrast", it) }
        setupSlider(binding.adjustHighlights, "Highlights", if (isSecond) image.highlights2 else image.highlights, -1f, 1f) { onAdjustmentChanged("highlights", it) }
        setupSlider(binding.adjustShadows, "Shadows", if (isSecond) image.shadows2 else image.shadows, -1f, 1f) { onAdjustmentChanged("shadows", it) }
        setupSlider(binding.adjustWhites, "Whites", if (isSecond) image.whites2 else image.whites, -1f, 1f) { onAdjustmentChanged("whites", it) }
        setupSlider(binding.adjustBlacks, "Blacks", if (isSecond) image.blacks2 else image.blacks, -1f, 1f) { onAdjustmentChanged("blacks", it) }
        setupSlider(binding.adjustSaturation, "Saturation", if (isSecond) image.saturation2 else image.saturation, -1f, 1f) { onAdjustmentChanged("saturation", it) }

        setupLogSpinner()
    }

    private fun setupLogSpinner() {
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, SettingsFragment.LOG_CURVES)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.logSpinner.adapter = adapter
        binding.logSpinner.setSelection(image.targetLog)

        binding.logSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position != image.targetLog) {
                    onAdjustmentChanged("log", position.toFloat())
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupSlider(sliderBinding: ViewAdjustmentSliderBinding, label: String, value: Float, from: Float, to: Float, onUpdate: (Float) -> Unit) {
        sliderBinding.label.text = label
        sliderBinding.slider.valueFrom = from
        sliderBinding.slider.valueTo = to
        sliderBinding.slider.value = value
        sliderBinding.value.text = "%.2f".format(value)

        sliderBinding.slider.addOnChangeListener { _, v, _ ->
            sliderBinding.value.text = "%.2f".format(v)
            onUpdate(v)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
