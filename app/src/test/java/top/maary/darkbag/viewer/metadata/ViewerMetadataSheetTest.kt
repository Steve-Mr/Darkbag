package top.maary.darkbag.viewer.metadata

import android.net.Uri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ViewerMetadataSheetTest {

    @Test
    fun testFormatFileSize() {
        assertEquals("0 B", ViewerMetadataSheet.formatFileSize(0))
        assertEquals("0 B", ViewerMetadataSheet.formatFileSize(-100))
        assertEquals("1.0 KB", ViewerMetadataSheet.formatFileSize(1024))
        assertEquals("1.5 KB", ViewerMetadataSheet.formatFileSize(1536))
        assertEquals("1.0 MB", ViewerMetadataSheet.formatFileSize(1024 * 1024))
        assertEquals("2.5 GB", ViewerMetadataSheet.formatFileSize((2.5 * 1024 * 1024 * 1024).toLong()))
    }

    @Test
    fun testNewInstanceCreatesSheetWithBundle() {
        val uri = Uri.parse("content://media/external/images/media/42")
        val sheet = ViewerMetadataSheet.newInstance(uri)

        val bundle = requireNotNull(sheet.arguments)
        val extractedUri = androidx.core.os.BundleCompat.getParcelable(bundle, "arg_uri", Uri::class.java)
        assertEquals(uri, extractedUri)
    }

    @Test
    fun testNewInstanceWithGroupAndAdjustmentFormatting() {
        val uri = Uri.parse("content://media/external/images/media/42")
        val group = top.maary.darkbag.models.ImageGroup(baseName = "IMG_42", jpgUri = uri, isMotionPhoto = true)
        val sheet = ViewerMetadataSheet.newInstance(uri, group)

        val bundle = requireNotNull(sheet.arguments)
        val extractedGroup = androidx.core.os.BundleCompat.getParcelable(bundle, "arg_group", top.maary.darkbag.models.ImageGroup::class.java)
        assertEquals(group, extractedGroup)

        val adj = org.json.JSONObject().apply {
            put("exposure", 0.5)
            put("contrast", 0.0)
            put("shadows", -0.25)
        }
        val formatted = ViewerMetadataSheet.formatNonDefaultAdjustments(adj)
        assertEquals("Exposure +0.5 · Shadows -0.25", formatted)

        val chips = ViewerMetadataSheet.extractAdjustmentChips(adj)
        assertEquals(listOf("Exposure +0.5", "Shadows -0.25"), chips)

        val (date, time) = ViewerMetadataSheet.splitExifDateTime("2026:11:27 09:42:18")
        assertEquals("2026-11-27", date)
        assertEquals("09:42:18", time)
    }
}
