package top.maary.darkbag.ui.viewer.playback

import android.content.Context
import android.graphics.RectF
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.maary.darkbag.models.ImageGroup

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ViewerPagePlaybackControllerTest {

    private lateinit var context: Context
    private lateinit var scope: CoroutineScope
    private val testGroups = mutableListOf<ImageGroup>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        scope = CoroutineScope(Dispatchers.Unconfined)
        testGroups.clear()
    }

    @Test
    fun testFormatVideoTime() {
        val controller = ViewerPagePlaybackController(
            context = context,
            scope = scope,
            getRecyclerView = { null },
            getGroupAt = { testGroups.getOrNull(it) },
            getBackingList = { testGroups },
            radius = 16f,
            loadCinemaDngFrame = { _, _, _, _ -> },
            onCinemaDngFrameSelected = null
        )

        assertEquals("00:00", controller.formatVideoTime(0))
        assertEquals("00:05", controller.formatVideoTime(5_000))
        assertEquals("01:30", controller.formatVideoTime(90_000))
        assertEquals("10:05", controller.formatVideoTime(605_000))
    }

    @Test
    fun testGetVideoDuration_FromRawVideoMetadata() {
        val group = ImageGroup(
            baseName = "RAWVID_TEST",
            isRawVideo = true,
            rawVideoDurationMs = 4500L
        )
        testGroups.add(group)

        val controller = ViewerPagePlaybackController(
            context = context,
            scope = scope,
            getRecyclerView = { null },
            getGroupAt = { testGroups.getOrNull(it) },
            getBackingList = { testGroups },
            radius = 16f,
            loadCinemaDngFrame = { _, _, _, _ -> },
            onCinemaDngFrameSelected = null
        )

        assertEquals(4500L, controller.getVideoDuration(0))
    }

    @Test
    fun testGetVideoDuration_FromCinemaDngFrameCount() {
        val group = ImageGroup(
            baseName = "CDNG_TEST",
            isCinemaDng = true,
            cinemaDngFrameCount = 48 // at 24fps -> 2000ms
        )
        testGroups.add(group)

        val controller = ViewerPagePlaybackController(
            context = context,
            scope = scope,
            getRecyclerView = { null },
            getGroupAt = { testGroups.getOrNull(it) },
            getBackingList = { testGroups },
            radius = 16f,
            loadCinemaDngFrame = { _, _, _, _ -> },
            onCinemaDngFrameSelected = null
        )

        assertEquals(2000L, controller.getVideoDuration(0))
    }

    @Test
    fun testVideoDurationCacheIntegration() {
        val videoUri = Uri.parse("content://media/external/video/media/555")
        val group = ImageGroup(
            baseName = "MP4_TEST",
            mp4VideoUri = videoUri
        )
        testGroups.add(group)

        val controller = ViewerPagePlaybackController(
            context = context,
            scope = scope,
            getRecyclerView = { null },
            getGroupAt = { testGroups.getOrNull(it) },
            getBackingList = { testGroups },
            radius = 16f,
            loadCinemaDngFrame = { _, _, _, _ -> },
            onCinemaDngFrameSelected = null
        )

        controller.videoDurationCache[videoUri] = 12500L
        assertEquals(12500L, controller.getVideoDuration(0))
    }

    @Test
    fun testStopAllVideos_GracefulOnEmptyOrNullRecyclerView() {
        val controller = ViewerPagePlaybackController(
            context = context,
            scope = scope,
            getRecyclerView = { null },
            getGroupAt = { null },
            getBackingList = { emptyList() },
            radius = 16f,
            loadCinemaDngFrame = { _, _, _, _ -> },
            onCinemaDngFrameSelected = null
        )

        // Must execute safely without throwing NullPointerException
        controller.stopAllVideos(0)
        assertFalse(controller.isVideoPlaying(0))
        assertEquals(0L, controller.getVideoCurrentPosition(0))
    }
}
