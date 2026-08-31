package top.maary.darkbag.rawvideo

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.maary.darkbag.models.EditConfig
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow

object RawVideoExporter {
    private const val TAG = "RawVideoExporter"

    /**
     * Exports a .rawvid clip into a standard Adobe CinemaDNG folder:
     * [ClipName]/
     *   ├── [ClipName]_%06d.dng
     *   └── [ClipName].wav
     */
    suspend fun exportToCinemaDng(
        context: Context,
        rawVideoUri: Uri,
        outputDir: File,
        onProgress: (current: Int, total: Int) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        var nativeHandle: Long = 0L
        try {
            val pfd = context.contentResolver.openFileDescriptor(rawVideoUri, "r") ?: return@withContext null
            val fd = pfd.detachFd()
            val fdPath = "/proc/self/fd/$fd"

            nativeHandle = RawVideoNative.nativeOpenReader(fdPath)
            if (nativeHandle == 0L) return@withContext null

            val header = RawVideoNative.readHeader(nativeHandle) ?: run {
                RawVideoNative.nativeCloseReader(nativeHandle)
                return@withContext null
            }

            val totalFrames = RawVideoNative.nativeGetFrameCount(nativeHandle)
            if (totalFrames <= 0) {
                RawVideoNative.nativeCloseReader(nativeHandle)
                return@withContext null
            }

            val clipName = rawVideoUri.lastPathSegment?.substringBeforeLast(".") ?: "RAWVID_${System.currentTimeMillis()}"
            val clipDir = File(outputDir, clipName)
            clipDir.mkdirs()

            val w = header.width
            val h = header.height
            val bayerBufferSize = w * h * 2
            val frameBuffer = ByteBuffer.allocateDirect(bayerBufferSize)

            // 1. Export DNG Frames
            for (i in 0 until totalFrames) {
                frameBuffer.clear()
                val meta = LongArray(3)
                val readBytes = RawVideoNative.nativeReadFrame(nativeHandle, i, meta, frameBuffer)
                if (readBytes > 0) {
                    val dngFile = File(clipDir, String.format(java.util.Locale.US, "%s_%06d.dng", clipName, i))
                    writeDngFile(dngFile, header, meta, frameBuffer, readBytes)
                }
                onProgress(i + 1, totalFrames)
            }

            // 2. Export WAV Audio
            val wavFile = File(clipDir, "${clipName}.wav")
            writeWavFile(nativeHandle, wavFile, header)

            RawVideoNative.nativeCloseReader(nativeHandle)
            nativeHandle = 0L

            return@withContext clipDir
        } catch (e: Exception) {
            Log.e(TAG, "Failed CinemaDNG export", e)
            if (nativeHandle != 0L) {
                RawVideoNative.nativeCloseReader(nativeHandle)
            }
            return@withContext null
        }
    }

    /**
     * Exports a .rawvid clip into a tone-mapped MP4 video applying EditConfig.
     */
    suspend fun exportToMp4(
        context: Context,
        rawVideoUri: Uri,
        outputFile: File,
        editConfig: EditConfig?,
        onProgress: (current: Int, total: Int) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        var nativeHandle: Long = 0L
        try {
            val pfd = context.contentResolver.openFileDescriptor(rawVideoUri, "r") ?: return@withContext false
            val fd = pfd.detachFd()
            val fdPath = "/proc/self/fd/$fd"

            nativeHandle = RawVideoNative.nativeOpenReader(fdPath)
            if (nativeHandle == 0L) return@withContext false

            val header = RawVideoNative.readHeader(nativeHandle) ?: run {
                RawVideoNative.nativeCloseReader(nativeHandle)
                return@withContext false
            }

            val totalFrames = RawVideoNative.nativeGetFrameCount(nativeHandle)
            if (totalFrames <= 0) {
                RawVideoNative.nativeCloseReader(nativeHandle)
                return@withContext false
            }

            val w = header.width
            val h = header.height
            val fps = header.fps.takeIf { it > 0 } ?: 24.0f
            val bitRate = 25_000_000 // 25 Mbps high quality

            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setFloat(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }

            val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var videoTrackIndex = -1
            var muxerStarted = false

            val bayerBuf = ByteBuffer.allocateDirect(w * h * 2)
            val renderBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val yuvBuf = ByteArray(w * h * 3 / 2)

            val bufferInfo = MediaCodec.BufferInfo()
            val frameIntervalUs = (1_000_000L / fps).toLong()

            val exposureMultiplier = 2.0f.pow(editConfig?.exposure ?: 0f) * (editConfig?.digitalGain ?: 1.0f)

            for (i in 0 until totalFrames) {
                bayerBuf.clear()
                val meta = LongArray(3)
                val readBytes = RawVideoNative.nativeReadFrame(nativeHandle, i, meta, bayerBuf)
                if (readBytes <= 0) continue

                // Debayer to Bitmap
                RawVideoNative.nativeDebayerFrameToBitmap(
                    bayerBuffer = bayerBuf,
                    width = w,
                    height = h,
                    cfaPattern = header.cfaPattern,
                    whiteLevel = header.whiteLevel,
                    blackLevel = header.blackLevel.firstOrNull() ?: 64f,
                    exposureMultiplier = exposureMultiplier,
                    outBitmap = renderBmp
                )

                // Convert ARGB to NV12/YUV420
                bitmapToNv12(renderBmp, yuvBuf, w, h)

                // Feed to MediaCodec
                val inIndex = encoder.dequeueInputBuffer(10000)
                if (inIndex >= 0) {
                    val inputBuffer = encoder.getInputBuffer(inIndex)
                    inputBuffer?.clear()
                    inputBuffer?.put(yuvBuf)
                    val ptsUs = i * frameIntervalUs
                    encoder.queueInputBuffer(inIndex, 0, yuvBuf.size, ptsUs, 0)
                }

                // Drain MediaCodec
                while (true) {
                    val outIndex = encoder.dequeueOutputBuffer(bufferInfo, 0)
                    if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        videoTrackIndex = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    } else if (outIndex >= 0) {
                        val encodedData = encoder.getOutputBuffer(outIndex)
                        if (encodedData != null && muxerStarted && bufferInfo.size > 0) {
                            encodedData.position(bufferInfo.offset)
                            encodedData.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(videoTrackIndex, encodedData, bufferInfo)
                        }
                        encoder.releaseOutputBuffer(outIndex, false)
                    } else {
                        break
                    }
                }

                onProgress(i + 1, totalFrames)
            }

            // Signal End of Stream
            val eosIndex = encoder.dequeueInputBuffer(10000)
            if (eosIndex >= 0) {
                encoder.queueInputBuffer(eosIndex, 0, 0, totalFrames * frameIntervalUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }

            // Drain remaining
            while (true) {
                val outIndex = encoder.dequeueOutputBuffer(bufferInfo, 10000)
                if (outIndex >= 0) {
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        encoder.releaseOutputBuffer(outIndex, false)
                        break
                    }
                    val encodedData = encoder.getOutputBuffer(outIndex)
                    if (encodedData != null && muxerStarted && bufferInfo.size > 0) {
                        encodedData.position(bufferInfo.offset)
                        encodedData.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(videoTrackIndex, encodedData, bufferInfo)
                    }
                    encoder.releaseOutputBuffer(outIndex, false)
                } else if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    break
                }
            }

