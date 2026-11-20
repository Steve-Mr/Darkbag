package top.maary.darkbag.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.maary.darkbag.R
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ZoomableImageViewTest {

    private companion object {
        const val EPSILON = 0.01f
    }

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.setTheme(R.style.AppTheme)
    }

    @Test
    fun `content rect keeps image aspect ratio when only width is clipped`() {
        val view = createView(width = 1080, height = 1920)
        view.setImageDrawable(FixedSizeDrawable(4000, 3000))

        val zoom = 1.25f
        val totalScale = (1080f / 4000f) * zoom
        val matrix = Matrix().apply {
            setScale(totalScale, totalScale)
            postTranslate(-135f, 0f)
        }
        view.restoreZoomState(matrix, zoom)

        val geometry = requireNotNull(view.currentDisplayGeometry())
        val content = geometry.contentRect
        val visible = geometry.visibleRect

        assertEquals(4000f * totalScale, content.width(), EPSILON)
        assertEquals(3000f * totalScale, content.height(), EPSILON)
        assertEquals(4f / 3f, content.width() / content.height(), EPSILON)

        // The viewport-clipped rect only keeps the visible slice, so its aspect ratio drifts.
        assertEquals(1080f, visible.width(), EPSILON)
        assertEquals(content.height(), visible.height(), EPSILON)
        assertTrue(abs(visible.width() / visible.height() - 4f / 3f) > 0.1f)
    }

    @Test
    fun `outline interpolates toward full screen while zooming`() {
        val view = createView(width = 1080, height = 1920)
        view.setVisualParams(margin = 0f, radius = 24f)
        view.setImageDrawable(FixedSizeDrawable(4000, 3000))

        val zoom = 1.05f
        val totalScale = (1080f / 4000f) * zoom
        view.restoreZoomState(Matrix().apply { setScale(totalScale, totalScale) }, zoom)

        val geometry = requireNotNull(view.currentDisplayGeometry())

        // t = (1.05 - 1.0) / 0.1 = 0.5 -> half of the card corner radius and inset remain.
        assertEquals(12f, geometry.outlineRadius, EPSILON)
        assertEquals(0f, geometry.outlineRect.left, EPSILON)
        assertEquals(277f, geometry.outlineRect.top, EPSILON)
        assertEquals(1080f, geometry.outlineRect.right, EPSILON)
        assertEquals(1643f, geometry.outlineRect.bottom, EPSILON)
    }

    @Test
    fun `content rect keeps image aspect and outline becomes full screen when both axes are clipped`() {
        val view = createView(width = 1080, height = 1920)
        view.setVisualParams(margin = 0f, radius = 24f)
        view.setImageDrawable(FixedSizeDrawable(4000, 3000))

        val zoom = 2.5f
        val totalScale = (1080f / 4000f) * zoom
        view.restoreZoomState(
            Matrix().apply {
                setScale(totalScale, totalScale)
                postTranslate(-800f, -50f)
            },
            zoom
        )

        val geometry = requireNotNull(view.currentDisplayGeometry())
        val content = geometry.contentRect
        val visible = geometry.visibleRect

        assertEquals(4f / 3f, content.width() / content.height(), EPSILON)
        assertTrue(content.width() > 1080f)
        assertTrue(content.height() > 1920f)
        assertEquals(0f, visible.left, EPSILON)
        assertEquals(0f, visible.top, EPSILON)
        assertEquals(1080f, visible.right, EPSILON)
        assertEquals(1920f, visible.bottom, EPSILON)

        assertEquals(0f, geometry.outlineRadius, EPSILON)
        assertEquals(0f, geometry.outlineRect.left, EPSILON)
        assertEquals(0f, geometry.outlineRect.top, EPSILON)
        assertEquals(1080f, geometry.outlineRect.right, EPSILON)
        assertEquals(1920f, geometry.outlineRect.bottom, EPSILON)
    }

    private fun createView(width: Int, height: Int): ZoomableImageView {
        val view = ZoomableImageView(context)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, width, height)
        return view
    }

    @Suppress("OVERRIDE_DEPRECATION")
    private class FixedSizeDrawable(
        private val width: Int,
        private val height: Int
    ) : Drawable() {
        override fun draw(canvas: Canvas) = Unit
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        override fun getOpacity(): Int = PixelFormat.OPAQUE
        override fun getIntrinsicWidth(): Int = width
        override fun getIntrinsicHeight(): Int = height
    }
}
