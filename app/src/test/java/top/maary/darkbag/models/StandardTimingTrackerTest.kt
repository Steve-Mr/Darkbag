package top.maary.darkbag.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StandardTimingTrackerTest {

    @Test
    fun testRecordFrameArrival_singleAndMultiple() {
        val timing = StandardTimingTracker(shutterClick = 1000L)
        timing.recordFrameArrival(1050L)

        assertEquals(1050L, timing.firstFrameCaptured)
        assertEquals(1050L, timing.lastFrameCaptured)
        assertEquals(1, timing.frameArrivalTimes.size)

        timing.recordFrameArrival(1083L)
        timing.recordFrameArrival(1116L)

        assertEquals(1050L, timing.firstFrameCaptured)
        assertEquals(1116L, timing.lastFrameCaptured)
        assertEquals(3, timing.frameArrivalTimes.size)
    }

    @Test
    fun testRecordShutterReady_idempotent() {
        val timing = StandardTimingTracker(shutterClick = 1000L)
        timing.recordShutterReady(1150L)
        assertEquals(1150L, timing.shutterReady)

        // Second call should not overwrite first
        timing.recordShutterReady(1200L)
        assertEquals(1150L, timing.shutterReady)
    }

    @Test
    fun testBuildSummaryReport_containsMetrics() {
        val timing = StandardTimingTracker(
            shutterClick = 1000L,
            captureMode = CaptureTimingMode.HDR_BURST
        )
        timing.recordFrameArrival(1040L)
        timing.recordFrameArrival(1073L)
        timing.recordFrameArrival(1106L)
        timing.accumulateDone = 1120L
        timing.recordShutterReady(1130L)
        timing.enqueued = 1135L
        timing.processingStart = 1150L
        timing.stage1ComputeDone = 1600L
        timing.stage2ExportDone = 1800L
        timing.firstOutputWritten = 1850L
        timing.taskCompleted = 1860L
        timing.recordStage2Metrics(postMs = 45L, dngMs = 110L, jpgMs = 80L)

        val report = timing.buildSummaryReport()

        assertTrue(report.contains("HDR+ Burst (3 frames)"))
        assertTrue(report.contains("T1 快门就绪延迟: 130ms"))
        assertTrue(report.contains("T2 首张出片耗时: 850ms"))
        assertTrue(report.contains("T3 全流程总吞吐: 860ms"))
        assertTrue(report.contains("首帧延迟: 40ms"))
        assertTrue(report.contains("并发排队等待: 15ms"))
        assertTrue(report.contains("Stage 1 计算 (管线解算/融合): 450ms"))
        assertTrue(report.contains("C++ ColorPipe: 45ms"))
        assertTrue(report.contains("DNG 编码:     110ms"))
        assertTrue(report.contains("JPEG 编码:    80ms"))
    }

    @Test
    fun testBuildSummaryReport_singleRawMode() {
        val timing = StandardTimingTracker(
            shutterClick = 2000L,
            captureMode = CaptureTimingMode.SINGLE_RAW
        )
        timing.recordFrameArrival(2060L)
        timing.recordShutterReady(2100L)
        timing.enqueued = 2105L
        timing.processingStart = 2110L
        timing.stage1ComputeDone = 2400L
        timing.stage2ExportDone = 2600L
        timing.firstOutputWritten = 2650L

        val report = timing.buildSummaryReport()

        assertTrue(report.contains("Single RAW"))
        assertTrue(report.contains("T1 快门就绪延迟: 100ms"))
        assertTrue(report.contains("T2 首张出片耗时: 650ms"))
        assertTrue(report.contains("首帧延迟: 60ms"))
        assertTrue(report.contains("Stage 1 计算 (管线解算/融合): 290ms"))
    }

    @Test
    fun testBuildSummaryReport_jniDoneFallback() {
        val timing = StandardTimingTracker(shutterClick = 3000L)
        timing.processingStart = 3100L
        timing.jniDone = 3500L // Legacy field only, stage1ComputeDone left at 0
        timing.firstOutputWritten = 3800L

        val report = timing.buildSummaryReport()

        assertTrue(report.contains("Stage 1 计算 (管线解算/融合): 400ms"))
        assertTrue(report.contains("Stage 2 导出落盘 (总计: 300ms)"))
    }
}
