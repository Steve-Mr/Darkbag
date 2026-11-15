package top.maary.darkbag.modes

import top.maary.darkbag.repository.PreferenceKeys

/**
 * 拍摄模式枚举定义。
 */
enum class CaptureMode(val key: String) {
    NORMAL(PreferenceKeys.MODE_NORMAL),
    HALF_FRAME_SBS(PreferenceKeys.MODE_HALF_FRAME_SBS),
    HALF_FRAME_TB(PreferenceKeys.MODE_HALF_FRAME_TB),
    MULTI_CAMERA(PreferenceKeys.MODE_MULTI_CAMERA);

    val isHalfFrame: Boolean
        get() = this == HALF_FRAME_SBS || this == HALF_FRAME_TB

    val isMultiCamera: Boolean
        get() = this == MULTI_CAMERA

    val supportsMotionPhoto: Boolean
        get() = this == NORMAL

    companion object {
        fun fromKey(key: String?): CaptureMode? = entries.find { it.key == key }
    }
}
