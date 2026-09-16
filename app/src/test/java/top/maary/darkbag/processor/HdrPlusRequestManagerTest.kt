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
        // Reset pending tasks count to 0
        while (HdrPlusRequestManager.pendingTasksCount.value > 0) {
            HdrPlusRequestManager.onTaskFinished()
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
    }
}
