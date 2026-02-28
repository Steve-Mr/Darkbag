package top.maary.darkbag.fragments

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.launch
import top.maary.darkbag.databinding.LayoutHfShareSheetBinding
import top.maary.darkbag.utils.ImageUtils

class HalfFrameShareSheet(
    private val uri1: Uri?,
    private val uri2: Uri?,
    private val onShareSelected: (List<Uri>) -> Unit
) : BottomSheetDialogFragment() {

    private var _binding: LayoutHfShareSheetBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = LayoutHfShareSheetBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        lifecycleScope.launch {
            uri1?.let {
                val bit = ImageUtils.decodeDngThumbnail(requireContext(), it)
                binding.ivFrame1.setImageBitmap(bit)
            } ?: binding.ivFrame1.setImageResource(android.R.drawable.ic_menu_gallery)

            uri2?.let {
                val bit = ImageUtils.decodeDngThumbnail(requireContext(), it)
                binding.ivFrame2.setImageBitmap(bit)
            } ?: binding.ivFrame2.setImageResource(android.R.drawable.ic_menu_gallery)
        }

        binding.cardFrame1.setOnClickListener {
            uri1?.let { onShareSelected(listOf(it)) }
            dismiss()
        }

        binding.cardFrame2.setOnClickListener {
            uri2?.let { onShareSelected(listOf(it)) }
            dismiss()
        }

        binding.btnShareBoth.setOnClickListener {
            val list = mutableListOf<Uri>()
            uri1?.let { list.add(it) }
            uri2?.let { list.add(it) }
            if (list.isNotEmpty()) onShareSelected(list)
            dismiss()
        }

        binding.btnShareBoth.isEnabled = uri1 != null && uri2 != null
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "HalfFrameShareSheet"
    }
}
