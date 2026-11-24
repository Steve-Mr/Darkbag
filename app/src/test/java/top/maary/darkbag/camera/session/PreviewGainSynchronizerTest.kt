package top.maary.darkbag.camera.session

import android.hardware.camera2.CaptureResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowTotalCaptureResult

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PreviewGainSynchronizerTest {

    @Test
    fun `steadyStateTracksEv0ExposureAndResolvesTargetGain`() {
        val sync = PreviewGainSynchronizer()
        val g1 = sync.onRepeatingCaptureResult(
            timestampNs = 1_000L,
            iso = 100,
            exposureTimeNs = 10_000_000L,
            aeCompSteps = 0,
            aeState = CaptureResult.CONTROL_AE_STATE_CONVERGED
        )
        assertEquals(1.0f, g1, 1e-4f)
        assertEquals(1.0f, sync.resolveGainForTimestamp(1_000L), 1e-4f)
        assertEquals(1.0f, sync.currentEffectiveGain, 1e-4f)
    }

    @Test
    fun `hdrPlusOnTransitionHoldsStartGainUntilAeCompTakesEffectAndRampsWithHardwareExposure`() {
        val sync = PreviewGainSynchronizer()
        // Baseline 0-EV exposure: ISO 100 * 10ms = 1e9
        sync.onRepeatingCaptureResult(
            timestampNs = 100L,
            iso = 100,
            exposureTimeNs = 10_000_000L,
            aeCompSteps = 0,
            aeState = CaptureResult.CONTROL_AE_STATE_CONVERGED,
            nowNs = 100L
        )

        // Arm HDR+ ZSL (-1 EV -> targetGain = 2.0f, expectedAeCompSteps = -3)
        sync.beginAeTransition(newTargetGain = 2.0f, expectedAeCompSteps = -3, nowNs = 200L)
        // Must NOT jump immediately!
        assertEquals(1.0f, sync.currentEffectiveGain, 1e-4f)

        // Frame with old aeComp=0 still in pipeline -> must stay at 1.0f
        val preCompGain = sync.onRepeatingCaptureResult(
            timestampNs = 200L,
            iso = 100,
            exposureTimeNs = 10_000_000L,
            aeCompSteps = 0,
            aeState = CaptureResult.CONTROL_AE_STATE_SEARCHING,
            nowNs = 210L
        )
        assertEquals(1.0f, preCompGain, 1e-4f)

        // Frame with aeComp=-3, exposure ramped halfway (ISO 100 * 7ms = 7e8 -> 1e9/7e8 = 1.428f)
        val midGain = sync.onRepeatingCaptureResult(
            timestampNs = 300L,
            iso = 100,
            exposureTimeNs = 7_000_000L,
            aeCompSteps = -3,
            aeState = CaptureResult.CONTROL_AE_STATE_SEARCHING,
            nowNs = 240L
        )
        assertEquals(10f / 7f, midGain, 0.02f)

        // Frame with aeComp=-3, exposure reached -1 EV (ISO 100 * 5ms = 5e8 -> 1e9/5e8 = 2.0f) -> converges
        val convergedGain = sync.onRepeatingCaptureResult(
            timestampNs = 400L,
            iso = 100,
            exposureTimeNs = 5_000_000L,
            aeCompSteps = -3,
            aeState = CaptureResult.CONTROL_AE_STATE_SEARCHING,
            nowNs = 270L
        )
        assertEquals(2.0f, convergedGain, 1e-4f)
        assertEquals(2.0f, sync.resolveGainForTimestamp(400L), 1e-4f)
        assertEquals(1.0f, sync.resolveGainForTimestamp(200L), 1e-4f)
    }

    @Test
    fun `hdrPlusOffTransitionDoesNotDipDarkWhileHardwareAeRampsUp`() {
        val sync = PreviewGainSynchronizer()
        // Start steady in HDR+ (-1 EV, gain 2.0x)
        sync.beginAeTransition(newTargetGain = 2.0f, expectedAeCompSteps = -3, nowNs = 100L)
        repeat(PreviewGainSynchronizer.MAX_TRANSITION_FRAMES) { idx ->
            sync.onRepeatingCaptureResult(
                timestampNs = 100L + idx,
                iso = 100,
                exposureTimeNs = 5_000_000L,
                aeCompSteps = -3,
                aeState = CaptureResult.CONTROL_AE_STATE_CONVERGED,
                nowNs = 100L + idx * 33_000_000L
            )
        }
        assertEquals(2.0f, sync.currentEffectiveGain, 1e-4f)

        // Switch off HDR+ (targetGain = 1.0f, expectedAeCompSteps = 0)
        sync.beginAeTransition(newTargetGain = 1.0f, expectedAeCompSteps = 0, nowNs = 2_000_000_000L)
        assertEquals(2.0f, sync.currentEffectiveGain, 1e-4f)

        // Old -1 EV frame still in flight -> holds at 2.0f
        val oldFrameGain = sync.onRepeatingCaptureResult(
            timestampNs = 500L,
            iso = 100,
            exposureTimeNs = 5_000_000L,
            aeCompSteps = -3,
            aeState = CaptureResult.CONTROL_AE_STATE_SEARCHING,
            nowNs = 2_030_000_000L
        )
        assertEquals(2.0f, oldFrameGain, 1e-4f)

        // AE ramps up to 0 EV (ISO 100 * 10ms) -> resolves to 1.0f
        val restoredGain = sync.onRepeatingCaptureResult(
            timestampNs = 600L,
            iso = 100,
            exposureTimeNs = 10_000_000L,
            aeCompSteps = 0,
            aeState = CaptureResult.CONTROL_AE_STATE_CONVERGED,
            nowNs = 2_100_000_000L
        )
        assertEquals(1.0f, restoredGain, 1e-4f)
    }

    @Test
    fun `burstFramesAreIsolatedFromSteadyStateAndResolvePerTimestamp`() {
        val sync = PreviewGainSynchronizer()
        sync.onRepeatingCaptureResult(
            timestampNs = 1_000L,
            iso = 200,
            exposureTimeNs = 10_000_000L,
            aeCompSteps = 0,
            aeState = CaptureResult.CONTROL_AE_STATE_CONVERGED
        )

        val burstGain = sync.computeBurstPreviewGain(
            curIso = 200,
            curExposureTimeNs = 10_000_000L,
            burstIso = 100,
            burstExposureTimeNs = 5_000_000L,
            isManualExposure = false
        )
        assertEquals(4.0f, burstGain, 1e-4f)

        sync.registerBurstFrame(timestampNs = 2_000L, gain = burstGain)
        // Repeating callback seeing the burst frame timestamp returns burstGain without corrupting steady gain
        val resultGain = sync.onRepeatingCaptureResult(
            timestampNs = 2_000L,
            iso = 100,
            exposureTimeNs = 5_000_000L,
            aeCompSteps = 0,
            aeState = CaptureResult.CONTROL_AE_STATE_LOCKED
        )
        assertEquals(4.0f, resultGain, 1e-4f)
        assertEquals(1.0f, sync.currentEffectiveGain, 1e-4f)
        assertEquals(4.0f, sync.resolveGainForTimestamp(2_000L), 1e-4f)
        assertEquals(1.0f, sync.resolveGainForTimestamp(1_000L), 1e-4f)
    }

    @Test
    fun `resolveGainForRawFramePeeksResultDuringAeTransitionBeforeRepeatingCallbackRuns`() {
        val sync = PreviewGainSynchronizer()
        sync.onRepeatingCaptureResult(
            timestampNs = 100L,
            iso = 100,
            exposureTimeNs = 10_000_000L,
            aeCompSteps = 0,
            aeState = CaptureResult.CONTROL_AE_STATE_CONVERGED,
            nowNs = 100L
        )
        sync.beginAeTransition(newTargetGain = 2.0f, expectedAeCompSteps = -3, nowNs = 200L)

        val result = ShadowTotalCaptureResult.newTotalCaptureResult()
        Shadows.shadowOf(result).set(CaptureResult.SENSOR_TIMESTAMP, 300L)
        Shadows.shadowOf(result).set(CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION, -3)
        Shadows.shadowOf(result).set(CaptureResult.SENSOR_SENSITIVITY, 100)
        Shadows.shadowOf(result).set(CaptureResult.SENSOR_EXPOSURE_TIME, 5_000_000L)

        val rawGain = sync.resolveGainForRawFrame(300L, result)
        assertEquals(2.0f, rawGain, 1e-4f)
        // Non-mutating peek: currentEffectiveGain remains 1.0f until repeating callback processes it
        assertTrue(sync.currentEffectiveGain < 1.05f)
    }

    @Test
    fun `transitionTimesOutCleanlyToTargetGain`() {
        val sync = PreviewGainSynchronizer()
        sync.beginAeTransition(newTargetGain = 2.0f, expectedAeCompSteps = -3, nowNs = 0L)
        val timedOutGain = sync.onRepeatingCaptureResult(
            timestampNs = 999L,
            iso = null,
            exposureTimeNs = null,
            aeCompSteps = 0,
            aeState = null,
            nowNs = PreviewGainSynchronizer.MAX_TRANSITION_DURATION_NS + 1L
        )
        assertEquals(2.0f, timedOutGain, 1e-4f)
        assertEquals(2.0f, sync.currentEffectiveGain, 1e-4f)
    }
}
