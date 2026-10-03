package top.maary.darkbag.processor

import android.app.ActivityManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class HdrPlusRequestManagerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Reset pending tasks count to 0 and drain channel
        while (HdrPlusRequestManager.pendingTasksCount.value > 0) {
            HdrPlusRequestManager.onTaskFinished()
        }
        while (HdrPlusRequestManager.requestChannel.tryReceive().isSuccess) {
            // Drain channel
        }
        // Set healthy memory defaults in Robolectric
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo().apply {
            availMem = 2048L * 1024L * 1024L // 2GB available
            lowMemory = false
        }
        shadowOf(am).setMemoryInfo(memInfo)
    }

    @Test
    fun testCanAcceptNewTask_QueueLimit() {
        // Balanced limit = 3
        assertTrue(HdrPlusRequestManager.canAcceptNewTask(3, context))

        // When queue limit is 0, should reject
        assertFalse(HdrPlusRequestManager.canAcceptNewTask(0, context))
    }

    @Test
    fun testCanAcceptNewTask_LowMemoryThreshold() {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        
        // When lowMemory is true
        val memInfoLow = ActivityManager.MemoryInfo().apply {
            availMem = 1000L * 1024L * 1024L
            lowMemory = true
        }
        shadowOf(am).setMemoryInfo(memInfoLow)
        assertFalse(HdrPlusRequestManager.canAcceptNewTask(3, context))

        // When availMem < 600MB
        val memInfoCritical = ActivityManager.MemoryInfo().apply {
            availMem = 500L * 1024L * 1024L // 500MB
            lowMemory = false
        }
        shadowOf(am).setMemoryInfo(memInfoCritical)
        assertFalse(HdrPlusRequestManager.canAcceptNewTask(3, context))

        // When memory is adequate (> 600MB and not lowMemory)
        val memInfoNormal = ActivityManager.MemoryInfo().apply {
            availMem = 800L * 1024L * 1024L // 800MB
            lowMemory = false
        }
        shadowOf(am).setMemoryInfo(memInfoNormal)
        assertTrue(HdrPlusRequestManager.canAcceptNewTask(3, context))
    }

    @Test
    fun testTaskLifecycle_IncrementAndDecrement() {
        // onTaskFinished safely decrements with coerceAtLeast(0)
        HdrPlusRequestManager.onTaskFinished()
        assertEquals(0, HdrPlusRequestManager.pendingTasksCount.value)

        // onTaskStarted increments counter
        HdrPlusRequestManager.onTaskStarted()
        assertEquals(1, HdrPlusRequestManager.pendingTasksCount.value)

        HdrPlusRequestManager.onTaskStarted()
        assertEquals(2, HdrPlusRequestManager.pendingTasksCount.value)

        // onTaskFinished decrements counter
        HdrPlusRequestManager.onTaskFinished()
        assertEquals(1, HdrPlusRequestManager.pendingTasksCount.value)

        HdrPlusRequestManager.onTaskFinished()
        assertEquals(0, HdrPlusRequestManager.pendingTasksCount.value)

        // Ensure floor at 0
        HdrPlusRequestManager.onTaskFinished()
        assertEquals(0, HdrPlusRequestManager.pendingTasksCount.value)
    }

    private fun createDummyRequest(id: String = "test-req"): HdrPlusRequest {
        return HdrPlusRequest(
            requestId = id,
            numFrames = 1,
            width = 100,
            height = 100,
            orientation = 0,
            whiteLevel = 1023,
            blackLevelPattern = intArrayOf(64, 64, 64, 64),
            lensShadingMap = null,
            lensShadingRows = 0,
            lensShadingCols = 0,
            useSensorColorMatrix = false,
            whiteBalance = floatArrayOf(1f, 1f, 1f, 1f),
            ccm = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
            ccmAlt = null,
            exportMatrixAB = false,
            cfaPattern = 0,
            targetLogIndex = 0,
            lutPath = null,
            digitalGain = 1.0f,
            zoomFactor = 1.0f,
            mirror = false,
            metadata = top.maary.darkbag.models.CaptureMetadata(
                iso = 100,
                exposureTime = 10000000L,
                focalLength = 4.5f,
                fNumber = 1.8f
            ),
            isSingleFrame = true,
            saveJpg = true,
            saveRaw = false,
            baseName = "test",
            fullResJpgPath = "/tmp/test.jpg",
            linearDngPath = "/tmp/test.dng",
            zslTargetUriStr = null,
            jpgFolderUri = null,
            rawFolderUri = null,
            hfMetadata = null,
            editConfig = null,
            runAblationTest = false
        )
    }

    @Test
    fun testEnqueue_NotTracked_IncrementsCount() {
        assertEquals(0, HdrPlusRequestManager.pendingTasksCount.value)
        val req = createDummyRequest("req-1")
        HdrPlusRequestManager.enqueue(req, alreadyTracked = false)
        assertEquals(1, HdrPlusRequestManager.pendingTasksCount.value)

        val received = HdrPlusRequestManager.requestChannel.tryReceive().getOrNull()
        assertEquals("req-1", received?.requestId)
        HdrPlusRequestManager.onTaskFinished()
        assertEquals(0, HdrPlusRequestManager.pendingTasksCount.value)
    }

    @Test
    fun testEnqueue_AlreadyTracked_PreservesCount() {
        assertEquals(0, HdrPlusRequestManager.pendingTasksCount.value)
        // Task started upfront (e.g. burst capture done)
        HdrPlusRequestManager.onTaskStarted()
        assertEquals(1, HdrPlusRequestManager.pendingTasksCount.value)

        val req = createDummyRequest("req-2")
        HdrPlusRequestManager.enqueue(req, alreadyTracked = true)
        // Count should still be 1 (not 2)
        assertEquals(1, HdrPlusRequestManager.pendingTasksCount.value)

        val received = HdrPlusRequestManager.requestChannel.tryReceive().getOrNull()
        assertEquals("req-2", received?.requestId)
        HdrPlusRequestManager.onTaskFinished()
        assertEquals(0, HdrPlusRequestManager.pendingTasksCount.value)
    }

    @Test
    fun testEnqueue_FailureRollback_NotTracked() {
        assertEquals(0, HdrPlusRequestManager.pendingTasksCount.value)
        // Fill channel (capacity is MAX_IN_FLIGHT_REQUESTS)
        for (i in 1..HdrPlusRequestManager.MAX_IN_FLIGHT_REQUESTS) {
            HdrPlusRequestManager.enqueue(createDummyRequest("req-$i"))
        }
        assertEquals(HdrPlusRequestManager.MAX_IN_FLIGHT_REQUESTS, HdrPlusRequestManager.pendingTasksCount.value)

        // Overflow request should fail trySend and roll back
        try {
            HdrPlusRequestManager.enqueue(createDummyRequest("req-overflow"), alreadyTracked = false)
            org.junit.Assert.fail("Expected IllegalStateException")
        } catch (e: IllegalStateException) {
            // Count rolled back to MAX_IN_FLIGHT_REQUESTS
            assertEquals(HdrPlusRequestManager.MAX_IN_FLIGHT_REQUESTS, HdrPlusRequestManager.pendingTasksCount.value)
        }

        // Clean up channel
        while (HdrPlusRequestManager.requestChannel.tryReceive().isSuccess) {
            HdrPlusRequestManager.onTaskFinished()
        }
        assertEquals(0, HdrPlusRequestManager.pendingTasksCount.value)
    }

    @Test
    fun testEnqueue_FailureRollback_AlreadyTracked_DoesNotDoubleDecrement() {
        assertEquals(0, HdrPlusRequestManager.pendingTasksCount.value)
        // Fill channel (capacity is MAX_IN_FLIGHT_REQUESTS)
        for (i in 1..HdrPlusRequestManager.MAX_IN_FLIGHT_REQUESTS) {
            HdrPlusRequestManager.enqueue(createDummyRequest("req-$i"))
        }
        assertEquals(HdrPlusRequestManager.MAX_IN_FLIGHT_REQUESTS, HdrPlusRequestManager.pendingTasksCount.value)

        // Overflow request was tracked upfront
        HdrPlusRequestManager.onTaskStarted()
        assertEquals(HdrPlusRequestManager.MAX_IN_FLIGHT_REQUESTS + 1, HdrPlusRequestManager.pendingTasksCount.value)

        try {
            HdrPlusRequestManager.enqueue(createDummyRequest("req-overflow"), alreadyTracked = true)
            org.junit.Assert.fail("Expected IllegalStateException")
        } catch (e: IllegalStateException) {
            // enqueue itself did NOT decrement count because alreadyTracked == true!
            assertEquals(HdrPlusRequestManager.MAX_IN_FLIGHT_REQUESTS + 1, HdrPlusRequestManager.pendingTasksCount.value)
            // Caller's catch block does the decrement:
            HdrPlusRequestManager.onTaskFinished()
            assertEquals(HdrPlusRequestManager.MAX_IN_FLIGHT_REQUESTS, HdrPlusRequestManager.pendingTasksCount.value)
        }

        // Clean up channel
        while (HdrPlusRequestManager.requestChannel.tryReceive().isSuccess) {
            HdrPlusRequestManager.onTaskFinished()
        }
        assertEquals(0, HdrPlusRequestManager.pendingTasksCount.value)
    }
}
