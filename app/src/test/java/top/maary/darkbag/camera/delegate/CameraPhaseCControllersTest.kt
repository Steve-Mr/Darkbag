package top.maary.darkbag.camera.delegate

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Bitmap
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.media.ImageReader
import android.net.Uri
import android.os.Handler
import android.util.Range
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.Surface
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.fragment.app.FragmentActivity
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.sync.Semaphore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.maary.darkbag.R
import top.maary.darkbag.camera.ConcurrentFrontCameraManager
import top.maary.darkbag.camera.MultiCameraCaptureManager
import top.maary.darkbag.camera.dispatch.CameraCapturePipelineDispatcher
import top.maary.darkbag.camera.dispatch.CameraCapturePipelineHost
import top.maary.darkbag.camera.dispatch.CameraMultiCaptureDispatcher
import top.maary.darkbag.camera.dispatch.CameraMultiCaptureHost
import top.maary.darkbag.camera.session.Camera2SessionController
import top.maary.darkbag.camera.session.CameraHardwareStreamController
import top.maary.darkbag.camera.session.CameraHardwareStreamHost
import top.maary.darkbag.camera.video.CameraVideoController
import top.maary.darkbag.camera.video.CameraVideoHost
import top.maary.darkbag.databinding.CameraUiContainerBinding
import top.maary.darkbag.databinding.FragmentCameraBinding
import top.maary.darkbag.fragments.CameraFragment
import top.maary.darkbag.fragments.SettingsFragment
import top.maary.darkbag.models.StandardTimingTracker
import top.maary.darkbag.modes.CaptureMode
import top.maary.darkbag.modes.CaptureModeCoordinator
import top.maary.darkbag.processor.HdrPlusStreamingBurst
import top.maary.darkbag.processor.LutSurfaceProcessor
import top.maary.darkbag.rawvideo.RawVideoSessionManager
import top.maary.darkbag.repository.ImageRepository
import top.maary.darkbag.utils.CameraRepository
import top.maary.darkbag.utils.ExposureUtils
import top.maary.darkbag.utils.HalfFrameSessionStore
import top.maary.darkbag.utils.LensInfo
import top.maary.darkbag.utils.LocationHelper
import top.maary.darkbag.utils.LutManager
import top.maary.darkbag.viewmodel.CameraUiTier
import top.maary.darkbag.viewmodel.CameraViewModel

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CameraPhaseCControllersTest {

    private lateinit var themedContext: Context
    private lateinit var prefs: SharedPreferences
    private lateinit var fragmentBinding: FragmentCameraBinding
    private lateinit var uiBinding: CameraUiContainerBinding
    private lateinit var viewModel: CameraViewModel
    private val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        themedContext = ContextThemeWrapper(app, R.style.AppTheme)
        prefs = themedContext.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
        fragmentBinding = FragmentCameraBinding.inflate(LayoutInflater.from(themedContext))
        val root = fragmentBinding.root as ConstraintLayout
        uiBinding = CameraUiContainerBinding.inflate(LayoutInflater.from(themedContext), root)
        viewModel = CameraViewModel()
    }

    // -------------------------------------------------------------------------
    // CameraHalfFrameStateController tests
    // -------------------------------------------------------------------------

    @Test
    fun testHalfFrameStateController_readWriteAndReset() {
        val host = FakeHalfFrameHost()
        val controller = CameraHalfFrameStateController(host)

        // Initially step 0
        controller.readState(requireFileForStep1 = true)
        assertEquals(0, controller.step)
        assertNull(controller.tempPath)

        // Write step 1 with active timestamp
        val now = System.currentTimeMillis()
        controller.writeStep(1, captureTimeMillis = now, digitalGain = 1.75f, flareType = 2)
        assertEquals(1, controller.step)
        val stored = HalfFrameSessionStore(themedContext).readSession(strict = false)
        assertEquals(1, stored.step)
        assertEquals(now, stored.captureTimeMillis)
        assertEquals(1.75f, stored.digitalGain, 0.001f)
        assertEquals(2, stored.flareType)

        // Strict read when temp file doesn't exist resets step back to 0
        controller.readState(requireFileForStep1 = true)
        assertEquals(0, controller.step)

        // Non-strict write followed by explicit reset clears session
        controller.writeStep(1, captureTimeMillis = now, digitalGain = 2.0f, flareType = 1)
        assertEquals(1, controller.step)
        controller.writeStep(0)
        assertEquals(0, controller.step)
        assertNull(controller.tempPath)
        assertEquals(0, HalfFrameSessionStore(themedContext).readSession(strict = false).step)
    }

    @Test
    fun testHalfFrameStateController_digitalGainAndStepCommit() {
        val host = FakeHalfFrameHost().apply {
            isHdrPlusEnabledFlag = false
            isoRangeVal = Range(50, 3200)
            exposureTimeRangeVal = Range(1000L, 1_000_000_000L)
        }
        val controller = CameraHalfFrameStateController(host)
        val gain = controller.getDigitalGainAndUpdateStep(
            timestamp = 5000L,
            isFrame1Trigger = true,
            shutterClick = 777777L
        )
        assertEquals(1.0f, gain, 0.001f)
        assertEquals(1, controller.step)
        val session = HalfFrameSessionStore(themedContext).readSession(strict = false)
        assertEquals(777777L, session.captureTimeMillis)
    }

    // -------------------------------------------------------------------------
    // CameraHdrPlusController tests
    // -------------------------------------------------------------------------

    @Test
    fun testHdrPlusController_restoreAndToggle() {
        prefs.edit()
            .putBoolean(SettingsFragment.KEY_FLASH_MODE, true)
            .putBoolean(CameraFragment.KEY_HDR_PLUS_ENABLED, false)
            .apply()

        val megaHost = FakeMegaHost()
        val lutController = CameraLutController(megaHost)
        val lensController = CameraLensController(megaHost)
        val controller = CameraHdrPlusController(megaHost, lutController, lensController)

        controller.restorePersistedState()
        assertTrue(megaHost.isFlashEnabled)
        assertFalse(viewModel.uiState.value.isHdrPlusEnabled)

        controller.bindHdrPlusControls()
        uiBinding.hdrPlusSwitch?.visibility = View.VISIBLE
        controller.updateHdrPlusUi()
        assertFalse(uiBinding.hdrPlusSwitch!!.isChecked)

        // Clicking hdrPlusPill toggles hdrPlusSwitch when visible
        uiBinding.hdrPlusPill?.performClick()
        assertTrue(uiBinding.hdrPlusSwitch!!.isChecked)
        assertTrue(viewModel.uiState.value.isHdrPlusEnabled)
        assertTrue(prefs.getBoolean(CameraFragment.KEY_HDR_PLUS_ENABLED, false))

        // Enabling HDR+ clears flash
        assertFalse(megaHost.isFlashEnabled)
        assertTrue(megaHost.applyCameraControlsCalled)
    }

    @Test
    fun testHdrPlusController_flashButtonBranchesByHdrState() {
        val megaHost = FakeMegaHost()
        val lutController = CameraLutController(megaHost)
        val lensController = CameraLensController(megaHost)
        val controller = CameraHdrPlusController(megaHost, lutController, lensController)

        // When HDR+ is disabled, clicking flashButton toggles flash mode
        viewModel.setHdrPlusEnabled(false)
        megaHost.isFlashEnabled = false
        controller.bindFlashButton()
        uiBinding.flashButton?.performClick()
        assertTrue(megaHost.isFlashEnabled)
        assertTrue(prefs.getBoolean(SettingsFragment.KEY_FLASH_MODE, false))

        // When HDR+ is enabled, clicking flashButton cycles underexposure mode instead
        viewModel.setHdrPlusEnabled(true)
        val beforeMode = prefs.getString(SettingsFragment.KEY_HDR_UNDEREXPOSURE_MODE, "Dynamic (Experimental)")
        uiBinding.flashButton?.performClick()
        val afterMode = prefs.getString(SettingsFragment.KEY_HDR_UNDEREXPOSURE_MODE, "Dynamic (Experimental)")
        assertTrue(beforeMode != afterMode)
    }

    // -------------------------------------------------------------------------
    // CameraMotionPhotoController tests
    // -------------------------------------------------------------------------

    @Test
    fun testMotionPhotoController_visibilityAndToggle() {
        val host = FakeMotionHost()
        val controller = CameraMotionPhotoController(host)

        // In POINT_AND_SHOOT tier, film row is hidden -> motionPhotoButton is GONE
        viewModel.setUiTier(CameraUiTier.POINT_AND_SHOOT)
        controller.updateButton()
        assertEquals(View.GONE, uiBinding.motionPhotoButton?.visibility)

        // In FILM tier with NORMAL mode, button is VISIBLE and toggles prefs
        viewModel.setUiTier(CameraUiTier.FILM)
        host.effectiveCaptureMode = CaptureMode.NORMAL
        controller.bindButton()
        assertEquals(View.VISIBLE, uiBinding.motionPhotoButton?.visibility)
        assertFalse(controller.isEnabled)

        uiBinding.motionPhotoButton?.performClick()
        assertTrue(controller.isEnabled)
        assertTrue(prefs.getBoolean(SettingsFragment.KEY_MOTION_PHOTO, false))
        assertTrue(viewModel.uiState.value.isMotionPhotoEnabled)

        // Switching to HALF_FRAME_SBS hides button because half-frame doesn't support motion photo
        host.effectiveCaptureMode = CaptureMode.HALF_FRAME_SBS
        controller.updateButton()
        assertEquals(View.GONE, uiBinding.motionPhotoButton?.visibility)
    }

    // -------------------------------------------------------------------------
    // CameraThumbnailController tests
    // -------------------------------------------------------------------------

    @Test
    fun testThumbnailController_visibilityAndState() {
        val activity = org.robolectric.Robolectric.buildActivity(FragmentActivity::class.java)
            .create().start().resume().visible().get()
        activity.setTheme(R.style.AppTheme)
        activity.setContentView(fragmentBinding.root)
        ShadowLooperHelper.runMainLooperIdle()

        val host = FakeThumbnailHost()
        val controller = CameraThumbnailController(host)

        controller.setGalleryThumbnail(null)
        ShadowLooperHelper.runMainLooperIdle()
        assertEquals(View.GONE, uiBinding.photoViewButton?.visibility)

        val testUri = Uri.parse("content://media/external/images/media/42")
        controller.setGalleryThumbnail(testUri.toString())
        ShadowLooperHelper.runMainLooperIdle()
        assertEquals(testUri, controller.currentThumbnailUri)
        assertEquals(View.VISIBLE, uiBinding.photoViewButton?.visibility)

        // When processing, button becomes INVISIBLE instead of VISIBLE/GONE
        host.isProcessing = true
        controller.setGalleryThumbnail(null)
        ShadowLooperHelper.runMainLooperIdle()
        assertEquals(View.INVISIBLE, uiBinding.photoViewButton?.visibility)
    }

    // -------------------------------------------------------------------------
    // CameraShutterController tests
    // -------------------------------------------------------------------------

    @Test
    fun testShutterController_clickLongPressAndReset() {
        val megaHost = FakeMegaHost()
        val overlayController = CameraOverlayController(FakeOverlayHost())
        val gestureDelegate = CameraGestureDelegate(themedContext, FakeGestureHost())
        val focusController = CameraFocusMeteringController(megaHost, gestureDelegate)
        val videoController = CameraVideoController(megaHost)
        val controller = CameraShutterController(megaHost, overlayController, focusController, videoController)

        val stubCoordinator = StubCoordinator(CaptureMode.NORMAL)
        megaHost.activeCoordinator = stubCoordinator
        controller.bindShutter()

        // Normal click dispatches to coordinator.onShutterTriggered
        uiBinding.cameraCaptureButton?.performClick()
        assertEquals(1, stubCoordinator.shutterTriggerCount)

        // Long press consumed by coordinator does not escalate to video recording
        stubCoordinator.consumeLongPress = true
        uiBinding.cameraCaptureButton?.onLongPressHoldStarted?.invoke()
        assertEquals(1, stubCoordinator.longPressCount)

        // Long press not consumed by coordinator does NOT start recording when supportsLongPressVideo is false
        stubCoordinator.consumeLongPress = false
        stubCoordinator.allowLongPressVideo = false
        uiBinding.cameraCaptureButton?.onLongPressHoldStarted?.invoke()
        assertEquals(2, stubCoordinator.longPressCount)
        assertFalse(megaHost.audioRationaleChecked)

        // When supportsLongPressVideo is true and coordinator does not consume, long press proceeds to video recording path
        stubCoordinator.allowLongPressVideo = true
        uiBinding.cameraCaptureButton?.onLongPressHoldStarted?.invoke()
        assertTrue(megaHost.audioRationaleChecked)

        // Dot rotation delegates to coordinator
        megaHost.deviceOrientationDegrees = 90
        stubCoordinator.dotRotation = -90f
        assertEquals(-90f, controller.getDotTargetRotation(), 0.001f)

        // resetBurstUi enables button when semaphore permits > 0, disables when 0
        megaHost.isBurstActive = true
        controller.resetBurstUi()
        assertFalse(megaHost.isBurstActive)
        assertTrue(uiBinding.cameraCaptureButton!!.isEnabled)

        megaHost.simulatedPermits = 0
        controller.resetBurstUi()
        assertFalse(uiBinding.cameraCaptureButton!!.isEnabled)

        // onDestroy cleanly releases orientation listener & animators
        controller.onDestroy()
        focusController.onDestroy()
        CameraLutController(megaHost).onDestroy()
        CameraLensController(megaHost).onDestroy()
    }

    // -------------------------------------------------------------------------
    // CameraModeController tests
    // -------------------------------------------------------------------------

    @Test
    fun testModeController_resolveAndCycleModes() {
        val megaHost = FakeMegaHost()
        val overlayController = CameraOverlayController(FakeOverlayHost())
        val gestureDelegate = CameraGestureDelegate(themedContext, FakeGestureHost())
        val focusController = CameraFocusMeteringController(megaHost, gestureDelegate)
        val videoController = CameraVideoController(megaHost)
        val shutterController = CameraShutterController(megaHost, overlayController, focusController, videoController)
        val thumbnailController = CameraThumbnailController(megaHost)
        val motionController = CameraMotionPhotoController(megaHost)
        val pipelineDispatcher = CameraCapturePipelineDispatcher(megaHost)
        val multiCaptureDispatcher = CameraMultiCaptureDispatcher(megaHost)
        val streamController = CameraHardwareStreamController(megaHost)
        val lensController = CameraLensController(megaHost)

        val modeController = CameraModeController(
            megaHost,
            CameraModeCollaborators(
                overlay = overlayController,
                shutter = shutterController,
                thumbnail = thumbnailController,
                motionPhoto = motionController,
                pipeline = pipelineDispatcher,
                multiCapture = multiCaptureDispatcher,
                stream = streamController,
                lens = lensController
            )
        )

        megaHost.modeControllerRef = modeController

        // Default resolves to NORMAL
        assertEquals(CaptureMode.NORMAL, modeController.resolveActiveCaptureMode())
        assertFalse(modeController.isHalfFrameActive)

        // Stale HALF_FRAME_SBS preference without KEY_HALF_FRAME_MODE falls back to NORMAL
        prefs.edit().putString(SettingsFragment.KEY_ACTIVE_CAPTURE_MODE, CaptureMode.HALF_FRAME_SBS.key).apply()
        assertEquals(CaptureMode.NORMAL, modeController.resolveActiveCaptureMode())

        // Enabling half-frame with cleared saved mode resolves HALF_FRAME_SBS and cycles SBS -> TB -> NORMAL
        prefs.edit()
            .remove(SettingsFragment.KEY_ACTIVE_CAPTURE_MODE)
            .putBoolean(SettingsFragment.KEY_HALF_FRAME_MODE, true)
            .apply()
        modeController.resolveActiveCaptureMode()
        assertEquals(CaptureMode.HALF_FRAME_SBS, modeController.effectiveMode)

        modeController.cycleCaptureMode()
        assertEquals(CaptureMode.HALF_FRAME_TB, modeController.effectiveMode)
        assertTrue(megaHost.modeCycledFlag == true)

        modeController.cycleCaptureMode()
        assertEquals(CaptureMode.NORMAL, modeController.effectiveMode)

        // Burst active blocks mode cycling
        megaHost.isBurstActive = true
        modeController.cycleCaptureMode()
        assertEquals(CaptureMode.NORMAL, modeController.effectiveMode)
        ShadowLooperHelper.runMainLooperIdle()
    }

    // -------------------------------------------------------------------------
    // CameraOverlayController ViewFinder constraint & aspect ratio tests
    // -------------------------------------------------------------------------

    @Test
    fun testOverlayController_updateViewFinderConstraintsAndAspectRatio() {
        val overlayController = CameraOverlayController(FakeOverlayHost())

        overlayController.syncViewFinderAspectRatio(isPortraitBounds = true)
        val lpPortrait = fragmentBinding.viewFinderContainer.layoutParams as ConstraintLayout.LayoutParams
        assertEquals("3:4", lpPortrait.dimensionRatio)

        overlayController.syncViewFinderAspectRatio(isPortraitBounds = false)
        val lpLand = fragmentBinding.viewFinderContainer.layoutParams as ConstraintLayout.LayoutParams
        assertEquals("4:3", lpLand.dimensionRatio)

        themedContext.resources.configuration.orientation = Configuration.ORIENTATION_PORTRAIT
        overlayController.updateViewFinderConstraints()
        val updatedLp = fragmentBinding.viewFinderContainer.layoutParams as ConstraintLayout.LayoutParams
        assertEquals("3:4", updatedLp.dimensionRatio)
        assertEquals(uiBinding.bottomIslandCard!!.id, updatedLp.bottomToTop)
        assertTrue(fragmentBinding.viewFinderContainer.clipToOutline)
    }

    // =========================================================================
    // Test Doubles
    // =========================================================================

    private object ShadowLooperHelper {
        fun runMainLooperIdle() {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        }
    }

    private inner class FakeHalfFrameHost : CameraHalfFrameStateHost {
        override val hostContext: Context get() = themedContext
        override val isHdrPlusEnabled: Boolean get() = isHdrPlusEnabledFlag
        var isHdrPlusEnabledFlag = false
        override val isoRange: Range<Int>? get() = isoRangeVal
        var isoRangeVal: Range<Int>? = Range(50, 3200)
        override val exposureTimeRange: Range<Long>? get() = exposureTimeRangeVal
        var exposureTimeRangeVal: Range<Long>? = Range(1000L, 1_000_000_000L)
        override val lastClippingRatio: Double = 0.0
        override val captureResults: MutableMap<Long, TotalCaptureResult> = mutableMapOf()
        override val captureResultFlow = MutableSharedFlow<TotalCaptureResult>(replay = 1)
    }

    private inner class FakeMotionHost : CameraMotionPhotoHost {
        override val hostContext: Context get() = themedContext
        override val isHostAdded: Boolean = true
        override val cameraViewModel: CameraViewModel get() = viewModel
        override val cameraUiContainerBinding: CameraUiContainerBinding get() = uiBinding
        override val lutProcessor: LutSurfaceProcessor? = null
        override var effectiveCaptureMode: CaptureMode = CaptureMode.NORMAL
        override var pendingMotionPhotoTask: CompletableDeferred<Pair<String?, Long>>? = null
    }

    private inner class FakeThumbnailHost : CameraThumbnailHost {
        override val hostContext: Context get() = themedContext
        override val hostActivity: FragmentActivity? = null
        override val lifecycleScope: CoroutineScope get() = testScope
        override val cameraUiContainerBinding: CameraUiContainerBinding get() = uiBinding
        override val activeCoordinator: CaptureModeCoordinator? = null
        override val imageRepository: ImageRepository = ImageRepository(themedContext)
        override var isProcessing: Boolean = false
    }

    private inner class FakeOverlayHost : CameraOverlayHost {
        override val context: Context get() = themedContext
        override val fragmentCameraBinding: FragmentCameraBinding get() = fragmentBinding
        override val cameraUiContainerBinding: CameraUiContainerBinding get() = uiBinding
        override val activeCoordinator: CaptureModeCoordinator? = null
        override val cameraViewModel: CameraViewModel get() = viewModel
        override val isProcessing: Boolean = false
        override val halfFrameStep: Int = 0
        override var isHalfFrameUiAnimating: Boolean = false
        override var pendingVfSnapshot: Bitmap? = null
        override fun readScopedHalfFrameState(prefs: SharedPreferences, requireFileForStep1: Boolean) {}
    }

    private class FakeGestureHost : CameraGestureHost {
        override var isFloatingOverlayOpen: Boolean = false
        override fun dismissFloatingOverlays() {}
        override fun triggerTapToFocus(x: Float, y: Float) {}
        override fun unlockFocusAndMetering() {}
    }

    private class StubCoordinator(override val mode: CaptureMode) : CaptureModeCoordinator {
        var shutterTriggerCount = 0
        var longPressCount = 0
        var consumeLongPress = false
        var allowLongPressVideo = false
        var dotRotation = 0f

        override fun onActivated() {}
        override fun onDeactivated() {}
        override fun onShutterTriggered(timing: StandardTimingTracker) {
            shutterTriggerCount++
        }
        override fun onShutterLongPressed(): Boolean {
            longPressCount++
            return consumeLongPress
        }
        override fun supportsLongPressVideo(): Boolean = allowLongPressVideo
        override fun getShutterDotRotation(deviceOrientationDegrees: Int): Float = dotRotation
    }

    private inner class FakeMegaHost :
        CameraHdrPlusHost,
        CameraLutHost,
        CameraLensHost,
        CameraFocusMeteringHost,
        CameraVideoHost,
        CameraShutterHost,
        CameraModeHost,
        CameraThumbnailHost,
        CameraMotionPhotoHost,
        CameraCapturePipelineHost,
        CameraMultiCaptureHost,
        CameraHardwareStreamHost {

        var applyCameraControlsCalled = false
        var modeCycledFlag: Boolean? = null
        var modeControllerRef: CameraModeController? = null
        var audioRationaleChecked = false
        var simulatedPermits = 3

        override val hostContext: Context get() = themedContext
        override val hostActivity: FragmentActivity? = null
        override val isHostAdded: Boolean = true
        override val lifecycleScope: CoroutineScope get() = testScope
        override val cameraViewModel: CameraViewModel get() = viewModel
        override val camera2Manager: CameraManager = themedContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        override val sessionController = Camera2SessionController()
        override val camera2Device: CameraDevice? = null
        override val camera2Session: CameraCaptureSession? = null
        override var camera2PreviewSurface: Surface? = null
        override val camera2Handler: Handler? = null
        override var rawImageReader: ImageReader? = null
        override var analysisImageReader: ImageReader? = null
        override val fragmentCameraBinding: FragmentCameraBinding get() = fragmentBinding
        override val cameraUiContainerBinding: CameraUiContainerBinding get() = uiBinding
        override var activeCoordinator: CaptureModeCoordinator? = null
            get() = modeControllerRef?.activeCoordinator ?: field
        override val effectiveCaptureMode: CaptureMode
            get() = modeControllerRef?.effectiveMode ?: activeCoordinator?.mode ?: CaptureMode.NORMAL
        override val lutManager: LutManager = LutManager(themedContext)
        override val lutProcessor: LutSurfaceProcessor? = null
        override val cameraRepository: CameraRepository = CameraRepository(themedContext)
        override val imageRepository: ImageRepository = ImageRepository(themedContext)
        override val locationHelper: LocationHelper = LocationHelper(themedContext)
        override val rawVideoSessionManager = RawVideoSessionManager()
        override var multiCameraManager: MultiCameraCaptureManager? = null
        override var concurrentFrontCameraManager: ConcurrentFrontCameraManager? = null
        override var availableLenses: List<LensInfo> = emptyList()
        override var currentLens: LensInfo? = null
        override var lensFacing: Int = CameraCharacteristics.LENS_FACING_BACK
        override val displayRotation: Int = Surface.ROTATION_0
        override var isFlashEnabled: Boolean = false
        override val isHdrPlusEnabled: Boolean get() = viewModel.uiState.value.isHdrPlusEnabled
        override var lastHdrPlusConfig: ExposureUtils.ExposureConfig? = null
        override val isOisSupported: Boolean = false
        override val isHdrOisEnabledPref: Boolean = true
        override val isRawSupported: Boolean = true
        override val isManualFocus: Boolean = false
        override val isManualIso: Boolean = false
        override val isManualShutter: Boolean = false
        override val isManualExposure: Boolean = false
        override var currentFocusDistance: Float = 0f
        override var liveFocusDistance: Float = 0f
        override val currentIso: Int = 100
        override var liveIso: Int = 100
        override val currentExposureTime: Long = 10_000_000L
        override var liveExposureTime: Long = 10_000_000L
        override val currentEvIndex: Int = 0
        override val isoRange: Range<Int>? = Range(50, 3200)
        override val exposureTimeRange: Range<Long>? = Range(1000L, 1_000_000_000L)
        override var lastClippingRatio: Double = 0.0
        override val shouldMirror: Boolean = false
        override var focusMeteringRegion: MeteringRectangle? = null
        override var exposureMeteringRegion: MeteringRectangle? = null
        override val captureResults: MutableMap<Long, TotalCaptureResult> = mutableMapOf()
        override val captureResultFlow = MutableSharedFlow<TotalCaptureResult>(replay = 1)
        override val activeManualTab: String? = null
        override val processingSemaphore: Semaphore = object : Semaphore {
            override val availablePermits: Int get() = simulatedPermits
            override suspend fun acquire() {}
            override fun release() {}
            override fun tryAcquire(): Boolean = simulatedPermits > 0
        }
        override var isBurstActive: Boolean = false
        override var burstStartTime: Long = 0L
        override var isSwitchingLens: Boolean = false
        override var camera2RetryCount: Int = 0
        override var lastTelemetryUpdateMs: Long = 0L
        override var hdrPlusStreamingBurstHelper: HdrPlusStreamingBurst? = null
        override var pendingMotionPhotoTask: CompletableDeferred<Pair<String?, Long>>? = null
        override var pendingVfSnapshot: Bitmap? = null
        override var isFrontPipActive: Boolean = false
        override val isMultiCameraManualLinked: Boolean = true
        override val isProcessing: Boolean = false
        override var isVideoSaving: Boolean = false
        override var transientLensLabel: String? = null
        override var deviceOrientationDegrees: Int = 0

        override fun applyCameraControls() { applyCameraControlsCalled = true }
        override fun dismissFloatingOverlays() {}
        override fun bindCameraUseCases() {}
        override fun applyManualSettingsToRequest(request: CaptureRequest.Builder) {}
        override fun applyManualSettingsToRequest(request: CaptureRequest.Builder, isHdrBurst: Boolean) {}
        override fun handleCaptureResult(result: TotalCaptureResult) {}
        override fun applyUIVisibility() {}
        override fun updateProInfoBar() {}
        override fun updateDialPanel() {}
        override fun ensureActiveCoordinator(): CaptureModeCoordinator? = activeCoordinator
        override fun showShutterBlackout() {}
        override fun updateCurrentThumbnail(uri: Uri?) {}
        override fun showProcessingAnimation() {}
        override fun hideProcessingAnimation() {}
        override fun updateMotionPhotoEncoder() {}
        override fun getCombinedOrientation(): Int = 0
        override fun writeScopedHalfFrameStep(prefs: SharedPreferences, step: Int, captureTimeMillis: Long?, digitalGain: Float, flareType: Int) {}
        override fun getDigitalGainAndUpdateStep(timestamp: Long, isFrame1Trigger: Boolean, shutterClick: Long?): Float = 1.0f
        override fun resetBurstUi() {}
        override fun setGalleryThumbnail(filename: String?) {}
        override fun triggerAutoBurst(prefs: SharedPreferences) {}
        override fun shouldShowAudioPermissionRationale(): Boolean {
            audioRationaleChecked = true
            return false
        }
        override fun requestAudioPermission() {}
        override fun onCaptureModeCycled(modeChanged: Boolean) { modeCycledFlag = modeChanged }
        override fun formatFocusDist(dist: Float): String = "∞"
        override fun formatShutterTime(nanos: Long): String = "1/100s"
        override fun onSessionConfigured() {}
    }
}
