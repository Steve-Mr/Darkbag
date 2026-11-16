package top.maary.darkbag.zsl

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.StreamConfigurationMap
import android.util.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowCameraCharacteristics
import org.robolectric.shadows.StreamConfigurationMapBuilder
import top.maary.darkbag.camera.zsl.ZslStaticCapabilities
import java.lang.reflect.Array as ReflectArray

/**
 * 静态能力读取（S1-1）：RAW 支持/尺寸/最小帧时长映射、无 RAW 回落、
 * `getAvailableCaptureRequestKeys` 缺失或抛异常时不崩溃、capabilities 位解析容错。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ZslCapabilityTest {

    private fun streamConfigMap(
        rawSizes: List<Size>,
        minFrameDurationNs: Long? = null
    ): StreamConfigurationMap {
        val builder = StreamConfigurationMapBuilder.newBuilder()
        rawSizes.forEach { builder.addOutputSize(ImageFormat.RAW_SENSOR, it) }
        val map = builder.build()
        if (minFrameDurationNs != null && rawSizes.isNotEmpty()) {
            val durationClass = Class.forName(
                "android.hardware.camera2.params.StreamConfigurationDuration"
            )
            val constructor = durationClass.getConstructor(
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Long::class.javaPrimitiveType
            )
            val durations = ReflectArray.newInstance(durationClass, rawSizes.size)
            rawSizes.forEachIndexed { index, size ->
                ReflectArray.set(
                    durations,
                    index,
                    constructor.newInstance(
                        ImageFormat.RAW_SENSOR,
                        size.width,
                        size.height,
                        minFrameDurationNs
                    )
                )
            }
            val field = StreamConfigurationMap::class.java.getDeclaredField("mMinFrameDurations")
            field.isAccessible = true
            field.set(map, durations)
        }
        return map
    }

    private fun nonRawMap(): StreamConfigurationMap =
        StreamConfigurationMapBuilder.newBuilder()
            .addOutputSize(ImageFormat.PRIVATE, Size(4000, 3000))
            .build()

    private fun setAvailableRequestKeys(
        chars: CameraCharacteristics,
        keys: List<CaptureRequest.Key<*>>
    ) {
        val field = CameraCharacteristics::class.java.getDeclaredField("mAvailableRequestKeys")
        field.isAccessible = true
        field.set(chars, keys)
    }

    private fun newCharacteristics(): CameraCharacteristics =
        ShadowCameraCharacteristics.newCameraCharacteristics()

    @Test
    fun testRawLargestSizeDurationAndCapabilityFlagsAreMapped() {
        val chars = newCharacteristics()
        val shadow = Shadows.shadowOf(chars)
        shadow.set(
            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP,
            streamConfigMap(
                rawSizes = listOf(Size(1920, 1080), Size(4000, 3000), Size(1024, 768)),
                minFrameDurationNs = 33_333_333L
            )
        )
        shadow.set(
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL,
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL
        )
        shadow.set(
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES,
            intArrayOf(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW,
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_PRIVATE_REPROCESSING,
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_YUV_REPROCESSING
            )
        )
        shadow.set(CameraCharacteristics.SYNC_MAX_LATENCY, 2)
        shadow.set(CameraCharacteristics.REQUEST_PIPELINE_MAX_DEPTH, 3.toByte())
        setAvailableRequestKeys(chars, listOf(CaptureRequest.CONTROL_ENABLE_ZSL))

        val caps = ZslStaticCapabilities.from(chars)

        assertTrue(caps.rawSupported)
        assertEquals(4000, caps.rawWidth)
        assertEquals(3000, caps.rawHeight)
        assertEquals(33L, caps.rawMinFrameDurationMs)
        assertEquals(1000f / 33L, caps.maxRawFrameRate!!, 0.01f)
        assertEquals(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL, caps.hardwareLevel)
        assertTrue(caps.hasDeviceZslKey)
        assertTrue(caps.supportsPrivateReprocessing)
        assertTrue(caps.supportsYuvReprocessing)
        assertTrue(caps.supportsRawCapability)
        assertEquals(2, caps.syncMaxLatency)
        assertEquals(3, caps.pipelineMaxDepth)
    }

    @Test
    fun testNoRawOutputSizesYieldsUnsupportedAndZeroDimensions() {
        val chars = newCharacteristics()
        Shadows.shadowOf(chars).set(
            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP,
            nonRawMap()
        )

        val caps = ZslStaticCapabilities.from(chars)

        assertFalse(caps.rawSupported)
        assertEquals(0, caps.rawWidth)
        assertEquals(0, caps.rawHeight)
        assertNull(caps.rawMinFrameDurationMs)
        assertNull(caps.maxRawFrameRate)
        assertFalse(caps.hasDeviceZslKey)
        assertFalse(caps.supportsRawCapability)
    }

    @Test
    fun testMissingCharacteristicsNeverThrowAndFallBackToUnknown() {
        val caps = ZslStaticCapabilities.from(newCharacteristics())

        assertEquals(-1, caps.hardwareLevel)
        assertFalse(caps.rawSupported)
        assertEquals(0, caps.rawWidth)
        assertEquals(0, caps.rawHeight)
        assertNull(caps.rawMinFrameDurationMs)
        assertNull(caps.maxRawFrameRate)
        assertFalse(caps.hasDeviceZslKey)
        assertNull(caps.syncMaxLatency)
        assertNull(caps.pipelineMaxDepth)
        assertFalse(caps.supportsPrivateReprocessing)
        assertFalse(caps.supportsYuvReprocessing)
        assertFalse(caps.supportsRawCapability)
    }

    @Test
    fun testRawCapabilityFlagWithoutStreamConfigIsStillDetected() {
        val chars = newCharacteristics()
        Shadows.shadowOf(chars).set(
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES,
            intArrayOf(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)
        )

        val caps = ZslStaticCapabilities.from(chars)

        assertTrue(caps.supportsRawCapability)
        assertFalse(caps.rawSupported)
    }

    @Test
    fun testUnknownSyncLatencyMapsToNull() {
        val chars = newCharacteristics()
        Shadows.shadowOf(chars).set(
            CameraCharacteristics.SYNC_MAX_LATENCY,
            CameraCharacteristics.SYNC_MAX_LATENCY_UNKNOWN
        )

        val caps = ZslStaticCapabilities.from(chars)

        assertNull(caps.syncMaxLatency)
    }

    @Test
    fun testAvailableRequestKeysWithoutZslKeyIsFalse() {
        val chars = newCharacteristics()
        setAvailableRequestKeys(chars, listOf(CaptureRequest.CONTROL_AE_MODE))

        assertFalse(ZslStaticCapabilities.from(chars).hasDeviceZslKey)

        val empty = newCharacteristics()
        setAvailableRequestKeys(empty, emptyList())
        assertFalse(ZslStaticCapabilities.from(empty).hasDeviceZslKey)
    }

    @Test
    fun testZeroMinFrameDurationIsReportedAsUnknown() {
        val chars = newCharacteristics()
        Shadows.shadowOf(chars).set(
            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP,
            streamConfigMap(rawSizes = listOf(Size(4000, 3000)), minFrameDurationNs = 0L)
        )

        val caps = ZslStaticCapabilities.from(chars)

        assertTrue(caps.rawSupported)
        assertEquals(4000, caps.rawWidth)
        assertNull(caps.rawMinFrameDurationMs)
        assertNull(caps.maxRawFrameRate)
    }
}
