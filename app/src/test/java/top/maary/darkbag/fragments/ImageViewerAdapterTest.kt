package top.maary.darkbag.fragments

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.maary.darkbag.models.ImageGroup

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ImageViewerAdapterTest {

    private lateinit var context: Context
    private lateinit var adapter: ImageViewerAdapter

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        adapter = ImageViewerAdapter(emptyList(), scope, context)
    }

    @Test
    fun testDiffCallbackDetectsMp4VideoUriChange() {
        val rawVideoUri = Uri.parse("content://media/external/video/media/100")
        val exportedMp4Uri = Uri.parse("content://media/external/video/media/101")

        val oldItem = ImageGroup(
            baseName = "RAWVID_20260904_120000",
            isRawVideo = true,
            rawVideoUri = rawVideoUri,
            mp4VideoUri = null
        )

        val newItem = oldItem.copy(
            mp4VideoUri = exportedMp4Uri
        )

        assertTrue("Items should be the same based on baseName", adapter.diffCallback.areItemsTheSame(oldItem, newItem))
        assertFalse("Contents should differ when mp4VideoUri is updated", adapter.diffCallback.areContentsTheSame(oldItem, newItem))

        val payload = adapter.diffCallback.getChangePayload(oldItem, newItem)
        assertNotNull("Payload must not be null", payload)
        val payloadSet = payload as? Set<*>
        assertNotNull("Payload must be a Set", payloadSet)
        assertTrue("Payload should contain MP4_URI_CHANGED", payloadSet!!.contains("MP4_URI_CHANGED"))
        assertTrue("Payload should contain DERIVATIVES_CHANGED", payloadSet.contains("DERIVATIVES_CHANGED"))
    }

    @Test
    fun testDiffCallbackDetectsDerivativeMp4UrisChange() {
        val rawVideoUri = Uri.parse("content://media/external/video/media/100")
        val derivativeMp4Uri = Uri.parse("content://media/external/video/media/102")

        val oldItem = ImageGroup(
            baseName = "RAWVID_20260904_120000",
            isRawVideo = true,
            rawVideoUri = rawVideoUri,
            derivativeMp4Uris = emptyList()
        )

        val newItem = oldItem.copy(
            derivativeMp4Uris = listOf(derivativeMp4Uri)
        )

        assertFalse("Contents should differ when derivativeMp4Uris change", adapter.diffCallback.areContentsTheSame(oldItem, newItem))

        val payload = adapter.diffCallback.getChangePayload(oldItem, newItem)
        val payloadSet = payload as? Set<*>
        assertNotNull("Payload must be a Set", payloadSet)
        assertTrue("Payload should contain MP4_URI_CHANGED", payloadSet!!.contains("MP4_URI_CHANGED"))
        assertTrue("Payload should contain DERIVATIVES_CHANGED", payloadSet.contains("DERIVATIVES_CHANGED"))
    }

    @Test
    fun testDiffCallbackDetectsCinemaDngFolderUriChange() {
        val rawVideoUri = Uri.parse("content://media/external/video/media/100")
        val folderUri = Uri.parse("content://documents/tree/DBAG_CDNG_20260904_120000")
        val frame0 = Uri.parse("content://documents/tree/DBAG_CDNG_20260904_120000/000000.dng")

        val oldItem = ImageGroup(
            baseName = "CDNG_20260904_120000",
            isRawVideo = true,
            rawVideoUri = rawVideoUri,
            cinemaDngFolderUri = null,
            isCinemaDng = false
        )

        val newItem = oldItem.copy(
            cinemaDngFolderUri = folderUri,
            isCinemaDng = true,
            cinemaDngFrameUris = listOf(frame0)
        )

        assertTrue(adapter.diffCallback.areItemsTheSame(oldItem, newItem))
        assertFalse("Contents should differ when cinemaDngFolderUri is updated", adapter.diffCallback.areContentsTheSame(oldItem, newItem))

        val payload = adapter.diffCallback.getChangePayload(oldItem, newItem)
        assertNotNull(payload)
        val payloadSet = payload as? Set<*>
        assertNotNull(payloadSet)
        assertTrue("Payload should contain CINEMADNG_CHANGED", payloadSet!!.contains("CINEMADNG_CHANGED"))
    }

    @Test
    fun testDiffCallbackDetectsDerivativeJpgChange() {
        val rawUri = Uri.parse("content://media/external/images/media/200")
        val derivativeJpg = Uri.parse("content://media/external/images/media/201")

        val oldItem = ImageGroup(
            baseName = "PHOTO_20260904_120000",
            dngUri = rawUri,
            derivativeJpgUris = emptyList()
        )

        val newItem = oldItem.copy(
            derivativeJpgUris = listOf(derivativeJpg)
        )

        assertFalse("Contents should differ when derivativeJpgUris is updated", adapter.diffCallback.areContentsTheSame(oldItem, newItem))

        val payload = adapter.diffCallback.getChangePayload(oldItem, newItem)
        val payloadSet = payload as? Set<*>
        assertNotNull(payloadSet)
        assertTrue("Payload should contain DERIVATIVES_CHANGED", payloadSet!!.contains("DERIVATIVES_CHANGED"))
    }

    @Test
    fun testSetFormatForGroup() {
        val group = ImageGroup(
            baseName = "RAWVID_20260904_120000",
            isRawVideo = true,
            rawVideoUri = Uri.parse("content://media/external/video/media/100"),
            mp4VideoUri = Uri.parse("content://media/external/video/media/101")
        )

        // Set to JPG format
        adapter.setFormatForGroup(group.baseName, ImageViewerAdapter.FORMAT_JPG)
        assertEquals(ImageViewerAdapter.FORMAT_JPG, adapter.getSelectedFormat(group))

        // Set to DNG format
        adapter.setFormatForGroup(group.baseName, ImageViewerAdapter.FORMAT_DNG)
        assertEquals(ImageViewerAdapter.FORMAT_DNG, adapter.getSelectedFormat(group))
    }
}
