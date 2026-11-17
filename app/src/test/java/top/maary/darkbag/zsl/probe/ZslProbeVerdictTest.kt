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
        copyThroughputP5MiBps: Float = 2000f,
        copyThroughputP50MiBps: Float = 2500f,
        copyThroughputMeasured: Boolean = true,
        copyFrameBytes: Long = 24L * 1024 * 1024,
        previewFpsBaseline: Float = 30f,
        previewFpsDual: Float = 30f,
        previewDropRatio: Float = 0f,
        metadataHitRate: Float = 1f,
        warmupMs: Long? = 100L,
        thermalStatusBefore: Int? = 0,
        thermalStatusAfter: Int? = 1,
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
        copyThroughputP5MiBps = copyThroughputP5MiBps,
        copyThroughputP50MiBps = copyThroughputP50MiBps,
        copyThroughputMeasured = copyThroughputMeasured,
        copyFrameBytes = copyFrameBytes,
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
                copyThroughputP5MiBps = 1800f,
                previewDropRatio = 0.10f,
                metadataHitRate = 0.99f,
                warmupMs = 200L,
                thermalStatusAfter = 1,
                captureFailures = 0
            )
        )

        assertEquals(ZslProbeVerdict.RECOMMENDED, outcome.verdict)
        assertTrue(outcome.failures.isEmpty())
        // R6 定稿：rawFps ≥ 24 → 24（不再被 floor(24/5)*5 = 20 降到 20）
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

    /**
     * 拷贝判据为**吞吐口径**（S1-FIX-R2）：RECOMMENDED ≥1800 MiB/s、SUPPORTED ≥900 MiB/s，
     * 边界含等号。绝对耗时分位 `copyP95Ms` 不再参与判定——即使耗时很低，吞吐不足仍须判失败。
     */
    @Test
    fun testCopyThroughputBoundary() {
        assertEquals(ZslProbeVerdict.RECOMMENDED, evaluate(sample(copyThroughputP5MiBps = 1800f)).verdict)
        assertEquals(ZslProbeVerdict.SUPPORTED, evaluate(sample(copyThroughputP5MiBps = 1799f)).verdict)
        assertEquals(ZslProbeVerdict.SUPPORTED, evaluate(sample(copyThroughputP5MiBps = 900f)).verdict)
        val outcome = evaluate(sample(copyThroughputP5MiBps = 899f))
        assertEquals(ZslProbeVerdict.UNSUPPORTED, outcome.verdict)
        assertEquals(listOf(ZslProbeFailure.COPY_SLOW), outcome.failures)
    }

    @Test
    fun testCopyLatencyNoLongerGatesVerdict() {
        // 旧口径下 copyP95Ms=15.15 会判 UNSUPPORTED；新口径只看吞吐，耗时分位仅入报告。
        assertEquals(ZslProbeVerdict.RECOMMENDED, evaluate(sample(copyP95Ms = 15.15f)).verdict)
        assertEquals(ZslProbeVerdict.RECOMMENDED, evaluate(sample(copyP95Ms = 999f)).verdict)
    }

    @Test
    fun testMeasuredThroughputOfZeroIsCopySlow() {
        // 真的测得 0 MiB/s（measured=true）属于"已知不可行"。
        val outcome = evaluate(
            sample(copyThroughputP5MiBps = 0f, copyThroughputMeasured = true)
        )
        assertEquals(ZslProbeVerdict.UNSUPPORTED, outcome.verdict)
        assertEquals(listOf(ZslProbeFailure.COPY_SLOW), outcome.failures)
    }

    /**
     * 未测到吞吐（能力读取失败 -> 字节数未知）必须报 `COPY_UNMEASURED` 而非 `COPY_SLOW`：
     * 这是"阈值未知"，不该指责拷贝慢（S1-FIX-R2 复审 MAJOR-3）。结论仍是 UNSUPPORTED。
     */
    @Test
    fun testUnmeasuredThroughputIsCopyUnmeasuredNotCopySlow() {
        val outcome = evaluate(
            sample(copyThroughputP5MiBps = 0f, copyThroughputMeasured = false)
        )
        assertEquals(ZslProbeVerdict.UNSUPPORTED, outcome.verdict)
        assertEquals(listOf(ZslProbeFailure.COPY_UNMEASURED), outcome.failures)
    }

    @Test
    fun testUnmeasuredCopyIsReportedEvenWhenThroughputValueLooksFine() {
        // 标记为未测到时，即便携带的数值看起来达标，也不可采信。
        val outcome = evaluate(
            sample(copyThroughputP5MiBps = 5000f, copyThroughputMeasured = false)
        )
        assertEquals(ZslProbeVerdict.UNSUPPORTED, outcome.verdict)
        assertEquals(listOf(ZslProbeFailure.COPY_UNMEASURED), outcome.failures)
    }

    /**
     * 真机回归（S1-FIX-R2）：moto XT2409-5 / lens 0 / 4096×3072 实测报告。
     *
     * 原始样本（省略未参与判定的字段）：rawFps 29.5、gapP95 33.479、gapMax 33.479、
     * ageP95 78.58、copyP50 9 ms、copyP95 15.15 ms、previewFpsBaseline 26.67、previewFpsDual 29.25、
     * metadataHitRate 1.0、warmupMs 225、thermal 0/0、captureFailures 0。
     *
     * 旧口径（copyP95 ≤12 ms）判为 UNSUPPORTED / COPY_SLOW，但 24 MiB / 15.15 ms ≈ 1584 MiB/s
     * 已接近内存带宽上限，属于正常表现。新吞吐口径下该机应判 SUPPORTED（预热 225 > 200 使其
     * 无法达到 RECOMMENDED），环深度 2。
     */
    @Test
    fun testMotoXt2409RealReportIsSupportedNotCopySlow() {
        val outcome = evaluate(
            sample(
                rawFps = 29.5f,
                gapP50Ms = 33.479f,
                gapP95Ms = 33.479f,
                gapMaxMs = 33.479f,
                ageP50Ms = 67.987f,
                ageP95Ms = 78.582f,
                copyP50Ms = 9f,
                copyP95Ms = 15.15f,
                copyThroughputP5MiBps = 1584f,
                copyThroughputP50MiBps = 2666f,
                previewFpsBaseline = 26.667f,
                previewFpsDual = 29.25f,
                previewDropRatio = 0f,
                metadataHitRate = 1f,
                warmupMs = 225L,
                thermalStatusBefore = 0,
                thermalStatusAfter = 0,
                captureFailures = 0
            )
        )

        assertEquals(ZslProbeVerdict.SUPPORTED, outcome.verdict)
        assertTrue(outcome.failures.isEmpty())
        assertEquals(RingConfig(2, 24, 2), outcome.ringConfig)
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
    fun testUnknownThermalStatusCapsAtSupportedWithoutFailureCodes() {
        // F1：热读失败/API<29 → null，与 warmupMs == null 同构：
        // 不否定设备（不产生 THERMAL_RISK 失败码），但不足以判 RECOMMENDED。
        val outcome = evaluate(sample(thermalStatusAfter = null))

        assertEquals(ZslProbeVerdict.SUPPORTED, outcome.verdict)
        assertTrue(outcome.failures.isEmpty())
        assertEquals(RingConfig(3, 24, 2), outcome.ringConfig)

        // SUPPORTED 档不受影响：即使其它指标只到 SUPPORTED 档，也仍是 SUPPORTED 且无失败码
        val limited = evaluate(sample(thermalStatusAfter = null, rawFps = 15f, warmupMs = 300L))
        assertEquals(ZslProbeVerdict.SUPPORTED, limited.verdict)
        assertTrue(limited.failures.isEmpty())
    }

    @Test
    fun testFifteenFpsNominalIntervalIsNotJudgedAsJitter() {
        // F6：targetFps=15 → 标称 1000/15 ≈ 66.67 ms；实测 gapP95 66.7 ms 属正常，
        // 不得被判 RAW_JITTER / RAW_STALL（旧实现写死 30 fps 标称时会误判）。
        val outcome = ZslProbeScoring.evaluate(
            sample(
                rawFps = 15f,
                gapP50Ms = 66.7f,
                gapP95Ms = 66.7f,
                gapMaxMs = 66.7f,
                ageP95Ms = 60f,
                copyP95Ms = 4f,
                warmupMs = 100L,
                thermalStatusAfter = 1
            ),
            1000f / 15f
        )

        assertEquals(ZslProbeVerdict.SUPPORTED, outcome.verdict)
        assertTrue(outcome.failures.isEmpty())
        assertEquals(RingConfig(3, 15, 2), outcome.ringConfig)
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
                copyThroughputP5MiBps = 100f,
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
        // 每个失败码恰好一次（R3 新增的 PC/RAW 两个码不在阈值评分表内，故按 10 个评分码计数）
        assertEquals(10, outcome.failures.size)
        assertEquals(RingConfig.DISABLED, outcome.ringConfig)
    }

    @Test
    fun testRecommendedRingConfigUsesHighFpsAndMinimumTwoValidFrames() {
        val ring = ZslProbeScoring.recommendedRingConfig(sample(rawFps = 100f, warmupMs = 50L))

        assertEquals(RingConfig(3, 24, 2), ring)
    }

    @Test
    fun testRecommendedRingConfigClampsTargetFpsToMeasuredFpsToAvoidInversion() {
        // R6：14.9 fps < 15 → floor(14.9/5)*5 = 10（不再是 15 档），避免"推荐帧率 > 实测帧率"倒挂。
        val ring = ZslProbeScoring.recommendedRingConfig(sample(rawFps = 14.9f))

        assertEquals(10, ring.targetFps)
        assertEquals(2, ring.minValidFrames)
        // warmupMs 默认 100 ≤ 200 → 深度确定为 3（不再断言"2 或 3"这种恒真条件）
        assertEquals(3, ring.depth)
    }

    /**
     * R6 定稿公式（设计 §3.8.4）五档钉死：
     * `≥24 → 24`；`15–23.9 → 15`；`<15 → floor(rawFps/5)*5`，下限 5。
     */
    @Test
    fun testRecommendedTargetFpsPinnedAcrossFiveTiers() {
        assertEquals(24, ZslProbeScoring.recommendedRingConfig(sample(rawFps = 24f)).targetFps)
        assertEquals(15, ZslProbeScoring.recommendedRingConfig(sample(rawFps = 23.9f)).targetFps)
        assertEquals(10, ZslProbeScoring.recommendedRingConfig(sample(rawFps = 14.9f)).targetFps)
        // 下限至少 5：极慢设备也不会推荐 0/负数帧率
        assertEquals(5, ZslProbeScoring.recommendedRingConfig(sample(rawFps = 4f)).targetFps)
        assertEquals(24, ZslProbeScoring.recommendedRingConfig(sample(rawFps = 100f)).targetFps)
    }

    @Test
    fun testRecommendedTargetFpsFollowsFloorStepFormula() {
        assertEquals(10, ZslProbeScoring.recommendedRingConfig(sample(rawFps = 14.9f)).targetFps)
        assertEquals(10, ZslProbeScoring.recommendedRingConfig(sample(rawFps = 12f)).targetFps)
        assertEquals(15, ZslProbeScoring.recommendedRingConfig(sample(rawFps = 15f)).targetFps)
        assertEquals(15, ZslProbeScoring.recommendedRingConfig(sample(rawFps = 20f)).targetFps)
        assertEquals(24, ZslProbeScoring.recommendedRingConfig(sample(rawFps = 24f)).targetFps)
        assertEquals(24, ZslProbeScoring.recommendedRingConfig(sample(rawFps = 25f)).targetFps)
        assertEquals(24, ZslProbeScoring.recommendedRingConfig(sample(rawFps = 100f)).targetFps)
        assertEquals(5, ZslProbeScoring.recommendedRingConfig(sample(rawFps = 4f)).targetFps)
    }

    @Test
    fun testLowFpsAndLongWarmupDeriveDepthTwo() {
        val ring = ZslProbeScoring.recommendedRingConfig(sample(rawFps = 20f, warmupMs = 350L))

        assertEquals(2, ring.depth)
        assertEquals(15, ring.targetFps)
    }
}
