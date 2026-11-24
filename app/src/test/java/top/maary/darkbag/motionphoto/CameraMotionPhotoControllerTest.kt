package top.maary.darkbag.motionphoto

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.net.Uri
import android.view.ContextThemeWrapper
import android.view.Surface
import android.view.ViewGroup
import androidx.fragment.app.FragmentActivity
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowCameraCharacteristics
import top.maary.darkbag.R
import top.maary.darkbag.camera.delegate.CameraCoordinatorHost
import top.maary.darkbag.camera.delegate.CameraEnvironmentHost
import top.maary.darkbag.camera.delegate.CameraMotionPhotoController
import top.maary.darkbag.camera.delegate.CameraUiHost
import top.maary.darkbag.camera.session.CameraSessionArbiter
import top.maary.darkbag.camera.state.CameraRuntimeStateHolder
import top.maary.darkbag.databinding.CameraUiContainerBinding
import top.maary.darkbag.databinding.FragmentCameraBinding
import top.maary.darkbag.modes.CaptureMode
import top.maary.darkbag.modes.CaptureModeCoordinator
import top.maary.darkbag.repository.ImageRepository
import top.maary.darkbag.repository.PreferenceKeys
import top.maary.darkbag.repository.SettingsRepository
import top.maary.darkbag.utils.CameraRepository
import top.maary.darkbag.utils.LocationHelper
import top.maary.darkbag.utils.LutManager
import top.maary.darkbag.viewmodel.CameraViewModel
import java.io.File
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CameraMotionPhotoControllerTest {

    private lateinit var themedContext: Context
    private lateinit var cameraManager: CameraManager
    private lateinit var gateway: TestGateway
    private val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val directExecutor = Executor { it.run() }

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        themedContext = ContextThemeWrapper(app, R.style.AppTheme)
        themedContext.getSharedPreferences(PreferenceKeys.LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        SettingsRepository.resetForTest(themedContext)
        SettingsRepository.getInstance(themedContext).awaitHydratedBlocking()

        cameraManager = themedContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val chars = ShadowCameraCharacteristics.newCameraCharacteristics()
        Shadows.shadowOf(chars).set(CameraCharacteristics.SENSOR_ORIENTATION, 90)
        Shadows.shadowOf(cameraManager).addCamera("0", chars)

        gateway = TestGateway()
        gateway.runtimeState.currentToneMapCameraId = "0"
    }

    @After
    fun tearDown() {
        gateway.sessionArbiter.closeRawFrameRouter()
        gateway.sessionArbiter.rawImageReader?.close()
        gateway.sessionArbiter.rawImageReader = null
        SettingsRepository.resetForTest(themedContext)
    }

    @Test
    fun buildCandidateEncoderSizes_producesExpectedPortraitAndLandscapeLadders() {
        // Non-RAW always targets 1080x1440
        assertEquals(
            listOf(1080 to 1440),
            CameraMotionPhotoController.buildCandidateEncoderSizes(
                isRawMotion = false,
                rawWidth = 4096,
                rawHeight = 3072,
                qualityMode = RawMotionQuality.NATIVE.mode,
                sensorOrientationDegrees = 90
            )
        )

        // 4096x3072 sensor @ 90 deg -> portrait ladders
        assertEquals(
            listOf(768 to 1024, 1080 to 1440),
            CameraMotionPhotoController.buildCandidateEncoderSizes(
                isRawMotion = true,
                rawWidth = 4096,
                rawHeight = 3072,
                qualityMode = RawMotionQuality.SUBSAMPLE_4X4.mode,
                sensorOrientationDegrees = 90
            )
        )
        assertEquals(
            listOf(1536 to 2048, 768 to 1024, 1080 to 1440),
            CameraMotionPhotoController.buildCandidateEncoderSizes(
                isRawMotion = true,
                rawWidth = 4096,
                rawHeight = 3072,
                qualityMode = RawMotionQuality.BINNING_2X2.mode,
                sensorOrientationDegrees = 90
            )
        )
        assertEquals(
            listOf(3072 to 4096, 2160 to 2880, 1920 to 2560, 1536 to 2048, 768 to 1024, 1080 to 1440),
            CameraMotionPhotoController.buildCandidateEncoderSizes(
                isRawMotion = true,
                rawWidth = 4096,
                rawHeight = 3072,
                qualityMode = RawMotionQuality.NATIVE.mode,
                sensorOrientationDegrees = 90
            )
        )

        // 50MP sensor clamps Native -> 2x2
        assertEquals(
            listOf(3072 to 4096, 2160 to 2880, 1920 to 2560, 1536 to 2048, 1080 to 1440),
            CameraMotionPhotoController.buildCandidateEncoderSizes(
                isRawMotion = true,
                rawWidth = 8192,
                rawHeight = 6144,
                qualityMode = RawMotionQuality.NATIVE.mode,
                sensorOrientationDegrees = 90
            )
        )

        // 0 deg sensor preserves landscape dimensions
        assertEquals(
            listOf(1024 to 768, 1080 to 1440),
            CameraMotionPhotoController.buildCandidateEncoderSizes(
                isRawMotion = true,
                rawWidth = 4096,
                rawHeight = 3072,
                qualityMode = RawMotionQuality.SUBSAMPLE_4X4.mode,
                sensorOrientationDegrees = 0
            )
        )
    }

    @Test
    fun selectSupportedCandidates_filtersUnsupportedSizesWithDefaultFallback() {
        val candidates = listOf(3072 to 4096, 1536 to 2048, 768 to 1024, 1080 to 1440)
        val filtered = CameraMotionPhotoController.selectSupportedCandidates(candidates, 30) { _, w, h, _ ->
            w <= 1080 && h <= 1440
        }
        assertEquals(listOf(768 to 1024, 1080 to 1440), filtered)

        val fallback = CameraMotionPhotoController.selectSupportedCandidates(candidates, 30) { _, _, _, _ -> false }
        assertEquals(listOf(1080 to 1440), fallback)
    }

    @Test
    fun updateEncoder_defersBeforeSessionReadyAndAttachesTapOnceConfigured() {
        var sessionReady = false
        val created = mutableListOf<FakeMotionEncoder>()
        val controller = CameraMotionPhotoController(
            env = gateway,
            ui = gateway,
            coordinator = gateway,
            lifecycleExecutor = directExecutor,
            encoderFactory = { w, h, _, _, audio ->
                FakeMotionEncoder(w, h, audio, shouldSucceed = true).also { created.add(it) }
            },
            sizeSupportChecker = { _, _, _, _ -> true },
            isSessionReady = { sessionReady }
        )

        val repo = SettingsRepository.getInstance(themedContext)
        repo.setMotionPhotoEnabled(true)
        repo.setExpRawMotionPhoto(true)
        repo.setExpRawMotionPhotoQuality(RawMotionQuality.BINNING_2X2.mode)

        // Before Camera2 session is ready: must defer without spawning a throwaway 1080x1440 encoder
        controller.updateEncoder()
        assertEquals(0, created.size)
        assertNull(controller.activeEncoderSize)
        assertEquals(true, gateway.rawMotionTargetEnabled)

        // Simulate Camera2 session + rawImageReader + rawFrameRouter becoming active
        val reader = ImageReader.newInstance(4096, 3072, ImageFormat.RAW_SENSOR, 2)
        gateway.sessionArbiter.rawImageReader = reader
        val router = gateway.sessionArbiter.ensureRawFrameRouter(reader) { null }
        sessionReady = true

        controller.updateEncoder()
        assertEquals(1, created.size)
        assertEquals(1536 to 2048, controller.activeEncoderSize)
        assertNotNull("rawMotionTap must be attached when session is configured", router.rawMotionTap)
    }

    @Test
    fun updateEncoder_fallsBackDownLadderWhenEncoderStartFailsAndRetriesCleanly() {
        var failAll = false
        val attemptedSizes = mutableListOf<Pair<Int, Int>>()
        val created = mutableListOf<FakeMotionEncoder>()
        val controller = CameraMotionPhotoController(
            env = gateway,
            ui = gateway,
            coordinator = gateway,
            lifecycleExecutor = directExecutor,
            encoderFactory = { w, h, _, _, audio ->
                attemptedSizes.add(w to h)
                val ok = !failAll && (w <= 1536 && h <= 2048)
                FakeMotionEncoder(w, h, audio, shouldSucceed = ok).also { created.add(it) }
            },
            sizeSupportChecker = { _, _, _, _ -> true },
            isSessionReady = { true }
        )

        val reader = ImageReader.newInstance(4096, 3072, ImageFormat.RAW_SENSOR, 2)
        gateway.sessionArbiter.rawImageReader = reader
        val router = gateway.sessionArbiter.ensureRawFrameRouter(reader) { null }

        val repo = SettingsRepository.getInstance(themedContext)
        repo.setMotionPhotoEnabled(true)
        repo.setExpRawMotionPhoto(true)
        repo.setExpRawMotionPhotoQuality(RawMotionQuality.NATIVE.mode)

        // Native (3072x4096) & 4K intermediates (>1536) fail start() -> falls back cleanly to 1536x2048
        controller.updateEncoder()
        assertEquals(
            listOf(3072 to 4096, 2160 to 2880, 1920 to 2560, 1536 to 2048),
            attemptedSizes
        )
        assertEquals(1536 to 2048, controller.activeEncoderSize)
        assertEquals(1, created.count { it.isEncoding })

        // Redundant updateEncoder with same settings preserves running fallback encoder
        val countBefore = attemptedSizes.size
        controller.updateEncoder()
        assertEquals(countBefore, attemptedSizes.size)

        // Release stops active encoder and clears rawMotionTap
        controller.release()
        assertNull(controller.activeEncoderSize)
        assertNull(router.rawMotionTap)
        assertTrue(created.last().stopped)

        // When all candidates fail, encoder stays null, RAW tap/target stop cleanly, and retry works
        failAll = true
        repo.setExpRawMotionPhotoQuality(RawMotionQuality.BINNING_2X2.mode)
        controller.updateEncoder()
        assertNull(controller.activeEncoderSize)
        assertNull("RAW tap must be detached when all encoder candidates fail", router.rawMotionTap)
        assertFalse("RAW motion target must be disarmed when all encoder candidates fail", gateway.rawMotionTargetEnabled)

        failAll = false
        controller.updateEncoder()
        assertEquals(1536 to 2048, controller.activeEncoderSize)
        assertNotNull(router.rawMotionTap)
        assertTrue(gateway.rawMotionTargetEnabled)
    }

    @Test
    fun updateEncoder_queuedGenerationSupersedesStaleTaskAndStopsMidStartEncoder() {
        val queuedTasks = ArrayDeque<Runnable>()
        val queuedExecutor = Executor { queuedTasks.addLast(it) }
        val attemptedSizes = mutableListOf<Pair<Int, Int>>()
        val created = mutableListOf<FakeMotionEncoder>()
        var releaseMidStart = false
        lateinit var controller: CameraMotionPhotoController

        controller = CameraMotionPhotoController(
            env = gateway,
            ui = gateway,
            coordinator = gateway,
            lifecycleExecutor = queuedExecutor,
            encoderFactory = { w, h, _, _, audio ->
                attemptedSizes.add(w to h)
                FakeMotionEncoder(
                    reqWidth = w,
                    reqHeight = h,
                    audio = audio,
                    shouldSucceed = true,
                    onStartHook = {
                        if (releaseMidStart) {
                            releaseMidStart = false
                            controller.release()
                        }
                    }
                ).also { created.add(it) }
            },
            sizeSupportChecker = { _, _, _, _ -> true },
            isSessionReady = { true }
        )

        val reader = ImageReader.newInstance(4096, 3072, ImageFormat.RAW_SENSOR, 2)
        gateway.sessionArbiter.rawImageReader = reader
        val router = gateway.sessionArbiter.ensureRawFrameRouter(reader) { null }

        val repo = SettingsRepository.getInstance(themedContext)
        repo.setMotionPhotoEnabled(true)
        repo.setExpRawMotionPhoto(true)

        // 1. Enqueue 2x2 task, then immediately supersede with 4x4 before draining queue
        repo.setExpRawMotionPhotoQuality(RawMotionQuality.BINNING_2X2.mode)
        controller.updateEncoder()
        repo.setExpRawMotionPhotoQuality(RawMotionQuality.SUBSAMPLE_4X4.mode)
        controller.updateEncoder()

        assertEquals(2, queuedTasks.size)
        while (queuedTasks.isNotEmpty()) {
            queuedTasks.removeFirst().run()
        }

        // Only the newer 4x4 (768x1024) task should have created an encoder
        assertEquals(listOf(768 to 1024), attemptedSizes)
        assertEquals(768 to 1024, controller.activeEncoderSize)

        // 2. Release triggered mid-start must immediately stop the stale started encoder
        controller.release()
        while (queuedTasks.isNotEmpty()) {
            queuedTasks.removeFirst().run()
        }
        attemptedSizes.clear()
        created.clear()

        releaseMidStart = true
        repo.setExpRawMotionPhotoQuality(RawMotionQuality.BINNING_2X2.mode)
        controller.updateEncoder()
        while (queuedTasks.isNotEmpty()) {
            queuedTasks.removeFirst().run()
        }

        assertEquals(listOf(1536 to 2048), attemptedSizes)
        assertNull("Encoder superseded mid-start must not remain active", controller.activeEncoderSize)
        assertTrue("Superseded encoder must be stopped immediately", created.single().stopped)
        assertNull(router.rawMotionTap)
    }

    private class FakeMotionEncoder(
        reqWidth: Int,
        reqHeight: Int,
        audio: Boolean,
        private val shouldSucceed: Boolean,
        private val onStartHook: (() -> Unit)? = null
    ) : MotionPhotoEncoder(reqWidth, reqHeight, 30, 10_000_000, audio) {
        private var running = false
        var stopped = false

        override val surface: Surface? = null
        override val isEncoding: Boolean get() = running

        override fun start(): Boolean {
            if (!shouldSucceed) return false
            onStartHook?.invoke()
            running = true
            return true
        }

        override fun updateAudioEnabled(enabled: Boolean) {
            isAudioEnabled = enabled
        }

        override fun captureSnapshot(
            captureTimestampNs: Long,
            preDurationMs: Long,
            postDurationMs: Long,
            outputFile: File,
            orientationDegrees: Int,
            stillTimestampProvider: (() -> Long)?,
            onComplete: (file: File?, stillPtsUs: Long) -> Unit
        ) {
            onComplete(null, 0L)
        }

        override fun stop() {
            running = false
            stopped = true
        }
    }

    private inner class TestGateway :
        CameraEnvironmentHost,
        CameraUiHost,
        CameraCoordinatorHost {

        var rawMotionTargetEnabled: Boolean = false

        override val hostContext: Context get() = themedContext
        override val hostActivity: FragmentActivity? = null
        override val isHostAdded: Boolean = true
        override val lifecycleScope: CoroutineScope get() = testScope
        override val cameraViewModel: CameraViewModel = CameraViewModel()
        override val camera2Manager: CameraManager get() = this@CameraMotionPhotoControllerTest.cameraManager
        override val runtimeState = CameraRuntimeStateHolder().apply {
            isRawSupported = true
        }
        override val sessionArbiter = CameraSessionArbiter(camera2Manager, runtimeState)
        override val cameraRepository = CameraRepository(themedContext)
        override val imageRepository = ImageRepository(themedContext)
        override val lutManager = LutManager(themedContext)
        override val locationHelper = LocationHelper(themedContext)
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
        override fun resetBurstUi() {}
        override fun updateCurrentThumbnail(uri: Uri?) {}
        override fun setGalleryThumbnail(filename: String?) {}
        override fun onSessionConfigured() {}
        override fun triggerTapToFocus(x: Float, y: Float) {}
        override fun unlockFocusAndMetering() {}
        override fun ensureActiveCoordinator(): CaptureModeCoordinator? = null
        override fun bindCameraUseCases() {}
        override fun applyCameraControls() {}
        override fun applyManualSettingsToRequest(
            request: CaptureRequest.Builder,
            isHdrBurst: Boolean,
            isPreviewStream: Boolean
        ) {}
        override fun applyPreviewFocusAndExposureSettings(builder: CaptureRequest.Builder) {}
        override fun updatePreviewToneMapPolicyForDevice(
            deviceId: String,
            chars: CameraCharacteristics
        ) {}
        override fun handleCaptureResult(result: TotalCaptureResult) {}
        override fun getCombinedOrientation(): Int = 0
        override fun updateMotionPhotoEncoder() {}
        override fun setRawMotionPhotoTargetEnabled(enabled: Boolean) {
            rawMotionTargetEnabled = enabled
        }
        override fun triggerAutoBurst() {}
        override fun onCaptureModeCycled(modeChanged: Boolean) {}
        override fun readScopedHalfFrameState(requireFileForStep1: Boolean) {}
        override fun writeScopedHalfFrameStep(
            step: Int,
            captureTimeMillis: Long?,
            digitalGain: Float,
            flareType: Int
        ) {}
        override fun getDigitalGainAndUpdateStep(
            timestamp: Long,
            isFrame1Trigger: Boolean,
            shutterClick: Long?
        ): Float = 1.0f
    }
}
