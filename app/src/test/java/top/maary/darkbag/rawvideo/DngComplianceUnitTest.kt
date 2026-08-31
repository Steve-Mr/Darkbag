package top.maary.darkbag.rawvideo

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DngComplianceUnitTest {

    @Test
    fun testDngSpecificationCompliance() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "dng_test_${System.currentTimeMillis()}").apply { mkdirs() }
        val dngFile = File(tempDir, "test_frame.dng")

        val width = 4096
        val height = 3072
        val payloadSize = width * height * 2
        val fakeBayer = ByteBuffer.allocateDirect(payloadSize)
        // Fill fake bayer data
        for (i in 0 until payloadSize step 2) {
            fakeBayer.putShort((i % 1024).toShort())
        }
        fakeBayer.flip()

        val header = RawVideoNative.Header(
            width = width,
            height = height,
            bitDepth = 10,
            cfaPattern = RawVideoNative.CFA_BGGR,
            fps = 24.0f,
            compressionType = RawVideoNative.COMPRESSION_NONE,
            audioSampleRate = 48000,
            audioChannels = 1,
            whiteLevel = 1023,
            blackLevel = floatArrayOf(64f, 64f, 64f, 64f),
            neutralPoint = floatArrayOf(0.4723f, 1.0f, 0.6505f),
            activeLutName = "",
            activeLogName = "",
            frameCount = 1,
            orientation = 90,
            calibrationIlluminant1 = 21,
            calibrationIlluminant2 = 17,
            baselineExposure = 0.0f,
            colorMatrix1 = floatArrayOf(0.6667f, -0.1588f, -0.0857f, -0.5739f, 1.3897f, 0.143f, -0.1378f, 0.2651f, 0.6036f),
            make = "motorola",
            model = "XT2409-5"
        )

        val meta = longArrayOf(System.nanoTime(), 10_000_000L, 325L, 0L, 0L)

        RawVideoExporter.writeDngFile(
            file = dngFile,
            header = header,
            meta = meta,
            bayerData = fakeBayer,
            payloadSize = payloadSize
        )

        assertTrue(dngFile.exists())
        assertTrue(dngFile.length() > payloadSize)

        // Read and parse TIFF structure
        val fileBytes = dngFile.readBytes()
        val buf = ByteBuffer.wrap(fileBytes).order(ByteOrder.LITTLE_ENDIAN)

        // 1. TIFF Header
        val magic = buf.short.toInt() and 0xFFFF
        assertEquals(0x4949, magic) // 'II'
        val version = buf.short.toInt() and 0xFFFF
        assertEquals(42, version)
        val ifd0Offset = buf.int
        assertEquals(8, ifd0Offset)

        // 2. IFD0 Entries
        buf.position(ifd0Offset)
        val entryCount = buf.short.toInt() and 0xFFFF
        assertEquals(29, entryCount)

        data class IFDEntry(val tag: Int, val type: Int, val count: Int, val valueOrOffset: Int)
        val entries = mutableListOf<IFDEntry>()

        for (i in 0 until entryCount) {
            val tag = buf.short.toInt() and 0xFFFF
            val type = buf.short.toInt() and 0xFFFF
            val count = buf.int
            val valOrOffset = buf.int
            entries.add(IFDEntry(tag, type, count, valOrOffset))
        }

        // Verify strictly ascending order
        for (i in 0 until entries.size - 1) {
            assertTrue(
                "Tag 0x${Integer.toHexString(entries[i].tag)} must be strictly less than Tag 0x${Integer.toHexString(entries[i + 1].tag)}",
                entries[i].tag < entries[i + 1].tag
            )
        }

        val tagMap = entries.associateBy { it.tag }

        // Mandatory Tags Validation
        // 0x00FE: NewSubfileType = 0
        val subfile = tagMap[0x00FE]
        assertNotNull("Tag 0x00FE (NewSubfileType) must exist", subfile)
        assertEquals(4, subfile!!.type) // LONG
        assertEquals(1, subfile.count)
        assertEquals(0, subfile.valueOrOffset)

        // 0x0100: ImageWidth = 4096
        val widthTag = tagMap[0x0100]
        assertNotNull(widthTag)
        assertEquals(4096, widthTag!!.valueOrOffset)

        // 0x0101: ImageLength = 3072
        val heightTag = tagMap[0x0101]
        assertNotNull(heightTag)
        assertEquals(3072, heightTag!!.valueOrOffset)

        // 0x0102: BitsPerSample = 16
        val bpsTag = tagMap[0x0102]
        assertNotNull(bpsTag)
        assertEquals(16, bpsTag!!.valueOrOffset)

        // 0x0103: Compression = 1
        val compTag = tagMap[0x0103]
        assertNotNull(compTag)
        assertEquals(1, compTag!!.valueOrOffset)

        // 0x0106: PhotometricInterpretation = 32803
        val photoTag = tagMap[0x0106]
        assertNotNull(photoTag)
        assertEquals(32803, photoTag!!.valueOrOffset)

        // 0x0112: Orientation = 6 (90 deg)
        val orientTag = tagMap[0x0112]
        assertNotNull(orientTag)
        assertEquals(6, orientTag!!.valueOrOffset)

        // 0xC619: BlackLevelRepeatDim = [2, 2]
        val blDimTag = tagMap[0xC619]
        assertNotNull("Tag 0xC619 (BlackLevelRepeatDim) must exist", blDimTag)
        assertEquals(3, blDimTag!!.type) // SHORT
        assertEquals(2, blDimTag.count)

        // 0xC61D: WhiteLevel = 1023
        val wlTag = tagMap[0xC61D]
        assertNotNull(wlTag)
        assertEquals(1023, wlTag!!.valueOrOffset)

        // 0xC634: AsShotNeutral (3 RATIONALs)
        val neutralTag = tagMap[0xC634]
        assertNotNull("Tag 0xC634 (AsShotNeutral) must exist", neutralTag)
        assertEquals(5, neutralTag!!.type) // RATIONAL
        assertEquals(3, neutralTag.count)

        // Read AsShotNeutral values from offset
        buf.position(neutralTag.valueOrOffset)
        val rNum = buf.int
        val rDen = buf.int
        val gNum = buf.int
        val gDen = buf.int
        val bNum = buf.int
        val bDen = buf.int
        assertEquals(0.4723f, rNum.toFloat() / rDen.toFloat(), 0.001f)
        assertEquals(1.0f, gNum.toFloat() / gDen.toFloat(), 0.001f)
        assertEquals(0.6505f, bNum.toFloat() / bDen.toFloat(), 0.001f)

        // Strip offset alignment
        val stripTag = tagMap[0x0111]
        assertNotNull(stripTag)
        val stripOffset = stripTag!!.valueOrOffset
        assertEquals(0, stripOffset % 16) // 16-byte aligned

        val stripByteCountsTag = tagMap[0x0117]
        assertNotNull(stripByteCountsTag)
        assertEquals(payloadSize, stripByteCountsTag!!.valueOrOffset)

        assertEquals((stripOffset + payloadSize).toLong(), dngFile.length())

        // Cleanup
        dngFile.delete()
        tempDir.delete()
    }
}
