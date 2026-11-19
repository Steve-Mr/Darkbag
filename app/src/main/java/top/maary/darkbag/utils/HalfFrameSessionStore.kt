package top.maary.darkbag.utils

import android.content.Context
import top.maary.darkbag.repository.DarkbagDataStoreProvider
import top.maary.darkbag.repository.PreferenceKeys
import java.io.File

class HalfFrameSessionStore(private val context: Context) {
    private val store = DarkbagDataStoreProvider.getStore(context)

    data class Session(
        val profile: String,
        val step: Int,
        val tempPath: String?,
        val baseName: String?,
        val captureTimeMillis: Long,
        val digitalGain: Float = 1.0f,
        val flareType: Int = -1,
        val tempDngPath: String? = null
    )

    fun currentProfile(): String {
        val snapshot = store.current()
        val activeMode = snapshot[PreferenceKeys.ACTIVE_CAPTURE_MODE]
        if (activeMode != null) {
            return when (activeMode) {
                PreferenceKeys.MODE_HALF_FRAME_SBS -> PROFILE_HALF_SIDE
                PreferenceKeys.MODE_HALF_FRAME_TB -> PROFILE_HALF_TOP
                else -> PROFILE_NORMAL
            }
        }
        val mode = snapshot[PreferenceKeys.HALF_FRAME_MODE] ?: false
        if (!mode) return PROFILE_NORMAL
        val layout = snapshot[PreferenceKeys.HALF_FRAME_LAYOUT] ?: PreferenceKeys.HALF_FRAME_LAYOUT_SBS
        return if (layout == PreferenceKeys.HALF_FRAME_LAYOUT_SBS) PROFILE_HALF_SIDE else PROFILE_HALF_TOP
    }

    fun readSession(strict: Boolean = false, profile: String? = null): Session {
        val activeProfile = profile ?: currentProfile()
        val snapshot = store.current()
        val step = snapshot[PreferenceKeys.halfFrameStepKey(activeProfile)] ?: 0
        val tempPath = snapshot[PreferenceKeys.halfFrameTempPathKey(activeProfile)]
        val baseName = snapshot[PreferenceKeys.halfFrameBaseNameKey(activeProfile)]
        val captureTime = snapshot[PreferenceKeys.halfFrameCaptureTimeKey(activeProfile)] ?: 0L
        val digitalGain = snapshot[PreferenceKeys.halfFrameDigitalGainKey(activeProfile)] ?: 1.0f
        val flareType = snapshot[PreferenceKeys.halfFrameFlareTypeKey(activeProfile)] ?: -1
        val tempDngPath = snapshot[PreferenceKeys.halfFrameTempDngPathKey(activeProfile)]

        var resolvedStep = step
        var resolvedTemp = tempPath
        var resolvedBase = baseName
        var resolvedDng = tempDngPath

        if (resolvedStep == 1) {
            val exists = !resolvedTemp.isNullOrBlank() && File(resolvedTemp).exists()
            val expired = captureTime <= 0L || (System.currentTimeMillis() - captureTime) > MAX_RECOVER_WINDOW_MS
            val invalid = if (strict) !exists else (!exists && expired)
            if (invalid) {
                resolvedStep = 0
                resolvedTemp = null
                resolvedBase = null
                resolvedDng = null
                clearProfile(activeProfile)
            }
        }

        return Session(activeProfile, resolvedStep, resolvedTemp, resolvedBase, captureTime, digitalGain, flareType, resolvedDng)
    }

