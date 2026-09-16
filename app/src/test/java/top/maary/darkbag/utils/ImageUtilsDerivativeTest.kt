package top.maary.darkbag.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageUtilsDerivativeTest {

    @Test
    fun testGetBaseName_originalRawCapture() {
        val fileName = "DBAG_2026-09-16-10-25-00-123.dng"
        val baseName = ImageUtils.getBaseName(fileName)
        assertEquals("2026-09-16-10-25-00-123", baseName)
    }

    @Test
    fun testGetBaseName_editedDerivativeVersion() {
        val fileName = "DBAG_2026-09-16-10-25-00-123_edited_2026-09-16-10-35-12-456.jpg"
        val baseName = ImageUtils.getBaseName(fileName)
        assertEquals("2026-09-16-10-25-00-123", baseName)
    }

    @Test
    fun testGetBaseName_multiCameraEditedVersion() {
        val fileName = "DBAG_2026-09-16-10-25-00-123_MULTI_1.0x_edited_2026-09-16-10-35-12-456.jpg"
        val baseName = ImageUtils.getBaseName(fileName)
        assertEquals("2026-09-16-10-25-00-123", baseName)
    }

    @Test
    fun testPrefixedBaseName_addsPrefixWhenMissing() {
        val unprefixed = "2026-09-16-10-25-00-123"
        val prefixed = DarkbagIdentity.prefixedBaseName(unprefixed)
        assertEquals("DBAG_2026-09-16-10-25-00-123", prefixed)
    }

    @Test
    fun testPrefixedBaseName_doesNotDuplicateExistingPrefix() {
        val alreadyPrefixed = "DBAG_2026-09-16-10-25-00-123"
        val result = DarkbagIdentity.prefixedBaseName(alreadyPrefixed)
        assertEquals("DBAG_2026-09-16-10-25-00-123", result)
    }
}
