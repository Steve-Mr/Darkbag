package top.maary.darkbag.camera.session

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.ImageReader
import android.view.Surface
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
import top.maary.darkbag.camera.state.CameraRuntimeStateHolder
import top.maary.darkbag.utils.LensInfo
import top.maary.darkbag.utils.LensType
import top.maary.darkbag.utils.LogicalMultiCameraInfo
import top.maary.darkbag.utils.MultiCameraHardwareType
import top.maary.darkbag.utils.PhysicalLensInfo

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CameraSessionArbiterTest {

    private lateinit var context: Context
    private lateinit var cameraManager: CameraManager
    private lateinit var state: CameraRuntimeStateHolder
    private lateinit var arbiter: CameraSessionArbiter
    private val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        state = CameraRuntimeStateHolder()
        arbiter = CameraSessionArbiter(cameraManager, state)
    }

    @Test
    fun testSingleCameraPreviewRoutingDefaults() {
        val surface = Surface(SurfaceTexture(0))
        arbiter.camera2PreviewSurface = surface
        arbiter.sessionController.startBackgroundThread()

        assertSame(arbiter.camera2Device, arbiter.activePreviewDevice)
        assertSame(arbiter.camera2Session, arbiter.activePreviewSession)
        assertSame(surface, arbiter.activePreviewSurface)
        assertSame(arbiter.camera2Handler, arbiter.activePreviewHandler)
        assertNull(arbiter.activePreviewPhysicalCharacteristics)

        arbiter.sessionController.stopBackgroundThread()
        surface.release()
    }

    @Test
    fun testMultiCameraPreviewRouting_nativeLogicalAndConcurrentStandalone() {
        val wideChars = ShadowCameraCharacteristics.newCameraCharacteristics()
        val uwChars = ShadowCameraCharacteristics.newCameraCharacteristics()
        val wideLens = PhysicalLensInfo("0", "1.0x", 4.5f, 24f, 1.0f, LensType.WIDE, wideChars)
        val uwLens = PhysicalLensInfo("2", "0.6x", 1.9f, 14f, 0.6f, LensType.ULTRA_WIDE, uwChars)

        val logicalInfo = LogicalMultiCameraInfo(
            logicalCameraId = "0",
            isLogicalMultiCamera = true,
            syncType = 0,
            physicalLenses = listOf(wideLens, uwLens),
            hardwareType = MultiCameraHardwareType.NATIVE_LOGICAL
        )
        val manager = MultiCameraCaptureManager(context, testScope)
        arbiter.multiCameraManager = manager
        arbiter.isMultiCameraMode = true

        // NATIVE_LOGICAL: switching primary lens switches activePreviewPhysicalCharacteristics
        manager.configurePreviewStateForTesting(
            logicalInfo = logicalInfo,
            lenses = listOf(wideLens, uwLens),
            primaryLens = wideLens,
            standalonePrimaryId = null
        )
        assertSame(wideChars, arbiter.activePreviewPhysicalCharacteristics)

        manager.setPrimaryPreviewLens(uwLens)
        assertSame(uwChars, arbiter.activePreviewPhysicalCharacteristics)

        // CONCURRENT_STANDALONE: stays pinned to standalonePrimaryId ("0" -> wideChars)
        val standaloneInfo = logicalInfo.copy(
            isLogicalMultiCamera = false,
            hardwareType = MultiCameraHardwareType.CONCURRENT_STANDALONE
        )
        manager.configurePreviewStateForTesting(
            logicalInfo = standaloneInfo,
            lenses = listOf(wideLens, uwLens),
            primaryLens = wideLens,
            standalonePrimaryId = "0"
        )
        manager.setPrimaryPreviewLens(uwLens)
        assertSame(wideChars, arbiter.activePreviewPhysicalCharacteristics)

        // Switching back to Single-Cam ignores multiCameraManager routing
        arbiter.isMultiCameraMode = false
        assertNull(arbiter.activePreviewPhysicalCharacteristics)
    }

    @Test
    fun testComputeCropRegion_handlesPresetAndNativeZoom() {
        val activeArray = Rect(0, 0, 4000, 3000)
        val nativeLens = LensInfo(
            id = "0",
            physicalId = null,
            sensorId = "0",
            name = "1x",
            focalLength = 6.9f,
            equivalentFocalLength = 24f,
            multiplier = 1.0f,
            type = LensType.WIDE,
            facing = CameraCharacteristics.LENS_FACING_BACK,
            isZoomPreset = false
        )
        val crop2xLens = nativeLens.copy(
            sensorId = "0_2x",
            name = "2x",
            multiplier = 2.0f,
            isZoomPreset = true,
            targetZoomRatio = 2.0f
        )

        assertEquals(activeArray, arbiter.computeCropRegion(activeArray, nativeLens))
        assertEquals(Rect(1000, 750, 3000, 2250), arbiter.computeCropRegion(activeArray, crop2xLens))
    }

    @Test
    fun testGetCombinedOrientation_computesBackAndFrontCorrectly() {
        val backChars = ShadowCameraCharacteristics.newCameraCharacteristics()
        Shadows.shadowOf(backChars).set(CameraCharacteristics.SENSOR_ORIENTATION, 90)
        val frontChars = ShadowCameraCharacteristics.newCameraCharacteristics()
        Shadows.shadowOf(frontChars).set(CameraCharacteristics.SENSOR_ORIENTATION, 270)

        val shadowCm = Shadows.shadowOf(cameraManager)
        shadowCm.addCamera("0", backChars)
        shadowCm.addCamera("1", frontChars)

        state.lensFacing = CameraCharacteristics.LENS_FACING_BACK
        state.deviceOrientationDegrees = 90
        assertEquals(180, arbiter.getCombinedOrientation(90))

        state.lensFacing = CameraCharacteristics.LENS_FACING_FRONT
        assertEquals(180, arbiter.getCombinedOrientation(90))
        assertEquals(0, arbiter.getCombinedOrientation(270))
    }

    @Test
    fun testCloseAllSessions_releasesReadersAndSurface() = runBlocking {
        val surface = Surface(SurfaceTexture(0))
        arbiter.camera2PreviewSurface = surface
        arbiter.rawImageReader = ImageReader.newInstance(640, 480, ImageFormat.RAW_SENSOR, 2)
        arbiter.analysisImageReader = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 2)
        arbiter.sessionController.startBackgroundThread()

        arbiter.closeAllSessions(releaseSessionController = true)

        assertNull(arbiter.camera2PreviewSurface)
        assertNull(arbiter.rawImageReader)
        assertNull(arbiter.analysisImageReader)
        assertNull(arbiter.camera2Handler)
        assertTrue(state.captureResults.isEmpty())
        surface.release()
    }

    @Test
    fun testModeRoutingHelpers_dispatchSingleVsMultiCamera() = runBlocking {
        arbiter.isMultiCameraMode = false
        var singleRepeatingCalled = false
        arbiter.updateActivePreviewRepeatingRequest { singleRepeatingCalled = true }
        assertTrue(singleRepeatingCalled)
        assertTrue(arbiter.requiresSessionRebind)

        var fullApplied = false
        var focusOnlyApplied = false
        arbiter.applyManualSettingsForActiveSession(
            applySingleCameraFull = { fullApplied = true },
            applyFocusAndExposureOnly = { focusOnlyApplied = true }
        )
        assertTrue(fullApplied)

        arbiter.isMultiCameraMode = true
        fullApplied = false
        focusOnlyApplied = false
        arbiter.applyManualSettingsForActiveSession(
            applySingleCameraFull = { fullApplied = true },
            applyFocusAndExposureOnly = { focusOnlyApplied = true }
        )
        assertTrue(focusOnlyApplied)
        assertTrue(arbiter.requiresSessionRebind)
    }
}
