package top.maary.darkbag.camera.zsl

import android.hardware.camera2.CaptureResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
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
    fun lazyModeIsCarriedByConfig() {
        val mode = ZslRingMode.LAZY_IMAGE
        assertEquals(mode, ZslRingConfig.forHdrPlus(5, 24L * 1024L * 1024L, mode)?.mode)
        assertEquals(mode, ZslRingConfig.forSingle(24L * 1024L * 1024L, mode)?.mode)
    }

    @Test
    fun ownershipHelperPrefersPostTakeFrame() {
        val preTake = ZslFrame(
            buffer = null,
            width = 4,
            height = 2,
            rowStride = 4,
            timestamp = 1L,
            result = null,
            iso = null,
            exposureTimeNs = null,
            wb = null,
            cropKey = null,
            afFocused = false,
            lensId = "0",
            zoomRatio = 1f,
            rotationDegrees = 0,
            rowLength = 4
        )
        val owned = preTake.copy(buffer = java.nio.ByteBuffer.allocateDirect(8))
        assertSame(owned, listOf(preTake, owned).firstOwnedFrame())
    }

    @Test
    fun matchesCameraLensAndZoom() {
        val ring = ring()
        assertTrue(ring.matches("0", "0", "0", 1.0f))
        assertFalse(ring.matches("1", "0", "0", 1.0f))
        assertFalse(ring.matches("0", "2", "0", 1.0f))
        assertFalse(ring.matches("0", "0", "2", 1.0f))
        assertFalse(ring.matches("0", "0", "0", 2.0f))
    }

    @Test
    fun afStateHelperAcceptsStationaryInactiveAndRejectsScanning() {
        assertTrue(isAfStateFocused(CaptureResult.CONTROL_AF_MODE_OFF, CaptureResult.CONTROL_AF_STATE_INACTIVE, CaptureResult.LENS_STATE_MOVING))
        assertTrue(isAfStateFocused(CaptureResult.CONTROL_AF_MODE_CONTINUOUS_PICTURE, CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED, CaptureResult.LENS_STATE_MOVING))
        assertTrue(isAfStateFocused(CaptureResult.CONTROL_AF_MODE_CONTINUOUS_PICTURE, CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED, CaptureResult.LENS_STATE_STATIONARY))
        assertTrue(isAfStateFocused(CaptureResult.CONTROL_AF_MODE_CONTINUOUS_PICTURE, CaptureResult.CONTROL_AF_STATE_INACTIVE, CaptureResult.LENS_STATE_STATIONARY))
        assertFalse(isAfStateFocused(CaptureResult.CONTROL_AF_MODE_CONTINUOUS_PICTURE, CaptureResult.CONTROL_AF_STATE_INACTIVE, CaptureResult.LENS_STATE_MOVING))
        assertFalse(isAfStateFocused(CaptureResult.CONTROL_AF_MODE_CONTINUOUS_PICTURE, CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN, CaptureResult.LENS_STATE_STATIONARY))
        assertFalse(isAfStateFocused(CaptureResult.CONTROL_AF_MODE_CONTINUOUS_PICTURE, CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN, CaptureResult.LENS_STATE_STATIONARY))
        assertFalse(isAfStateFocused(CaptureResult.CONTROL_AF_MODE_CONTINUOUS_PICTURE, CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED, CaptureResult.LENS_STATE_STATIONARY))
    }
}
