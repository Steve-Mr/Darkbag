package top.maary.darkbag.motionphoto

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

class MotionPhotoReaderTest {

    @Test
    fun testParseXmpPayload() {
        val payload = MotionPhotoXmpWriter.buildXmpPayload(
            presentationTimestampUs = 750000L,
            videoLengthBytes = 123456L
        )

        val info = MotionPhotoReader.parseXmpPayload(payload, 500000L)
        assertNotNull(info)
        assertEquals(123456L, info!!.videoLength)
        assertEquals(750000L, info.presentationTimestampUs)
    }

    @Test
    fun testParseFromWrittenMotionPhoto() {
        // Minimal fake JPEG: SOI (FF D8) + SOS + EOI (FF D9)
        val fakeJpeg = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(),
            0xFF.toByte(), 0xDA.toByte(), 0x00, 0x02,
            0x12, 0x34,
            0xFF.toByte(), 0xD9.toByte()
        )

        val tempMp4 = File.createTempFile("test_motion_reader", ".mp4")
        val fakeMp4Data = "TEST_MP4_PAYLOAD_BYTES".toByteArray(Charsets.UTF_8)
        tempMp4.writeBytes(fakeMp4Data)

        val out = ByteArrayOutputStream()
        val pts = 987654L
        MotionPhotoXmpWriter.writeMotionPhoto(fakeJpeg, tempMp4, pts, out)
        val motionPhotoBytes = out.toByteArray()

        val inputStream = ByteArrayInputStream(motionPhotoBytes)
        val info = MotionPhotoReader.parseMotionPhotoInfo(inputStream, motionPhotoBytes.size.toLong())

        assertNotNull(info)
        assertEquals(fakeMp4Data.size.toLong(), info!!.videoLength)
        assertEquals(pts, info.presentationTimestampUs)

        tempMp4.delete()
    }

    @Test
    fun testNonMotionPhotoReturnsNull() {
        val normalJpeg = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(),
            0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10,
            0x4A, 0x46, 0x49, 0x46, 0x00, 0x01, 0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00,
            0xFF.toByte(), 0xDA.toByte(), 0x00, 0x02, 0x12, 0x34,
            0xFF.toByte(), 0xD9.toByte()
        )

        val inputStream = ByteArrayInputStream(normalJpeg)
        val info = MotionPhotoReader.parseMotionPhotoInfo(inputStream, normalJpeg.size.toLong())
        assertNull(info)
    }
}
