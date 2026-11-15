package top.maary.darkbag.repository

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.util.Size

/**
 * Hardware capability and RAW video resolution selection helpers extracted from SettingsFragment.
 */
object CameraHardwareCapabilities {
    fun getMaxSupportedFps(chars: CameraCharacteristics?): Int {
        if (chars == null) return 30
        val ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: return 30
        return ranges.maxOfOrNull { it.upper } ?: 30
    }

    fun is4kCropSupported(chars: CameraCharacteristics?): Boolean {
        if (chars == null) return false
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return false
        val rawSizes = map.getOutputSizes(ImageFormat.RAW_SENSOR) ?: return false
        val maxRaw = rawSizes.maxByOrNull { it.width * it.height } ?: return false
        return maxRaw.width >= 3840 && maxRaw.height >= 2160
    }

    fun getMaxRawSize(chars: CameraCharacteristics?): Size? {
        if (chars == null) return null
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val rawSizes = map.getOutputSizes(ImageFormat.RAW_SENSOR) ?: return null
        return rawSizes.maxByOrNull { it.width * it.height }
    }

    fun selectRawVideoSize(preference: String, availableSizes: Array<Size>): Size {
        if (availableSizes.isEmpty()) {
            return Size(4000, 3000)
        }
        return when {
            preference.contains("1080p") -> {
                val exact = availableSizes.find { it.width == 1920 && it.height == 1080 }
                if (exact != null) {
                    exact
                } else {
                    val candidate16x9 = availableSizes
                        .filter { it.width <= 1920 && kotlin.math.abs((it.width.toFloat() / it.height.toFloat()) - (16.0f / 9.0f)) < 0.05f }
                        .maxByOrNull { it.width * it.height }
                    candidate16x9
                        ?: availableSizes.minByOrNull { kotlin.math.abs(it.width * it.height - 1920 * 1080) }
                        ?: (availableSizes.maxByOrNull { it.width * it.height } ?: Size(4000, 3000))
                }
            }
            preference.contains("4K") -> {
                val exact = availableSizes.find { it.width == 3840 && it.height == 2160 }
                if (exact != null) {
                    exact
                } else {
                    val candidate16x9 = availableSizes
                        .filter { kotlin.math.abs((it.width.toFloat() / it.height.toFloat()) - (16.0f / 9.0f)) < 0.05f }
                        .minByOrNull { kotlin.math.abs(it.width * it.height - 3840 * 2160) }
                    candidate16x9
                        ?: availableSizes.minByOrNull { kotlin.math.abs(it.width * it.height - 3840 * 2160) }
                        ?: (availableSizes.maxByOrNull { it.width * it.height } ?: Size(4000, 3000))
                }
            }
            preference.contains("2K Open Gate") || preference.contains("Max Native") -> {
                availableSizes.maxByOrNull { it.width * it.height } ?: Size(4000, 3000)
            }
            else -> {
                availableSizes.maxByOrNull { it.width * it.height } ?: Size(4000, 3000)
            }
        }
    }
}
