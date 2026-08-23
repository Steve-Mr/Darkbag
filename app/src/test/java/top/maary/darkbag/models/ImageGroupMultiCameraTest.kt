package top.maary.darkbag.models

import android.net.Uri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.maary.darkbag.utils.ImageUtils

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ImageGroupMultiCameraTest {

    @Test
    fun testBaseNameExtractionWithMultiCameraSuffix() {
        val ultraWideJpg = "Darkbag_20260823_120000_MULTI_0.6x.jpg"
        val wideJpg = "Darkbag_20260823_120000_MULTI_1.0x.jpg"
        val teleDng = "Darkbag_20260823_120000_MULTI_3.0x.dng"

        val base1 = ImageUtils.getBaseName(ultraWideJpg)
        val base2 = ImageUtils.getBaseName(wideJpg)
        val base3 = ImageUtils.getBaseName(teleDng)

        assertEquals("20260823_120000", base1)
        assertEquals("20260823_120000", base2)
        assertEquals("20260823_120000", base3)
        assertEquals(base1, base2)
        assertEquals(base2, base3)
    }

    @Test
    fun testImageGroupMultiCameraProperties() {
        val uri1 = Uri.parse("content://media/external/images/media/1")
        val uri2 = Uri.parse("content://media/external/images/media/2")
        val uri3 = Uri.parse("content://media/external/images/media/3")

        val group = ImageGroup(
            baseName = "20260823_120000",
            jpgUri = uri2,
            isMultiCamera = true,
            multiJpgUris = listOf(uri1, uri2, uri3)
        )

        assertTrue(group.hasAny())
        assertTrue(group.isMultiCamera)
        assertEquals(3, group.multiJpgUris.size)
        assertEquals(uri2, group.jpgUri)
        assertFalse(group.isHalfFrame())
    }
}
