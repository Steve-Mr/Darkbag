package top.maary.darkbag.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.StateFlow
import top.maary.darkbag.repository.DarkbagPreferences
import top.maary.darkbag.repository.SettingsRepository

/**
 * ViewModel for application settings, driving UI state reactively from SettingsRepository.
 * Uses @JvmOverloads constructor to ensure AndroidViewModelFactory reflection can instantiate it.
 */
class SettingsViewModel @JvmOverloads constructor(
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

    val repository: SettingsRepository get() = settingsRepository

    fun updateDefaultLensId(lensId: String?) {
        settingsRepository.setDefaultLensId(lensId)
    }

    fun updateDefaultFocal1x(focalName: String) {
        settingsRepository.setDefaultFocal1x(focalName)
    }

    fun updateHdrUnderexposureMode(mode: String) {
        settingsRepository.setHdrUnderexposureMode(mode)
    }

    fun updateBurstProcessingStrategy(strategy: String) {
        settingsRepository.setBurstProcessingStrategy(strategy)
    }

    fun updateShutterLongPressAction(action: String) {
        settingsRepository.setShutterLongPressAction(action)
    }

    fun updateRawOutputFormatIndex(index: Int) {
        settingsRepository.setRawOutputFormatIndex(index)
    }

    fun updateMultiCameraModeEnabled(enabled: Boolean) {
        settingsRepository.setMultiCameraModeEnabled(enabled)
    }

    fun updateMultiCameraForceEnable(enabled: Boolean) {
        settingsRepository.setMultiCameraForceEnable(enabled)
    }

    fun updateMultiCameraCountPref(pref: String) {
        settingsRepository.setMultiCameraCountPref(pref)
    }

    fun updateMultiCameraDualPair(pair: String) {
        settingsRepository.setMultiCameraDualPair(pair)
    }

    fun updateMultiCameraSaveRaw(saveRaw: Boolean) {
        settingsRepository.setMultiCameraSaveRaw(saveRaw)
    }

    fun updateHalfFrameMode(enabled: Boolean) {
        settingsRepository.setHalfFrameMode(enabled)
    }

    fun updateHalfFrameDownsample(enabled: Boolean) {
        settingsRepository.setHalfFrameDownsample(enabled)
    }

    fun updateHalfFrameDateStamp(enabled: Boolean) {
        settingsRepository.setHalfFrameDateStamp(enabled)
    }

    fun updateHalfFrameLightLeak(enabled: Boolean) {
        settingsRepository.setHalfFrameLightLeak(enabled)
    }

    fun updateHalfFrameSaveRaw(saveRaw: Boolean) {
        settingsRepository.setHalfFrameSaveRaw(saveRaw)
    }

    fun updateDefaultStartupPage(page: String) {
        settingsRepository.setDefaultStartupPage(page)
    }

    fun updateEnableCamera(enabled: Boolean) {
        settingsRepository.setEnableCamera(enabled)
    }

    fun updateEnablePlayground(enabled: Boolean) {
        settingsRepository.setEnablePlayground(enabled)
    }

    fun updateUseInternalViewer(useInternal: Boolean) {
        settingsRepository.setUseInternalViewer(useInternal)
    }

    fun updateExternalViewer(name: String, packageName: String) {
        settingsRepository.setExternalViewer(name, packageName)
    }

    fun updateMirrorFrontCamera(mirror: Boolean) {
        settingsRepository.setMirrorFrontCamera(mirror)
    }

    fun updateHdrPlusOis(enabled: Boolean) {
        settingsRepository.setHdrPlusOis(enabled)
    }

    fun updateForce60Fps(enabled: Boolean) {
        settingsRepository.setForce60Fps(enabled)
    }

    fun updateDebugEnabled(enabled: Boolean) {
        settingsRepository.setDebugEnabled(enabled)
    }

    fun updateSaveLocationEnabled(enabled: Boolean) {
        settingsRepository.setSaveLocationEnabled(enabled)
    }

    fun updateStorageUri(isJpg: Boolean, uri: String?, name: String?) {
        settingsRepository.setStorageUri(isJpg, uri, name)
    }

    fun updateUiTier(tierKey: String) {
        settingsRepository.setUiTier(tierKey)
    }

    fun updateLivePreview(enabled: Boolean) {
        settingsRepository.setLivePreview(enabled)
    }
}
