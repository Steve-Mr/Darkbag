package top.maary.darkbag.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.FragmentManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import top.maary.darkbag.R
import top.maary.darkbag.databinding.BottomSheetRawVideoSettingsBinding
import top.maary.darkbag.databinding.ItemBentoSelectionOptionBinding
import top.maary.darkbag.fragments.SettingsFragment

/**
 * RawVideoSettingsBottomSheet provides a unified Bento bottom sheet for selecting
 * both RAW video frame rate (FPS) and resolution / sensor gate in a single cohesive view,
 * eliminating the nested dialog anti-pattern.
 */
class RawVideoSettingsBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetRawVideoSettingsBinding? = null
    private val binding get() = _binding!!

    private var onSettingsChangedListener: (() -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetRawVideoSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupFpsButtons()
        setupResolutionCards()
    }

    private fun setupFpsButtons() {
        val prefs = requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        val cameraManager = requireContext().getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        val backCameraChars = try {
            val backId = cameraManager?.cameraIdList?.firstOrNull { id ->
                val c = top.maary.darkbag.utils.CameraRepository.getCharacteristics(cameraManager, id)
                c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: cameraManager?.cameraIdList?.firstOrNull()
            backId?.let { id -> cameraManager?.let { top.maary.darkbag.utils.CameraRepository.getCharacteristics(it, id) } }
        } catch (_: Exception) {
            null
        }

        val maxHardwareFps = SettingsFragment.HardwareCapabilities.getMaxSupportedFps(backCameraChars)
        val savedFps = prefs.getString(SettingsFragment.KEY_RAW_VIDEO_FPS, "24") ?: "24"

        fun updateFpsStyles(currentFps: String) {
            val fpsList = listOf(
                Triple(binding.btnFps24, "24", "Cinema"),
                Triple(binding.btnFps30, "30", "Standard"),
                Triple(binding.btnFps60, "60", "High-Speed")
            )

            val colorPrimary = MaterialColors.getColor(binding.root, android.R.attr.colorPrimary, Color.WHITE)
            val colorPrimaryContainer = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorPrimaryContainer, Color.DKGRAY)
            val colorOnPrimaryContainer = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnPrimaryContainer, colorPrimary)
            val colorSurfaceContainerHigh = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorSurfaceContainerHigh, Color.DKGRAY)
            val colorOutlineVariant = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOutlineVariant, Color.GRAY)
            val colorOnSurface = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnSurface, Color.WHITE)

            val density = resources.displayMetrics.density
            val stroke1dp = (1f * density).toInt()
            val stroke2dp = (2f * density).toInt()
            val radius10dp = (10f * density).toInt()
            val radius14dp = (14f * density).toInt()

            for ((btn, fpsStr, label) in fpsList) {
                val isSelected = (fpsStr == currentFps)
                val fpsVal = fpsStr.toIntOrNull() ?: 24
                val isSupported = fpsVal <= maxHardwareFps

                val subLabel = if (isSupported) label else "Unsupported"
                btn.text = "$fpsStr FPS\n$subLabel"

                if (isSelected) {
                    btn.backgroundTintList = ColorStateList.valueOf(colorPrimaryContainer)
                    btn.strokeColor = ColorStateList.valueOf(colorPrimary)
                    btn.strokeWidth = stroke2dp
                    btn.cornerRadius = radius10dp
                    btn.setTextColor(colorOnPrimaryContainer)
                } else {
                    btn.backgroundTintList = ColorStateList.valueOf(colorSurfaceContainerHigh)
                    btn.strokeColor = ColorStateList.valueOf(colorOutlineVariant)
                    btn.strokeWidth = stroke1dp
                    btn.cornerRadius = radius14dp
                    btn.setTextColor(colorOnSurface)
                }

                btn.alpha = if (isSupported) 1.0f else 0.5f

                btn.setOnClickListener {
                    if (!isSupported) {
                        Toast.makeText(requireContext(), getString(R.string.raw_video_option_unsupported_suffix, "$fpsStr FPS"), Toast.LENGTH_SHORT).show()
                    }
                    prefs.edit().putString(SettingsFragment.KEY_RAW_VIDEO_FPS, fpsStr).apply()
                    updateFpsStyles(fpsStr)
                    onSettingsChangedListener?.invoke()
                }
            }
        }

        updateFpsStyles(savedFps)
    }

    private fun setupResolutionCards() {
        val prefs = requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        val cameraManager = requireContext().getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        val backCameraChars = try {
            val backId = cameraManager?.cameraIdList?.firstOrNull { id ->
                val c = top.maary.darkbag.utils.CameraRepository.getCharacteristics(cameraManager, id)
                c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: cameraManager?.cameraIdList?.firstOrNull()
            backId?.let { id -> cameraManager?.let { top.maary.darkbag.utils.CameraRepository.getCharacteristics(it, id) } }
        } catch (_: Exception) {
            null
        }

        val is4kSupported = SettingsFragment.HardwareCapabilities.is4kCropSupported(backCameraChars)
        val savedRes = prefs.getString(SettingsFragment.KEY_RAW_VIDEO_RESOLUTION, SettingsFragment.DEFAULT_RAW_VIDEO_RESOLUTION)
            ?: SettingsFragment.DEFAULT_RAW_VIDEO_RESOLUTION

        val container = binding.containerResolutionOptions
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

        val stroke1dp = (1f * density).toInt()
        val stroke2dp = (2f * density).toInt()
        val radius10dp = 10f * density
        val radius14dp = 14f * density

        val descriptions = mapOf(
            "Max Native (4:3)" to "Full sensor area without cropping",
            "2K Open Gate (4:3)" to "2048 x 1536 open gate cinematic preview",
            "4K UHD (16:9)" to "3840 x 2160 16:9 Ultra High Definition",
            "1080p (16:9)" to "1920 x 1080 Full HD recording stream"
        )

        fun renderCards(currentRes: String) {
            container.removeAllViews()
            SettingsFragment.RAW_VIDEO_RESOLUTION_OPTIONS.forEach { resOption ->
                val itemBinding = ItemBentoSelectionOptionBinding.inflate(inflater, container, false)
                val isSelected = (resOption == currentRes)
                val isSupported = !resOption.contains("4K") || is4kSupported

                itemBinding.tvOptionTitle.text = resOption
                itemBinding.tvOptionDesc.text = descriptions[resOption] ?: ""
                itemBinding.tvOptionDesc.visibility = View.VISIBLE

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

                itemBinding.cardOption.alpha = if (isSupported) 1.0f else 0.5f

                itemBinding.cardOption.setOnClickListener {
                    if (!isSupported) {
                        Toast.makeText(requireContext(), getString(R.string.raw_video_option_unsupported_suffix, resOption), Toast.LENGTH_SHORT).show()
                    }
                    prefs.edit().putString(SettingsFragment.KEY_RAW_VIDEO_RESOLUTION, resOption).apply()
                    renderCards(resOption)
                    onSettingsChangedListener?.invoke()
                }

                container.addView(itemBinding.root)
            }
        }

        renderCards(savedRes)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val TAG = "RawVideoSettingsSheet"

        fun show(
            fragmentManager: FragmentManager,
            onChanged: () -> Unit
        ): RawVideoSettingsBottomSheet {
            val sheet = RawVideoSettingsBottomSheet().apply {
                this.onSettingsChangedListener = onChanged
            }
            sheet.show(fragmentManager, TAG)
            return sheet
        }
    }
}
