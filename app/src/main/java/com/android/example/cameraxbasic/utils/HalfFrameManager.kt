package com.android.example.cameraxbasic.utils

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.android.example.cameraxbasic.fragments.SettingsFragment
import java.io.File
import java.io.FileOutputStream

class HalfFrameManager(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)

    var step: Int
        get() = prefs.getInt(SettingsFragment.KEY_HALF_FRAME_STEP, 0)
        set(value) = prefs.edit().putInt(SettingsFragment.KEY_HALF_FRAME_STEP, value).apply()

    var tempPath: String?
        get() = prefs.getString(SettingsFragment.KEY_HALF_FRAME_TEMP_PATH, null)
        set(value) = prefs.edit().putString(SettingsFragment.KEY_HALF_FRAME_TEMP_PATH, value).apply()

    val isEnabled: Boolean
        get() = prefs.getBoolean(SettingsFragment.KEY_HALF_FRAME_MODE, false)

    val layout: String
        get() = prefs.getString(SettingsFragment.KEY_HALF_FRAME_LAYOUT, SettingsFragment.HALF_FRAME_LAYOUTS[0]) ?: SettingsFragment.HALF_FRAME_LAYOUTS[0]

    val downsample: Boolean
        get() = prefs.getBoolean(SettingsFragment.KEY_HALF_FRAME_DOWNSAMPLE, true)

    val dateStamp: Boolean
        get() = prefs.getBoolean(SettingsFragment.KEY_HALF_FRAME_DATE_STAMP, false)

    val lightLeak: Boolean
        get() = prefs.getBoolean(SettingsFragment.KEY_HALF_FRAME_LIGHT_LEAK, false)

    /**
     * Handles the captured JPG.
     * @param currentJpgPath Path to the just-captured JPG.
     * @return Path to the final stitched image if step 2 is complete, null otherwise.
     */
    fun handleCapture(currentJpgPath: String): String? {
        if (!isEnabled) return currentJpgPath

        if (step == 0) {
            // First frame: Save to internal temp
            val tempFile = File(context.filesDir, "half_frame_frame1.jpg")
            File(currentJpgPath).copyTo(tempFile, overwrite = true)
            tempPath = tempFile.absolutePath
            step = 1
            Log.d(TAG, "Half-frame Step 1 complete. Saved to $tempPath")
            return null // Signal that it's not finished
        } else {
            // Second frame: Stitch
            val firstPath = tempPath
            if (firstPath == null || !File(firstPath).exists()) {
                Log.e(TAG, "First frame missing, resetting to step 1")
                val tempFile = File(context.filesDir, "half_frame_frame1.jpg")
                File(currentJpgPath).copyTo(tempFile, overwrite = true)
                tempPath = tempFile.absolutePath
                step = 1
                return null
            }

            Log.d(TAG, "Half-frame Step 2: Stitching $firstPath and $currentJpgPath")
            val stitchedBitmap = HalfFrameUtils.stitchImages(firstPath, currentJpgPath, layout, downsample)
            if (stitchedBitmap == null) {
                Log.e(TAG, "Stitching failed")
                return currentJpgPath // Fallback to just current
            }

            // Add effects
            val finalBitmap = HalfFrameUtils.addEffects(stitchedBitmap, dateStamp, lightLeak)

            // Save stitched result to a temp file that will be processed by ImageSaver
            val stitchedFile = File(context.cacheDir, "stitched_halfframe_${System.currentTimeMillis()}.jpg")
            FileOutputStream(stitchedFile).use { out ->
                finalBitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }
            if (finalBitmap != stitchedBitmap) {
                stitchedBitmap.recycle()
            }
            finalBitmap.recycle()

            // Cleanup
            File(firstPath).delete()
            tempPath = null
            step = 0

            Log.d(TAG, "Half-frame complete. Stitched file: ${stitchedFile.absolutePath}")
            return stitchedFile.absolutePath
        }
    }

    /**
     * Gets a FileProvider URI for the intermediate frame, for external access or debugging.
     */
    fun getIntermediateUri(): Uri? {
        val path = tempPath ?: return null
        val file = File(path)
        if (!file.exists()) return null
        return FileProvider.getUriForFile(
            context,
            "com.android.example.cameraxbasic.fileprovider",
            file
        )
    }

    companion object {
        private const val TAG = "HalfFrameManager"
    }
}