    fun markStep(step: Int, captureTimeMillis: Long? = null, profile: String? = null, digitalGain: Float = 1.0f, flareType: Int = -1) {
        val activeProfile = profile ?: currentProfile()
        store.updateAsync { mutable ->
            mutable[PreferenceKeys.halfFrameStepKey(activeProfile)] = step
            if (step == 1) {
                mutable[PreferenceKeys.halfFrameCaptureTimeKey(activeProfile)] = captureTimeMillis ?: System.currentTimeMillis()
                mutable[PreferenceKeys.halfFrameDigitalGainKey(activeProfile)] = digitalGain
                mutable[PreferenceKeys.halfFrameFlareTypeKey(activeProfile)] = flareType
            } else {
                mutable.remove(PreferenceKeys.halfFrameCaptureTimeKey(activeProfile))
                mutable.remove(PreferenceKeys.halfFrameDigitalGainKey(activeProfile))
                mutable.remove(PreferenceKeys.halfFrameFlareTypeKey(activeProfile))
            }
        }
    }

    fun setTempPath(path: String?, profile: String? = null) {
        val activeProfile = profile ?: currentProfile()
        store.updateAsync { mutable ->
            if (path != null) {
                mutable[PreferenceKeys.halfFrameTempPathKey(activeProfile)] = path
            } else {
                mutable.remove(PreferenceKeys.halfFrameTempPathKey(activeProfile))
            }
        }
    }

    fun setTempDngPath(path: String?, profile: String? = null) {
        val activeProfile = profile ?: currentProfile()
        store.updateAsync { mutable ->
            if (path != null) {
                mutable[PreferenceKeys.halfFrameTempDngPathKey(activeProfile)] = path
            } else {
                mutable.remove(PreferenceKeys.halfFrameTempDngPathKey(activeProfile))
            }
        }
    }

    fun setBaseName(baseName: String?, profile: String? = null) {
        val activeProfile = profile ?: currentProfile()
        store.updateAsync { mutable ->
            if (baseName != null) {
                mutable[PreferenceKeys.halfFrameBaseNameKey(activeProfile)] = baseName
            } else {
                mutable.remove(PreferenceKeys.halfFrameBaseNameKey(activeProfile))
            }
        }
    }

    fun clearProfile(profile: String) {
        store.updateAsync { mutable ->
            mutable[PreferenceKeys.halfFrameStepKey(profile)] = 0
            mutable.remove(PreferenceKeys.halfFrameTempPathKey(profile))
            mutable.remove(PreferenceKeys.halfFrameTempDngPathKey(profile))
            mutable.remove(PreferenceKeys.halfFrameBaseNameKey(profile))
            mutable.remove(PreferenceKeys.halfFrameCaptureTimeKey(profile))
            mutable.remove(PreferenceKeys.halfFrameDigitalGainKey(profile))
            mutable.remove(PreferenceKeys.halfFrameFlareTypeKey(profile))
        }
    }

    fun clearCurrentSession(deleteTempFile: Boolean = false) {
        val profile = currentProfile()
        val snapshot = store.current()
        val tempPath = snapshot[PreferenceKeys.halfFrameTempPathKey(profile)]
        val tempDngPath = snapshot[PreferenceKeys.halfFrameTempDngPathKey(profile)]
        if (deleteTempFile) {
            if (!tempPath.isNullOrBlank()) {
                runCatching { File(tempPath).delete() }
            }
            if (!tempDngPath.isNullOrBlank()) {
                runCatching { File(tempDngPath).delete() }
            }
            runCatching { tempDngFileForProfile(profile).delete() }
        }
        clearProfile(profile)
    }

    fun tempFileForProfile(profile: String): File = File(context.filesDir, "half_frame_frame1_$profile.jpg")

    fun tempFileForCurrentProfile(): File = tempFileForProfile(currentProfile())

    fun tempDngFileForProfile(profile: String): File = File(context.filesDir, "half_frame_frame1_$profile.dng")

    fun tempDngFileForCurrentProfile(): File = tempDngFileForProfile(currentProfile())

    fun scopedStepKeyForCurrentProfile(): String = "${PreferenceKeys.KEY_HALF_FRAME_STEP}_${currentProfile()}"

    companion object {
        private const val MAX_RECOVER_WINDOW_MS = 120_000L
        const val PROFILE_NORMAL = "normal"
        const val PROFILE_HALF_SIDE = "half_side"
        const val PROFILE_HALF_TOP = "half_top"
    }
}
