package top.maary.darkbag.fragments

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import top.maary.darkbag.R
import top.maary.darkbag.databinding.SheetGalleryAlbumInfoBinding
import top.maary.darkbag.models.ImageGroup
import top.maary.darkbag.utils.CameraRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Bento Matrix BottomSheet presenting hardware capture device telemetry and album statistics.
 */
class GalleryAlbumInfoSheet : BottomSheetDialogFragment() {

    private var _binding: SheetGalleryAlbumInfoBinding? = null
    private val binding get() = _binding!!

    companion object {
        const val TAG = "GalleryAlbumInfoSheet"
        private const val ARG_TOTAL_COUNT = "arg_total_count"
        private const val ARG_TOTAL_SIZE = "arg_total_size"
        private const val ARG_LAST_CAPTURE = "arg_last_capture"

        fun newInstance(totalCount: Int, totalSizeBytes: Long, lastCaptureTime: Long): GalleryAlbumInfoSheet {
            return GalleryAlbumInfoSheet().apply {
                arguments = Bundle().apply {
                    putInt(ARG_TOTAL_COUNT, totalCount)
                    putLong(ARG_TOTAL_SIZE, totalSizeBytes)
                    putLong(ARG_LAST_CAPTURE, lastCaptureTime)
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = SheetGalleryAlbumInfoBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val totalCount = arguments?.getInt(ARG_TOTAL_COUNT, 0) ?: 0
        val totalSizeBytes = arguments?.getLong(ARG_TOTAL_SIZE, 0L) ?: 0L
        val lastCaptureTime = arguments?.getLong(ARG_LAST_CAPTURE, 0L) ?: 0L

        // 1. Populate Device Information
        populateDeviceTelemetry()

        // 2. Populate Album Statistics
        binding.tvStatTotalCount.text = "$totalCount"
        binding.tvStatTotalSize.text = Formatter.formatFileSize(requireContext(), totalSizeBytes)

        if (lastCaptureTime > 0L) {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            binding.tvStatLastCapture.text = sdf.format(Date(lastCaptureTime))
        } else {
            binding.tvStatLastCapture.text = "--"
        }
    }

    private fun populateDeviceTelemetry() {
        val manufacturer = Build.MANUFACTURER.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
        val model = Build.MODEL
        val modelText = if (model.startsWith(manufacturer, ignoreCase = true)) model else "$manufacturer $model"
        binding.tvDeviceModel.text = modelText

        try {
            val cameraManager = requireContext().getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            if (cameraManager != null) {
                val backIds = cameraManager.cameraIdList.filter { id ->
                    try {
                        val chars = CameraRepository.getCharacteristics(cameraManager, id)
                        chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
                    } catch (e: Exception) {
                        false
                    }
                }
                val mainId = backIds.firstOrNull { it == "0" } ?: backIds.firstOrNull()
                if (mainId != null) {
                    val chars = CameraRepository.getCharacteristics(cameraManager, mainId)

                    // Focal length & aperture
                    val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    val apertures = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
                    val sensorSize = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                    val activeArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)

                    val actualF = focalLengths?.firstOrNull() ?: 0f
                    val apertureF = apertures?.firstOrNull() ?: 1.8f
                    val eqFocal = if (actualF > 0f && sensorSize != null && sensorSize.width > 0f) {
                        (actualF * 36f / sensorSize.width).roundToInt()
                    } else {
                        actualF.roundToInt()
                    }

                    val lensDesc = if (eqFocal > 0) {
                        "${eqFocal}mm ƒ/${String.format(Locale.US, "%.1f", apertureF)}"
                    } else {
                        "ƒ/${String.format(Locale.US, "%.1f", apertureF)}"
                    }
                    binding.tvDeviceLens.text = lensDesc

                    // Sensor resolution & optical size
                    if (activeArray != null) {
                        val mp = (activeArray.width().toLong() * activeArray.height() / 1_000_000L).toInt()
                        val opticalFormat = if (sensorSize != null) {
                            val diag = sqrt(sensorSize.width * sensorSize.width + sensorSize.height * sensorSize.height)
                            when {
                                diag >= 15.0f -> "1\""
                                diag >= 11.5f -> "1/1.31\""
                                diag >= 9.0f -> "1/1.7\""
                                diag >= 7.5f -> "1/2.0\""
                                diag >= 6.0f -> "1/2.55\""
                                else -> String.format(Locale.US, "%.1f×%.1fmm", sensorSize.width, sensorSize.height)
                            }
                        } else ""

                        binding.tvDeviceSensor.text = if (opticalFormat.isNotEmpty()) "$mp MP · $opticalFormat" else "$mp MP"
                    } else {
                        binding.tvDeviceSensor.text = "--"
                    }
                    return
                }
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Failed to resolve device camera telemetry", e)
        }

        binding.tvDeviceLens.text = "Wide ƒ/1.8"
        binding.tvDeviceSensor.text = "--"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
