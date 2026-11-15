package top.maary.darkbag.viewer.deletion

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.maary.darkbag.fragments.DarkbagBatchDeleteSheet
import top.maary.darkbag.fragments.GallerySelectedItem
import top.maary.darkbag.models.ImageGroup
import top.maary.darkbag.models.MultiCameraLensItem

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GalleryBatchDeletionCoordinatorTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun testComputeDialogOptionsForMixedAndSpecificLensSelection() {
        val lensWithBoth = MultiCameraLensItem(
            lensTag = "1.0x",
            multiplier = 1.0f,
            jpgUri = Uri.parse("content://media/lens1.jpg"),
            dngUri = Uri.parse("content://media/lens1.dng")
        )
        val lensJpgOnly = MultiCameraLensItem(
            lensTag = "3.0x",
            multiplier = 3.0f,
            jpgUri = Uri.parse("content://media/lens3.jpg"),
            dngUri = null
        )
        val multiGroup = ImageGroup(
            baseName = "DBAG_MULTI",
            isMultiCamera = true,
            multiCameraLenses = listOf(lensWithBoth, lensJpgOnly)
        )
        val cdngGroup = ImageGroup(
            baseName = "CDNG_001",
            isCinemaDng = true,
            cinemaDngFolderUri = Uri.parse("content://docs/tree/cdng"),
            cinemaDngFirstFrameUri = Uri.parse("content://docs/tree/cdng/000000.dng")
        )

        val jpgOnlySelection = listOf(GallerySelectedItem(multiGroup, lensJpgOnly))
        val jpgOnlyOpts = GalleryBatchDeletionCoordinator.computeDialogOptions(jpgOnlySelection)
        assertFalse(jpgOnlyOpts.hasAnyRaw)
        assertTrue(jpgOnlyOpts.hasAnyDerivatives)
        assertFalse(jpgOnlyOpts.hasAnyCinemaDng)

        val mixedSelection = listOf(
            GallerySelectedItem(multiGroup, lensWithBoth),
            GallerySelectedItem(cdngGroup, null)
        )
        val mixedOpts = GalleryBatchDeletionCoordinator.computeDialogOptions(mixedSelection)
        assertTrue(mixedOpts.hasAnyRaw)
        assertTrue(mixedOpts.hasAnyDerivatives)
        assertTrue(mixedOpts.hasAnyCinemaDng)
    }

    @Test
    fun testResolveBatchDeleteUrisAcrossAllModes() {
        val lens = MultiCameraLensItem(
            lensTag = "1.0x",
            multiplier = 1.0f,
            jpgUri = Uri.parse("content://media/lens.jpg"),
            dngUri = Uri.parse("content://media/lens.dng")
        )
        val multiGroup = ImageGroup(
            baseName = "MULTI_1",
            isMultiCamera = true,
            multiCameraLenses = listOf(lens)
        )
        val folderUri = Uri.parse("content://docs/tree/cdng")
        val frame0 = Uri.parse("content://docs/tree/cdng/000000.dng")
        val rawVid = Uri.parse("content://media/vid.rawvid")
        val mp4 = Uri.parse("content://media/vid.mp4")
        val videoGroup = ImageGroup(
            baseName = "VID_1",
            isCinemaDng = true,
            cinemaDngFolderUri = folderUri,
            cinemaDngFirstFrameUri = frame0,
            cinemaDngFrameUris = listOf(frame0),
            isRawVideo = true,
            rawVideoUri = rawVid,
            mp4VideoUri = mp4
        )
        val items = listOf(
            GallerySelectedItem(multiGroup, lens),
            GallerySelectedItem(videoGroup, null)
        )

        val entire = GalleryBatchDeletionCoordinator.resolveBatchDeleteUris(items, DarkbagBatchDeleteSheet.MODE_ENTIRE_GROUP)
        assertTrue(entire.containsAll(listOf(lens.jpgUri, lens.dngUri, folderUri, frame0, rawVid, mp4)))

        val rawOnly = GalleryBatchDeletionCoordinator.resolveBatchDeleteUris(items, DarkbagBatchDeleteSheet.MODE_RAW_ONLY)
        assertTrue(rawOnly.containsAll(listOf(lens.dngUri, folderUri, frame0, rawVid)))
        assertFalse(rawOnly.contains(lens.jpgUri))
        assertFalse(rawOnly.contains(mp4))

        val derivOnly = GalleryBatchDeletionCoordinator.resolveBatchDeleteUris(items, DarkbagBatchDeleteSheet.MODE_DERIVATIVES_ONLY)
        assertEquals(listOf(lens.jpgUri, mp4), derivOnly)

        val cdngOnly = GalleryBatchDeletionCoordinator.resolveBatchDeleteUris(items, DarkbagBatchDeleteSheet.MODE_CINEMADNG_ONLY)
        assertTrue(cdngOnly.containsAll(listOf(folderUri, frame0)))
        assertFalse(cdngOnly.contains(rawVid))
        assertFalse(cdngOnly.contains(mp4))
        assertFalse(cdngOnly.contains(lens.dngUri))
    }

    @Test
    fun testExecuteUriBatchDeletionEmptyList() = runBlocking {
        val outcome = GalleryBatchDeletionCoordinator.executeUriBatchDeletion(context, emptyList())
        assertEquals(0, outcome.attemptedCount)
        assertNull(outcome.deleteRequest)
        assertFalse(outcome.promptCreationFailed)
    }
}
