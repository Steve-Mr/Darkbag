package top.maary.darkbag.camera.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 有界元数据缓存的淘汰语义（S0-3）。
 *
 * 值类型用 String 代替 [android.hardware.camera2.TotalCaptureResult]：后者在 host 单测中无法构造，
 * 而淘汰/查找语义只与 key 相关。
 */
class BoundedCaptureResultCacheTest {

    @Test
    fun testDefaultMaxEntriesIs300() {
        assertEquals(300, BoundedCaptureResultCache.DEFAULT_MAX_ENTRIES)

        val cache = BoundedCaptureResultCache<String>()
        for (i in 0 until 1000) {
            cache[i.toLong()] = "frame-$i"
        }

        assertEquals(300, cache.size)
    }

    @Test
    fun testOldestEntriesEvictedAndNewest300Retained() {
        val cache = BoundedCaptureResultCache<String>()
        for (i in 0 until 1000) {
            cache[i.toLong()] = "frame-$i"
        }

        // 最新 300 条（key 700..999）保留
        assertTrue(cache.containsKey(700L))
        assertTrue(cache.containsKey(999L))
        // 最旧条目（key 0..699）全部被淘汰
        assertFalse(cache.containsKey(699L))
        assertFalse(cache.containsKey(0L))
    }

    @Test
    fun testExactKeyLookupSurvivesEviction() {
        val cache = BoundedCaptureResultCache<String>()
        for (i in 0 until 1000) {
            cache[i.toLong()] = "frame-$i"
        }

        assertEquals("frame-700", cache[700L])
        assertEquals("frame-999", cache[999L])
        assertNull(cache[699L])
    }

    @Test
    fun testInsertionOrderDrivesNewestValueLookup() {
        val cache = BoundedCaptureResultCache<String>()
        for (i in 0 until 1000) {
            cache[i.toLong()] = "frame-$i"
        }

        // CameraVideoController 依赖 values.lastOrNull() 拿到最新结果
        assertEquals("frame-999", cache.values.lastOrNull())
        assertEquals(999L, cache.entries.last().key)
    }

    @Test
    fun testCustomMaxEntriesEvictsExactlyBeyondBoundary() {
        val cache = BoundedCaptureResultCache<String>(maxEntries = 3)
        for (i in 1..4) {
            cache[i.toLong()] = "v$i"
        }

        assertEquals(3, cache.size)
        assertFalse(cache.containsKey(1L))
        assertEquals(listOf(2L, 3L, 4L), cache.keys.toList())
    }

    @Test
    fun testClearRemovesAllEntries() {
        val cache = BoundedCaptureResultCache<String>()
        for (i in 0 until 10) {
            cache[i.toLong()] = "frame-$i"
        }

        assertEquals(10, cache.size)
        cache.clear()
        assertTrue(cache.isEmpty())
        assertNull(cache[5L])
    }
}
