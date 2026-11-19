package top.maary.darkbag.viewer.strategy

import android.app.Application
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.maary.darkbag.models.ImageGroup
import top.maary.darkbag.repository.ImageRepository
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ViewerMediaStrategyTest {

    private lateinit var context: Application
    private lateinit var repository: ImageRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        repository = ImageRepository(context)
    }

    @Test
    fun testViewerMediaStrategyFactory_createsSandboxStrategy_whenPlaygroundDngPathsPresent() {
        val args = Bundle().apply {
            putStringArray(ViewerMediaStrategyFactory.KEY_PLAYGROUND_DNG_PATHS, arrayOf("/path/to/1.dng"))
        }
        val strategy = ViewerMediaStrategyFactory.create(args)
        assertTrue(strategy is SandboxViewerStrategy)
        assertTrue(strategy.isSandbox)
        assertFalse(strategy.isGallerySupported)
    }

    @Test
    fun testViewerMediaStrategyFactory_createsMediaStoreStrategy_whenArgumentsNullOrEmpty() {
        val nullStrategy = ViewerMediaStrategyFactory.create(null)
        assertTrue(nullStrategy is MediaStoreViewerStrategy)
        assertFalse(nullStrategy.isSandbox)
        assertTrue(nullStrategy.isGallerySupported)

        val emptyArgs = Bundle()
        val emptyStrategy = ViewerMediaStrategyFactory.create(emptyArgs)
        assertTrue(emptyStrategy is MediaStoreViewerStrategy)
    }

    @Test
    fun testHandleDiscard_sandboxNewMergedComposite_returnsTrue() {
        val strategy = SandboxViewerStrategy()
        val args = Bundle().apply {
            putStringArray("playground_dng_paths", arrayOf("/path/to/frame1.dng", "/path/to/frame2.dng"))
        }
        val groupWithoutJpg = ImageGroup(
            baseName = "test_merged",
            jpgUri = null
        )
        val shouldDiscardDirectly = strategy.handleDiscard(
            currentGroup = groupWithoutJpg,
            groups = listOf(groupWithoutJpg),
            args = args
        )
        assertTrue(shouldDiscardDirectly)
    }

    @Test
    fun testHandleDiscard_sandboxSavedComposite_returnsFalse() {
        val strategy = SandboxViewerStrategy()
        val args = Bundle().apply {
            putStringArray("playground_dng_paths", arrayOf("/path/to/frame1.dng", "/path/to/frame2.dng"))
        }
        val groupWithJpg = ImageGroup(
            baseName = "test_merged",
            jpgUri = android.net.Uri.parse("file:///path/to/test_merged.jpg")
        )
        val shouldDiscardDirectly = strategy.handleDiscard(
            currentGroup = groupWithJpg,
            groups = listOf(groupWithJpg),
            args = args
        )
        assertFalse(shouldDiscardDirectly)
    }

    @Test
    fun testHandleDiscard_mediaStore_alwaysReturnsFalse() {
        val strategy = MediaStoreViewerStrategy()
        val group = ImageGroup(baseName = "IMG_001")
        val handled = strategy.handleDiscard(
            currentGroup = group,
            groups = listOf(group),
            args = Bundle()
        )
        assertFalse(handled)
    }

    @Test
    fun testLoadInitialGroups_sandboxSingleDng() = kotlinx.coroutines.runBlocking {
        val strategy = SandboxViewerStrategy()
        val playgroundDir = File(context.filesDir, "playground_dngs").apply { mkdirs() }
        val testDng = File(playgroundDir, "DARK_SINGLE_TEST.dng").apply { writeText("dummy") }

        val args = Bundle().apply {
            putStringArray("playground_dng_paths", arrayOf(testDng.absolutePath))
        }

        val groups = strategy.loadInitialGroups(context, args, repository)
        assertNotNull(groups)
        assertEquals(1, groups?.size)
        val loaded = groups?.first()
        assertEquals("DARK_SINGLE_TEST", loaded?.baseName)
        assertEquals(android.net.Uri.fromFile(testDng), loaded?.dngUri)
    }

    @Test
    fun testLoadInitialGroups_sandboxDualDngHalfFrame() = kotlinx.coroutines.runBlocking {
        val strategy = SandboxViewerStrategy()
        val playgroundDir = File(context.filesDir, "playground_dngs").apply { mkdirs() }
        val dng1 = File(playgroundDir, "TEST_HF_1.dng").apply { writeText("dummy1") }
        val dng2 = File(playgroundDir, "TEST_HF_2.dng").apply { writeText("dummy2") }

        val args = Bundle().apply {
            putStringArray("playground_dng_paths", arrayOf(dng1.absolutePath, dng2.absolutePath))
            putString("playground_hf_layout", "TB")
        }

        val groups = strategy.loadInitialGroups(context, args, repository)
        assertNotNull(groups)
        assertEquals(1, groups?.size)
        val loaded = groups?.first()
        assertEquals("TEST_HF", loaded?.baseName)
        assertEquals("TB", loaded?.hfLayout)
        assertEquals(android.net.Uri.fromFile(dng1), loaded?.dngUri1)
        assertEquals(android.net.Uri.fromFile(dng2), loaded?.dngUri2)
    }
}
