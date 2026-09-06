package top.maary.darkbag.fragments

import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.setFragmentResult
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import top.maary.darkbag.R
import top.maary.darkbag.databinding.LayoutRawVideoExportSheetBinding
import top.maary.darkbag.models.ImageGroup
import top.maary.darkbag.utils.LutManager
import java.util.Locale

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

        val group: ImageGroup? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arguments?.getParcelable(ARG_GROUP, ImageGroup::class.java)
        } else {
            @Suppress("DEPRECATION")
            arguments?.getParcelable(ARG_GROUP)
        }
        val initialLog = arguments?.getString(ARG_LOG)?.takeIf { it.isNotBlank() } ?: "None"
        val initialLut = arguments?.getString(ARG_LUT)?.takeIf { it.isNotBlank() } ?: "None"
        val frameIndex = arguments?.getInt(ARG_FRAME_INDEX, 0) ?: 0

        val context = requireContext()
        val lutManager = LutManager(context)
        val luts = lutManager.getLuts()

        // 1. Setup Header Title & Subtitle
        binding.tvTitle.text = getString(R.string.export_hub_title)
        if (group != null) {
            binding.tvSubtitle.text = when {
                group.rawVideoFrameCount > 0 && group.rawVideoDurationMs > 0 -> {
                    getString(
                        R.string.export_hub_subtitle_rawvid,
                        group.baseName,
                        group.rawVideoFrameCount,
                        String.format(Locale.US, "%.1fs", group.rawVideoDurationMs / 1000f)
                    )
                }
                group.cinemaDngFrameUris.isNotEmpty() -> {
                    getString(R.string.export_hub_subtitle_cdng, group.baseName, group.cinemaDngFrameUris.size)
                }
                group.cinemaDngFrameCount > 0 -> {
                    getString(R.string.export_hub_subtitle_cdng, group.baseName, group.cinemaDngFrameCount)
                }
                else -> group.baseName
            }
        } else {
            binding.tvSubtitle.text = getString(R.string.export_hub_subtitle_default)
        }

        // 2. Setup Log Dropdown Menu
        val logOptions = listOf("None") + SettingsFragment.LOG_CURVES
        selectedLog = if (logOptions.contains(initialLog)) initialLog else "None"

        val logAdapter = android.widget.ArrayAdapter(context, android.R.layout.simple_dropdown_item_1line, logOptions)
        binding.menuLog.setAdapter(logAdapter)
        binding.menuLog.setText(selectedLog, false)
        binding.menuLog.setOnItemClickListener { _, _, position, _ ->
            selectedLog = logOptions[position]
        }

        // 3. Setup LUT Dropdown Menu with robust matching
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

        // 4. Resolution Chips for Video
        binding.chipGroupResolution.setOnCheckedStateChangeListener { _, checkedIds ->
            selectedResolution = if (checkedIds.contains(binding.chipRes4k.id)) 2160 else 1080
        }

        // 5. Visibility for sections and actions
        val hasRawVid = group?.isRawVideo == true || group?.rawVideoUri != null
        val isCinemaDngGroup = group?.isCinemaDng == true || group?.cinemaDngFolderUri != null || group?.cinemaDngFirstFrameUri != null || group?.cinemaDngFrameUris?.isNotEmpty() == true
        val showVideoSection = hasRawVid || isCinemaDngGroup || group == null

        binding.cardVideoExport.visibility = if (showVideoSection) View.VISIBLE else View.GONE
        binding.btnExportMp4.visibility = if (showVideoSection) View.VISIBLE else View.GONE
        binding.btnExportCinemadng.visibility = if (hasRawVid) View.VISIBLE else View.GONE
        binding.tvResolutionLabel.visibility = if (showVideoSection) View.VISIBLE else View.GONE
        binding.chipGroupResolution.visibility = if (showVideoSection) View.VISIBLE else View.GONE

        val showFrameSection = isCinemaDngGroup || hasRawVid
        binding.sectionFrameExport.visibility = if (showFrameSection) View.VISIBLE else View.GONE
        binding.tvFrameSectionTitle.text = getString(R.string.export_hub_frame_section_title, frameIndex + 1)

        // 6. Action Dispatcher
        fun dispatchAction(action: String) {
            val exportMode = when (action) {
                ACTION_EXPORT_MP4 -> MODE_VIDEO
                ACTION_FRAME_JPG -> MODE_SINGLE_FRAME_JPG
                ACTION_FRAME_PAIR -> MODE_SINGLE_FRAME_PAIR
                else -> MODE_VIDEO
            }
            setFragmentResult(
                REQUEST_KEY,
                bundleOf(
                    RESULT_ACTION to action,
                    RESULT_LOG to selectedLog,
                    RESULT_LUT to selectedLut,
                    RESULT_RESOLUTION to selectedResolution,
                    RESULT_FRAME_INDEX to frameIndex,
                    RESULT_EXPORT_MODE to exportMode
                )
            )
            dismiss()
        }

        binding.btnExportMp4.setOnClickListener { dispatchAction(ACTION_EXPORT_MP4) }
        binding.btnExportCinemadng.setOnClickListener { dispatchAction(ACTION_EXPORT_CINEMADNG) }
        binding.btnFrameJpg.setOnClickListener { dispatchAction(ACTION_FRAME_JPG) }
        binding.btnFrameDng.setOnClickListener { dispatchAction(ACTION_FRAME_DNG) }
        binding.btnFramePair.setOnClickListener { dispatchAction(ACTION_FRAME_PAIR) }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "RawVideoExportSheet"
        const val REQUEST_KEY = "request_raw_video_export"
        const val ARG_GROUP = "arg_group"
        const val ARG_LOG = "arg_log"
        const val ARG_LUT = "arg_lut"
        const val ARG_FRAME_INDEX = "arg_frame_index"
        const val ARG_EXPORT_MODE = "arg_export_mode"

        const val RESULT_ACTION = "result_action"
        const val RESULT_LOG = "result_log"
        const val RESULT_LUT = "result_lut"
        const val RESULT_RESOLUTION = "result_resolution"
        const val RESULT_FRAME_INDEX = "result_frame_index"
        const val RESULT_EXPORT_MODE = "result_export_mode"

        const val ACTION_EXPORT_MP4 = "action_export_mp4"
        const val ACTION_EXPORT_CINEMADNG = "action_export_cinemadng"
        const val ACTION_FRAME_JPG = "action_frame_jpg"
        const val ACTION_FRAME_DNG = "action_frame_dng"
        const val ACTION_FRAME_PAIR = "action_frame_pair"

        const val MODE_VIDEO = 0
        const val MODE_SINGLE_FRAME_JPG = 1
        const val MODE_SINGLE_FRAME_PAIR = 2

        fun newInstance(
            group: ImageGroup,
            currentLog: String? = null,
            currentLut: String? = null,
            frameIndex: Int = 0
        ): RawVideoExportSheet {
            return RawVideoExportSheet().apply {
                arguments = bundleOf(
                    ARG_GROUP to group,
                    ARG_LOG to (currentLog ?: "None"),
                    ARG_LUT to (currentLut ?: "None"),
                    ARG_FRAME_INDEX to frameIndex
                )
            }
        }

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
