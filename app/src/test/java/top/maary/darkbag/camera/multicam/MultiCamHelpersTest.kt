package top.maary.darkbag.camera.multicam

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.util.Range
import android.util.Size
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowCameraCharacteristics
import top.maary.darkbag.camera.MultiCameraCaptureManager
import top.maary.darkbag.camera.MultiCameraManualConfig
import top.maary.darkbag.models.CaptureMetadata
import top.maary.darkbag.utils.DarkbagIdentity
import top.maary.darkbag.utils.LensType
import top.maary.darkbag.utils.LogicalMultiCameraInfo
import top.maary.darkbag.utils.MultiCameraHardwareType
import top.maary.darkbag.utils.PhysicalLensInfo
import java.nio.ByteBuffer
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MultiCamHelpersTest {

    private lateinit var context: Context
    private lateinit var cameraManager: CameraManager
    private lateinit var dngWriter: MultiCamDngMetadataWriter
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        dngWriter = MultiCamDngMetadataWriter(context, cameraManager)
    }

    @Test
    fun dngWriter_calculateDigitalGain_and_assembleFrame() {
        val chars = ShadowCameraCharacteristics.newCameraCharacteristics()
        Shadows.shadowOf(chars).set(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE, Range(100, 3200))
        Shadows.shadowOf(chars).set(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES, floatArrayOf(1.8f))

        // Null result defaults to 1.0f
        assertEquals(1.0f, dngWriter.calculateDigitalGain(null, chars), 0.001f)

        // Deficit compensation applied when targetExposureProduct exceeds actual (default 100 * 10ms = 1e9)
        val compensated = dngWriter.calculateDigitalGain(null, chars, targetExposureProduct = 4_000_000_000.0)
        assertEquals(4.0f, compensated, 0.01f)

        // Clamped at 64.0f
        val clamped = dngWriter.calculateDigitalGain(null, chars, targetExposureProduct = 1_000_000_000_000.0)
        assertEquals(64.0f, clamped, 0.001f)

        val lens = PhysicalLensInfo(
            physicalId = "2",
            name = "0.6x",
            focalLength = 1.9f,
            equivalentFocalLength = 14f,
            multiplier = 0.6f,
            type = LensType.ULTRA_WIDE,
            characteristics = chars
        )
        val meta = dngWriter.createMetadataFromCaptureResult(null, lens, captureTime = 1_700_000_000_000L)
        assertEquals(1.9f, meta.focalLength ?: 0f, 0.001f)
        assertEquals(14, meta.focalLengthIn35mmFilm)
        assertEquals(DarkbagIdentity.uniqueCameraModel("2"), meta.uniqueCameraModel)
        assertTrue(meta.lensModel!!.contains("0.6x"))

        val frame = dngWriter.assembleFrame(
            lens = lens,
            jpegPair = byteArrayOf(1, 2, 3) to Size(4032, 3024),
            dngPath = "/tmp/test.dng",
            captureResult = null,
            orientationDegrees = 90,
            captureTimestamp = 1_700_000_000_000L
        )
        assertEquals(4032, frame.width)
        assertEquals(3024, frame.height)
        assertEquals(90, frame.orientation)
        assertEquals("/tmp/test.dng", frame.tempDngPath)
        assertNotNull(frame.captureMetadata)
    }

    @Test
    fun dngWriter_isRawSupportedAndOptimalOutputSize_handlesMissingMap() {
        val chars = ShadowCameraCharacteristics.newCameraCharacteristics()
        val lens = PhysicalLensInfo("0", "1x", 4.5f, 24f, 1.0f, LensType.WIDE, chars)
        assertFalse(dngWriter.isRawSupportedForLens(lens))
        assertEquals(Size(4000, 3000), dngWriter.getOptimalOutputSize(lens, ImageFormat.JPEG))
    }

    @Test
    fun previewController_resolvesActiveLensAndRelaySwitching() {
        val wideChars = ShadowCameraCharacteristics.newCameraCharacteristics()
        Shadows.shadowOf(wideChars).set(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE, Rect(0, 0, 4000, 3000))
        val uwChars = ShadowCameraCharacteristics.newCameraCharacteristics()
        Shadows.shadowOf(uwChars).set(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE, Rect(0, 0, 3264, 2448))
        val teleChars = ShadowCameraCharacteristics.newCameraCharacteristics()

        val wideLens = PhysicalLensInfo("0", "1.0x", 4.5f, 24f, 1.0f, LensType.WIDE, wideChars)
        val uwLens = PhysicalLensInfo("2", "0.6x", 1.9f, 14f, 0.6f, LensType.ULTRA_WIDE, uwChars)
        val teleLens = PhysicalLensInfo("3", "3.0x", 11.0f, 70f, 3.0f, LensType.TELE, teleChars)

        val ctrl = MultiCameraPreviewController(
            cameraManager = cameraManager,
            scope = scope,
            executor = Executors.newSingleThreadExecutor(),
            dngWriter = dngWriter,
            onFallbackToRelay = { _, _ -> }
        )

        val relayInfo = LogicalMultiCameraInfo(
            logicalCameraId = "0",
            isLogicalMultiCamera = false,
            syncType = 0,
            physicalLenses = listOf(uwLens, wideLens, teleLens),
            hardwareType = MultiCameraHardwareType.FAST_RELAY_BURST
        )
        ctrl.configurePreviewStateForTesting(
            logicalInfo = relayInfo,
            lenses = listOf(uwLens, wideLens, teleLens),
            primaryLens = wideLens,
            standalonePrimaryId = null
        )

        assertEquals("0", ctrl.resolveActivePreviewPhysicalId())
        assertSame(wideChars, ctrl.getActivePreviewPhysicalCharacteristics())

        // No previewSurface -> setPrimaryPreviewLens updates currentPrimaryLens cleanly without crashing
        ctrl.switchPrimaryPreviewLensByMultiplier(2.8f) { _, _, _ -> }
        assertSame(teleLens, ctrl.currentPrimaryLens)
        assertEquals("3", ctrl.resolveActivePreviewPhysicalId())
        assertSame(teleChars, ctrl.getActivePreviewPhysicalCharacteristics())

        // Standalone pins preview physical ID to standalonePrimaryId
        val standaloneInfo = relayInfo.copy(hardwareType = MultiCameraHardwareType.CONCURRENT_STANDALONE)
        ctrl.configurePreviewStateForTesting(
            logicalInfo = standaloneInfo,
            lenses = listOf(uwLens, wideLens),
            primaryLens = uwLens,
            standalonePrimaryId = "0"
        )
        assertEquals("0", ctrl.resolveActivePreviewPhysicalId())
        assertSame(wideChars, ctrl.getActivePreviewPhysicalCharacteristics())

        ctrl.closeInternal()
        assertNull(ctrl.cameraDevice)
        assertNull(ctrl.captureSession)
        assertNull(ctrl.lastPreviewResult)
    }

    @Test
    fun multiCameraCaptureManager_resolveManualControlSpec_linkedVsUnlinked() {
        val manager = MultiCameraCaptureManager(context, scope)
        val defaultSpec = manager.resolveManualControlSpec(isPrimary = false, config = null)
        assertEquals(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE, defaultSpec.afMode)
        assertEquals(CaptureRequest.CONTROL_AE_MODE_ON, defaultSpec.aeMode)

        val manualCfg = MultiCameraManualConfig(
            isLinked = true,
            isManualFocus = true,
            focusDistance = 5.0f,
            isManualIso = true,
            iso = 800,
            isManualShutter = true,
            exposureTimeNanos = 20_000_000L
        )
        val linkedSecondary = manager.resolveManualControlSpec(isPrimary = false, config = manualCfg)
        assertEquals(CaptureRequest.CONTROL_AF_MODE_OFF, linkedSecondary.afMode)
        assertEquals(5.0f, linkedSecondary.focusDistance ?: 0f, 0.001f)
        assertEquals(CaptureRequest.CONTROL_AE_MODE_OFF, linkedSecondary.aeMode)
        assertEquals(800, linkedSecondary.iso)
        assertEquals(20_000_000L, linkedSecondary.exposureTimeNanos)

        val unlinkedSecondary = manager.resolveManualControlSpec(
            isPrimary = false,
            config = manualCfg.copy(isLinked = false),
            isMeteringPrimary = false
        )
        assertEquals(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE, unlinkedSecondary.afMode)
        assertEquals(CaptureRequest.CONTROL_AE_MODE_ON, unlinkedSecondary.aeMode)
        assertNull(unlinkedSecondary.iso)
        assertNull(unlinkedSecondary.focusDistance)
    }

    @Test
    fun multiCamPrimaryHdrHelper_calculatesExposureCorrectly() {
        val chars = ShadowCameraCharacteristics.newCameraCharacteristics()
        Shadows.shadowOf(chars).set(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE, Range(100, 3200))
        Shadows.shadowOf(chars).set(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE, Range(100_000L, 1_000_000_000L))

        val primaryLens = PhysicalLensInfo("0", "1x", 4.5f, 24f, 1.0f, LensType.WIDE, chars)
        val helper = MultiCamPrimaryHdrHelper(
            context = context,
            lens = primaryLens,
            chars = chars,
            dngWriter = dngWriter,
            orientationDegrees = 0,
            captureTimestamp = 1_700_000_000_000L
        )

        // Without preview result and no manual Ae, exposure calculation returns null (no blind 400/30ms guessing)
        assertNull(helper.calculateExposure(null))

        // With manual AE, returns manual spec directly with 1.0f burstGain
        val manualConfig = MultiCameraManualConfig(
            isManualIso = true,
            iso = 800,
            isManualShutter = true,
            exposureTimeNanos = 20_000_000L
        )
        val expSpec = helper.calculateExposure(null, manualConfig)
        assertNotNull(expSpec)
        assertEquals(1.0f, expSpec!!.burstGain, 0.01f)
        assertEquals(800, expSpec.burstIso)
        assertEquals(20_000_000L, expSpec.burstTimeNs)
    }

    @Test
    fun multiCameraCaptureManager_isRawSupportedForLens() {
        val manager = MultiCameraCaptureManager(context, scope)
        val chars = ShadowCameraCharacteristics.newCameraCharacteristics()
        val lens = PhysicalLensInfo("0", "1x", 4.5f, 24f, 1.0f, LensType.WIDE, chars)
        // No RAW_SENSOR in stream configuration map -> false
        assertFalse(manager.isRawSupportedForLens(lens))
    }

    @Test
    fun dngWriter_assembleFrame_withForcedDigitalGain() {
        val chars = ShadowCameraCharacteristics.newCameraCharacteristics()
        val lens = PhysicalLensInfo("0", "1x", 4.5f, 24f, 1.0f, LensType.WIDE, chars)
        val frame = dngWriter.assembleFrame(
            lens = lens,
            jpegPair = null,
            dngPath = "/tmp/fused.dng",
            captureResult = null,
            orientationDegrees = 0,
            captureTimestamp = 1000L,
            forcedDigitalGain = 2.0f
        )
        assertEquals(2.0f, frame.digitalGain, 0.001f)
        assertEquals("/tmp/fused.dng", frame.tempDngPath)
    }

    @Test
    fun multiCamPrimaryHdrHelper_abort_preservesFallbackWhenSpecified() {
        val chars = ShadowCameraCharacteristics.newCameraCharacteristics()
        val primaryLens = PhysicalLensInfo("0", "1x", 4.5f, 24f, 1.0f, LensType.WIDE, chars)
        val helper = MultiCamPrimaryHdrHelper(
            context = context,
            lens = primaryLens,
            chars = chars,
            dngWriter = dngWriter,
            orientationDegrees = 0,
            captureTimestamp = 1_700_000_000_000L
        )

        val testBuffer = ByteBuffer.allocateDirect(1024)
        helper.fallbackBuffer = testBuffer

        // abort(clearFallback = false) keeps fallbackBuffer for writeFallbackSingleFrameDng
        helper.abort(clearFallback = false)
        assertSame(testBuffer, helper.fallbackBuffer)

        // abort(clearFallback = true) purges the buffer
        helper.abort(clearFallback = true)
        assertNull(helper.fallbackBuffer)
    }

    @Test
    fun multiCamPrimaryHdrHelper_writeFallbackSingleFrameDng_handlesNullBufferDefensively() {
        val chars = ShadowCameraCharacteristics.newCameraCharacteristics()
        val primaryLens = PhysicalLensInfo("0", "1x", 4.5f, 24f, 1.0f, LensType.WIDE, chars)
        val helper = MultiCamPrimaryHdrHelper(
            context = context,
            lens = primaryLens,
            chars = chars,
            dngWriter = dngWriter,
            orientationDegrees = 0,
            captureTimestamp = 1_700_000_000_000L
        )

        helper.fallbackBuffer = null
        val dummyMeta = CaptureMetadata(
            exposureTime = 10_000_000L,
            iso = 100,
            focalLength = 24f,
            fNumber = 1.8f,
            dateTimeOriginal = 1_700_000_000_000L
        )
        val res = helper.writeFallbackSingleFrameDng(null, dummyMeta)
        assertNull(res)
    }
}
