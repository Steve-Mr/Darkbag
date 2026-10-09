package top.maary.darkbag.modes

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
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
import top.maary.darkbag.fragments.SettingsFragment
import top.maary.darkbag.models.CaptureTimingMode
import top.maary.darkbag.models.StandardTimingTracker
import top.maary.darkbag.pipeline.sink.CaptureSink
import top.maary.darkbag.pipeline.sink.DirectMediaStoreSink
import top.maary.darkbag.pipeline.sink.HalfFrameCacheSink
import top.maary.darkbag.utils.HalfFrameManager
import top.maary.darkbag.utils.HalfFrameSessionStore

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class CaptureModeCoordinatorTest {

    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences

    class MockModeExecutionContext(
        override val context: Context,
        override val preferences: SharedPreferences,
        override var isHdrPlusEnabled: Boolean = true,
        override var isRawSupported: Boolean = true,
        override var deviceOrientationDegrees: Int = 0
    ) : ModeExecutionContext {

        var canTrigger = true
        var shutterVisualsShown = false
        var processingAnimationShown = false
        var halfFrameUiUpdated = false
        var lastHalfFrameUiAnimated = false
        var thumbnailPlaceholderCleared = false
        var motionPhotoTriggeredTimestamp: Long? = null

        var burstTriggered = false
        var singleTriggered = false
        var multiCamTriggered = false

        var lastSink: CaptureSink? = null
        var lastIsFrame1: Boolean? = null
        var lastHfMetadata: HalfFrameManager.Metadata? = null
        var lastTiming: StandardTimingTracker? = null

        override fun canTriggerCapture(): Boolean = canTrigger

        override fun showShutterVisuals() {
            shutterVisualsShown = true
        }

        override fun captureViewFinderSnapshot(): Bitmap? = null

        override fun showProcessingAnimation() {
            processingAnimationShown = true
        }

        override fun updateHalfFrameUi(animate: Boolean) {
            halfFrameUiUpdated = true
            lastHalfFrameUiAnimated = animate
        }

        override fun clearThumbnailPlaceholder() {
            thumbnailPlaceholderCleared = true
        }

        override fun triggerMotionPhotoSnapshot(timestamp: Long): CompletableDeferred<Pair<String?, Long>>? {
            motionPhotoTriggeredTimestamp = timestamp
            return CompletableDeferred(Pair("/tmp/motion.mp4", 1000L))
        }

        override fun triggerHdrPlusBurst(
            sink: CaptureSink,
            isFrame1: Boolean,
            hfMetadata: HalfFrameManager.Metadata?,
            timing: StandardTimingTracker
        ) {
            burstTriggered = true
            lastSink = sink
            lastIsFrame1 = isFrame1
            lastHfMetadata = hfMetadata
            lastTiming = timing
        }

        override fun triggerSinglePicture(
            sink: CaptureSink,
            isFrame1: Boolean,
            hfMetadata: HalfFrameManager.Metadata?,
            timing: StandardTimingTracker
        ) {
            singleTriggered = true
            lastSink = sink
            lastIsFrame1 = isFrame1
            lastHfMetadata = hfMetadata
            lastTiming = timing
        }

        override fun triggerMultiCameraPicture(timing: StandardTimingTracker) {
            multiCamTriggered = true
            lastTiming = timing
        }
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs = context.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
    }

    @Test
    fun testModeCoordinatorFactory_CreatesCorrectInstances() {
        val host = MockModeExecutionContext(context, prefs)

        val normalCoord = ModeCoordinatorFactory.create(CaptureMode.NORMAL, host)
        assertTrue(normalCoord is NormalModeCoordinator)
        assertEquals(CaptureMode.NORMAL, normalCoord.mode)

        val hfSbsCoord = ModeCoordinatorFactory.create(CaptureMode.HALF_FRAME_SBS, host)
        assertTrue(hfSbsCoord is HalfFrameModeCoordinator)
        assertEquals(CaptureMode.HALF_FRAME_SBS, hfSbsCoord.mode)

        val hfTbCoord = ModeCoordinatorFactory.create(CaptureMode.HALF_FRAME_TB, host)
        assertTrue(hfTbCoord is HalfFrameModeCoordinator)
        assertEquals(CaptureMode.HALF_FRAME_TB, hfTbCoord.mode)

        val multiCoord = ModeCoordinatorFactory.create(CaptureMode.MULTI_CAMERA, host)
        assertTrue(multiCoord is MultiCameraModeCoordinator)
        assertEquals(CaptureMode.MULTI_CAMERA, multiCoord.mode)
    }

    @Test
    fun testNormalModeCoordinator_TriggersHdrPlusBurstWithDirectMediaStoreSink() {
        val host = MockModeExecutionContext(context, prefs, isHdrPlusEnabled = true, isRawSupported = true)
        val coordinator = NormalModeCoordinator(host)
        val timing = StandardTimingTracker(shutterClick = 1000L)

        coordinator.onShutterTriggered(timing)

        assertTrue(host.burstTriggered)
        assertFalse(host.singleTriggered)
        assertTrue(host.shutterVisualsShown)
        assertTrue(host.lastSink is DirectMediaStoreSink)
        assertEquals(CaptureTimingMode.HDR_BURST, host.lastTiming?.captureMode)
        assertFalse(host.lastIsFrame1 == true)
        assertNull(host.lastHfMetadata)
    }

    @Test
    fun testNormalModeCoordinator_TriggersSinglePictureWhenHdrPlusDisabled() {
        val host = MockModeExecutionContext(context, prefs, isHdrPlusEnabled = false, isRawSupported = true)
        val coordinator = NormalModeCoordinator(host)
        val timing = StandardTimingTracker(shutterClick = 2000L)

        coordinator.onShutterTriggered(timing)

        assertFalse(host.burstTriggered)
        assertTrue(host.singleTriggered)
        assertTrue(host.shutterVisualsShown)
        assertTrue(host.lastSink is DirectMediaStoreSink)
        assertEquals(CaptureTimingMode.SINGLE_RAW, host.lastTiming?.captureMode)
    }

    @Test
    fun testNormalModeCoordinator_MotionPhotoTriggeredWhenEnabled() {
        prefs.edit().putBoolean(SettingsFragment.KEY_MOTION_PHOTO, true).apply()
        val host = MockModeExecutionContext(context, prefs)
        val coordinator = NormalModeCoordinator(host)
        val timing = StandardTimingTracker(shutterClick = 3000L)

        coordinator.onShutterTriggered(timing)

        assertEquals(3000L, host.motionPhotoTriggeredTimestamp)
    }

    @Test
    fun testHalfFrameModeCoordinator_TwoFrameStateMachine() {
        val sessionStore = HalfFrameSessionStore(context)
        sessionStore.clearCurrentSession(deleteTempFile = true)
        sessionStore.markStep(0)

        val host = MockModeExecutionContext(context, prefs, isHdrPlusEnabled = true, isRawSupported = true)
        val coordinator = HalfFrameModeCoordinator(CaptureMode.HALF_FRAME_SBS, host)
        coordinator.onActivated()

        // 1. First Frame trigger
        val now = System.currentTimeMillis()
        val timing1 = StandardTimingTracker(shutterClick = now)
        coordinator.onShutterTriggered(timing1)

        assertTrue(host.burstTriggered)
        assertTrue(host.lastIsFrame1 == true)
        assertTrue(host.lastSink is HalfFrameCacheSink)
        assertEquals(CaptureTimingMode.HALF_FRAME, host.lastTiming?.captureMode)
        assertTrue(host.halfFrameUiUpdated)
        assertTrue(host.lastHalfFrameUiAnimated)
        assertTrue(host.processingAnimationShown)
        assertEquals(1, sessionStore.readSession().step)
        assertNotNull(sessionStore.readSession().baseName)

        // Simulate Frame 1 stage result writing temp file path
        sessionStore.setTempPath("/tmp/frame1.jpg")

        // 2. Second Frame trigger
        host.burstTriggered = false
        host.halfFrameUiUpdated = false
        host.processingAnimationShown = false
        val timing2 = StandardTimingTracker(shutterClick = now + 2000L)
        coordinator.onShutterTriggered(timing2)

        assertTrue(host.burstTriggered)
        assertFalse(host.lastIsFrame1 == true)
        assertTrue(host.lastSink is HalfFrameCacheSink)
        assertTrue(host.thumbnailPlaceholderCleared)
        assertEquals(0, sessionStore.readSession().step)
        assertNotNull(host.lastHfMetadata)
        assertEquals(sessionStore.readSession().baseName, host.lastHfMetadata?.frame1BaseName)
        assertEquals("/tmp/frame1.jpg", host.lastHfMetadata?.frame1TempPath)
    }

    @Test
    fun testHalfFrameModeCoordinator_DotRotationRespectsLayout() {
        val host = MockModeExecutionContext(context, prefs)

        // SBS layout (Portrait) -> 0f
        prefs.edit().putString(SettingsFragment.KEY_HALF_FRAME_LAYOUT, SettingsFragment.HALF_FRAME_LAYOUT_SBS).apply()
        val sbsCoordinator = HalfFrameModeCoordinator(CaptureMode.HALF_FRAME_SBS, host)
        assertEquals(0f, sbsCoordinator.getShutterDotRotation(90))

        // TB layout (Landscape) -> 90f
        prefs.edit().putString(SettingsFragment.KEY_HALF_FRAME_LAYOUT, SettingsFragment.HALF_FRAME_LAYOUT_TB).apply()
        val tbCoordinator = HalfFrameModeCoordinator(CaptureMode.HALF_FRAME_TB, host)
        assertEquals(90f, tbCoordinator.getShutterDotRotation(90))
    }

    @Test
    fun testMultiCameraModeCoordinator_TriggersMultiCameraCapture() {
        val host = MockModeExecutionContext(context, prefs)
        val coordinator = MultiCameraModeCoordinator(host)
        val timing = StandardTimingTracker(shutterClick = 5000L)

        coordinator.onShutterTriggered(timing)

        assertTrue(host.multiCamTriggered)
        assertEquals(CaptureTimingMode.MULTI_CAMERA, host.lastTiming?.captureMode)
    }

    @Test
    fun testCoordinators_RespectCanTriggerCaptureGuard() {
        val host = MockModeExecutionContext(context, prefs).apply { canTrigger = false }
        val normal = NormalModeCoordinator(host)
        normal.onShutterTriggered(StandardTimingTracker(shutterClick = 100L))
        assertFalse(host.burstTriggered)
        assertFalse(host.singleTriggered)

        val half = HalfFrameModeCoordinator(CaptureMode.HALF_FRAME_SBS, host)
        half.onShutterTriggered(StandardTimingTracker(shutterClick = 200L))
        assertFalse(host.burstTriggered)

        val multi = MultiCameraModeCoordinator(host)
        multi.onShutterTriggered(StandardTimingTracker(shutterClick = 300L))
        assertFalse(host.multiCamTriggered)
    }

    @Test
    fun testCaptureMode_SupportsMotionPhoto() {
        assertTrue(CaptureMode.NORMAL.supportsMotionPhoto)
        assertFalse(CaptureMode.HALF_FRAME_SBS.supportsMotionPhoto)
        assertFalse(CaptureMode.HALF_FRAME_TB.supportsMotionPhoto)
        assertFalse(CaptureMode.MULTI_CAMERA.supportsMotionPhoto)
    }

    @Test
    fun testHalfFrameModeCoordinator_OnShutterLongPressed_ResetsStep1() {
        val sessionStore = HalfFrameSessionStore(context)
        sessionStore.clearProfile(HalfFrameSessionStore.PROFILE_HALF_SIDE)
        sessionStore.markStep(1, System.currentTimeMillis(), profile = HalfFrameSessionStore.PROFILE_HALF_SIDE)

        val host = MockModeExecutionContext(context, prefs)
        val coordinator = HalfFrameModeCoordinator(CaptureMode.HALF_FRAME_SBS, host)

        val consumed = coordinator.onShutterLongPressed()
        assertTrue(consumed)
        assertEquals(0, sessionStore.readSession(profile = HalfFrameSessionStore.PROFILE_HALF_SIDE).step)
        assertTrue(host.halfFrameUiUpdated)
        assertFalse(host.lastHalfFrameUiAnimated)

        // Calling when step is 0 should return false
        val consumedAgain = coordinator.onShutterLongPressed()
        assertFalse(consumedAgain)
    }

    @Test
    fun testHalfFrameModeCoordinator_EffectiveOrientationRespectsLayout() {
        val host = MockModeExecutionContext(context, prefs)

        // SBS layout -> 0
        prefs.edit().putString(SettingsFragment.KEY_HALF_FRAME_LAYOUT, SettingsFragment.HALF_FRAME_LAYOUT_SBS).apply()
        val sbsCoordinator = HalfFrameModeCoordinator(CaptureMode.HALF_FRAME_SBS, host)
        assertEquals(0, sbsCoordinator.getEffectiveOrientation(90))

        // TB layout -> 270
        prefs.edit().putString(SettingsFragment.KEY_HALF_FRAME_LAYOUT, SettingsFragment.HALF_FRAME_LAYOUT_TB).apply()
        val tbCoordinator = HalfFrameModeCoordinator(CaptureMode.HALF_FRAME_TB, host)
        assertEquals(270, tbCoordinator.getEffectiveOrientation(90))
    }

    @Test
    fun testNormalAndMultiCamera_DefaultLongPressAndOrientation() {
        val host = MockModeExecutionContext(context, prefs)
        val normal = NormalModeCoordinator(host)
        val multi = MultiCameraModeCoordinator(host)

        assertFalse(normal.onShutterLongPressed())
        assertEquals(180, normal.getEffectiveOrientation(180))

        assertFalse(multi.onShutterLongPressed())
        assertEquals(180, multi.getEffectiveOrientation(180))
    }
}

