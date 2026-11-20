package top.maary.darkbag.ui.viewer.playback

import android.graphics.Matrix
import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class VideoSurfaceTransformTest {

    private companion object {
        const val EPSILON = 0.001f
    }

    private fun Matrix.values() = FloatArray(9).also { getValues(it) }

    @Test
    fun `maps the full video frame onto the unclipped content rect`() {
        val contentRect = RectF(-120f, -50f, 2280f, 1550f)

        val matrix = VideoSurfaceTransform.compute(contentRect, viewWidth = 1080f, viewHeight = 1920f)
        val values = matrix.values()

        assertEquals(2400f / 1080f, values[Matrix.MSCALE_X], EPSILON)
        assertEquals(1600f / 1920f, values[Matrix.MSCALE_Y], EPSILON)
        assertEquals(-120f, values[Matrix.MTRANS_X], EPSILON)
        assertEquals(-50f, values[Matrix.MTRANS_Y], EPSILON)
    }

    @Test
    fun `maps viewport corners to content rect corners even when content exceeds viewport`() {
        val contentRect = RectF(-135f, 0f, 1215f, 1012.5f)

        val matrix = VideoSurfaceTransform.compute(contentRect, viewWidth = 1080f, viewHeight = 1920f)

        val topLeft = floatArrayOf(0f, 0f)
        matrix.mapPoints(topLeft)
        assertEquals(contentRect.left, topLeft[0], EPSILON)
        assertEquals(contentRect.top, topLeft[1], EPSILON)

        val bottomRight = floatArrayOf(1080f, 1920f)
        matrix.mapPoints(bottomRight)
        assertEquals(contentRect.right, bottomRight[0], EPSILON)
        assertEquals(contentRect.bottom, bottomRight[1], EPSILON)
    }

    @Test
    fun `returns identity for an invalid viewport`() {
        val values = VideoSurfaceTransform
            .compute(RectF(0f, 0f, 100f, 100f), viewWidth = 0f, viewHeight = 0f)
            .values()

        val identity = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        identity.forEachIndexed { index, expected -> assertEquals(expected, values[index], EPSILON) }
    }
}
