package top.maary.darkbag.rawvideo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RawVideoExporterTest {

    @Test
    fun testCalculateMeasuredFps_standardScenarios() {
        // 31 frames spanning exactly 1.0 second -> 30.0 fps
        val startNs = 10_000_000_000L
        val endNs = startNs + 1_000_000_000L
        val fps30 = RawVideoExporter.calculateMeasuredFps(
            totalFrames = 31,
            firstFrameNs = startNs,
            lastFrameNs = endNs,
            fallbackFps = 24.0f
        )
        assertEquals(30.0f, fps30, 0.01f)

        // 25 frames spanning exactly 1.0 second -> 24.0 fps
        val fps24 = RawVideoExporter.calculateMeasuredFps(
            totalFrames = 25,
            firstFrameNs = startNs,
            lastFrameNs = endNs,
            fallbackFps = 30.0f
        )
        assertEquals(24.0f, fps24, 0.01f)

        // 61 frames spanning exactly 2.0 seconds -> 30.0 fps
        val fps60In2Sec = RawVideoExporter.calculateMeasuredFps(
            totalFrames = 61,
            firstFrameNs = startNs,
            lastFrameNs = startNs + 2_000_000_000L,
            fallbackFps = 24.0f
        )
        assertEquals(30.0f, fps60In2Sec, 0.01f)
    }

    @Test
    fun testCalculateMeasuredFps_fallbacksAndClamping() {
        val startNs = 5_000_000_000L

        // Single frame -> returns fallback FPS
        val singleFrameFps = RawVideoExporter.calculateMeasuredFps(
            totalFrames = 1,
            firstFrameNs = startNs,
            lastFrameNs = startNs,
            fallbackFps = 29.97f
        )
        assertEquals(29.97f, singleFrameFps, 0.01f)

        // Invalid fallback FPS (<= 0) -> defaults to 24.0f
        val invalidFallback = RawVideoExporter.calculateMeasuredFps(
            totalFrames = 1,
            firstFrameNs = startNs,
            lastFrameNs = startNs,
            fallbackFps = 0.0f
        )
        assertEquals(24.0f, invalidFallback, 0.01f)

        // Non-positive time difference -> returns fallback FPS
        val backwardsTimeFps = RawVideoExporter.calculateMeasuredFps(
            totalFrames = 10,
            firstFrameNs = startNs,
            lastFrameNs = startNs - 1_000L,
            fallbackFps = 30.0f
        )
        assertEquals(30.0f, backwardsTimeFps, 0.01f)

        // Identical timestamps -> returns fallback FPS
        val identicalTimeFps = RawVideoExporter.calculateMeasuredFps(
            totalFrames = 10,
            firstFrameNs = startNs,
            lastFrameNs = startNs,
            fallbackFps = 30.0f
        )
        assertEquals(30.0f, identicalTimeFps, 0.01f)

        // Clamping upper bound: 1000 frames in 1 ms would be 1,000,000 fps -> clamped to 120.0f
        val superHighFps = RawVideoExporter.calculateMeasuredFps(
            totalFrames = 1000,
            firstFrameNs = startNs,
            lastFrameNs = startNs + 1_000_000L,
            fallbackFps = 30.0f
        )
        assertEquals(120.0f, superHighFps, 0.01f)

        // Clamping lower bound: 2 frames in 100 seconds would be 0.01 fps -> clamped to 1.0f
        val superLowFps = RawVideoExporter.calculateMeasuredFps(
            totalFrames = 2,
            firstFrameNs = startNs,
            lastFrameNs = startNs + 100_000_000_000L,
            fallbackFps = 30.0f
        )
        assertEquals(1.0f, superLowFps, 0.01f)
    }

    @Test
    fun testCalculatePtsUs_strictlyMonotonicNormal() {
        val firstNs = 1_000_000_000L
        val fallbackIntervalUs = 33_333L // ~30 fps
        var lastPtsUs = -1L

        val timestampsNs = listOf(
            firstNs,
            firstNs + 33_333_000L,
            firstNs + 66_667_000L,
            firstNs + 100_000_000L
        )

        val ptsResults = mutableListOf<Long>()
        timestampsNs.forEachIndexed { index, tsNs ->
            val pts = RawVideoExporter.calculatePtsUs(
                frameIndex = index,
                frameTsNs = tsNs,
                firstFrameNs = firstNs,
                lastPtsUs = lastPtsUs,
                fallbackIntervalUs = fallbackIntervalUs
            )
            assertTrue("PTS must strictly increase from lastPts: pts=$pts, lastPts=$lastPtsUs", pts > lastPtsUs)
            ptsResults.add(pts)
            lastPtsUs = pts
        }

        assertEquals(0L, ptsResults[0])
        assertEquals(33_333L, ptsResults[1])
        assertEquals(66_667L, ptsResults[2])
        assertEquals(100_000L, ptsResults[3])
    }

    @Test
    fun testCalculatePtsUs_jitterAndDuplicateHandling() {
        val firstNs = 1_000_000_000L
        val fallbackIntervalUs = 41_666L // ~24 fps
        var lastPtsUs = -1L

        // Sequence with:
        // Frame 0: normal
        // Frame 1: duplicate timestamp of frame 0
        // Frame 2: minor jitter forward (only 200us after frame 0)
        // Frame 3: normal
        val timestampsNs = listOf(
            firstNs,
            firstNs, // Duplicate!
            firstNs + 200_000L, // Only 200 us later (< 1000 us delta)
            firstNs + 100_000_000L // 100 ms later
        )

        val ptsResults = mutableListOf<Long>()
        timestampsNs.forEachIndexed { index, tsNs ->
            val pts = RawVideoExporter.calculatePtsUs(
                frameIndex = index,
                frameTsNs = tsNs,
                firstFrameNs = firstNs,
                lastPtsUs = lastPtsUs,
                fallbackIntervalUs = fallbackIntervalUs
            )
            if (lastPtsUs >= 0L) {
                // Must be at least 1000us greater than last PTS for MediaCodec monotonicity
                assertTrue("PTS must advance by at least 1000us: pts=$pts, lastPts=$lastPtsUs", pts >= lastPtsUs + 1000L)
            }
            ptsResults.add(pts)
            lastPtsUs = pts
        }

        assertEquals(0L, ptsResults[0])
        assertEquals(1_000L, ptsResults[1]) // Enforced +1000us over frame 0
        assertEquals(2_000L, ptsResults[2]) // Enforced +1000us over frame 1
        assertEquals(100_000L, ptsResults[3]) // Catches up to true hardware timestamp
    }

    @Test
    fun testCalculatePtsUs_fallbackWhenTimestampsMissingOrInvalid() {
        val fallbackIntervalUs = 33_333L
        var lastPtsUs = -1L

        // When firstFrameNs is 0 (missing timestamp), should use index * fallbackIntervalUs
        for (i in 0 until 5) {
            val pts = RawVideoExporter.calculatePtsUs(
                frameIndex = i,
                frameTsNs = 0L,
                firstFrameNs = 0L,
                lastPtsUs = lastPtsUs,
                fallbackIntervalUs = fallbackIntervalUs
            )
            assertEquals(i * fallbackIntervalUs, pts)
            lastPtsUs = pts
        }
    }

    @Test
    fun testEosPtsUs_greaterThanLastFrame() {
        val fallbackIntervalUs = 33_333L
        val lastPtsUs = 5_000_000L
        val eosPtsUs = (if (lastPtsUs >= 0L) lastPtsUs else 0L) + fallbackIntervalUs

        assertTrue("EOS PTS must be strictly greater than last frame PTS", eosPtsUs > lastPtsUs)
        assertEquals(5_033_333L, eosPtsUs)

        // Case when no frames were processed
        val initialLastPts = -1L
        val emptyEosPtsUs = (if (initialLastPts >= 0L) initialLastPts else 0L) + fallbackIntervalUs
        assertEquals(fallbackIntervalUs, emptyEosPtsUs)
    }
}
