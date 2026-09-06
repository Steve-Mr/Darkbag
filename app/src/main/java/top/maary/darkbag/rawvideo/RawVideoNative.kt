package top.maary.darkbag.rawvideo

import android.graphics.Bitmap
import java.nio.ByteBuffer

object RawVideoNative {
    init {
        System.loadLibrary("native-lib")
    }

    const val CFA_RGGB = 0
    const val CFA_GRBG = 1
    const val CFA_GBRG = 2
    const val CFA_BGGR = 3

    const val COMPRESSION_NONE = 0
    const val COMPRESSION_NEON_DPCM_LZ4 = 1

    const val DOWNSAMPLE_NONE = 0
    const val DOWNSAMPLE_CROP_4K = 1
    const val DOWNSAMPLE_BINNING_1080P = 2
    const val DOWNSAMPLE_BINNING_2K_OPEN_GATE_4_3 = 3
    const val DOWNSAMPLE_BINNING_4X4 = 4

    const val BINNING_MODE_AVERAGE = 0
    const val BINNING_MODE_SUMMATION = 1

    data class Header(
        val width: Int,
        val height: Int,
        val bitDepth: Int,
        val cfaPattern: Int,
        val fps: Float,
        val compressionType: Int,
        val audioSampleRate: Int,
        val audioChannels: Int,
        val whiteLevel: Int,
        val blackLevel: FloatArray,
        val neutralPoint: FloatArray,
        val activeLutName: String,
        val activeLogName: String,
        val frameCount: Int,
        val orientation: Int = 0,
        val calibrationIlluminant1: Int = 21,
        val calibrationIlluminant2: Int = 17,
        val baselineExposure: Float = 0.0f,
        val exposure: Float = 0.0f,
        val contrast: Float = 0.0f,
        val saturation: Float = 0.0f,
        val colorMatrix1: FloatArray = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
        val colorMatrix2: FloatArray = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
        val forwardMatrix1: FloatArray = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
        val forwardMatrix2: FloatArray = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
        val make: String = "",
        val model: String = ""
    )

    data class FrameMetadata(
        val timestampNs: Long,
        val exposureTimeNs: Long,
        val iso: Int,
        val fNumber: Float = 0.0f,
        val focalLength: Float = 0.0f
    )

    external fun nativeStartRecording(
        outputPath: String,
        width: Int,
        height: Int,
        bitDepth: Int,
        cfaPattern: Int,
        fps: Float,
        compressionType: Int,
        audioSampleRate: Int,
        audioChannels: Int,
        audioBitDepth: Int,
        whiteLevel: Int,
        blackLevel: FloatArray?,
        colorMatrix1: FloatArray?,
        colorMatrix2: FloatArray?,
        forwardMatrix1: FloatArray?,
        forwardMatrix2: FloatArray?,
        neutralPoint: FloatArray?,
        lutName: String?,
        logName: String?,
        orientation: Int = 0,
        calibrationIlluminant1: Int = 21,
        calibrationIlluminant2: Int = 17,
        baselineExposure: Float = 0.0f,
        make: String? = null,
        model: String? = null,
        downsampleMode: Int = 0
    ): Long

    external fun nativePushVideoFrame(
        handle: Long,
        bayerBuffer: ByteBuffer,
        dataSize: Int,
        width: Int,
        height: Int,
        rowStride: Int,
        timestampNs: Long,
        exposureTimeNs: Long,
        iso: Int,
        neutralColorPoint: FloatArray?,
        fNumber: Float = 0.0f,
        focalLength: Float = 0.0f
    ): Boolean

    external fun nativePushAudioPacket(
        handle: Long,
        pcmBuffer: ByteBuffer,
        pcmSize: Int,
        timestampNs: Long,
        sampleCount: Int
    ): Boolean

    external fun nativeStopRecording(handle: Long): Boolean

    external fun nativeOpenReader(filePath: String): Long
    external fun nativeOpenReaderFd(fd: Int): Long

    external fun nativeGetHeader(
        handle: Long,
        intParams: IntArray, // [width, height, bitDepth, cfaPattern, compressionType, audioSampleRate, audioChannels, whiteLevel, frameCount, orientation, calibIllum1, calibIllum2]
        floatParams: FloatArray, // [fps, bl0..3, np0..2, baselineExposure, cm1(9), cm2(9), fm1(9), fm2(9)]
        stringParams: Array<String?> // [activeLutName, activeLogName, make, model]
    ): Boolean

    external fun nativeGetFrameCount(handle: Long): Int

    external fun nativeReadFrame(
        handle: Long,
        frameIndex: Int,
        outMetadata: LongArray, // [timestampNs, exposureTimeNs, iso, fNumberBits, focalLengthBits]
        outByteBuffer: ByteBuffer
    ): Int

    external fun nativeReadAudioPacket(
        handle: Long,
        packetIndex: Int,
        outByteBuffer: ByteBuffer
    ): Int

