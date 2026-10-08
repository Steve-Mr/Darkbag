package top.maary.darkbag.modes

import top.maary.darkbag.fragments.SettingsFragment

/**
 * 拍摄模式枚举定义。
 */
enum class CaptureMode(val key: String) {
    NORMAL(SettingsFragment.MODE_NORMAL),
    HALF_FRAME_SBS(SettingsFragment.MODE_HALF_FRAME_SBS),
    HALF_FRAME_TB(SettingsFragment.MODE_HALF_FRAME_TB),
    MULTI_CAMERA(SettingsFragment.MODE_MULTI_CAMERA);

    val isHalfFrame: Boolean
        get() = this == HALF_FRAME_SBS || this == HALF_FRAME_TB

    val isMultiCamera: Boolean
        get() = this == MULTI_CAMERA

    companion object {
        fun fromKey(key: String?): CaptureMode? = entries.find { it.key == key }
    }
}
