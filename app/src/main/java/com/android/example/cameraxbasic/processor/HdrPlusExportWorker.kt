package com.android.example.cameraxbasic.processor

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

class HdrPlusExportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val tempRawPath = inputData.getString("tempRawPath") ?: return@withContext Result.failure()
        val width = inputData.getInt("width", 0)
        val height = inputData.getInt("height", 0)
        val orientation = inputData.getInt("orientation", 0)
        val digitalGain = inputData.getFloat("digitalGain", 1.0f)
        val zoomFactor = inputData.getFloat("zoomFactor", 1.0f)
        val targetLog = inputData.getInt("targetLog", 0)
        val lutPath = inputData.getString("lutPath")
        val tiffPath = inputData.getString("tiffPath")
        val jpgPath = inputData.getString("jpgPath")
        val dngPath = inputData.getString("dngPath")
        val iso = inputData.getInt("iso", 100)
        val exposureTime = inputData.getLong("exposureTime", 10_000_000L)
        val fNumber = inputData.getFloat("fNumber", 1.8f)
        val focalLength = inputData.getFloat("focalLength", 0.0f)
        val captureTimeMillis = inputData.getLong("captureTimeMillis", 0L)
        val ccm = inputData.getFloatArray("ccm") ?: floatArrayOf()
        val whiteBalance = inputData.getFloatArray("whiteBalance") ?: floatArrayOf()
        val baseName = inputData.getString("baseName") ?: "HDRPLUS"
        val saveTiff = inputData.getBoolean("saveTiff", true)
        val targetUri = inputData.getString("targetUri")

        Log.d(TAG, "Background Export Worker started for $baseName (targetUri=$targetUri). Waiting for permit...")

        ColorProcessor.exportSemaphore.acquire()
        val ret = try {
            ColorProcessor.exportHdrPlus(
                tempRawPath, width, height, orientation, digitalGain, targetLog,
                lutPath, tiffPath, jpgPath, dngPath,
                iso, exposureTime, fNumber, focalLength, captureTimeMillis,
                ccm, whiteBalance, zoomFactor
            )
        } finally {
            // Clean up temp RAW file immediately after processing
            try { java.io.File(tempRawPath).delete() } catch (e: Exception) { Log.e(TAG, "Failed to delete temp RAW", e) }
            ColorProcessor.exportSemaphore.release()
        }

        if (ret == 0) {
            Log.d(TAG, "Background Export Worker finished successfully for $baseName")
            // Notify completion via same flow as JNI background thread for MediaStore consistency
            ColorProcessor.onBackgroundSaveComplete(baseName, tiffPath, dngPath, jpgPath, saveTiff, targetUri)
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
