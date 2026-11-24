package top.maary.darkbag.camera.zsl

import android.graphics.ImageFormat
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowTotalCaptureResult

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RawFrameRouterTest {
    @Test
    fun captureFrameMustStartAtOrAfterCaptureStart() {
        assertTrue(RawFrameRouter.isFreshCaptureFrame(true, 1_000L, 1_000L))
        assertTrue(RawFrameRouter.isFreshCaptureFrame(true, 1_000L, 1_001L))
        assertFalse(RawFrameRouter.isFreshCaptureFrame(true, 1_000L, 999L))
        assertFalse(RawFrameRouter.isFreshCaptureFrame(false, 1_000L, 1_001L))
    }

    @Test
    fun resolveMotionTapResultFallsBackToLatestCaptureResultAndClearsOnClose() {
        val reader = ImageReader.newInstance(64, 64, ImageFormat.RAW_SENSOR, 2)
        val exactResult = ShadowTotalCaptureResult.newTotalCaptureResult()
        val latestResult = ShadowTotalCaptureResult.newTotalCaptureResult()
        val router = RawFrameRouter(
            reader = reader,
            handler = Handler(Looper.getMainLooper()),
            resultLookup = { ts -> if (ts == 2_000L) exactResult else null }
        )

        try {
            // Exact lookup hits when timestamp matches
            assertSame(exactResult, router.resolveMotionTapResult(2_000L))
            // Missing timestamp returns null before any capture result arrives
            assertNull(router.resolveMotionTapResult(1_000L))

            // Once a capture result arrives, missing timestamp falls back to latestCaptureResult
            router.onCaptureResult(latestResult)
            assertSame(latestResult, router.resolveMotionTapResult(1_000L))
            // Exact match still takes precedence over latestCaptureResult
            assertSame(exactResult, router.resolveMotionTapResult(2_000L))

            // close() clears latestCaptureResult and detaches listener
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            router.close()
            assertNull(router.resolveMotionTapResult(1_000L))
        } finally {
            reader.close()
        }
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
