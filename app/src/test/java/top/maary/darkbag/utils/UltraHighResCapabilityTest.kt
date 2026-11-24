package top.maary.darkbag.utils

import android.hardware.camera2.CameraCharacteristics
import android.os.Build
import android.util.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class UltraHighResCapabilityTest {

    private val capUltraHighRes = intArrayOf(
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE,
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW,
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR
    )
    private val capStandardRaw = intArrayOf(
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE,
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW
    )

    @Test
    fun evaluate_belowApi31_returnsUnsupported() {
        val res = CameraRepository.evaluateUltraHighResCapability(
            sdkInt = Build.VERSION_CODES.R,
            capabilities = capUltraHighRes,
            defaultRawSizes = arrayOf(Size(4000, 3000)),
            maxResRawSizes = arrayOf(Size(8000, 6000))
        )
        assertFalse(res.supported)
        assertEquals(Size(4000, 3000), res.defaultRawSize)
        assertNull(res.maxResolutionRawSize)
    }

    @Test
    fun evaluate_missingUltraHighResCapability_returnsUnsupported() {
        val res = CameraRepository.evaluateUltraHighResCapability(
            sdkInt = Build.VERSION_CODES.S,
            capabilities = capStandardRaw,
            defaultRawSizes = arrayOf(Size(4000, 3000)),
            maxResRawSizes = arrayOf(Size(8000, 6000))
        )
        assertFalse(res.supported)
        assertEquals(1.0f, res.areaRatio, 1e-4f)
    }

    @Test
    fun evaluate_sameResolutionMaxMap_returnsUnsupported() {
        val res = CameraRepository.evaluateUltraHighResCapability(
            sdkInt = Build.VERSION_CODES.S,
            capabilities = capUltraHighRes,
            defaultRawSizes = arrayOf(Size(4000, 3000)),
            maxResRawSizes = arrayOf(Size(4000, 3000))
        )
        assertFalse(res.supported)
        assertEquals(1.0f, res.areaRatio, 1e-4f)
    }

    @Test
    fun evaluate_quadBayer48MpVs12Mp_returnsSupportedWith4xRatio() {
        val res = CameraRepository.evaluateUltraHighResCapability(
            sdkInt = Build.VERSION_CODES.TIRAMISU,
            capabilities = capUltraHighRes,
            defaultRawSizes = arrayOf(Size(2000, 1500), Size(4000, 3000)),
            maxResRawSizes = arrayOf(Size(4000, 3000), Size(8000, 6000))
        )
        assertTrue(res.supported)
        assertEquals(Size(4000, 3000), res.defaultRawSize)
        assertEquals(Size(8000, 6000), res.maxResolutionRawSize)
        assertEquals(4.0f, res.areaRatio, 1e-4f)
    }

    @Test
    fun evaluate_2xLinearRemosaic50MpVs12_5Mp_returnsSupported() {
        val res = CameraRepository.evaluateUltraHighResCapability(
            sdkInt = Build.VERSION_CODES.S,
            capabilities = capUltraHighRes,
            defaultRawSizes = arrayOf(Size(4096, 3072)),
            maxResRawSizes = arrayOf(Size(8192, 6144))
        )
        assertTrue(res.supported)
        assertEquals(4.0f, res.areaRatio, 1e-4f)
    }
}
