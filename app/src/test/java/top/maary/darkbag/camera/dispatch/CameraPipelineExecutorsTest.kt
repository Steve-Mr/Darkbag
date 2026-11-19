package top.maary.darkbag.camera.dispatch

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.net.Uri
import android.os.Looper
import android.util.Range
import android.view.Surface
import android.view.ViewGroup
import androidx.fragment.app.FragmentActivity
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import top.maary.darkbag.camera.delegate.CameraCoordinatorHost
import top.maary.darkbag.camera.delegate.CameraEnvironmentHost
import top.maary.darkbag.camera.delegate.CameraUiHost
import top.maary.darkbag.modes.CaptureMode
import top.maary.darkbag.modes.CaptureModeCoordinator
import top.maary.darkbag.camera.session.CameraSessionArbiter
import top.maary.darkbag.camera.state.CameraRuntimeStateHolder
import top.maary.darkbag.databinding.CameraUiContainerBinding
import top.maary.darkbag.databinding.FragmentCameraBinding
import top.maary.darkbag.fragments.HdrPlusBurst
import top.maary.darkbag.pipeline.model.CaptureTaskSpec
import top.maary.darkbag.pipeline.sink.CaptureSink
import top.maary.darkbag.pipeline.sink.SinkOutputTargets
import top.maary.darkbag.repository.ImageRepository
import top.maary.darkbag.repository.SettingsRepository
import top.maary.darkbag.utils.CameraRepository
import top.maary.darkbag.utils.LocationHelper
import top.maary.darkbag.utils.LutManager
import top.maary.darkbag.viewmodel.CameraViewModel

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CameraPipelineExecutorsTest {

    private lateinit var context: Context
    private lateinit var viewModel: CameraViewModel
    private val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        SettingsRepository.resetForTest(context)
        SettingsRepository.getInstance(context).awaitHydratedBlocking()
        viewModel = CameraViewModel()
    }

    @Test
    fun dispatcher_takeSingleAndBurst_abortCleanlyWhenDeviceNull() {
        val gateway = FakeGateway()
        val dispatcher = CameraCapturePipelineDispatcher(gateway, gateway, gateway)

        gateway.runtimeState.isBurstActive = true
        dispatcher.takeSinglePictureCamera2()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertFalse(gateway.runtimeState.isBurstActive)
        assertTrue(gateway.resetBurstUiCount >= 1)

        gateway.runtimeState.isBurstActive = true
        dispatcher.triggerHdrPlusBurstCamera2()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertFalse(gateway.runtimeState.isBurstActive)
        assertTrue(gateway.resetBurstUiCount >= 2)
    }

    @Test
    fun dispatcher_startAndStopProcessingWorker_togglesCleanly() {
        val gateway = FakeGateway()
        val dispatcher = CameraCapturePipelineDispatcher(gateway, gateway, gateway)
        dispatcher.startProcessingWorker(testScope)
        dispatcher.startProcessingWorker(testScope) // idempotent when already active
        dispatcher.stopProcessingWorker()
        dispatcher.stopProcessingWorker() // idempotent when stopped
    }

    @Test
    fun dispatcher_processImageAsync_notifiesSinkOnErrorWhenCharacteristicsUnavailable(): Unit = runBlocking {
        val gateway = FakeGateway()
        val dispatcher = CameraCapturePipelineDispatcher(gateway, gateway, gateway)
        var capturedError: Throwable? = null

        val sink = object : CaptureSink {
            override fun prepareTargets(context: Context, spec: CaptureTaskSpec) = SinkOutputTargets()
            override suspend fun onImageExported(context: Context, spec: CaptureTaskSpec, success: Boolean, outputPath: String?, outputFd: Int) {}
            override suspend fun onRawExported(context: Context, spec: CaptureTaskSpec, success: Boolean, outputPath: String?, outputFd: Int) {}
            override suspend fun onComplete(context: Context, spec: CaptureTaskSpec, jpgSuccess: Boolean, rawSuccess: Boolean) {}
            override suspend fun onError(context: Context, spec: CaptureTaskSpec, error: Throwable) {
                capturedError = error
            }
        }

        val buffer = ByteBuffer.allocateDirect(128)
        val holder = RawImageHolder(
            data = buffer,
            width = 8,
            height = 8,
            timestamp = 123456L,
            rotationDegrees = 0,
            combinedOrientation = 0,
            zoomRatio = 1.0f,
            physicalId = "nonexistent_camera_id",
            sink = sink
        )

        dispatcher.processImageAsync(context, holder)
        // Robolectric throws IllegalArgumentException for nonexistent camera id -> sink.onError invoked
        assertNotNull(capturedError)
    }

    @Test
    fun burstEnqueuer_processHdrPlusBurst_handlesEmptyFramesGracefully() {
        val gateway = FakeGateway()
        val dispatcher = CameraCapturePipelineDispatcher(gateway, gateway, gateway)
        val megaBuffer = ByteBuffer.allocateDirect(64)
        val emptyBurst = top.maary.darkbag.fragments.BurstResult(megaBuffer, emptyList())

        dispatcher.processHdrPlusBurst(
            burstResult = emptyBurst,
            digitalGain = 1.0f
        )
        // Empty frames triggers IndexOutOfBoundsException caught in try/catch -> resets UI
        val deadline = System.currentTimeMillis() + 1000L
        while (gateway.resetBurstUiCount < 1 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(gateway.resetBurstUiCount >= 1)
    }

    private inner class FakeGateway :
        CameraEnvironmentHost,
        CameraUiHost,
        CameraCoordinatorHost {

        var resetBurstUiCount = 0

        override val hostContext: Context get() = context
        override val hostActivity: FragmentActivity? = null
        override val isHostAdded: Boolean = true
        override val lifecycleScope: CoroutineScope get() = testScope
        override val cameraViewModel: CameraViewModel get() = viewModel
        override val camera2Manager: CameraManager =
            context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        override val runtimeState = CameraRuntimeStateHolder().apply {
            isoRange = Range(100, 3200)
            exposureTimeRange = Range(1000L, 1_000_000_000L)
            isRawSupported = true
        }
        override val sessionArbiter = CameraSessionArbiter(camera2Manager, runtimeState)
        override val cameraRepository = CameraRepository(context)
        override val imageRepository = ImageRepository(context)
        override val lutManager = LutManager(context)
        override val locationHelper = LocationHelper(context)
        override val displayRotation: Int = Surface.ROTATION_0
        override val shouldMirror: Boolean = false

        override val fragmentCameraBinding: FragmentCameraBinding? = null
        override val cameraUiContainerBinding: CameraUiContainerBinding? = null
        override val rootViewGroup: ViewGroup? = null
        override val isFloatingOverlayOpen: Boolean = false

        override val activeCoordinator: CaptureModeCoordinator? = null
        override val effectiveCaptureMode: CaptureMode = CaptureMode.NORMAL
        override val halfFrameStep: Int = 0

        override fun shouldShowAudioPermissionRationale(): Boolean = false
        override fun requestAudioPermission() {}
        override fun applyUIVisibility() {}
        override fun dismissFloatingOverlays() {}
        override fun updateProInfoBar() {}
        override fun updateDialPanel() {}
        override fun formatFocusDist(dist: Float): String = "∞"
        override fun formatShutterTime(nanos: Long): String = "1/100s"
        override fun showShutterBlackout() {}
        override fun showProcessingAnimation() {}
        override fun hideProcessingAnimation() {}
        override fun resetBurstUi() { resetBurstUiCount++ }
        override fun updateCurrentThumbnail(uri: Uri?) {}
        override fun setGalleryThumbnail(filename: String?) {}
        override fun onSessionConfigured() {}
        override fun triggerTapToFocus(x: Float, y: Float) {}
        override fun unlockFocusAndMetering() {}
        override fun ensureActiveCoordinator(): CaptureModeCoordinator? = null
        override fun bindCameraUseCases() {}
        override fun applyCameraControls() {}
        override fun applyManualSettingsToRequest(request: CaptureRequest.Builder, isHdrBurst: Boolean) {}
        override fun applyPreviewFocusAndExposureSettings(builder: CaptureRequest.Builder) {}
        override fun handleCaptureResult(result: TotalCaptureResult) {}
        override fun getCombinedOrientation(): Int = 0
        override fun updateMotionPhotoEncoder() {}
        override fun triggerAutoBurst() {}
        override fun onCaptureModeCycled(modeChanged: Boolean) {}
        override fun readScopedHalfFrameState(requireFileForStep1: Boolean) {}
        override fun writeScopedHalfFrameStep(step: Int, captureTimeMillis: Long?, digitalGain: Float, flareType: Int) {}
        override fun getDigitalGainAndUpdateStep(timestamp: Long, isFrame1Trigger: Boolean, shutterClick: Long?): Float = 1.0f
    }
}
