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
        assertTrue(xmp.contains("<Item:Semantic>MotionPhoto</Item:Semantic>"))
        assertTrue(xmp.contains("<Item:Length>2048100</Item:Length>"))
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
}
