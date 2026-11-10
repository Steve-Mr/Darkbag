package top.maary.darkbag.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GalleryAspectRatioHelperTest {

    @Test
    fun testFormatAspectRatio_NormalOrientation() {
        val ratio = GalleryAspectRatioHelper.formatAspectRatio(4000, 3000, 0)
        assertEquals("4000:3000", ratio)
    }

    @Test
    fun testFormatAspectRatio_Rotated90Degrees() {
        val ratio = GalleryAspectRatioHelper.formatAspectRatio(4000, 3000, 90)
        assertEquals("3000:4000", ratio)
    }

    @Test
    fun testFormatAspectRatio_Rotated270Degrees() {
        val ratio = GalleryAspectRatioHelper.formatAspectRatio(4000, 3000, 270)
        assertEquals("3000:4000", ratio)
    }

    @Test
    fun testFormatAspectRatio_InvalidDimensions() {
        assertNull(GalleryAspectRatioHelper.formatAspectRatio(0, 3000, 0))
        assertNull(GalleryAspectRatioHelper.formatAspectRatio(4000, 0, 0))
        assertNull(GalleryAspectRatioHelper.formatAspectRatio(-1, -1, 0))
    }
}
