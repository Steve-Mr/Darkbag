package top.maary.darkbag.camera.zsl

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
