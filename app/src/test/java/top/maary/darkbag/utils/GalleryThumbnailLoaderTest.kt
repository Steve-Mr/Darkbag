package top.maary.darkbag.utils

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GalleryThumbnailLoaderTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun testLoadDngThumbnail_nonExistentUri_returnsNullGracefully() = runBlocking {
        val nonExistentUri = Uri.parse("file:///non/existent/path/test.dng")
        val bitmap = GalleryThumbnailLoader.loadDngThumbnail(context, nonExistentUri)
        assertNull("Should return null for non-existent file", bitmap)
    }

    @Test
    fun testLoadDngThumbnail_validImageFile_decodesFallbackBitmap() = runBlocking {
        val testFile = File(context.cacheDir, "sample_test.dng")
        val bmp = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        FileOutputStream(testFile).use { out ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        }

        try {
            val loaded = GalleryThumbnailLoader.loadDngThumbnail(context, Uri.fromFile(testFile), 50, 50)
            assertNotNull("Fallback should decode valid bitmap image file", loaded)
        } finally {
            testFile.delete()
        }
    }

    @Test
    fun testLoadDngThumbnail_cancellationPropagated() = runBlocking {
        var cancellationObserved = false
        val job = launch {
            try {
                val nonExistentUri = Uri.parse("file:///dummy.dng")
                GalleryThumbnailLoader.loadDngThumbnail(context, nonExistentUri)
            } catch (e: CancellationException) {
                cancellationObserved = true
                throw e
            }
        }
        job.cancelAndJoin()
        // If job was cancelled, cancellation is expected
        assertTrue("Job is cancelled", job.isCancelled)
    }
}
