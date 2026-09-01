package top.maary.darkbag.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.setFragmentResult
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
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
