package top.maary.darkbag.repository

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey

/**
 * Centralized preference key and constant contract for Darkbag.
 * Replaces scattered magic strings and SharedPreferences keys in SettingsFragment and callers.
 */
object PreferenceKeys {
    const val LEGACY_PREFS_NAME = "camera_settings"
    const val DATASTORE_NAME = "darkbag_settings"

    // --- Raw key names ---
    const val KEY_TARGET_LOG = "target_log"
    const val KEY_ACTIVE_LUT = "active_lut_filename"
    const val KEY_LUT_URI = "lut_uri"
    const val KEY_SAVE_JPG = "save_jpg"
    const val KEY_SAVE_RAW = "save_raw"
    const val KEY_USE_GPU = "use_gpu"
    const val KEY_MANUAL_CONTROLS = "enable_manual_controls"
    const val KEY_ENABLE_LUT_PREVIEW = "enable_lut_preview"
    const val KEY_DEFAULT_LENS_ID = "default_lens_id"
    const val KEY_DEFAULT_FOCAL_1X = "default_focal_1x"
    const val KEY_ANTIBANDING = "antibanding_mode"
    const val KEY_FLASH_MODE = "flash_mode"
    const val KEY_HDR_BURST_COUNT = "hdr_burst_count"
    const val KEY_HDR_UNDEREXPOSURE_MODE = "hdr_underexposure_mode"
    const val KEY_BURST_PROCESSING_STRATEGY = "burst_processing_strategy"
    const val KEY_HDR_FUSION_MODE = "hdr_fusion_mode"
    const val KEY_SHOW_HDR_UNDEREXPOSURE_BUTTON = "show_hdr_underexposure_button"
    const val KEY_SHOW_HDR_PLUS_SWITCH = "show_hdr_plus_switch"
    const val KEY_USE_INTERNAL_VIEWER = "use_internal_viewer"
    const val KEY_EXTERNAL_VIEWER_PACKAGE = "external_viewer_package"
    const val KEY_EXTERNAL_VIEWER_NAME = "external_viewer_name"
    const val KEY_SHOW_SETTINGS_BUTTON = "show_settings_button"
    const val KEY_SHOW_CAMERA_SWITCH_BUTTON = "show_camera_switch_button"
    const val KEY_SHOW_MODE_SWITCH_BUTTON = "show_mode_switch_button"
    const val KEY_SHOW_LENS_CONTROLS = "show_lens_controls"
    const val KEY_SHOW_LUT_SWITCHER = "show_lut_switcher"
    const val KEY_MIRROR_FRONT_CAMERA = "mirror_front_camera"
    const val KEY_HDR_PLUS_OIS = "hdr_plus_ois_enabled"
    const val KEY_FORCE_60FPS = "force_60fps"
    const val KEY_ZSL_ENABLED = "zsl_enabled"
    const val KEY_ZSL_ROUTE = "zsl_route"
    const val KEY_ZSL_REASON = "zsl_reason"
    const val KEY_DEBUG_ENABLED = "debug_enabled"
    const val KEY_MOTION_PHOTO = "motion_photo_enabled"
    const val KEY_MOTION_PHOTO_AUDIO = "motion_photo_audio_enabled"
    const val KEY_SAVE_LOCATION = "save_location_enabled"
    const val KEY_RAW_OUTPUT_TYPE = "raw_output_type"
    const val KEY_DNG_COMPRESSION_MODE = "dng_compression_mode"
    const val KEY_JPG_STORAGE_URI = "jpg_storage_uri"
    const val KEY_JPG_STORAGE_URI_NAME = "jpg_storage_uri_name"
    const val KEY_RAW_STORAGE_URI = "raw_storage_uri"
    const val KEY_RAW_STORAGE_URI_NAME = "raw_storage_uri_name"
    const val KEY_LAST_CAPTURE_URI = "last_capture_uri"

