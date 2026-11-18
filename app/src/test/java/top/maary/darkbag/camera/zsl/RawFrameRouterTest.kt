package top.maary.darkbag.camera.zsl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawFrameRouterTest {
    @Test
    fun captureFrameMustStartAtOrAfterCaptureStart() {
        assertTrue(RawFrameRouter.isFreshCaptureFrame(true, 1_000L, 1_000L))
        assertTrue(RawFrameRouter.isFreshCaptureFrame(true, 1_000L, 1_001L))
        assertFalse(RawFrameRouter.isFreshCaptureFrame(true, 1_000L, 999L))
        assertFalse(RawFrameRouter.isFreshCaptureFrame(false, 1_000L, 1_001L))
    }
}

class RawRouterStateTest {
    @Test
    fun captureNeedsCaptureStartedAndFreshTimestamp() {
        val state = RawRouterState().beginCapture(7L)
        assertFalse(state.canDeliver(2_000L))
        val started = state.onCaptureStarted(1_000L)
        assertTrue(started.canDeliver(1_000L))
        assertTrue(started.canDeliver(1_001L))
        assertFalse(started.canDeliver(999L))
    }

    @Test
    fun captureStartedIsLatchedAndScopedToCaptureMode() {
        val started = RawRouterState().beginCapture(1L).onCaptureStarted(100L)
        val secondStart = started.onCaptureStarted(200L)
        assertTrue(secondStart.captureStarted)
        assertTrue(secondStart.captureStartTimestampNs == 100L)
        assertFalse(started.endCapture(true).onCaptureStarted(300L).canDeliver(300L))
    }

    @Test
    fun transitionsResetCaptureFields() {
        val state = RawRouterState().beginCapture(9L).onCaptureStarted(100L)
        val ring = state.endCapture(hasRing = true)
        assertTrue(ring.mode == RawRouterMode.RING)
        assertEquals(0L, ring.activeCaptureGeneration)
        assertFalse(ring.captureStarted)
        assertTrue(ring.idle().mode == RawRouterMode.IDLE)
    }
}
