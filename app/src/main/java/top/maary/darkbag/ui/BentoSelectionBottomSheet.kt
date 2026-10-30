package top.maary.darkbag.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.FragmentManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.color.MaterialColors
import top.maary.darkbag.R
import top.maary.darkbag.databinding.BottomSheetBentoSelectionBinding
import top.maary.darkbag.databinding.ItemBentoSelectionOptionBinding

/**
 * BentoSelectionBottomSheet renders a clean, cohesive Material 3 Expressive
 * Bento bottom sheet picker, replacing legacy Android alert dialogs.
 */
class BentoSelectionBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetBentoSelectionBinding? = null
    private val binding get() = _binding!!

    private var title: String = ""
    private var badge: String? = null
    private var options: List<String> = emptyList()
    private var descriptions: List<String>? = null
    private var selectedIndex: Int = -1
    private var onOptionSelectedListener: ((Int) -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetBentoSelectionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.tvSheetTitle.text = title
        if (!badge.isNullOrBlank()) {
            binding.tvSheetBadge.text = badge
            binding.tvSheetBadge.visibility = View.VISIBLE
        } else {
            binding.tvSheetBadge.visibility = View.GONE
        }

        populateOptions()
    }

    private fun populateOptions() {
        val container = binding.containerBentoOptions
        container.removeAllViews()

        val ctx = requireContext()
        val inflater = LayoutInflater.from(ctx)
        val density = resources.displayMetrics.density

        val colorPrimary = MaterialColors.getColor(binding.root, android.R.attr.colorPrimary, Color.WHITE)
        val colorPrimaryContainer = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorPrimaryContainer, Color.DKGRAY)
        val colorOnPrimaryContainer = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnPrimaryContainer, colorPrimary)
        val colorSurfaceContainerHigh = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorSurfaceContainerHigh, Color.DKGRAY)
        val colorOutlineVariant = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOutlineVariant, Color.GRAY)
        val colorOnSurface = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnSurface, Color.WHITE)
        val colorOnSurfaceVariant = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.LTGRAY)

        val stroke1dp = (1f * density).toInt()
        val stroke2dp = (2f * density).toInt()
        val radius10dp = 10f * density
        val radius14dp = 14f * density

        options.forEachIndexed { index, optionText ->
            val itemBinding = ItemBentoSelectionOptionBinding.inflate(inflater, container, false)
            val isSelected = (index == selectedIndex)

            itemBinding.tvOptionTitle.text = optionText
            val desc = descriptions?.getOrNull(index)
            if (!desc.isNullOrBlank()) {
                itemBinding.tvOptionDesc.text = desc
                itemBinding.tvOptionDesc.visibility = View.VISIBLE
            } else {
                itemBinding.tvOptionDesc.visibility = View.GONE
            }

            if (isSelected) {
                itemBinding.cardOption.radius = radius10dp
                itemBinding.cardOption.setCardBackgroundColor(ColorStateList.valueOf(colorPrimaryContainer))
                itemBinding.cardOption.strokeColor = colorPrimary
                itemBinding.cardOption.strokeWidth = stroke2dp
                itemBinding.tvOptionTitle.setTextColor(colorOnPrimaryContainer)
                itemBinding.ivOptionCheck.visibility = View.VISIBLE
                itemBinding.ivOptionCheck.imageTintList = ColorStateList.valueOf(colorOnPrimaryContainer)
                itemBinding.tvOptionBadge.visibility = View.VISIBLE
            } else {
                itemBinding.cardOption.radius = radius14dp
                itemBinding.cardOption.setCardBackgroundColor(ColorStateList.valueOf(colorSurfaceContainerHigh))
                itemBinding.cardOption.strokeColor = colorOutlineVariant
                itemBinding.cardOption.strokeWidth = stroke1dp
                itemBinding.tvOptionTitle.setTextColor(colorOnSurface)
                itemBinding.ivOptionCheck.visibility = View.GONE
                itemBinding.tvOptionBadge.visibility = View.GONE
            }

            itemBinding.cardOption.setOnClickListener {
                onOptionSelectedListener?.invoke(index)
                dismiss()
            }

            container.addView(itemBinding.root)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val TAG = "BentoSelectionSheet"

        fun show(
            fragmentManager: FragmentManager,
            title: String,
            options: List<String>,
            selectedIndex: Int,
            badge: String? = null,
            descriptions: List<String>? = null,
            onSelected: (Int) -> Unit
        ): BentoSelectionBottomSheet {
            val sheet = BentoSelectionBottomSheet().apply {
                this.title = title
                this.badge = badge
                this.options = options
                this.descriptions = descriptions
                this.selectedIndex = selectedIndex
                this.onOptionSelectedListener = onSelected
            }
            sheet.show(fragmentManager, TAG)
            return sheet
        }
    }
}
