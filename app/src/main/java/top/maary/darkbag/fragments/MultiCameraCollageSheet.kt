package top.maary.darkbag.fragments

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.chip.Chip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.maary.darkbag.R
import top.maary.darkbag.databinding.LayoutMultiCameraCollageSheetBinding
import top.maary.darkbag.models.ImageGroup
import top.maary.darkbag.models.MultiCameraLensItem
import top.maary.darkbag.repository.ImageRepository
import top.maary.darkbag.utils.DarkbagIdentity
import top.maary.darkbag.utils.ImageSaver
import top.maary.darkbag.utils.MultiCameraCollageHelper
import top.maary.darkbag.utils.MultiCameraCollageHelper.CollageLayout

class MultiCameraCollageSheet : BottomSheetDialogFragment() {

    private var _binding: LayoutMultiCameraCollageSheetBinding? = null
    private val binding get() = _binding!!

    private var group: ImageGroup? = null
    private val allLenses = mutableListOf<MultiCameraLensItem>()
    private val selectedLenses = mutableListOf<MultiCameraLensItem>()
    private var currentLayout: CollageLayout = CollageLayout.SIDE_BY_SIDE
    private var currentBgColor: Int = Color.WHITE
    private var currentBorderDp: Float = 16f
    private var currentDividerDp: Float = 8f
    private var currentCornerDp: Float = 4f

    private var previewJob: Job? = null
    private var isSaving = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = LayoutMultiCameraCollageSheetBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        group = arguments?.getParcelable(ARG_GROUP)
        val imageGroup = group ?: run {
            dismiss()
            return
        }

        allLenses.clear()
        if (imageGroup.multiCameraLenses.isNotEmpty()) {
            allLenses.addAll(imageGroup.multiCameraLenses)
        } else {
            val uris = if (imageGroup.multiJpgUris.isNotEmpty()) imageGroup.multiJpgUris else imageGroup.multiDngUris
            uris.forEachIndexed { index, uri ->
                allLenses.add(MultiCameraLensItem(lensTag = "Lens ${index + 1}", multiplier = 1.0f, jpgUri = uri))
            }
        }

        selectedLenses.clear()
        selectedLenses.addAll(allLenses)

        setupLensChips()
        setupStyleChips()
        updateLayoutChips()

        binding.btnSave.setOnClickListener {
            saveCollage(shareAfterSave = false)
        }

        binding.btnShare.setOnClickListener {
            saveCollage(shareAfterSave = true)
        }

