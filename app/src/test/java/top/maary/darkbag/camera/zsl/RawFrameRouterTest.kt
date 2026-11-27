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
        val exactResult = org.robolectric.shadows.TotalCaptureResultBuilder.newBuilder().build()
        val latestResult = org.robolectric.shadows.TotalCaptureResultBuilder.newBuilder().build()
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

    @Test
    fun preStartImageBufferedAndDeliveredOnCaptureStarted() {
        val thread = android.os.HandlerThread("test-raw-router").apply { start() }
        val bgHandler = Handler(thread.looper)
        val reader = ImageReader.newInstance(64, 64, android.graphics.PixelFormat.RGBA_8888, 4)
        val router = RawFrameRouter(
            reader = reader,
            handler = bgHandler,
            resultLookup = { null }
        )
        val delivered = java.util.concurrent.CopyOnWriteArrayList<Long>()
        val deliveredLatch = java.util.concurrent.CountDownLatch(1)

        try {
            assertTrue(router.beginCapture(consumer = { img ->
                delivered.add(img.timestamp)
                img.close()
                deliveredLatch.countDown()
            }, requireCaptureStarted = true))

            // Post a frame before onCaptureStarted arrives; listener buffers it instead of dropping.
            val canvas1 = reader.surface.lockCanvas(null)
            reader.surface.unlockCanvasAndPost(canvas1)
            Shadows.shadowOf(thread.looper).idle()
            assertEquals(0, delivered.size)

            // Fire onCaptureStarted with timestamp=0L so the buffered frame (ts >= 0L) is flushed.
            router.onCaptureStarted(0L)
            Shadows.shadowOf(thread.looper).idle()
            assertTrue(deliveredLatch.await(1, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(1, delivered.size)

            // Verify stale pre-start frame (ts < captureStartTimestampNs) is closed and NOT delivered.
            assertTrue(router.beginCapture(consumer = { img ->
                delivered.add(img.timestamp)
                img.close()
            }, requireCaptureStarted = true))
            val canvas2 = reader.surface.lockCanvas(null)
            reader.surface.unlockCanvasAndPost(canvas2)
            Shadows.shadowOf(thread.looper).idle()
            router.onCaptureStarted(Long.MAX_VALUE)
            Shadows.shadowOf(thread.looper).idle()
            assertEquals(1, delivered.size)

            // Verify endCapture clears any un-flushed pre-start images cleanly.
            assertTrue(router.beginCapture(consumer = { img ->
                delivered.add(img.timestamp)
                img.close()
            }, requireCaptureStarted = true))
            val canvas3 = reader.surface.lockCanvas(null)
            reader.surface.unlockCanvasAndPost(canvas3)
            Shadows.shadowOf(thread.looper).idle()
            router.endCapture()
            Shadows.shadowOf(thread.looper).idle()
            assertEquals(1, delivered.size)
        } finally {
            router.close()
            Shadows.shadowOf(thread.looper).idle()
            reader.close()
            thread.quitSafely()
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
    fun captureDoesNotRequireCaptureStartedWhenDisabled() {
        val state = RawRouterState().beginCapture(7L, requireCaptureStarted = false)
        assertTrue(state.canDeliver(500L))
        assertFalse(state.endCapture(hasRing = false).canDeliver(500L))
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
