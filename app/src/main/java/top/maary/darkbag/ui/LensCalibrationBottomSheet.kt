package top.maary.darkbag.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.chip.Chip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.maary.darkbag.calibration.CustomLensCalibration
import top.maary.darkbag.calibration.LensCalibrationStore
import top.maary.darkbag.databinding.SheetLensCalibrationBinding
import top.maary.darkbag.utils.CameraRepository
import top.maary.darkbag.utils.LensInfo
import java.io.InputStream
import java.util.Locale

/**
 * Material 3 Expressive Bento BottomSheet for user-guided lens distortion calibration.
 * Allows photographers to visually adjust radial distortion coefficients (k1) against
 * a reference alignment grid using test photos or default perspective patterns.
 */
class LensCalibrationBottomSheet : BottomSheetDialogFragment() {

    private var _binding: SheetLensCalibrationBinding? = null
    private val binding get() = _binding!!

    private lateinit var calibrationStore: LensCalibrationStore
    private var availableLenses: List<LensInfo> = emptyList()
    private var selectedLens: LensInfo? = null

    private val pickPhotoLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            loadBitmapFromUri(uri)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = SheetLensCalibrationBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        calibrationStore = LensCalibrationStore.getInstance(requireContext())

        // Expand bottom sheet fully so interactive canvas is comfortable
        val behavior = BottomSheetBehavior.from(view.parent as View)
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
        behavior.skipCollapsed = true

        setupLenses()
        setupControls()
    }

    private fun setupLenses() {
        val repo = CameraRepository(requireContext())
        availableLenses = repo.enumerateCameras().filter { !it.isLogicalAuto && !it.isZoomPreset }
        if (availableLenses.isEmpty()) {
            availableLenses = repo.enumerateCameras()
        }

        val chipGroup = binding.chipGroupLenses
        chipGroup.removeAllViews()

        // Prioritize ultra-wide camera by default
        val defaultLens = availableLenses.firstOrNull { it.multiplier < 0.95f || it.focalLength in 0.1f..3.2f }
            ?: availableLenses.firstOrNull()

        availableLenses.forEach { lens ->
            val chip = Chip(requireContext()).apply {
                val label = buildString {
                    append(lens.name)
                    if (lens.focalLength > 0f) {
                        append(String.format(Locale.US, " · %.1fmm", lens.focalLength))
                    }
                }
                text = label
                isCheckable = true
                isChecked = (lens.sensorId == defaultLens?.sensorId)
                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) {
                        selectLens(lens)
                    }
                }
            }
            chipGroup.addView(chip)
        }

        defaultLens?.let { selectLens(it) }
    }

    private fun selectLens(lens: LensInfo) {
        selectedLens = lens
        val existingCalib = calibrationStore.getCalibration(lens.sensorId)
        val k1 = existingCalib?.k1 ?: 0f

        binding.sliderK1.value = k1.coerceIn(binding.sliderK1.valueFrom, binding.sliderK1.valueTo)
        updateK1Display(k1)
        binding.calibrationPreview.k1 = k1
    }

    private fun setupControls() {
        binding.sliderK1.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                updateK1Display(value)
                binding.calibrationPreview.k1 = value
            }
        }

        binding.btnResetK1.setOnClickListener {
            binding.sliderK1.value = 0.000f
            updateK1Display(0.000f)
            binding.calibrationPreview.k1 = 0.000f
        }

        binding.btnToggleGrid.setOnClickListener {
            binding.calibrationPreview.showGrid = !binding.calibrationPreview.showGrid
        }

        binding.btnToggleCompare.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    binding.calibrationPreview.isBypassed = true
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    binding.calibrationPreview.isBypassed = false
                    true
                }
                else -> false
            }
        }

        binding.btnPickPhoto.setOnClickListener {
            pickPhotoLauncher.launch("image/*")
        }

        binding.btnSaveCalibration.setOnClickListener {
            val lens = selectedLens ?: return@setOnClickListener
            val k1 = binding.sliderK1.value

            val calib = CustomLensCalibration(
                lensId = lens.sensorId,
                focalLength = lens.focalLength,
                k1 = k1,
                k2 = 0f,
                isEnabled = kotlin.math.abs(k1) > 0.0001f
            )
            calibrationStore.saveCalibration(calib)

            val msg = if (calib.isEnabled) {
                String.format(Locale.US, "已保存 %s 标定参数 (k1=%.3f)", lens.name, k1)
            } else {
                String.format(Locale.US, "已重置 %s 标定", lens.name)
            }
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
            dismiss()
        }
    }

    private fun updateK1Display(k1: Float) {
        val label = when {
            k1 < -0.0005f -> String.format(Locale.US, "%.3f · 桶形矫正", k1)
            k1 > 0.0005f -> String.format(Locale.US, "+%.3f · 枕形矫正", k1)
            else -> "0.000 · 原始"
        }
        binding.tvK1Value.text = label
    }

    private fun loadBitmapFromUri(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    var input: InputStream? = requireContext().contentResolver.openInputStream(uri)
                    val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeStream(input, null, boundsOptions)
                    input?.close()

                    val maxDim = 1400
                    var sampleSize = 1
                    var w = boundsOptions.outWidth
                    var h = boundsOptions.outHeight
                    while (w > maxDim || h > maxDim) {
                        sampleSize *= 2
                        w /= 2
                        h /= 2
                    }

                    val decodeOptions = BitmapFactory.Options().apply {
                        inSampleSize = sampleSize
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                    input = requireContext().contentResolver.openInputStream(uri)
                    val bmp = BitmapFactory.decodeStream(input, null, decodeOptions)
                    input?.close()
                    bmp
                }

                if (_binding != null) {
                    if (bitmap != null) {
                        binding.calibrationPreview.setBitmap(bitmap)
                    } else {
                        Toast.makeText(requireContext(), "无法解析图片", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                if (_binding != null) {
                    Toast.makeText(requireContext(), "读取图片失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "LensCalibrationBottomSheet"

        fun show(fragmentManager: androidx.fragment.app.FragmentManager) {
            val sheet = LensCalibrationBottomSheet()
            sheet.show(fragmentManager, TAG)
        }
    }
}
