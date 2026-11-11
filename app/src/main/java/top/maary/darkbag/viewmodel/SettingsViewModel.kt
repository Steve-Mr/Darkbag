package top.maary.darkbag.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.StateFlow
import top.maary.darkbag.repository.DarkbagPreferences
import top.maary.darkbag.repository.SettingsRepository

/**
 * ViewModel for application settings, driving UI state reactively from SettingsRepository.
 */
class SettingsViewModel(
    application: Application,
    private val settingsRepository: SettingsRepository = SettingsRepository.getInstance(application)
) : AndroidViewModel(application) {

    val preferences: StateFlow<DarkbagPreferences> = settingsRepository.preferences

    fun updateTargetLog(log: String) {
        settingsRepository.setTargetLog(log)
    }

    fun updateActiveLut(lut: String, uri: String? = null) {
        settingsRepository.setActiveLut(lut, uri)
    }

    fun updateSaveJpg(save: Boolean) {
        settingsRepository.setSaveJpg(save)
    }

    fun updateSaveRaw(save: Boolean) {
        settingsRepository.setSaveRaw(save)
    }

    fun updateMotionPhoto(enabled: Boolean) {
        settingsRepository.setMotionPhotoEnabled(enabled)
    }

    fun updateMotionPhotoAudio(enabled: Boolean) {
        settingsRepository.setMotionPhotoAudioEnabled(enabled)
    }

    fun updateCaptureMode(mode: String) {
        settingsRepository.setActiveCaptureMode(mode)
    }

    fun updateHdrFusionMode(mode: String) {
        settingsRepository.setHdrFusionMode(mode)
    }

    fun updateHdrBurstCount(count: Int) {
        settingsRepository.setHdrBurstCount(count)
    }

    fun updateDngCompressionMode(mode: Int) {
        settingsRepository.setDngCompressionMode(mode)
    }

    fun updateColorEngineMode(mode: Int) {
        settingsRepository.setColorEngineMode(mode)
    }

    fun updateFlashMode(flashMode: String) {
        settingsRepository.setFlashMode(flashMode)
    }

    fun updateAntibandingMode(mode: String) {
        settingsRepository.setAntibandingMode(mode)
    }

    fun updateFocusPeakingActive(active: Boolean) {
        settingsRepository.setFocusPeakingActive(active)
    }
}
