package top.maary.darkbag.motionphoto

import java.util.concurrent.atomic.AtomicLong

/** Maps Camera2 sensor timestamps onto the encoder's System.nanoTime() timeline. */
object MotionPhotoTimebase {
    fun sensorToMonotonic(
        sensorTimestampNs: Long,
        realtime: Boolean,
        sensorNowNs: Long,
        monotonicNowNs: Long
    ): Long {
        if (sensorTimestampNs <= 0L || sensorNowNs <= 0L || monotonicNowNs <= 0L) return 0L
        val ageNs = (sensorNowNs - sensorTimestampNs).coerceAtLeast(0L)
        return (monotonicNowNs - ageNs).coerceAtLeast(0L)
    }
}

/** Per-shot still timestamp holder so rapid consecutive captures cannot overwrite each other's PTS. */
internal class MotionPhotoStillTimestampSlot(triggerNs: Long) {
    private val resolvedNs = AtomicLong(triggerNs)

    fun resolve(mappedNs: Long) {
        if (mappedNs > 0L) resolvedNs.set(mappedNs)
    }

    fun get(): Long = resolvedNs.get()
}
