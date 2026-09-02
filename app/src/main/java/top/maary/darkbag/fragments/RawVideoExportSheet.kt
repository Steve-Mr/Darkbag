package top.maary.darkbag.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.setFragmentResult
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import top.maary.darkbag.R
import top.maary.darkbag.databinding.LayoutRawVideoExportSheetBinding
import top.maary.darkbag.utils.LutManager

class RawVideoExportSheet : BottomSheetDialogFragment() {

    private var _binding: LayoutRawVideoExportSheetBinding? = null
    private val binding get() = _binding!!

    private var selectedLog: String = "None"
    private var selectedLut: String = "None"
    private var selectedResolution: Int = 1080

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = LayoutRawVideoExportSheetBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val initialLog = arguments?.getString(ARG_LOG)?.takeIf { it.isNotBlank() } ?: "None"
        val initialLut = arguments?.getString(ARG_LUT)?.takeIf { it.isNotBlank() } ?: "None"
        val exportMode = arguments?.getInt(ARG_EXPORT_MODE, MODE_VIDEO) ?: MODE_VIDEO
        val frameIndex = arguments?.getInt(ARG_FRAME_INDEX, 0) ?: 0

        val context = requireContext()
        val lutManager = LutManager(context)
        val luts = lutManager.getLuts()

        // 1. Setup Log Dropdown Menu
        val logOptions = listOf("None") + SettingsFragment.LOG_CURVES
        selectedLog = if (logOptions.contains(initialLog)) initialLog else "None"

        val logAdapter = android.widget.ArrayAdapter(context, android.R.layout.simple_dropdown_item_1line, logOptions)
        binding.menuLog.setAdapter(logAdapter)
        binding.menuLog.setText(selectedLog, false)
        binding.menuLog.setOnItemClickListener { _, _, position, _ ->
            selectedLog = logOptions[position]
        }

        // 2. Setup LUT Dropdown Menu with robust matching
        val lutEntries = mutableListOf("None" to "None")
        luts.forEach { file ->
            lutEntries.add(file.nameWithoutExtension to file.name)
        }

        val matchedEntry = if (initialLut.equals("None", ignoreCase = true)) {
            lutEntries.first()
        } else {
            lutEntries.find { (displayName, fileName) ->
                fileName.equals(initialLut, ignoreCase = true) ||
                displayName.equals(initialLut, ignoreCase = true) ||
                fileName.removeSuffix(".cube").equals(initialLut.removeSuffix(".cube"), ignoreCase = true)
            } ?: ("None" to "None")
        }

        selectedLut = matchedEntry.second
        val lutDisplayNames = lutEntries.map { it.first }
        val lutAdapter = android.widget.ArrayAdapter(context, android.R.layout.simple_dropdown_item_1line, lutDisplayNames)
        binding.menuLut.setAdapter(lutAdapter)
        binding.menuLut.setText(matchedEntry.first, false)
        binding.menuLut.setOnItemClickListener { _, _, position, _ ->
            selectedLut = lutEntries[position].second
        }

        // 3. Setup UI based on Export Mode
        if (exportMode != MODE_VIDEO) {
            binding.tvResolutionLabel.visibility = View.GONE
            binding.chipGroupResolution.visibility = View.GONE
            binding.tvTitle.text = if (exportMode == MODE_SINGLE_FRAME_PAIR) {
                "导出 RAW + 调色 JPG (Frame #${frameIndex + 1})"
            } else {
                "导出单帧调色照片 (Frame #${frameIndex + 1})"
            }
            binding.tvSubtitle.text = "选择母带输出的 Log 曲线与 3D LUT 胶片模拟"
            binding.btnExportAction.text = "导出调色照片"
            binding.btnExportAction.setIconResource(R.drawable.ic_camera)
        } else {
            // Resolution Chips for Video
            binding.chipGroupResolution.setOnCheckedStateChangeListener { _, checkedIds ->
                selectedResolution = if (checkedIds.contains(binding.chipRes4k.id)) 2160 else 1080
            }
        }

        // 4. Export Button
        binding.btnExportAction.setOnClickListener {
            setFragmentResult(
                REQUEST_KEY,
                bundleOf(
                    RESULT_LOG to selectedLog,
                    RESULT_LUT to selectedLut,
                    RESULT_RESOLUTION to selectedResolution,
                    RESULT_EXPORT_MODE to exportMode,
                    RESULT_FRAME_INDEX to frameIndex
                )
            )
            dismiss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "RawVideoExportSheet"
        const val REQUEST_KEY = "request_raw_video_export"
        const val ARG_LOG = "arg_log"
        const val ARG_LUT = "arg_lut"
        const val ARG_EXPORT_MODE = "arg_export_mode"
        const val ARG_FRAME_INDEX = "arg_frame_index"
        const val RESULT_LOG = "result_log"
        const val RESULT_LUT = "result_lut"
        const val RESULT_RESOLUTION = "result_resolution"
        const val RESULT_EXPORT_MODE = "result_export_mode"
        const val RESULT_FRAME_INDEX = "result_frame_index"

        const val MODE_VIDEO = 0
        const val MODE_SINGLE_FRAME_JPG = 1
        const val MODE_SINGLE_FRAME_PAIR = 2

        fun newInstance(
            currentLog: String?,
            currentLut: String?,
            exportMode: Int = MODE_VIDEO,
            frameIndex: Int = 0
        ): RawVideoExportSheet {
            return RawVideoExportSheet().apply {
                arguments = bundleOf(
                    ARG_LOG to (currentLog ?: "None"),
                    ARG_LUT to (currentLut ?: "None"),
                    ARG_EXPORT_MODE to exportMode,
                    ARG_FRAME_INDEX to frameIndex
                )
            }
        }
    }
}
