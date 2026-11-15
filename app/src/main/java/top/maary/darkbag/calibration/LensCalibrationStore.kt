package top.maary.darkbag.calibration

import android.content.Context
import top.maary.darkbag.repository.DarkbagDataStoreProvider
import top.maary.darkbag.repository.PreferenceKeys

/**
 * Thread-safe persistent repository for custom lens calibration profiles backed by Jetpack DataStore.
 */
class LensCalibrationStore(private val context: Context) {
    private val store by lazy {
        DarkbagDataStoreProvider.getStore(context.applicationContext ?: context)
    }

    companion object {
        @Volatile
        private var instance: LensCalibrationStore? = null

        fun getInstance(context: Context): LensCalibrationStore {
            return instance ?: synchronized(this) {
                instance ?: LensCalibrationStore(context.applicationContext).also { instance = it }
            }
        }
    }

    fun getCalibration(lensId: String): CustomLensCalibration? {
        val snapshot = store.current()
        val allLenses = snapshot[PreferenceKeys.CALIBRATED_LENSES] ?: emptySet()
        if (!allLenses.contains(lensId)) return null

        val k1 = snapshot[PreferenceKeys.lensCalibrationK1Key(lensId)] ?: 0f
        val k2 = snapshot[PreferenceKeys.lensCalibrationK2Key(lensId)] ?: 0f
        val focal = snapshot[PreferenceKeys.lensCalibrationFocalKey(lensId)] ?: 0f
        val enabled = snapshot[PreferenceKeys.lensCalibrationEnabledKey(lensId)] ?: true

        return CustomLensCalibration(
            lensId = lensId,
            focalLength = focal,
            k1 = k1,
            k2 = k2,
            isEnabled = enabled
        )
    }

    fun saveCalibration(calibration: CustomLensCalibration) {
        store.updateAsync { mutable ->
            val allLenses = mutable[PreferenceKeys.CALIBRATED_LENSES]?.toMutableSet() ?: mutableSetOf()
            allLenses.add(calibration.lensId)
            mutable[PreferenceKeys.CALIBRATED_LENSES] = allLenses
            mutable[PreferenceKeys.lensCalibrationK1Key(calibration.lensId)] = calibration.k1
            mutable[PreferenceKeys.lensCalibrationK2Key(calibration.lensId)] = calibration.k2
            mutable[PreferenceKeys.lensCalibrationFocalKey(calibration.lensId)] = calibration.focalLength
            mutable[PreferenceKeys.lensCalibrationEnabledKey(calibration.lensId)] = calibration.isEnabled
        }
    }

    fun removeCalibration(lensId: String) {
        store.updateAsync { mutable ->
            val allLenses = mutable[PreferenceKeys.CALIBRATED_LENSES]?.toMutableSet() ?: mutableSetOf()
            allLenses.remove(lensId)
            mutable[PreferenceKeys.CALIBRATED_LENSES] = allLenses
            mutable.remove(PreferenceKeys.lensCalibrationK1Key(lensId))
            mutable.remove(PreferenceKeys.lensCalibrationK2Key(lensId))
            mutable.remove(PreferenceKeys.lensCalibrationFocalKey(lensId))
            mutable.remove(PreferenceKeys.lensCalibrationEnabledKey(lensId))
        }
    }

    fun setEnabled(lensId: String, enabled: Boolean) {
        val calib = getCalibration(lensId) ?: return
        saveCalibration(calib.copy(isEnabled = enabled))
    }

    fun getAllCalibrations(): List<CustomLensCalibration> {
        val allLenses = store.current()[PreferenceKeys.CALIBRATED_LENSES] ?: emptySet()
        return allLenses.mapNotNull { getCalibration(it) }
    }
}