    external fun nativeCloseReader(handle: Long)
    
    external fun nativeBayerBinning2x2(
        srcBuffer: ByteBuffer,
        srcWidth: Int,
        srcHeight: Int,
        srcRowStrideBytes: Int,
        dstBuffer: ByteBuffer,
        mode: Int = BINNING_MODE_AVERAGE
    ): Boolean

    external fun nativeBayerBinning4x4(
        srcBuffer: ByteBuffer,
        srcWidth: Int,
        srcHeight: Int,
        srcRowStrideBytes: Int,
        dstBuffer: ByteBuffer,
        mode: Int = BINNING_MODE_AVERAGE
    ): Boolean

    external fun nativeEvaluateBurst(
        megaBuffer: ByteBuffer,
        numFrames: Int,
        width: Int,
        height: Int,
        rowStride: Int,
        cfaPattern: Int,
        iso: Int = 100,
        triggerIndex: Int = 0,
        rejectionThreshold: Float = 0.45f
    ): IntArray?

    external fun nativeDebayerFrameToBitmap(
        bayerBuffer: ByteBuffer,
        width: Int,
        height: Int,
        orientation: Int = 0,
        cfaPattern: Int,
        whiteLevel: Int,
        blackLevel: Float,
        neutralPoint: FloatArray?,
        forwardMatrix1: FloatArray? = null,
        forwardMatrix2: FloatArray? = null,
        calibIllum1: Int = 21,
        calibIllum2: Int = 17,
        targetLog: Int = -1,
        lutPath: String? = null,
        exposure: Float = 0.0f,
        contrast: Float = 0.0f,
        saturation: Float = 0.0f,
        outBitmap: Bitmap
    ): Boolean

    external fun nativeCreateGLRenderer(): Long
    external fun nativeSetGLSurface(rendererHandle: Long, surface: android.view.Surface?)
    external fun nativeRenderGLFrame(
        rendererHandle: Long,
        bayerBuffer: ByteBuffer,
        width: Int,
        height: Int,
        orientation: Int = 0,
        cfaPattern: Int,
        whiteLevel: Int,
        blackLevels: FloatArray?,
        neutralPoint: FloatArray?,
        forwardMatrix1: FloatArray?,
        forwardMatrix2: FloatArray?,
        calibIllum1: Int = 21,
        calibIllum2: Int = 17,
        targetLog: Int = -1,
        lutPath: String? = null,
        exposure: Float = 0.0f,
        contrast: Float = 0.0f,
        saturation: Float = 0.0f,
        ptsNs: Long = -1L
    ): Boolean
    external fun nativeDestroyGLRenderer(rendererHandle: Long)

    external fun nativeUpdateHeaderMetadata(
        fd: Int,
        activeLog: String?,
        activeLut: String?,
        exposure: Float,
        contrast: Float,
        saturation: Float
    ): Boolean

    fun readHeader(handle: Long): Header? {
        val intParams = IntArray(12)
        val floatParams = FloatArray(48)
        val stringParams = arrayOfNulls<String>(4)

        if (!nativeGetHeader(handle, intParams, floatParams, stringParams)) {
            return null
        }

        val blackLevel = floatParams.copyOfRange(1, 5)
        val neutralPoint = floatParams.copyOfRange(5, 8)
        val baselineExposure = floatParams[8]
        val colorMatrix1 = floatParams.copyOfRange(9, 18)
        val colorMatrix2 = floatParams.copyOfRange(18, 27)
        val forwardMatrix1 = floatParams.copyOfRange(27, 36)
        val forwardMatrix2 = floatParams.copyOfRange(36, 45)
        val exposure = if (floatParams.size >= 48) floatParams[45] else 0.0f
        val contrast = if (floatParams.size >= 48) floatParams[46] else 0.0f
        val saturation = if (floatParams.size >= 48) floatParams[47] else 0.0f

        return Header(
            width = intParams[0],
            height = intParams[1],
            bitDepth = intParams[2],
            cfaPattern = intParams[3],
            fps = floatParams[0],
            compressionType = intParams[4],
            audioSampleRate = intParams[5],
            audioChannels = intParams[6],
            whiteLevel = intParams[7],
            blackLevel = blackLevel,
            neutralPoint = neutralPoint,
            activeLutName = stringParams[0] ?: "",
            activeLogName = stringParams[1] ?: "",
            frameCount = intParams[8],
            orientation = intParams[9],
            calibrationIlluminant1 = intParams[10],
            calibrationIlluminant2 = intParams[11],
            baselineExposure = baselineExposure,
            exposure = exposure,
            contrast = contrast,
            saturation = saturation,
            colorMatrix1 = colorMatrix1,
            colorMatrix2 = colorMatrix2,
            forwardMatrix1 = forwardMatrix1,
            forwardMatrix2 = forwardMatrix2,
            make = stringParams[2] ?: "",
            model = stringParams[3] ?: ""
        )
    }
}
