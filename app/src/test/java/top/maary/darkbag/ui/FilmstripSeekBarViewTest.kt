package top.maary.darkbag.ui

import android.content.Context
import android.net.Uri
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FilmstripSeekBarViewTest {

    private lateinit var context: Context
    private lateinit var seekbar: FilmstripSeekBarView

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        seekbar = FilmstripSeekBarView(context)
    }

    @Test
    fun testSetFramesAndInitialState() {
        val uris = listOf(
            Uri.parse("file:///dng/0.dng"),
            Uri.parse("file:///dng/1.dng"),
            Uri.parse("file:///dng/2.dng"),
            Uri.parse("file:///dng/3.dng")
        )

        seekbar.setFrames(uris, initialFrameIndex = 2)

        assertEquals(4, seekbar.totalFrames)
        assertEquals(2, seekbar.currentFrameIndex)
        assertEquals(2f / 3f, seekbar.progress, 0.001f)
        assertEquals(uris, seekbar.frameUris)
    }

    @Test
    fun testSetProgress() {
        val uris = listOf(
            Uri.parse("file:///dng/0.dng"),
            Uri.parse("file:///dng/1.dng"),
            Uri.parse("file:///dng/2.dng"),
            Uri.parse("file:///dng/3.dng")
        )
        seekbar.setFrames(uris, 0)

        seekbar.setProgress(currentFrameIndex = 3, totalFrames = 4)

        assertEquals(3, seekbar.currentFrameIndex)
        assertEquals(1.0f, seekbar.progress, 0.001f)

        // Clamping check
        seekbar.setProgress(currentFrameIndex = 10, totalFrames = 4)
        assertEquals(3, seekbar.currentFrameIndex)
    }

    @Test
    fun testTouchScrubbingAndCallbacks() {
        val uris = (0 until 10).map { Uri.parse("file:///dng/$it.dng") }
        seekbar.setFrames(uris, 0)

        // Simulate layout dimensions
        seekbar.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(400, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(100, android.view.View.MeasureSpec.EXACTLY)
        )
        seekbar.layout(0, 0, 400, 100)

        var selectedIndex = -1
        var selectedUri: Uri? = null
        var isScrubbingState = false

        seekbar.onFrameSelected = { index, uri ->
            selectedIndex = index
            selectedUri = uri
        }
        seekbar.onScrubStateChanged = { scrubbing ->
            isScrubbingState = scrubbing
        }

        // Action Down at 50% width (touchX = 200f) -> frame index 5 (0.5 * 9 = 4.5 -> 5)
        val downEvent = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 200f, 50f, 0)
        seekbar.onTouchEvent(downEvent)

        assertTrue(isScrubbingState)
        assertEquals(5, selectedIndex)
        assertEquals(Uri.parse("file:///dng/5.dng"), selectedUri)
        assertEquals(5, seekbar.currentFrameIndex)

        // Action Move to 100% width (touchX = 400f) -> frame index 9
        val moveEvent = MotionEvent.obtain(0L, 10L, MotionEvent.ACTION_MOVE, 400f, 50f, 0)
        seekbar.onTouchEvent(moveEvent)

        assertEquals(9, selectedIndex)
        assertEquals(Uri.parse("file:///dng/9.dng"), selectedUri)
        assertEquals(9, seekbar.currentFrameIndex)

        // Action Up
        val upEvent = MotionEvent.obtain(0L, 20L, MotionEvent.ACTION_UP, 400f, 50f, 0)
        seekbar.onTouchEvent(upEvent)

        assertFalse(isScrubbingState)
    }
}
