package top.maary.darkbag.zsl.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        copies: List<Float> = listOf(2f),
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
        copySamplesMs = copies,
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
            copies = listOf(2f, 4f)
        )

        assertEquals(25f, sample.ageP50Ms, delta)
        assertEquals(38.5f, sample.ageP95Ms, 0.01f)
        assertEquals(3f, sample.copyP50Ms, delta)
        assertEquals(3.9f, sample.copyP95Ms, 0.01f)
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
            copies = emptyList(),
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
}
