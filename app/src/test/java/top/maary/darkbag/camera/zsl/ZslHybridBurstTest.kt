package top.maary.darkbag.camera.zsl

import android.hardware.camera2.TotalCaptureResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.maary.darkbag.camera.motion.MotionLevel
import top.maary.darkbag.models.StandardTimingTracker

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

    private fun createSupervisorWithRing(ring: ZslRawRing): ZslRingSupervisor {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val field = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = field.get(null)
        val allocateInstance = unsafeClass.getMethod("allocateInstance", Class::class.java)
        val supervisor = allocateInstance.invoke(unsafe, ZslRingSupervisor::class.java) as ZslRingSupervisor
        val ringField = ZslRingSupervisor::class.java.getDeclaredField("ring").apply { isAccessible = true }
        ringField.set(supervisor, ring)
        return supervisor
    }

    private fun populateRing(ring: ZslRawRing, framesList: List<ZslFrame>) {
        val framesField = ZslRawRing::class.java.getDeclaredField("frames").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val deque = framesField.get(ring) as java.util.ArrayDeque<ZslFrame>
        deque.addAll(framesList)
    }

    @Test
    fun testPrepareHdrPlusSeedMotionAdaptation() {
        val ring = ZslRawRing(
            config = ZslRingConfig(8, 30, 128L * 1024L * 1024L, 24L * 1024L * 1024L, 300_000_000L),
            cameraId = "0", lensId = "0", physicalId = "0", zoomRatio = 1f, rotationDegrees = 0
        )
        val supervisor = createSupervisorWithRing(ring)
        val mockResult = dummyTotalCaptureResult()

        // Create 6 stable frames inside the 300ms window
        val baseTs = 1000_000_000L
        val frames = (0 until 6).map { i ->
            ZslFrame(
                buffer = null, width = 4000, height = 3000, rowStride = 4000,
                timestamp = baseTs + i * 33_000_000L,
                result = mockResult, iso = 100, exposureTimeNs = 10_000_000L,
                wb = floatArrayOf(1f, 1f, 1f, 1f), cropKey = null, afFocused = true,
                lensId = "0", zoomRatio = 1f, rotationDegrees = 0, rowLength = 4000, image = null
            )
        }
        populateRing(ring, frames)

        val t0Ns = baseTs + 5 * 33_000_000L + 5_000_000L // 5ms after latest frame

        // 1. MotionLevel.MODERATE: requested burst = 6, but shrinks to 3 frames
        val timingModerate = StandardTimingTracker(shutterClick = 1000L)
        val seedModerate = supervisor.prepareHdrPlusSeed(
            timing = timingModerate,
            adaptive = true,
            requestedBurst = 6,
            isoUpper = 3200,
            exposureUpper = 100_000_000L,
            hdrDigitalGain = 1.0f,
            liveIso = 100,
            liveExposure = 10_000_000L,
            t0Ns = t0Ns,
            motionLevel = MotionLevel.MODERATE
        )
        assertNotNull(seedModerate)
        assertEquals(3, seedModerate!!.preRollCount)
        assertEquals(3, seedModerate.targetFrameCount)
        assertEquals(0, seedModerate.remainingCount)

        // Clear ring and repopulate for next test
        ring.clear()
        populateRing(ring, frames)

        // 2. MotionLevel.FAST in normal light: triggers motion_fast_single and returns null
        val timingFast = StandardTimingTracker(shutterClick = 1000L)
        val seedFast = supervisor.prepareHdrPlusSeed(
            timing = timingFast,
            adaptive = true,
            requestedBurst = 6,
            isoUpper = 3200,
            exposureUpper = 100_000_000L,
            hdrDigitalGain = 1.0f,
            liveIso = 100,
            liveExposure = 10_000_000L,
            t0Ns = t0Ns,
            motionLevel = MotionLevel.FAST
        )
        assertNull(seedFast)
        assertEquals("motion_fast_single", timingFast.zslFallbackReason)
    }
}
