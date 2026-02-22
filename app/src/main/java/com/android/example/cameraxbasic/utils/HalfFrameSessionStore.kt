package com.android.example.cameraxbasic.utils

import android.content.Context
import android.content.SharedPreferences
import com.android.example.cameraxbasic.fragments.SettingsFragment
import java.io.File

class HalfFrameSessionStore(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)

    data class Session(
        val profile: String,
        val step: Int,
        val tempPath: String?,
        val baseName: String?,
        val captureTimeMillis: Long
    )

    fun currentProfile(): String {
        val mode = prefs.getBoolean(SettingsFragment.KEY_HALF_FRAME_MODE, false)
        if (!mode) return PROFILE_NORMAL
        val layout = prefs.getString(SettingsFragment.KEY_HALF_FRAME_LAYOUT, SettingsFragment.HALF_FRAME_LAYOUTS[0])
        return if (layout == SettingsFragment.HALF_FRAME_LAYOUTS[0]) PROFILE_HALF_SIDE else PROFILE_HALF_TOP
    }

    fun readSession(strict: Boolean = false): Session {
        val profile = currentProfile()
        val step = prefs.getInt(key(SettingsFragment.KEY_HALF_FRAME_STEP, profile), 0)
        val tempPath = prefs.getString(key(SettingsFragment.KEY_HALF_FRAME_TEMP_PATH, profile), null)
        val baseName = prefs.getString(key(SettingsFragment.KEY_HALF_FRAME_BASE_NAME, profile), null)
        val captureTime = prefs.getLong(key(KEY_CAPTURE_TIME, profile), 0L)

        var resolvedStep = step
        var resolvedTemp = tempPath
        var resolvedBase = baseName

        if (resolvedStep == 1) {
            val exists = !resolvedTemp.isNullOrBlank() && File(resolvedTemp).exists()
            val expired = captureTime <= 0L || (System.currentTimeMillis() - captureTime) > MAX_RECOVER_WINDOW_MS
            val invalid = if (strict) !exists else (!exists && expired)
            if (invalid) {
                resolvedStep = 0
                resolvedTemp = null
                resolvedBase = null
                prefs.edit()
                    .putInt(key(SettingsFragment.KEY_HALF_FRAME_STEP, profile), 0)
                    .remove(key(SettingsFragment.KEY_HALF_FRAME_TEMP_PATH, profile))
                    .remove(key(SettingsFragment.KEY_HALF_FRAME_BASE_NAME, profile))
                    .remove(key(KEY_CAPTURE_TIME, profile))
                    .apply()
            }
        }

        return Session(profile, resolvedStep, resolvedTemp, resolvedBase, captureTime)
    }

    fun markStep(step: Int, captureTimeMillis: Long? = null) {
        val profile = currentProfile()
        val editor = prefs.edit().putInt(key(SettingsFragment.KEY_HALF_FRAME_STEP, profile), step)
        if (step == 1) {
            editor.putLong(key(KEY_CAPTURE_TIME, profile), captureTimeMillis ?: System.currentTimeMillis())
        } else {
            editor.remove(key(KEY_CAPTURE_TIME, profile))
        }
        editor.apply()
    }

    fun setTempPath(path: String?) {
        val profile = currentProfile()
        prefs.edit().putString(key(SettingsFragment.KEY_HALF_FRAME_TEMP_PATH, profile), path).apply()
    }

    fun setBaseName(baseName: String?) {
        val profile = currentProfile()
        prefs.edit().putString(key(SettingsFragment.KEY_HALF_FRAME_BASE_NAME, profile), baseName).apply()
    }

    fun clearCurrentSession(deleteTempFile: Boolean = false) {
        val profile = currentProfile()
        val tempPath = prefs.getString(key(SettingsFragment.KEY_HALF_FRAME_TEMP_PATH, profile), null)
        if (deleteTempFile && !tempPath.isNullOrBlank()) {
            runCatching { File(tempPath).delete() }
        }
        prefs.edit()
            .putInt(key(SettingsFragment.KEY_HALF_FRAME_STEP, profile), 0)
            .remove(key(SettingsFragment.KEY_HALF_FRAME_TEMP_PATH, profile))
            .remove(key(SettingsFragment.KEY_HALF_FRAME_BASE_NAME, profile))
            .remove(key(KEY_CAPTURE_TIME, profile))
            .apply()
    }

    fun tempFileForCurrentProfile(): File = File(context.filesDir, "half_frame_frame1_${currentProfile()}.jpg")

    fun scopedStepKeyForCurrentProfile(): String = key(SettingsFragment.KEY_HALF_FRAME_STEP, currentProfile())

    private fun key(base: String, profile: String): String = "${base}_$profile"

    companion object {
        private const val KEY_CAPTURE_TIME = "half_frame_capture_time"
        private const val MAX_RECOVER_WINDOW_MS = 120_000L
        const val PROFILE_NORMAL = "normal"
        const val PROFILE_HALF_SIDE = "half_side"
        const val PROFILE_HALF_TOP = "half_top"
    }
}
