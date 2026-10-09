package top.maary.darkbag.camera.session

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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class Camera2SessionControllerTest {

    private lateinit var controller: Camera2SessionController

    @Before
    fun setUp() {
        controller = Camera2SessionController("TestCameraThread")
    }

    @Test
    fun testInitialState() {
        assertEquals(CameraSessionState.Closed, controller.sessionState.value)
        assertNull(controller.cameraDevice)
        assertNull(controller.captureSession)
        assertNull(controller.backgroundHandler)
        assertFalse(controller.isSessionActive)
    }

    @Test
    fun testBackgroundThreadLifecycle() {
        controller.startBackgroundThread()
        assertNotNull(controller.backgroundHandler)

        controller.stopBackgroundThread()
        assertNull(controller.backgroundHandler)
    }

    @Test
    fun testCloseCamera_WhenAlreadyClosed() {
        controller.closeCamera()
        assertEquals(CameraSessionState.Closed, controller.sessionState.value)
        assertNull(controller.cameraDevice)
        assertNull(controller.captureSession)
    }

    @Test
    fun testRelease_CleansUpAllResources() {
        controller.startBackgroundThread()
        assertNotNull(controller.backgroundHandler)

        controller.release()
        assertEquals(CameraSessionState.Closed, controller.sessionState.value)
        assertNull(controller.backgroundHandler)
    }

    @Test
    fun testLockPermits_NeverExceedOneOnSubsequentClose() {
        assertEquals(1, controller.availableLockPermits)
        controller.closeCamera()
        assertEquals(1, controller.availableLockPermits)
        controller.closeCamera()
        assertEquals(1, controller.availableLockPermits)
    }

    @Test
    fun testConcurrentCloseCamera_MaintainsSinglePermit() {
        assertEquals(1, controller.availableLockPermits)
        val threads = (1..10).map {
            Thread {
                controller.closeCamera()
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(1, controller.availableLockPermits)
    }
}
