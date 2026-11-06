package top.maary.darkbag.calibration

import android.content.Context
import android.content.SharedPreferences
import top.maary.darkbag.fragments.SettingsFragment

/**
 * Thread-safe persistent repository for custom lens calibration profiles.
 */
class LensCalibrationStore(private val context: Context) {
    private val prefs: SharedPreferences by lazy {
        context.applicationContext.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
    }

    companion object {
        private const val PREFIX = "custom_lens_calibration_"
        private const val KEY_ENABLED_SUFFIX = "_enabled"
        private const val KEY_K1_SUFFIX = "_k1"
        private const val KEY_K2_SUFFIX = "_k2"
        private const val KEY_FOCAL_SUFFIX = "_focal"
        private const val KEY_CALIBRATED_LENSES = "custom_lens_calibration_all_lenses"

        @Volatile
        private var instance: LensCalibrationStore? = null

        fun getInstance(context: Context): LensCalibrationStore {
            return instance ?: synchronized(this) {
                instance ?: LensCalibrationStore(context.applicationContext).also { instance = it }
            }
        }
    }

    fun getCalibration(lensId: String): CustomLensCalibration? {
        val allLenses = prefs.getStringSet(KEY_CALIBRATED_LENSES, emptySet()) ?: emptySet()
        if (!allLenses.contains(lensId)) return null

        val k1 = prefs.getFloat(PREFIX + lensId + KEY_K1_SUFFIX, 0f)
        val k2 = prefs.getFloat(PREFIX + lensId + KEY_K2_SUFFIX, 0f)
        val focal = prefs.getFloat(PREFIX + lensId + KEY_FOCAL_SUFFIX, 0f)
        val enabled = prefs.getBoolean(PREFIX + lensId + KEY_ENABLED_SUFFIX, true)

        return CustomLensCalibration(
            lensId = lensId,
            focalLength = focal,
            k1 = k1,
            k2 = k2,
            isEnabled = enabled
        )
    }

    fun saveCalibration(calibration: CustomLensCalibration) {
        val allLenses = prefs.getStringSet(KEY_CALIBRATED_LENSES, emptySet())?.toMutableSet() ?: mutableSetOf()
        allLenses.add(calibration.lensId)

        prefs.edit()
            .putStringSet(KEY_CALIBRATED_LENSES, allLenses)
            .putFloat(PREFIX + calibration.lensId + KEY_K1_SUFFIX, calibration.k1)
            .putFloat(PREFIX + calibration.lensId + KEY_K2_SUFFIX, calibration.k2)
            .putFloat(PREFIX + calibration.lensId + KEY_FOCAL_SUFFIX, calibration.focalLength)
            .putBoolean(PREFIX + calibration.lensId + KEY_ENABLED_SUFFIX, calibration.isEnabled)
            .apply()
    }

    fun removeCalibration(lensId: String) {
        val allLenses = prefs.getStringSet(KEY_CALIBRATED_LENSES, emptySet())?.toMutableSet() ?: mutableSetOf()
        allLenses.remove(lensId)

        prefs.edit()
            .putStringSet(KEY_CALIBRATED_LENSES, allLenses)
            .remove(PREFIX + lensId + KEY_K1_SUFFIX)
            .remove(PREFIX + lensId + KEY_K2_SUFFIX)
            .remove(PREFIX + lensId + KEY_FOCAL_SUFFIX)
            .remove(PREFIX + lensId + KEY_ENABLED_SUFFIX)
            .apply()
    }

    fun setEnabled(lensId: String, enabled: Boolean) {
        val calib = getCalibration(lensId) ?: return
        saveCalibration(calib.copy(isEnabled = enabled))
    }

    fun getAllCalibrations(): List<CustomLensCalibration> {
        val allLenses = prefs.getStringSet(KEY_CALIBRATED_LENSES, emptySet()) ?: emptySet()
        return allLenses.mapNotNull { getCalibration(it) }
    }
}