    const val KEY_HALF_FRAME_MODE = "half_frame_mode"
    const val KEY_HALF_FRAME_LAYOUT = "half_frame_layout"
    const val KEY_HALF_FRAME_DOWNSAMPLE = "half_frame_downsample"
    const val KEY_HALF_FRAME_STEP = "half_frame_step"
    const val KEY_HALF_FRAME_TEMP_PATH = "half_frame_temp_path"
    const val KEY_HALF_FRAME_DATE_STAMP = "half_frame_date_stamp"
    const val KEY_HALF_FRAME_LIGHT_LEAK = "half_frame_light_leak"
    const val KEY_HALF_FRAME_AUTO_BURST = "half_frame_auto_burst"
    const val KEY_HALF_FRAME_SAVE_RAW = "half_frame_save_raw"
    const val KEY_HALF_FRAME_BASE_NAME = "half_frame_base_name"
    const val KEY_HALF_FRAME_CAPTURE_TIME = "half_frame_capture_time"
    const val KEY_HALF_FRAME_DIGITAL_GAIN = "half_frame_digital_gain"
    const val KEY_HALF_FRAME_FLARE_TYPE = "half_frame_flare_type"
    const val KEY_HALF_FRAME_TEMP_DNG_PATH = "half_frame_temp_dng_path"

    const val KEY_ACTIVE_CAPTURE_MODE = "active_capture_mode"
    const val KEY_MULTI_CAMERA_MODE = "multi_camera_mode_enabled"
    const val KEY_MULTI_CAMERA_FORCE_ENABLE = "multi_camera_force_enable"
    const val KEY_MULTI_CAMERA_COUNT_PREF = "multi_camera_count_pref"
    const val KEY_MULTI_CAMERA_DUAL_PAIR = "multi_camera_dual_pair"
    const val KEY_MULTI_CAMERA_SAVE_RAW = "multi_camera_save_raw"
    const val KEY_MULTI_CAMERA_MANUAL_LINKED = "pref_multi_camera_manual_linked"

    const val KEY_DEFAULT_STARTUP = "default_startup_page"
    const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"
    const val KEY_ENABLE_CAMERA = "enable_camera"
    const val KEY_ENABLE_PLAYGROUND = "enable_playground"
    const val KEY_SHOW_FLOATING_TOOLBAR = "show_floating_toolbar"
    const val KEY_EXP_FOCUS_PEAKING = "exp_focus_peaking"
    const val KEY_FOCUS_PEAKING_ACTIVE = "pref_focus_peaking_active"
    const val KEY_COLOR_ENGINE_MODE = "color_engine_mode"
    const val KEY_GALLERY_COLUMNS_PORTRAIT = "gallery_columns_portrait"
    const val KEY_GALLERY_COLUMNS_LANDSCAPE = "gallery_columns_landscape"
    const val KEY_GALLERY_WATERFALL = "gallery_waterfall"
    const val KEY_PLAYGROUND_COLUMNS = "playground_columns"
    const val KEY_SHUTTER_LONG_PRESS_ACTION = "shutter_long_press_action"
    const val KEY_RAW_VIDEO_FPS = "raw_video_fps"
    const val KEY_RAW_VIDEO_RESOLUTION = "raw_video_resolution"

    const val KEY_UI_TIER = "pref_camera_bento_ui_tier"
    const val KEY_SELECTED_LENS_SENSOR_ID = "selected_lens_sensor_id"
    const val KEY_SELECTED_LENS_ID = "selected_lens_id"
    const val KEY_LENS_FACING = "lens_facing"
    const val KEY_HDR_PLUS_ENABLED = "hdr_plus_enabled"
    const val KEY_VIEWER_MOTION_PHOTO_AUTO_PLAY = "viewer_motion_photo_auto_play"

    const val PREF_PREFIX_LUT_LOG = "lut_assoc_log_"
    const val KEY_CALIBRATED_LENSES = "custom_lens_calibration_all_lenses"
    private const val CALIBRATION_PREFIX = "custom_lens_calibration_"
    private const val CALIBRATION_ENABLED_SUFFIX = "_enabled"
    private const val CALIBRATION_K1_SUFFIX = "_k1"
    private const val CALIBRATION_K2_SUFFIX = "_k2"
    private const val CALIBRATION_FOCAL_SUFFIX = "_focal"

