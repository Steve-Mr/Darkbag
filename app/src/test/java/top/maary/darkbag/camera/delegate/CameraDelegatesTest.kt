package top.maary.darkbag.camera.delegate

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.util.Range
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ApplicationProvider
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
import top.maary.darkbag.modes.CaptureMode
import top.maary.darkbag.modes.CaptureModeCoordinator
import top.maary.darkbag.processor.lut.LutProcessor
import top.maary.darkbag.ui.ProDialWheelView
import top.maary.darkbag.viewmodel.CameraUiTier
import top.maary.darkbag.viewmodel.CameraViewModel

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class CameraDelegatesTest {

    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences

    class MockGestureHost : CameraGestureHost {
        override var isFloatingOverlayOpen: Boolean = false
        var overlayDismissed = false
        var lastTapX: Float? = null
        var lastTapY: Float? = null
        var focusUnlocked = false

        override fun dismissFloatingOverlays() {
            overlayDismissed = true
        }

        override fun triggerTapToFocus(x: Float, y: Float) {
            lastTapX = x
            lastTapY = y
        }

        override fun unlockFocusAndMetering() {
            focusUnlocked = true
        }
    }

    class MockTierHost(
        override val context: Context,
        val preferences: SharedPreferences
    ) : CameraTierHost {
        override val binding = null
        override val rootViewGroup: ViewGroup? = null
        override val activeCoordinator: CaptureModeCoordinator? = null
        override val cameraViewModel = CameraViewModel()
        override val lutProcessor: LutProcessor? = null
        override val isFrontPipActive = false
        override val hasMultipleAvailableLenses = true
        override val hasMultiCameraManager = false
        override val minFocusDistance = 10.0f
        override val isoRange = Range(50, 6400)
        override val exposureTimeRange = Range(125_000L, 30_000_000_000L)
        override val evRange = Range(-6, 6)

        override var currentFocusDistance = 0f
        override var liveFocusDistance = 0.5f
        override var isManualFocus = false

        override var currentIso = 100
        override var liveIso = 200
        override var isManualIso = false

        override var currentExposureTime = 10_000_000L
        override var liveExposureTime = 20_000_000L
        override var isManualShutter = false

        override var currentEvIndex = 0
        override var isMultiCameraManualLinked = true
        override var hasFocusMeteringLock = false

        var cameraControlsApplied = false
        var focusMeteringCleared = false
        var exposureMeteringCleared = false

        override fun applyCameraControls() {
            cameraControlsApplied = true
        }

        override fun clearFocusMetering() {
            focusMeteringCleared = true
        }

        override fun clearExposureMetering() {
            exposureMeteringCleared = true
        }
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs = context.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
    }

    @Test
    fun testCameraGestureDelegate_TapAndUnlockRouting() {
        val host = MockGestureHost()
        val delegate = CameraGestureDelegate(context, host)

        // When overlay is open, tap dismisses overlays
        host.isFloatingOverlayOpen = true
        val stageView = View(context)
        delegate.setupTapToFocus(stageView)

        assertFalse(host.overlayDismissed)
        assertNull(host.lastTapX)
    }

    @Test
    fun testCameraTierController_Formatters() {
        val host = MockTierHost(context, prefs)
        val controller = CameraTierController(host)

        // Focus distance formatting
        assertEquals("∞", controller.formatFocusDist(0f))
        assertEquals("Macro", controller.formatFocusDist(9.8f))
        assertEquals("50cm", controller.formatFocusDist(2.0f))
        assertEquals("2.0m", controller.formatFocusDist(0.5f))

        // Shutter time formatting
        assertEquals("1/1000s", controller.formatShutterTime(1_000_000L))
        assertEquals("1/30s", controller.formatShutterTime(33_333_333L))
        assertEquals("1.0s", controller.formatShutterTime(1_000_000_000L))
        assertEquals("2.0s", controller.formatShutterTime(2_000_000_000L))

        // EV index formatting
        assertEquals("EV 0.0", controller.formatEvVal(0))
        assertEquals("EV +2", controller.formatEvVal(2))
        assertEquals("EV -1", controller.formatEvVal(-1))
    }

    @Test
    fun testCameraTierController_DialItemBuilders() {
        val host = MockTierHost(context, prefs)
        val controller = CameraTierController(host)

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
        val host = MockTierHost(context, prefs)
        val controller = CameraTierController(host)

        // Simulate Focus tab active
        val tabField = CameraTierController::class.java.getDeclaredField("activeManualTab")
        tabField.isAccessible = true
        tabField.set(controller, "Focus")

        controller.handleDialSelection(ProDialWheelView.DialItem("1", "1.0m", 1.0, true))
        assertEquals(1.0f, host.currentFocusDistance, 0.01f)
        assertTrue(host.isManualFocus)
        assertTrue(host.cameraControlsApplied)

        // Reset manual focus
        controller.resetCurrentManualParameter()
        assertFalse(host.isManualFocus)
        assertTrue(host.focusMeteringCleared)
    }
}
