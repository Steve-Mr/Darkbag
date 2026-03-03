package top.maary.darkbag.fragments

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.launch
import top.maary.darkbag.databinding.LayoutHfShareSheetBinding
import top.maary.darkbag.utils.ImageUtils

class HalfFrameShareSheet : BottomSheetDialogFragment() {

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

        val uri1 = arguments?.getParcelable<Uri>("uri1")
        val uri2 = arguments?.getParcelable<Uri>("uri2")

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
            uri1?.let { sendResult(listOf(it)) }
        }

        binding.cardFrame2.setOnClickListener {
            uri2?.let { sendResult(listOf(it)) }
        }

        binding.btnShareBoth.setOnClickListener {
            val list = mutableListOf<Uri>()
            uri1?.let { list.add(it) }
            uri2?.let { list.add(it) }
            if (list.isNotEmpty()) sendResult(list)
        }

        binding.btnShareBoth.isEnabled = uri1 != null && uri2 != null
    }

    private fun sendResult(uris: List<Uri>) {
        setFragmentResult(REQUEST_KEY, Bundle().apply {
            putParcelableArrayList(BUNDLE_KEY_URIS, ArrayList(uris))
        })
        dismiss()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "HalfFrameShareSheet"
        const val REQUEST_KEY = "shareRequest"
        const val BUNDLE_KEY_URIS = "uris"

        fun newInstance(uri1: Uri?, uri2: Uri?): HalfFrameShareSheet {
            return HalfFrameShareSheet().apply {
                arguments = Bundle().apply {
                    putParcelable("uri1", uri1)
                    putParcelable("uri2", uri2)
                }
            }
        }
    }
}