    // --- Typed DataStore Keys ---
    val TARGET_LOG = stringPreferencesKey(KEY_TARGET_LOG)
    val ACTIVE_LUT = stringPreferencesKey(KEY_ACTIVE_LUT)
    val LUT_URI = stringPreferencesKey(KEY_LUT_URI)
    val SAVE_JPG = booleanPreferencesKey(KEY_SAVE_JPG)
    val SAVE_RAW = booleanPreferencesKey(KEY_SAVE_RAW)
    val USE_GPU = booleanPreferencesKey(KEY_USE_GPU)
    val MANUAL_CONTROLS = booleanPreferencesKey(KEY_MANUAL_CONTROLS)
    val ENABLE_LUT_PREVIEW = booleanPreferencesKey(KEY_ENABLE_LUT_PREVIEW)
    val DEFAULT_LENS_ID = stringPreferencesKey(KEY_DEFAULT_LENS_ID)
    val DEFAULT_FOCAL_1X = stringPreferencesKey(KEY_DEFAULT_FOCAL_1X)
    val ANTIBANDING = stringPreferencesKey(KEY_ANTIBANDING)
    val FLASH_MODE = booleanPreferencesKey(KEY_FLASH_MODE)
    val HDR_BURST_COUNT = intPreferencesKey(KEY_HDR_BURST_COUNT)
    val HDR_UNDEREXPOSURE_MODE = stringPreferencesKey(KEY_HDR_UNDEREXPOSURE_MODE)
    val BURST_PROCESSING_STRATEGY = stringPreferencesKey(KEY_BURST_PROCESSING_STRATEGY)
    val HDR_FUSION_MODE = stringPreferencesKey(KEY_HDR_FUSION_MODE)
    val SHOW_HDR_UNDEREXPOSURE_BUTTON = booleanPreferencesKey(KEY_SHOW_HDR_UNDEREXPOSURE_BUTTON)
    val SHOW_HDR_PLUS_SWITCH = booleanPreferencesKey(KEY_SHOW_HDR_PLUS_SWITCH)
    val USE_INTERNAL_VIEWER = booleanPreferencesKey(KEY_USE_INTERNAL_VIEWER)
    val EXTERNAL_VIEWER_PACKAGE = stringPreferencesKey(KEY_EXTERNAL_VIEWER_PACKAGE)
    val EXTERNAL_VIEWER_NAME = stringPreferencesKey(KEY_EXTERNAL_VIEWER_NAME)
    val SHOW_SETTINGS_BUTTON = booleanPreferencesKey(KEY_SHOW_SETTINGS_BUTTON)
    val SHOW_CAMERA_SWITCH_BUTTON = booleanPreferencesKey(KEY_SHOW_CAMERA_SWITCH_BUTTON)
    val SHOW_MODE_SWITCH_BUTTON = booleanPreferencesKey(KEY_SHOW_MODE_SWITCH_BUTTON)
    val SHOW_LENS_CONTROLS = booleanPreferencesKey(KEY_SHOW_LENS_CONTROLS)
    val SHOW_LUT_SWITCHER = booleanPreferencesKey(KEY_SHOW_LUT_SWITCHER)
    val MIRROR_FRONT_CAMERA = booleanPreferencesKey(KEY_MIRROR_FRONT_CAMERA)
    val HDR_PLUS_OIS = booleanPreferencesKey(KEY_HDR_PLUS_OIS)
    val FORCE_60FPS = booleanPreferencesKey(KEY_FORCE_60FPS)
    val ZSL_ENABLED = booleanPreferencesKey(KEY_ZSL_ENABLED)
    val ZSL_ROUTE = stringPreferencesKey(KEY_ZSL_ROUTE)
    val ZSL_REASON = stringPreferencesKey(KEY_ZSL_REASON)
    val DEBUG_ENABLED = booleanPreferencesKey(KEY_DEBUG_ENABLED)
    val MOTION_PHOTO = booleanPreferencesKey(KEY_MOTION_PHOTO)
    val MOTION_PHOTO_AUDIO = booleanPreferencesKey(KEY_MOTION_PHOTO_AUDIO)
    val SAVE_LOCATION = booleanPreferencesKey(KEY_SAVE_LOCATION)
    val RAW_OUTPUT_TYPE = intPreferencesKey(KEY_RAW_OUTPUT_TYPE)
    val DNG_COMPRESSION_MODE = intPreferencesKey(KEY_DNG_COMPRESSION_MODE)
    val JPG_STORAGE_URI = stringPreferencesKey(KEY_JPG_STORAGE_URI)
    val JPG_STORAGE_URI_NAME = stringPreferencesKey(KEY_JPG_STORAGE_URI_NAME)
    val RAW_STORAGE_URI = stringPreferencesKey(KEY_RAW_STORAGE_URI)
    val RAW_STORAGE_URI_NAME = stringPreferencesKey(KEY_RAW_STORAGE_URI_NAME)
    val LAST_CAPTURE_URI = stringPreferencesKey(KEY_LAST_CAPTURE_URI)

