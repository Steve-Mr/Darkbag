package top.maary.darkbag.processor
import top.maary.darkbag.fragments.SettingsFragment
import top.maary.darkbag.utils.HalfFrameManager

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import top.maary.darkbag.utils.ImageSaver

class HdrPlusExportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val data = inputData
        val tempRawPath = data.getString("tempRawPath") ?: return Result.failure()
        val width = data.getInt("width", 0)
        val height = data.getInt("height", 0)
        val orientation = data.getInt("orientation", 0)
        val digitalGain = data.getFloat("digitalGain", 1.0f)
        val targetLog = data.getInt("targetLog", 0)
        val lutPath = data.getString("lutPath")
        val tiffPath = data.getString("tiffPath")
        val jpgPath = data.getString("jpgPath")
        val targetUri = data.getString("targetUri")
        val zoomFactor = data.getFloat("zoomFactor", 1.0f)
        val dngPath = data.getString("dngPath")
        val iso = data.getInt("iso", 100)
        val exposureTime = data.getLong("exposureTime", 10_000_000L)
        val fNumber = data.getFloat("fNumber", 1.8f)
        val focalLength = data.getFloat("focalLength", 0.0f)
        val captureTimeMillis = data.getLong("captureTimeMillis", 0L)

        val ccm = data.getFloatArray("ccm")
        if (ccm == null || ccm.size != 9) {
            Log.e(TAG, "Missing or malformed CCM array.")
            return Result.failure()
        }
        val whiteBalance = data.getFloatArray("whiteBalance")
        if (whiteBalance == null || whiteBalance.size != 4) {
            Log.e(TAG, "Missing or malformed WhiteBalance array.")
            return Result.failure()
        }
        val baseName = data.getString("baseName") ?: "HDRPLUS"
        val saveTiff = data.getBoolean("saveTiff", true)
        val saveJpg = data.getBoolean("saveJpg", true)
        val saveRaw = data.getBoolean("saveRaw", true)
        val jpgFolderUri = data.getString("jpgFolderUri")
        val tiffFolderUri = data.getString("tiffFolderUri")
        val rawFolderUri = data.getString("rawFolderUri")
        val mirror = data.getBoolean("mirror", false)

        val hfProfile = data.getString("hfProfile")
        val hfDateStamp = data.getBoolean("hfDateStamp", false)
        val hfCaptureTime = data.getLong("hfCaptureTime", captureTimeMillis)
        val hfF1Base = data.getString("hfF1Base")
        val hfF1Path = data.getString("hfF1Path")
        val hfF1Time = data.getLong("hfF1Time", 0L)

        val hfMetadata = hfProfile?.let {
            HalfFrameManager.Metadata(
                profile = it,
                dateStamp = hfDateStamp,
                captureTimeMillis = hfCaptureTime,
                frame1BaseName = hfF1Base,
                frame1TempPath = hfF1Path,
                frame1CaptureTime = hfF1Time
            )
        }

        val prefs = applicationContext.getSharedPreferences(
            SettingsFragment.PREFS_NAME,
            Context.MODE_PRIVATE
        )
        val editConfig = if (hfMetadata == null) {
            val activeLut = prefs.getString(SettingsFragment.KEY_ACTIVE_LUT, "None")
            val targetLog = prefs.getString(SettingsFragment.KEY_TARGET_LOG, "None")
            top.maary.darkbag.models.EditConfig(
                log = targetLog,
                lut = activeLut
            )
        } else null

        Log.d(TAG, "Background Export Worker started for $baseName")

        val ret = ColorProcessor.exportHdrPlus(
            tempRawPath = tempRawPath,
            width = width,
            height = height,
            orientation = orientation,
            digitalGain = digitalGain,
            targetLog = targetLog,
            lutPath = lutPath,
            exposure = editConfig?.exposure ?: 0f,
            contrast = editConfig?.contrast ?: 0f,
            saturation = editConfig?.saturation ?: 0f,
            highlights = editConfig?.highlights ?: 0f,
            shadows = editConfig?.shadows ?: 0f,
            whites = editConfig?.whites ?: 0f,
            blacks = editConfig?.blacks ?: 0f,
            tiffPath = tiffPath,
            jpgPath = jpgPath,
            dngPath = dngPath,
            iso = iso,
            exposureTime = exposureTime,
            fNumber = fNumber,
            focalLength = focalLength,
            captureTimeMillis = captureTimeMillis,
            ccm = ccm,
            whiteBalance = whiteBalance,
            zoomFactor = zoomFactor,
            mirror = mirror
        )

        if (ret == 0) {
            Log.d(TAG, "Background Export Worker finished JNI processing for $baseName")

            // Robustly finalize MediaStore export directly from Worker
            // JNI already did rotation and zoom!
            val finalUri = ImageSaver.saveProcessedImage(
                context = applicationContext,
                inputBitmap = null,
                bmpPath = jpgPath,
                rotationDegrees = 0, // orientation 0 (already handled by JNI)
                zoomFactor = 1.0f, // zoom 1.0 (already handled by JNI)
                baseName = baseName,
                linearDngPath = dngPath,
                tiffPath = tiffPath,
                saveJpg = saveJpg,
                saveTiff = saveTiff,
                saveRaw = saveRaw,
                jpgFolderUri = jpgFolderUri,
                tiffFolderUri = tiffFolderUri,
                rawFolderUri = rawFolderUri,
                targetUri = targetUri?.let { Uri.parse(it) },
                mirror = false, // already handled by JNI
                halfFrameMetadata = hfMetadata,
                editConfig = editConfig
            )

            Log.d(TAG, "Background Export Worker finished successfully for $baseName. finalUri=$finalUri")

            if (finalUri != null) {
                val prefs = applicationContext.getSharedPreferences(
                    SettingsFragment.PREFS_NAME,
                    Context.MODE_PRIVATE
                )
                prefs.edit().putString(SettingsFragment.KEY_LAST_CAPTURE_URI, finalUri.toString()).apply()
            }

            // Still notify UI for thumbnail update if possible
            // We pass null for paths to signal that saving is already done
            ColorProcessor.onBackgroundSaveComplete(
                baseName, null, null, null, finalUri?.toString(), zoomFactor, orientation, saveTiff, saveJpg
            )
            return Result.success()
        } else {
            Log.e(TAG, "Background Export Worker failed with code $ret")
            // Notify UI to stop animation even on failure
            ColorProcessor.onBackgroundSaveComplete(
                baseName, null, null, null, null, zoomFactor, orientation, saveTiff, saveJpg
            )
            return Result.failure()
        }
    }

    companion object {
        private const val TAG = "HdrPlusExportWorker"
    }
}
