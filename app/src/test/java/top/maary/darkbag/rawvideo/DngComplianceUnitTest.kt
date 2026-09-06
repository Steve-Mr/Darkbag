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
            activeLutName = "CinematicWarm",
            activeLogName = "D-Log",
            frameCount = 1,
            orientation = 90,
            calibrationIlluminant1 = 21,
            calibrationIlluminant2 = 17,
            baselineExposure = 0.0f,
            exposure = 0.5f,
            contrast = 0.2f,
            saturation = -0.1f,
            colorMatrix1 = floatArrayOf(0.6667f, -0.1588f, -0.0857f, -0.5739f, 1.3897f, 0.143f, -0.1378f, 0.2651f, 0.6036f),
            colorMatrix2 = floatArrayOf(0.7f, -0.1f, -0.05f, -0.5f, 1.3f, 0.1f, -0.1f, 0.2f, 0.6f),
            forwardMatrix1 = floatArrayOf(0.8f, 0.1f, 0.0f, 0.1f, 0.8f, 0.1f, 0.0f, 0.1f, 0.8f),
            forwardMatrix2 = floatArrayOf(0.85f, 0.05f, 0.0f, 0.05f, 0.85f, 0.05f, 0.0f, 0.05f, 0.85f),
            make = "motorola",
            model = "XT2409-5"
        )

        val fnBits = java.lang.Float.floatToIntBits(1.8f).toLong() and 0xFFFFFFFFL
        val flBits = java.lang.Float.floatToIntBits(24.0f).toLong() and 0xFFFFFFFFL
        val meta = longArrayOf(System.nanoTime(), 10_000_000L, 325L, fnBits, flBits)

        RawVideoExporter.writeDngFile(
            file = dngFile,
            header = header,
            meta = meta,
            bayerData = fakeBayer,
            payloadSize = payloadSize
        )

        assertTrue(dngFile.exists())
        assertTrue(dngFile.length() > payloadSize)

        // Validate parsing with Android's ExifInterface
        val exif = androidx.exifinterface.media.ExifInterface(dngFile.absolutePath)
        val userComment = exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_USER_COMMENT)
        assertNotNull("UserComment should be present and non-null via ExifInterface", userComment)
        assertTrue("UserComment should contain D-Log", userComment!!.contains("\"log\":\"D-Log\""))
        assertTrue("UserComment should contain CinematicWarm", userComment.contains("\"lut\":\"CinematicWarm\""))
        assertTrue("UserComment should contain exposure", userComment.contains("\"exposure\":0.5"))

        assertEquals("motorola", exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_MAKE))
        assertEquals("XT2409-5", exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_MODEL))
        val fNumParsed = exif.getAttributeDouble(androidx.exifinterface.media.ExifInterface.TAG_F_NUMBER, 0.0)
        assertEquals(1.8, fNumParsed, 0.01)
        val focalLengthParsed = exif.getAttributeDouble(androidx.exifinterface.media.ExifInterface.TAG_FOCAL_LENGTH, 0.0)
        assertEquals(24.0, focalLengthParsed, 0.01)

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
        assertEquals(37, entryCount)

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

        // 0x829D: FNumber
        val fnTag = tagMap[0x829D]
        assertNotNull("Tag 0x829D (FNumber) must exist", fnTag)
        assertEquals(5, fnTag!!.type) // RATIONAL
        assertEquals(1, fnTag.count)

        // 0x8769: ExifIFDPointer
        val exifIfdPtrTag = tagMap[0x8769]
        assertNotNull("Tag 0x8769 (ExifIFDPointer) must exist", exifIfdPtrTag)
        assertEquals(4, exifIfdPtrTag!!.type) // LONG

        // 0x920A: FocalLength
        val flTag = tagMap[0x920A]
        assertNotNull("Tag 0x920A (FocalLength) must exist", flTag)
        assertEquals(5, flTag!!.type) // RATIONAL
        assertEquals(1, flTag.count)

        // 0x9286: UserComment
        val commentTag = tagMap[0x9286]
        assertNotNull("Tag 0x9286 (UserComment) must exist", commentTag)
        assertEquals(7, commentTag!!.type) // UNDEFINED

        // 0xC619: BlackLevelRepeatDim = [2, 2]
        val blDimTag = tagMap[0xC619]
        assertNotNull("Tag 0xC619 (BlackLevelRepeatDim) must exist", blDimTag)
        assertEquals(3, blDimTag!!.type) // SHORT
        assertEquals(2, blDimTag.count)

        // 0xC61D: WhiteLevel = 1023
        val wlTag = tagMap[0xC61D]
        assertNotNull(wlTag)
        assertEquals(1023, wlTag!!.valueOrOffset)

        // 0xC621: ColorMatrix1 (9 SRATIONALs)
        val cm1Tag = tagMap[0xC621]
        assertNotNull("Tag 0xC621 (ColorMatrix1) must exist", cm1Tag)
        assertEquals(10, cm1Tag!!.type) // SRATIONAL
        assertEquals(9, cm1Tag.count)

        // 0xC622: ColorMatrix2 (9 SRATIONALs)
        val cm2Tag = tagMap[0xC622]
        assertNotNull("Tag 0xC622 (ColorMatrix2) must exist", cm2Tag)
        assertEquals(10, cm2Tag!!.type) // SRATIONAL
        assertEquals(9, cm2Tag.count)

        // 0xC634: AsShotNeutral (3 RATIONALs)
        val neutralTag = tagMap[0xC634]
        assertNotNull("Tag 0xC634 (AsShotNeutral) must exist", neutralTag)
        assertEquals(5, neutralTag!!.type) // RATIONAL
        assertEquals(3, neutralTag.count)

        // 0xC65A: CalibrationIlluminant1 (SHORT)
        val calib1Tag = tagMap[0xC65A]
        assertNotNull("Tag 0xC65A (CalibrationIlluminant1) must exist", calib1Tag)
        assertEquals(3, calib1Tag!!.type)
        assertEquals(21, calib1Tag.valueOrOffset)

        // 0xC65B: CalibrationIlluminant2 (SHORT)
        val calib2Tag = tagMap[0xC65B]
        assertNotNull("Tag 0xC65B (CalibrationIlluminant2) must exist", calib2Tag)
        assertEquals(3, calib2Tag!!.type)
        assertEquals(17, calib2Tag.valueOrOffset)

        // 0xC714: ForwardMatrix1 (9 SRATIONALs)
        val fm1Tag = tagMap[0xC714]
        assertNotNull("Tag 0xC714 (ForwardMatrix1) must exist", fm1Tag)
        assertEquals(10, fm1Tag!!.type)
        assertEquals(9, fm1Tag.count)

        // 0xC715: ForwardMatrix2 (9 SRATIONALs)
        val fm2Tag = tagMap[0xC715]
        assertNotNull("Tag 0xC715 (ForwardMatrix2) must exist", fm2Tag)
        assertEquals(10, fm2Tag!!.type)
        assertEquals(9, fm2Tag.count)

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