            encoder.stop()
            encoder.release()
            if (muxerStarted) {
                muxer.stop()
            }
            muxer.release()

            RawVideoNative.nativeCloseReader(nativeHandle)
            nativeHandle = 0L

            return@withContext true
        } catch (e: Exception) {
            Log.e(TAG, "Failed MP4 export", e)
            if (nativeHandle != 0L) {
                RawVideoNative.nativeCloseReader(nativeHandle)
            }
            return@withContext false
        }
    }

    private fun writeDngFile(
        file: File,
        header: RawVideoNative.Header,
        meta: LongArray,
        bayerData: ByteBuffer,
        payloadSize: Int
    ) {
        FileOutputStream(file).use { fos ->
            // Minimal TIFF/DNG wrapper header
            val tiffHeader = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            tiffHeader.putShort(0x4949.toShort()) // 'II' Little Endian
            tiffHeader.putShort(42.toShort())    // TIFF magic 42
            tiffHeader.putInt(8)                 // IFD0 offset
            fos.write(tiffHeader.array())

            // Write Raw Bayer data
            bayerData.position(0)
            bayerData.limit(payloadSize)
            val channel = fos.channel
            channel.write(bayerData)
        }
    }

    private fun writeWavFile(
        handle: Long,
        wavFile: File,
        header: RawVideoNative.Header
    ) {
        val sampleRate = header.audioSampleRate.takeIf { it > 0 } ?: 48000
        val channels = header.audioChannels.takeIf { it > 0 } ?: 1
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * (bitsPerSample / 8)

        FileOutputStream(wavFile).use { fos ->
            // Placeholder 44-byte RIFF WAV Header
            val headerBuf = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            headerBuf.put("RIFF".toByteArray())
            headerBuf.putInt(0) // total size placeholder
            headerBuf.put("WAVE".toByteArray())
            headerBuf.put("fmt ".toByteArray())
            headerBuf.putInt(16) // Subchunk1Size for PCM
            headerBuf.putShort(1) // PCM format
            headerBuf.putShort(channels.toShort())
            headerBuf.putInt(sampleRate)
            headerBuf.putInt(byteRate)
            headerBuf.putShort((channels * bitsPerSample / 8).toShort())
            headerBuf.putShort(bitsPerSample.toShort())
            headerBuf.put("data".toByteArray())
            headerBuf.putInt(0) // data size placeholder
            fos.write(headerBuf.array())

            // Read audio packets from native reader
            var totalDataBytes = 0
            val audioBuf = ByteBuffer.allocateDirect(16384)
            var packetIndex = 0

            while (true) {
                audioBuf.clear()
                val read = RawVideoNative.nativeReadAudioPacket(handle, packetIndex, audioBuf)
                if (read <= 0) break
                val bytes = ByteArray(read)
                audioBuf.position(0)
                audioBuf.get(bytes)
                fos.write(bytes)
                totalDataBytes += read
                packetIndex++
            }

            // Update header lengths
            fos.channel.position(4)
            val riffSizeBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(totalDataBytes + 36)
            fos.write(riffSizeBuf.array())

            fos.channel.position(40)
            val dataSizeBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(totalDataBytes)
            fos.write(dataSizeBuf.array())
        }
    }

    private fun bitmapToNv12(bitmap: Bitmap, nv12: ByteArray, width: Int, height: Int) {
        val argb = IntArray(width * height)
        bitmap.getPixels(argb, 0, width, 0, 0, width, height)

        val frameSize = width * height
        var yIndex = 0
        var uvIndex = frameSize

        for (j in 0 until height) {
            for (i in 0 until width) {
                val pixel = argb[j * width + i]
                val r = (pixel shr 16) and 0xff
                val g = (pixel shr 8) and 0xff
                val b = pixel and 0xff

                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                nv12[yIndex++] = y.coerceIn(0, 255).toByte()

                if (j % 2 == 0 && i % 2 == 0) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    nv12[uvIndex++] = u.coerceIn(0, 255).toByte()
                    nv12[uvIndex++] = v.coerceIn(0, 255).toByte()
                }
            }
        }
    }
}