        renderPreview()
    }

    private fun setupLensChips() {
        binding.chipGroupLenses.removeAllViews()
        for (lens in allLenses) {
            val chip = Chip(requireContext()).apply {
                text = lens.lensTag
                isCheckable = true
                isChecked = selectedLenses.contains(lens)
                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) {
                        if (!selectedLenses.contains(lens)) {
                            selectedLenses.add(lens)
                            // Keep sorted by multiplier
                            selectedLenses.sortBy { it.multiplier }
                        }
                    } else {
                        if (selectedLenses.size <= 2) {
                            // Don't allow unchecking if only 2 left
                            this.isChecked = true
                            Toast.makeText(requireContext(), R.string.collage_min_lenses_warning, Toast.LENGTH_SHORT).show()
                            return@setOnCheckedChangeListener
                        }
                        selectedLenses.remove(lens)
                    }
                    updateLayoutChips()
                    renderPreview()
                }
            }
            binding.chipGroupLenses.addView(chip)
        }
    }

    private fun updateLayoutChips() {
        val count = selectedLenses.size
        binding.chipGroupLayouts.removeAllViews()

        val availableLayouts = when (count) {
            2 -> listOf(
                CollageLayout.SIDE_BY_SIDE to getString(R.string.collage_layout_sbs),
                CollageLayout.TOP_BOTTOM to getString(R.string.collage_layout_tb)
            )
            3 -> listOf(
                CollageLayout.TRIPTYCH_ROW to getString(R.string.collage_layout_triptych_row),
                CollageLayout.TRIPTYCH_COLUMN to getString(R.string.collage_layout_triptych_col),
                CollageLayout.FEATURED_TOP to getString(R.string.collage_layout_featured_top),
                CollageLayout.FEATURED_LEFT to getString(R.string.collage_layout_featured_left)
            )
            else -> listOf(
                CollageLayout.SIDE_BY_SIDE to getString(R.string.collage_layout_sbs),
                CollageLayout.TOP_BOTTOM to getString(R.string.collage_layout_tb)
            )
        }

        if (availableLayouts.none { it.first == currentLayout }) {
            currentLayout = availableLayouts.first().first
        }

        for ((layout, title) in availableLayouts) {
            val chip = Chip(requireContext()).apply {
                text = title
                isCheckable = true
                isChecked = (layout == currentLayout)
                setOnClickListener {
                    if (currentLayout != layout) {
                        currentLayout = layout
                        renderPreview()
                    }
                }
            }
            binding.chipGroupLayouts.addView(chip)
        }
    }

    private fun setupStyleChips() {
        binding.chipGroupStyles.removeAllViews()

        val styles = listOf(
            Triple(getString(R.string.collage_style_white), Color.WHITE, 16f to 8f),
            Triple(getString(R.string.collage_style_black), Color.parseColor("#141414"), 16f to 8f),
            Triple(getString(R.string.collage_border_none), Color.TRANSPARENT, 0f to 4f)
        )

        for ((title, bgColor, borders) in styles) {
            val chip = Chip(requireContext()).apply {
                text = title
                isCheckable = true
                isChecked = (bgColor == currentBgColor && borders.first == currentBorderDp)
                setOnClickListener {
                    currentBgColor = bgColor
                    currentBorderDp = borders.first
                    currentDividerDp = borders.second
                    currentCornerDp = if (borders.first > 0) 4f else 0f
                    renderPreview()
                }
            }
            binding.chipGroupStyles.addView(chip)
        }
    }

    private fun getSelectedUris(): List<Uri> {
        return selectedLenses.mapNotNull { it.jpgUri ?: it.dngUri }
    }

    private fun renderPreview() {
        val uris = getSelectedUris()
        if (uris.size < 2) return

        previewJob?.cancel()
        binding.progressPreview.visibility = View.VISIBLE

        val layout = currentLayout
        val bgColor = currentBgColor
        val borderDp = currentBorderDp
        val dividerDp = currentDividerDp
        val cornerDp = currentCornerDp

        previewJob = lifecycleScope.launch(Dispatchers.IO) {
            val previewBitmap = MultiCameraCollageHelper.createCollage(
                context = requireContext().applicationContext,
                imageUris = uris,
                layout = layout,
                borderWidthDp = borderDp,
                dividerWidthDp = dividerDp,
                backgroundColor = bgColor,
                cornerRadiusDp = cornerDp,
                maxDimension = 1080
            )

            withContext(Dispatchers.Main) {
                binding.progressPreview.visibility = View.GONE
                if (previewBitmap != null) {
                    binding.ivCollagePreview.setImageBitmap(previewBitmap)
                }
            }
        }
    }

    private fun saveCollage(shareAfterSave: Boolean) {
        if (isSaving) return
        val currentGroup = group ?: return
        val uris = getSelectedUris()
        if (uris.size < 2) {
            Toast.makeText(requireContext(), R.string.collage_min_lenses_warning, Toast.LENGTH_SHORT).show()
            return
        }

        isSaving = true
        binding.btnSave.isEnabled = false
        binding.btnShare.isEnabled = false
        binding.progressPreview.visibility = View.VISIBLE

        val layout = currentLayout
        val bgColor = currentBgColor
        val borderDp = currentBorderDp
        val dividerDp = currentDividerDp
        val cornerDp = currentCornerDp

        lifecycleScope.launch(Dispatchers.IO) {
            val highResBitmap = MultiCameraCollageHelper.createCollage(
                context = requireContext().applicationContext,
                imageUris = uris,
                layout = layout,
                borderWidthDp = borderDp,
                dividerWidthDp = dividerDp,
                backgroundColor = bgColor,
                cornerRadiusDp = cornerDp,
                maxDimension = 3600
            )

            if (highResBitmap != null) {
                val suffix = System.currentTimeMillis().toString().takeLast(4)
                val baseName = DarkbagIdentity.prefixedBaseName("${currentGroup.baseName}_COLLAGE_$suffix")
                val savedUri = ImageSaver.saveProcessedImage(
                    context = requireContext().applicationContext,
                    inputBitmap = highResBitmap,
                    bmpPath = null,
                    rotationDegrees = 0,
                    zoomFactor = 1.0f,
                    baseName = baseName,
                    linearDngPath = null,
                    saveJpg = true,
                    saveRaw = false,
                    jpgFolderUri = null,
                    editConfig = currentGroup.editConfig,
                    captureMetadata = null
                )

                withContext(Dispatchers.Main) {
                    isSaving = false
                    binding.btnSave.isEnabled = true
                    binding.btnShare.isEnabled = true
                    binding.progressPreview.visibility = View.GONE

                    if (savedUri != null) {
                        ImageRepository(requireContext().applicationContext).invalidateCache()
                        Toast.makeText(requireContext(), R.string.collage_saved_toast, Toast.LENGTH_SHORT).show()

                        if (shareAfterSave) {
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "image/jpeg"
                                putExtra(Intent.EXTRA_STREAM, savedUri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            try {
                                startActivity(Intent.createChooser(intent, getString(R.string.create_multi_cam_collage)))
                            } catch (e: Exception) {
                                Toast.makeText(requireContext(), "No app found to share image", Toast.LENGTH_SHORT).show()
                            }
                        }
                        dismiss()
                    } else {
                        Toast.makeText(requireContext(), "Failed to save collage", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                withContext(Dispatchers.Main) {
                    isSaving = false
                    binding.btnSave.isEnabled = true
                    binding.btnShare.isEnabled = true
                    binding.progressPreview.visibility = View.GONE
                    Toast.makeText(requireContext(), "Failed to render collage", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        previewJob?.cancel()
        _binding = null
    }

    companion object {
        const val TAG = "MultiCameraCollageSheet"
        private const val ARG_GROUP = "group"

        fun newInstance(group: ImageGroup): MultiCameraCollageSheet {
            return MultiCameraCollageSheet().apply {
                arguments = Bundle().apply {
                    putParcelable(ARG_GROUP, group)
                }
            }
        }
    }
}
