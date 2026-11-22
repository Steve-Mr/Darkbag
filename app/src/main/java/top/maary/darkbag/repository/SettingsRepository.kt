package top.maary.darkbag.repository

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.util.Collections
import java.util.WeakHashMap

/**
 * Reactive, strongly typed configuration repository backed by Jetpack DataStore
 * ([DarkbagDataStoreProvider]). Eliminates raw SharedPreferences reads and writes across Darkbag.
 */
class SettingsRepository(context: Context) {

    private val store: DarkbagPreferencesStore = DarkbagDataStoreProvider.getStore(context)

    private val _preferences = MutableStateFlow(decodePreferences(store.current()))
    val preferences: StateFlow<DarkbagPreferences> = _preferences.asStateFlow()
    val wbStrategy: Flow<Int> = preferences.map { it.wbStrategy }.distinctUntilChanged()

    private val storeListener: (Preferences) -> Unit = { prefs ->
        _preferences.value = decodePreferences(prefs)
    }

    init {
        val initial = store.addListenerAndCurrent(storeListener)
        _preferences.value = decodePreferences(initial)
    }

    val isHydrated: Boolean
        get() = store.isHydratedSnapshot

    suspend fun awaitHydrated() {
        store.awaitHydrated()
    }

    @VisibleForTesting
    fun awaitHydratedBlocking() {
        store.awaitHydratedBlocking()
    }

    fun current(): DarkbagPreferences = _preferences.value

    fun rawSnapshot(): Preferences = store.current()

    fun hasExplicitKey(key: Preferences.Key<*>): Boolean = store.current().contains(key)

    suspend fun awaitPersisted() {
        store.awaitIdle()
    }

    fun edit(action: MutablePreferences.() -> Unit) {
        store.updateAsync(action)
    }

    suspend fun editAndAwait(action: MutablePreferences.() -> Unit) {
        store.update(action)
    }

