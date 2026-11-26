package top.maary.darkbag.camera.delegate

import android.content.Context
import android.content.res.Configuration
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.net.Uri
import android.util.Range
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.fragment.app.FragmentActivity
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
import top.maary.darkbag.camera.MultiCameraCaptureManager
import top.maary.darkbag.camera.dispatch.CameraCapturePipelineDispatcher
import top.maary.darkbag.camera.dispatch.CameraMultiCaptureDispatcher
import top.maary.darkbag.camera.session.CameraHardwareStreamController
import top.maary.darkbag.camera.session.CameraSessionArbiter
import top.maary.darkbag.camera.state.CameraRuntimeStateHolder
import top.maary.darkbag.camera.video.CameraVideoController
import top.maary.darkbag.databinding.CameraUiContainerBinding
import top.maary.darkbag.databinding.FragmentCameraBinding
import top.maary.darkbag.models.StandardTimingTracker
import top.maary.darkbag.modes.CaptureMode
import top.maary.darkbag.modes.CaptureModeCoordinator
import top.maary.darkbag.repository.ImageRepository
import top.maary.darkbag.repository.PreferenceKeys
import top.maary.darkbag.repository.SettingsRepository
import top.maary.darkbag.utils.CameraRepository
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
    private lateinit var fragmentBinding: FragmentCameraBinding
    private lateinit var uiBinding: CameraUiContainerBinding
    private lateinit var viewModel: CameraViewModel
    private val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        themedContext = ContextThemeWrapper(app, R.style.AppTheme)
        themedContext.getSharedPreferences(PreferenceKeys.LEGACY_PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        SettingsRepository.resetForTest(themedContext)
        SettingsRepository.getInstance(themedContext).awaitHydratedBlocking()
        fragmentBinding = FragmentCameraBinding.inflate(LayoutInflater.from(themedContext))
        val root = fragmentBinding.root
        uiBinding = CameraUiContainerBinding.inflate(LayoutInflater.from(themedContext), root)
        viewModel = CameraViewModel()
    }

    // -------------------------------------------------------------------------
    // CameraHalfFrameStateController tests
    // -------------------------------------------------------------------------

    @Test
    fun testHalfFrameStateController_readWriteAndReset() {
        val gateway = FakeCameraGateway()
        val controller = CameraHalfFrameStateController(gateway)

        controller.readState(requireFileForStep1 = true)
        assertEquals(0, controller.step)
        assertNull(controller.tempPath)

        val now = System.currentTimeMillis()
        controller.writeStep(1, captureTimeMillis = now, digitalGain = 1.75f, flareType = 2)
        assertEquals(1, controller.step)
        val stored = HalfFrameSessionStore(themedContext).readSession(strict = false)
        assertEquals(1, stored.step)
        assertEquals(now, stored.captureTimeMillis)
        assertEquals(1.75f, stored.digitalGain, 0.001f)
        assertEquals(2, stored.flareType)

        controller.readState(requireFileForStep1 = true)
        assertEquals(0, controller.step)

        controller.writeStep(1, captureTimeMillis = now, digitalGain = 2.0f, flareType = 1)
        assertEquals(1, controller.step)
        controller.writeStep(0)
        assertEquals(0, controller.step)
        assertNull(controller.tempPath)
        assertEquals(0, HalfFrameSessionStore(themedContext).readSession(strict = false).step)
    }

    @Test
    fun testHalfFrameStateController_digitalGainAndStepCommit() {
        val gateway = FakeCameraGateway().apply {
            viewModel.setHdrPlusEnabled(false)
            runtimeState.isoRange = Range(50, 3200)
            runtimeState.exposureTimeRange = Range(1000L, 1_000_000_000L)
        }
        val controller = CameraHalfFrameStateController(gateway)
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
        SettingsRepository.getInstance(themedContext).edit {
            this[PreferenceKeys.FLASH_MODE] = true
            this[PreferenceKeys.HDR_PLUS_ENABLED] = false
        }

        val gateway = FakeCameraGateway()
        val lutController = CameraLutController(gateway, gateway, gateway)
        val lensController = CameraLensController(gateway, gateway, gateway)
        val controller = CameraHdrPlusController(gateway, gateway, gateway, lutController, lensController)

        controller.restorePersistedState()
        assertTrue(gateway.runtimeState.isFlashEnabled)
        assertFalse(viewModel.uiState.value.isHdrPlusEnabled)

        controller.bindHdrPlusControls()
        uiBinding.hdrPlusSwitch.visibility = View.VISIBLE
        controller.updateHdrPlusUi()
        assertFalse(uiBinding.hdrPlusSwitch.isChecked)

        uiBinding.hdrPlusPill.performClick()
        assertTrue(uiBinding.hdrPlusSwitch.isChecked)
        assertTrue(viewModel.uiState.value.isHdrPlusEnabled)
        assertTrue(SettingsRepository.getInstance(themedContext).current().hdrPlusEnabled)

        assertFalse(gateway.runtimeState.isFlashEnabled)
        assertTrue(gateway.applyCameraControlsCalled)
    }

    @Test
    fun testHdrPlusController_flashButtonBranchesByHdrState() {
        val gateway = FakeCameraGateway()
        val lutController = CameraLutController(gateway, gateway, gateway)
        val lensController = CameraLensController(gateway, gateway, gateway)
        val controller = CameraHdrPlusController(gateway, gateway, gateway, lutController, lensController)

        val repo = SettingsRepository.getInstance(themedContext)
        viewModel.setHdrPlusEnabled(false)
        gateway.runtimeState.isFlashEnabled = false
        controller.bindFlashButton()
        uiBinding.flashButton.performClick()
        assertTrue(gateway.runtimeState.isFlashEnabled)
        assertTrue(repo.current().flashEnabled)

        viewModel.setHdrPlusEnabled(true)
        val beforeMode = repo.current().hdrUnderexposureMode
        uiBinding.flashButton.performClick()
        val afterMode = repo.current().hdrUnderexposureMode
        assertTrue(beforeMode != afterMode)
    }

    @Test
    fun testHdrPlusController_restoreWithLegacyStringFlashMode() {
        SettingsRepository.resetForTest(themedContext)
        themedContext.getSharedPreferences(PreferenceKeys.LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PreferenceKeys.KEY_FLASH_MODE, "on")
            .commit()
        SettingsRepository.getInstance(themedContext).awaitHydratedBlocking()

        val gateway = FakeCameraGateway()
        val lutController = CameraLutController(gateway, gateway, gateway)
        val lensController = CameraLensController(gateway, gateway, gateway)
        val controller = CameraHdrPlusController(gateway, gateway, gateway, lutController, lensController)

        controller.restorePersistedState()
        assertTrue(gateway.runtimeState.isFlashEnabled)
    }

    @Test
    fun testLensController_withFloatDefaultFocal1xDoesNotCrash() {
        SettingsRepository.resetForTest(themedContext)
        themedContext.getSharedPreferences(PreferenceKeys.LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat(PreferenceKeys.KEY_DEFAULT_FOCAL_1X, 28f)
            .commit()
        SettingsRepository.getInstance(themedContext).awaitHydratedBlocking()

        val gateway = FakeCameraGateway()
        val lensController = CameraLensController(gateway, gateway, gateway)
        lensController.initLensControls()
        assertNotNull(lensController)
    }

    // -------------------------------------------------------------------------
    // CameraMotionPhotoController tests
    // -------------------------------------------------------------------------

    @Test
    fun testMotionPhotoController_visibilityAndToggle() {
        val gateway = FakeCameraGateway()
        val controller = CameraMotionPhotoController(gateway, gateway, gateway)

        viewModel.setUiTier(CameraUiTier.POINT_AND_SHOOT)
        controller.updateButton()
        assertEquals(View.GONE, uiBinding.motionPhotoButton?.visibility)

        viewModel.setUiTier(CameraUiTier.FILM)
        gateway.effectiveModeOverride = CaptureMode.NORMAL
        controller.bindButton()
        assertEquals(View.VISIBLE, uiBinding.motionPhotoButton?.visibility)
        assertFalse(controller.isEnabled)

        uiBinding.motionPhotoButton?.performClick()
        assertTrue(controller.isEnabled)
        assertTrue(SettingsRepository.getInstance(themedContext).current().motionPhotoEnabled)
        assertTrue(viewModel.uiState.value.isMotionPhotoEnabled)

        gateway.effectiveModeOverride = CaptureMode.HALF_FRAME_SBS
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

        val gateway = FakeCameraGateway()
        val controller = CameraThumbnailController(gateway, gateway, gateway)

        controller.setGalleryThumbnail(null)
        ShadowLooperHelper.runMainLooperIdle()
        assertEquals(View.GONE, uiBinding.photoViewButton.visibility)

        val testUri = Uri.parse("content://media/external/images/media/42")
        controller.setGalleryThumbnail(testUri.toString(), timestamp = 500L)
        ShadowLooperHelper.runMainLooperIdle()
        assertEquals(testUri, controller.currentThumbnailUri)
        assertEquals(View.VISIBLE, uiBinding.photoViewButton.visibility)

        gateway.runtimeState.isVideoSaving = true
        controller.setGalleryThumbnail(null)
        ShadowLooperHelper.runMainLooperIdle()
        assertEquals(View.INVISIBLE, uiBinding.photoViewButton.visibility)

        // Multi-shot: when a real URI arrives while isProcessing == true, it must remain VISIBLE
        val shot1Uri = Uri.parse("content://media/external/images/media/101")
        assertTrue(controller.setGalleryThumbnail(shot1Uri.toString(), timestamp = 1000L))
        ShadowLooperHelper.runMainLooperIdle()
        assertEquals(shot1Uri, controller.currentThumbnailUri)
        assertEquals(View.VISIBLE, uiBinding.photoViewButton.visibility)

        // Clearing placeholder must NOT bump timestamp forward to reject in-flight captures
        assertTrue(controller.setGalleryThumbnail(null))
        ShadowLooperHelper.runMainLooperIdle()

        // Shot 2 (newer timestamp) arrives, then late Shot 1 (older timestamp) arrives out-of-order
        val shot2Uri = Uri.parse("content://media/external/images/media/202")
        assertTrue(controller.setGalleryThumbnail(shot2Uri.toString(), timestamp = 2000L))
        assertFalse(controller.setGalleryThumbnail(shot1Uri.toString(), timestamp = 1000L))
        assertEquals(shot2Uri, controller.currentThumbnailUri)

        // Half-frame Step 1 (shouldHideGalleryThumbnail() == true) still hides the thumbnail button
        gateway.activeCoordinatorOverride = object : CaptureModeCoordinator by StubCoordinator(CaptureMode.HALF_FRAME_SBS) {
            override fun shouldHideGalleryThumbnail(): Boolean = true
        }
        val hfUri = Uri.parse("content://media/external/images/media/303")
        assertTrue(controller.setGalleryThumbnail(hfUri.toString(), timestamp = 3000L))
        ShadowLooperHelper.runMainLooperIdle()
        assertEquals(View.INVISIBLE, uiBinding.photoViewButton.visibility)
    }

    @Test
    fun testOverlayController_hideProcessingAnimation_respectsActiveProcessingState() {
        val gateway = FakeCameraGateway()
        val overlayController = CameraOverlayController(gateway, gateway, gateway)

        gateway.runtimeState.isVideoSaving = true
        overlayController.showProcessingAnimation()
        assertEquals(View.VISIBLE, uiBinding.processingProgress.visibility)

        // Still processing -> hideProcessingAnimation must keep spinner visible
        overlayController.hideProcessingAnimation()
        assertEquals(View.VISIBLE, uiBinding.processingProgress.visibility)

        // All processing done -> spinner hides
        gateway.runtimeState.isVideoSaving = false
        overlayController.hideProcessingAnimation()
        assertEquals(View.GONE, uiBinding.processingProgress.visibility)
    }

    // -------------------------------------------------------------------------
    // CameraShutterController tests
    // -------------------------------------------------------------------------

    @Test
    fun testShutterController_clickLongPressAndReset() {
        val gateway = FakeCameraGateway()
        val overlayController = CameraOverlayController(gateway, gateway, gateway)
        val gestureDelegate = CameraGestureDelegate(themedContext, gateway)
        val focusController = CameraFocusMeteringController(gateway, gateway, gateway, gestureDelegate)
        val videoController = CameraVideoController(gateway, gateway, gateway)
        val controller = CameraShutterController(gateway, gateway, gateway, overlayController, focusController, videoController)

        val stubCoordinator = StubCoordinator(CaptureMode.NORMAL)
        gateway.activeCoordinatorOverride = stubCoordinator
        controller.bindShutter()

        uiBinding.cameraCaptureButton.performClick()
        assertEquals(1, stubCoordinator.shutterTriggerCount)

        stubCoordinator.consumeLongPress = true
        uiBinding.cameraCaptureButton.onLongPressHoldStarted?.invoke()
        assertEquals(1, stubCoordinator.longPressCount)

        stubCoordinator.consumeLongPress = false
        stubCoordinator.allowLongPressVideo = false
        uiBinding.cameraCaptureButton.onLongPressHoldStarted?.invoke()
        assertEquals(2, stubCoordinator.longPressCount)
        assertFalse(gateway.audioRationaleChecked)

        stubCoordinator.allowLongPressVideo = true
        uiBinding.cameraCaptureButton.onLongPressHoldStarted?.invoke()
        assertTrue(gateway.audioRationaleChecked)

        gateway.runtimeState.deviceOrientationDegrees = 90
        stubCoordinator.dotRotation = -90f
        assertEquals(-90f, controller.getDotTargetRotation(), 0.001f)

        gateway.runtimeState.isBurstActive = true
        controller.resetBurstUi()
        assertFalse(gateway.runtimeState.isBurstActive)
        assertTrue(uiBinding.cameraCaptureButton.isEnabled)

        controller.onDestroy()
        focusController.onDestroy()
        CameraLutController(gateway, gateway, gateway).onDestroy()
        CameraLensController(gateway, gateway, gateway).onDestroy()
    }

    // -------------------------------------------------------------------------
    // CameraModeController tests
    // -------------------------------------------------------------------------

    @Test
    fun testModeController_resolveAndCycleModes() {
        val gateway = FakeCameraGateway()
        val overlayController = CameraOverlayController(gateway, gateway, gateway)
        val gestureDelegate = CameraGestureDelegate(themedContext, gateway)
        val focusController = CameraFocusMeteringController(gateway, gateway, gateway, gestureDelegate)
        val videoController = CameraVideoController(gateway, gateway, gateway)
        val shutterController = CameraShutterController(gateway, gateway, gateway, overlayController, focusController, videoController)
        val thumbnailController = CameraThumbnailController(gateway, gateway, gateway)
        val motionController = CameraMotionPhotoController(gateway, gateway, gateway)
        val pipelineDispatcher = CameraCapturePipelineDispatcher(gateway, gateway, gateway)
        val multiCaptureDispatcher = CameraMultiCaptureDispatcher(gateway, gateway, gateway)
        val streamController = CameraHardwareStreamController(gateway, gateway, gateway)
        val lensController = CameraLensController(gateway, gateway, gateway)

        val modeController = CameraModeController(
            gateway,
            gateway,
            gateway,
            CameraModeCollaborators(
                overlay = overlayController,
                shutter = shutterController,
                thumbnail = thumbnailController,
                motionPhoto = motionController,
                pipeline = pipelineDispatcher,
                multiCapture = multiCaptureDispatcher,
                stream = streamController,
                lens = lensController,
                focus = focusController
            )
        )

        gateway.modeControllerRef = modeController

        assertEquals(CaptureMode.NORMAL, modeController.resolveActiveCaptureMode())
        assertFalse(modeController.isHalfFrameActive)

        val repo = SettingsRepository.getInstance(themedContext)
        repo.setActiveCaptureMode(CaptureMode.HALF_FRAME_SBS.key)
        assertEquals(CaptureMode.NORMAL, modeController.resolveActiveCaptureMode())

        repo.edit {
            remove(PreferenceKeys.ACTIVE_CAPTURE_MODE)
            this[PreferenceKeys.HALF_FRAME_MODE] = true
        }
        modeController.resolveActiveCaptureMode()
        assertEquals(CaptureMode.HALF_FRAME_SBS, modeController.effectiveMode)

        modeController.cycleCaptureMode()
        assertEquals(CaptureMode.HALF_FRAME_TB, modeController.effectiveMode)
        assertTrue(gateway.modeCycledFlag == true)

        modeController.cycleCaptureMode()
        assertEquals(CaptureMode.NORMAL, modeController.effectiveMode)

        gateway.runtimeState.isBurstActive = true
        modeController.cycleCaptureMode()
        assertEquals(CaptureMode.NORMAL, modeController.effectiveMode)
        ShadowLooperHelper.runMainLooperIdle()
    }

    @Test
    fun testFocusMeteringController_clearAndShowRingBehavior() {
        val gateway = FakeCameraGateway()
        val gestureDelegate = CameraGestureDelegate(themedContext, gateway)
        val focusController = CameraFocusMeteringController(gateway, gateway, gateway, gestureDelegate)

        assertEquals(gateway.sessionArbiter.camera2Device, gateway.sessionArbiter.activePreviewDevice)
        assertEquals(gateway.sessionArbiter.camera2Session, gateway.sessionArbiter.activePreviewSession)
        assertEquals(gateway.sessionArbiter.camera2PreviewSurface, gateway.sessionArbiter.activePreviewSurface)
        assertEquals(gateway.sessionArbiter.camera2Handler, gateway.sessionArbiter.activePreviewHandler)
        assertNull(gateway.sessionArbiter.activePreviewPhysicalCharacteristics)

        val rect = MeteringRectangle(100, 100, 200, 200, MeteringRectangle.METERING_WEIGHT_MAX)
        gateway.runtimeState.focusMeteringRegion = null
        gateway.runtimeState.exposureMeteringRegion = rect
        focusController.showFocusRingLocked()
        assertEquals(View.GONE, fragmentBinding.focusLockBadge.visibility)

        gateway.runtimeState.focusMeteringRegion = rect
        focusController.showFocusRingLocked()
        assertEquals(View.VISIBLE, fragmentBinding.focusLockBadge.visibility)

        focusController.clearFocusAndMeteringState()
        ShadowLooperHelper.runMainLooperIdle()
        assertNull(gateway.runtimeState.focusMeteringRegion)
        assertNull(gateway.runtimeState.exposureMeteringRegion)
        assertEquals(1, gateway.updateProInfoBarCount)

        focusController.clearFocusAndMeteringState()
        ShadowLooperHelper.runMainLooperIdle()
        assertEquals(1, gateway.updateProInfoBarCount)

        gateway.runtimeState.focusMeteringRegion = rect
        gateway.runtimeState.exposureMeteringRegion = rect
        focusController.unlockFocusAndMetering()
        ShadowLooperHelper.runMainLooperIdle()
        assertNull(gateway.runtimeState.focusMeteringRegion)
        assertNull(gateway.runtimeState.exposureMeteringRegion)
        assertEquals(2, gateway.updateProInfoBarCount)

        focusController.onDestroy()
    }

    @Test
    fun testStreamController_updateZslStreamConfigAndHandleCaptureResultSyncGain() {
        val gateway = FakeCameraGateway()
        val streamController = CameraHardwareStreamController(gateway, gateway, gateway)
        gateway.runtimeState.evRange = Range(-6, 6)
        gateway.runtimeState.currentEvIndex = 0
        gateway.runtimeState.isRawSupported = true

        // Baseline steady-state frame at 0 EV (ISO 100, 10ms)
        val baseResult = org.robolectric.shadows.TotalCaptureResultBuilder.newBuilder()
            .set(android.hardware.camera2.CaptureResult.SENSOR_TIMESTAMP, 1_000L)
            .set(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY, 100)
            .set(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME, 10_000_000L)
            .set(android.hardware.camera2.CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION, 0)
            .build()
        streamController.handleCaptureResult(baseResult)
        assertEquals(1.0f, gateway.sessionArbiter.previewGainSynchronizer.currentEffectiveGain, 1e-4f)

        // Arm ZSL HDR+ (-1 EV -> target 2.0f)
        streamController.updateZslStreamConfig(
            rawTargetEnabled = true,
            previewProfileEnabled = true,
            aeCompensationSteps = -3,
            fpsRange = Range(15, 30),
            targetPreviewGain = 2.0f
        )
        assertEquals(2.0f, gateway.sessionArbiter.previewGainSynchronizer.currentSteadyTargetGain, 1e-4f)
        // Must not jump before hardware AE compensation arrives
        assertEquals(1.0f, gateway.sessionArbiter.previewGainSynchronizer.currentEffectiveGain, 1e-4f)

        // In-flight frame still at aeComp = 0 -> gain stays 1.0f
        val staleResult = org.robolectric.shadows.TotalCaptureResultBuilder.newBuilder()
            .set(android.hardware.camera2.CaptureResult.SENSOR_TIMESTAMP, 2_000L)
            .set(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY, 100)
            .set(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME, 10_000_000L)
            .set(android.hardware.camera2.CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION, 0)
            .build()
        streamController.handleCaptureResult(staleResult)
        assertEquals(1.0f, gateway.sessionArbiter.previewGainSynchronizer.resolveGainForTimestamp(2_000L), 1e-4f)

        // Converged frame at aeComp = -3 (ISO 100, 5ms) -> gain becomes 2.0f
        val hdrResult = org.robolectric.shadows.TotalCaptureResultBuilder.newBuilder()
            .set(android.hardware.camera2.CaptureResult.SENSOR_TIMESTAMP, 3_000L)
            .set(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY, 100)
            .set(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME, 5_000_000L)
            .set(android.hardware.camera2.CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION, -3)
            .build()
        streamController.handleCaptureResult(hdrResult)
        assertEquals(2.0f, gateway.sessionArbiter.previewGainSynchronizer.resolveGainForTimestamp(3_000L), 1e-4f)
    }

    @Test
    fun testMultiCamera_lensSwitchClearsFocusLock_andManualConfigCarriesMeteringRegions() {
        val gateway = FakeCameraGateway()
        val overlayController = CameraOverlayController(gateway, gateway, gateway)
        val gestureDelegate = CameraGestureDelegate(themedContext, gateway)
        val focusController = CameraFocusMeteringController(gateway, gateway, gateway, gestureDelegate)
        val videoController = CameraVideoController(gateway, gateway, gateway)
        val shutterController = CameraShutterController(gateway, gateway, gateway, overlayController, focusController, videoController)
        val thumbnailController = CameraThumbnailController(gateway, gateway, gateway)
        val motionController = CameraMotionPhotoController(gateway, gateway, gateway)
        val pipelineDispatcher = CameraCapturePipelineDispatcher(gateway, gateway, gateway)
        val multiCaptureDispatcher = CameraMultiCaptureDispatcher(gateway, gateway, gateway)
        val streamController = CameraHardwareStreamController(gateway, gateway, gateway)
        val lensController = CameraLensController(gateway, gateway, gateway)

        val modeController = CameraModeController(
            gateway,
            gateway,
            gateway,
            CameraModeCollaborators(
                overlay = overlayController,
                shutter = shutterController,
                thumbnail = thumbnailController,
                motionPhoto = motionController,
                pipeline = pipelineDispatcher,
                multiCapture = multiCaptureDispatcher,
                stream = streamController,
                lens = lensController,
                focus = focusController
            )
        )
        gateway.modeControllerRef = modeController

        val rect = MeteringRectangle(50, 50, 150, 150, MeteringRectangle.METERING_WEIGHT_MAX)
        gateway.runtimeState.focusMeteringRegion = rect
        gateway.runtimeState.exposureMeteringRegion = rect

        modeController.updateActiveCoordinator(CaptureMode.MULTI_CAMERA)
        val coordinator = modeController.activeCoordinator
        assertNotNull(coordinator)
        val sampleLens = LensInfo(
            id = "0",
            physicalId = "2",
            sensorId = "2",
            name = "0.6x",
            focalLength = 1.9f,
            equivalentFocalLength = 14f,
            multiplier = 0.6f,
            type = top.maary.darkbag.utils.LensType.ULTRA_WIDE,
            facing = CameraCharacteristics.LENS_FACING_BACK,
            useCamera2 = true
        )
        assertTrue(coordinator!!.onLensSelected(sampleLens))
        ShadowLooperHelper.runMainLooperIdle()

        assertNull(gateway.runtimeState.focusMeteringRegion)
        assertNull(gateway.runtimeState.exposureMeteringRegion)
        assertEquals(1, gateway.updateProInfoBarCount)

        val config = top.maary.darkbag.camera.MultiCameraManualConfig(
            focusMeteringRegion = rect,
            exposureMeteringRegion = rect
        )
        assertEquals(rect, config.focusMeteringRegion)
        assertEquals(rect, config.exposureMeteringRegion)

        shutterController.onDestroy()
        focusController.onDestroy()
    }

    @Test
    fun testFocusMeteringController_sanitizeCropRegion_validatesBounds() {
        val gateway = FakeCameraGateway()
        val gestureDelegate = CameraGestureDelegate(themedContext, gateway)
        val focusController = CameraFocusMeteringController(gateway, gateway, gateway, gestureDelegate)

        val activeArray = android.graphics.Rect(0, 0, 3264, 2448)

        assertEquals(activeArray, focusController.sanitizeCropRegion(null, activeArray))
        assertEquals(activeArray, focusController.sanitizeCropRegion(android.graphics.Rect(0, 0, 0, 0), activeArray))

        val oversized = android.graphics.Rect(0, 0, 4000, 3000)
        assertEquals(activeArray, focusController.sanitizeCropRegion(oversized, activeArray))

        val validSubCrop = android.graphics.Rect(408, 306, 2856, 2142)
        assertEquals(validSubCrop, focusController.sanitizeCropRegion(validSubCrop, activeArray))

        focusController.onDestroy()
    }

    @Test
    fun testMultiCamera_concurrentStandalone_keepsPreviewCharacteristicsAndMeteringOnStandalonePrimary() {
        val wideChars = org.robolectric.shadows.ShadowCameraCharacteristics.newCameraCharacteristics()
        val uwChars = org.robolectric.shadows.ShadowCameraCharacteristics.newCameraCharacteristics()

        val wideLens = top.maary.darkbag.utils.PhysicalLensInfo(
            physicalId = "0",
            name = "1.0x",
            focalLength = 4.5f,
            equivalentFocalLength = 24f,
            multiplier = 1.0f,
            type = top.maary.darkbag.utils.LensType.WIDE,
            characteristics = wideChars
        )
        val uwLens = top.maary.darkbag.utils.PhysicalLensInfo(
            physicalId = "2",
            name = "0.6x",
            focalLength = 1.9f,
            equivalentFocalLength = 14f,
            multiplier = 0.6f,
            type = top.maary.darkbag.utils.LensType.ULTRA_WIDE,
            characteristics = uwChars
        )
        val standaloneInfo = top.maary.darkbag.utils.LogicalMultiCameraInfo(
            logicalCameraId = "0",
            isLogicalMultiCamera = false,
            syncType = 0,
            physicalLenses = listOf(wideLens, uwLens),
            hardwareType = top.maary.darkbag.utils.MultiCameraHardwareType.CONCURRENT_STANDALONE
        )

        val manager = MultiCameraCaptureManager(themedContext, testScope)
        manager.configurePreviewStateForTesting(
            logicalInfo = standaloneInfo,
            lenses = listOf(wideLens, uwLens),
            primaryLens = wideLens,
            standalonePrimaryId = "0"
        )

        manager.setPrimaryPreviewLens(uwLens)
        assertEquals("0", manager.resolveActivePreviewPhysicalId())
        assertTrue(manager.getActivePreviewPhysicalCharacteristics() === wideChars)

        val logicalInfo = standaloneInfo.copy(
            isLogicalMultiCamera = true,
            hardwareType = top.maary.darkbag.utils.MultiCameraHardwareType.NATIVE_LOGICAL
        )
        manager.configurePreviewStateForTesting(
            logicalInfo = logicalInfo,
            lenses = listOf(wideLens, uwLens),
            primaryLens = wideLens,
            standalonePrimaryId = null
        )
        manager.setPrimaryPreviewLens(uwLens)
        assertEquals("2", manager.resolveActivePreviewPhysicalId())
        assertTrue(manager.getActivePreviewPhysicalCharacteristics() === uwChars)

        val region = MeteringRectangle(100, 100, 200, 200, MeteringRectangle.METERING_WEIGHT_MAX)
        val manualConfig = top.maary.darkbag.camera.MultiCameraManualConfig(
            isLinked = false,
            focusMeteringRegion = region,
            exposureMeteringRegion = region
        )
        val specForPreviewWide = manager.resolveManualControlSpec(
            isPrimary = false,
            config = manualConfig,
            isMeteringPrimary = true
        )
        assertEquals(CaptureRequest.CONTROL_AF_MODE_AUTO, specForPreviewWide.afMode)
        assertEquals(region, specForPreviewWide.afRegion)
        assertEquals(region, specForPreviewWide.aeRegion)

        val specForNonPreviewUw = manager.resolveManualControlSpec(
            isPrimary = true,
            config = manualConfig,
            isMeteringPrimary = false
        )
        assertEquals(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE, specForNonPreviewUw.afMode)
        assertNull(specForNonPreviewUw.afRegion)
        assertNull(specForNonPreviewUw.aeRegion)
    }

    // -------------------------------------------------------------------------
    // CameraOverlayController ViewFinder constraint & aspect ratio tests
    // -------------------------------------------------------------------------

    @Test
    fun testOverlayController_updateViewFinderConstraintsAndAspectRatio() {
        val gateway = FakeCameraGateway()
        val overlayController = CameraOverlayController(gateway, gateway, gateway)

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
        assertEquals(uiBinding.bottomIslandCard.id, updatedLp.bottomToTop)
        assertTrue(fragmentBinding.viewFinderContainer.clipToOutline)
    }

    @Test
    fun testOverlayController_updateHalfFrameUI_preservesMultiCameraContainerRole() {
        val activity = org.robolectric.Robolectric.buildActivity(FragmentActivity::class.java)
            .create().start().resume().visible().get()
        activity.setTheme(R.style.AppTheme)
        activity.setContentView(fragmentBinding.root)
        ShadowLooperHelper.runMainLooperIdle()

        val gateway = FakeCameraGateway()
        val overlayController = CameraOverlayController(gateway, gateway, gateway)
        val card = uiBinding.bottomIslandCard

        gateway.activeCoordinatorOverride = StubCoordinator(CaptureMode.MULTI_CAMERA)
        gateway.effectiveModeOverride = CaptureMode.MULTI_CAMERA
        overlayController.updateHalfFrameUI()
        ShadowLooperHelper.runMainLooperIdle()
        assertEquals(top.maary.darkbag.ui.bento.BentoContainerRole.SECONDARY_CONTAINER, card.containerRole)

        gateway.activeCoordinatorOverride = StubCoordinator(CaptureMode.HALF_FRAME_SBS)
        gateway.effectiveModeOverride = CaptureMode.HALF_FRAME_SBS
        overlayController.updateHalfFrameUI()
        ShadowLooperHelper.runMainLooperIdle()
        assertEquals(top.maary.darkbag.ui.bento.BentoContainerRole.TERTIARY_CONTAINER, card.containerRole)

        gateway.activeCoordinatorOverride = StubCoordinator(CaptureMode.NORMAL)
        gateway.effectiveModeOverride = CaptureMode.NORMAL
        overlayController.updateHalfFrameUI()
        ShadowLooperHelper.runMainLooperIdle()
        assertEquals(top.maary.darkbag.ui.bento.BentoContainerRole.SURFACE_CONTAINER, card.containerRole)
    }

    // -------------------------------------------------------------------------
    // CameraTierController tests
    // -------------------------------------------------------------------------

    @Test
    fun testTierController_bindTierControls_noRecursion() {
        val gateway = FakeCameraGateway()
        val tierController = CameraTierController(gateway, gateway, gateway)
        var visibilityRefreshed = false
        var lensRefreshed = false

        tierController.bindTierControls(
            onVisibilityRefresh = { visibilityRefreshed = true },
            onLensRefresh = { lensRefreshed = true }
        )

        assertEquals(CameraUiTier.FILM, viewModel.uiState.value.uiTier)
        assertNotNull(uiBinding.btnBentoTier)

        uiBinding.btnBentoTier?.performClick()
        ShadowLooperHelper.runMainLooperIdle()

        assertEquals(CameraUiTier.PRO, viewModel.uiState.value.uiTier)
        assertTrue(visibilityRefreshed)
        assertTrue(lensRefreshed)
    }

    @Test
    fun testTierController_updateTierUi_and_updateProInfoBar_noStackOverflow() {
        val gateway = FakeCameraGateway()
        val tierController = CameraTierController(gateway, gateway, gateway)

        viewModel.setUiTier(CameraUiTier.PRO)
        tierController.updateTierUi(CameraUiTier.PRO)
        tierController.updateProInfoBar()
        assertEquals(CameraUiTier.PRO.displayLabel, uiBinding.btnBentoTier?.text)

        viewModel.setUiTier(CameraUiTier.FILM)
        tierController.updateTierUi(CameraUiTier.FILM)
        tierController.updateProInfoBar()
        assertEquals(CameraUiTier.FILM.displayLabel, uiBinding.btnBentoTier?.text)

        viewModel.setUiTier(CameraUiTier.POINT_AND_SHOOT)
        tierController.updateTierUi(CameraUiTier.POINT_AND_SHOOT)
        assertEquals(CameraUiTier.POINT_AND_SHOOT.displayLabel, uiBinding.btnBentoTier?.text)
    }

    @Test
    fun testTierController_manualFlags_syncToViewModel() {
        val gateway = FakeCameraGateway()
        val tierController = CameraTierController(gateway, gateway, gateway)
        gateway.runtimeState.isManualFocus = true
        gateway.runtimeState.isManualIso = true
        gateway.runtimeState.currentIso = 800

        tierController.updateProInfoBar()

        assertTrue(viewModel.uiState.value.isManualFocus)
        assertTrue(viewModel.uiState.value.isManualIso)
    }

    // =========================================================================
    // Test Doubles (using the 3 consolidated gateways)
    // =========================================================================

    private object ShadowLooperHelper {
        fun runMainLooperIdle() {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        }
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

    private inner class FakeCameraGateway :
        CameraEnvironmentHost,
        CameraUiHost,
        CameraCoordinatorHost {

        var applyCameraControlsCalled = false
        var updateProInfoBarCount = 0
        var modeCycledFlag: Boolean? = null
        var modeControllerRef: CameraModeController? = null
        var audioRationaleChecked = false
        var activeCoordinatorOverride: CaptureModeCoordinator? = null
        var effectiveModeOverride: CaptureMode? = null

        override val hostContext: Context get() = themedContext
        override val hostActivity: FragmentActivity? = null
        override val isHostAdded: Boolean = true
        override val lifecycleScope: CoroutineScope get() = testScope
        override val cameraViewModel: CameraViewModel get() = viewModel
        override val camera2Manager: CameraManager =
            themedContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        override val runtimeState = CameraRuntimeStateHolder().apply {
            isoRange = Range(50, 3200)
            exposureTimeRange = Range(1000L, 1_000_000_000L)
            minFocusDistance = 10f
            evRange = Range(-4, 4)
            isRawSupported = true
        }
        override val sessionArbiter = CameraSessionArbiter(camera2Manager, runtimeState)
        override val cameraRepository = CameraRepository(themedContext)
        override val imageRepository = ImageRepository(themedContext)
        override val lutManager = LutManager(themedContext)
        override val locationHelper = LocationHelper(themedContext)
        override val displayRotation: Int = Surface.ROTATION_0
        override val shouldMirror: Boolean = false

        override val fragmentCameraBinding: FragmentCameraBinding get() = fragmentBinding
        override val cameraUiContainerBinding: CameraUiContainerBinding get() = uiBinding
        override val rootViewGroup: ViewGroup? get() = fragmentBinding.root as? ViewGroup
        override val isFloatingOverlayOpen: Boolean = false

        override val activeCoordinator: CaptureModeCoordinator?
            get() = modeControllerRef?.activeCoordinator ?: activeCoordinatorOverride
        override val effectiveCaptureMode: CaptureMode
            get() = effectiveModeOverride ?: modeControllerRef?.effectiveMode ?: activeCoordinator?.mode ?: CaptureMode.NORMAL
        override val halfFrameStep: Int = 0

        override fun shouldShowAudioPermissionRationale(): Boolean {
            audioRationaleChecked = true
            return false
        }
        override fun requestAudioPermission() {}

        override fun applyUIVisibility() {}
        override fun dismissFloatingOverlays() {}
        override fun updateProInfoBar() { updateProInfoBarCount++ }
        override fun updateDialPanel() {}
        override fun formatFocusDist(dist: Float): String = "∞"
        override fun formatShutterTime(nanos: Long): String = "1/100s"
        override fun showShutterBlackout() {}
        override fun showProcessingAnimation() {}
        override fun hideProcessingAnimation() {}
        override fun resetBurstUi() {}
        override fun updateCurrentThumbnail(uri: Uri?, timestamp: Long): Boolean = true
        override fun setGalleryThumbnail(filename: String?, timestamp: Long): Boolean = true
        override fun onSessionConfigured() {}
        override fun triggerTapToFocus(x: Float, y: Float) {}
        override fun unlockFocusAndMetering() {}

        override fun ensureActiveCoordinator(): CaptureModeCoordinator? = activeCoordinator
        override fun bindCameraUseCases() {}
        override fun applyCameraControls() { applyCameraControlsCalled = true }
        override fun applyManualSettingsToRequest(request: CaptureRequest.Builder, isHdrBurst: Boolean, isPreviewStream: Boolean) {}
        override fun applyPreviewFocusAndExposureSettings(builder: CaptureRequest.Builder) {}
        override fun updatePreviewToneMapPolicyForDevice(deviceId: String, chars: android.hardware.camera2.CameraCharacteristics) {}
        override fun handleCaptureResult(result: TotalCaptureResult) {}
        override fun getCombinedOrientation(): Int = 0
        override fun updateMotionPhotoEncoder() {}
        override fun triggerAutoBurst() {}
        override fun onCaptureModeCycled(modeChanged: Boolean) { modeCycledFlag = modeChanged }
        override fun readScopedHalfFrameState(requireFileForStep1: Boolean) {}
        override fun writeScopedHalfFrameStep(step: Int, captureTimeMillis: Long?, digitalGain: Float, flareType: Int) {}
        override fun getDigitalGainAndUpdateStep(timestamp: Long, isFrame1Trigger: Boolean, shutterClick: Long?): Float = 1.0f
    }
}
