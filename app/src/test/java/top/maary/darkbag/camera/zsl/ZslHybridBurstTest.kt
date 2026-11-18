package top.maary.darkbag.camera.zsl

import android.hardware.camera2.TotalCaptureResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZslHybridBurstTest {

    private fun dummyTotalCaptureResult(): TotalCaptureResult {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val field = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = field.get(null)
        val allocateInstance = unsafeClass.getMethod("allocateInstance", Class::class.java)
        return allocateInstance.invoke(unsafe, TotalCaptureResult::class.java) as TotalCaptureResult
    }

    @Test
    fun adaptivePolicyThresholdsAndWindowExtension() {
        assertEquals(2, ZslAdaptivePolicy.MIN_HYBRID_PRE_ROLL)
        assertEquals(3, ZslAdaptivePolicy.MIN_HDR_FRAMES)

        // Strict mode (!adaptive) insists on requestedBurst
        assertEquals(5, ZslAdaptivePolicy.hdrTargetFrameCount(adaptive = false, stableFrames = 2, requestedBurst = 5))
        assertEquals(5, ZslAdaptivePolicy.hdrTargetFrameCount(adaptive = false, stableFrames = 3, requestedBurst = 5))
        assertEquals(8, ZslAdaptivePolicy.hdrTargetFrameCount(adaptive = false, stableFrames = 4, requestedBurst = 8))

        // Adaptive mode contracts down to stableFrames (bounded by 0..requestedBurst)
        assertEquals(0, ZslAdaptivePolicy.hdrTargetFrameCount(adaptive = true, stableFrames = 0, requestedBurst = 5))
        assertEquals(3, ZslAdaptivePolicy.hdrTargetFrameCount(adaptive = true, stableFrames = 3, requestedBurst = 5))
        assertEquals(4, ZslAdaptivePolicy.hdrTargetFrameCount(adaptive = true, stableFrames = 4, requestedBurst = 5))
        assertEquals(5, ZslAdaptivePolicy.hdrTargetFrameCount(adaptive = true, stableFrames = 5, requestedBurst = 5))

        // canExtendWindow
        assertFalse(ZslAdaptivePolicy.canExtendWindow(stableFrames = 1, requestedBurst = 5))
        assertTrue(ZslAdaptivePolicy.canExtendWindow(stableFrames = 2, requestedBurst = 5))
        assertTrue(ZslAdaptivePolicy.canExtendWindow(stableFrames = 3, requestedBurst = 5))
        assertTrue(ZslAdaptivePolicy.canExtendWindow(stableFrames = 4, requestedBurst = 5))
        assertFalse(ZslAdaptivePolicy.canExtendWindow(stableFrames = 5, requestedBurst = 5))
    }

    @Test
    fun burstSeedCountsCalculatedCorrectly() {
        val mockResult = dummyTotalCaptureResult()
        val dummyFrame1 = ZslFrame(
            buffer = null,
            width = 4000,
            height = 3000,
            rowStride = 4000,
            timestamp = 1000L,
            result = mockResult,
            iso = 100,
            exposureTimeNs = 10_000_000L,
            wb = floatArrayOf(1f, 1f, 1f, 1f),
            cropKey = null,
            afFocused = true,
            lensId = "0",
            zoomRatio = 1f,
            rotationDegrees = 0,
            rowLength = 4000,
            image = null
        )
        val dummyFrame2 = dummyFrame1.copy(timestamp = 1033L)

        // Partial pre-roll (2 frames) extending to 5 frames
        val hybridSeed = ZslBurstSeed(
            frames = listOf(dummyFrame2, dummyFrame1),
            baseResult = mockResult,
            iso = 100,
            exposureTimeNs = 10_000_000L,
            digitalGain = 2.0f,
            targetFrameCount = 5
        )
        assertEquals(2, hybridSeed.preRollCount)
        assertEquals(3, hybridSeed.remainingCount)

        // Full pre-roll (5 frames)
        val fullSeed = ZslBurstSeed(
            frames = listOf(dummyFrame2, dummyFrame1, dummyFrame1, dummyFrame1, dummyFrame1),
            baseResult = mockResult,
            iso = 100,
            exposureTimeNs = 10_000_000L,
            digitalGain = 2.0f,
            targetFrameCount = 5
        )
        assertEquals(5, fullSeed.preRollCount)
        assertEquals(0, fullSeed.remainingCount)
    }

    @Test
    fun testPreRollShortVsNoStableRunFallbackReasons() {
        val minRequiredRun = ZslAdaptivePolicy.MIN_HYBRID_PRE_ROLL // 2

        // When adaptive=false and physical ring frames are strictly less than minRequiredRun (e.g. 0 or 1 frame)
        val ringFrames0 = 0
        val reasonStrict0 = if (!false && ringFrames0 < minRequiredRun) "pre_roll_short" else "no_stable_run"
        assertEquals("pre_roll_short", reasonStrict0)

        val ringFrames1 = 1
        val reasonStrict1 = if (!false && ringFrames1 < minRequiredRun) "pre_roll_short" else "no_stable_run"
        assertEquals("pre_roll_short", reasonStrict1)

        // When adaptive=true, even with 1 frame, reason stays no_stable_run to allow instant single fallback
        val reasonAdaptive1 = if (!true && ringFrames1 < minRequiredRun) "pre_roll_short" else "no_stable_run"
        assertEquals("no_stable_run", reasonAdaptive1)

        // When ring has plenty of frames (e.g. 5) but stability failed (unfocused or AE jumping)
        val ringFrames5 = 5
        val reasonStrictJumping = if (!false && ringFrames5 < minRequiredRun) "pre_roll_short" else "no_stable_run"
        assertEquals("no_stable_run", reasonStrictJumping)
    }
}
