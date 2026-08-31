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
        val frameCount: Int
    )

    data class FrameMetadata(
        val timestampNs: Long,
        val exposureTimeNs: Long,
        val iso: Int
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
        neutralPoint: FloatArray?,
        lutName: String?,
        logName: String?
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
        neutralColorPoint: FloatArray?
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

    external fun nativeGetHeader(
        handle: Long,
        intParams: IntArray, // [width, height, bitDepth, cfaPattern, compressionType, audioSampleRate, audioChannels, whiteLevel, frameCount]
        floatParams: FloatArray, // [fps, bl0, bl1, bl2, bl3, np0, np1, np2]
        stringParams: Array<String?> // [activeLutName, activeLogName]
    ): Boolean

    external fun nativeGetFrameCount(handle: Long): Int

    external fun nativeReadFrame(
        handle: Long,
        frameIndex: Int,
        outMetadata: LongArray, // [timestampNs, exposureTimeNs, iso]
        outByteBuffer: ByteBuffer
    ): Int

    external fun nativeCloseReader(handle: Long)

    external fun nativeDebayerFrameToBitmap(
        bayerBuffer: ByteBuffer,
        width: Int,
        height: Int,
        cfaPattern: Int,
        whiteLevel: Int,
        blackLevel: Float,
        exposureMultiplier: Float,
        outBitmap: Bitmap
    ): Boolean

    fun readHeader(handle: Long): Header? {
        val intParams = IntArray(9)
        val floatParams = FloatArray(8)
        val stringParams = arrayOfNulls<String>(2)

        if (!nativeGetHeader(handle, intParams, floatParams, stringParams)) {
            return null
        }

        val blackLevel = floatParams.copyOfRange(1, 5)
        val neutralPoint = floatParams.copyOfRange(5, 8)

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
            frameCount = intParams[8]
        )
    }
}
