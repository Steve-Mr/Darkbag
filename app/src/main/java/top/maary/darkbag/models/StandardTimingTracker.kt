package top.maary.darkbag.models

import java.text.SimpleDateFormat
import java.util.Locale

enum class CaptureTimingMode(val displayName: String) {
    HDR_BURST("HDR+ Burst"),
    SINGLE_RAW("Single RAW"),
    SINGLE_JPEG("Single JPEG"),
    MULTI_CAMERA("Multi-Camera"),
    HALF_FRAME("Half-Frame")
}

data class StandardTimingTracker(
    val shutterClick: Long,
    var captureMode: CaptureTimingMode = CaptureTimingMode.HDR_BURST,
    @Volatile var firstFrameCaptured: Long = 0,
    @Volatile var lastFrameCaptured: Long = 0,
    @Volatile var captureCallback: Long = 0,
    @Volatile var shutterReady: Long = 0,
    @Volatile var accumulateStart: Long = 0,
    @Volatile var accumulateDone: Long = 0,
    @Volatile var enqueued: Long = 0,
    @Volatile var processingStart: Long = 0,
    @Volatile var stage1ComputeDone: Long = 0,
    @Volatile var stage2ExportDone: Long = 0,
    @Volatile var jniDone: Long = 0, // Retained for backwards compatibility
    @Volatile var firstOutputWritten: Long = 0,
    @Volatile var taskCompleted: Long = 0,
    @Volatile var nativePostProcessMs: Long = 0,
    @Volatile var nativeDngEncodeMs: Long = 0,
    @Volatile var nativeJpegEncodeMs: Long = 0,
    @Volatile var imageWidth: Int = 0,
    @Volatile var imageHeight: Int = 0,
    @Volatile var iso: Int = 0,
    @Volatile var exposureTimeNs: Long = 0,
    @Volatile var zoomFactor: Float = 1.0f,
    @Volatile var fusionMode: Int = 0,
    @Volatile var streamingPushCount: Int = 0,
    @Volatile var streamingPushAvgMs: Long = 0,
    @Volatile var streamingPushMinMs: Long = 0,
    @Volatile var streamingPushMaxMs: Long = 0,
    @Volatile var stage1NormalizeMs: Long = 0,
    @Volatile var stage1FusionMs: Long = 0,
    @Volatile var isSingleFrame: Boolean = false
) {
    val frameArrivalTimes = mutableListOf<Long>()

    fun recordFrameArrival(timestampMs: Long = System.currentTimeMillis()) {
        synchronized(frameArrivalTimes) {
            frameArrivalTimes.add(timestampMs)
            if (firstFrameCaptured == 0L) {
                firstFrameCaptured = timestampMs
            }
            lastFrameCaptured = timestampMs
        }
    }

    fun recordShutterReady(timestampMs: Long = System.currentTimeMillis()) {
        if (shutterReady == 0L) {
            shutterReady = timestampMs
        }
    }

    fun recordStage2Metrics(postMs: Long, dngMs: Long, jpgMs: Long) {
        nativePostProcessMs = postMs
        nativeDngEncodeMs = dngMs
        nativeJpegEncodeMs = jpgMs
    }

    fun recordEnvironment(
        width: Int,
        height: Int,
        isoVal: Int = 0,
        exposureNs: Long = 0,
        zoom: Float = 1.0f,
        fusion: Int = 0,
        isSingle: Boolean = false
    ) {
        imageWidth = width
        imageHeight = height
        iso = isoVal
        exposureTimeNs = exposureNs
        zoomFactor = zoom
        fusionMode = fusion
        isSingleFrame = isSingle
    }

    fun recordStreamingPushStats(count: Int, avgMs: Long, minMs: Long, maxMs: Long) {
        streamingPushCount = count
        streamingPushAvgMs = avgMs
        streamingPushMinMs = minMs
        streamingPushMaxMs = maxMs
    }

    fun recordStage1ComputeBreakdown(normalizeMs: Long, fusionMs: Long) {
        stage1NormalizeMs = normalizeMs
        stage1FusionMs = fusionMs
    }

    fun buildSummaryReport(): String {
        val timeStr = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(shutterClick)
        val frameCount = synchronized(frameArrivalTimes) { frameArrivalTimes.size }
        val modeHeader = if (frameCount > 1) {
            "${captureMode.displayName} ($frameCount frames)"
        } else {
            captureMode.displayName
        }

        val envParts = mutableListOf<String>()
        if (imageWidth > 0 && imageHeight > 0) envParts.add("${imageWidth}x${imageHeight}")
        if (iso > 0) envParts.add("ISO $iso")
        if (exposureTimeNs > 0) {
            val sec = exposureTimeNs / 1_000_000_000.0
            val expStr = if (sec >= 1.0) String.format(Locale.US, "%.1fs", sec)
                         else "1/${Math.round(1.0 / sec)}s"
            envParts.add(expStr)
        }
        if (zoomFactor > 1.05f || zoomFactor < 0.95f) {
            envParts.add(String.format(Locale.US, "Zoom %.1fx", zoomFactor))
        }
        val fusionModeStr = if (isSingleFrame) {
            "Single(RCD)"
        } else when (fusionMode) {
            1 -> "Spatial+RCD"
            2 -> if (zoomFactor > 1.05f) "Sabre SR" else "Sabre 1x(Demosaic)"
            3 -> "Classic Wiener"
            else -> if (zoomFactor >= 1.25f) "Auto(Sabre)" else "Auto(RCD)"
        }
        envParts.add("Mode: $fusionModeStr")
        val envLine = if (envParts.isNotEmpty()) "📷 环境: ${envParts.joinToString(" | ")}\n" else ""

        // T1: Shutter to UI Ready (Shutter Lag / UI Recovery)
        val t1 = if (shutterReady > 0 && shutterClick > 0) {
            "${(shutterReady - shutterClick).coerceAtLeast(0)}ms"
        } else "N/A"

        // T2: Shutter to First Output in MediaStore (Time to Output)
        val t2 = if (firstOutputWritten > 0 && shutterClick > 0) {
            "${(firstOutputWritten - shutterClick).coerceAtLeast(0)}ms"
        } else "in progress"

        // T3: Shutter to Task Completed (Full End-to-End Throughput)
        val t3 = if (taskCompleted > 0 && shutterClick > 0) {
            "${(taskCompleted - shutterClick).coerceAtLeast(0)}ms"
        } else if (firstOutputWritten > 0 && shutterClick > 0) {
            "${(firstOutputWritten - shutterClick).coerceAtLeast(0)}ms"
        } else "in progress"

        // Stage 1 Sensor details
        val shutterToFirst = if (firstFrameCaptured > 0 && shutterClick > 0) {
            "${(firstFrameCaptured - shutterClick).coerceAtLeast(0)}ms"
        } else "N/A"

        val cadenceStr = synchronized(frameArrivalTimes) {
            if (frameArrivalTimes.size > 1) {
                val intervals = frameArrivalTimes.zipWithNext { a, b -> (b - a).coerceAtLeast(0) }
                val totalCap = (lastFrameCaptured - firstFrameCaptured).coerceAtLeast(0)
                "intervals: [${intervals.joinToString(", ")}]ms (Total: ${totalCap}ms)"
            } else if (frameArrivalTimes.size == 1) {
                "1 frame received"
            } else "N/A"
        }

        val accumulationStr = if (accumulateDone > 0 && firstFrameCaptured > 0) {
            val totalMs = (accumulateDone - firstFrameCaptured).coerceAtLeast(0)
            if (accumulateStart > firstFrameCaptured) {
                val baseWaitRef = if (lastFrameCaptured > 0) lastFrameCaptured else firstFrameCaptured
                val queueWaitMs = (accumulateStart - baseWaitRef).coerceAtLeast(0)
                val computeMs = (accumulateDone - accumulateStart).coerceAtLeast(0)
                if (queueWaitMs > 50) {
                    "${totalMs}ms (排队: ${queueWaitMs}ms, 累加: ${computeMs}ms)"
                } else {
                    "${totalMs}ms"
                }
            } else {
                "${totalMs}ms"
            }
        } else null

        // Stage 2 Queue
        val queueWait = if (processingStart > 0 && enqueued > 0) {
            "${(processingStart - enqueued).coerceAtLeast(0)}ms"
        } else "N/A"

        // Stage 3 Compute
        val effectiveStage1End = if (stage1ComputeDone > 0) stage1ComputeDone else jniDone
        val stage1Cost = if (effectiveStage1End > 0 && processingStart > 0) {
            "${(effectiveStage1End - processingStart).coerceAtLeast(0)}ms"
        } else "N/A"

        val stage1Detail = if (stage1NormalizeMs > 0 || stage1FusionMs > 0) {
            val sb = StringBuilder()
            if (stage1NormalizeMs > 0) sb.append("\n      - 归一化:      ${stage1NormalizeMs}ms")
            if (stage1FusionMs > 0) sb.append("\n      - 解算/去马赛克: ${stage1FusionMs}ms")
            sb.toString()
        } else ""

        // Stage 4 Export & Disk Save
        val exportSection = StringBuilder()
        val stage2TotalMs = if (firstOutputWritten > 0 && effectiveStage1End > 0) {
            (firstOutputWritten - effectiveStage1End).coerceAtLeast(0)
        } else -1L

        val stage2Header = if (stage2TotalMs >= 0) "${stage2TotalMs}ms" else "in progress"
        exportSection.append("  [4] Stage 2 导出落盘 (总计: $stage2Header):\n")

        val hasNativeBreakdown = nativePostProcessMs > 0 || nativeDngEncodeMs > 0 || nativeJpegEncodeMs > 0
        if (hasNativeBreakdown) {
            if (nativePostProcessMs > 0) exportSection.append("      - C++ ColorPipe (调色/LUT): ${nativePostProcessMs}ms\n")
            if (nativeDngEncodeMs > 0) exportSection.append("      - DNG 编码:     ${nativeDngEncodeMs}ms\n")
            if (nativeJpegEncodeMs > 0) exportSection.append("      - JPEG 压缩:    ${nativeJpegEncodeMs}ms\n")
            val nativeSum = nativePostProcessMs + nativeDngEncodeMs + nativeJpegEncodeMs
            val maxJob = maxOf(nativeDngEncodeMs, nativePostProcessMs + nativeJpegEncodeMs)
            val nativeWallClock = if (stage2TotalMs >= nativeSum) nativeSum else maxJob
            val ioTime = (stage2TotalMs - nativeWallClock).coerceAtLeast(0)
            if (stage2TotalMs > 0 && ioTime > 0) {
                exportSection.append("      - MediaStore 写库/EXIF: ${ioTime}ms\n")
            }
        } else if (stage2ExportDone > 0 && effectiveStage1End > 0) {
            val exportMs = (stage2ExportDone - effectiveStage1End).coerceAtLeast(0)
            exportSection.append("      - Native 导出:  ${exportMs}ms\n")
            if (firstOutputWritten > 0) {
                val ioMs = (firstOutputWritten - stage2ExportDone).coerceAtLeast(0)
                exportSection.append("      - MediaStore 写库: ${ioMs}ms\n")
            }
        } else {
            exportSection.append("      - 导出与写库: $stage2Header\n")
        }

        val sensorSection = StringBuilder()
        sensorSection.append("  [1] 传感器捕获:\n")
        sensorSection.append("      - 首帧延迟: $shutterToFirst\n")
        if (frameCount > 1) {
            sensorSection.append("      - 连拍分布: $cadenceStr\n")
        }
        if (accumulationStr != null) {
            val pushDetail = if (streamingPushCount > 1) {
                " ($streamingPushCount 帧 push: avg ${streamingPushAvgMs}ms, min ${streamingPushMinMs}ms, max ${streamingPushMaxMs}ms)"
            } else ""
            sensorSection.append("      - 流式累加完成: $accumulationStr$pushDetail\n")
        }

        return """
========================================
[$modeHeader] $timeStr
$envLine========================================
⚡ 核心延迟指标:
  • T1 快门就绪延迟: $t1
  • T2 首张出片耗时: $t2
  • T3 全流程总吞吐: $t3

📊 阶段耗时分解:
$sensorSection  [2] 并发排队等待: $queueWait
  [3] Stage 1 计算 (管线解算/融合): $stage1Cost$stage1Detail
$exportSection========================================
        """.trimIndent()
    }
}
