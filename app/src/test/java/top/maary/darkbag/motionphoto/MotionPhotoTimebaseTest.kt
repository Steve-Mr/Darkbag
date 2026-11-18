package top.maary.darkbag.motionphoto

import org.junit.Assert.assertEquals
import org.junit.Test

class MotionPhotoTimebaseTest {
    @Test
    fun mapsSensorTimeToMonotonicTimeline() {
        val mapped = MotionPhotoTimebase.sensorToMonotonic(
            sensorTimestampNs = 1_000_000_000L,
            realtime = true,
            sensorNowNs = 1_050_000_000L,
            monotonicNowNs = 5_000_000_000L
        )
        assertEquals(4_950_000_000L, mapped)
    }

    @Test
    fun rejectsInvalidInputs() {
        assertEquals(0L, MotionPhotoTimebase.sensorToMonotonic(0L, true, 1L, 2L))
        assertEquals(0L, MotionPhotoTimebase.sensorToMonotonic(1L, false, 0L, 2L))
    }

    @Test
    fun stillTimestampSlotsAreIsolatedPerShot() {
        val shot1 = MotionPhotoStillTimestampSlot(triggerNs = 1_000L)
        shot1.resolve(950L)
        val shot2 = MotionPhotoStillTimestampSlot(triggerNs = 2_000L)
        shot2.resolve(1_920L)
        assertEquals(950L, shot1.get())
        assertEquals(1_920L, shot2.get())
        shot2.resolve(0L)
        assertEquals(1_920L, shot2.get())
    }
}