    private fun decodePreferences(prefs: Preferences): DarkbagPreferences {
        val rawMap = prefs.asMap().entries.associate { it.key.name to it.value }

        fun bool(key: String, default: Boolean): Boolean = when (val v = rawMap[key]) {
            is Boolean -> v
            is String -> v.toBooleanStrictOrNull() ?: default
            else -> default
        }

        fun int(key: String, default: Int): Int = when (val v = rawMap[key]) {
            is Int -> v
            is Long -> v.toInt()
            is String -> v.toIntOrNull() ?: default
            else -> default
        }

        fun str(key: String, default: String? = null): String? = when (val v = rawMap[key]) {
            is String -> v
            else -> default
        }

        val flashEnabled = when (val v = rawMap[PreferenceKeys.KEY_FLASH_MODE]) {
            is Boolean -> v
            is String -> v.equals("on", ignoreCase = true)
            else -> false
        }

        val rawFormatIndex = when (val v = rawMap[PreferenceKeys.KEY_RAW_OUTPUT_TYPE]) {
            is Int -> v
            is String -> if (v.contains("Linear")) 1 else (v.toIntOrNull() ?: 0)
            else -> 0
        }
        val rawTypeName = if (rawFormatIndex == 1) {
            "Linear DNG RAW (~36MB, Sabre/Merged)"
        } else {
            "Bayer CFA RAW (~12MB, Default)"
        }

        val focalRaw = rawMap[PreferenceKeys.KEY_DEFAULT_FOCAL_1X]
        val defaultFocal1xStr: String? = focalRaw as? String
        val defaultFocal1xFloat: Float = when (focalRaw) {
            is Float -> focalRaw
            is Int -> focalRaw.toFloat()
            is String -> focalRaw.filter { it.isDigit() || it == '.' }.toFloatOrNull() ?: 0f
            else -> 0f
        }
        val resolvedFocal1xName = defaultFocal1xStr
            ?: if (defaultFocal1xFloat > 0f) "${defaultFocal1xFloat.toInt()}mm" else null

        val burstCount = when (val v = rawMap[PreferenceKeys.KEY_HDR_BURST_COUNT]) {
            is Int -> v
            is String -> v.toIntOrNull() ?: 5
            else -> 5
        }

        val lensFacingValue = when (val v = rawMap[PreferenceKeys.KEY_LENS_FACING]) {
            is Int -> v
            else -> null
        }

        return DarkbagPreferences(
            targetLog = str(PreferenceKeys.KEY_TARGET_LOG, "None") ?: "None",
            activeLut = str(PreferenceKeys.KEY_ACTIVE_LUT, "None") ?: "None",
            lutUri = str(PreferenceKeys.KEY_LUT_URI),
            saveJpg = bool(PreferenceKeys.KEY_SAVE_JPG, true),
            saveRaw = bool(PreferenceKeys.KEY_SAVE_RAW, false),
            useGpu = bool(PreferenceKeys.KEY_USE_GPU, true),
            enableManualControls = bool(PreferenceKeys.KEY_MANUAL_CONTROLS, false),
            enableLutPreview = bool(PreferenceKeys.KEY_ENABLE_LUT_PREVIEW, true),
            defaultLensId = str(PreferenceKeys.KEY_DEFAULT_LENS_ID),
            defaultFocal1x = defaultFocal1xFloat,
            defaultFocal1xName = resolvedFocal1xName,
            antibandingMode = str(PreferenceKeys.KEY_ANTIBANDING, "Auto") ?: "Auto",
            flashMode = if (flashEnabled) "on" else "off",
            flashEnabled = flashEnabled,
            hdrBurstCount = burstCount,
            hdrFusionMode = str(PreferenceKeys.KEY_HDR_FUSION_MODE, PreferenceKeys.HDR_FUSION_AUTO)
                ?: PreferenceKeys.HDR_FUSION_AUTO,
            burstProcessingStrategy = str(
                PreferenceKeys.KEY_BURST_PROCESSING_STRATEGY,
                PreferenceKeys.BURST_STRATEGY_BALANCED
            ) ?: PreferenceKeys.BURST_STRATEGY_BALANCED,
            showHdrUnderexposureButton = bool(PreferenceKeys.KEY_SHOW_HDR_UNDEREXPOSURE_BUTTON, true),
            showHdrPlusSwitch = bool(PreferenceKeys.KEY_SHOW_HDR_PLUS_SWITCH, true),
            useInternalViewer = bool(PreferenceKeys.KEY_USE_INTERNAL_VIEWER, true),
            externalViewerPackage = str(PreferenceKeys.KEY_EXTERNAL_VIEWER_PACKAGE),
            showSettingsButton = bool(PreferenceKeys.KEY_SHOW_SETTINGS_BUTTON, true),
            showCameraSwitchButton = bool(PreferenceKeys.KEY_SHOW_CAMERA_SWITCH_BUTTON, true),
            showModeSwitchButton = bool(PreferenceKeys.KEY_SHOW_MODE_SWITCH_BUTTON, true),
            showLensControls = bool(PreferenceKeys.KEY_SHOW_LENS_CONTROLS, true),
            showLutSwitcher = bool(PreferenceKeys.KEY_SHOW_LUT_SWITCHER, true),
            mirrorFrontCamera = bool(PreferenceKeys.KEY_MIRROR_FRONT_CAMERA, true),
            hdrPlusOis = bool(PreferenceKeys.KEY_HDR_PLUS_OIS, true),
            force60Fps = bool(PreferenceKeys.KEY_FORCE_60FPS, false),
            zslEnabled = bool(PreferenceKeys.KEY_ZSL_ENABLED, false),
            zslAdaptive = bool(PreferenceKeys.KEY_ZSL_ADAPTIVE, true),
            zslLazyRing = bool(PreferenceKeys.KEY_ZSL_LAZY_RING, false),
            zslRoute = str(PreferenceKeys.KEY_ZSL_ROUTE, "none") ?: "none",
            zslReason = str(PreferenceKeys.KEY_ZSL_REASON, "none") ?: "none",
            debugEnabled = bool(PreferenceKeys.KEY_DEBUG_ENABLED, false),
            motionPhotoEnabled = bool(PreferenceKeys.KEY_MOTION_PHOTO, false),
            motionPhotoAudioEnabled = bool(PreferenceKeys.KEY_MOTION_PHOTO_AUDIO, true),
            saveLocationEnabled = bool(PreferenceKeys.KEY_SAVE_LOCATION, false),
            rawOutputType = rawTypeName,
            dngCompressionMode = int(PreferenceKeys.KEY_DNG_COMPRESSION_MODE, 0),
            jpgStorageUri = str(PreferenceKeys.KEY_JPG_STORAGE_URI),
            rawStorageUri = str(PreferenceKeys.KEY_RAW_STORAGE_URI),
            lastCaptureUri = str(PreferenceKeys.KEY_LAST_CAPTURE_URI),
            activeCaptureMode = str(PreferenceKeys.KEY_ACTIVE_CAPTURE_MODE, PreferenceKeys.MODE_NORMAL)
                ?: PreferenceKeys.MODE_NORMAL,
            multiCameraModeEnabled = bool(PreferenceKeys.KEY_MULTI_CAMERA_MODE, false),
            multiCameraForceEnable = bool(PreferenceKeys.KEY_MULTI_CAMERA_FORCE_ENABLE, false),
            multiCameraSaveRaw = bool(PreferenceKeys.KEY_MULTI_CAMERA_SAVE_RAW, false),
            multiCameraManualLinked = bool(PreferenceKeys.KEY_MULTI_CAMERA_MANUAL_LINKED, true),
            defaultStartupPage = str(PreferenceKeys.KEY_DEFAULT_STARTUP, PreferenceKeys.STARTUP_CAMERA)
                ?: PreferenceKeys.STARTUP_CAMERA,
            onboardingCompleted = bool(PreferenceKeys.KEY_ONBOARDING_COMPLETED, false),
            expFocusPeaking = bool(PreferenceKeys.KEY_EXP_FOCUS_PEAKING, false),
            expMultiCamColorHarmonization = bool(PreferenceKeys.KEY_EXP_MULTICAM_COLOR_HARMONIZATION, true),
            expRawMotionPhoto = bool(PreferenceKeys.KEY_EXP_RAW_MOTION_PHOTO, false),
            focusPeakingActive = bool(PreferenceKeys.KEY_FOCUS_PEAKING_ACTIVE, true),
            colorEngineMode = int(PreferenceKeys.KEY_COLOR_ENGINE_MODE, 2),
            wbStrategy = int(PreferenceKeys.KEY_WB_STRATEGY, PreferenceKeys.WB_STRATEGY_AMBIENCE),
            shutterLongPressAction = str(
                PreferenceKeys.KEY_SHUTTER_LONG_PRESS_ACTION,
                PreferenceKeys.SHUTTER_LONG_PRESS_MP4
            ) ?: PreferenceKeys.SHUTTER_LONG_PRESS_MP4,
            rawVideoFps = str(PreferenceKeys.KEY_RAW_VIDEO_FPS, "24") ?: "24",
            rawVideoResolution = str(
                PreferenceKeys.KEY_RAW_VIDEO_RESOLUTION,
                PreferenceKeys.DEFAULT_RAW_VIDEO_RESOLUTION
            ) ?: PreferenceKeys.DEFAULT_RAW_VIDEO_RESOLUTION,
            hdrUnderexposureMode = str(PreferenceKeys.KEY_HDR_UNDEREXPOSURE_MODE, "Dynamic (Experimental)")
                ?: "Dynamic (Experimental)",
            enableCamera = bool(PreferenceKeys.KEY_ENABLE_CAMERA, true),
            enablePlayground = bool(PreferenceKeys.KEY_ENABLE_PLAYGROUND, true),
            showFloatingToolbar = bool(PreferenceKeys.KEY_SHOW_FLOATING_TOOLBAR, false),
            halfFrameMode = bool(PreferenceKeys.KEY_HALF_FRAME_MODE, false),
            halfFrameLayout = str(PreferenceKeys.KEY_HALF_FRAME_LAYOUT, PreferenceKeys.HALF_FRAME_LAYOUT_SBS)
                ?: PreferenceKeys.HALF_FRAME_LAYOUT_SBS,
            halfFrameDownsample = bool(PreferenceKeys.KEY_HALF_FRAME_DOWNSAMPLE, true),
            halfFrameDateStamp = bool(PreferenceKeys.KEY_HALF_FRAME_DATE_STAMP, false),
            halfFrameLightLeak = bool(PreferenceKeys.KEY_HALF_FRAME_LIGHT_LEAK, false),
            halfFrameAutoBurst = bool(PreferenceKeys.KEY_HALF_FRAME_AUTO_BURST, false),
            halfFrameSaveRaw = bool(PreferenceKeys.KEY_HALF_FRAME_SAVE_RAW, false),
            externalViewerName = str(PreferenceKeys.KEY_EXTERNAL_VIEWER_NAME),
            jpgStorageUriName = str(PreferenceKeys.KEY_JPG_STORAGE_URI_NAME),
            rawStorageUriName = str(PreferenceKeys.KEY_RAW_STORAGE_URI_NAME),
            multiCameraCountPref = str(PreferenceKeys.KEY_MULTI_CAMERA_COUNT_PREF, "auto_max") ?: "auto_max",
            multiCameraDualPair = str(PreferenceKeys.KEY_MULTI_CAMERA_DUAL_PAIR, "wide_ultrawide")
                ?: "wide_ultrawide",
            uiTier = str(PreferenceKeys.KEY_UI_TIER, "film") ?: "film",
            rawOutputFormatIndex = rawFormatIndex,
            selectedLensSensorId = str(PreferenceKeys.KEY_SELECTED_LENS_SENSOR_ID),
            selectedLensId = str(PreferenceKeys.KEY_SELECTED_LENS_ID),
            lensFacing = lensFacingValue,
            hdrPlusEnabled = bool(PreferenceKeys.KEY_HDR_PLUS_ENABLED, true),
            viewerMotionPhotoAutoPlay = bool(PreferenceKeys.KEY_VIEWER_MOTION_PHOTO_AUTO_PLAY, true),
            galleryColumnsPortrait = int(PreferenceKeys.KEY_GALLERY_COLUMNS_PORTRAIT, 3),
            galleryColumnsLandscape = int(PreferenceKeys.KEY_GALLERY_COLUMNS_LANDSCAPE, 4),
            galleryWaterfall = bool(PreferenceKeys.KEY_GALLERY_WATERFALL, false),
            playgroundColumns = int(PreferenceKeys.KEY_PLAYGROUND_COLUMNS, 2)
        )
    }

