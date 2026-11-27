package top.maary.darkbag.ui

import android.content.Context
import android.graphics.RectF
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class HalfFrameDimOverlayViewTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun computeDimmedRect_sbsSelectsOppositeHalfAndClipsToVisibleAndOutline() {
        val geometry = ImageDisplayGeometry(
            contentRect = RectF(100f, 50f, 500f, 350f),
            visibleRect = RectF(100f, 50f, 500f, 350f),
            outlineRect = RectF(100f, 50f, 500f, 350f),
            outlineRadius = 24f
        )

        val dimRight = HalfFrameDimOverlayView.computeDimmedRect(
            geometry = geometry,
            isTopBottom = false,
            selectedHalfFrameIndex = 0
        )
        assertEquals(RectF(300f, 50f, 500f, 350f), dimRight)

        val dimLeft = HalfFrameDimOverlayView.computeDimmedRect(
            geometry = geometry,
            isTopBottom = false,
            selectedHalfFrameIndex = 1
        )
        assertEquals(RectF(100f, 50f, 300f, 350f), dimLeft)
    }

    @Test
    fun computeDimmedRect_tbSelectsOppositeHalfAndClipsZoomedContent() {
        val geometry = ImageDisplayGeometry(
            contentRect = RectF(-200f, -100f, 800f, 900f),
            visibleRect = RectF(0f, 0f, 600f, 800f),
            outlineRect = RectF(0f, 0f, 600f, 800f),
            outlineRadius = 0f
        )

        // midY of contentRect is 400f; frame 0 selected -> bottom half (400..900) clipped to visible (0..800)
        val dimBottom = HalfFrameDimOverlayView.computeDimmedRect(
            geometry = geometry,
            isTopBottom = true,
            selectedHalfFrameIndex = 0
        )
        assertEquals(RectF(0f, 400f, 600f, 800f), dimBottom)

        // frame 1 selected -> top half (-100..400) clipped to visible (0..800)
        val dimTop = HalfFrameDimOverlayView.computeDimmedRect(
            geometry = geometry,
            isTopBottom = true,
            selectedHalfFrameIndex = 1
        )
        assertEquals(RectF(0f, 0f, 600f, 400f), dimTop)
    }

    @Test
    fun computeDimmedRect_returnsNullWhenContentEmptyOrNonIntersecting() {
        val emptyGeometry = ImageDisplayGeometry(
            contentRect = RectF(),
            visibleRect = RectF(),
            outlineRect = RectF(),
            outlineRadius = 0f
        )
        assertNull(HalfFrameDimOverlayView.computeDimmedRect(emptyGeometry, false, 0))
    }

    @Test
    fun updateState_togglesVisibility() {
        val overlay = HalfFrameDimOverlayView(context)
        assertEquals(View.GONE, overlay.visibility)

        overlay.updateState(active = true, isTopBottom = false, selectedHalfFrameIndex = 0)
        assertEquals(View.VISIBLE, overlay.visibility)

        overlay.updateState(active = false, isTopBottom = false, selectedHalfFrameIndex = 0)
        assertEquals(View.GONE, overlay.visibility)
    }
}
