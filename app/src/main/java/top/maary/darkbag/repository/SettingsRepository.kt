package top.maary.darkbag.repository

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import top.maary.darkbag.fragments.CameraFragment
import top.maary.darkbag.fragments.SettingsFragment

/**
 * Reactive, strongly typed configuration repository that eliminates raw magic-string
 * SharedPreferences reads across Darkbag.
 */
class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(
        SettingsFragment.PREFS_NAME,
        Context.MODE_PRIVATE
    )

    private val _preferences = MutableStateFlow(readPreferencesFromDisk())
    val preferences: StateFlow<DarkbagPreferences> = _preferences.asStateFlow()

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        _preferences.value = readPreferencesFromDisk()
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun current(): DarkbagPreferences = _preferences.value

    private fun readPreferencesFromDisk(): DarkbagPreferences {
        return DarkbagPreferences(
            targetLog = prefs.getString(SettingsFragment.KEY_TARGET_LOG, "None") ?: "None",
            activeLut = prefs.getString(SettingsFragment.KEY_ACTIVE_LUT, "None") ?: "None",
            lutUri = prefs.getString(SettingsFragment.KEY_LUT_URI, null),
            saveJpg = prefs.getBoolean(SettingsFragment.KEY_SAVE_JPG, true),
            saveRaw = prefs.getBoolean(SettingsFragment.KEY_SAVE_RAW, false),
            useGpu = prefs.getBoolean(SettingsFragment.KEY_USE_GPU, true),
            enableManualControls = prefs.getBoolean(SettingsFragment.KEY_MANUAL_CONTROLS, true),
            enableLutPreview = prefs.getBoolean(SettingsFragment.KEY_ENABLE_LUT_PREVIEW, true),
            defaultLensId = prefs.getString(SettingsFragment.KEY_DEFAULT_LENS_ID, null),
            defaultFocal1x = try {
                prefs.getFloat(SettingsFragment.KEY_DEFAULT_FOCAL_1X, 0f)
            } catch (_: Exception) {
                0f
            },
            defaultFocal1xName = try {
                prefs.getString(SettingsFragment.KEY_DEFAULT_FOCAL_1X, null)
            } catch (_: Exception) {
                null
            },
            antibandingMode = prefs.getString(SettingsFragment.KEY_ANTIBANDING, "Auto") ?: "Auto",
            flashMode = prefs.getString(SettingsFragment.KEY_FLASH_MODE, "off") ?: "off",
            hdrBurstCount = try {
                prefs.getInt(SettingsFragment.KEY_HDR_BURST_COUNT, 5)
            } catch (_: Exception) {
                prefs.getString(SettingsFragment.KEY_HDR_BURST_COUNT, "5")?.toIntOrNull() ?: 5
            },
            hdrFusionMode = prefs.getString(SettingsFragment.KEY_HDR_FUSION_MODE, SettingsFragment.HDR_FUSION_AUTO) ?: SettingsFragment.HDR_FUSION_AUTO,
            burstProcessingStrategy = prefs.getString(SettingsFragment.KEY_BURST_PROCESSING_STRATEGY, SettingsFragment.BURST_STRATEGY_BALANCED) ?: SettingsFragment.BURST_STRATEGY_BALANCED,
            showHdrUnderexposureButton = prefs.getBoolean(SettingsFragment.KEY_SHOW_HDR_UNDEREXPOSURE_BUTTON, true),
            showHdrPlusSwitch = prefs.getBoolean(SettingsFragment.KEY_SHOW_HDR_PLUS_SWITCH, true),
            useInternalViewer = prefs.getBoolean(SettingsFragment.KEY_USE_INTERNAL_VIEWER, true),
            externalViewerPackage = prefs.getString(SettingsFragment.KEY_EXTERNAL_VIEWER_PACKAGE, null),
            showSettingsButton = prefs.getBoolean(SettingsFragment.KEY_SHOW_SETTINGS_BUTTON, true),
            showCameraSwitchButton = prefs.getBoolean(SettingsFragment.KEY_SHOW_CAMERA_SWITCH_BUTTON, true),
            showModeSwitchButton = prefs.getBoolean(SettingsFragment.KEY_SHOW_MODE_SWITCH_BUTTON, true),
            showLensControls = prefs.getBoolean(SettingsFragment.KEY_SHOW_LENS_CONTROLS, true),
            showLutSwitcher = prefs.getBoolean(SettingsFragment.KEY_SHOW_LUT_SWITCHER, true),
            mirrorFrontCamera = prefs.getBoolean(SettingsFragment.KEY_MIRROR_FRONT_CAMERA, false),
            hdrPlusOis = prefs.getBoolean(SettingsFragment.KEY_HDR_PLUS_OIS, true),
            force60Fps = prefs.getBoolean(SettingsFragment.KEY_FORCE_60FPS, false),
            debugEnabled = prefs.getBoolean(SettingsFragment.KEY_DEBUG_ENABLED, false),
            motionPhotoEnabled = prefs.getBoolean(SettingsFragment.KEY_MOTION_PHOTO, false),
            motionPhotoAudioEnabled = prefs.getBoolean(SettingsFragment.KEY_MOTION_PHOTO_AUDIO, false),
            saveLocationEnabled = prefs.getBoolean(SettingsFragment.KEY_SAVE_LOCATION, false),
            rawOutputType = prefs.getString(SettingsFragment.KEY_RAW_OUTPUT_TYPE, "Bayer CFA RAW (~12MB, Default)") ?: "Bayer CFA RAW (~12MB, Default)",
            dngCompressionMode = prefs.getInt(SettingsFragment.KEY_DNG_COMPRESSION_MODE, 0),
            jpgStorageUri = prefs.getString(SettingsFragment.KEY_JPG_STORAGE_URI, null),
            rawStorageUri = prefs.getString(SettingsFragment.KEY_RAW_STORAGE_URI, null),
            activeCaptureMode = prefs.getString(SettingsFragment.KEY_ACTIVE_CAPTURE_MODE, SettingsFragment.MODE_NORMAL) ?: SettingsFragment.MODE_NORMAL,
            multiCameraModeEnabled = prefs.getBoolean(SettingsFragment.KEY_MULTI_CAMERA_MODE, false),
            multiCameraForceEnable = prefs.getBoolean(SettingsFragment.KEY_MULTI_CAMERA_FORCE_ENABLE, false),
            multiCameraSaveRaw = prefs.getBoolean(SettingsFragment.KEY_MULTI_CAMERA_SAVE_RAW, false),
            defaultStartupPage = prefs.getString(SettingsFragment.KEY_DEFAULT_STARTUP, SettingsFragment.STARTUP_CAMERA) ?: SettingsFragment.STARTUP_CAMERA,
            onboardingCompleted = prefs.getBoolean(SettingsFragment.KEY_ONBOARDING_COMPLETED, false),
            expFocusPeaking = prefs.getBoolean(SettingsFragment.KEY_EXP_FOCUS_PEAKING, false),
            focusPeakingActive = prefs.getBoolean(SettingsFragment.KEY_FOCUS_PEAKING_ACTIVE, false),
            colorEngineMode = prefs.getInt(SettingsFragment.KEY_COLOR_ENGINE_MODE, 0),
            shutterLongPressAction = prefs.getString(SettingsFragment.KEY_SHUTTER_LONG_PRESS_ACTION, SettingsFragment.SHUTTER_LONG_PRESS_MP4) ?: SettingsFragment.SHUTTER_LONG_PRESS_MP4,
            rawVideoFps = prefs.getString(SettingsFragment.KEY_RAW_VIDEO_FPS, "24") ?: "24",
            rawVideoResolution = prefs.getString(SettingsFragment.KEY_RAW_VIDEO_RESOLUTION, SettingsFragment.DEFAULT_RAW_VIDEO_RESOLUTION) ?: SettingsFragment.DEFAULT_RAW_VIDEO_RESOLUTION,
            hdrUnderexposureMode = prefs.getString(SettingsFragment.KEY_HDR_UNDEREXPOSURE_MODE, "Dynamic") ?: "Dynamic",
            enableCamera = prefs.getBoolean(SettingsFragment.KEY_ENABLE_CAMERA, true),
            enablePlayground = prefs.getBoolean(SettingsFragment.KEY_ENABLE_PLAYGROUND, true),
            halfFrameMode = prefs.getBoolean(SettingsFragment.KEY_HALF_FRAME_MODE, false),
            halfFrameDownsample = prefs.getBoolean(SettingsFragment.KEY_HALF_FRAME_DOWNSAMPLE, true),
            halfFrameDateStamp = prefs.getBoolean(SettingsFragment.KEY_HALF_FRAME_DATE_STAMP, false),
            halfFrameLightLeak = prefs.getBoolean(SettingsFragment.KEY_HALF_FRAME_LIGHT_LEAK, false),
            halfFrameSaveRaw = prefs.getBoolean(SettingsFragment.KEY_HALF_FRAME_SAVE_RAW, false),
            externalViewerName = prefs.getString(SettingsFragment.KEY_EXTERNAL_VIEWER_NAME, null),
            jpgStorageUriName = prefs.getString(SettingsFragment.KEY_JPG_STORAGE_URI_NAME, null),
            rawStorageUriName = prefs.getString(SettingsFragment.KEY_RAW_STORAGE_URI_NAME, null),
            multiCameraCountPref = prefs.getString(SettingsFragment.KEY_MULTI_CAMERA_COUNT_PREF, "auto_max") ?: "auto_max",
            multiCameraDualPair = prefs.getString(SettingsFragment.KEY_MULTI_CAMERA_DUAL_PAIR, "wide_ultrawide") ?: "wide_ultrawide",
            uiTier = prefs.getString(top.maary.darkbag.ui.CameraBentoDeckView.PREF_KEY_UI_TIER, "film") ?: "film",
            rawOutputFormatIndex = try {
                prefs.getInt(SettingsFragment.KEY_RAW_OUTPUT_TYPE, 0)
            } catch (_: Exception) {
                0
            }
        )
    }

    fun setTargetLog(targetLog: String) {
        prefs.edit().putString(SettingsFragment.KEY_TARGET_LOG, targetLog).apply()
    }

    fun setActiveLut(lutName: String, uriString: String? = null) {
        prefs.edit()
            .putString(SettingsFragment.KEY_ACTIVE_LUT, lutName)
            .putString(SettingsFragment.KEY_LUT_URI, uriString)
            .apply()
    }

    fun setSaveJpg(saveJpg: Boolean) {
        prefs.edit().putBoolean(SettingsFragment.KEY_SAVE_JPG, saveJpg).apply()
    }

    fun setSaveRaw(saveRaw: Boolean) {
        prefs.edit().putBoolean(SettingsFragment.KEY_SAVE_RAW, saveRaw).apply()
    }

    fun setMotionPhotoEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(SettingsFragment.KEY_MOTION_PHOTO, enabled).apply()
    }

    fun setMotionPhotoAudioEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(SettingsFragment.KEY_MOTION_PHOTO_AUDIO, enabled).apply()
    }

    fun setActiveCaptureMode(mode: String) {
        prefs.edit().putString(SettingsFragment.KEY_ACTIVE_CAPTURE_MODE, mode).apply()
    }

    fun setHdrFusionMode(mode: String) {
        prefs.edit().putString(SettingsFragment.KEY_HDR_FUSION_MODE, mode).apply()
    }

    fun setHdrBurstCount(count: Int) {
        prefs.edit().putInt(SettingsFragment.KEY_HDR_BURST_COUNT, count).apply()
    }

    fun setDngCompressionMode(mode: Int) {
        prefs.edit().putInt(SettingsFragment.KEY_DNG_COMPRESSION_MODE, mode).apply()
    }

    fun setColorEngineMode(mode: Int) {
        prefs.edit().putInt(SettingsFragment.KEY_COLOR_ENGINE_MODE, mode).apply()
    }

    fun setFlashMode(flashMode: String) {
        prefs.edit().putString(SettingsFragment.KEY_FLASH_MODE, flashMode).apply()
    }

    fun setAntibandingMode(mode: String) {
        prefs.edit().putString(SettingsFragment.KEY_ANTIBANDING, mode).apply()
    }

    fun setFocusPeakingActive(active: Boolean) {
        prefs.edit().putBoolean(SettingsFragment.KEY_FOCUS_PEAKING_ACTIVE, active).apply()
    }

    fun edit(action: SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(action).apply()
    }

    fun setDefaultLensId(lensId: String?) {
        val editor = prefs.edit()
        if (lensId != null) {
            editor.putString(SettingsFragment.KEY_DEFAULT_LENS_ID, lensId)
        } else {
            editor.remove(SettingsFragment.KEY_DEFAULT_LENS_ID)
        }
        editor.remove(CameraFragment.KEY_SELECTED_LENS_ID)
        editor.apply()
    }

    fun setDefaultFocal1x(focalName: String) {
        prefs.edit().putString(SettingsFragment.KEY_DEFAULT_FOCAL_1X, focalName).apply()
    }

    fun setHdrUnderexposureMode(mode: String) {
        prefs.edit().putString(SettingsFragment.KEY_HDR_UNDEREXPOSURE_MODE, mode).apply()
    }

    fun setBurstProcessingStrategy(strategy: String) {
        prefs.edit().putString(SettingsFragment.KEY_BURST_PROCESSING_STRATEGY, strategy).apply()
    }

    fun setShutterLongPressAction(action: String) {
        prefs.edit().putString(SettingsFragment.KEY_SHUTTER_LONG_PRESS_ACTION, action).apply()
    }

    fun setRawOutputFormatIndex(index: Int) {
        prefs.edit().putInt(SettingsFragment.KEY_RAW_OUTPUT_TYPE, index).apply()
    }

    fun setMultiCameraModeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(SettingsFragment.KEY_MULTI_CAMERA_MODE, enabled).apply()
    }

    fun setMultiCameraForceEnable(enabled: Boolean) {
        prefs.edit().putBoolean(SettingsFragment.KEY_MULTI_CAMERA_FORCE_ENABLE, enabled).apply()
    }

    fun setMultiCameraCountPref(pref: String) {
        prefs.edit().putString(SettingsFragment.KEY_MULTI_CAMERA_COUNT_PREF, pref).apply()
    }

    fun setMultiCameraDualPair(pair: String) {
        prefs.edit().putString(SettingsFragment.KEY_MULTI_CAMERA_DUAL_PAIR, pair).apply()
    }

    fun setMultiCameraSaveRaw(saveRaw: Boolean) {
        prefs.edit().putBoolean(SettingsFragment.KEY_MULTI_CAMERA_SAVE_RAW, saveRaw).apply()
    }

    fun setHalfFrameMode(enabled: Boolean) {
        prefs.edit().putBoolean(SettingsFragment.KEY_HALF_FRAME_MODE, enabled).apply()
    }

    fun setHalfFrameDownsample(enabled: Boolean) {
        prefs.edit().putBoolean(SettingsFragment.KEY_HALF_FRAME_DOWNSAMPLE, enabled).apply()
    }

    fun setHalfFrameDateStamp(enabled: Boolean) {
        prefs.edit().putBoolean(SettingsFragment.KEY_HALF_FRAME_DATE_STAMP, enabled).apply()
    }

    fun setHalfFrameLightLeak(enabled: Boolean) {
        prefs.edit().putBoolean(SettingsFragment.KEY_HALF_FRAME_LIGHT_LEAK, enabled).apply()
    }

    fun setHalfFrameSaveRaw(saveRaw: Boolean) {
        prefs.edit().putBoolean(SettingsFragment.KEY_HALF_FRAME_SAVE_RAW, saveRaw).apply()
    }

    fun setDefaultStartupPage(page: String) {
        prefs.edit().putString(SettingsFragment.KEY_DEFAULT_STARTUP, page).apply()
    }

    fun setEnableCamera(enabled: Boolean) {
        prefs.edit().putBoolean(SettingsFragment.KEY_ENABLE_CAMERA, enabled).apply()
    }

    fun setEnablePlayground(enabled: Boolean) {
        prefs.edit().putBoolean(SettingsFragment.KEY_ENABLE_PLAYGROUND, enabled).apply()
    }

    fun setUseInternalViewer(useInternal: Boolean) {
        prefs.edit().putBoolean(SettingsFragment.KEY_USE_INTERNAL_VIEWER, useInternal).apply()
    }

    fun setExternalViewer(name: String, packageName: String) {
        prefs.edit()
            .putString(SettingsFragment.KEY_EXTERNAL_VIEWER_NAME, name)
            .putString(SettingsFragment.KEY_EXTERNAL_VIEWER_PACKAGE, packageName)
            .apply()
    }

    fun setMirrorFrontCamera(mirror: Boolean) {
        prefs.edit().putBoolean(SettingsFragment.KEY_MIRROR_FRONT_CAMERA, mirror).apply()
    }

    fun setHdrPlusOis(enabled: Boolean) {
        prefs.edit().putBoolean(SettingsFragment.KEY_HDR_PLUS_OIS, enabled).apply()
    }

    fun setForce60Fps(enabled: Boolean) {
        prefs.edit().putBoolean(SettingsFragment.KEY_FORCE_60FPS, enabled).apply()
    }

    fun setDebugEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(SettingsFragment.KEY_DEBUG_ENABLED, enabled).apply()
    }

    fun setSaveLocationEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(SettingsFragment.KEY_SAVE_LOCATION, enabled).apply()
    }

    fun setStorageUri(isJpg: Boolean, uri: String?, name: String?) {
        val uriKey = if (isJpg) SettingsFragment.KEY_JPG_STORAGE_URI else SettingsFragment.KEY_RAW_STORAGE_URI
        val nameKey = if (isJpg) SettingsFragment.KEY_JPG_STORAGE_URI_NAME else SettingsFragment.KEY_RAW_STORAGE_URI_NAME
        val editor = prefs.edit()
        if (uri != null) {
            editor.putString(uriKey, uri)
            editor.putString(nameKey, name)
        } else {
            editor.remove(uriKey)
            editor.remove(nameKey)
        }
        editor.apply()
    }

    fun setUiTier(tierKey: String) {
        prefs.edit().putString(top.maary.darkbag.ui.CameraBentoDeckView.PREF_KEY_UI_TIER, tierKey).apply()
    }

    fun setLivePreview(enabled: Boolean) {
        prefs.edit().putBoolean(SettingsFragment.KEY_ENABLE_LUT_PREVIEW, enabled).apply()
    }

    companion object {
        @Volatile
        private var instance: SettingsRepository? = null

        fun getInstance(context: Context): SettingsRepository {
            return instance ?: synchronized(this) {
                instance ?: SettingsRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