    fun setTargetLog(targetLog: String) {
        edit { this[PreferenceKeys.TARGET_LOG] = targetLog }
    }

    fun setActiveLut(lutName: String?, uriString: String? = null) {
        edit {
            if (lutName == null) {
                remove(PreferenceKeys.ACTIVE_LUT)
            } else {
                this[PreferenceKeys.ACTIVE_LUT] = lutName
            }
            if (uriString == null) {
                remove(PreferenceKeys.LUT_URI)
            } else {
                this[PreferenceKeys.LUT_URI] = uriString
            }
        }
    }

    fun setSaveJpg(saveJpg: Boolean) {
        edit { this[PreferenceKeys.SAVE_JPG] = saveJpg }
    }

    fun setSaveRaw(saveRaw: Boolean) {
        edit { this[PreferenceKeys.SAVE_RAW] = saveRaw }
    }

    fun setMotionPhotoEnabled(enabled: Boolean) {
        edit { this[PreferenceKeys.MOTION_PHOTO] = enabled }
    }

    fun setMotionPhotoAudioEnabled(enabled: Boolean) {
        edit { this[PreferenceKeys.MOTION_PHOTO_AUDIO] = enabled }
    }

    fun setActiveCaptureMode(mode: String) {
        edit { this[PreferenceKeys.ACTIVE_CAPTURE_MODE] = mode }
    }

