package com.android.example.cameraxbasic.processor

import java.nio.ByteBuffer
import kotlinx.coroutines.flow.MutableSharedFlow

object ColorProcessor {
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
        val targetUri: String?,
        val zoomFactor: Float,
        val orientation: Int,
        val saveTiff: Boolean,
        val saveJpg: Boolean
    )

    /**
     * @param dngData Byte array containing the full DNG file.
     * @param targetLog Index of target log curve.
     * @param lutPath Path to .cube file.
     * @param outputTiffPath Output path for TIFF.
     * @param outputJpgPath Output path for JPEG.
     * @param useGpu Whether to use GPU acceleration.
     * @param orientation Orientation in degrees.
     * @param mirror Whether to mirror horizontally.
     * @return 0 for GPU Success, 1 for CPU Success (Fallback or requested), -1 for Failure.
     */
    external fun processRaw(
        dngData: ByteArray,
        targetLog: Int,
        lutPath: String?,
        outputTiffPath: String?,
        outputJpgPath: String?,
        useGpu: Boolean,
        orientation: Int,
        mirror: Boolean
    ): Int

    /**
     * Optimized single frame processing using the Halide pipeline.
     */
    external fun processSingleFrameRaw(
        bayerBuffer: ByteBuffer,
        width: Int,
        height: Int,
        orientation: Int,
        whiteLevel: Int,
        blackLevelPattern: IntArray,
        lensShadingMap: FloatArray?,
        lensShadingRows: Int,
        lensShadingCols: Int,
        whiteBalance: FloatArray,
        ccm: FloatArray,
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
        digitalGain: Float,
        debugStats: LongArray?,
        outputBitmap: android.graphics.Bitmap? = null,
        tempRawPath: String? = null,
        zoomFactor: Float,
        mirror: Boolean,
        activeArray: IntArray? = null
    ): Int

    /**
     * Loads a .cube LUT file into a flat float array (RGB interleaved).
     * @param lutPath Path to .cube file.
     * @return Float array of size N^3 * 3, or null if loading failed.
     */
    external fun loadLutData(lutPath: String): FloatArray?

    /**
     * Callback for background export completion. Called from JNI thread.
     */
    @JvmStatic
    fun onBackgroundSaveComplete(
        baseName: String,
        tiffPath: String?,
        dngPath: String?,
        jpgPath: String?,
        targetUri: String?,
        zoomFactor: Float,
        orientation: Int,
        saveTiff: Boolean,
        saveJpg: Boolean
    ) {
        backgroundSaveFlow.tryEmit(BackgroundSaveEvent(baseName, tiffPath, dngPath, jpgPath, targetUri, zoomFactor, orientation, saveTiff, saveJpg))
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
        iso: Int,
        exposureTime: Long,
        fNumber: Float,
        focalLength: Float,
        captureTimeMillis: Long,
        ccm: FloatArray,
        whiteBalance: FloatArray,
        zoomFactor: Float,
        mirror: Boolean,
        activeArray: IntArray? = null
    ): Int

    external fun processHdrPlus(
        dngBuffers: Array<ByteBuffer>,
        width: Int,
        height: Int,
        orientation: Int,
        whiteLevel: Int,
        blackLevelPattern: IntArray, // [r, g0, g1, b]
        lensShadingMap: FloatArray?, // [4 * rows * cols], channel-major R,GE,GO,B
        lensShadingRows: Int,
        lensShadingCols: Int,
        useSensorColorMatrix: Boolean,
        whiteBalance: FloatArray, // [r, g0, g1, b]
        ccm: FloatArray,          // selected [3x3]
        ccmAlt: FloatArray?,      // alternate [3x3] for AB compare
        exportMatrixAB: Boolean,
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
        digitalGain: Float,
        debugStats: LongArray?, // [0] Halide, [1] Copy, [2] Post, [3] DNG Encode, [4] Save, [5] DNG Wait, [6] Total, [7] Align, [8] Merge, [9] Demosaic, [10] Denoise, [11] sRGB, [12] JNI Prep, [13] BlackWhite, [14] WB
        outputBitmap: android.graphics.Bitmap? = null,
        tempRawPath: String? = null,
        zoomFactor: Float,
        mirror: Boolean,
        activeArray: IntArray? = null
    ): Int
}
