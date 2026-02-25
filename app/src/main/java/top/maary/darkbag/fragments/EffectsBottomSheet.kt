package top.maary.darkbag.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import top.maary.darkbag.databinding.BottomSheetEffectsBinding
import top.maary.darkbag.persistence.ImageEntity

class EffectsBottomSheet(
    private val image: ImageEntity,
    private val onEffectToggled: (String) -> Unit
) : BottomSheetDialogFragment() {

    private var _binding: BottomSheetEffectsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = BottomSheetEffectsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.cbDateStamp.isChecked = image.dateStamp
        binding.cbLightLeak.isChecked = image.lightLeak

        binding.cbDateStamp.setOnCheckedChangeListener { _, _ -> onEffectToggled("dateStamp") }
        binding.cbLightLeak.setOnCheckedChangeListener { _, _ -> onEffectToggled("lightLeak") }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
