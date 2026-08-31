package top.maary.darkbag.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.setFragmentResult
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.chip.Chip
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

        selectedLog = arguments?.getString(ARG_LOG) ?: "None"
        selectedLut = arguments?.getString(ARG_LUT) ?: "None"

        val context = requireContext()
        val lutManager = LutManager(context)
        val luts = lutManager.getLuts()

        // 1. Populate Log Chips
        val logOptions = listOf("None") + SettingsFragment.LOG_CURVES
        logOptions.forEach { logName ->
            val chip = Chip(context, null, com.google.android.material.R.attr.chipStyle).apply {
                text = logName
                isCheckable = true
                isChecked = logName == selectedLog
                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) {
                        selectedLog = logName
                    }
                }
            }
            binding.chipGroupLog.addView(chip)
        }

        // 2. Populate LUT Chips
        val lutEntries = listOf("None" to "None") + luts.map { it.nameWithoutExtension to it.name }
        lutEntries.forEach { (displayName, fileName) ->
            val chip = Chip(context, null, com.google.android.material.R.attr.chipStyle).apply {
                text = displayName
                isCheckable = true
                isChecked = fileName == selectedLut || (selectedLut == "None" && fileName == "None")
                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) {
                        selectedLut = fileName
                    }
                }
            }
            binding.chipGroupLut.addView(chip)
        }

        // 3. Resolution Chips
        binding.chipGroupResolution.setOnCheckedStateChangeListener { _, checkedIds ->
            selectedResolution = if (checkedIds.contains(binding.chipRes4k.id)) 2160 else 1080
        }

        // 4. Export Button
        binding.btnExportAction.setOnClickListener {
            setFragmentResult(
                REQUEST_KEY,
                bundleOf(
                    RESULT_LOG to selectedLog,
                    RESULT_LUT to selectedLut,
                    RESULT_RESOLUTION to selectedResolution
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
        const val RESULT_LOG = "result_log"
        const val RESULT_LUT = "result_lut"
        const val RESULT_RESOLUTION = "result_resolution"

        fun newInstance(currentLog: String?, currentLut: String?): RawVideoExportSheet {
            return RawVideoExportSheet().apply {
                arguments = bundleOf(
                    ARG_LOG to (currentLog ?: "None"),
                    ARG_LUT to (currentLut ?: "None")
                )
            }
        }
    }
}
