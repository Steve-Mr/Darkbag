package top.maary.darkbag.processor

import java.nio.ByteBuffer
import kotlinx.coroutines.flow.MutableSharedFlow
import top.maary.darkbag.models.CaptureMetadata

object ColorProcessor {
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
    external fun processRaw(
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
        outputTiffPath: String? = null,
        useGpu: Boolean,
        orientation: Int,
        mirror: Boolean,
        outputBitmap: android.graphics.Bitmap? = null,
        downsampleFactor: Int = 1,
        zoomFactor: Float = 1.0f,
        metadata: CaptureMetadata? = null
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
        targetLog: Int,
        lutPath: String?,
        outputJpgPath: String?,
        outputDngPath: String?,
        digitalGain: Float,
        debugStats: LongArray?,
        outputBitmap: android.graphics.Bitmap? = null,
        tempRawPath: String? = null,
        zoomFactor: Float,
        mirror: Boolean,
        metadata: CaptureMetadata
    ): Int

    /**
     * Loads a .cube LUT file into a flat float array (RGB interleaved).
     * @param lutPath Path to .cube file.
     * @return Float array of size N^3 * 3, or null if loading failed.
     */
    external fun loadLutData(lutPath: String): FloatArray?

    /**
     * Saves an existing RGBA Bitmap to a TIFF file.
     * Useful for saving stitched half-frame images with effects already applied.
     */
    /**
     * Saves an existing RGBA Bitmap to a TIFF file with metadata.
     */
    external fun saveBitmapToTiff(
        bitmap: android.graphics.Bitmap,
        outputTiffPath: String,
        metadata: CaptureMetadata
    ): Boolean

    /**
     * Applies LUT and Log curve directly to an Android Bitmap (RGBA_8888).
     * Used for ZSL fast preview generation.
     */
    external fun processZslBitmapWithLut(
        bitmap: android.graphics.Bitmap,
        targetLog: Int,
        lutPath: String?
    ): Int


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

    external fun exportHdrPlus(
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
        ccm: FloatArray,
        whiteBalance: FloatArray,
        zoomFactor: Float,
        mirror: Boolean,
        metadata: CaptureMetadata
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
        targetLog: Int,
        lutPath: String?,
        outputJpgPath: String?,
        outputDngPath: String?,
        digitalGain: Float,
        debugStats: LongArray?, // [0] Halide, [1] Copy, [2] Post, [3] DNG Encode, [4] Save, [5] DNG Wait, [6] Total, [7] Align, [8] Merge, [9] Demosaic, [10] Denoise, [11] sRGB, [12] JNI Prep, [13] BlackWhite, [14] WB
        outputBitmap: android.graphics.Bitmap? = null,
        tempRawPath: String? = null,
        zoomFactor: Float,
        mirror: Boolean,
        metadata: CaptureMetadata
    ): Int
}