    val HALF_FRAME_MODE = booleanPreferencesKey(KEY_HALF_FRAME_MODE)
    val HALF_FRAME_LAYOUT = stringPreferencesKey(KEY_HALF_FRAME_LAYOUT)
    val HALF_FRAME_DOWNSAMPLE = booleanPreferencesKey(KEY_HALF_FRAME_DOWNSAMPLE)
    val HALF_FRAME_DATE_STAMP = booleanPreferencesKey(KEY_HALF_FRAME_DATE_STAMP)
    val HALF_FRAME_LIGHT_LEAK = booleanPreferencesKey(KEY_HALF_FRAME_LIGHT_LEAK)
    val HALF_FRAME_AUTO_BURST = booleanPreferencesKey(KEY_HALF_FRAME_AUTO_BURST)
    val HALF_FRAME_SAVE_RAW = booleanPreferencesKey(KEY_HALF_FRAME_SAVE_RAW)

    val ACTIVE_CAPTURE_MODE = stringPreferencesKey(KEY_ACTIVE_CAPTURE_MODE)
    val MULTI_CAMERA_MODE = booleanPreferencesKey(KEY_MULTI_CAMERA_MODE)
    val MULTI_CAMERA_FORCE_ENABLE = booleanPreferencesKey(KEY_MULTI_CAMERA_FORCE_ENABLE)
    val MULTI_CAMERA_COUNT_PREF = stringPreferencesKey(KEY_MULTI_CAMERA_COUNT_PREF)
    val MULTI_CAMERA_DUAL_PAIR = stringPreferencesKey(KEY_MULTI_CAMERA_DUAL_PAIR)
    val MULTI_CAMERA_SAVE_RAW = booleanPreferencesKey(KEY_MULTI_CAMERA_SAVE_RAW)
    val MULTI_CAMERA_MANUAL_LINKED = booleanPreferencesKey(KEY_MULTI_CAMERA_MANUAL_LINKED)

    val DEFAULT_STARTUP = stringPreferencesKey(KEY_DEFAULT_STARTUP)
    val ONBOARDING_COMPLETED = booleanPreferencesKey(KEY_ONBOARDING_COMPLETED)
    val ENABLE_CAMERA = booleanPreferencesKey(KEY_ENABLE_CAMERA)
    val ENABLE_PLAYGROUND = booleanPreferencesKey(KEY_ENABLE_PLAYGROUND)
    val SHOW_FLOATING_TOOLBAR = booleanPreferencesKey(KEY_SHOW_FLOATING_TOOLBAR)
    val EXP_FOCUS_PEAKING = booleanPreferencesKey(KEY_EXP_FOCUS_PEAKING)
    val FOCUS_PEAKING_ACTIVE = booleanPreferencesKey(KEY_FOCUS_PEAKING_ACTIVE)
    val COLOR_ENGINE_MODE = intPreferencesKey(KEY_COLOR_ENGINE_MODE)
    val GALLERY_COLUMNS_PORTRAIT = intPreferencesKey(KEY_GALLERY_COLUMNS_PORTRAIT)
    val GALLERY_COLUMNS_LANDSCAPE = intPreferencesKey(KEY_GALLERY_COLUMNS_LANDSCAPE)
    val GALLERY_WATERFALL = booleanPreferencesKey(KEY_GALLERY_WATERFALL)
    val PLAYGROUND_COLUMNS = intPreferencesKey(KEY_PLAYGROUND_COLUMNS)
    val SHUTTER_LONG_PRESS_ACTION = stringPreferencesKey(KEY_SHUTTER_LONG_PRESS_ACTION)
    val RAW_VIDEO_FPS = stringPreferencesKey(KEY_RAW_VIDEO_FPS)
    val RAW_VIDEO_RESOLUTION = stringPreferencesKey(KEY_RAW_VIDEO_RESOLUTION)

