package top.maary.darkbag.camera.dispatch

import android.content.Context
import android.content.SharedPreferences
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.TotalCaptureResult
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
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
import org.robolectric.annotation.Config
import top.maary.darkbag.fragments.SettingsFragment
import top.maary.darkbag.models.CaptureMetadata
import top.maary.darkbag.pipeline.model.CaptureTaskSpec
import top.maary.darkbag.pipeline.sink.CaptureSink
import top.maary.darkbag.utils.DarkbagIdentity
import top.maary.darkbag.utils.HalfFrameManager
import top.maary.darkbag.utils.HalfFrameSessionStore
import top.maary.darkbag.utils.LensInfo
import top.maary.darkbag.utils.LocationHelper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CameraDispatchHelpersTest {

    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences
    private lateinit var cameraManager: CameraManager
    private lateinit var captureResults: MutableMap<Long, TotalCaptureResult>
    private lateinit var captureResultFlow: MutableSharedFlow<TotalCaptureResult>
    private lateinit var extractor: CameraCaptureMetadataExtractor
    private lateinit var assembler: CameraTaskSpecAssembler

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs = context.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
        cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        captureResults = mutableMapOf()
        captureResultFlow = MutableSharedFlow(replay = 1)
        extractor = CameraCaptureMetadataExtractor(
            context = context,
            camera2Manager = cameraManager,
            captureResults = captureResults,
            captureResultFlow = captureResultFlow,
            locationHelper = LocationHelper(context),
            currentLensProvider = { null },
            isHdrPlusEnabledProvider = { true }
        )
        assembler = CameraTaskSpecAssembler(extractor)
    }

    // -------------------------------------------------------------------------
    // CameraCaptureMetadataExtractor tests
    // -------------------------------------------------------------------------

    @Test
    fun testMetadataExtractor_defaultsWhenResultAndCharsNull() {
        val (wb, ccm) = extractor.extractWbAndCcm(null)
        assertArrayEquals(floatArrayOf(2.0f, 1.0f, 1.0f, 1.5f), wb, 0.001f)
        assertEquals(9, ccm.size)

        val blackLevel = extractor.extractBlackLevelPattern(null)
        assertArrayEquals(intArrayOf(64, 64, 64, 64), blackLevel)

        assertNull(extractor.extractActiveArray(null))
        assertNull(extractor.extractNoiseProfile(null))

        val (lsc, rows, cols) = extractor.findLatestLensShadingMap(null)
        assertNull(lsc)
        assertEquals(0, rows)
        assertEquals(0, cols)
    }

    @Test
    fun testMetadataExtractor_createCaptureMetadataPopulatesIdentityAndExif() {
        val now = 1_700_000_000_000L
        val meta = extractor.createCaptureMetadata(
            iso = 400,
            exposureTime = 20_000_000L,
            fNumber = 1.8f,
            focalLength = 6.9f,
            captureTime = now,
            targetCharId = "0",
            isHdrPlus = true,
            captureResult = null,
            capturedLens = null
        )
        assertEquals(400, meta.iso)
        assertEquals(20_000_000L, meta.exposureTime)
        assertEquals(1.8f, meta.fNumber ?: 0f, 0.001f)
        assertEquals(6.9f, meta.focalLength ?: 0f, 0.001f)
        assertEquals(now, meta.dateTimeOriginal)
        assertEquals(DarkbagIdentity.softwareString(true), meta.software)
        assertEquals(DarkbagIdentity.imageDescription(true), meta.imageDescription)
    }

    @Test
    fun testMetadataExtractor_sensorCalibrationFallbackIdentity() {
        val calib = extractor.extractSensorCalibration(null, null, null)
        assertEquals(21, calib.calibrationIlluminant1)
        assertEquals(17, calib.calibrationIlluminant2)
        // Identity fallback for CM1/CM2
        assertEquals(1f, calib.colorMatrix1[0], 0.001f)
        assertEquals(1f, calib.colorMatrix1[4], 0.001f)
        assertEquals(1f, calib.colorMatrix1[8], 0.001f)
        assertNull(calib.forwardMatrix1)
    }

    // -------------------------------------------------------------------------
    // CameraTaskSpecAssembler tests
    // -------------------------------------------------------------------------

    @Test
    fun testSpecAssembler_resolveEffectiveZoomAndFusionMode() {
        val presetLens = LensInfo(
            id = "0",
            physicalId = null,
            sensorId = "0_50mm",
            name = "2x",
            focalLength = 6.9f,
            equivalentFocalLength = 48f,
            multiplier = 2.0f,
            type = top.maary.darkbag.utils.LensType.WIDE,
            facing = CameraCharacteristics.LENS_FACING_BACK,
            isZoomPreset = true,
            targetZoomRatio = 2.0f
        )
        val normalLens = LensInfo(
            id = "0",
            physicalId = null,
            sensorId = "0",
            name = "1x",
            focalLength = 6.9f,
            equivalentFocalLength = 24f,
            multiplier = 1.0f,
            type = top.maary.darkbag.utils.LensType.WIDE,
            facing = CameraCharacteristics.LENS_FACING_BACK,
            isZoomPreset = false
        )
        assertEquals(2.0f, assembler.resolveEffectiveZoom(presetLens, null), 0.001f)
        assertEquals(1.0f, assembler.resolveEffectiveZoom(normalLens, presetLens), 0.001f)
        assertEquals(1.5f, assembler.resolveEffectiveZoom(null, null, defaultZoom = 1.5f), 0.001f)

        prefs.edit().putString(SettingsFragment.KEY_HDR_FUSION_MODE, SettingsFragment.HDR_FUSION_SPATIAL_RCD).apply()
        assertEquals(1, assembler.resolveFusionModeInt(prefs))
        prefs.edit().putString(SettingsFragment.KEY_HDR_FUSION_MODE, SettingsFragment.HDR_FUSION_SABRE).apply()
        assertEquals(2, assembler.resolveFusionModeInt(prefs))
        prefs.edit().putString(SettingsFragment.KEY_HDR_FUSION_MODE, SettingsFragment.HDR_FUSION_CLASSIC).apply()
        assertEquals(3, assembler.resolveFusionModeInt(prefs))
    }

    @Test
    fun testSpecAssembler_buildCaptureBaseNameAndMotionPhotoAwait() = runBlocking {
        val hf1 = HalfFrameManager.Metadata(
            profile = HalfFrameSessionStore.PROFILE_HALF_SIDE,
            dateStamp = false,
            captureTimeMillis = 1_700_000_000_000L
        )
        val hf2 = hf1.copy(frame1BaseName = "2023-11-14-12-00-00-000")

        val name1 = assembler.buildCaptureBaseName(hf1, isHdrPlus = true)
        val name2 = assembler.buildCaptureBaseName(hf2, isHdrPlus = false)
        assertTrue(name1.endsWith("_HF1_HDRPLUS"))
        assertTrue(name2.endsWith("_HF2"))

        var cleared = false
        val deferred = CompletableDeferred<Pair<String?, Long>>().apply {
            complete(Pair("/tmp/motion.mp4", 42_000L))
        }
        val (path, pts) = assembler.awaitPendingMotionPhoto(
            pendingTaskProvider = { deferred },
            clearPendingTask = { cleared = true }
        )
        assertTrue(cleared)
        assertEquals("/tmp/motion.mp4", path)
        assertEquals(42_000L, pts)
    }

    @Test
    fun testSpecAssembler_assembleSpecPreservesSinkAndParameters() {
        val fakeSink = object : CaptureSink {
            override fun prepareTargets(context: Context, spec: CaptureTaskSpec) =
                top.maary.darkbag.pipeline.sink.SinkOutputTargets()
            override suspend fun onImageExported(context: Context, spec: CaptureTaskSpec, success: Boolean, outputPath: String?, outputFd: Int) {}
            override suspend fun onRawExported(context: Context, spec: CaptureTaskSpec, success: Boolean, outputPath: String?, outputFd: Int) {}
            override suspend fun onComplete(context: Context, spec: CaptureTaskSpec, jpgSuccess: Boolean, rawSuccess: Boolean) {}
            override suspend fun onError(context: Context, spec: CaptureTaskSpec, error: Throwable) {}
        }
        val hfMeta = HalfFrameManager.Metadata(
            profile = HalfFrameSessionStore.PROFILE_HALF_TOP,
            dateStamp = true,
            frame1DigitalGain = 1.25f,
            flareType = 1
        )
        prefs.edit()
            .putString(SettingsFragment.KEY_TARGET_LOG, "LogC")
            .putString(SettingsFragment.KEY_HDR_FUSION_MODE, SettingsFragment.HDR_FUSION_SABRE)
            .putInt(SettingsFragment.KEY_DNG_COMPRESSION_MODE, 1)
            .apply()

        val calib = extractor.extractSensorCalibration(null, null, null)
        val captureMeta = CaptureMetadata(iso = 200, exposureTime = 10_000_000L, dateTimeOriginal = 123456789L)
        val spec = assembler.assemble(
            context = context,
            prefs = prefs,
            request = TaskSpecAssemblyRequest(
                width = 4000,
                height = 3000,
                orientation = 90,
                zoomFactor = 1.5f,
                mirror = true,
                isSingleFrame = false,
                isHdrPlus = true,
                targetCharId = "0",
                chars = null,
                captureResult = null,
                captureMetadata = captureMeta,
                cfa = 0,
                whiteLevel = 1023,
                blackLevelPattern = intArrayOf(64, 64, 64, 64),
                wb = floatArrayOf(2f, 1f, 1f, 1.5f),
                ccm = FloatArray(9),
                calibration = calib,
                lensShadingMapData = null,
                lensShadingRows = 0,
                lensShadingCols = 0,
                noiseProfile = null,
                digitalGain = 2.0f,
                faithfulHighlights = false,
                hfMetadata = hfMeta,
                motionPhotoMp4Path = "/tmp/m.mp4",
                motionPhotoStillPtsUs = 100L,
                sink = fakeSink
            )
        )

        assertSame(fakeSink, spec.sink)
        assertEquals(4000, spec.width)
        assertEquals(3000, spec.height)
        assertEquals(90, spec.orientation)
        assertEquals(1.5f, spec.zoomFactor, 0.001f)
        assertTrue(spec.mirror)
        assertFalse(spec.isSingleFrame)
        assertEquals(2, spec.fusionMode)
        assertEquals(1, spec.dngCompressionMode)
        assertEquals("TB", spec.renderRecipe.editConfig?.hfLayout)
        assertEquals(2.0f, spec.hfMetadata?.digitalGain ?: 0f, 0.001f)
        assertEquals("/tmp/m.mp4", spec.motionPhotoMp4Path)

        val failSpec = assembler.buildFailureSpec(
            width = 100, height = 100, orientation = 0, lensId = "2", hfMetadata = hfMeta, sink = fakeSink
        )
        assertSame(fakeSink, failSpec.sink)
        assertEquals("2", failSpec.hardwareProfile.lensId)
        assertNotNull(failSpec.taskId)
    }
}