    fun setHdrFusionMode(mode: String) {
        edit { this[PreferenceKeys.HDR_FUSION_MODE] = mode }
    }

    fun setHdrBurstCount(count: Int) {
        edit { this[PreferenceKeys.HDR_BURST_COUNT] = count }
    }

    fun setDngCompressionMode(mode: Int) {
        edit { this[PreferenceKeys.DNG_COMPRESSION_MODE] = mode }
    }

    fun setColorEngineMode(mode: Int) {
        edit { this[PreferenceKeys.COLOR_ENGINE_MODE] = mode }
    }

    fun setWbStrategy(strategy: Int) {
        edit { this[PreferenceKeys.WB_STRATEGY] = strategy }
    }

    fun setFlashMode(flashMode: String) {
        val enabled = flashMode.equals("on", ignoreCase = true)
        edit { this[PreferenceKeys.FLASH_MODE] = enabled }
    }

    fun setFlashEnabled(enabled: Boolean) {
        edit { this[PreferenceKeys.FLASH_MODE] = enabled }
    }

    fun setAntibandingMode(mode: String) {
        edit { this[PreferenceKeys.ANTIBANDING] = mode }
    }

    fun setFocusPeakingActive(active: Boolean) {
        edit { this[PreferenceKeys.FOCUS_PEAKING_ACTIVE] = active }
    }

