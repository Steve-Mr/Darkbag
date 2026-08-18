package top.maary.darkbag.motionphoto

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

class MotionPhotoXmpWriterTest {

    @Test
    fun testXmpPayloadGeneration() {
        val stillPtsUs = 1_250_000L
        val videoLength = 2_048_100L
        val xmp = MotionPhotoXmpWriter.buildXmpPayload(stillPtsUs, videoLength)

        assertTrue(xmp.contains("GCamera:MotionPhoto=\"1\""))
        assertTrue(xmp.contains("GCamera:MotionPhotoPresentationTimestampUs=\"1250000\""))
        assertTrue(xmp.contains("GCamera:MicroVideoOffset=\"2048100\""))
        assertTrue(xmp.contains("Item:Semantic=\"MotionPhoto\""))
        assertTrue(xmp.contains("Item:Length=\"2048100\""))
        assertTrue(xmp.contains("<Container:Item"))
    }

    @Test
    fun testMotionPhotoAssembly() {
        // Minimal fake JPEG: SOI (FF D8) + SOS + EOI (FF D9)
        val fakeJpeg = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(),
            0xFF.toByte(), 0xDA.toByte(), 0x00, 0x02,
            0x12, 0x34,
            0xFF.toByte(), 0xD9.toByte()
        )

        val tempMp4 = File.createTempFile("test_video", ".mp4")
        val fakeMp4Data = byteArrayOf(0x00, 0x00, 0x00, 0x18, 0x66, 0x74, 0x79, 0x70) // ftyp header
        tempMp4.writeBytes(fakeMp4Data)

        val out = ByteArrayOutputStream()
        MotionPhotoXmpWriter.writeMotionPhoto(fakeJpeg, tempMp4, 1_000_000L, out)
        val combinedBytes = out.toByteArray()

        // 1. Starts with SOI
        assertEquals(0xFF.toByte(), combinedBytes[0])
        assertEquals(0xD8.toByte(), combinedBytes[1])

        // 2. Contains APP1 marker
        assertEquals(0xFF.toByte(), combinedBytes[2])
        assertEquals(0xE1.toByte(), combinedBytes[3])

        // 3. Contains MP4 data at the end
        val mp4Offset = combinedBytes.size - fakeMp4Data.size
        for (i in fakeMp4Data.indices) {
            assertEquals(fakeMp4Data[i], combinedBytes[mp4Offset + i])
        }

        tempMp4.delete()
    }

    @Test
    fun testExifPreservingXmpInjection() {
        // Fake JPEG with existing Exif APP1: SOI (FF D8) + APP1 Exif (FF E1 00 06 45 78 69 66) + SOS + EOI (FF D9)
        val exifPayload = "Exif".toByteArray(Charsets.US_ASCII)
        val exifLen = exifPayload.size + 2
        val fakeJpegWithExif = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(),
            0xFF.toByte(), 0xE1.toByte(), ((exifLen shr 8) and 0xFF).toByte(), (exifLen and 0xFF).toByte(),
            0x45, 0x78, 0x69, 0x66,
            0xFF.toByte(), 0xDA.toByte(), 0x00, 0x02, 0x12, 0x34,
            0xFF.toByte(), 0xD9.toByte()
        )

        val xmpBytes = "http://ns.adobe.com/xap/1.0/\u0000<test/>".toByteArray(Charsets.UTF_8)
        val injected = MotionPhotoXmpWriter.injectXmpIntoJpeg(fakeJpegWithExif, xmpBytes)

        // Must start with SOI
        assertEquals(0xFF.toByte(), injected[0])
        assertEquals(0xD8.toByte(), injected[1])

        // First APP1 is Exif
        assertEquals(0xFF.toByte(), injected[2])
        assertEquals(0xE1.toByte(), injected[3])
        assertEquals(0x45.toByte(), injected[6]) // 'E'
        assertEquals(0x78.toByte(), injected[7]) // 'x'

        // Second APP1 is XMP
        val secondApp1Pos = 2 + 2 + exifLen
        assertEquals(0xFF.toByte(), injected[secondApp1Pos])
        assertEquals(0xE1.toByte(), injected[secondApp1Pos + 1])
    }
}
