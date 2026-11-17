package top.maary.darkbag.zsl.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.maary.darkbag.camera.zsl.probe.Percentiles
import top.maary.darkbag.camera.zsl.probe.ZslProbeAggregator

/**
 * 纯统计函数（§3.8.7）：分位插值、由时间戳推导帧率/间隔、帧龄/拷贝分位、预览掉帧、命中率、内存差。
 */
class ZslProbeMeasurementsTest {

    private val delta = 0.001f

    private fun aggregate(
        timestamps: List<Long> = listOf(0L),
        ages: List<Float> = listOf(10f),
        copiesNs: List<Long> = listOf(2_000_000L),
        frameBytes: Long = 24L * 1024 * 1024,
        baselineCount: Int = 30,
        dualCount: Int = 30,
        metadataHits: Int = 1,
        metadataTotal: Int = 1,
        warmupMs: Long? = 100L,
        thermalBefore: Int = 0,
        thermalAfter: Int = 1,
        memoryBefore: Long = 2_000L,
        memoryAfter: Long = 1_500L,
        captureFailures: Int = 0,
        baselineWindowMs: Long = 1_000L,
        dualWindowMs: Long = 1_000L
    ) = ZslProbeAggregator.aggregate(
        rawTimestampsNs = timestamps,
        ageSamplesMs = ages,
        copySamplesNs = copiesNs,
        frameBytes = frameBytes,
        previewBaselineCount = baselineCount,
        previewDualCount = dualCount,
        metadataHits = metadataHits,
        metadataTotal = metadataTotal,
        warmupMs = warmupMs,
        thermalBefore = thermalBefore,
        thermalAfter = thermalAfter,
        memoryBefore = memoryBefore,
        memoryAfter = memoryAfter,
        captureFailures = captureFailures,
        baselineWindowMs = baselineWindowMs,
        dualWindowMs = dualWindowMs
    )

    @Test
    fun testPercentileOfEmptyListIsZero() {
        assertEquals(0f, Percentiles.percentile(emptyList(), 0.5f), 0f)
        assertEquals(0f, Percentiles.percentile(emptyList(), 0f), 0f)
        assertEquals(0f, Percentiles.percentile(emptyList(), 1f), 0f)
    }

    @Test
    fun testPercentileOfSingleValueReturnsThatValue() {
        val values = listOf(7.5f)
        assertEquals(7.5f, Percentiles.percentile(values, 0f), 0f)
        assertEquals(7.5f, Percentiles.percentile(values, 0.5f), 0f)
        assertEquals(7.5f, Percentiles.percentile(values, 1f), 0f)
    }

    @Test
    fun testPercentileOfOddCountHitsBoundaries() {
        val values = listOf(1f, 2f, 3f)
        assertEquals(1f, Percentiles.percentile(values, 0f), 0f)
        assertEquals(2f, Percentiles.percentile(values, 0.5f), 0f)
        assertEquals(3f, Percentiles.percentile(values, 1f), 0f)
    }

    @Test
    fun testPercentileOfEvenCountInterpolatesLinearly() {
        val values = listOf(1f, 2f, 3f, 4f)
        assertEquals(2.5f, Percentiles.percentile(values, 0.5f), 0f)
        assertEquals(1.75f, Percentiles.percentile(values, 0.25f), 0f)
        assertEquals(3.25f, Percentiles.percentile(values, 0.75f), 0f)
    }

    @Test
    fun testPercentileClampsOutOfRangeProbability() {
        val values = listOf(10f, 20f)
        assertEquals(10f, Percentiles.percentile(values, -1f), 0f)
        assertEquals(20f, Percentiles.percentile(values, 2f), 0f)
    }

    @Test
    fun testAggregateComputesFpsAndGapStatsFromTimestamps() {
        val sample = aggregate(
            timestamps = listOf(0L, 33_333_333L, 66_666_666L, 100_000_000L),
            dualWindowMs = 1_000L
        )

        assertEquals(4, sample.rawFrameCount)
        assertEquals(4f, sample.rawFps, delta)
        assertEquals(33.333f, sample.gapP50Ms, 0.01f)
        assertEquals(33.333f, sample.gapP95Ms, 0.01f)
        assertEquals(33.333f, sample.gapMaxMs, 0.01f)
    }

    @Test
    fun testAggregateSortsUnorderedTimestampsBeforeGapMath() {
        val sorted = aggregate(timestamps = listOf(0L, 33_000_000L, 66_000_000L))
        val unordered = aggregate(timestamps = listOf(66_000_000L, 0L, 33_000_000L))

        assertEquals(33f, unordered.gapP50Ms, delta)
        assertEquals(33f, unordered.gapMaxMs, delta)
        assertEquals(sorted.gapP50Ms, unordered.gapP50Ms, delta)
        assertEquals(sorted.gapMaxMs, unordered.gapMaxMs, delta)
    }

    @Test
    fun testAggregateComputesAgeAndCopyPercentiles() {
        val sample = aggregate(
            ages = listOf(10f, 20f, 30f, 40f),
            copiesNs = listOf(2_000_000L, 4_000_000L)
        )

        assertEquals(25f, sample.ageP50Ms, delta)
        assertEquals(38.5f, sample.ageP95Ms, 0.01f)
        assertEquals(3f, sample.copyP50Ms, delta)
        assertEquals(3.9f, sample.copyP95Ms, 0.01f)
        // 24 MiB / 2 ms = 12288 MiB/s；24 MiB / 4 ms = 6144 MiB/s
        val mib = 24.0
        val fast = (mib / 0.002).toFloat()
        val slow = (mib / 0.004).toFloat()
        // p5（n=2）落在慢端：t=0.05*(2-1)=0.05 -> slow*0.95 + fast*0.05
        assertEquals(slow * 0.95f + fast * 0.05f, sample.copyThroughputP5MiBps, 1f)
        assertEquals((slow + fast) / 2f, sample.copyThroughputP50MiBps, 1f)
        assertEquals(24L * 1024 * 1024, sample.copyFrameBytes)
    }

