package top.maary.darkbag.motionphoto

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
