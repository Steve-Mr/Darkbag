package com.android.example.cameraxbasic.utils

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.android.example.cameraxbasic.fragments.SettingsFragment
import com.android.example.cameraxbasic.provider.HalfFrameDocumentsProvider
import java.io.File
import java.io.FileOutputStream

class HalfFrameManager(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)

    private fun profileKey(): String {
        if (!isEnabled) return "normal"
        return if (layout == SettingsFragment.HALF_FRAME_LAYOUTS[0]) "half_side" else "half_top"
    }

    private fun scopedKey(base: String): String = "${base}_${profileKey()}"

    var step: Int
        get() = prefs.getInt(scopedKey(SettingsFragment.KEY_HALF_FRAME_STEP), 0)
        set(value) = prefs.edit().putInt(scopedKey(SettingsFragment.KEY_HALF_FRAME_STEP), value).apply()

    var tempPath: String?
        get() = prefs.getString(scopedKey(SettingsFragment.KEY_HALF_FRAME_TEMP_PATH), null)
        set(value) = prefs.edit().putString(scopedKey(SettingsFragment.KEY_HALF_FRAME_TEMP_PATH), value).apply()

    var frame1BaseName: String?
        get() = prefs.getString(scopedKey(SettingsFragment.KEY_HALF_FRAME_BASE_NAME), null)
        set(value) = prefs.edit().putString(scopedKey(SettingsFragment.KEY_HALF_FRAME_BASE_NAME), value).apply()

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

    val saveJpg: Boolean
        get() = prefs.getBoolean(SettingsFragment.KEY_HALF_FRAME_SAVE_JPG, true)

    val saveRaw: Boolean
        get() = prefs.getBoolean(SettingsFragment.KEY_HALF_FRAME_SAVE_RAW, false)

    /**
     * Handles the captured JPG.
     * @param currentJpgPath Path to the just-captured JPG.
     * @param baseName Unique ID for this capture.
     * @param isFastPath Whether this is a fast preview.
     * @return Path to the final stitched image if complete, null otherwise.
     */
    fun handleCapture(currentJpgPath: String, baseName: String, isFastPath: Boolean): String? {
        if (!isEnabled) return currentJpgPath

        val f1Base = frame1BaseName

        if (f1Base == null || baseName == f1Base) {
            // This is Frame 1 (either Fast Path or HQ update)
            if (f1Base == null) {
                frame1BaseName = baseName
                Log.d(TAG, "Frame 1 Start: $baseName")
            } else {
                Log.d(TAG, "Frame 1 Update (HQ): $baseName")
            }

            // Save to internal temp
            val tempFile = File(context.filesDir, "half_frame_frame1_${profileKey()}.jpg")
            File(currentJpgPath).copyTo(tempFile, overwrite = true)
            tempPath = tempFile.absolutePath
            return null
        } else {
            // This is Frame 2
            if (isFastPath) {
                // Perform fast stitching for immediate thumbnail feedback
                val firstPath = tempPath
                if (firstPath == null || !File(firstPath).exists()) {
                    Log.e(TAG, "First frame missing for fast stitch")
                    return null
                }

                Log.d(TAG, "Frame 2 Fast: Stitching $f1Base and $baseName")
                val stitchedBitmap = HalfFrameUtils.stitchImages(firstPath, currentJpgPath, layout, downsample)
                if (stitchedBitmap == null) return null

                val finalBitmap = HalfFrameUtils.addEffects(stitchedBitmap, dateStamp, lightLeak, layout)
                val stitchedFile = File(context.cacheDir, "stitched_hf_fast.jpg")
                FileOutputStream(stitchedFile).use { out ->
                    finalBitmap.compress(Bitmap.CompressFormat.JPEG, 70, out)
                }
                if (finalBitmap != stitchedBitmap) stitchedBitmap.recycle()
                finalBitmap.recycle()
                return stitchedFile.absolutePath
            } else {
                // HQ Path: Stitch!
                val firstPath = tempPath
                if (firstPath == null || !File(firstPath).exists()) {
                    Log.e(TAG, "First frame missing, resetting to step 1")
                    frame1BaseName = baseName
                    val tempFile = File(context.filesDir, "half_frame_frame1_${profileKey()}.jpg")
                    File(currentJpgPath).copyTo(tempFile, overwrite = true)
                    tempPath = tempFile.absolutePath
                    return null
                }

                Log.d(TAG, "Frame 2 HQ: Stitching $f1Base and $baseName")
                val stitchedBitmap = HalfFrameUtils.stitchImages(firstPath, currentJpgPath, layout, downsample)
                if (stitchedBitmap == null) {
                    Log.e(TAG, "Stitching failed")
                    return null
                }

                val finalBitmap = HalfFrameUtils.addEffects(stitchedBitmap, dateStamp, lightLeak, layout)
                val stitchedFile = File(context.cacheDir, "stitched_hf_${System.currentTimeMillis()}.jpg")
                FileOutputStream(stitchedFile).use { out ->
                    finalBitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                }
                if (finalBitmap != stitchedBitmap) stitchedBitmap.recycle()
                finalBitmap.recycle()

                // Cleanup
                File(firstPath).delete()
                tempPath = null
                frame1BaseName = null
                return stitchedFile.absolutePath
            }
        }
    }

    /**
     * Gets a DocumentsProvider URI for the intermediate frame.
     */
    fun getIntermediateUri(): Uri? {
        val path = tempPath ?: return null
        val file = File(path)
        if (!file.exists()) return null
        return DocumentsContract.buildDocumentUri(
            HalfFrameDocumentsProvider.AUTHORITY,
            "file:${file.name}"
        )
    }

    companion object {
        private const val TAG = "HalfFrameManager"
    }
}
