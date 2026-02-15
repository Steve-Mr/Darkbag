package com.android.example.cameraxbasic.processor

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.android.example.cameraxbasic.utils.ImageSaver

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
        val mirror = data.getBoolean("mirror", false)

        Log.d(TAG, "Background Export Worker started for $baseName")

        val ret = ColorProcessor.exportHdrPlus(
            tempRawPath, width, height, orientation, digitalGain, targetLog,
            lutPath, tiffPath, jpgPath, dngPath,
            iso, exposureTime, fNumber, focalLength, captureTimeMillis,
            ccm, whiteBalance, zoomFactor, mirror
        )

        if (ret == 0) {
            Log.d(TAG, "Background Export Worker finished JNI processing for $baseName")

            // Robustly finalize MediaStore export directly from Worker
            // JNI already did rotation and zoom!
            val finalUri = ImageSaver.saveProcessedImage(
                applicationContext,
                null,
                jpgPath,
                0, // orientation 0 (already handled by JNI)
                1.0f, // zoom 1.0 (already handled by JNI)
                baseName,
                dngPath,
                tiffPath,
                saveJpg,
                saveTiff,
                targetUri?.let { Uri.parse(it) },
                mirror = false // already handled by JNI
            )

            Log.d(TAG, "Background Export Worker finished successfully for $baseName. finalUri=$finalUri")

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
