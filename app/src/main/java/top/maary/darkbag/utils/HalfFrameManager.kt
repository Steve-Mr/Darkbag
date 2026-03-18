package top.maary.darkbag.utils

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import top.maary.darkbag.fragments.SettingsFragment
import top.maary.darkbag.provider.HalfFrameDocumentsProvider
import java.io.File
import java.io.FileOutputStream

class HalfFrameManager(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
    private val sessionStore = HalfFrameSessionStore(context)

    data class Metadata(
        val profile: String,
        val dateStamp: Boolean,
        val captureTimeMillis: Long = System.currentTimeMillis(),
        val frame1BaseName: String? = null,
        val frame1TempPath: String? = null,
        val frame1CaptureTime: Long = 0L,
        val digitalGain: Float = 1.0f,
        val frame1DigitalGain: Float = 1.0f,
        val flareType: Int = -1
    )

    var step: Int
        get() = sessionStore.readSession().step
        set(value) = sessionStore.markStep(value)

    var tempPath: String?
        get() = sessionStore.readSession().tempPath
        set(value) = sessionStore.setTempPath(value)

    var frame1BaseName: String?
        get() = sessionStore.readSession().baseName
        set(value) = sessionStore.setBaseName(value)

    val isEnabled: Boolean
        get() = prefs.getBoolean(SettingsFragment.KEY_HALF_FRAME_MODE, false)

    val layout: String
        get() = prefs.getString(SettingsFragment.KEY_HALF_FRAME_LAYOUT, SettingsFragment.HALF_FRAME_LAYOUT_SBS) ?: SettingsFragment.HALF_FRAME_LAYOUT_SBS

    val downsample: Boolean
        get() = prefs.getBoolean(SettingsFragment.KEY_HALF_FRAME_DOWNSAMPLE, true)

    val dateStamp: Boolean
        get() = prefs.getBoolean(SettingsFragment.KEY_HALF_FRAME_DATE_STAMP, false)

    val lightLeak: Boolean
        get() = prefs.getBoolean(SettingsFragment.KEY_HALF_FRAME_LIGHT_LEAK, false)

    val saveJpg: Boolean
        get() = true // Mandatory for half-frame mode

    val saveRaw: Boolean
        get() = prefs.getBoolean(SettingsFragment.KEY_HALF_FRAME_SAVE_RAW, false)

    /**
     * Handles the captured JPG.
     * @param currentJpgPath Path to the just-captured JPG.
     * @param baseName Unique ID for this capture.
     * @param isFastPath Whether this is a fast preview.
     * @param metadata Capture-time metadata (profile, dateStamp).
     * @return Path to the final stitched image if complete, null otherwise.
     */
    fun handleCapture(
        currentJpgPath: String,
        baseName: String,
        isFastPath: Boolean,
        metadata: Metadata? = null,
        digitalGain: Float = 1.0f
    ): String? {
        val activeProfile = metadata?.profile ?: sessionStore.currentProfile()
        val isManualMode = metadata != null

        // If no metadata and not enabled globally, it's a normal capture
        if (!isManualMode && !isEnabled) return currentJpgPath
        // If metadata profile is "normal", it's a normal capture even if HF is enabled globally
        if (activeProfile == HalfFrameSessionStore.PROFILE_NORMAL) return currentJpgPath

        val f1Base = sessionStore.readSession(profile = activeProfile).baseName

        if (f1Base == null || baseName == f1Base) {
            // This is Frame 1 (either Fast Path or HQ update)
            if (f1Base == null) {
                sessionStore.setBaseName(baseName, activeProfile)
                Log.d(TAG, "Frame 1 Start: $baseName in $activeProfile")
            } else {
                Log.d(TAG, "Frame 1 Update (HQ): $baseName in $activeProfile")
            }

            // Save to internal temp
            val tempFile = sessionStore.tempFileForProfile(activeProfile)
            File(currentJpgPath).copyTo(tempFile, overwrite = true)
            sessionStore.setTempPath(tempFile.absolutePath, activeProfile)

            // Store capture time and digital gain. Only update step on fast path to avoid race with frame 2
            if (isFastPath) {
                if (metadata != null) {
                    sessionStore.markStep(1, metadata.captureTimeMillis, activeProfile, digitalGain = metadata.digitalGain)
                } else {
                    sessionStore.markStep(1, tempFile.lastModified(), activeProfile, digitalGain = digitalGain)
                }
            }

            return null
        } else {
            // This is Frame 2
            val session = sessionStore.readSession(profile = activeProfile)

            // Prioritize metadata-provided partner info to avoid race with UI thread clearing prefs
            val firstPath = metadata?.frame1TempPath ?: session.tempPath
            val time1 = if (metadata?.frame1CaptureTime != null && metadata.frame1CaptureTime > 0)
                metadata.frame1CaptureTime else session.captureTimeMillis
            val time2 = metadata?.captureTimeMillis ?: System.currentTimeMillis()

            val partnerBaseName = metadata?.frame1BaseName ?: session.baseName
            val dateStampEnabled = metadata?.dateStamp ?: dateStamp
            val activeLayout = if (activeProfile == HalfFrameSessionStore.PROFILE_HALF_TOP)
                SettingsFragment.HALF_FRAME_LAYOUT_TB else SettingsFragment.HALF_FRAME_LAYOUT_SBS

            if (isFastPath) {
                // Perform fast stitching for immediate thumbnail feedback
                if (firstPath == null || !File(firstPath).exists()) {
                    Log.e(TAG, "First frame missing for fast stitch in $activeProfile (baseName: $baseName, partner: $partnerBaseName)")
                    return null
                }

                Log.d(TAG, "Frame 2 Fast: Stitching $partnerBaseName and $baseName in $activeProfile")
                val stitchedBitmap = HalfFrameUtils.stitchImages(firstPath, currentJpgPath, activeLayout, downsample)
                if (stitchedBitmap == null) return null

                val finalBitmap = HalfFrameUtils.addEffects(
                    stitchedBitmap, dateStampEnabled, lightLeak, activeLayout, time1, time2,
                    flareType = metadata?.flareType ?: if (lightLeak) java.util.Random().nextInt(2) + 1 else -1
                )
                val stitchedFile = File(context.cacheDir, "stitched_hf_fast.jpg")
                FileOutputStream(stitchedFile).use { out ->
                    finalBitmap.compress(Bitmap.CompressFormat.JPEG, 70, out)
                }
                if (finalBitmap != stitchedBitmap) stitchedBitmap.recycle()
                finalBitmap.recycle()
                return stitchedFile.absolutePath
            } else {
                // HQ Path: Stitch!
                if (firstPath == null || !File(firstPath).exists()) {
                    Log.e(TAG, "First frame missing in $activeProfile for HQ (baseName: $baseName, partner: $partnerBaseName), resetting to step 1")
                    sessionStore.setBaseName(baseName, activeProfile)
                    val tempFile = sessionStore.tempFileForProfile(activeProfile)
                    File(currentJpgPath).copyTo(tempFile, overwrite = true)
                    sessionStore.setTempPath(tempFile.absolutePath, activeProfile)
                    sessionStore.markStep(1, time2, activeProfile)
                    return null
                }

                Log.d(TAG, "Frame 2 HQ: Stitching $partnerBaseName and $baseName in $activeProfile")
                val stitchedBitmap = HalfFrameUtils.stitchImages(firstPath, currentJpgPath, activeLayout, downsample)
                if (stitchedBitmap == null) {
                    Log.e(TAG, "Stitching failed in $activeProfile")
                    return null
                }

                val finalBitmap = HalfFrameUtils.addEffects(
                    stitchedBitmap, dateStampEnabled, lightLeak, activeLayout, time1, time2,
                    flareType = metadata?.flareType ?: if (lightLeak) java.util.Random().nextInt(2) + 1 else -1
                )
                val stitchedFile = File(context.cacheDir, "${partnerBaseName}_stitched.jpg")
                FileOutputStream(stitchedFile).use { out ->
                    finalBitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                }

                if (finalBitmap != stitchedBitmap) stitchedBitmap.recycle()
                finalBitmap.recycle()

                // Cleanup
                File(firstPath).delete()
                sessionStore.clearProfile(activeProfile)
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
