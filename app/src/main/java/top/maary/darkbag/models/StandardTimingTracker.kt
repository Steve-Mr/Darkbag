package top.maary.darkbag.models

data class StandardTimingTracker(
    val shutterClick: Long,
    var captureCallback: Long = 0,
    var enqueued: Long = 0,
    var processingStart: Long = 0,
    var jniDone: Long = 0,
    var firstOutputWritten: Long = 0
)
