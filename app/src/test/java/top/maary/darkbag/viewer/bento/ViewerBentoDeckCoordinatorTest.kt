package top.maary.darkbag.viewer.bento

import android.content.Context
import android.view.LayoutInflater
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.maary.darkbag.R
import top.maary.darkbag.databinding.FragmentImageViewerBinding

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ViewerBentoDeckCoordinatorTest {

    private lateinit var context: Context
    private lateinit var binding: FragmentImageViewerBinding
    private lateinit var coordinator: ViewerBentoDeckCoordinator

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.setTheme(R.style.AppTheme)
        val inflater = LayoutInflater.from(context)
        binding = FragmentImageViewerBinding.inflate(inflater)

        coordinator = ViewerBentoDeckCoordinator(
            binding = binding,
            getAdapter = { null },
            getCurrentItemIndex = { 0 },
            onControlsVisibilityRequested = {},
            onMultiCamCollageRequested = {},
            onOpenExportHubRequested = {},
            onFormatToggleRequested = {},
            onDerivativeCycleRequested = {},
            onMotionLiveToggleRequested = {}
        )
    }

    @Test
    fun testFormatVideoTime() {
        assertEquals("00:00", ViewerBentoDeckCoordinator.formatVideoTime(0L))
        assertEquals("00:09", ViewerBentoDeckCoordinator.formatVideoTime(9500L))
        assertEquals("01:05", ViewerBentoDeckCoordinator.formatVideoTime(65000L))
        assertEquals("10:00", ViewerBentoDeckCoordinator.formatVideoTime(600000L))
    }

    @Test
    fun testLookDotColorsPalette() {
        assertEquals(6, ViewerBentoDeckCoordinator.LOOK_DOT_COLORS.size)
    }

    @Test
    fun testUpdateVideoPlaybackButtonDisplaysPausedTime() {
        coordinator.updateVideoPlaybackButton(isPlaying = false, currentMs = 15000L, totalMs = 60000L)
        val text = binding.btnVideoPlayback.text.toString()
        assertTrue(text.contains("PLAY"))
        assertTrue(text.contains("00:15 / 01:00"))
    }
}
