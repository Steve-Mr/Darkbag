package top.maary.darkbag.camera.delegate

import android.hardware.camera2.CameraCharacteristics
import android.view.View
import top.maary.darkbag.repository.SettingsRepository
import top.maary.darkbag.ui.CameraBentoDeckView
import top.maary.darkbag.utils.CameraRepository

/**
 * Owns the HDR+ tile/switch state and the mutually exclusive flash / underexposure button
 * (flash is hidden and disabled while HDR+ is active, the button then cycles underexposure).
 */
class CameraHdrPlusController(
    private val env: CameraEnvironmentHost,
    private val ui: CameraUiHost,
    private val coordinator: CameraCoordinatorHost,
    private val lutController: CameraLutController,
    private val lensController: CameraLensController
) {
    private val settingsRepository by lazy { SettingsRepository.getInstance(env.hostContext) }

    /** Seeds the flash flag and HDR+ state from preferences (call once when the view is created). */
    fun restorePersistedState() {
        val current = settingsRepository.current()
        env.runtimeState.isFlashEnabled = current.flashEnabled
        env.cameraViewModel.setHdrPlusEnabled(current.hdrPlusEnabled)
    }

    fun updateHdrPlusUi() {
        val binding = ui.cameraUiContainerBinding ?: return
        binding.hdrPlusSwitch?.isChecked = env.isHdrPlusEnabled
        CameraBentoDeckView.styleHdrPlusTile(binding, env.isHdrPlusEnabled)
    }

    fun updateHdrPlusConstraints() {
        val binding = ui.cameraUiContainerBinding ?: return
        val spec = CameraBentoDeckView.resolveVisibilitySpec(
            settingsRepository.current(),
            env.cameraViewModel.uiState.value.uiTier
        )
        val showFlashButton = spec.showUnderexposure

        if (env.isHdrPlusEnabled) {
            if (showFlashButton) {
                binding.flashButton?.visibility = View.VISIBLE
                lutController.updateUnderexposureButton()
            } else {
                binding.flashButton?.visibility = View.GONE
            }
            if (env.runtimeState.isFlashEnabled) {
                env.runtimeState.isFlashEnabled = false
                env.cameraViewModel.setFlashEnabled(false)
                if (showFlashButton) lutController.updateUnderexposureButton()
                coordinator.applyCameraControls()
            }
        } else {
            val hasFlash = hasFlashUnit()
            binding.flashButton?.visibility = if (hasFlash && showFlashButton) View.VISIBLE else View.GONE
            if (hasFlash) binding.flashButton?.let { lutController.updateFlashIcon(it) }
        }
        binding.btnProMode?.visibility = View.GONE
        ui.updateProInfoBar()
        lensController.updateLensControlRowPosition(animate = true)
    }

    private fun hasFlashUnit(): Boolean {
        val state = env.runtimeState
        val targetId = state.currentLens?.id
            ?: if (state.lensFacing == CameraCharacteristics.LENS_FACING_BACK) "0" else "1"
        return try {
            CameraRepository.getCharacteristics(env.camera2Manager, targetId)
                .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
        } catch (e: Exception) {
            false
        }
    }

    /** Binds the flash / underexposure button (behaviour depends on HDR+ state at click time). */
    fun bindFlashButton() {
        val btn = ui.cameraUiContainerBinding?.flashButton ?: return
        if (env.isHdrPlusEnabled) lutController.updateUnderexposureButton() else lutController.updateFlashIcon(btn)
        btn.setOnClickListener {
            if (env.isHdrPlusEnabled) {
                lutController.cycleUnderexposureMode()
            } else {
                val newFlash = !env.runtimeState.isFlashEnabled
                env.runtimeState.isFlashEnabled = newFlash
                env.cameraViewModel.setFlashEnabled(newFlash)
                settingsRepository.setFlashEnabled(newFlash)
                lutController.updateFlashIcon(btn)
                coordinator.applyCameraControls()
            }
        }
    }

    /** Binds the HDR+ switch and its pill tile. */
    fun bindHdrPlusControls() {
        val binding = ui.cameraUiContainerBinding ?: return
        binding.hdrPlusSwitch?.setOnCheckedChangeListener { _, isChecked ->
            env.cameraViewModel.setHdrPlusEnabled(isChecked)
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
