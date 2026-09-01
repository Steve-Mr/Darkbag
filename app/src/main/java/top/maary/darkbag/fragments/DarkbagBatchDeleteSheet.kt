package top.maary.darkbag.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.setFragmentResult
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.color.MaterialColors
import top.maary.darkbag.R
import top.maary.darkbag.databinding.LayoutDarkbagBatchDeleteSheetBinding

class DarkbagBatchDeleteSheet : BottomSheetDialogFragment() {

    private var _binding: LayoutDarkbagBatchDeleteSheetBinding? = null
    private val binding get() = _binding!!

    private var selectedMode = 0 // 0: Entire Group, 1: RAW only, 2: Derivatives only
    private var selectedCount = 1
    private var hasRaw = true
    private var hasDerivatives = true

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = LayoutDarkbagBatchDeleteSheetBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        selectedCount = arguments?.getInt(ARG_SELECTED_COUNT, 1) ?: 1
        hasRaw = arguments?.getBoolean(ARG_HAS_RAW, true) ?: true
        hasDerivatives = arguments?.getBoolean(ARG_HAS_DERIVATIVES, true) ?: true

        binding.tvSubtitle.text = getString(R.string.gallery_delete_summary, selectedCount)
        binding.btnConfirmDelete.text = getString(R.string.gallery_delete_confirm_format, selectedCount)

        // Adjust visibility according to format availability
        if (hasRaw && hasDerivatives) {
            binding.cardOptionGroup.visibility = View.VISIBLE
            binding.cardOptionRaw.visibility = View.VISIBLE
            binding.cardOptionJpg.visibility = View.VISIBLE
            selectedMode = 0
        } else if (hasRaw && !hasDerivatives) {
            binding.cardOptionGroup.visibility = View.GONE
            binding.cardOptionRaw.visibility = View.VISIBLE
            binding.cardOptionJpg.visibility = View.GONE
            selectedMode = 1
        } else { // only Derivatives
            binding.cardOptionGroup.visibility = View.GONE
            binding.cardOptionRaw.visibility = View.GONE
            binding.cardOptionJpg.visibility = View.VISIBLE
            selectedMode = 2
        }

        updateSelectionState(selectedMode)

        binding.cardOptionGroup.setOnClickListener {
            selectedMode = 0
            updateSelectionState(0)
        }

        binding.cardOptionRaw.setOnClickListener {
            selectedMode = 1
            updateSelectionState(1)
        }

        binding.cardOptionJpg.setOnClickListener {
            selectedMode = 2
            updateSelectionState(2)
        }

        binding.btnConfirmDelete.setOnClickListener {
            setFragmentResult(REQUEST_KEY, Bundle().apply {
                putInt(BUNDLE_KEY_DELETE_MODE, selectedMode)
            })
            dismiss()
        }

        binding.btnCancel.setOnClickListener {
            dismiss()
        }
    }

    private fun updateSelectionState(mode: Int) {
        val primaryColor = MaterialColors.getColor(binding.root, android.R.attr.colorPrimary)
        val outlineColor = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOutlineVariant)
        val onSurfaceVariant = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnSurfaceVariant)
        val bgSelected = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorSurfaceContainerHigh)
        val bgUnselected = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorSurfaceContainerLow)

        val density = resources.displayMetrics.density
        val strokeSelected = (1.5f * density).toInt()
        val strokeUnselected = (1f * density).toInt()

        // Option 0: Entire group
        val isGroup = (mode == 0)
        binding.cardOptionGroup.strokeColor = if (isGroup) primaryColor else outlineColor
        binding.cardOptionGroup.strokeWidth = if (isGroup) strokeSelected else strokeUnselected
        binding.cardOptionGroup.setCardBackgroundColor(if (isGroup) bgSelected else bgUnselected)
        binding.radioGroup.setImageResource(if (isGroup) R.drawable.ic_check_circle else R.drawable.ic_radio_button_unchecked)
        binding.radioGroup.setColorFilter(if (isGroup) primaryColor else onSurfaceVariant)
        binding.iconGroup.setColorFilter(if (isGroup) primaryColor else onSurfaceVariant)

        // Option 1: RAW only
        val isRaw = (mode == 1)
        binding.cardOptionRaw.strokeColor = if (isRaw) primaryColor else outlineColor
        binding.cardOptionRaw.strokeWidth = if (isRaw) strokeSelected else strokeUnselected
        binding.cardOptionRaw.setCardBackgroundColor(if (isRaw) bgSelected else bgUnselected)
        binding.radioRaw.setImageResource(if (isRaw) R.drawable.ic_check_circle else R.drawable.ic_radio_button_unchecked)
        binding.radioRaw.setColorFilter(if (isRaw) primaryColor else onSurfaceVariant)
        binding.iconRaw.setColorFilter(if (isRaw) primaryColor else onSurfaceVariant)

        // Option 2: JPG only
        val isJpg = (mode == 2)
        binding.cardOptionJpg.strokeColor = if (isJpg) primaryColor else outlineColor
        binding.cardOptionJpg.strokeWidth = if (isJpg) strokeSelected else strokeUnselected
        binding.cardOptionJpg.setCardBackgroundColor(if (isJpg) bgSelected else bgUnselected)
        binding.radioJpg.setImageResource(if (isJpg) R.drawable.ic_check_circle else R.drawable.ic_radio_button_unchecked)
        binding.radioJpg.setColorFilter(if (isJpg) primaryColor else onSurfaceVariant)
        binding.iconJpg.setColorFilter(if (isJpg) primaryColor else onSurfaceVariant)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "DarkbagBatchDeleteSheet"
        const val REQUEST_KEY = "darkbagBatchDeleteRequest"
        const val BUNDLE_KEY_DELETE_MODE = "delete_mode"
        private const val ARG_SELECTED_COUNT = "selected_count"
        private const val ARG_HAS_RAW = "has_raw"
        private const val ARG_HAS_DERIVATIVES = "has_derivatives"

        fun newInstance(selectedCount: Int, hasRaw: Boolean = true, hasDerivatives: Boolean = true): DarkbagBatchDeleteSheet {
            return DarkbagBatchDeleteSheet().apply {
                arguments = Bundle().apply {
                    putInt(ARG_SELECTED_COUNT, selectedCount)
                    putBoolean(ARG_HAS_RAW, hasRaw)
                    putBoolean(ARG_HAS_DERIVATIVES, hasDerivatives)
                }
            }
        }
    }
}
