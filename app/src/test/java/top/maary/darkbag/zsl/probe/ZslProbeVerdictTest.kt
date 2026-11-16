package top.maary.darkbag.zsl.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.maary.darkbag.camera.zsl.RingConfig
import top.maary.darkbag.camera.zsl.probe.ZslProbeFailure
import top.maary.darkbag.camera.zsl.probe.ZslProbeSample
import top.maary.darkbag.camera.zsl.probe.ZslProbeScoring
import top.maary.darkbag.camera.zsl.probe.ZslProbeVerdict

/**
 * 阈值表（§3.8.4）表驱动测试：每条阈值两侧各一例（边界含等号）、三档优先级、
 * 失败码集合、推荐环参数派生（含预热未知与 fps 倒挂保护）。
 *
 * 统一取标称帧间隔 30 ms，使 1.5×/1.8×/4×/6× 阈值都是精确二进制浮点，避免边界抖动。
 */
class ZslProbeVerdictTest {

    private fun sample(
        rawFps: Float = 30f,
        gapP50Ms: Float = 30f,
        gapP95Ms: Float = 40f,
        gapMaxMs: Float = 80f,
        ageP50Ms: Float = 40f,
        ageP95Ms: Float = 60f,
        copyP50Ms: Float = 2f,
        copyP95Ms: Float = 4f,
        previewFpsBaseline: Float = 30f,
        previewFpsDual: Float = 30f,
        previewDropRatio: Float = 0f,
        metadataHitRate: Float = 1f,
        warmupMs: Long? = 100L,
        thermalStatusBefore: Int = 0,
        thermalStatusAfter: Int = 1,
        memoryDeltaBytes: Long = 0L,
        captureFailures: Int = 0
    ) = ZslProbeSample(
        rawFrameCount = 120,
        rawFps = rawFps,
        gapP50Ms = gapP50Ms,
        gapP95Ms = gapP95Ms,
        gapMaxMs = gapMaxMs,
        ageP50Ms = ageP50Ms,
        ageP95Ms = ageP95Ms,
        copyP50Ms = copyP50Ms,
        copyP95Ms = copyP95Ms,
        previewFpsBaseline = previewFpsBaseline,
        previewFpsDual = previewFpsDual,
        previewDropRatio = previewDropRatio,
        metadataHitRate = metadataHitRate,
        warmupMs = warmupMs,
        thermalStatusBefore = thermalStatusBefore,
        thermalStatusAfter = thermalStatusAfter,
        memoryDeltaBytes = memoryDeltaBytes,
        captureFailures = captureFailures
    )

    private fun evaluate(sample: ZslProbeSample) = ZslProbeScoring.evaluate(sample, 30f)

    @Test
    fun testAllMetricsExactlyAtRecommendedBoundaryIsRecommended() {
        val outcome = evaluate(
            sample(
                rawFps = 24f,
                gapP95Ms = 45f,
                gapMaxMs = 120f,
                ageP95Ms = 100f,
                copyP95Ms = 6f,
                previewDropRatio = 0.10f,
                metadataHitRate = 0.99f,
                warmupMs = 200L,
                thermalStatusAfter = 1,
                captureFailures = 0
            )
        )

        assertEquals(ZslProbeVerdict.RECOMMENDED, outcome.verdict)
        assertTrue(outcome.failures.isEmpty())
        assertEquals(RingConfig(3, 24, 2), outcome.ringConfig)
    }

    @Test
    fun testRawFpsAt24IsRecommendedAndJustBelowDropsToOneTier() {
        assertEquals(ZslProbeVerdict.RECOMMENDED, evaluate(sample(rawFps = 24f)).verdict)
        assertEquals(ZslProbeVerdict.SUPPORTED, evaluate(sample(rawFps = 23.9f)).verdict)
    }

    @Test
    fun testRawFpsBelowSupportedThresholdFails() {
        assertEquals(ZslProbeVerdict.SUPPORTED, evaluate(sample(rawFps = 15f)).verdict)
        val outcome = evaluate(sample(rawFps = 14.9f))
        assertEquals(ZslProbeVerdict.UNSUPPORTED, outcome.verdict)
        assertEquals(listOf(ZslProbeFailure.RAW_FPS_LOW), outcome.failures)
        assertEquals(RingConfig.DISABLED, outcome.ringConfig)
    }

    @Test
    fun testGapP95JitterBoundary() {
        assertEquals(ZslProbeVerdict.RECOMMENDED, evaluate(sample(gapP95Ms = 45f)).verdict)
        assertEquals(ZslProbeVerdict.SUPPORTED, evaluate(sample(gapP95Ms = 45.1f)).verdict)
    }

