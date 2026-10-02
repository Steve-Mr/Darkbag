package top.maary.darkbag.models

data class StandardTimingTracker(
    val shutterClick: Long,
    var firstFrameCaptured: Long = 0,
    var lastFrameCaptured: Long = 0,
    var captureCallback: Long = 0,
    var accumulateDone: Long = 0,
    var enqueued: Long = 0,
    var processingStart: Long = 0,
    var jniDone: Long = 0,
    var firstOutputWritten: Long = 0
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

    fun buildSummaryReport(): String {
        val total = if (firstOutputWritten > 0) "${firstOutputWritten - shutterClick}ms" else "in progress"
        val shutterToFirst = if (firstFrameCaptured > 0) "${firstFrameCaptured - shutterClick}ms" else "N/A"
        val captureCadence = synchronized(frameArrivalTimes) {
            if (frameArrivalTimes.size > 1) {
                val intervals = frameArrivalTimes.zipWithNext { a, b -> b - a }
                val totalCap = lastFrameCaptured - firstFrameCaptured
                "intervals: ${intervals.joinToString(", ")}ms (Total: ${totalCap}ms for ${frameArrivalTimes.size} frames)"
            } else if (frameArrivalTimes.size == 1) {
                "1 frame"
            } else "N/A"
        }
        val hwCaptureTotal = if (captureCallback > 0) "${captureCallback - shutterClick}ms" else "N/A"
        val halideDuration = if (accumulateDone > 0 && firstFrameCaptured > 0) "${accumulateDone - firstFrameCaptured}ms" else "N/A"
        val queueWait = if (processingStart > 0 && enqueued > 0) "${processingStart - enqueued}ms" else "N/A"
        val stage1Cost = if (jniDone > 0 && processingStart > 0) "${jniDone - processingStart}ms" else "N/A"
        val diskSaveCost = if (firstOutputWritten > 0 && jniDone > 0) "${firstOutputWritten - jniDone}ms" else "N/A"

        return """
            [HDR+ Lifecycle Timing Report]
            Total Shutter-to-Output: $total
            - Shutter to First Frame: $shutterToFirst
            - Sensor Capture Cadence: $captureCadence
            - Shutter to HW Capture Complete: $hwCaptureTotal
            - Halide Streaming Accumulation: $halideDuration
            - Queue Wait: $queueWait
            - Native Stage 1 (Demosaic & Matrix): $stage1Cost
            - Stage 2 Disk Save (DNG & MediaStore): $diskSaveCost
        """.trimIndent()
    }
}
