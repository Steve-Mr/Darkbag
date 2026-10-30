package top.maary.darkbag.motionphoto

import android.media.MediaCodec
import org.junit.Assert.*
import org.junit.Test

class CircularAudioRingBufferTest {

    @Test
    fun testAudioRingBufferAddAndPrune() {
        val ringBuffer = CircularAudioRingBuffer(maxRetentionDurationUs = 1_000_000L) // 1 second window

        // Add 21ms audio packets (typical for 1024 samples at 48kHz = 21.33ms) from 0 to 2000ms
        for (i in 0..100) {
            val ptsUs = (i * 21_333L)
            val dummyData = byteArrayOf((i % 256).toByte(), 0x11, 0x22)
            ringBuffer.addSample(dummyData, ptsUs, 0)
        }

        // Slice between 1200ms and 1800ms
        val startPtsUs = 1_200_000L
        val endPtsUs = 1_800_000L
        val slice = ringBuffer.slice(startTimeUs = startPtsUs, endTimeUs = endPtsUs)

        assertNotNull("Slice should not be null", slice)
        assertTrue("Slice must have audio samples", slice!!.samples.isNotEmpty())
        assertTrue("First sample PTS should be >= startPtsUs", slice.samples.first().presentationTimeUs >= startPtsUs)
        assertTrue("Last sample PTS should be <= endPtsUs", slice.samples.last().presentationTimeUs <= endPtsUs)
    }

    @Test
    fun testCodecConfigRetention() {
        val ringBuffer = CircularAudioRingBuffer(maxRetentionDurationUs = 2_000_000L)
        val dummyCsd0 = byteArrayOf(0x11, 0x90.toByte()) // Typical AAC LC 48kHz Stereo CSD-0
        ringBuffer.setCodecConfig(dummyCsd0)

        ringBuffer.addSample(byteArrayOf(1, 2, 3), 100_000L, 0)
        ringBuffer.addSample(byteArrayOf(4, 5, 6), 121_333L, 0)

        val slice = ringBuffer.slice(startTimeUs = 90_000L, endTimeUs = 130_000L)
        assertNotNull(slice)
        assertNotNull(slice!!.csd0Buffer)
        assertArrayEquals(dummyCsd0, slice.csd0Buffer)
        assertEquals(2, slice.samples.size)
    }

    @Test
    fun testEmptyRingBufferReturnsNull() {
        val ringBuffer = CircularAudioRingBuffer()
        val slice = ringBuffer.slice(100_000L, 200_000L)
        assertNull(slice)
    }

    @Test
    fun testClearRemovesAllSamples() {
        val ringBuffer = CircularAudioRingBuffer()
        ringBuffer.addSample(byteArrayOf(1), 100_000L, 0)
        ringBuffer.clear()
        assertNull(ringBuffer.slice(50_000L, 150_000L))
    }
}
