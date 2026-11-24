package top.maary.darkbag.motionphoto

import android.content.Context
import androidx.annotation.StringRes
import top.maary.darkbag.R

/**
 * Type-safe domain representation of RAW Sensor Motion Photo quality tiers.
 * Bridges persistent storage (Int: 0, 1, 2), native C++ processing mode, and localized string resources.
 */
enum class RawMotionQuality(
    val mode: Int,
    @StringRes val titleRes: Int,
    @StringRes val descRes: Int
) {
    BINNING_2X2(
        mode = 1,
        titleRes = R.string.raw_motion_quality_2x2,
        descRes = R.string.raw_motion_quality_2x2_desc
    ),
    SUBSAMPLE_4X4(
        mode = 0,
        titleRes = R.string.raw_motion_quality_4x4,
        descRes = R.string.raw_motion_quality_4x4_desc
    ),
    NATIVE(
        mode = 2,
        titleRes = R.string.raw_motion_quality_native,
        descRes = R.string.raw_motion_quality_native_desc
    );

    fun getTitle(context: Context): String = context.getString(titleRes)
    fun getDescription(context: Context): String = context.getString(descRes)

    companion object {
        const val DEFAULT_MODE = 1 // 2x2 Binning
        const val MAX_NATIVE_RAW_PIXELS = 16_000_000L

        fun fromMode(mode: Int): RawMotionQuality =
            entries.find { it.mode == mode } ?: BINNING_2X2

        /**
         * Clamps Native 1x1 RAW motion mode to 2x2 Binning on ultra-high-res sensors (> 16MP)
         * to prevent excessive direct buffer and GL texture allocations (~288 MiB on 50MP).
         */
        fun resolveEffectiveMode(requestedMode: Int, srcWidth: Int, srcHeight: Int): Int {
            val canonical = fromMode(requestedMode).mode
            if (canonical == NATIVE.mode &&
                srcWidth > 0 &&
                srcHeight > 0 &&
                srcWidth.toLong() * srcHeight.toLong() > MAX_NATIVE_RAW_PIXELS
            ) {
                return BINNING_2X2.mode
            }
            return canonical
        }

        /** Computes even-aligned RAW Bayer dimensions for [effectiveMode]. */
        fun computeScaledDimensions(srcWidth: Int, srcHeight: Int, effectiveMode: Int): Pair<Int, Int> {
            val w = when (effectiveMode) {
                NATIVE.mode -> srcWidth and 1.inv()
                BINNING_2X2.mode -> (srcWidth / 2) and 1.inv()
                else -> (srcWidth / 4) and 1.inv()
            }
            val h = when (effectiveMode) {
                NATIVE.mode -> srcHeight and 1.inv()
                BINNING_2X2.mode -> (srcHeight / 2) and 1.inv()
                else -> (srcHeight / 4) and 1.inv()
            }
            return w to h
        }

        /**
         * Backward compatibility decoder: gracefully parses legacy stored strings, numeric strings,
         * or integers from older versions and migrates them to the canonical RawMotionQuality.
         */
        fun fromLegacy(raw: Any?): RawMotionQuality = when (raw) {
            is Int -> fromMode(raw)
            is Number -> fromMode(raw.toInt())
            is String -> when {
                raw == "2" || raw.contains("Native", ignoreCase = true) || raw.contains("全像素") -> NATIVE
                raw == "0" || raw.contains("4x4", ignoreCase = true) || raw.contains("抽样") -> SUBSAMPLE_4X4
                else -> BINNING_2X2
            }
            else -> BINNING_2X2
        }
    }
}
