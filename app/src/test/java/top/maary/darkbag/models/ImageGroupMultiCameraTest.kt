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

    @Test
    fun testMultiCameraCollageLayouts() {
        val sbs = top.maary.darkbag.utils.MultiCameraCollageHelper.CollageLayout.SIDE_BY_SIDE
        val tb = top.maary.darkbag.utils.MultiCameraCollageHelper.CollageLayout.TOP_BOTTOM
        val triptych = top.maary.darkbag.utils.MultiCameraCollageHelper.CollageLayout.TRIPTYCH_ROW

        assertEquals("SIDE_BY_SIDE", sbs.name)
        assertEquals("TOP_BOTTOM", tb.name)
        assertEquals("TRIPTYCH_ROW", triptych.name)
    }

    @Test
    fun testLensTagAndMultiplierExtraction() {
        val uri1 = "content://media/external/images/media/DBAG_20260823_133214_MULTI_0.6x.jpg"
        val uri2 = "content://media/external/images/media/DBAG_20260823_133214_MULTI_1.0x.jpg"
        val uri3 = "tree/primary%3APictures%2FRaw/document/primary%3APictures%2FRaw%2FDBAG_20260823_133214_MULTI_3.0x.dng"

        assertEquals("0.6x", ImageUtils.extractMultiCameraLensTag(uri1))
        assertEquals("1.0x", ImageUtils.extractMultiCameraLensTag(uri2))
        assertEquals("3.0x", ImageUtils.extractMultiCameraLensTag(uri3))

        assertEquals(0.6f, ImageUtils.extractMultiCameraMultiplier(uri1), 0.01f)
        assertEquals(1.0f, ImageUtils.extractMultiCameraMultiplier(uri2), 0.01f)
        assertEquals(3.0f, ImageUtils.extractMultiCameraMultiplier(uri3), 0.01f)
    }

    @Test
    fun testMultiCameraUriSorting() {
        val uriTele = Uri.parse("file:///Pictures/DBAG_20260823_133214_MULTI_3.0x.jpg")
        val uriWide = Uri.parse("file:///Pictures/DBAG_20260823_133214_MULTI_1.0x.jpg")
        val uriUltra = Uri.parse("file:///Pictures/DBAG_20260823_133214_MULTI_0.6x.jpg")

        val rawList = listOf(uriTele, uriUltra, uriWide)
        val sortedList = rawList.sortedWith(compareBy<Uri> {
            ImageUtils.extractMultiCameraMultiplier(it.toString())
        }.thenBy { it.toString() })

        assertEquals(uriUltra, sortedList[0])
        assertEquals(uriWide, sortedList[1])
        assertEquals(uriTele, sortedList[2])
    }

    @Test
    fun testMultiCameraLensItemPairing() {
        val ultraJpg = Uri.parse("content://media/external/images/media/101")
        val ultraDng = Uri.parse("content://saf/DBAG_20260823_120000_MULTI_0.7x.dng")
        val wideJpg = Uri.parse("content://media/external/images/media/102")
        val wideDng = Uri.parse("content://saf/DBAG_20260823_120000_MULTI_1.0x.dng")
        val teleJpg = Uri.parse("content://media/external/images/media/103")
        val teleDng = Uri.parse("content://saf/DBAG_20260823_120000_MULTI_3.0x.dng")

        val lens0 = MultiCameraLensItem(lensTag = "0.7x", multiplier = 0.7f, jpgUri = ultraJpg, dngUri = ultraDng)
        val lens1 = MultiCameraLensItem(lensTag = "1.0x", multiplier = 1.0f, jpgUri = wideJpg, dngUri = wideDng)
        val lens2 = MultiCameraLensItem(lensTag = "3.0x", multiplier = 3.0f, jpgUri = teleJpg, dngUri = teleDng)

        val group = ImageGroup(
            baseName = "20260823_120000",
            jpgUri = wideJpg,
            isMultiCamera = true,
            multiCameraLenses = listOf(lens0, lens1, lens2),
            multiJpgUris = listOf(ultraJpg, wideJpg, teleJpg),
            multiDngUris = listOf(ultraDng, wideDng, teleDng)
        )

        assertEquals(3, group.multiCameraLenses.size)
        assertEquals("0.7x", group.multiCameraLenses[0].lensTag)
        assertEquals(ultraJpg, group.multiCameraLenses[0].jpgUri)
        assertEquals(ultraDng, group.multiCameraLenses[0].dngUri)

        assertEquals("1.0x", group.multiCameraLenses[1].lensTag)
        assertEquals(wideJpg, group.multiCameraLenses[1].jpgUri)
        assertEquals(wideDng, group.multiCameraLenses[1].dngUri)

        assertEquals("3.0x", group.multiCameraLenses[2].lensTag)
        assertEquals(teleJpg, group.multiCameraLenses[2].jpgUri)
        assertEquals(teleDng, group.multiCameraLenses[2].dngUri)
    }
}
