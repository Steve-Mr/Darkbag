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

    private var selectedMode = MODE_ENTIRE_GROUP // 0: Entire Group, 1: RAW only, 2: Derivatives only, 3: CinemaDNG only
    private var selectedCount = 1
    private var hasRaw = true
    private var hasDerivatives = true
    private var hasCinemaDng = false

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
        hasCinemaDng = arguments?.getBoolean(ARG_HAS_CINEMADNG, false) ?: false

        binding.tvSubtitle.text = getString(R.string.gallery_delete_summary, selectedCount)
        binding.btnConfirmDelete.text = getString(R.string.gallery_delete_confirm_format, selectedCount)

        // Adjust visibility according to format availability
        if (hasRaw && hasDerivatives) {
            binding.cardOptionGroup.visibility = View.VISIBLE
            binding.cardOptionRaw.visibility = View.VISIBLE
            binding.cardOptionJpg.visibility = View.VISIBLE
            selectedMode = MODE_ENTIRE_GROUP
        } else if (hasRaw && !hasDerivatives) {
            binding.cardOptionGroup.visibility = View.GONE
            binding.cardOptionRaw.visibility = View.VISIBLE
            binding.cardOptionJpg.visibility = View.GONE
            selectedMode = MODE_RAW_ONLY
        } else { // only Derivatives
            binding.cardOptionGroup.visibility = View.GONE
            binding.cardOptionRaw.visibility = View.GONE
            binding.cardOptionJpg.visibility = View.VISIBLE
            selectedMode = MODE_DERIVATIVES_ONLY
        }

        binding.cardOptionCdng.visibility = if (hasCinemaDng) View.VISIBLE else View.GONE

        updateSelectionState(selectedMode)

        binding.cardOptionGroup.setOnClickListener {
            selectedMode = MODE_ENTIRE_GROUP
            updateSelectionState(MODE_ENTIRE_GROUP)
        }

        binding.cardOptionRaw.setOnClickListener {
            selectedMode = MODE_RAW_ONLY
            updateSelectionState(MODE_RAW_ONLY)
        }

        binding.cardOptionJpg.setOnClickListener {
            selectedMode = MODE_DERIVATIVES_ONLY
            updateSelectionState(MODE_DERIVATIVES_ONLY)
        }

        binding.cardOptionCdng.setOnClickListener {
            selectedMode = MODE_CINEMADNG_ONLY
            updateSelectionState(MODE_CINEMADNG_ONLY)
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
        val isGroup = (mode == MODE_ENTIRE_GROUP)
        binding.cardOptionGroup.strokeColor = if (isGroup) primaryColor else outlineColor
        binding.cardOptionGroup.strokeWidth = if (isGroup) strokeSelected else strokeUnselected
        binding.cardOptionGroup.setCardBackgroundColor(if (isGroup) bgSelected else bgUnselected)
        binding.radioGroup.setImageResource(if (isGroup) R.drawable.ic_check_circle else R.drawable.ic_radio_button_unchecked)
        binding.radioGroup.setColorFilter(if (isGroup) primaryColor else onSurfaceVariant)
        binding.iconGroup.setColorFilter(if (isGroup) primaryColor else onSurfaceVariant)

        // Option 1: RAW only
        val isRaw = (mode == MODE_RAW_ONLY)
        binding.cardOptionRaw.strokeColor = if (isRaw) primaryColor else outlineColor
        binding.cardOptionRaw.strokeWidth = if (isRaw) strokeSelected else strokeUnselected
        binding.cardOptionRaw.setCardBackgroundColor(if (isRaw) bgSelected else bgUnselected)
        binding.radioRaw.setImageResource(if (isRaw) R.drawable.ic_check_circle else R.drawable.ic_radio_button_unchecked)
        binding.radioRaw.setColorFilter(if (isRaw) primaryColor else onSurfaceVariant)
        binding.iconRaw.setColorFilter(if (isRaw) primaryColor else onSurfaceVariant)

        // Option 2: JPG only
        val isJpg = (mode == MODE_DERIVATIVES_ONLY)
        binding.cardOptionJpg.strokeColor = if (isJpg) primaryColor else outlineColor
        binding.cardOptionJpg.strokeWidth = if (isJpg) strokeSelected else strokeUnselected
        binding.cardOptionJpg.setCardBackgroundColor(if (isJpg) bgSelected else bgUnselected)
        binding.radioJpg.setImageResource(if (isJpg) R.drawable.ic_check_circle else R.drawable.ic_radio_button_unchecked)
        binding.radioJpg.setColorFilter(if (isJpg) primaryColor else onSurfaceVariant)
        binding.iconJpg.setColorFilter(if (isJpg) primaryColor else onSurfaceVariant)

        // Option 3: CinemaDNG only
        val isCdng = (mode == MODE_CINEMADNG_ONLY)
        binding.cardOptionCdng.strokeColor = if (isCdng) primaryColor else outlineColor
        binding.cardOptionCdng.strokeWidth = if (isCdng) strokeSelected else strokeUnselected
        binding.cardOptionCdng.setCardBackgroundColor(if (isCdng) bgSelected else bgUnselected)
        binding.radioCdng.setImageResource(if (isCdng) R.drawable.ic_check_circle else R.drawable.ic_radio_button_unchecked)
        binding.radioCdng.setColorFilter(if (isCdng) primaryColor else onSurfaceVariant)
        binding.iconCdng.setColorFilter(if (isCdng) primaryColor else onSurfaceVariant)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "DarkbagBatchDeleteSheet"
        const val REQUEST_KEY = "darkbagBatchDeleteRequest"
        const val BUNDLE_KEY_DELETE_MODE = "delete_mode"
        const val MODE_ENTIRE_GROUP = 0
        const val MODE_RAW_ONLY = 1
        const val MODE_DERIVATIVES_ONLY = 2
        const val MODE_CINEMADNG_ONLY = 3

        private const val ARG_SELECTED_COUNT = "selected_count"
        private const val ARG_HAS_RAW = "has_raw"
        private const val ARG_HAS_DERIVATIVES = "has_derivatives"
        private const val ARG_HAS_CINEMADNG = "has_cinemadng"

        fun newInstance(
            selectedCount: Int,
            hasRaw: Boolean = true,
            hasDerivatives: Boolean = true,
            hasCinemaDng: Boolean = false
        ): DarkbagBatchDeleteSheet {
            return DarkbagBatchDeleteSheet().apply {
                arguments = Bundle().apply {
                    putInt(ARG_SELECTED_COUNT, selectedCount)
                    putBoolean(ARG_HAS_RAW, hasRaw)
                    putBoolean(ARG_HAS_DERIVATIVES, hasDerivatives)
                    putBoolean(ARG_HAS_CINEMADNG, hasCinemaDng)
                }
            }
        }
    }
}