    fun setDefaultLensId(lensId: String?) {
        edit {
            if (lensId != null) {
                this[PreferenceKeys.DEFAULT_LENS_ID] = lensId
            } else {
                remove(PreferenceKeys.DEFAULT_LENS_ID)
            }
            remove(PreferenceKeys.SELECTED_LENS_SENSOR_ID)
        }
    }

    fun setDefaultFocal1x(focalName: String) {
        edit { this[PreferenceKeys.DEFAULT_FOCAL_1X] = focalName }
    }

    fun setHdrUnderexposureMode(mode: String) {
        edit { this[PreferenceKeys.HDR_UNDEREXPOSURE_MODE] = mode }
    }

    fun setBurstProcessingStrategy(strategy: String) {
        edit { this[PreferenceKeys.BURST_PROCESSING_STRATEGY] = strategy }
    }

    fun setShutterLongPressAction(action: String) {
        edit { this[PreferenceKeys.SHUTTER_LONG_PRESS_ACTION] = action }
    }

    fun setRawOutputFormatIndex(index: Int) {
        edit { this[PreferenceKeys.RAW_OUTPUT_TYPE] = index }
    }

    fun setRawOutputType(type: String) {
        val index = if (type.contains("Linear")) 1 else 0
        edit { this[PreferenceKeys.RAW_OUTPUT_TYPE] = index }
    }

    fun setMultiCameraModeEnabled(enabled: Boolean) {
        edit { this[PreferenceKeys.MULTI_CAMERA_MODE] = enabled }
    }

    fun setMultiCameraForceEnable(enabled: Boolean) {
        edit { this[PreferenceKeys.MULTI_CAMERA_FORCE_ENABLE] = enabled }
    }

    fun setMultiCameraCountPref(pref: String) {
        edit { this[PreferenceKeys.MULTI_CAMERA_COUNT_PREF] = pref }
    }

    fun setMultiCameraDualPair(pair: String) {
        edit { this[PreferenceKeys.MULTI_CAMERA_DUAL_PAIR] = pair }
    }

    fun setMultiCameraSaveRaw(saveRaw: Boolean) {
        edit { this[PreferenceKeys.MULTI_CAMERA_SAVE_RAW] = saveRaw }
    }

    fun setMultiCameraManualLinked(linked: Boolean) {
        edit { this[PreferenceKeys.MULTI_CAMERA_MANUAL_LINKED] = linked }
    }

    fun setHalfFrameMode(enabled: Boolean) {
        edit { this[PreferenceKeys.HALF_FRAME_MODE] = enabled }
    }

    fun setHalfFrameLayout(layout: String) {
        edit { this[PreferenceKeys.HALF_FRAME_LAYOUT] = layout }
    }

