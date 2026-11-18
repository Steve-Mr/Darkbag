package top.maary.darkbag.camera.zsl

/**
 * Strongly typed result produced by [ZslCaptureController] capture attempts.
 *
 * Eliminates implicit temporal coupling via mutable controller state fields.
 */
sealed class ZslDispatchOutcome {
    data class Consumed(
        val sensorTimestampNs: Long,
        val realtimeTimestamp: Boolean,
        val preRollFrames: Int = 1,
        val postRollFrames: Int = 0
    ) : ZslDispatchOutcome() {
        val totalFrames: Int get() = preRollFrames + postRollFrames
        val isHybrid: Boolean get() = postRollFrames > 0
    }

    data class Fallback(
        val reason: String
    ) : ZslDispatchOutcome()

    val isConsumed: Boolean get() = this is Consumed
}
