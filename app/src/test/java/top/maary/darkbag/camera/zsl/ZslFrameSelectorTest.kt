package top.maary.darkbag.camera.zsl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZslFrameSelectorTest {

    private val wb = floatArrayOf(2.0f, 1.0f, 1.0f, 1.5f)

    private fun sample(
        ts: Long,
        iso: Int = 400,
        exposure: Long = 16_000_000L,
        wbValue: FloatArray? = wb,
        crop: String = "Rect(0,0,4000,3000)",
        af: Boolean = true,
        hasResult: Boolean = true
    ) = ZslFrameSample(ts, hasResult, iso, exposure, wbValue, crop, af)

    @Test
    fun nearestUsesClosestValidFrameWithinAge() {
        val samples = listOf(sample(100), sample(200), sample(300))
        assertEquals(1, ZslFrameSelector.nearestIndex(samples, 190, 100))
        assertNull(ZslFrameSelector.nearestIndex(samples, 500, 100))
        assertNull(ZslFrameSelector.nearestIndex(listOf(sample(200, hasResult = false)), 200, 100))
    }

    @Test
    fun stableRunReturnsBaseFirst() {
        val samples = (0 until 5).map { sample(1_000L + it * 33L) }
        val indices = ZslFrameSelector.stableRunIndices(samples, 1_132L, 5, 300)
        assertEquals(listOf(4, 3, 2, 1, 0), indices)
    }

    @Test
    fun exposureJumpShrinksToStableTail() {
        val samples = listOf(
            sample(100, iso = 400, exposure = 16_000_000L),
            sample(133, iso = 400, exposure = 16_000_000L),
            sample(166, iso = 800, exposure = 33_000_000L),
            sample(199, iso = 800, exposure = 33_000_000L),
            sample(232, iso = 800, exposure = 33_000_000L)
        )
        val indices = ZslFrameSelector.stableRunIndices(samples, 232, 5, 300)
        assertEquals(listOf(4, 3, 2), indices)
    }

    @Test
    fun wbMismatchRejectsRun() {
        val samples = (0 until 3).map {
            sample(1_000L + it * 33L, wbValue = if (it == 2) floatArrayOf(1.0f, 1.0f, 1.0f, 1.0f) else wb)
        }
        assertTrue(ZslFrameSelector.stableRunIndices(samples, 1_066, 3, 300).isEmpty())
    }

    @Test
    fun cropMismatchRejectsRun() {
        val samples = listOf(
            sample(1_000),
            sample(1_033),
            sample(1_066, crop = "Rect(0,0,2000,1500)")
        )
        assertTrue(ZslFrameSelector.stableRunIndices(samples, 1_066, 3, 300).isEmpty())
    }

    @Test
    fun missingWbOrIsoRejectsStability() {
        val samples = listOf(
            sample(1_000),
            sample(1_033, wbValue = null),
            sample(1_066)
        )
        assertTrue(ZslFrameSelector.stableRunIndices(samples, 1_066, 3, 300).isEmpty())
    }

    @Test
    fun nearestRejectsUnfocusedFrame() {
        val samples = listOf(sample(100, af = true), sample(200, af = false), sample(300, af = true))
        assertEquals(0, ZslFrameSelector.nearestIndex(samples, 190, 300))
    }

    @Test
    fun stableRunRequiresEveryFrameFocused() {
        val samples = (0 until 3).map { sample(1_000L + it * 33L, af = it != 1) }
        assertTrue(ZslFrameSelector.stableRunIndices(samples, 1_066, 3, 300).isEmpty())
    }

    @Test
    fun afOutlierOlderThanTimeoutRejectsRun() {
        val samples = listOf(
            sample(0, af = false),
            sample(3_033_000_000L, af = true),
            sample(3_066_000_000L, af = true)
        )
        assertTrue(
            ZslFrameSelector.stableRunIndices(samples, 3_066_000_000L, 3, 4_000_000_000L).isEmpty()
        )
    }
}
