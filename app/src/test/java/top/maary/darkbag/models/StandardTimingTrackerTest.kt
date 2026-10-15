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
        assertTrue(report.contains("C++ ColorPipe (调色/LUT): 45ms"))
        assertTrue(report.contains("DNG 编码:     110ms"))
        assertTrue(report.contains("JPEG 压缩:    80ms"))
    }

    @Test
    fun testBuildSummaryReport_withEnvironmentMetadata() {
        val timing = StandardTimingTracker(
            shutterClick = 1000L,
            captureMode = CaptureTimingMode.HDR_BURST
        )
        timing.recordEnvironment(
            width = 4032,
            height = 3024,
            isoVal = 100,
            exposureNs = 33_333_333L, // ~1/30s
            zoom = 1.0f,
            fusion = 1
        )
        val report = timing.buildSummaryReport()
        assertTrue(report.contains("📷 环境: 4032x3024 | ISO 100 | 1/30s | Mode: Spatial+RCD"))
    }

    @Test
    fun testBuildSummaryReport_withStreamingPushAndStage1Breakdown() {
        val timing = StandardTimingTracker(
            shutterClick = 1000L,
            captureMode = CaptureTimingMode.HDR_BURST
        )
        timing.recordFrameArrival(1020L)
        timing.recordFrameArrival(1050L)
        timing.accumulateDone = 1500L
        timing.processingStart = 1510L
        timing.stage1ComputeDone = 1800L
        timing.recordStreamingPushStats(count = 8, avgMs = 55L, minMs = 40L, maxMs = 75L)
        timing.recordStage1ComputeBreakdown(normalizeMs = 12L, fusionMs = 278L)

        val report = timing.buildSummaryReport()
        assertTrue(report.contains("流式累加完成: 480ms (8 帧 push: avg 55ms, min 40ms, max 75ms)"))
        assertTrue(report.contains("归一化:      12ms"))
        assertTrue(report.contains("解算/去马赛克: 278ms"))
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

    @Test
    fun testBuildSummaryReport_concurrentExportIoTime() {
        val timing = StandardTimingTracker(shutterClick = 4000L)
        timing.processingStart = 4100L
        timing.stage1ComputeDone = 4500L
        timing.stage2ExportDone = 6550L
        timing.firstOutputWritten = 6620L // stage2TotalMs = 2120ms
        // DNG = 1000ms, ColorPipe = 2000ms, JPEG = 50ms (concurrently run in ~2050ms)
        timing.recordStage2Metrics(postMs = 2000L, dngMs = 1000L, jpgMs = 50L)

        val report = timing.buildSummaryReport()
        assertTrue(report.contains("Stage 2 导出落盘 (总计: 2120ms)"))
        assertTrue(report.contains("C++ ColorPipe (调色/LUT): 2000ms"))
        assertTrue(report.contains("DNG 编码:     1000ms"))
        assertTrue(report.contains("JPEG 压缩:    50ms"))
        assertTrue(report.contains("MediaStore 写库/EXIF: 70ms"))
    }

    @Test
    fun testBuildSummaryReport_accumulationQueueWait() {
        val timing = StandardTimingTracker(shutterClick = 5000L)
        timing.recordFrameArrival(5100L)
        timing.recordFrameArrival(5500L) // last frame captured at 5500L
        timing.accumulateStart = 20000L  // Waited in queue until 20000L (14500ms queue wait)
        timing.accumulateDone = 24800L   // Finished accumulation at 24800L (4800ms compute)

        val report = timing.buildSummaryReport()
        assertTrue(report.contains("流式累加完成: 19700ms (排队: 14500ms, 累加: 4800ms)"))
    }

    @Test
    fun testBuildSummaryReport_decoupledStage2_jpegOnly() {
        val timing = StandardTimingTracker(shutterClick = 6000L)
        timing.recordFrameArrival(6040L)
        timing.recordShutterReady(6080L)
        timing.enqueued = 6090L
        timing.processingStart = 6100L
        timing.stage1ComputeDone = 6400L // 300ms compute
        timing.firstOutputWritten = 6520L // Fast path JPEG written at 6520L (T2 = 520ms)
        timing.stage2ExportDone = 6530L
        timing.taskCompleted = 6540L
        timing.recordStage2Metrics(postMs = 60L, dngMs = 0L, jpgMs = 40L)

        val report = timing.buildSummaryReport()
        assertTrue(report.contains("T2 首张出片耗时: 520ms"))
        assertTrue(report.contains("C++ ColorPipe (调色/LUT): 60ms"))
        assertTrue(report.contains("JPEG 压缩:    40ms"))
        assertFalse(report.contains("DNG 编码"))
    }

    @Test
    fun testBuildSummaryReport_decoupledStage2_rawOnly() {
        val timing = StandardTimingTracker(shutterClick = 7000L)
        timing.recordFrameArrival(7050L)
        timing.recordShutterReady(7100L)
        timing.enqueued = 7110L
        timing.processingStart = 7120L
        timing.stage1ComputeDone = 7450L
        timing.firstOutputWritten = 7650L // RAW-only output written at 7650L (T2 = 650ms)
        timing.stage2ExportDone = 7660L
        timing.taskCompleted = 7670L
        timing.recordStage2Metrics(postMs = 0L, dngMs = 180L, jpgMs = 0L)

        val report = timing.buildSummaryReport()
        assertTrue(report.contains("T2 首张出片耗时: 650ms"))
        assertTrue(report.contains("DNG 编码:     180ms"))
        assertFalse(report.contains("C++ ColorPipe"))
        assertFalse(report.contains("JPEG 压缩"))
    }

    @Test
    fun testBuildSummaryReport_singleRawMode_displaysSingleRcdEvenWithZoom() {
        val timing = StandardTimingTracker(
            shutterClick = 2000L,
            captureMode = CaptureTimingMode.SINGLE_RAW
        )
        timing.recordEnvironment(
            width = 4032,
            height = 3024,
            isoVal = 400,
            exposureNs = 20_000_000L,
            zoom = 1.5f,
            fusion = 0,
            isSingle = true
        )
        val report = timing.buildSummaryReport()
        assertTrue(report.contains("Mode: Single(RCD)"))
        assertFalse(report.contains("Auto(Sabre)"))
    }

    @Test
    fun testBuildSummaryReport_fusionModeFormatting() {
        // fusionMode = 2 at zoom 1.0f -> Sabre 1x(Demosaic)
        val timingSabre1x = StandardTimingTracker(shutterClick = 1000L).apply {
            recordEnvironment(width = 4032, height = 3024, zoom = 1.0f, fusion = 2)
        }
        val reportSabre1x = timingSabre1x.buildSummaryReport()
        assertTrue("Expected 'Mode: Sabre 1x(Demosaic)' at zoom 1.0f",
            reportSabre1x.contains("Mode: Sabre 1x(Demosaic)"))

        // fusionMode = 2 at zoom 2.0f -> Sabre SR
        val timingSabreSr = StandardTimingTracker(shutterClick = 1000L).apply {
            recordEnvironment(width = 4032, height = 3024, zoom = 2.0f, fusion = 2)
        }
        val reportSabreSr = timingSabreSr.buildSummaryReport()
        assertTrue("Expected 'Mode: Sabre SR' at zoom 2.0f",
            reportSabreSr.contains("Mode: Sabre SR"))

        // fusionMode = 0 (Auto) at zoom 1.0f -> Auto(RCD)
        val timingAutoRcd = StandardTimingTracker(shutterClick = 1000L).apply {
            recordEnvironment(width = 4032, height = 3024, zoom = 1.0f, fusion = 0)
        }
        val reportAutoRcd = timingAutoRcd.buildSummaryReport()
        assertTrue("Expected 'Mode: Auto(RCD)' at zoom 1.0f",
            reportAutoRcd.contains("Mode: Auto(RCD)"))

        // fusionMode = 0 (Auto) at zoom 1.5f -> Auto(Sabre)
        val timingAutoSabre = StandardTimingTracker(shutterClick = 1000L).apply {
            recordEnvironment(width = 4032, height = 3024, zoom = 1.5f, fusion = 0)
        }
        val reportAutoSabre = timingAutoSabre.buildSummaryReport()
        assertTrue("Expected 'Mode: Auto(Sabre)' at zoom 1.5f",
            reportAutoSabre.contains("Mode: Auto(Sabre)"))
    }
}

