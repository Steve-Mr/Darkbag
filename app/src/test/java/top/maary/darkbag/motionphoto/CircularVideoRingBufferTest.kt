package top.maary.darkbag.motionphoto

import android.media.MediaCodec
import org.junit.Assert.*
import org.junit.Test

class CircularVideoRingBufferTest {

    @Test
    fun testRingBufferAddAndPrune() {
        val ringBuffer = CircularVideoRingBuffer(maxRetentionDurationUs = 1_000_000L) // 1 second window

        // Add frames from 0ms to 2000ms at 33ms (~30fps) intervals
        for (i in 0..60) {
            val ptsUs = i * 33_333L
            val flags = if (i % 30 == 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            val dummyData = byteArrayOf((i % 256).toByte())
            ringBuffer.addSample(dummyData, ptsUs, flags)
        }

        // Slice around 1800ms with pre=500ms, post=200ms
        val triggerPtsUs = 1_800_000L
        val slice = ringBuffer.slice(
            triggerTimestampUs = triggerPtsUs,
            preDurationUs = 500_000L,
            postDurationUs = 200_000L
        )

        assertNotNull("Slice should not be null", slice)
        assertTrue("Slice must have samples", slice!!.samples.isNotEmpty())
        assertTrue("Slice first frame must be keyframe", slice.samples.first().isKeyFrame)
        assertTrue("Still PTS offset must be non-negative", slice.stillPresentationTimestampUs >= 0)
        assertTrue("Still PTS offset must be <= duration", slice.stillPresentationTimestampUs <= slice.durationUs + 100_000L)
    }

    @Test
    fun testCodecConfigRetention() {
        val ringBuffer = CircularVideoRingBuffer(maxRetentionDurationUs = 2_000_000L)
        val spsPps = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0x00)
        ringBuffer.addSample(spsPps, 0L, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)

        // Add 1 keyframe and some normal frames
        ringBuffer.addSample(byteArrayOf(1), 100_000L, MediaCodec.BUFFER_FLAG_KEY_FRAME)
        ringBuffer.addSample(byteArrayOf(2), 133_000L, 0)
        ringBuffer.addSample(byteArrayOf(3), 166_000L, 0)

        val slice = ringBuffer.slice(
            triggerTimestampUs = 150_000L,
            preDurationUs = 100_000L,
            postDurationUs = 50_000L
        )

        assertNotNull(slice)
        assertEquals(1, slice!!.spsPpsBuffers.size)
        assertArrayEquals(spsPps, slice.spsPpsBuffers.first())
        assertEquals(3, slice.samples.size)
    }
}