    @Test
    fun testGapP95AboveSupportedThresholdFails() {
        assertEquals(ZslProbeVerdict.SUPPORTED, evaluate(sample(gapP95Ms = 54f)).verdict)
        val outcome = evaluate(sample(gapP95Ms = 54.1f))
        assertEquals(ZslProbeVerdict.UNSUPPORTED, outcome.verdict)
        assertEquals(listOf(ZslProbeFailure.RAW_JITTER), outcome.failures)
    }

    @Test
    fun testGapMaxStallBoundary() {
        assertEquals(ZslProbeVerdict.RECOMMENDED, evaluate(sample(gapMaxMs = 120f)).verdict)
        assertEquals(ZslProbeVerdict.SUPPORTED, evaluate(sample(gapMaxMs = 120.1f)).verdict)
        assertEquals(ZslProbeVerdict.SUPPORTED, evaluate(sample(gapMaxMs = 180f)).verdict)
    }

    @Test
    fun testGapMaxAboveSupportedThresholdFails() {
        val outcome = evaluate(sample(gapMaxMs = 180.1f))
        assertEquals(ZslProbeVerdict.UNSUPPORTED, outcome.verdict)
        assertEquals(listOf(ZslProbeFailure.RAW_STALL), outcome.failures)
    }

    @Test
    fun testFrameAgeP95Boundary() {
        assertEquals(ZslProbeVerdict.RECOMMENDED, evaluate(sample(ageP95Ms = 100f)).verdict)
        assertEquals(ZslProbeVerdict.SUPPORTED, evaluate(sample(ageP95Ms = 100.1f)).verdict)
        assertEquals(ZslProbeVerdict.SUPPORTED, evaluate(sample(ageP95Ms = 140f)).verdict)
        val outcome = evaluate(sample(ageP95Ms = 140.1f))
        assertEquals(ZslProbeVerdict.UNSUPPORTED, outcome.verdict)
        assertEquals(listOf(ZslProbeFailure.FRAME_AGE_HIGH), outcome.failures)
    }

    @Test
    fun testCopyP95Boundary() {
        assertEquals(ZslProbeVerdict.RECOMMENDED, evaluate(sample(copyP95Ms = 6f)).verdict)
        assertEquals(ZslProbeVerdict.SUPPORTED, evaluate(sample(copyP95Ms = 6.1f)).verdict)
        assertEquals(ZslProbeVerdict.SUPPORTED, evaluate(sample(copyP95Ms = 12f)).verdict)
        val outcome = evaluate(sample(copyP95Ms = 12.1f))
        assertEquals(ZslProbeVerdict.UNSUPPORTED, outcome.verdict)
        assertEquals(listOf(ZslProbeFailure.COPY_SLOW), outcome.failures)
    }

    @Test
    fun testPreviewDropRatioBoundary() {
        assertEquals(ZslProbeVerdict.RECOMMENDED, evaluate(sample(previewDropRatio = 0.10f)).verdict)
        assertEquals(ZslProbeVerdict.SUPPORTED, evaluate(sample(previewDropRatio = 0.11f)).verdict)
        assertEquals(ZslProbeVerdict.SUPPORTED, evaluate(sample(previewDropRatio = 0.20f)).verdict)
        val outcome = evaluate(sample(previewDropRatio = 0.21f))
        assertEquals(ZslProbeVerdict.UNSUPPORTED, outcome.verdict)
        assertEquals(listOf(ZslProbeFailure.PREVIEW_DEGRADED), outcome.failures)
    }

    @Test
    fun testMetadataHitRateBoundary() {
        assertEquals(ZslProbeVerdict.RECOMMENDED, evaluate(sample(metadataHitRate = 0.99f)).verdict)
        assertEquals(ZslProbeVerdict.SUPPORTED, evaluate(sample(metadataHitRate = 0.98f)).verdict)
        assertEquals(ZslProbeVerdict.SUPPORTED, evaluate(sample(metadataHitRate = 0.97f)).verdict)
        val outcome = evaluate(sample(metadataHitRate = 0.96f))
        assertEquals(ZslProbeVerdict.UNSUPPORTED, outcome.verdict)
        assertEquals(listOf(ZslProbeFailure.METADATA_MISS), outcome.failures)
    }

    @Test
    fun testWarmupBoundaryAndDerivedRingDepth() {
        val recommended = evaluate(sample(warmupMs = 200L))
        assertEquals(ZslProbeVerdict.RECOMMENDED, recommended.verdict)
        assertEquals(3, recommended.ringConfig.depth)

        val limited = evaluate(sample(warmupMs = 201L))
        assertEquals(ZslProbeVerdict.SUPPORTED, limited.verdict)
        assertEquals(2, limited.ringConfig.depth)

        assertEquals(ZslProbeVerdict.SUPPORTED, evaluate(sample(warmupMs = 400L)).verdict)

        val outcome = evaluate(sample(warmupMs = 401L))
        assertEquals(ZslProbeVerdict.UNSUPPORTED, outcome.verdict)
        assertEquals(listOf(ZslProbeFailure.WARMUP_SLOW), outcome.failures)
    }

