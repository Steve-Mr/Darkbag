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

        val bundle = sheet.arguments
        assertNotNull(bundle)
        val extractedUri = bundle?.getParcelable<Uri>("arg_uri")
        assertEquals(uri, extractedUri)
    }
}
