package top.maary.darkbag.processor

import java.nio.ByteBuffer
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.media.Image
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableSharedFlow
import top.maary.darkbag.models.CaptureMetadata

object ColorProcessor {
    init {
        try {
            System.loadLibrary("native-lib")
        } catch (e: UnsatisfiedLinkError) {
            // Expected during host unit tests
        }
    }

    val backgroundSaveFlow = MutableSharedFlow<BackgroundSaveEvent>(extraBufferCapacity = 10)
    val halfFrameFlow = MutableSharedFlow<Int>(extraBufferCapacity = 5)

    val imageProcessingDispatcher = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread {
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DEFAULT + 2)
            } catch (e: Exception) {
                // Ignore
            }
            runnable.run()
        }.apply {
            name = "HdrPlusProcessor"
            isDaemon = true
        }
    }.asCoroutineDispatcher()

    val exportProcessingDispatcher = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread {
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DEFAULT + 2)
            } catch (e: Exception) {
                // Ignore
            }
            runnable.run()
        }.apply {
            name = "HdrPlusExporter"
            isDaemon = true
        }
    }.asCoroutineDispatcher()

    external fun initMemoryPool(width: Int, height: Int, frames: Int)

    external fun allocateDirectBuffer(capacity: Long): ByteBuffer?
    external fun freeDirectBuffer(buffer: ByteBuffer)
    external fun freeSharedRawMemory(tempRawPath: String)

    external fun copyBayerWithStride(
        srcBuffer: ByteBuffer,
        srcPos: Int,
        dstBuffer: ByteBuffer,
        dstPos: Int,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int
    )

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
        metadata: CaptureMetadata? = null,
        enableMemoryColor: Boolean = false,
        colorEngineMode: Int = 0
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
        metadata: CaptureMetadata,
        enableMemoryColor: Boolean = false,
        colorEngineMode: Int = 0,
        colorMatrix1: FloatArray? = null,
        colorMatrix2: FloatArray? = null,
        forwardMatrix1: FloatArray? = null,
        forwardMatrix2: FloatArray? = null,
        calibrationIlluminant1: Int = 21,
        calibrationIlluminant2: Int = 17,
        neutralColorPoint: FloatArray? = null,
        dngCompressionMode: Int = 0
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
        metadata: CaptureMetadata,
        enableMemoryColor: Boolean = false,
        colorEngineMode: Int = 0,
        colorMatrix1: FloatArray? = null,
        colorMatrix2: FloatArray? = null,
        forwardMatrix1: FloatArray? = null,
        forwardMatrix2: FloatArray? = null,
        calibrationIlluminant1: Int = 21,
        calibrationIlluminant2: Int = 17,
        neutralColorPoint: FloatArray? = null,
        faithfulHighlights: Boolean = false,
        debugStats: LongArray? = null,
        outJpgFd: Int = -1,
        outDngFd: Int = -1,
        dngCompressionMode: Int = 0,
        rawOutputType: Int = 0,
        cfaPattern: Int = 0,
        blackLevelPattern: IntArray? = null,
        whiteLevel: Int = 0,
        dynamicBlackLevel: FloatArray? = null,
        noiseProfile: DoubleArray? = null,
        activeArray: IntArray? = null,
        lensShadingMap: FloatArray? = null,
        lensShadingRows: Int = 0,
        lensShadingCols: Int = 0
    ): Int

    fun exportHdrPlus(
        spec: top.maary.darkbag.pipeline.model.CaptureTaskSpec,
        jpgPath: String? = null,
        dngPath: String? = null,
        outJpgFd: Int = -1,
        outDngFd: Int = -1,
        debugStats: LongArray? = null
    ): Int {
        val hw = spec.hardwareProfile
        val fm = spec.frameMetadata
        val rr = spec.renderRecipe
        val edit = rr.editConfig
        return exportHdrPlus(
            tempRawPath = spec.taskId,
            width = spec.width,
            height = spec.height,
            orientation = spec.orientation,
            digitalGain = rr.digitalGain,
            targetLog = rr.targetLogIndex,
            lutPath = rr.lutPath,
            exposure = edit?.exposure ?: rr.exposure,
            contrast = edit?.contrast ?: rr.contrast,
            saturation = edit?.saturation ?: rr.saturation,
            highlights = edit?.highlights ?: rr.highlights,
            shadows = edit?.shadows ?: rr.shadows,
            whites = edit?.whites ?: rr.whites,
            blacks = edit?.blacks ?: rr.blacks,
            jpgPath = jpgPath,
            dngPath = dngPath,
            faithfulHighlights = rr.faithfulHighlights,
            ccm = fm.ccm,
            whiteBalance = fm.whiteBalance,
            zoomFactor = spec.zoomFactor,
            mirror = spec.mirror,
            metadata = fm.captureMetadata,
            enableMemoryColor = rr.enableMemoryColor,
            colorEngineMode = rr.colorEngineMode,
            colorMatrix1 = hw.colorMatrix1,
            colorMatrix2 = hw.colorMatrix2,
            forwardMatrix1 = hw.forwardMatrix1,
            forwardMatrix2 = hw.forwardMatrix2,
            calibrationIlluminant1 = hw.calibrationIlluminant1,
            calibrationIlluminant2 = hw.calibrationIlluminant2,
            neutralColorPoint = fm.neutralColorPoint,
            debugStats = debugStats,
            outJpgFd = outJpgFd,
            outDngFd = outDngFd,
            dngCompressionMode = spec.dngCompressionMode,
            rawOutputType = spec.rawOutputType,
            cfaPattern = hw.cfaPattern,
            blackLevelPattern = hw.blackLevelPattern,
            whiteLevel = hw.whiteLevel,
            dynamicBlackLevel = hw.dynamicBlackLevel,
            noiseProfile = hw.noiseProfile,
            activeArray = hw.activeArray,
            lensShadingMap = fm.lensShadingMap,
            lensShadingRows = fm.lensShadingRows,
            lensShadingCols = fm.lensShadingCols
        )
    }

    external fun processHdrPlus(
        dngBuffer: ByteBuffer,
        numFrames: Int,
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
        metadata: CaptureMetadata,
        enableMemoryColor: Boolean = false,
        colorEngineMode: Int = 0,
        colorMatrix1: FloatArray? = null,
        colorMatrix2: FloatArray? = null,
        forwardMatrix1: FloatArray? = null,
        forwardMatrix2: FloatArray? = null,
        calibrationIlluminant1: Int = 21,
        calibrationIlluminant2: Int = 17,
        neutralColorPoint: FloatArray? = null,
        dngCompressionMode: Int = 0,
        noiseProfile: DoubleArray? = null
    ): Int

    external fun nativeCreateStreamingSession(
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
        noiseProfile: DoubleArray? = null,
        fusionMode: Int = 0,
        zoomFactor: Float = 1.0f
    ): Long

    external fun nativePushStreamingFrame(
        sessionHandle: Long,
        frameBuffer: ByteBuffer
    ): Boolean

    external fun nativeComputeGcamFrameScore(
        frameBuffer: ByteBuffer,
        width: Int,
        height: Int,
        cfaPattern: Int,
        noiseProfileS: Float,
        noiseProfileO: Float,
        exposureTimeNs: Long,
        timeDeltaFromFirstNs: Long
    ): Float


    external fun nativeFinishStreamingSession(
        sessionHandle: Long,
        tempRawPath: String?,
        outputBitmap: android.graphics.Bitmap? = null,
        digitalGain: Float = 1.0f,
        targetLog: Int = 0,
        lutPath: String? = null,
        zoomFactor: Float = 1.0f,
        mirror: Boolean = false,
        enableMemoryColor: Boolean = false,
        colorEngineMode: Int = 0,
        fusionMode: Int = 0,
        debugStats: LongArray? = null
    ): Int

    external fun nativeAbortStreamingSession(
        sessionHandle: Long
    )

    external fun rcdDemosaicNative(
        bayerBuffer: ByteBuffer,
        width: Int,
        height: Int,
        cfaPattern: Int,
        blackLevelPattern: IntArray,
        whiteLevel: Int,
        whiteBalanceGains: FloatArray?,
        rgbBuffer: ByteBuffer
    )

    external fun nativeWriteRawImageDng(
        rawBuffer: ByteBuffer,
        bufferOffset: Int,
        width: Int,
        height: Int,
        rowStrideBytes: Int,
        pixelStrideBytes: Int,
        outputPath: String?,
        outFd: Int,
        orientationDegrees: Int,
        whiteLevel: Int,
        blackLevelPattern: FloatArray?,
        cfaPattern: Int,
        colorMatrix1: FloatArray?,
        colorMatrix2: FloatArray?,
        forwardMatrix1: FloatArray?,
        forwardMatrix2: FloatArray?,
        calibrationIlluminant1: Int,
        calibrationIlluminant2: Int,
        neutralColorPoint: FloatArray?,
        lensShadingMap: FloatArray?,
        lensShadingRows: Int,
        lensShadingCols: Int,
        activeArea: IntArray?,
        noiseProfile: DoubleArray?,
        iso: Int,
        exposureTimeNanos: Long,
        focalLength: Float,
        focalLength35mm: Int,
        fNumber: Float,
        dngCompressionMode: Int,
        isHdrPlus: Boolean
    ): Boolean

    fun writeRawImageToDng(
        rawImage: Image,
        chars: CameraCharacteristics,
        captureResult: CaptureResult?,
        orientationDegrees: Int,
        outputPath: String,
        dngCompressionMode: Int = 0,
        isHdrPlus: Boolean = false
    ): Boolean {
        val plane = rawImage.planes[0]
        val buffer = plane.buffer
        val width = rawImage.width
        val height = rawImage.height
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride

        val whiteLevel = chars.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023
        val cfaPattern = chars.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: 0

        val dynamicBl = captureResult?.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)
        val blPattern = if (dynamicBl != null && dynamicBl.size >= 4) {
            dynamicBl
        } else {
            val staticBl = chars.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
            if (staticBl != null) {
                floatArrayOf(
                    staticBl.getOffsetForIndex(0, 0).toFloat(),
                    staticBl.getOffsetForIndex(1, 0).toFloat(),
                    staticBl.getOffsetForIndex(0, 1).toFloat(),
                    staticBl.getOffsetForIndex(1, 1).toFloat()
                )
            } else {
                floatArrayOf(64f, 64f, 64f, 64f)
            }
        }

        val calib = SensorCalibrationHelper.extractCalibration(chars, captureResult)
        val lscTriple = SensorCalibrationHelper.extractLensShading(captureResult)

        val activeRect = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val activeArea = if (activeRect != null) intArrayOf(activeRect.top, activeRect.left, activeRect.bottom, activeRect.right) else null

        val noisePairs = captureResult?.get(CaptureResult.SENSOR_NOISE_PROFILE)
        val noiseProfile = if (noisePairs != null && noisePairs.isNotEmpty()) {
            val arr = DoubleArray(noisePairs.size * 2)
            for (i in noisePairs.indices) {
                arr[i * 2] = noisePairs[i].first
                arr[i * 2 + 1] = noisePairs[i].second
            }
            arr
        } else null

        val iso = captureResult?.get(CaptureResult.SENSOR_SENSITIVITY) ?: 100
        val exposureTime = captureResult?.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 10_000_000L
        val focalLength = captureResult?.get(CaptureResult.LENS_FOCAL_LENGTH) ?: 5.0f
        val fNumber = captureResult?.get(CaptureResult.LENS_APERTURE) ?: 1.8f
        val sensorPhysicalSize = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val focalLength35mm = if (sensorPhysicalSize != null && sensorPhysicalSize.width > 0f) {
            (focalLength * 36f / sensorPhysicalSize.width).toInt()
        } else 24

        return nativeWriteRawImageDng(
            rawBuffer = buffer,
            bufferOffset = buffer.position(),
            width = width,
            height = height,
            rowStrideBytes = rowStride,
            pixelStrideBytes = pixelStride,
            outputPath = outputPath,
            outFd = -1,
            orientationDegrees = orientationDegrees,
            whiteLevel = whiteLevel,
            blackLevelPattern = blPattern,
            cfaPattern = cfaPattern,
            colorMatrix1 = calib.colorMatrix1,
            colorMatrix2 = calib.colorMatrix2,
            forwardMatrix1 = calib.forwardMatrix1,
            forwardMatrix2 = calib.forwardMatrix2,
            calibrationIlluminant1 = calib.calibrationIlluminant1,
            calibrationIlluminant2 = calib.calibrationIlluminant2,
            neutralColorPoint = calib.neutralColorPoint,
            lensShadingMap = lscTriple.first,
            lensShadingRows = lscTriple.second,
            lensShadingCols = lscTriple.third,
            activeArea = activeArea,
            noiseProfile = noiseProfile,
            iso = iso,
            exposureTimeNanos = exposureTime,
            focalLength = focalLength,
            focalLength35mm = focalLength35mm,
            fNumber = fNumber,
            dngCompressionMode = dngCompressionMode,
            isHdrPlus = isHdrPlus
        )
    }
}
