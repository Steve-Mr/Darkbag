package top.maary.darkbag.camera.state

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.params.MeteringRectangle
import android.util.Range
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.maary.darkbag.processor.HdrPlusStreamingBurst
import java.nio.ByteBuffer

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CameraRuntimeStateHolderTest {

    @Test
    fun testDefaultStateAndDerivedFlags() {
        val state = CameraRuntimeStateHolder()

        assertEquals(CameraCharacteristics.LENS_FACING_BACK, state.lensFacing)
        assertFalse(state.isManualExposure)
        assertFalse(state.hasAnyManualOverride)
        assertFalse(state.hasFocusMeteringLock)
        assertFalse(state.isProcessing)
    }

    @Test
    fun testManualOverridesAndMeteringFlags() {
        val state = CameraRuntimeStateHolder()

        state.isManualIso = true
        assertTrue(state.isManualExposure)
        assertTrue(state.hasAnyManualOverride)

        state.isManualIso = false
        state.isManualShutter = true
        assertTrue(state.isManualExposure)
        assertTrue(state.hasAnyManualOverride)

        state.isManualShutter = false
        state.currentEvIndex = 2
        assertFalse(state.isManualExposure)
        assertTrue(state.hasAnyManualOverride)

        val rect = MeteringRectangle(10, 10, 50, 50, MeteringRectangle.METERING_WEIGHT_MAX)
        state.focusMeteringRegion = rect
        state.exposureMeteringRegion = rect
        assertTrue(state.hasFocusMeteringLock)

        assertTrue(state.clearMeteringRegions())
        assertFalse(state.hasFocusMeteringLock)
        assertNull(state.focusMeteringRegion)
        assertNull(state.exposureMeteringRegion)
        assertFalse(state.clearMeteringRegions())
    }

    @Test
    fun testApplyCharacteristics_clampsManualIsoAndExposure() {
        val state = CameraRuntimeStateHolder()
        state.currentIso = 50000
        state.currentExposureTime = 1_000L

        state.applyCharacteristics(
            isoRange = Range(100, 6400),
            exposureTimeRange = Range(100_000L, 1_000_000_000L),
            minFocusDistance = 10f,
            evRange = Range(-6, 6),
            isRawSupported = true,
            isOisSupported = true,
            isHdrOisEnabledPref = false,
            isPresetCurveToneMapSupported = false
        )

        assertEquals(6400, state.currentIso)
        assertEquals(100_000L, state.currentExposureTime)
        assertEquals(10f, state.minFocusDistance, 0.001f)
        assertTrue(state.isRawSupported)
        assertTrue(state.isOisSupported)
        assertFalse(state.isHdrOisEnabledPref)
    }

    @Test
    fun testIsProcessing_reflectsBurstAndVideoSaving() {
        val state = CameraRuntimeStateHolder()
        assertFalse(state.isProcessing)

        state.isBurstActive = true
        assertTrue(state.isProcessing)

        state.isBurstActive = false
        state.isVideoSaving = true
        assertTrue(state.isProcessing)

        state.isVideoSaving = false
        assertFalse(state.isProcessing)
    }

    @Test
    fun testAbortStreamingBurst_clearsHelperSafelyForNullAndNonNull() {
        val state = CameraRuntimeStateHolder()

        assertNull(state.hdrPlusStreamingBurstHelper)
        state.abortStreamingBurst()
        assertNull(state.hdrPlusStreamingBurstHelper)

        val helper = HdrPlusStreamingBurst(
            sessionHandle = 0L,
            frameCount = 1,
            onBurstComplete = {}
        )
        state.hdrPlusStreamingBurstHelper = helper

        state.abortStreamingBurst()
        assertNull(state.hdrPlusStreamingBurstHelper)
        assertFalse(helper.addFrame(ByteBuffer.allocate(16), 2, 2, 4, 1, 0L, 0L, 0))

        // Idempotent when called again after already cleared
        state.abortStreamingBurst()
        assertNull(state.hdrPlusStreamingBurstHelper)
    }
}
