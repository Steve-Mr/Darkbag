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
        val pfd = context.contentResolver.openFileDescriptor(rawVideoUri, "r") ?: return@withContext null
        pfd.use { parcelFd ->
            try {
                val fd = parcelFd.fd
                nativeHandle = RawVideoNative.nativeOpenReaderFd(fd)
                if (nativeHandle == 0L) {
                    Log.e(TAG, "Failed to open native reader for CinemaDNG export (fd=$fd, uri=$rawVideoUri)")
                    return@withContext null
                }

                val header = RawVideoNative.readHeader(nativeHandle) ?: run {
                    RawVideoNative.nativeCloseReader(nativeHandle)
                    return@withContext null
                }

                val totalFrames = RawVideoNative.nativeGetFrameCount(nativeHandle)
                if (totalFrames <= 0) {
                    RawVideoNative.nativeCloseReader(nativeHandle)
                    return@withContext null
                }

                val rawSegment = rawVideoUri.lastPathSegment ?: "RAWVID_${System.currentTimeMillis()}"
                val clipName = rawSegment.substringAfterLast("/").substringAfterLast(":").substringBeforeLast(".")
                val clipDir = File(outputDir, clipName).apply { mkdirs() }

                val w = header.width
                val h = header.height
                val bayerBufferSize = w * h * 2
                val frameBuffer = ByteBuffer.allocateDirect(bayerBufferSize)

                // 1. Export DNG Frames
                for (i in 0 until totalFrames) {
                    frameBuffer.clear()
                    val meta = LongArray(5)
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
    }

    /**
     * Exports a .rawvid clip into a tone-mapped MP4 video with orientation hint and BT.709 colorimetry.
     */
    suspend fun exportToMp4(
        context: Context,
        rawVideoUri: Uri,
        outputFile: File,
        editConfig: EditConfig?,
        targetResolution: Int = 1080,
        onProgress: (current: Int, total: Int) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        var nativeHandle: Long = 0L
        val pfd = context.contentResolver.openFileDescriptor(rawVideoUri, "r") ?: return@withContext false
        pfd.use { parcelFd ->
            try {
                val fd = parcelFd.fd
                nativeHandle = RawVideoNative.nativeOpenReaderFd(fd)
                if (nativeHandle == 0L) {
                    Log.e(TAG, "Failed to open native reader for MP4 export (fd=$fd, uri=$rawVideoUri)")
                    return@withContext false
                }

                val header = RawVideoNative.readHeader(nativeHandle) ?: run {
                    RawVideoNative.nativeCloseReader(nativeHandle)
                    return@withContext false
                }

                val totalFrames = RawVideoNative.nativeGetFrameCount(nativeHandle)
                if (totalFrames <= 0) {
                    RawVideoNative.nativeCloseReader(nativeHandle)
                    return@withContext false
                }

                val rawW = header.width
                val rawH = header.height
                val fps = header.fps.takeIf { it > 0 } ?: 24.0f
                val bitRate = if (targetResolution >= 2160) 40_000_000 else 20_000_000 // 40 Mbps for 4K, 20 Mbps for 1080p

                // Downscale / fit to standard 1080p (or 4K) resolution with 16-pixel alignment for MediaCodec
                val maxDim = if (targetResolution >= 2160) 3840 else 1920
                val scale = if (maxOf(rawW, rawH) > maxDim) maxDim.toFloat() / maxOf(rawW, rawH) else 1.0f
                val exportW = (((rawW * scale).toInt() + 15) / 16) * 16
                val exportH = (((rawH * scale).toInt() + 15) / 16) * 16

                val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, exportW, exportH).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)
                    setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                    setFloat(MediaFormat.KEY_FRAME_RATE, fps)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                    setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
                    setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
                    setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
                }

                val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                encoder.start()

                val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                if (header.orientation != 0) {
                    muxer.setOrientationHint(header.orientation)
                }
                var videoTrackIndex = -1
                var muxerStarted = false

                val bayerBuf = ByteBuffer.allocateDirect(rawW * rawH * 2)
                val fullBmp = Bitmap.createBitmap(rawW, rawH, Bitmap.Config.ARGB_8888)
                val scaledBmp = if (exportW == rawW && exportH == rawH) fullBmp else Bitmap.createBitmap(exportW, exportH, Bitmap.Config.ARGB_8888)
                val canvas = if (scaledBmp !== fullBmp) android.graphics.Canvas(scaledBmp) else null
                val yuvBuf = ByteArray(exportW * exportH * 3 / 2)

                val bufferInfo = MediaCodec.BufferInfo()
                val frameIntervalUs = (1_000_000L / fps).toLong()

                val targetLogIndex = if (editConfig?.log != null && editConfig.log != "None") {
                    top.maary.darkbag.fragments.SettingsFragment.LOG_CURVES.indexOf(editConfig.log).takeIf { it >= 0 } ?: -1
                } else -1

                val lutManager = top.maary.darkbag.utils.LutManager(context)
                val lutPath = if (editConfig?.lut != null && editConfig.lut != "None" && editConfig.lut!!.isNotBlank()) {
                    val f = File(lutManager.lutDir, editConfig.lut!!)
                    if (f.exists()) f.absolutePath else {
                        val f2 = File(File(context.filesDir, "luts"), editConfig.lut!!)
                        if (f2.exists()) f2.absolutePath else null
                    }
                } else null

                val exposure = editConfig?.exposure ?: 0f
                val contrast = editConfig?.contrast ?: 0f
                val saturation = editConfig?.saturation ?: 0f

                for (i in 0 until totalFrames) {
                    bayerBuf.clear()
                    val meta = LongArray(5)
                    val readBytes = RawVideoNative.nativeReadFrame(nativeHandle, i, meta, bayerBuf)
                    if (readBytes <= 0) continue

                    // Debayer & Grade to Bitmap
                    RawVideoNative.nativeDebayerFrameToBitmap(
                        bayerBuffer = bayerBuf,
                        width = rawW,
                        height = rawH,
                        orientation = 0,
                        cfaPattern = header.cfaPattern,
                        whiteLevel = header.whiteLevel,
                        blackLevel = header.blackLevel.firstOrNull() ?: 64f,
                        neutralPoint = header.neutralPoint,
                        targetLog = targetLogIndex,
                        lutPath = lutPath,
                        exposure = exposure,
                        contrast = contrast,
                        saturation = saturation,
                        outBitmap = fullBmp
                    )

                    if (scaledBmp !== fullBmp && canvas != null) {
                        canvas.drawBitmap(fullBmp, android.graphics.Rect(0, 0, rawW, rawH), android.graphics.Rect(0, 0, exportW, exportH), null)
                    }

                    // Convert ARGB to NV12/YUV420
                    bitmapToNv12(scaledBmp, yuvBuf, exportW, exportH)

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
    }

    internal fun writeDngFile(
        file: File,
        header: RawVideoNative.Header,
        meta: LongArray,
        bayerData: ByteBuffer,
        payloadSize: Int
    ) {
        val w = header.width
        val h = header.height

        val cfaPatternBytes = when (header.cfaPattern) {
            RawVideoNative.CFA_RGGB -> byteArrayOf(0, 1, 1, 2)
            RawVideoNative.CFA_GRBG -> byteArrayOf(1, 0, 2, 1)
            RawVideoNative.CFA_GBRG -> byteArrayOf(1, 2, 0, 1)
            RawVideoNative.CFA_BGGR -> byteArrayOf(2, 1, 1, 0)
            else -> byteArrayOf(0, 1, 1, 2)
        }

        val orientationVal = when (header.orientation) {
            90 -> 6
            180 -> 3
            270 -> 8
            else -> 1
        }

        val makeStr = (if (header.make.isNotBlank()) header.make else "Darkbag") + "\u0000"
        val modelStr = (if (header.model.isNotBlank()) header.model else "Darkbag Camera") + "\u0000"
        val ucmStr = "${header.make} ${header.model}".trim().ifBlank { "Darkbag RAW Video" } + "\u0000"

        val makeBytes = makeStr.toByteArray(Charsets.US_ASCII)
        val modelBytes = modelStr.toByteArray(Charsets.US_ASCII)
        val ucmBytes = ucmStr.toByteArray(Charsets.US_ASCII)

        val ifdOffset = 8
        val entryCount = 29
        val ifdSize = 2 + entryCount * 12 + 4 // 354 bytes
        var curOffset = ((ifdOffset + ifdSize + 3) / 4) * 4 // 364 bytes

        val makeOffset = curOffset
        curOffset += makeBytes.size
        val modelOffset = curOffset
        curOffset += modelBytes.size
        val ucmOffset = curOffset
        curOffset += ucmBytes.size

        curOffset = ((curOffset + 3) / 4) * 4
        val exposureTimeOffset = curOffset
        curOffset += 8

        val blackLevelOffset = curOffset
        curOffset += 32 // 4 RATIONALs (4 * 8 = 32)

        val colorMatrix1Offset = curOffset
        curOffset += 72 // 9 SRATIONALs (9 * 8 = 72)

        val baselineExposureOffset = curOffset
        curOffset += 8 // 1 SRATIONAL (8 bytes)

        val asShotNeutralOffset = curOffset
        curOffset += 24 // 3 RATIONALs (3 * 8 = 24)

        val frameRateOffset = curOffset
        curOffset += 8 // 1 RATIONAL (8 bytes)

        val stripOffset = ((curOffset + 15) / 16) * 16 // 16-byte aligned

        val buf = ByteBuffer.allocate(stripOffset).order(ByteOrder.LITTLE_ENDIAN)

        // 1. TIFF Header
        buf.putShort(0x4949.toShort()) // 'II'
        buf.putShort(42.toShort())
        buf.putInt(ifdOffset)

        // 2. IFD0 Entries (Sorted ascending by Tag ID)
        buf.putShort(entryCount.toShort())

        fun putEntry(tag: Int, type: Int, count: Int, valueOrOffset: Int) {
            buf.putShort(tag.toShort())
            buf.putShort(type.toShort())
            buf.putInt(count)
            buf.putInt(valueOrOffset)
        }

        // Tag 0x00FE: NewSubfileType (LONG = 0 Full-resolution primary image)
        putEntry(0x00FE, 4, 1, 0)
        // Tag 0x0100: ImageWidth (LONG)
        putEntry(0x0100, 4, 1, w)
        // Tag 0x0101: ImageLength (LONG)
        putEntry(0x0101, 4, 1, h)
        // Tag 0x0102: BitsPerSample (SHORT = 16)
        putEntry(0x0102, 3, 1, 16)
        // Tag 0x0103: Compression (SHORT = 1 Uncompressed)
        putEntry(0x0103, 3, 1, 1)
        // Tag 0x0106: PhotometricInterpretation (SHORT = 32803 CFA)
        putEntry(0x0106, 3, 1, 32803)
        // Tag 0x010F: Make (ASCII)
        putEntry(0x010F, 2, makeBytes.size, makeOffset)
        // Tag 0x0110: Model (ASCII)
        putEntry(0x0110, 2, modelBytes.size, modelOffset)
        // Tag 0x0111: StripOffsets (LONG)
        putEntry(0x0111, 4, 1, stripOffset)
        // Tag 0x0112: Orientation (SHORT)
        putEntry(0x0112, 3, 1, orientationVal)
        // Tag 0x0115: SamplesPerPixel (SHORT = 1)
        putEntry(0x0115, 3, 1, 1)
        // Tag 0x0116: RowsPerStrip (LONG = h)
        putEntry(0x0116, 4, 1, h)
        // Tag 0x0117: StripByteCounts (LONG = payloadSize)
        putEntry(0x0117, 4, 1, payloadSize)
        // Tag 0x011C: PlanarConfiguration (SHORT = 1)
        putEntry(0x011C, 3, 1, 1)
        // Tag 0x828D: CFARepeatPatternDim (SHORT[2] = [2, 2])
        putEntry(0x828D, 3, 2, (2 shl 16) or 2)
        // Tag 0x828E: CFAPattern (BYTE[4])
        val cfaVal = ((cfaPatternBytes[3].toInt() and 0xFF) shl 24) or
                     ((cfaPatternBytes[2].toInt() and 0xFF) shl 16) or
                     ((cfaPatternBytes[1].toInt() and 0xFF) shl 8) or
                     (cfaPatternBytes[0].toInt() and 0xFF)
        putEntry(0x828E, 1, 4, cfaVal)
        // Tag 0x829A: ExposureTime (RATIONAL)
        putEntry(0x829A, 5, 1, exposureTimeOffset)
        // Tag 0x8827: ISOSpeedRatings (SHORT)
        val isoVal = if (meta.size > 2 && meta[2] > 0) meta[2].toInt().coerceIn(1, 65535) else 100
        putEntry(0x8827, 3, 1, isoVal)
        // Tag 0xC612: DNGVersion (BYTE[4] = 1.4.0.0)
        putEntry(0xC612, 1, 4, 0x00000401)
        // Tag 0xC613: DNGBackwardVersion (BYTE[4] = 1.1.0.0)
        putEntry(0xC613, 1, 4, 0x00000101)
        // Tag 0xC614: UniqueCameraModel (ASCII)
        putEntry(0xC614, 2, ucmBytes.size, ucmOffset)
        // Tag 0xC619: BlackLevelRepeatDim (SHORT[2] = [2, 2])
        putEntry(0xC619, 3, 2, (2 shl 16) or 2)
        // Tag 0xC61A: BlackLevel (RATIONAL[4])
        putEntry(0xC61A, 5, 4, blackLevelOffset)
        // Tag 0xC61D: WhiteLevel (LONG)
        putEntry(0xC61D, 4, 1, header.whiteLevel)
        // Tag 0xC621: ColorMatrix1 (SRATIONAL[9])
        putEntry(0xC621, 10, 9, colorMatrix1Offset)
        // Tag 0xC62A: BaselineExposure (SRATIONAL)
        putEntry(0xC62A, 10, 1, baselineExposureOffset)
        // Tag 0xC634: AsShotNeutral (RATIONAL[3])
        putEntry(0xC634, 5, 3, asShotNeutralOffset)
        // Tag 0xC65A: CalibrationIlluminant1 (SHORT)
        putEntry(0xC65A, 3, 1, header.calibrationIlluminant1.takeIf { it > 0 } ?: 21)
        // Tag 0xC764: FrameRate (RATIONAL)
        putEntry(0xC764, 5, 1, frameRateOffset)

        // Next IFD = 0
        buf.putInt(0)

        // Write Make, Model, UniqueCameraModel strings
        buf.position(makeOffset)
        buf.put(makeBytes)
        buf.position(modelOffset)
        buf.put(modelBytes)
        buf.position(ucmOffset)
        buf.put(ucmBytes)

        // Write ExposureTime (RATIONAL = numerator/denominator)
        buf.position(exposureTimeOffset)
        val expNs = if (meta.size > 1 && meta[1] > 0) meta[1] else (1_000_000_000L / (header.fps.takeIf { it > 0 } ?: 24f)).toLong()
        buf.putInt((expNs / 1000).toInt())
        buf.putInt(1_000_000)

        // Write BlackLevel (4 RATIONALs)
        buf.position(blackLevelOffset)
        for (i in 0 until 4) {
            val bl = header.blackLevel.getOrElse(i) { 64.0f }
            buf.putInt((bl * 100).toInt())
            buf.putInt(100)
        }

        // Write ColorMatrix1 (9 SRATIONALs)
        buf.position(colorMatrix1Offset)
        for (i in 0 until 9) {
            val cm = header.colorMatrix1.getOrElse(i) { if (i % 4 == 0) 1.0f else 0.0f }
            buf.putInt((cm * 10000).toInt())
            buf.putInt(10000)
        }

        // Write BaselineExposure (1 SRATIONAL)
        buf.position(baselineExposureOffset)
        buf.putInt((header.baselineExposure * 100).toInt())
        buf.putInt(100)

        // Write AsShotNeutral (3 RATIONALs)
        buf.position(asShotNeutralOffset)
        for (i in 0 until 3) {
            val np = header.neutralPoint.getOrElse(i) { 1.0f }
            buf.putInt((np * 10000).toInt())
            buf.putInt(10000)
        }

        // Write FrameRate (1 RATIONAL)
        buf.position(frameRateOffset)
        val fpsVal = header.fps.takeIf { it > 0 } ?: 24.0f
        buf.putInt((fpsVal * 1000).toInt())
        buf.putInt(1000)

        file.parentFile?.mkdirs()
        FileOutputStream(file).use { fos ->
            fos.write(buf.array(), 0, stripOffset)
            bayerData.position(0)
            bayerData.limit(payloadSize)
            fos.channel.write(bayerData)
        }
    }

    private fun writeWavFile(
        handle: Long,
        wavFile: File,
        header: RawVideoNative.Header
    ) {
        wavFile.parentFile?.mkdirs()
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
