package top.maary.darkbag.fragments

import android.net.Uri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.maary.darkbag.models.ImageGroup

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BatchDeleteAndViewerDeleteTest {

    @Test
    fun testDarkbagBatchDeleteSheetNewInstanceArguments() {
        val sheet = DarkbagBatchDeleteSheet.newInstance(
            selectedCount = 5,
            hasRaw = true,
            hasDerivatives = true,
            hasCinemaDng = true
        )
        val args = sheet.arguments
        assertNotNull(args)
        assertEquals(5, args?.getInt("selected_count"))
        assertTrue(args?.getBoolean("has_raw") == true)
        assertTrue(args?.getBoolean("has_derivatives") == true)
        assertTrue(args?.getBoolean("has_cinemadng") == true)
        assertEquals(3, DarkbagBatchDeleteSheet.MODE_CINEMADNG_ONLY)
    }

    @Test
    fun testCinemaDngAndRawVidDisentangledDeletionUris() {
        val folderUri = Uri.parse("content://documents/tree/DBAG_CDNG_20260901_220000")
        val frame0 = Uri.parse("content://documents/tree/DBAG_CDNG_20260901_220000/000000.dng")
        val frame1 = Uri.parse("content://documents/tree/DBAG_CDNG_20260901_220000/000001.dng")
        val rawVideo = Uri.parse("content://media/external/video/media/100")
        val exportedMp4 = Uri.parse("content://media/external/video/media/101")
        val exportedJpg = Uri.parse("content://media/external/images/media/102")

        val group = ImageGroup(
            baseName = "CDNG_20260901_220000",
            isCinemaDng = true,
            cinemaDngFolderUri = folderUri,
            cinemaDngFirstFrameUri = frame0,
            cinemaDngFrameUris = listOf(frame0, frame1),
            isRawVideo = true,
            rawVideoUri = rawVideo,
            mp4VideoUri = exportedMp4,
            derivativeJpgUris = listOf(exportedJpg)
        )

        // Check CinemaDNG targets
        val cdngUris = group.allCinemaDngUris
        assertTrue(cdngUris.contains(folderUri))
        assertTrue(cdngUris.contains(frame0))
        assertTrue(cdngUris.contains(frame1))
        assertFalse(cdngUris.contains(rawVideo))
        assertFalse(cdngUris.contains(exportedMp4))
        assertFalse(cdngUris.contains(exportedJpg))

        // Check RAWVID master targets
        val rawvidUris = group.rawVideoMasterUris
        assertEquals(listOf(rawVideo), rawvidUris)
        assertFalse(rawvidUris.contains(folderUri))
        assertFalse(rawvidUris.contains(frame0))
        assertFalse(rawvidUris.contains(exportedMp4))

        // Check derivative targets
        val derivativeUris = group.allDerivativeUris
        assertTrue(derivativeUris.contains(exportedMp4))
        assertTrue(derivativeUris.contains(exportedJpg))
        assertFalse(derivativeUris.contains(folderUri))
        assertFalse(derivativeUris.contains(rawVideo))

        // Check entire group targets
        val allUris = group.allUris
        assertTrue(allUris.contains(folderUri))
        assertTrue(allUris.contains(frame0))
        assertTrue(allUris.contains(frame1))
        assertTrue(allUris.contains(rawVideo))
        assertTrue(allUris.contains(exportedMp4))
        assertTrue(allUris.contains(exportedJpg))
    }

    @Test
    fun testPhotoRawAndJpgDisentangledDeletionUris() {
        val dngUri = Uri.parse("content://media/external/images/media/200")
        val jpgUri = Uri.parse("content://media/external/images/media/201")

        val group = ImageGroup(
            baseName = "PHOTO_20260901_220000",
            dngUri = dngUri,
            jpgUri = jpgUri
        )

        val rawUris = group.photoMasterUris
        assertEquals(listOf(dngUri), rawUris)

        val derivativeUris = group.allDerivativeUris
        assertEquals(listOf(jpgUri), derivativeUris)

        val allUris = group.allUris
        assertTrue(allUris.contains(dngUri))
        assertTrue(allUris.contains(jpgUri))
    }

    @Test
    fun testBatchDeleteMode3OnlyDeletesCinemaDng() {
        val folderUri = Uri.parse("content://documents/tree/DBAG_CDNG_20260901_220000")
        val frame0 = Uri.parse("content://documents/tree/DBAG_CDNG_20260901_220000/000000.dng")
        val rawVideo = Uri.parse("content://media/external/video/media/300")
        val mp4 = Uri.parse("content://media/external/video/media/301")

        val group = ImageGroup(
            baseName = "CDNG_20260901_220000",
            isCinemaDng = true,
            cinemaDngFolderUri = folderUri,
            cinemaDngFirstFrameUri = frame0,
            cinemaDngFrameUris = listOf(frame0),
            isRawVideo = true,
            rawVideoUri = rawVideo,
            mp4VideoUri = mp4
        )

        // Simulate deleteBatchImages with deleteMode == 3
        val deleteMode = DarkbagBatchDeleteSheet.MODE_CINEMADNG_ONLY
        val urisToDelete = mutableListOf<Uri>()
        if (deleteMode == DarkbagBatchDeleteSheet.MODE_CINEMADNG_ONLY) {
            group.cinemaDngFolderUri?.let { urisToDelete.add(it) }
            urisToDelete.addAll(group.allCinemaDngUris)
        }

        val distinctUris = urisToDelete.distinct()
        assertTrue(distinctUris.contains(folderUri))
        assertTrue(distinctUris.contains(frame0))
        assertFalse("RAWVID master must not be deleted in Mode 3", distinctUris.contains(rawVideo))
        assertFalse("Exported MP4 must not be deleted in Mode 3", distinctUris.contains(mp4))
    }
}