    val UI_TIER = stringPreferencesKey(KEY_UI_TIER)
    val SELECTED_LENS_SENSOR_ID = stringPreferencesKey(KEY_SELECTED_LENS_SENSOR_ID)
    val SELECTED_LENS_ID = stringPreferencesKey(KEY_SELECTED_LENS_ID)
    val LENS_FACING = intPreferencesKey(KEY_LENS_FACING)
    val HDR_PLUS_ENABLED = booleanPreferencesKey(KEY_HDR_PLUS_ENABLED)
    val VIEWER_MOTION_PHOTO_AUTO_PLAY = booleanPreferencesKey(KEY_VIEWER_MOTION_PHOTO_AUTO_PLAY)
    val CALIBRATED_LENSES = stringSetPreferencesKey(KEY_CALIBRATED_LENSES)

    // --- Dynamic Key Builders ---
    fun lutAssocLogKey(lutFileName: String): Preferences.Key<String> =
        stringPreferencesKey(PREF_PREFIX_LUT_LOG + lutFileName)

    fun halfFrameStepKey(profile: String): Preferences.Key<Int> =
        intPreferencesKey("${KEY_HALF_FRAME_STEP}_$profile")

    fun halfFrameTempPathKey(profile: String): Preferences.Key<String> =
        stringPreferencesKey("${KEY_HALF_FRAME_TEMP_PATH}_$profile")

    fun halfFrameBaseNameKey(profile: String): Preferences.Key<String> =
        stringPreferencesKey("${KEY_HALF_FRAME_BASE_NAME}_$profile")

    fun halfFrameCaptureTimeKey(profile: String): Preferences.Key<Long> =
        longPreferencesKey("${KEY_HALF_FRAME_CAPTURE_TIME}_$profile")

    fun halfFrameDigitalGainKey(profile: String): Preferences.Key<Float> =
        floatPreferencesKey("${KEY_HALF_FRAME_DIGITAL_GAIN}_$profile")

    fun halfFrameFlareTypeKey(profile: String): Preferences.Key<Int> =
        intPreferencesKey("${KEY_HALF_FRAME_FLARE_TYPE}_$profile")

    fun halfFrameTempDngPathKey(profile: String): Preferences.Key<String> =
        stringPreferencesKey("${KEY_HALF_FRAME_TEMP_DNG_PATH}_$profile")

    fun lensCalibrationEnabledKey(lensId: String): Preferences.Key<Boolean> =
        booleanPreferencesKey(CALIBRATION_PREFIX + lensId + CALIBRATION_ENABLED_SUFFIX)

    fun lensCalibrationK1Key(lensId: String): Preferences.Key<Float> =
        floatPreferencesKey(CALIBRATION_PREFIX + lensId + CALIBRATION_K1_SUFFIX)

    fun lensCalibrationK2Key(lensId: String): Preferences.Key<Float> =
        floatPreferencesKey(CALIBRATION_PREFIX + lensId + CALIBRATION_K2_SUFFIX)

    fun lensCalibrationFocalKey(lensId: String): Preferences.Key<Float> =
        floatPreferencesKey(CALIBRATION_PREFIX + lensId + CALIBRATION_FOCAL_SUFFIX)

