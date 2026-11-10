package top.maary.darkbag.utils

import android.net.Uri
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.maary.darkbag.fragments.GallerySelectedItem
import top.maary.darkbag.models.ImageGroup

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class GalleryHomogeneityCheckerTest {

    @Test
    fun testEmptyListReturnsFalse() {
        assertFalse(GalleryHomogeneityChecker.isHomogeneous(emptyList()))
    }

    @Test
    fun testStaticPhotosHomogeneous() {
        val item1 = GallerySelectedItem(ImageGroup(baseName = "img1", jpgUri = Uri.parse("content://media/1")))
        val item2 = GallerySelectedItem(ImageGroup(baseName = "img2", jpgUri = Uri.parse("content://media/2")))
        assertTrue(GalleryHomogeneityChecker.isHomogeneous(listOf(item1, item2)))
    }

    @Test
    fun testMotionPhotosHomogeneous() {
        val item1 = GallerySelectedItem(ImageGroup(baseName = "mp1", isMotionPhoto = true, jpgUri = Uri.parse("content://media/1")))
        val item2 = GallerySelectedItem(ImageGroup(baseName = "mp2", isMotionPhoto = true, jpgUri = Uri.parse("content://media/2")))
        assertTrue(GalleryHomogeneityChecker.isHomogeneous(listOf(item1, item2)))
    }

    @Test
    fun testVideosHomogeneous() {
        val item1 = GallerySelectedItem(ImageGroup(baseName = "vid1", isRawVideo = true, rawVideoUri = Uri.parse("content://media/1")))
        val item2 = GallerySelectedItem(ImageGroup(baseName = "vid2", isMp4Video = true, mp4VideoUri = Uri.parse("content://media/2")))
        assertTrue(GalleryHomogeneityChecker.isHomogeneous(listOf(item1, item2)))
    }

    @Test
    fun testMixedTypesReturnsFalse() {
        val photo = GallerySelectedItem(ImageGroup(baseName = "img1", jpgUri = Uri.parse("content://media/1")))
        val motionPhoto = GallerySelectedItem(ImageGroup(baseName = "mp1", isMotionPhoto = true, jpgUri = Uri.parse("content://media/2")))
        assertFalse(GalleryHomogeneityChecker.isHomogeneous(listOf(photo, motionPhoto)))

        val video = GallerySelectedItem(ImageGroup(baseName = "vid1", isRawVideo = true, rawVideoUri = Uri.parse("content://media/3")))
        assertFalse(GalleryHomogeneityChecker.isHomogeneous(listOf(photo, video)))
    }
}
