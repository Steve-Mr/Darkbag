package top.maary.darkbag.fragments

import android.net.Uri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.maary.darkbag.models.ImageGroup

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ViewerGalleryBentoTest {

    @Test
    fun testGalleryAlbumInfoSheetNewInstance() {
        val sheet = GalleryAlbumInfoSheet.newInstance(
            totalCount = 42,
            totalSizeBytes = 1024L * 1024L * 500L, // 500 MB
            lastCaptureTime = 1700000000000L
        )
        val args = sheet.arguments
        assertNotNull(args)
        assertEquals(42, args?.getInt("arg_total_count"))
        assertEquals(1024L * 1024L * 500L, args?.getLong("arg_total_size"))
        assertEquals(1700000000000L, args?.getLong("arg_last_capture"))
    }

    @Test
    fun testAlbumStatsCache_instantComputation() {
        val g1 = ImageGroup(baseName = "IMG_001", captureTime = 1000L, totalSizeBytes = 1024L * 1024L * 10L)
        val g2 = ImageGroup(baseName = "IMG_002", captureTime = 2000L, totalSizeBytes = 1024L * 1024L * 20L)
        val items = listOf(g1, g2)

        top.maary.darkbag.repository.AlbumStatsCache.invalidate()
        val stats = top.maary.darkbag.repository.AlbumStatsCache.getOrCompute(items)

        assertEquals(2, stats.totalCount)
        assertEquals(1024L * 1024L * 30L, stats.totalSizeBytes)
        assertEquals(2000L, stats.lastCaptureTime)

        // Verify cache hit
        val cached = top.maary.darkbag.repository.AlbumStatsCache.getOrCompute(items)
        assertEquals(stats, cached)
    }

    @Test
    fun testAlbumStatsCache_reactiveUpdateOnDatasetChange() {
        val g1 = ImageGroup(baseName = "IMG_001", captureTime = 1000L, totalSizeBytes = 1024L * 1024L * 10L)
        val items1 = listOf(g1)
        top.maary.darkbag.repository.AlbumStatsCache.invalidate()
        val stats1 = top.maary.darkbag.repository.AlbumStatsCache.getOrCompute(items1)
        assertEquals(1, stats1.totalCount)
        assertEquals(1024L * 1024L * 10L, stats1.totalSizeBytes)

        val g2 = ImageGroup(baseName = "IMG_002", captureTime = 2500L, totalSizeBytes = 1024L * 1024L * 15L)
        val items2 = listOf(g1, g2)
        val stats2 = top.maary.darkbag.repository.AlbumStatsCache.getOrCompute(items2)
        assertEquals(2, stats2.totalCount)
        assertEquals(1024L * 1024L * 25L, stats2.totalSizeBytes)
        assertEquals(2500L, stats2.lastCaptureTime)
    }

    @Test
    fun testAlbumStatsCache_manualUpdateCalculatedSize() {
        val g1 = ImageGroup(baseName = "IMG_001", captureTime = 1000L)
        val items = listOf(g1)
        top.maary.darkbag.repository.AlbumStatsCache.invalidate()
        top.maary.darkbag.repository.AlbumStatsCache.updateCalculatedSize(1024L * 500L, items)

        val stats = top.maary.darkbag.repository.AlbumStatsCache.getOrCompute(items)
        assertEquals(1, stats.totalCount)
        assertEquals(1024L * 500L, stats.totalSizeBytes)
    }

    @Test
    fun testHomogeneity_allStaticPhotos() {
        val fragment = ImageViewerFragment()
        val g1 = ImageGroup(baseName = "IMG_001", jpgUri = Uri.parse("content://media/1"))
        val g2 = ImageGroup(baseName = "IMG_002", dngUri = Uri.parse("content://media/2"), jpgUri = Uri.parse("content://media/3"))
        val items = listOf(
            GallerySelectedItem(g1),
            GallerySelectedItem(g2)
        )
        assertTrue(fragment.checkHomogeneity(items))
    }

    @Test
    fun testHomogeneity_allMotionPhotos() {
        val fragment = ImageViewerFragment()
        val g1 = ImageGroup(baseName = "IMG_001", jpgUri = Uri.parse("content://media/1"), isMotionPhoto = true)
        val g2 = ImageGroup(baseName = "IMG_002", jpgUri = Uri.parse("content://media/2"), isMotionPhoto = true)
        val items = listOf(
            GallerySelectedItem(g1),
            GallerySelectedItem(g2)
        )
        assertTrue(fragment.checkHomogeneity(items))
    }

    @Test
    fun testHomogeneity_allVideos() {
        val fragment = ImageViewerFragment()
        val g1 = ImageGroup(baseName = "VID_001", rawVideoUri = Uri.parse("content://media/1"), isRawVideo = true)
        val g2 = ImageGroup(baseName = "VID_002", mp4VideoUri = Uri.parse("content://media/2"))
        val g3 = ImageGroup(baseName = "VID_003", isCinemaDng = true, cinemaDngFolderUri = Uri.parse("content://media/3"))
        val items = listOf(
            GallerySelectedItem(g1),
            GallerySelectedItem(g2),
            GallerySelectedItem(g3)
        )
        assertTrue(fragment.checkHomogeneity(items))
    }

    @Test
    fun testHomogeneity_mixedHeterogeneousFails() {
        val fragment = ImageViewerFragment()
        val gPhoto = ImageGroup(baseName = "IMG_001", jpgUri = Uri.parse("content://media/1"))
        val gVideo = ImageGroup(baseName = "VID_001", mp4VideoUri = Uri.parse("content://media/2"))
        val gMotion = ImageGroup(baseName = "IMG_002", jpgUri = Uri.parse("content://media/3"), isMotionPhoto = true)

        assertFalse(fragment.checkHomogeneity(listOf(GallerySelectedItem(gPhoto), GallerySelectedItem(gVideo))))
        assertFalse(fragment.checkHomogeneity(listOf(GallerySelectedItem(gPhoto), GallerySelectedItem(gMotion))))
        assertFalse(fragment.checkHomogeneity(listOf(GallerySelectedItem(gVideo), GallerySelectedItem(gMotion))))
        assertFalse(fragment.checkHomogeneity(emptyList()))
    }
}
