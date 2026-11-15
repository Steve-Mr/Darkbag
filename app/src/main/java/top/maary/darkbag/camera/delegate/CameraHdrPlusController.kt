package top.maary.darkbag.camera.delegate

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.view.View
import top.maary.darkbag.databinding.CameraUiContainerBinding
import top.maary.darkbag.repository.SettingsRepository
import top.maary.darkbag.ui.CameraBentoDeckView
import top.maary.darkbag.utils.CameraRepository
import top.maary.darkbag.utils.LensInfo
import top.maary.darkbag.viewmodel.CameraViewModel

/** Host contract for the HDR+ / flash controller. */
interface CameraHdrPlusHost {
    val hostContext: Context
    val cameraViewModel: CameraViewModel
    val cameraUiContainerBinding: CameraUiContainerBinding?
    val camera2Manager: CameraManager
    val currentLens: LensInfo?
    val lensFacing: Int
    val isHdrPlusEnabled: Boolean
    var isFlashEnabled: Boolean

    fun applyCameraControls()
    fun updateProInfoBar()
}

/**
 * Owns the HDR+ tile/switch state and the mutually exclusive flash / underexposure button
 * (flash is hidden and disabled while HDR+ is active, the button then cycles underexposure).
 */
class CameraHdrPlusController(
    private val host: CameraHdrPlusHost,
    private val lutController: CameraLutController,
    private val lensController: CameraLensController
) {
    private val settingsRepository by lazy { SettingsRepository.getInstance(host.hostContext) }

    /** Seeds the flash flag and HDR+ state from preferences (call once when the view is created). */
    fun restorePersistedState() {
        val current = settingsRepository.current()
        host.isFlashEnabled = current.flashEnabled
        host.cameraViewModel.setHdrPlusEnabled(current.hdrPlusEnabled)
    }

    fun updateHdrPlusUi() {
        val binding = host.cameraUiContainerBinding ?: return
        binding.hdrPlusSwitch?.isChecked = host.isHdrPlusEnabled
        CameraBentoDeckView.styleHdrPlusTile(binding, host.isHdrPlusEnabled)
    }

    fun updateHdrPlusConstraints() {
        val binding = host.cameraUiContainerBinding ?: return
        val spec = CameraBentoDeckView.resolveVisibilitySpec(
            settingsRepository.current(),
            host.cameraViewModel.uiState.value.uiTier
        )
        val showFlashButton = spec.showUnderexposure

        if (host.isHdrPlusEnabled) {
            if (showFlashButton) {
                binding.flashButton?.visibility = View.VISIBLE
                lutController.updateUnderexposureButton()
            } else {
                binding.flashButton?.visibility = View.GONE
            }
            if (host.isFlashEnabled) {
                host.isFlashEnabled = false
                host.cameraViewModel.setFlashEnabled(false)
                if (showFlashButton) lutController.updateUnderexposureButton()
                host.applyCameraControls()
            }
        } else {
            val hasFlash = hasFlashUnit()
            binding.flashButton?.visibility = if (hasFlash && showFlashButton) View.VISIBLE else View.GONE
            if (hasFlash) binding.flashButton?.let { lutController.updateFlashIcon(it) }
        }
        binding.btnProMode?.visibility = View.GONE
        host.updateProInfoBar()
        lensController.updateLensControlRowPosition(animate = true)
    }

    private fun hasFlashUnit(): Boolean {
        val targetId = host.currentLens?.id
            ?: if (host.lensFacing == CameraCharacteristics.LENS_FACING_BACK) "0" else "1"
        return try {
            CameraRepository.getCharacteristics(host.camera2Manager, targetId)
                .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
        } catch (e: Exception) { false }
    }

    /** Binds the flash / underexposure button (behaviour depends on HDR+ state at click time). */
    fun bindFlashButton() {
        val btn = host.cameraUiContainerBinding?.flashButton ?: return
        if (host.isHdrPlusEnabled) lutController.updateUnderexposureButton() else lutController.updateFlashIcon(btn)
        btn.setOnClickListener {
            if (host.isHdrPlusEnabled) {
                lutController.cycleUnderexposureMode()
            } else {
                host.isFlashEnabled = !host.isFlashEnabled
                host.cameraViewModel.setFlashEnabled(host.isFlashEnabled)
                settingsRepository.setFlashEnabled(host.isFlashEnabled)
                lutController.updateFlashIcon(btn)
                host.applyCameraControls()
            }
        }
    }

    /** Binds the HDR+ switch and its pill tile. */
    fun bindHdrPlusControls() {
        val binding = host.cameraUiContainerBinding ?: return
        binding.hdrPlusSwitch?.setOnCheckedChangeListener { _, isChecked ->
            host.cameraViewModel.setHdrPlusEnabled(isChecked)
            settingsRepository.setHdrPlusEnabled(isChecked)
            updateHdrPlusUi()
            updateHdrPlusConstraints()
        }
        binding.hdrPlusPill?.setOnClickListener {
            binding.hdrPlusSwitch?.let { toggle ->
                if (toggle.visibility == View.VISIBLE) toggle.isChecked = !toggle.isChecked
            }
        }
    }
}