    fun setHalfFrameDownsample(enabled: Boolean) {
        edit { this[PreferenceKeys.HALF_FRAME_DOWNSAMPLE] = enabled }
    }

    fun setHalfFrameDateStamp(enabled: Boolean) {
        edit { this[PreferenceKeys.HALF_FRAME_DATE_STAMP] = enabled }
    }

    fun setHalfFrameLightLeak(enabled: Boolean) {
        edit { this[PreferenceKeys.HALF_FRAME_LIGHT_LEAK] = enabled }
    }

    fun setHalfFrameAutoBurst(enabled: Boolean) {
        edit { this[PreferenceKeys.HALF_FRAME_AUTO_BURST] = enabled }
    }

    fun setHalfFrameSaveRaw(saveRaw: Boolean) {
        edit { this[PreferenceKeys.HALF_FRAME_SAVE_RAW] = saveRaw }
    }

    fun setDefaultStartupPage(page: String) {
        edit { this[PreferenceKeys.DEFAULT_STARTUP] = page }
    }

    fun setOnboardingCompleted(completed: Boolean) {
        edit { this[PreferenceKeys.ONBOARDING_COMPLETED] = completed }
    }

    fun setEnableCamera(enabled: Boolean) {
        edit { this[PreferenceKeys.ENABLE_CAMERA] = enabled }
    }

    fun setEnablePlayground(enabled: Boolean) {
        edit { this[PreferenceKeys.ENABLE_PLAYGROUND] = enabled }
    }

    fun setUseInternalViewer(useInternal: Boolean) {
        edit { this[PreferenceKeys.USE_INTERNAL_VIEWER] = useInternal }
    }

    fun setExternalViewer(name: String, packageName: String) {
        edit {
            this[PreferenceKeys.EXTERNAL_VIEWER_NAME] = name
            this[PreferenceKeys.EXTERNAL_VIEWER_PACKAGE] = packageName
        }
    }

    fun setMirrorFrontCamera(mirror: Boolean) {
        edit { this[PreferenceKeys.MIRROR_FRONT_CAMERA] = mirror }
    }

    fun setHdrPlusOis(enabled: Boolean) {
        edit { this[PreferenceKeys.HDR_PLUS_OIS] = enabled }
    }

    fun setForce60Fps(enabled: Boolean) {
        edit { this[PreferenceKeys.FORCE_60FPS] = enabled }
    }

    fun setZslAdaptive(enabled: Boolean) {
        edit { this[PreferenceKeys.ZSL_ADAPTIVE] = enabled }
    }

    fun setZslLazyRing(enabled: Boolean) {
        edit { this[PreferenceKeys.ZSL_LAZY_RING] = enabled }
    }

    fun setZslEnabled(enabled: Boolean) {
        edit { this[PreferenceKeys.ZSL_ENABLED] = enabled }
    }

    fun setZslCapability(route: String, reason: String) {
        edit {
            this[PreferenceKeys.ZSL_ROUTE] = route
            this[PreferenceKeys.ZSL_REASON] = reason
        }
    }

    fun setDebugEnabled(enabled: Boolean) {
        edit { this[PreferenceKeys.DEBUG_ENABLED] = enabled }
    }

    fun setSaveLocationEnabled(enabled: Boolean) {
        edit { this[PreferenceKeys.SAVE_LOCATION] = enabled }
    }

    fun setStorageUri(isJpg: Boolean, uri: String?, name: String?) {
        val uriKey = if (isJpg) PreferenceKeys.JPG_STORAGE_URI else PreferenceKeys.RAW_STORAGE_URI
        val nameKey = if (isJpg) PreferenceKeys.JPG_STORAGE_URI_NAME else PreferenceKeys.RAW_STORAGE_URI_NAME
        edit {
            if (uri != null) {
                this[uriKey] = uri
                if (name != null) {
                    this[nameKey] = name
                } else {
                    remove(nameKey)
                }
            } else {
                remove(uriKey)
                remove(nameKey)
            }
        }
    }

