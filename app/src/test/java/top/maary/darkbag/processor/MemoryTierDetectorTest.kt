package top.maary.darkbag.processor

import android.app.ActivityManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class MemoryTierDetectorTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ColorProcessor.resetMemoryTierDetectionForTesting()
    }

    @Test
    fun testDetectMemoryTier_4GbDeviceIsLowTier() {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        // 4GB RAM typical Pixel 3 ~ 3.7GB reported totalMem
        val memInfo = ActivityManager.MemoryInfo().apply {
            totalMem = (3.7 * 1024 * 1024 * 1024).toLong()
            availMem = (1.5 * 1024 * 1024 * 1024).toLong()
            lowMemory = false
        }
        shadowOf(am).setMemoryInfo(memInfo)

        val isLow = ColorProcessor.detectAndApplyMemoryTier(context)
        assertTrue("4GB device must be classified as low memory tier", isLow)
    }

    @Test
    fun testDetectMemoryTier_6GbDeviceIsLowTier() {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        // 6GB RAM device ~ 5.7GB reported totalMem
        val memInfo = ActivityManager.MemoryInfo().apply {
            totalMem = (5.7 * 1024 * 1024 * 1024).toLong()
            availMem = (2.5 * 1024 * 1024 * 1024).toLong()
            lowMemory = false
        }
        shadowOf(am).setMemoryInfo(memInfo)

        val isLow = ColorProcessor.detectAndApplyMemoryTier(context)
        assertTrue("6GB device must be classified as low memory tier", isLow)
    }

    @Test
    fun testDetectMemoryTier_12GbFlagshipWithHighPerfClassIsNotLowTier() {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        // 12GB RAM flagship ~ 11.2GB reported totalMem
        val memInfo = ActivityManager.MemoryInfo().apply {
            totalMem = (11.2 * 1024 * 1024 * 1024).toLong()
            availMem = (6.0 * 1024 * 1024 * 1024).toLong()
            lowMemory = false
        }
        shadowOf(am).setMemoryInfo(memInfo)

        // Robolectric SDK 33 config sets Build.VERSION.SDK_INT = 33
        // If media performance class is set or default on 12GB device, test detection
        val isLow = ColorProcessor.detectAndApplyMemoryTier(context)
        // With totalMem > 8GB on SDK 33, it will not be low tier if perfClass >= S (31)
        // Or if media perf class default is 0 on Robolectric, totalMem > 8GB condition
        // evaluates mediaPerfClass < S && totalMem <= 8GB which is false.
        assertFalse("12GB flagship device must not be low memory tier", isLow)
    }

    @Test
    fun testClearSabreTexturePoolAsyncDoesNotThrow() {
        // Verify invoking clearSabreTexturePoolAsync completes gracefully without crashing host JVM
        ColorProcessor.clearSabreTexturePoolAsync()
    }
}
