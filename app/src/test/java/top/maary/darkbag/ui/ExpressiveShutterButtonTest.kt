package top.maary.darkbag.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.ContextThemeWrapper
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.maary.darkbag.R

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ExpressiveShutterButtonTest {

    private lateinit var themedContext: Context
    private lateinit var shutterButton: ExpressiveShutterButton

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        themedContext = ContextThemeWrapper(app, R.style.AppTheme)
        shutterButton = ExpressiveShutterButton(themedContext)
    }

    @Test
    fun testInitialState() {
        assertFalse("Half-frame should be disabled by default", shutterButton.isHalfFrameEnabled())
        assertEquals("Initial half-frame step should be 0", 0, shutterButton.getHalfFrameStep())
        assertEquals(0.54f, shutterButton.getSplitRatio(), 0.001f)
        assertFalse(shutterButton.isSplitRatioAnimating())
    }

    @Test
    fun testSetHalfFrameStateEnabledStep0() {
        shutterButton.setHalfFrameState(enabled = true, step = 0)

        assertTrue(shutterButton.isHalfFrameEnabled())
        assertEquals(0, shutterButton.getHalfFrameStep())
        assertEquals(0.54f, shutterButton.getSplitRatio(), 0.001f)
        assertFalse(shutterButton.isSplitRatioAnimating())
    }

    @Test
    fun testSetHalfFrameStateEnabledStep1Directly() {
        shutterButton.setHalfFrameState(enabled = true, step = 1)

        assertTrue(shutterButton.isHalfFrameEnabled())
        assertEquals(1, shutterButton.getHalfFrameStep())
        assertEquals(0.46f, shutterButton.getSplitRatio(), 0.001f)
        assertFalse(shutterButton.isSplitRatioAnimating())
    }

    @Test
    fun testStepClamping() {
        shutterButton.setHalfFrameState(enabled = true, step = -5)
        assertEquals(0, shutterButton.getHalfFrameStep())

        shutterButton.setHalfFrameState(enabled = true, step = 10)
        assertEquals(1, shutterButton.getHalfFrameStep())
    }

    @Test
    fun testStepTransitionTriggersSplitRatioAnimator() {
        // Initial setup at step 0
        shutterButton.setHalfFrameState(enabled = true, step = 0)
        assertEquals(0.54f, shutterButton.getSplitRatio(), 0.001f)

        // Step transition 0 -> 1 while enabled
        shutterButton.setHalfFrameState(enabled = true, step = 1)
        assertEquals(1, shutterButton.getHalfFrameStep())
        assertTrue("Step transition must start animator", shutterButton.isSplitRatioAnimating())

        // Fast-forward animator to completion
        shutterButton.splitRatioAnimatorForTest?.end()
        assertFalse(shutterButton.isSplitRatioAnimating())
        assertEquals(0.46f, shutterButton.getSplitRatio(), 0.001f)

        // Step transition 1 -> 0 while enabled
        shutterButton.setHalfFrameState(enabled = true, step = 0)
        assertEquals(0, shutterButton.getHalfFrameStep())
        assertTrue("Step transition must start animator", shutterButton.isSplitRatioAnimating())

        // Fast-forward animator to completion
        shutterButton.splitRatioAnimatorForTest?.end()
        assertFalse(shutterButton.isSplitRatioAnimating())
        assertEquals(0.54f, shutterButton.getSplitRatio(), 0.001f)
    }

    @Test
    fun testDisableHalfFrameStateCancelsAnimatorAndResets() {
        shutterButton.setHalfFrameState(enabled = true, step = 0)
        shutterButton.setHalfFrameState(enabled = true, step = 1)
        assertTrue(shutterButton.isSplitRatioAnimating())

        // Disabling half-frame cancels animator
        shutterButton.setHalfFrameState(enabled = false, step = 0)
        assertFalse(shutterButton.isHalfFrameEnabled())
        assertFalse(shutterButton.isSplitRatioAnimating())
        assertEquals(0.54f, shutterButton.getSplitRatio(), 0.001f)
    }

    @Test
    fun testHalfFrameDrawingLayoutConcentricGeometryStep0() {
        val dp = themedContext.resources.displayMetrics.density
        val sizePx = (96f * dp).toInt()

        shutterButton.measure(
            View.MeasureSpec.makeMeasureSpec(sizePx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(sizePx, View.MeasureSpec.EXACTLY)
        )
        shutterButton.layout(0, 0, sizePx, sizePx)

        shutterButton.setHalfFrameState(enabled = true, step = 0)

        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        shutterButton.draw(canvas)

        val bounds = shutterButton.boundsRectForTest
        val inner = shutterButton.innerRectForTest
        val left = shutterButton.leftMiniRectForTest
        val right = shutterButton.rightMiniRectForTest

        val inset = 2f * dp
        assertEquals(inset, bounds.left, 0.01f)
        assertEquals(inset, bounds.top, 0.01f)
        assertEquals(sizePx - inset, bounds.right, 0.01f)
        assertEquals(sizePx - inset, bounds.bottom, 0.01f)

        // Inner rect padded by 4dp concentrically
        val innerPad = 4f * dp
        assertEquals(bounds.left + innerPad, inner.left, 0.01f)
        assertEquals(bounds.top + innerPad, inner.top, 0.01f)
        assertEquals(bounds.right - innerPad, inner.right, 0.01f)
        assertEquals(bounds.bottom - innerPad, inner.bottom, 0.01f)

        // 4dp gap between left and right mini blocks
        val gap = 4f * dp
        assertEquals(gap, right.left - left.right, 0.01f)

        // Vertical bounds match inner rect
        assertEquals(inner.top, left.top, 0.01f)
        assertEquals(inner.bottom, left.bottom, 0.01f)
        assertEquals(inner.top, right.top, 0.01f)
        assertEquals(inner.bottom, right.bottom, 0.01f)

        // Horizontal split: left 54%, right 46%
        val availableW = inner.width() - gap
        assertEquals(availableW * 0.54f, left.width(), 0.01f)
        assertEquals(availableW * 0.46f, right.width(), 0.01f)
    }

    @Test
    fun testHalfFrameDrawingLayoutConcentricGeometryStep1() {
        val dp = themedContext.resources.displayMetrics.density
        val sizePx = (96f * dp).toInt()

        shutterButton.measure(
            View.MeasureSpec.makeMeasureSpec(sizePx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(sizePx, View.MeasureSpec.EXACTLY)
        )
        shutterButton.layout(0, 0, sizePx, sizePx)

        shutterButton.setHalfFrameState(enabled = true, step = 1)
        shutterButton.splitRatioAnimatorForTest?.end()

        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        shutterButton.draw(canvas)

        val inner = shutterButton.innerRectForTest
        val left = shutterButton.leftMiniRectForTest
        val right = shutterButton.rightMiniRectForTest

        val gap = 4f * dp
        val availableW = inner.width() - gap
        assertEquals(availableW * 0.46f, left.width(), 0.01f)
        assertEquals(availableW * 0.54f, right.width(), 0.01f)
        assertEquals(gap, right.left - left.right, 0.01f)
    }

    @Test
    fun testNormalDrawingModeSingleBlock() {
        val dp = themedContext.resources.displayMetrics.density
        val sizePx = (96f * dp).toInt()

        shutterButton.measure(
            View.MeasureSpec.makeMeasureSpec(sizePx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(sizePx, View.MeasureSpec.EXACTLY)
        )
        shutterButton.layout(0, 0, sizePx, sizePx)

        shutterButton.setHalfFrameState(enabled = false, step = 0)

        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        // Should draw full single active block cleanly
        shutterButton.draw(canvas)

        val bounds = shutterButton.boundsRectForTest
        val inset = 2f * dp
        assertEquals(inset, bounds.left, 0.01f)
        assertEquals(sizePx - inset, bounds.right, 0.01f)
    }
}