    @Test
    fun testUnknownWarmupIsNotAFailureButCapsAtSupported() {
        val outcome = evaluate(sample(warmupMs = null))

        assertEquals(ZslProbeVerdict.SUPPORTED, outcome.verdict)
        assertTrue(outcome.failures.isEmpty())
        assertEquals(2, outcome.ringConfig.depth)
        assertEquals(RingConfig(2, 24, 2), outcome.ringConfig)
    }

    @Test
    fun testThermalStatusBoundary() {
        assertEquals(ZslProbeVerdict.RECOMMENDED, evaluate(sample(thermalStatusAfter = 1)).verdict)
        assertEquals(ZslProbeVerdict.SUPPORTED, evaluate(sample(thermalStatusAfter = 2)).verdict)
        val outcome = evaluate(sample(thermalStatusAfter = 3))
        assertEquals(ZslProbeVerdict.UNSUPPORTED, outcome.verdict)
        assertEquals(listOf(ZslProbeFailure.THERMAL_RISK), outcome.failures)
    }

    @Test
    fun testCaptureFailuresBoundary() {
        assertEquals(ZslProbeVerdict.RECOMMENDED, evaluate(sample(captureFailures = 0)).verdict)
        assertEquals(ZslProbeVerdict.SUPPORTED, evaluate(sample(captureFailures = 1)).verdict)
        val outcome = evaluate(sample(captureFailures = 2))
        assertEquals(ZslProbeVerdict.UNSUPPORTED, outcome.verdict)
        assertEquals(listOf(ZslProbeFailure.CAPTURE_FAILED), outcome.failures)
    }

    @Test
    fun testOnlySupportedTierViolationsYieldSupportedWithNoFailures() {
        val outcome = evaluate(
            sample(
                rawFps = 20f,
                gapP95Ms = 50f,
                gapMaxMs = 150f,
                ageP95Ms = 120f,
                copyP95Ms = 8f,
                previewDropRatio = 0.15f,
                metadataHitRate = 0.98f,
                warmupMs = 300L,
                thermalStatusAfter = 2,
                captureFailures = 1
            )
        )

        assertEquals(ZslProbeVerdict.SUPPORTED, outcome.verdict)
        assertTrue(outcome.failures.isEmpty())
    }

    @Test
    fun testAllViolationsAreReportedExactlyOnce() {
        val outcome = evaluate(
            sample(
                rawFps = 10f,
                gapP95Ms = 100f,
                gapMaxMs = 500f,
                ageP95Ms = 200f,
                copyP95Ms = 20f,
                previewDropRatio = 0.5f,
                metadataHitRate = 0.5f,
                warmupMs = 500L,
                thermalStatusAfter = 5,
                captureFailures = 3
            )
        )

        assertEquals(ZslProbeVerdict.UNSUPPORTED, outcome.verdict)
        assertEquals(
            setOf(
                ZslProbeFailure.RAW_FPS_LOW,
                ZslProbeFailure.RAW_JITTER,
                ZslProbeFailure.RAW_STALL,
                ZslProbeFailure.FRAME_AGE_HIGH,
                ZslProbeFailure.COPY_SLOW,
                ZslProbeFailure.PREVIEW_DEGRADED,
                ZslProbeFailure.METADATA_MISS,
                ZslProbeFailure.WARMUP_SLOW,
                ZslProbeFailure.THERMAL_RISK,
                ZslProbeFailure.CAPTURE_FAILED
            ),
            outcome.failures.toSet()
        )
        assertEquals(ZslProbeFailure.entries.size, outcome.failures.size)
        assertEquals(RingConfig.DISABLED, outcome.ringConfig)
    }

    @Test
    fun testRecommendedRingConfigUsesHighFpsAndMinimumTwoValidFrames() {
        val ring = ZslProbeScoring.recommendedRingConfig(sample(rawFps = 100f, warmupMs = 50L))

        assertEquals(RingConfig(3, 24, 2), ring)
    }

    @Test
    fun testRecommendedRingConfigClampsTargetFpsToMeasuredFpsToAvoidInversion() {
        // 15/24 的推荐值高于实测 14.9 fps 时必须夹到 14，避免"推荐帧率 > 实测帧率"倒挂。
        val ring = ZslProbeScoring.recommendedRingConfig(sample(rawFps = 14.9f))

        assertEquals(14, ring.targetFps)
        assertEquals(2, ring.minValidFrames)
        assertTrue(ring.depth == 2 || ring.depth == 3)
    }

    @Test
    fun testLowFpsAndLongWarmupDeriveDepthTwo() {
        val ring = ZslProbeScoring.recommendedRingConfig(sample(rawFps = 20f, warmupMs = 350L))

        assertEquals(2, ring.depth)
        assertEquals(15, ring.targetFps)
    }
}
