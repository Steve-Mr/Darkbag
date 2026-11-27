package top.maary.darkbag.viewer.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionFilmstripScrubberViewTest {

    @Test
    fun testComputeKeyframeFraction() {
        assertEquals(0.5f, MotionFilmstripScrubberView.computeKeyframeFraction(1000L, 0L), 0.0001f)
        assertEquals(0.25f, MotionFilmstripScrubberView.computeKeyframeFraction(250L, 1000L), 0.0001f)
        assertEquals(1.0f, MotionFilmstripScrubberView.computeKeyframeFraction(1500L, 1000L), 0.0001f)
    }

    @Test
    fun testCrossedKeyframeDetent() {
        assertTrue(MotionFilmstripScrubberView.crossedKeyframeDetent(0.45f, 0.52f, 0.50f))
        assertTrue(MotionFilmstripScrubberView.crossedKeyframeDetent(0.55f, 0.48f, 0.50f))
        assertTrue(MotionFilmstripScrubberView.crossedKeyframeDetent(0.46f, 0.50f, 0.50f))
        assertFalse(MotionFilmstripScrubberView.crossedKeyframeDetent(0.10f, 0.20f, 0.50f))
        assertFalse(MotionFilmstripScrubberView.crossedKeyframeDetent(0.50f, 0.50f, 0.50f))
    }
}
