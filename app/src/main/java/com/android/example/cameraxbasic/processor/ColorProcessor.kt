package com.android.example.cameraxbasic.processor

import java.nio.ByteBuffer
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.flow.MutableSharedFlow

object ColorProcessor {
    // Limit concurrent background HQ exports to prevent OOM.
    // Each 12MP image processing consumes significant RAM.
    val exportSemaphore = Semaphore(1)

    init {
        System.loadLibrary("native-lib")
    }

    val backgroundSaveFlow = MutableSharedFlow<BackgroundSaveEvent>(extraBufferCapacity = 10)

    external fun initMemoryPool(width: Int, height: Int, frames: Int)

    data class BackgroundSaveEvent(
        val baseName: String,
        val tiffPath: String?,
        val dngPath: String?,
        val jpgPath: String?,
        val saveTiff: Boolean,
        val targetUri: String? = null
    )

    /**
     * @param dngData Byte array containing the full DNG file.
     * @param targetLog Index of target log curve.
     * @param lutPath Path to .cube file.
     * @param outputTiffPath Output path for TIFF.
     * @param outputJpgPath Output path for JPEG.
     * @param useGpu Whether to use GPU acceleration.
     * @return 0 for GPU Success, 1 for CPU Success (Fallback or requested), -1 for Failure.
     */
    external fun processRaw(
        dngData: ByteArray,
        targetLog: Int,
        lutPath: String?,
        outputTiffPath: String?,
        outputJpgPath: String?,
        useGpu: Boolean
    ): Int

    /**
     * Loads a .cube LUT file into a flat float array (RGB interleaved).
     * @param lutPath Path to .cube file.
     * @return Float array of size N^3 * 3, or null if loading failed.
     */
    external fun loadLutData(lutPath: String): FloatArray?

    /**
     * Processes a burst of RAW frames using the HDR+ pipeline.
     * @param outputBitmap Optional Bitmap to receive the processed preview (faster than BMP file).
     */
    /**
     * Callback for background export completion. Called from JNI thread.
     */
    @JvmStatic
    fun onBackgroundSaveComplete(
        baseName: String,
        tiffPath: String?,
        dngPath: String?,
        jpgPath: String? = null,
        saveTiff: Boolean,
        targetUri: String? = null
    ) {
        backgroundSaveFlow.tryEmit(BackgroundSaveEvent(baseName, tiffPath, dngPath, jpgPath, saveTiff, targetUri))
    }

    external fun exportHdrPlus(
        tempRawPath: String,
        width: Int,
        height: Int,
        orientation: Int,
        digitalGain: Float,
        targetLog: Int,
        lutPath: String?,
        tiffPath: String?,
        jpgPath: String?,
        dngPath: String?,
        iso: Int,
        exposureTime: Long,
        fNumber: Float,
        focalLength: Float,
        captureTimeMillis: Long,
        ccm: FloatArray,
        whiteBalance: FloatArray,
        zoomFactor: Float = 1.0f
    ): Int

    external fun processHdrPlus(
        dngBuffers: Array<ByteBuffer>,
        width: Int,
        height: Int,
        orientation: Int,
        whiteLevel: Int,
        blackLevel: Int,
        whiteBalance: FloatArray, // [r, g0, g1, b]
        ccm: FloatArray,          // [3x3]
        cfaPattern: Int,
        iso: Int,
        exposureTime: Long,
        fNumber: Float,
        focalLength: Float,
        captureTimeMillis: Long,
        targetLog: Int,
        lutPath: String?,
        outputTiffPath: String?,
        outputJpgPath: String?,
        outputDngPath: String?,
        digitalGain: Float,
        debugStats: LongArray?, // [0] Halide, [1] Copy, [2] Post, [3] DNG Encode, [4] Save, [5] DNG Wait, [6] Total, [7] Align, [8] Merge, [9] Demosaic, [10] Denoise, [11] sRGB, [12] JNI Prep, [13] BlackWhite, [14] WB
        outputBitmap: android.graphics.Bitmap? = null,
        isAsync: Boolean = false,
        tempRawPath: String? = null,
        zoomFactor: Float = 1.0f
    ): Int
}