    @Test
    fun testAggregateComputesPreviewFpsAndDropRatio() {
        val sample = aggregate(
            baselineCount = 45,
            dualCount = 96,
            baselineWindowMs = 1_500L,
            dualWindowMs = 4_000L
        )

        assertEquals(30f, sample.previewFpsBaseline, delta)
        assertEquals(24f, sample.previewFpsDual, delta)
        assertEquals(0.2f, sample.previewDropRatio, 0.0001f)
    }

    @Test
    fun testPreviewDropRatioNeverGoesNegativeWhenDualIsFaster() {
        val sample = aggregate(
            baselineCount = 30,
            dualCount = 60,
            baselineWindowMs = 1_000L,
            dualWindowMs = 1_000L
        )

        assertEquals(0f, sample.previewDropRatio, 0f)
    }

    @Test
    fun testAggregateComputesHitRateMemoryDeltaThermalAndFailures() {
        val sample = aggregate(
            metadataHits = 99,
            metadataTotal = 100,
            warmupMs = null,
            thermalBefore = 0,
            thermalAfter = 2,
            memoryBefore = 2_000L,
            memoryAfter = 1_500L,
            captureFailures = 3
        )

        assertEquals(0.99f, sample.metadataHitRate, 0.0001f)
        assertEquals(-500L, sample.memoryDeltaBytes)
        assertEquals(0, sample.thermalStatusBefore)
        assertEquals(2, sample.thermalStatusAfter)
        assertEquals(3, sample.captureFailures)
        assertNull(sample.warmupMs)
    }

    @Test
    fun testAggregateWithNoFramesOrMetadataIsSafe() {
        val sample = aggregate(
            timestamps = emptyList(),
            ages = emptyList(),
            copiesNs = emptyList(),
            baselineCount = 0,
            dualCount = 0,
            metadataHits = 0,
            metadataTotal = 0,
            warmupMs = null,
            baselineWindowMs = 0L,
            dualWindowMs = 0L
        )

        assertEquals(0, sample.rawFrameCount)
        assertEquals(0f, sample.rawFps, 0f)
        assertEquals(0f, sample.gapP50Ms, 0f)
        assertEquals(0f, sample.gapP95Ms, 0f)
        assertEquals(0f, sample.gapMaxMs, 0f)
        assertEquals(0f, sample.ageP95Ms, 0f)
        assertEquals(0f, sample.copyP95Ms, 0f)
        assertEquals(0f, sample.previewFpsBaseline, 0f)
        assertEquals(0f, sample.previewFpsDual, 0f)
        assertEquals(0f, sample.previewDropRatio, 0f)
        assertEquals(0f, sample.metadataHitRate, 0f)
        assertNull(sample.warmupMs)
    }

    // ---------------------------------------------------------------------
    // S1-FIX-R2：拷贝吞吐（MiB/s）换算与边界
    // ---------------------------------------------------------------------

    @Test
    fun testThroughputIsMiBPerSecondWithNanosPrecision() {
        // 24 MiB / 15.0 ms = 1600 MiB/s；纳秒精度不应被整毫秒截断。
        val sample = aggregate(
            copiesNs = listOf(15_000_000L),
            frameBytes = 24L * 1024 * 1024
        )
        assertEquals(1600f, sample.copyThroughputP5MiBps, delta)
        assertEquals(1600f, sample.copyThroughputP50MiBps, delta)
        assertEquals(24L * 1024 * 1024, sample.copyFrameBytes)
    }

    @Test
    fun testThroughputIsResolutionNeutral() {
        // 同一耗时下 50 MP 帧的吞吐必须约为 12 MP 的 4.17 倍：这正是绝对毫秒门限做不到的。
        val small = aggregate(copiesNs = listOf(15_000_000L), frameBytes = 12L * 1024 * 1024)
        val big = aggregate(copiesNs = listOf(15_000_000L), frameBytes = 50L * 1024 * 1024)
        val ratio = big.copyThroughputP5MiBps / small.copyThroughputP5MiBps
        assertEquals(50f / 12f, ratio, 0.01f)
    }

    @Test
    fun testUnknownFrameBytesYieldsUnmeasuredThroughput() {
        // RAW 尺寸未知 -> 无法换算 -> 标记"未测到"（判定层据此判 COPY_UNMEASURED）。
        val sample = aggregate(copiesNs = listOf(15_000_000L), frameBytes = 0L)
        assertEquals(0f, sample.copyThroughputP5MiBps, 0f)
        assertEquals(0f, sample.copyThroughputP50MiBps, 0f)
        assertFalse(sample.copyThroughputMeasured)
        // 耗时仍然照常统计，便于诊断。
        assertEquals(15f, sample.copyP95Ms, delta)
    }

    @Test
    fun testMeasuredFlagIsTrueWhenThroughputIsComputable() {
        val sample = aggregate(copiesNs = listOf(15_000_000L), frameBytes = 24L * 1024 * 1024)
        assertTrue(sample.copyThroughputMeasured)
    }

    @Test
    fun testZeroOrNegativeCopyNanosDoesNotProduceInfinity() {
        val sample = aggregate(
            copiesNs = listOf(0L, -5L, 15_000_000L),
            frameBytes = 24L * 1024 * 1024
        )
        assertTrue(sample.copyThroughputP5MiBps.isFinite())
        assertTrue(sample.copyThroughputP50MiBps.isFinite())
        assertEquals(0f, sample.copyThroughputP5MiBps, delta)
    }
}
