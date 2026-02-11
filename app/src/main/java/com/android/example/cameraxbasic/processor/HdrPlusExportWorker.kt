package com.android.example.cameraxbasic.processor

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.android.example.cameraxbasic.utils.ImageSaver

class HdrPlusExportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val tempRawPath = inputData.getString("tempRawPath") ?: return Result.failure()
        val width = inputData.getInt("width", 0)
        val height = inputData.getInt("height", 0)
        val orientation = inputData.getInt("orientation", 0)
        val digitalGain = inputData.getFloat("digitalGain", 1.0f)
        val targetLog = inputData.getInt("targetLog", 0)
        val lutPath = inputData.getString("lutPath")
        val tiffPath = inputData.getString("tiffPath")
        val jpgPath = inputData.getString("jpgPath")
        val targetUri = inputData.getString("targetUri")
        val zoomFactor = inputData.getFloat("zoomFactor", 1.0f)
        val dngPath = inputData.getString("dngPath")
        val iso = inputData.getInt("iso", 100)
        val exposureTime = inputData.getLong("exposureTime", 10_000_000L)
        val fNumber = inputData.getFloat("fNumber", 1.8f)
        val focalLength = inputData.getFloat("focalLength", 0.0f)
        val captureTimeMillis = inputData.getLong("captureTimeMillis", 0L)
        val ccm = inputData.getFloatArray("ccm")
        if (ccm == null || ccm.size != 9) {
            Log.e(TAG, "Missing or malformed CCM array.")
            return Result.failure()
        }
        val whiteBalance = inputData.getFloatArray("whiteBalance")
        if (whiteBalance == null || whiteBalance.size != 4) {
            Log.e(TAG, "Missing or malformed WhiteBalance array.")
            return Result.failure()
        }
        val baseName = inputData.getString("baseName") ?: "HDRPLUS"
        val saveTiff = inputData.getBoolean("saveTiff", true)
        val saveJpg = inputData.getBoolean("saveJpg", true)

        Log.d(TAG, "Background Export Worker started for $baseName")

        val ret = ColorProcessor.exportHdrPlus(
            tempRawPath, width, height, orientation, digitalGain, targetLog,
            lutPath, tiffPath, jpgPath, dngPath,
            iso, exposureTime, fNumber, focalLength, captureTimeMillis,
            ccm, whiteBalance
        )

        return if (ret == 0) {
            Log.d(TAG, "Background Export Worker finished JNI processing for $baseName")

            // Robustly finalize MediaStore export directly from Worker
            val finalUri = ImageSaver.saveProcessedImage(
                applicationContext,
                null,
                jpgPath,
                orientation,
                zoomFactor,
                baseName,
                dngPath,
                tiffPath,
                saveJpg,
                saveTiff,
                targetUri?.let { Uri.parse(it) }
            )

            Log.d(TAG, "Background Export Worker finished successfully for $baseName. finalUri=$finalUri")

            // Still notify UI for thumbnail update if possible
            ColorProcessor.onBackgroundSaveComplete(
                baseName, tiffPath, dngPath, jpgPath, targetUri, zoomFactor, orientation, saveTiff, saveJpg
            )
            Result.success()
        } else {
            Log.e(TAG, "Background Export Worker failed with code $ret")
            Result.failure()
        }
    }

    companion object {
        private const val TAG = "HdrPlusExportWorker"
    }
}