    // --- Option / Mode Constants ---
    const val BURST_STRATEGY_BALANCED = "Balanced (Smooth UI)"
    const val BURST_STRATEGY_AGGRESSIVE = "Rapid Burst (Maximum Shots)"
    val BURST_PROCESSING_STRATEGIES = listOf(BURST_STRATEGY_BALANCED, BURST_STRATEGY_AGGRESSIVE)

    const val HDR_FUSION_AUTO = "Auto (Spatial / Sabre Zoom)"
    const val HDR_FUSION_SPATIAL_RCD = "Spatial Merge + RCD"
    const val HDR_FUSION_SABRE = "Sabre (Super-Resolution)"
    const val HDR_FUSION_CLASSIC = "Classic HDR+ (Wiener)"
    val HDR_FUSION_MODES = listOf(
        HDR_FUSION_AUTO,
        HDR_FUSION_SPATIAL_RCD,
        HDR_FUSION_SABRE,
        HDR_FUSION_CLASSIC
    )

    val RAW_OUTPUT_TYPES = listOf("Bayer CFA RAW (~12MB, Default)", "Linear RGB RAW (~50MB)")
    val DNG_COMPRESSION_MODES = listOf("Lossless JPEG (Default)", "Uncompressed")

    const val MODE_NORMAL = "normal"
    const val MODE_HALF_FRAME_SBS = "half_frame_sbs"
    const val MODE_HALF_FRAME_TB = "half_frame_tb"
    const val MODE_MULTI_CAMERA = "multi_camera"

    const val STARTUP_CAMERA = "camera"
    const val STARTUP_PLAYGROUND = "playground"

    const val SHUTTER_LONG_PRESS_MP4 = "Standard MP4 (Default)"
    const val SHUTTER_LONG_PRESS_RAW_VIDEO = "RAW Video (Bayer 10-bit)"
    const val SHUTTER_LONG_PRESS_DISABLED = "Disabled"
    val SHUTTER_LONG_PRESS_OPTIONS = listOf(
        SHUTTER_LONG_PRESS_MP4,
        SHUTTER_LONG_PRESS_RAW_VIDEO,
        SHUTTER_LONG_PRESS_DISABLED
    )

    val RAW_VIDEO_FPS_OPTIONS = listOf("24", "30", "60")
    const val DEFAULT_RAW_VIDEO_RESOLUTION = "Max Native (4:3)"
    val RAW_VIDEO_RESOLUTION_OPTIONS = listOf(
        "Max Native (4:3)",
        "2K Open Gate (4:3)",
        "4K UHD (16:9)",
        "1080p (16:9)"
    )

    val COLOR_ENGINE_MODES = listOf(
        "Khronos PBR Neutral",
        "Natural Filmic (Leica/Hasselblad)",
        "Sony Uchimura (GT7, Default)",
        "Legacy ACES Filmic (Original)"
    )

    val FOCAL_LENGTHS = listOf("24", "28", "35")
    val ANTIBANDING_MODES = listOf("Auto", "50Hz", "60Hz", "Off")
    val BURST_SIZES = listOf("3", "4", "5", "6", "7", "8")
    val HDR_UNDEREXPOSURE_MODES = listOf("Off", "-1 EV", "-2 EV", "Dynamic (Experimental)")

    const val HALF_FRAME_LAYOUT_SBS = "Side-by-side"
    const val HALF_FRAME_LAYOUT_TB = "Top-bottom"
    val HALF_FRAME_LAYOUTS = listOf(HALF_FRAME_LAYOUT_SBS, HALF_FRAME_LAYOUT_TB)

    val LOG_CURVES = listOf(
        "None",
        "Arri LogC3",
        "F-Log",
        "F-Log2",
        "F-Log2 C",
        "S-Log3",
        "S-Log3.Cine",
        "V-Log",
        "Canon Log 2",
        "Canon Log 3",
        "N-Log",
        "D-Log",
        "Log3G10"
    )
}