    fun setLastCaptureUri(uri: String?) {
        edit {
            if (uri != null) {
                this[PreferenceKeys.LAST_CAPTURE_URI] = uri
            } else {
                remove(PreferenceKeys.LAST_CAPTURE_URI)
            }
        }
    }

    fun setUiTier(tierKey: String) {
        edit { this[PreferenceKeys.UI_TIER] = tierKey }
    }

    fun setLivePreview(enabled: Boolean) {
        edit { this[PreferenceKeys.ENABLE_LUT_PREVIEW] = enabled }
    }

    fun setSelectedLensSensorId(sensorId: String?) {
        edit {
            if (sensorId != null) {
                this[PreferenceKeys.SELECTED_LENS_SENSOR_ID] = sensorId
            } else {
                remove(PreferenceKeys.SELECTED_LENS_SENSOR_ID)
            }
        }
    }

    fun setSelectedLensId(lensId: String?) {
        edit {
            if (lensId != null) {
                this[PreferenceKeys.SELECTED_LENS_ID] = lensId
            } else {
                remove(PreferenceKeys.SELECTED_LENS_ID)
            }
        }
    }

    fun setLensFacing(facing: Int?) {
        edit {
            if (facing != null) {
                this[PreferenceKeys.LENS_FACING] = facing
            } else {
                remove(PreferenceKeys.LENS_FACING)
            }
        }
    }

    fun clearSessionLensState() {
        edit {
            remove(PreferenceKeys.SELECTED_LENS_SENSOR_ID)
            remove(PreferenceKeys.LENS_FACING)
        }
    }

    fun setHdrPlusEnabled(enabled: Boolean) {
        edit { this[PreferenceKeys.HDR_PLUS_ENABLED] = enabled }
    }

    fun setViewerMotionPhotoAutoPlay(autoPlay: Boolean) {
        edit { this[PreferenceKeys.VIEWER_MOTION_PHOTO_AUTO_PLAY] = autoPlay }
    }

    fun setGalleryLayout(isLandscape: Boolean, columns: Int, isWaterfall: Boolean) {
        edit {
            this[PreferenceKeys.GALLERY_WATERFALL] = isWaterfall
            if (isLandscape) {
                this[PreferenceKeys.GALLERY_COLUMNS_LANDSCAPE] = columns
            } else {
                this[PreferenceKeys.GALLERY_COLUMNS_PORTRAIT] = columns
            }
        }
    }

    fun setPlaygroundColumns(columns: Int) {
        edit { this[PreferenceKeys.PLAYGROUND_COLUMNS] = columns }
    }

    fun setRawVideoFps(fps: String) {
        edit { this[PreferenceKeys.RAW_VIDEO_FPS] = fps }
    }

    fun setRawVideoResolution(resolution: String) {
        edit { this[PreferenceKeys.RAW_VIDEO_RESOLUTION] = resolution }
    }

    fun setExpMultiCamColorHarmonization(enabled: Boolean) {
        edit { this[PreferenceKeys.EXP_MULTICAM_COLOR_HARMONIZATION] = enabled }
    }

    fun setExpRawMotionPhoto(enabled: Boolean) {
        edit { this[PreferenceKeys.EXP_RAW_MOTION_PHOTO] = enabled }
    }

    @VisibleForTesting
    fun clearAllForTest() {
        store.clearAsync()
    }

    companion object {
        private val instancesByStore: MutableMap<DarkbagPreferencesStore, SettingsRepository> =
            Collections.synchronizedMap(WeakHashMap())

        fun getInstance(context: Context): SettingsRepository {
            val appContext = context.applicationContext ?: context
            val store = DarkbagDataStoreProvider.getStore(appContext)
            return instancesByStore[store] ?: synchronized(this) {
                instancesByStore.getOrPut(store) {
                    SettingsRepository(appContext)
                }
            }
        }

        @VisibleForTesting
        fun resetForTest(context: Context) {
            val appContext = context.applicationContext ?: context
            val removed = DarkbagDataStoreProvider.resetForTest(appContext)
            if (removed != null) {
                synchronized(this) {
                    instancesByStore.remove(removed)
                }
            }
        }
    }
}
