package top.maary.darkbag.rawvideo

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object CinemaDngWriter {

    fun writeDngFile(
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

        val safeLog = header.activeLogName.ifBlank { "None" }.replace("\"", "\\\"")
        val safeLut = header.activeLutName.ifBlank { "None" }.replace("\"", "\\\"")
        val editConfigJson =
            "{\"log\":\"$safeLog\",\"lut\":\"$safeLut\",\"exposure\":${header.exposure},\"contrast\":${header.contrast},\"saturation\":${header.saturation}}"
        val userCommentHeader = "ASCII\u0000\u0000\u0000".toByteArray(Charsets.US_ASCII)
        val userCommentBytes = userCommentHeader + editConfigJson.toByteArray(Charsets.US_ASCII)

        val ifdOffset = 8
        val entryCount = 37
        val ifdSize = 2 + entryCount * 12 + 4 // 450 bytes
        var curOffset = ((ifdOffset + ifdSize + 3) / 4) * 4 // 460 bytes

        val exifIfdOffset = curOffset
        val exifEntryCount = 5
        val exifIfdSize = 2 + exifEntryCount * 12 + 4 // 66 bytes
        curOffset = ((curOffset + exifIfdSize + 3) / 4) * 4 // 528 bytes

        val makeOffset = curOffset
        curOffset += makeBytes.size
        val modelOffset = curOffset
        curOffset += modelBytes.size
        val ucmOffset = curOffset
        curOffset += ucmBytes.size
        val userCommentOffset = curOffset
        curOffset += userCommentBytes.size

        curOffset = ((curOffset + 3) / 4) * 4
        val exposureTimeOffset = curOffset
        curOffset += 8

        val fNumberOffset = curOffset
        curOffset += 8

        val focalLengthOffset = curOffset
        curOffset += 8

        val blackLevelOffset = curOffset
        curOffset += 32 // 4 RATIONALs (4 * 8 = 32)

        val colorMatrix1Offset = curOffset
        curOffset += 72 // 9 SRATIONALs (9 * 8 = 72)

        val colorMatrix2Offset = curOffset
        curOffset += 72 // 9 SRATIONALs (9 * 8 = 72)

        val forwardMatrix1Offset = curOffset
        curOffset += 72 // 9 SRATIONALs (9 * 8 = 72)

        val forwardMatrix2Offset = curOffset
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

        putEntry(0x00FE, 4, 1, 0) // NewSubfileType
        putEntry(0x0100, 4, 1, w) // ImageWidth
        putEntry(0x0101, 4, 1, h) // ImageLength
        putEntry(0x0102, 3, 1, 16) // BitsPerSample
        putEntry(0x0103, 3, 1, 1) // Compression (Uncompressed)
        putEntry(0x0106, 3, 1, 32803) // PhotometricInterpretation (CFA)
        putEntry(0x010F, 2, makeBytes.size, makeOffset)
        putEntry(0x0110, 2, modelBytes.size, modelOffset)
        putEntry(0x0111, 4, 1, stripOffset)
        putEntry(0x0112, 3, 1, orientationVal)
        putEntry(0x0115, 3, 1, 1) // SamplesPerPixel
        putEntry(0x0116, 4, 1, h) // RowsPerStrip
        putEntry(0x0117, 4, 1, payloadSize) // StripByteCounts
        putEntry(0x011C, 3, 1, 1) // PlanarConfiguration
        putEntry(0x828D, 3, 2, (2 shl 16) or 2) // CFARepeatPatternDim
        val cfaVal = ((cfaPatternBytes[3].toInt() and 0xFF) shl 24) or
            ((cfaPatternBytes[2].toInt() and 0xFF) shl 16) or
            ((cfaPatternBytes[1].toInt() and 0xFF) shl 8) or
            (cfaPatternBytes[0].toInt() and 0xFF)
        putEntry(0x828E, 1, 4, cfaVal) // CFAPattern
        putEntry(0x829A, 5, 1, exposureTimeOffset)
        putEntry(0x829D, 5, 1, fNumberOffset)
        putEntry(0x8769, 4, 1, exifIfdOffset)
        val isoVal = if (meta.size > 2 && meta[2] > 0) meta[2].toInt().coerceIn(1, 65535) else 100
        putEntry(0x8827, 3, 1, isoVal)
        putEntry(0x920A, 5, 1, focalLengthOffset)
        putEntry(0x9286, 7, userCommentBytes.size, userCommentOffset)
        putEntry(0xC612, 1, 4, 0x00000401) // DNGVersion 1.4.0.0
        putEntry(0xC613, 1, 4, 0x00000101) // DNGBackwardVersion 1.1.0.0
        putEntry(0xC614, 2, ucmBytes.size, ucmOffset)
        putEntry(0xC619, 3, 2, (2 shl 16) or 2) // BlackLevelRepeatDim
        putEntry(0xC61A, 5, 4, blackLevelOffset)
        putEntry(0xC61D, 4, 1, header.whiteLevel)
        putEntry(0xC621, 10, 9, colorMatrix1Offset)
        putEntry(0xC622, 10, 9, colorMatrix2Offset)
        putEntry(0xC62A, 10, 1, baselineExposureOffset)
        putEntry(0xC634, 5, 3, asShotNeutralOffset)
        putEntry(0xC65A, 3, 1, header.calibrationIlluminant1.takeIf { it > 0 } ?: 21)
        putEntry(0xC65B, 3, 1, header.calibrationIlluminant2.takeIf { it > 0 } ?: 17)
        putEntry(0xC714, 10, 9, forwardMatrix1Offset)
        putEntry(0xC715, 10, 9, forwardMatrix2Offset)
        putEntry(0xC764, 5, 1, frameRateOffset)
        buf.putInt(0) // Next IFD = 0

        // 3. Exif Sub-IFD Entries (Sorted ascending by Tag ID)
        buf.position(exifIfdOffset)
        buf.putShort(exifEntryCount.toShort())
        putEntry(0x829A, 5, 1, exposureTimeOffset)
        putEntry(0x829D, 5, 1, fNumberOffset)
        putEntry(0x8827, 3, 1, isoVal)
        putEntry(0x920A, 5, 1, focalLengthOffset)
        putEntry(0x9286, 7, userCommentBytes.size, userCommentOffset)
        buf.putInt(0) // Next IFD = 0

        buf.position(makeOffset)
        buf.put(makeBytes)
        buf.position(modelOffset)
        buf.put(modelBytes)
        buf.position(ucmOffset)
        buf.put(ucmBytes)
        buf.position(userCommentOffset)
        buf.put(userCommentBytes)

        buf.position(exposureTimeOffset)
        val expNs = if (meta.size > 1 && meta[1] > 0) meta[1] else (1_000_000_000L / (header.fps.takeIf { it > 0 } ?: 24f)).toLong()
        buf.putInt((expNs / 1000).toInt())
        buf.putInt(1_000_000)

        buf.position(fNumberOffset)
        val fnVal = if (meta.size > 3 && meta[3] != 0L) java.lang.Float.intBitsToFloat(meta[3].toInt()) else 0.0f
        val fNumber = if (fnVal > 0f) fnVal else 1.8f
        buf.putInt((fNumber * 100).toInt())
        buf.putInt(100)

        buf.position(focalLengthOffset)
        val flVal = if (meta.size > 4 && meta[4] != 0L) java.lang.Float.intBitsToFloat(meta[4].toInt()) else 0.0f
        val focalLength = if (flVal > 0f) flVal else 5.0f
        buf.putInt((focalLength * 100).toInt())
        buf.putInt(100)

        buf.position(blackLevelOffset)
        for (i in 0 until 4) {
            val bl = header.blackLevel.getOrElse(i) { 64.0f }
            buf.putInt((bl * 100).toInt())
            buf.putInt(100)
        }

        writeMatrixSrational(buf, colorMatrix1Offset, header.colorMatrix1)
        writeMatrixSrational(buf, colorMatrix2Offset, header.colorMatrix2)
        writeMatrixSrational(buf, forwardMatrix1Offset, header.forwardMatrix1)
        writeMatrixSrational(buf, forwardMatrix2Offset, header.forwardMatrix2)

        buf.position(baselineExposureOffset)
        buf.putInt((header.baselineExposure * 100).toInt())
        buf.putInt(100)

        buf.position(asShotNeutralOffset)
        for (i in 0 until 3) {
            val np = header.neutralPoint.getOrElse(i) { 1.0f }
            buf.putInt((np * 10000).toInt())
            buf.putInt(10000)
        }

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

    private fun writeMatrixSrational(buf: ByteBuffer, offset: Int, matrix: FloatArray) {
        buf.position(offset)
        for (i in 0 until 9) {
            val v = matrix.getOrElse(i) { if (i % 4 == 0) 1.0f else 0.0f }
            buf.putInt((v * 10000).toInt())
            buf.putInt(10000)
        }
    }

    fun writeWavFile(
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
            val headerBuf = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            headerBuf.put("RIFF".toByteArray())
            headerBuf.putInt(0)
            headerBuf.put("WAVE".toByteArray())
            headerBuf.put("fmt ".toByteArray())
            headerBuf.putInt(16)
            headerBuf.putShort(1)
            headerBuf.putShort(channels.toShort())
            headerBuf.putInt(sampleRate)
            headerBuf.putInt(byteRate)
            headerBuf.putShort((channels * bitsPerSample / 8).toShort())
            headerBuf.putShort(bitsPerSample.toShort())
            headerBuf.put("data".toByteArray())
            headerBuf.putInt(0)
            fos.write(headerBuf.array())

            var totalDataBytes = 0
            val audioBuf = ByteBuffer.allocateDirect(65536)
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

            fos.channel.position(4)
            val riffSizeBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(totalDataBytes + 36)
            fos.write(riffSizeBuf.array())

            fos.channel.position(40)
            val dataSizeBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(totalDataBytes)
            fos.write(dataSizeBuf.array())
        }
    }
}
