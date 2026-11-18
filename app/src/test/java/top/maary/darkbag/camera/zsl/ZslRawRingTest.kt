package top.maary.darkbag.camera.zsl

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZslRawRingTest {
    private fun ring() = ZslRawRing(
        config = ZslRingConfig(3, 30, 128L * 1024L * 1024L, 24L * 1024L * 1024L, 300_000_000L),
        cameraId = "0",
        lensId = "0",
        physicalId = "0",
        zoomRatio = 1.0f,
        rotationDegrees = 90
    )

    @Test
    fun matchesCameraLensAndZoom() {
        val ring = ring()
        assertTrue(ring.matches("0", "0", "0", 1.0f))
        assertFalse(ring.matches("1", "0", "0", 1.0f))
        assertFalse(ring.matches("0", "2", "0", 1.0f))
        assertFalse(ring.matches("0", "0", "2", 1.0f))
        assertFalse(ring.matches("0", "0", "0", 2.0f))
    }
}
