package top.maary.darkbag.processor

import java.nio.ByteBuffer
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object ColorProcessor {
    private val nativeMutex = Mutex()

    init {
        System.loadLibrary("native-lib")
    }

    val backgroundSaveFlow = MutableSharedFlow<BackgroundSaveEvent>(extraBufferCapacity = 10)
    val halfFrameFlow = MutableSharedFlow<Int>(extraBufferCapacity = 5)

    external fun initMemoryPool(width: Int, height: Int, frames: Int)

    data class BackgroundSaveEvent(
        val baseName: String,
        val dngPath: String?,
        val jpgPath: String?,
        val targetUri: String?,
        val zoomFactor: Float,
        val orientation: Int,
        val saveJpg: Boolean
    )

    /**
     * @param dngData Byte array containing the full DNG file.
     * @param targetLog Index of target log curve.
     * @param lutPath Path to .cube file.
     * @param outputJpgPath Output path for JPEG.
     * @param useGpu Whether to use GPU acceleration.
     * @param orientation Orientation in degrees.
     * @param mirror Whether to mirror horizontally.
     * @return 0 for GPU Success, 1 for CPU Success (Fallback or requested), -1 for Failure.
     */
    suspend fun processRaw(
        dngData: ByteArray,
        targetLog: Int,
        lutPath: String?,
        exposure: Float = 0f,
        contrast: Float = 0f,
        saturation: Float = 0f,
        highlights: Float = 0f,
        shadows: Float = 0f,
        whites: Float = 0f,
        blacks: Float = 0f,
        digitalGain: Float = 1.0f,
        outputJpgPath: String?,
        useGpu: Boolean,
        orientation: Int,
        mirror: Boolean,
        outputBitmap: android.graphics.Bitmap? = null,
        downsampleFactor: Int = 1,
        zoomFactor: Float = 1.0f
    ): Int = nativeMutex.withLock {
        processRawNative(
            dngData, targetLog, lutPath, exposure, contrast, saturation,
            highlights, shadows, whites, blacks, digitalGain,
            outputJpgPath, useGpu, orientation, mirror, outputBitmap,
            downsampleFactor, zoomFactor
        )
    }

    private external fun processRawNative(
        dngData: ByteArray,
        targetLog: Int,
        lutPath: String?,
        exposure: Float,
        contrast: Float,
        saturation: Float,
        highlights: Float,
        shadows: Float,
        whites: Float,
        blacks: Float,
        digitalGain: Float,
        outputJpgPath: String?,
        useGpu: Boolean,
        orientation: Int,
        mirror: Boolean,
        outputBitmap: android.graphics.Bitmap?,
        downsampleFactor: Int,
        zoomFactor: Float
    ): Int

    /**
     * Optimized single frame processing using the Halide pipeline.
     */
    suspend fun processSingleFrameRaw(
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
        outputJpgPath: String?,
        outputDngPath: String?,
        digitalGain: Float,
        debugStats: LongArray?,
        outputBitmap: android.graphics.Bitmap? = null,
        tempRawPath: String? = null,
        zoomFactor: Float,
        mirror: Boolean
    ): Int = nativeMutex.withLock {
        processSingleFrameRawNative(
            bayerBuffer, width, height, orientation, whiteLevel,
            blackLevelPattern, lensShadingMap, lensShadingRows, lensShadingCols,
            whiteBalance, ccm, cfaPattern, iso, exposureTime, fNumber, focalLength,
            captureTimeMillis, targetLog, lutPath, outputJpgPath, outputDngPath,
            digitalGain, debugStats, outputBitmap, tempRawPath, zoomFactor, mirror
        )
    }

    private external fun processSingleFrameRawNative(
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
        outputJpgPath: String?,
        outputDngPath: String?,
        digitalGain: Float,
        debugStats: LongArray?,
        outputBitmap: android.graphics.Bitmap? = null,
        tempRawPath: String? = null,
        zoomFactor: Float,
        mirror: Boolean
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
        dngPath: String?,
        jpgPath: String?,
        targetUri: String?,
        zoomFactor: Float,
        orientation: Int,
        saveJpg: Boolean
    ) {
        backgroundSaveFlow.tryEmit(BackgroundSaveEvent(baseName, dngPath, jpgPath, targetUri, zoomFactor, orientation, saveJpg))
    }

    suspend fun exportHdrPlus(
        tempRawPath: String,
        width: Int,
        height: Int,
        orientation: Int,
        digitalGain: Float,
        targetLog: Int,
        lutPath: String?,
        exposure: Float = 0f,
        contrast: Float = 0f,
        saturation: Float = 0f,
        highlights: Float = 0f,
        shadows: Float = 0f,
        whites: Float = 0f,
        blacks: Float = 0f,
        jpgPath: String?,
        dngPath: String?,
        iso: Int,
        exposureTime: Long,
        fNumber: Float,
        focalLength: Float,
        captureTimeMillis: Long,
        ccm: FloatArray,
        whiteBalance: FloatArray,
        zoomFactor: Float,
        mirror: Boolean
    ): Int = nativeMutex.withLock {
        exportHdrPlusNative(
            tempRawPath, width, height, orientation, digitalGain, targetLog, lutPath,
            exposure, contrast, saturation, highlights, shadows, whites, blacks,
            jpgPath, dngPath, iso, exposureTime, fNumber, focalLength,
            captureTimeMillis, ccm, whiteBalance, zoomFactor, mirror
        )
    }

    private external fun exportHdrPlusNative(
        tempRawPath: String,
        width: Int,
        height: Int,
        orientation: Int,
        digitalGain: Float,
        targetLog: Int,
        lutPath: String?,
        exposure: Float,
        contrast: Float,
        saturation: Float,
        highlights: Float,
        shadows: Float,
        whites: Float,
        blacks: Float,
        jpgPath: String?,
        dngPath: String?,
        iso: Int,
        exposureTime: Long,
        fNumber: Float,
        focalLength: Float,
        captureTimeMillis: Long,
        ccm: FloatArray,
        whiteBalance: FloatArray,
        zoomFactor: Float,
        mirror: Boolean
    ): Int

    suspend fun processHdrPlus(
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
        outputJpgPath: String?,
        outputDngPath: String?,
        digitalGain: Float,
        debugStats: LongArray?, // [0] Halide, [1] Copy, [2] Post, [3] DNG Encode, [4] Save, [5] DNG Wait, [6] Total, [7] Align, [8] Merge, [9] Demosaic, [10] Denoise, [11] sRGB, [12] JNI Prep, [13] BlackWhite, [14] WB
        outputBitmap: android.graphics.Bitmap? = null,
        tempRawPath: String? = null,
        zoomFactor: Float,
        mirror: Boolean
    ): Int = nativeMutex.withLock {
        processHdrPlusNative(
            dngBuffers, width, height, orientation, whiteLevel, blackLevelPattern,
            lensShadingMap, lensShadingRows, lensShadingCols, useSensorColorMatrix,
            whiteBalance, ccm, ccmAlt, exportMatrixAB, cfaPattern, iso,
            exposureTime, fNumber, focalLength, captureTimeMillis, targetLog,
            lutPath, outputJpgPath, outputDngPath, digitalGain, debugStats,
            outputBitmap, tempRawPath, zoomFactor, mirror
        )
    }

    private external fun processHdrPlusNative(
        dngBuffers: Array<ByteBuffer>,
        width: Int,
        height: Int,
        orientation: Int,
        whiteLevel: Int,
        blackLevelPattern: IntArray,
        lensShadingMap: FloatArray?,
        lensShadingRows: Int,
        lensShadingCols: Int,
        useSensorColorMatrix: Boolean,
        whiteBalance: FloatArray,
        ccm: FloatArray,
        ccmAlt: FloatArray?,
        exportMatrixAB: Boolean,
        cfaPattern: Int,
        iso: Int,
        exposureTime: Long,
        fNumber: Float,
        focalLength: Float,
        captureTimeMillis: Long,
        targetLog: Int,
        lutPath: String?,
        outputJpgPath: String?,
        outputDngPath: String?,
        digitalGain: Float,
        debugStats: LongArray?,
        outputBitmap: android.graphics.Bitmap? = null,
        tempRawPath: String? = null,
        zoomFactor: Float,
        mirror: Boolean
    ): Int
}
