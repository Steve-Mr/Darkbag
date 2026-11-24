package top.maary.darkbag.camera.delegate

import android.content.Context
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.net.Uri
import android.util.Range
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.FragmentActivity
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.maary.darkbag.camera.session.CameraSessionArbiter
import top.maary.darkbag.camera.state.CameraRuntimeStateHolder
import top.maary.darkbag.databinding.CameraUiContainerBinding
import top.maary.darkbag.databinding.FragmentCameraBinding
import top.maary.darkbag.modes.CaptureMode
import top.maary.darkbag.modes.CaptureModeCoordinator
import top.maary.darkbag.repository.ImageRepository
import top.maary.darkbag.repository.SettingsRepository
import top.maary.darkbag.ui.ProDialWheelView
import top.maary.darkbag.utils.CameraRepository
import top.maary.darkbag.utils.LocationHelper
import top.maary.darkbag.utils.LutManager
import top.maary.darkbag.viewmodel.CameraViewModel

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class CameraDelegatesTest {

    private lateinit var context: Context

    class FakeUiHost : CameraUiHost {
        override val fragmentCameraBinding: FragmentCameraBinding? = null
        override val cameraUiContainerBinding: CameraUiContainerBinding? = null
        override val rootViewGroup: ViewGroup? = null
        override var isFloatingOverlayOpen: Boolean = false
        var overlayDismissed = false
        var lastTapX: Float? = null
        var lastTapY: Float? = null
        var focusUnlocked = false

        override fun applyUIVisibility() {}
        override fun dismissFloatingOverlays() { overlayDismissed = true }
        override fun updateProInfoBar() {}
        override fun updateDialPanel() {}
        override fun formatFocusDist(dist: Float): String = "∞"
        override fun formatShutterTime(nanos: Long): String = "1/100s"
        override fun showShutterBlackout() {}
        override fun showProcessingAnimation() {}
        override fun hideProcessingAnimation() {}
        override fun resetBurstUi() {}
        override fun updateCurrentThumbnail(uri: Uri?) {}
        override fun setGalleryThumbnail(filename: String?) {}
        override fun onSessionConfigured() {}
        override fun triggerTapToFocus(x: Float, y: Float) {
            lastTapX = x
            lastTapY = y
        }
        override fun unlockFocusAndMetering() { focusUnlocked = true }
    }

    class FakeTierGateway(
        override val hostContext: Context
    ) : CameraEnvironmentHost, CameraCoordinatorHost {
        override val hostActivity: FragmentActivity? = null
        override val isHostAdded: Boolean = true
        override val lifecycleScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        override val cameraViewModel = CameraViewModel()
        override val runtimeState = CameraRuntimeStateHolder().apply {
            minFocusDistance = 10.0f
            isoRange = Range(50, 6400)
            exposureTimeRange = Range(125_000L, 30_000_000_000L)
            evRange = Range(-6, 6)
            liveFocusDistance = 0.5f
            liveIso = 200
            liveExposureTime = 20_000_000L
            availableLenses = emptyList()
        }
        override val camera2Manager: CameraManager =
            hostContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        override val sessionArbiter = CameraSessionArbiter(camera2Manager, runtimeState)
        override val cameraRepository = CameraRepository(hostContext)
        override val imageRepository = ImageRepository(hostContext)
        override val lutManager = LutManager(hostContext)
        override val locationHelper = LocationHelper(hostContext)
        override val displayRotation: Int = Surface.ROTATION_0
        override val shouldMirror: Boolean = false
        override fun shouldShowAudioPermissionRationale(): Boolean = false
        override fun requestAudioPermission() {}

        override val activeCoordinator: CaptureModeCoordinator? = null
        override val effectiveCaptureMode: CaptureMode = CaptureMode.NORMAL
        override val halfFrameStep: Int = 0
        var cameraControlsApplied = false

        override fun ensureActiveCoordinator(): CaptureModeCoordinator? = null
        override fun bindCameraUseCases() {}
        override fun applyCameraControls() { cameraControlsApplied = true }
        override fun applyManualSettingsToRequest(request: CaptureRequest.Builder, isHdrBurst: Boolean, isPreviewStream: Boolean) {}
        override fun applyPreviewFocusAndExposureSettings(builder: CaptureRequest.Builder) {}
        override fun updatePreviewToneMapPolicyForDevice(deviceId: String, chars: android.hardware.camera2.CameraCharacteristics) {}
        override fun handleCaptureResult(result: TotalCaptureResult) {}
        override fun getCombinedOrientation(): Int = 0
        override fun updateMotionPhotoEncoder() {}
        override fun triggerAutoBurst() {}
        override fun onCaptureModeCycled(modeChanged: Boolean) {}
        override fun readScopedHalfFrameState(requireFileForStep1: Boolean) {}
        override fun writeScopedHalfFrameStep(step: Int, captureTimeMillis: Long?, digitalGain: Float, flareType: Int) {}
        override fun getDigitalGainAndUpdateStep(timestamp: Long, isFrame1Trigger: Boolean, shutterClick: Long?): Float = 1.0f
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        SettingsRepository.resetForTest(context)
        SettingsRepository.getInstance(context).awaitHydratedBlocking()
    }

    @Test
    fun testCameraGestureDelegate_TapAndUnlockRouting() {
        val uiHost = FakeUiHost()
        val delegate = CameraGestureDelegate(context, uiHost)

        uiHost.isFloatingOverlayOpen = true
        val stageView = View(context)
        delegate.setupTapToFocus(stageView)

        assertFalse(uiHost.overlayDismissed)
        assertNull(uiHost.lastTapX)
    }

    @Test
    fun testCameraTierController_Formatters() {
        val gateway = FakeTierGateway(context)
        val uiHost = FakeUiHost()
        val controller = CameraTierController(gateway, uiHost, gateway)

        assertEquals("∞", controller.formatFocusDist(0f))
        assertEquals("Macro", controller.formatFocusDist(9.8f))
        assertEquals("50cm", controller.formatFocusDist(2.0f))
        assertEquals("2.0m", controller.formatFocusDist(0.5f))

        assertEquals("1/1000s", controller.formatShutterTime(1_000_000L))
        assertEquals("1/30s", controller.formatShutterTime(33_333_333L))
        assertEquals("1.0s", controller.formatShutterTime(1_000_000_000L))
        assertEquals("2.0s", controller.formatShutterTime(2_000_000_000L))

        assertEquals("EV 0.0", controller.formatEvVal(0))
        assertEquals("EV +2", controller.formatEvVal(2))
        assertEquals("EV -1", controller.formatEvVal(-1))
    }

    @Test
    fun testCameraTierController_DialItemBuilders() {
        val gateway = FakeTierGateway(context)
        val uiHost = FakeUiHost()
        val controller = CameraTierController(gateway, uiHost, gateway)

        val shutterItems = controller.buildShutterDialItems(Range(125_000L, 1_000_000_000L))
        assertTrue(shutterItems.isNotEmpty())
        assertTrue(shutterItems.any { it.label == "1/1000" })

        val isoItems = controller.buildIsoDialItems(Range(50, 3200))
        assertTrue(isoItems.isNotEmpty())
        assertTrue(isoItems.any { it.label == "100" })

        val focusItems = controller.buildFocusDialItems(10.0f)
        assertTrue(focusItems.isNotEmpty())
        assertTrue(focusItems.any { it.label == "∞" })
        assertTrue(focusItems.any { it.label == "Macro" })

        val evItems = controller.buildEvDialItems(Range(-3, 3))
        assertEquals(7, evItems.size)
        assertTrue(evItems.any { it.label == "0" })
    }

    @Test
    fun testCameraTierController_HandleDialSelection() {
        val gateway = FakeTierGateway(context)
        val uiHost = FakeUiHost()
        val controller = CameraTierController(gateway, uiHost, gateway)

        gateway.runtimeState.activeManualTab = "Focus"

        controller.handleDialSelection(ProDialWheelView.DialItem("1", "1.0m", 1.0, true))
        assertEquals(1.0f, gateway.runtimeState.currentFocusDistance, 0.01f)
        assertTrue(gateway.runtimeState.isManualFocus)
        assertTrue(gateway.cameraControlsApplied)

        controller.resetCurrentManualParameter()
        assertFalse(gateway.runtimeState.isManualFocus)
        assertNull(gateway.runtimeState.focusMeteringRegion)
    }

    @Test
    fun testCameraTierController_WbDialSelectionAndPresets() {
        val gateway = FakeTierGateway(context)
        val uiHost = FakeUiHost()
        val controller = CameraTierController(gateway, uiHost, gateway)

        gateway.runtimeState.activeManualTab = "WB"

        // 1. Dial selection of 5500K
        controller.handleDialSelection(ProDialWheelView.DialItem("5500K", "5500K", 5500.0, true))
        assertTrue(gateway.runtimeState.isManualWb)
        assertEquals(5500, gateway.runtimeState.manualWbTempK)

        // 2. Preset selection (Tungsten 3200K)
        controller.selectWbPreset(3200)
        assertTrue(gateway.runtimeState.isManualWb)
        assertEquals(3200, gateway.runtimeState.manualWbTempK)

        // 3. Preset selection (Cloudy 6500K)
        controller.selectWbPreset(6500)
        assertTrue(gateway.runtimeState.isManualWb)
        assertEquals(6500, gateway.runtimeState.manualWbTempK)

        // 4. Dial selection of AUTO
        controller.handleDialSelection(ProDialWheelView.DialItem("AUTO", "AUTO", 0.0, true))
        assertFalse(gateway.runtimeState.isManualWb)

        // 5. Reset
        controller.selectWbPreset(5500)
        assertTrue(gateway.runtimeState.isManualWb)
        controller.resetCurrentManualParameter()
        assertFalse(gateway.runtimeState.isManualWb)
    }
}
