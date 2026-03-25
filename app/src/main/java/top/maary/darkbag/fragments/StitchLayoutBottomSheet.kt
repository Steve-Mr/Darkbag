package top.maary.darkbag.fragments

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import top.maary.darkbag.databinding.BottomSheetStitchLayoutBinding

class StitchLayoutBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetStitchLayoutBinding? = null
    private val binding get() = _binding!!

    var onLayoutSelected: ((String) -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetStitchLayoutBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnSbs.setOnClickListener {
            onLayoutSelected?.invoke("SBS")
            dismiss()
        }

        binding.btnTb.setOnClickListener {
            onLayoutSelected?.invoke("TB")
            dismiss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "StitchLayoutBottomSheet"
        fun newInstance() = StitchLayoutBottomSheet()
    }
}
