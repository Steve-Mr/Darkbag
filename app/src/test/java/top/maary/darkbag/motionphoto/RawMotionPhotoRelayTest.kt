package top.maary.darkbag.motionphoto

import android.media.FakeRawImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RawMotionPhotoRelayTest {

    @Test
    fun `throttlingDropsFramesWithIntervalLessThan33ms`() {
        val dispatchedTimestamps = mutableListOf<Long>()
        val relay = RawMotionPhotoRelay(
            lutSurfaceProcessorProvider = { null },
            characteristicsProvider = { null },
            nativeSubsampler = { _, w, h, _, _ -> ((w / 4) shl 16) or (h / 4) },
            frameConsumer = { _, _, _, _, _, _, _, _, ts ->
                dispatchedTimestamps.add(ts)
            }
        )

        relay.start()

        val buf = ByteBuffer.allocateDirect(1008 * 756 * 2)

        // Frame 1
        relay.onRawFrame(FakeRawImage(1008, 756, 1_000_000_000L, buf), null)
        assertEquals(1, dispatchedTimestamps.size)

        // Frame 2 arrives 20ms later (< 33ms) -> must be dropped
        relay.onRawFrame(FakeRawImage(1008, 756, 1_020_000_000L, buf), null)
        assertEquals(1, dispatchedTimestamps.size)

        // Frame 3 arrives 40ms after Frame 1 (>= 33ms) -> must be dispatched
        relay.onRawFrame(FakeRawImage(1008, 756, 1_040_000_000L, buf), null)
        assertEquals(2, dispatchedTimestamps.size)
    }

    @Test
    fun `doubleBufferRotatesBetweenTwoDistinctBuffers`() {
        val capturedBuffers = mutableListOf<ByteBuffer>()
        val relay = RawMotionPhotoRelay(
            lutSurfaceProcessorProvider = { null },
            characteristicsProvider = { null },
            nativeSubsampler = { _, w, h, _, _ -> ((w / 4) shl 16) or (h / 4) },
            frameConsumer = { bayerBuffer, _, _, _, _, _, _, _, _ ->
                capturedBuffers.add(bayerBuffer)
            }
        )

        relay.start()

        val buf = ByteBuffer.allocateDirect(1008 * 756 * 2)

        // Frame 1
        relay.onRawFrame(FakeRawImage(1008, 756, 1_000_000_000L, buf), null)
        // Frame 2 (50ms later)
        relay.onRawFrame(FakeRawImage(1008, 756, 1_050_000_000L, buf), null)

        assertEquals(2, capturedBuffers.size)
        assertNotSame("Double buffering must rotate to distinct ByteBuffers", capturedBuffers[0], capturedBuffers[1])
    }

    @Test
    fun `bufferCapacityExpandsForLargeSensors`() {
        val relay = RawMotionPhotoRelay(
            lutSurfaceProcessorProvider = { null },
            characteristicsProvider = { null },
            nativeSubsampler = { _, w, h, _, _ -> ((w / 4) shl 16) or (h / 4) },
            frameConsumer = { _, _, _, _, _, _, _, _, _ -> }
        )

        assertEquals(RawMotionPhotoRelay.INITIAL_BUFFER_CAPACITY, relay.currentBufferCapacity)

        relay.start()

        // 50MP sensor: 8192 x 6144 -> dst 2048 x 1536 -> required 6,291,456 bytes
        val largeBuf = ByteBuffer.allocateDirect(1000)
        relay.onRawFrame(FakeRawImage(8192, 6144, 1_000_000_000L, largeBuf), null)

        val requiredBytes = (8192 / 4) * (6144 / 4) * 2
        assertTrue(
            "Buffer capacity ${relay.currentBufferCapacity} must expand to >= $requiredBytes",
            relay.currentBufferCapacity >= requiredBytes
        )
    }

    @Test
    fun `relayRespectsStartAndStopLifecycle`() {
        var dispatched = 0
        val relay = RawMotionPhotoRelay(
            lutSurfaceProcessorProvider = { null },
            characteristicsProvider = { null },
            nativeSubsampler = { _, w, h, _, _ -> ((w / 4) shl 16) or (h / 4) },
            frameConsumer = { _, _, _, _, _, _, _, _, _ -> dispatched++ }
        )

        val buf = ByteBuffer.allocateDirect(1008 * 756 * 2)

        // Stopped -> frames are ignored
        relay.onRawFrame(FakeRawImage(1008, 756, 1_000_000_000L, buf), null)
        assertEquals(0, dispatched)

        // Started -> frame dispatched
        relay.start()
        relay.onRawFrame(FakeRawImage(1008, 756, 1_000_000_000L, buf), null)
        assertEquals(1, dispatched)

        // Stopped again -> subsequent frames ignored
        relay.stop()
        relay.onRawFrame(FakeRawImage(1008, 756, 1_100_000_000L, buf), null)
        assertEquals(1, dispatched)
    }
}
