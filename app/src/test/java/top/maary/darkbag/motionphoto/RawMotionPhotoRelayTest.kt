package top.maary.darkbag.motionphoto

import android.hardware.camera2.CaptureResult
import android.hardware.camera2.params.LensShadingMap
import android.hardware.camera2.params.RggbChannelVector
import android.media.FakeRawImage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowTotalCaptureResult
import java.nio.ByteBuffer

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RawMotionPhotoRelayTest {

    @Test
    fun `throttlingDropsFramesWithIntervalLessThan33ms`() {
        val dispatchedTimestamps = mutableListOf<Long>()
        val relay = RawMotionPhotoRelay(
            lutSurfaceProcessorProvider = { null },
            characteristicsProvider = { null },
            nativeSubsampler = { _, w, h, _, _ -> ((w / 4) shl 16) or (h / 4) },
            frameConsumer = { _, _, _, _, _, _, _, _, ts ->
                dispatchedTimestamps.add(ts)
            }
        )

        relay.start()

        val buf = ByteBuffer.allocateDirect(1008 * 756 * 2)

        // Frame 1
        relay.onRawFrame(FakeRawImage(1008, 756, 1_000_000_000L, buf), null)
        assertEquals(1, dispatchedTimestamps.size)

        // Frame 2 arrives 20ms later (< 33ms) -> must be dropped
        relay.onRawFrame(FakeRawImage(1008, 756, 1_020_000_000L, buf), null)
        assertEquals(1, dispatchedTimestamps.size)

        // Frame 3 arrives 40ms after Frame 1 (>= 33ms) -> must be dispatched
        relay.onRawFrame(FakeRawImage(1008, 756, 1_040_000_000L, buf), null)
        assertEquals(2, dispatchedTimestamps.size)
    }

    @Test
    fun `firstFrameWithSmallTimestampIsNotDropped`() {
        var dispatched = 0
        val relay = RawMotionPhotoRelay(
            lutSurfaceProcessorProvider = { null },
            characteristicsProvider = { null },
            nativeSubsampler = { _, w, h, _, _ -> ((w / 4) shl 16) or (h / 4) },
            frameConsumer = { _, _, _, _, _, _, _, _, _ -> dispatched++ }
        )
        relay.start()
        val buf = ByteBuffer.allocateDirect(1008 * 756 * 2)
        // Timestamp 10ms (< 33ms) on first frame must still be processed
        relay.onRawFrame(FakeRawImage(1008, 756, 10_000_000L, buf), null)
        assertEquals(1, dispatched)
    }

    @Test
    fun `doubleBufferRotatesBetweenTwoDistinctBuffers`() {
        val capturedBuffers = mutableListOf<ByteBuffer>()
        val relay = RawMotionPhotoRelay(
            lutSurfaceProcessorProvider = { null },
            characteristicsProvider = { null },
            nativeSubsampler = { _, w, h, _, _ -> ((w / 4) shl 16) or (h / 4) },
            frameConsumer = { bayerBuffer, _, _, _, _, _, _, _, _ ->
                capturedBuffers.add(bayerBuffer)
            }
        )

        relay.start()

        val buf = ByteBuffer.allocateDirect(1008 * 756 * 2)

        // Frame 1
        relay.onRawFrame(FakeRawImage(1008, 756, 1_000_000_000L, buf), null)
        // Frame 2 (50ms later)
        relay.onRawFrame(FakeRawImage(1008, 756, 1_050_000_000L, buf), null)

        assertEquals(2, capturedBuffers.size)
        assertNotSame("Double buffering must rotate to distinct ByteBuffers", capturedBuffers[0], capturedBuffers[1])
    }

    @Test
    fun `bufferCapacityExpandsForLargeSensors`() {
        val relay = RawMotionPhotoRelay(
            lutSurfaceProcessorProvider = { null },
            characteristicsProvider = { null },
            nativeSubsampler = { _, w, h, _, _ -> ((w / 4) shl 16) or (h / 4) },
            frameConsumer = { _, _, _, _, _, _, _, _, _ -> }
        )

        assertEquals(RawMotionPhotoRelay.INITIAL_BUFFER_CAPACITY, relay.currentBufferCapacity)

        relay.start()

        // 50MP sensor: 8192 x 6144 -> dst 2048 x 1536 -> required 6,291,456 bytes
        val largeBuf = ByteBuffer.allocateDirect(1000)
        relay.onRawFrame(FakeRawImage(8192, 6144, 1_000_000_000L, largeBuf), null)

        val requiredBytes = (8192 / 4) * (6144 / 4) * 2
        assertTrue(
            "Buffer capacity ${relay.currentBufferCapacity} must expand to >= $requiredBytes",
            relay.currentBufferCapacity >= requiredBytes
        )
    }

    @Test
    fun `relayRespectsStartAndStopLifecycle`() {
        var dispatched = 0
        val relay = RawMotionPhotoRelay(
            lutSurfaceProcessorProvider = { null },
            characteristicsProvider = { null },
            nativeSubsampler = { _, w, h, _, _ -> ((w / 4) shl 16) or (h / 4) },
            frameConsumer = { _, _, _, _, _, _, _, _, _ -> dispatched++ }
        )

        val buf = ByteBuffer.allocateDirect(1008 * 756 * 2)

        // Stopped -> frames are ignored
        relay.onRawFrame(FakeRawImage(1008, 756, 1_000_000_000L, buf), null)
        assertEquals(0, dispatched)

        // Started -> frame dispatched
        relay.start()
        relay.onRawFrame(FakeRawImage(1008, 756, 1_000_000_000L, buf), null)
        assertEquals(1, dispatched)

        // Stopped again -> subsequent frames ignored
        relay.stop()
        relay.onRawFrame(FakeRawImage(1008, 756, 1_100_000_000L, buf), null)
        assertEquals(1, dispatched)
    }

    @Test
    fun `qualityModeSelectsCorrectDimensionsAndModes`() {
        val capturedModes = mutableListOf<Int>()
        var capturedQuality = top.maary.darkbag.repository.PreferenceKeys.RAW_MOTION_QUALITY_2X2
        val relay = RawMotionPhotoRelay(
            lutSurfaceProcessorProvider = { null },
            characteristicsProvider = { null },
            qualityModeProvider = { capturedQuality },
            nativeProcessor = { _, w, h, _, _, mode ->
                capturedModes.add(mode)
                when (mode) {
                    2 -> (w shl 16) or h
                    1 -> ((w / 2) shl 16) or (h / 2)
                    else -> ((w / 4) shl 16) or (h / 4)
                }
            },
            frameConsumer = { _, _, _, _, _, _, _, _, _, _, _, _ -> }
        )

        relay.start()
        val buf = ByteBuffer.allocateDirect(1008 * 756 * 2)

        // 1. 2x2 Binning -> mode 1
        capturedQuality = top.maary.darkbag.repository.PreferenceKeys.RAW_MOTION_QUALITY_2X2
        relay.onRawFrame(FakeRawImage(1008, 756, 1_000_000_000L, buf), null)
        assertEquals(listOf(1), capturedModes)

        // 2. 4x4 Subsample -> mode 0
        capturedQuality = top.maary.darkbag.repository.PreferenceKeys.RAW_MOTION_QUALITY_4X4
        relay.onRawFrame(FakeRawImage(1008, 756, 1_050_000_000L, buf), null)
        assertEquals(listOf(1, 0), capturedModes)

        // 3. Native -> mode 2
        capturedQuality = top.maary.darkbag.repository.PreferenceKeys.RAW_MOTION_QUALITY_NATIVE
        relay.onRawFrame(FakeRawImage(1008, 756, 1_100_000_000L, buf), null)
        assertEquals(listOf(1, 0, 2), capturedModes)
    }

    @Test
    fun `stringQualityProviderOverloadHandlesLegacyVariations`() {
        val capturedModes = mutableListOf<Int>()
        var capturedQuality = "Native (Max Quality)"
        val relay = RawMotionPhotoRelay.createWithLegacyQuality(
            lutSurfaceProcessorProvider = { null },
            characteristicsProvider = { null },
            stringQualityProvider = { capturedQuality },
            nativeProcessor = { _, w, h, _, _, mode ->
                capturedModes.add(mode)
                when (mode) {
                    2 -> (w shl 16) or h
                    1 -> ((w / 2) shl 16) or (h / 2)
                    else -> ((w / 4) shl 16) or (h / 4)
                }
            },
            frameConsumer = { _, _, _, _, _, _, _, _, _, _, _, _ -> }
        )

        relay.start()
        val buf = ByteBuffer.allocateDirect(1008 * 756 * 2)

        // Native legacy string
        capturedQuality = "Native (Max Quality)"
        relay.onRawFrame(FakeRawImage(1008, 756, 1_000_000_000L, buf), null)
        assertEquals(listOf(2), capturedModes)

        // Chinese 4x4 subsample string
        capturedQuality = "4x4 抽样"
        relay.onRawFrame(FakeRawImage(1008, 756, 1_050_000_000L, buf), null)
        assertEquals(listOf(2, 0), capturedModes)
    }

    @Test
    fun `parseQualityModeHandlesAllVariations`() {
        assertEquals(2, RawMotionPhotoRelay.parseQualityMode("Native"))
        assertEquals(2, RawMotionPhotoRelay.parseQualityMode("Native (Max Quality)"))
        assertEquals(2, RawMotionPhotoRelay.parseQualityMode("Native Resolution"))
        assertEquals(2, RawMotionPhotoRelay.parseQualityMode("原生全像素"))
        assertEquals(2, RawMotionPhotoRelay.parseQualityMode("2"))

        assertEquals(0, RawMotionPhotoRelay.parseQualityMode("4x4 Subsample"))
        assertEquals(0, RawMotionPhotoRelay.parseQualityMode("4x4 Subsample (Fast)"))
        assertEquals(0, RawMotionPhotoRelay.parseQualityMode("4x4 抽样"))
        assertEquals(0, RawMotionPhotoRelay.parseQualityMode("0"))

        assertEquals(1, RawMotionPhotoRelay.parseQualityMode("2x2 Binning"))
        assertEquals(1, RawMotionPhotoRelay.parseQualityMode("2x2 Binning (Balanced)"))
        assertEquals(1, RawMotionPhotoRelay.parseQualityMode("2x2 合并"))
        assertEquals(1, RawMotionPhotoRelay.parseQualityMode(null))
        assertEquals(1, RawMotionPhotoRelay.parseQualityMode(""))
    }

    @Test
    fun `nativeModeClampsTo2x2On50MpSensorAndReclaimsOversizedBuffersOnStop`() {
        val capturedModes = mutableListOf<Int>()
        var quality = top.maary.darkbag.repository.PreferenceKeys.RAW_MOTION_QUALITY_NATIVE
        val relay = RawMotionPhotoRelay(
            lutSurfaceProcessorProvider = { null },
            characteristicsProvider = { null },
            qualityModeProvider = { quality },
            nativeProcessor = { _, w, h, _, _, mode ->
                capturedModes.add(mode)
                when (mode) {
                    2 -> (w shl 16) or h
                    1 -> ((w / 2) shl 16) or (h / 2)
                    else -> ((w / 4) shl 16) or (h / 4)
                }
            },
            frameConsumer = { _, _, _, _, _, _, _, _, _, _, _, _ -> }
        )

        relay.start()
        val smallBuf = ByteBuffer.allocateDirect(1024)

        // 50MP sensor (8192x6144) in Native mode must clamp to 2x2 Binning (mode 1)
        relay.onRawFrame(FakeRawImage(8192, 6144, 1_000_000_000L, smallBuf), null)
        assertEquals(listOf(RawMotionQuality.BINNING_2X2.mode), capturedModes)
        val expected2x2Bytes = 4096 * 3072 * 2
        assertEquals(expected2x2Bytes, relay.currentBufferCapacity)
        assertTrue(relay.currentBufferCapacity > RawMotionPhotoRelay.OVERSIZED_BUFFER_THRESHOLD_BYTES)

        // Stopping relay reclaims oversized (>8MB) direct buffers back to INITIAL_BUFFER_CAPACITY
        relay.stop()
        assertEquals(RawMotionPhotoRelay.INITIAL_BUFFER_CAPACITY, relay.currentBufferCapacity)
    }

    @Test
    fun `mapBlackLevelToRgbg mapsAllFourCfaPatternsCorrectly`() {
        val grid = floatArrayOf(10f, 20f, 30f, 40f) // [TL(0,0), TR(1,0), BL(0,1), BR(1,1)]
        assertArrayEquals(floatArrayOf(10f, 20f, 30f, 40f), RawMotionPhotoRelay.mapBlackLevelToRgbg(grid, 0), 1e-5f) // RGGB
        assertArrayEquals(floatArrayOf(20f, 10f, 40f, 30f), RawMotionPhotoRelay.mapBlackLevelToRgbg(grid, 1), 1e-5f) // GRBG
        assertArrayEquals(floatArrayOf(30f, 40f, 10f, 20f), RawMotionPhotoRelay.mapBlackLevelToRgbg(grid, 2), 1e-5f) // GBRG
        assertArrayEquals(floatArrayOf(40f, 30f, 20f, 10f), RawMotionPhotoRelay.mapBlackLevelToRgbg(grid, 3), 1e-5f) // BGGR
    }

    @Test
    fun `computeRenderCcmColMajor mapsNeutralToNeutralAndRespectsWbMode`() {
        // Real XT2409-5 ForwardMatrix1/2 & ColorMatrix1/2 & AsShotNeutral from sample DNG
        val fm1 = floatArrayOf(
            0.6731415f, 0.1950378f, 0.0960236f,
            0.2761841f, 0.8182068f, -0.0944061f,
            0.0216522f, -0.2324524f, 1.0360107f
        )
        val fm2 = floatArrayOf(
            0.5744934f, 0.1840057f, 0.2057037f,
            0.1938171f, 0.7453766f, 0.0607910f,
            -0.0144958f, -0.5286865f, 1.3684082f
        )
        val cm1 = floatArrayOf(
            0.6667938f, -0.1588898f, -0.0857391f,
            -0.5739441f, 1.3897858f, 0.1430206f,
            -0.1378784f, 0.2651520f, 0.6036224f
        )
        val cm2 = floatArrayOf(
            1.5314636f, -0.4696045f, -0.2150574f,
            -0.4762268f, 1.4453278f, 0.0066986f,
            -0.0717468f, 0.2387238f, 0.2329559f
        )
        val neutral = floatArrayOf(0.3923372f, 1.0f, 0.6918919f)
        val wbGains = floatArrayOf(1f / neutral[0], 1.0f, 1f / neutral[2])

        val ccmNeutralColMajor = RawMotionPhotoRelay.computeRenderCcmColMajor(
            fm1 = fm1,
            fm2 = fm2,
            cm1 = cm1,
            cm2 = cm2,
            illum1 = 21,
            illum2 = 17,
            neutralPoint = neutral,
            wbGains = wbGains,
            wbConfig = RawMotionWbConfig(wbMode = 0)
        )

        // Column-major matrix * [1, 1, 1]^T must map neutral white (after WB) to neutral sRGB [1, 1, 1]
        val rOut = ccmNeutralColMajor[0] + ccmNeutralColMajor[3] + ccmNeutralColMajor[6]
        val gOut = ccmNeutralColMajor[1] + ccmNeutralColMajor[4] + ccmNeutralColMajor[7]
        val bOut = ccmNeutralColMajor[2] + ccmNeutralColMajor[5] + ccmNeutralColMajor[8]
        assertEquals(1.0f, rOut, 0.02f)
        assertEquals(1.0f, gOut, 0.02f)
        assertEquals(1.0f, bOut, 0.02f)

        // Manual warm-look preset (7500K Shade illuminant, wbMode=2) shifts neutral [1,1,1] toward warmer R > B
        val ccmWarmColMajor = RawMotionPhotoRelay.computeRenderCcmColMajor(
            fm1 = fm1,
            fm2 = fm2,
            cm1 = cm1,
            cm2 = cm2,
            illum1 = 21,
            illum2 = 17,
            neutralPoint = neutral,
            wbGains = wbGains,
            wbConfig = RawMotionWbConfig(wbMode = 2, wbManualTempK = 7500f)
        )
        val rWarm = ccmWarmColMajor[0] + ccmWarmColMajor[3] + ccmWarmColMajor[6]
        val bWarm = ccmWarmColMajor[2] + ccmWarmColMajor[5] + ccmWarmColMajor[8]
        assertTrue("7500K manual WB must yield warmer R ($rWarm) > B ($bWarm)", rWarm > bWarm)
    }

    @Test
    fun `relayRetainsLastValidWbAndLscAcrossNullResultFramesAndClearsOnStop`() {
        val capturedWb = mutableListOf<FloatArray>()
        val capturedLsc = mutableListOf<FloatArray?>()
        val relay = RawMotionPhotoRelay(
            lutSurfaceProcessorProvider = { null },
            characteristicsProvider = { null },
            nativeProcessor = { _, w, h, _, _, _ -> ((w / 4) shl 16) or (h / 4) },
            frameConsumer = { _, _, _, _, _, _, wbGains, _, _, lscMap, _, _ ->
                capturedWb.add(wbGains.copyOf())
                capturedLsc.add(lscMap?.copyOf())
            }
        )

        val validResult = ShadowTotalCaptureResult.newTotalCaptureResult()
        Shadows.shadowOf(validResult).set(
            CaptureResult.COLOR_CORRECTION_GAINS,
            RggbChannelVector(2.55f, 1.0f, 1.0f, 1.45f)
        )
        val lscData = FloatArray(4 * 2 * 2) { 1.5f }
        val lscCtor = LensShadingMap::class.java.getDeclaredConstructor(
            FloatArray::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType
        ).apply { isAccessible = true }
        val lscMapObj = lscCtor.newInstance(lscData, 2, 2)
        Shadows.shadowOf(validResult).set(
            CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP,
            lscMapObj
        )

        relay.start()
        val buf = ByteBuffer.allocateDirect(1008 * 756 * 2)

        // Frame 1 with valid result -> caches WB [2.55, 1.0, 1.45] and LSC
        relay.onRawFrame(FakeRawImage(1008, 756, 1_000_000_000L, buf), validResult)
        // Frame 2 with null result -> must retain cached WB & LSC instead of reverting to [2.0, 1.0, 1.6] / null
        relay.onRawFrame(FakeRawImage(1008, 756, 1_050_000_000L, buf), null)

        assertEquals(2, capturedWb.size)
        assertArrayEquals(floatArrayOf(2.55f, 1.0f, 1.45f), capturedWb[0], 1e-4f)
        assertArrayEquals(floatArrayOf(2.55f, 1.0f, 1.45f), capturedWb[1], 1e-4f)
        assertNotNull(capturedLsc[0])
        assertNotNull(capturedLsc[1])

        // Restarting relay clears old session calibration cache
        relay.stop()
        relay.start()
        relay.onRawFrame(FakeRawImage(1008, 756, 2_000_000_000L, buf), null)
        assertArrayEquals(floatArrayOf(2.0f, 1.0f, 1.6f), capturedWb[2], 1e-4f)
        assertNull(capturedLsc[2])
    }
}
