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

    @Test
    fun testSingleItemDeleteMultiComponentConditions() {
        val dngUri = Uri.parse("content://media/external/images/media/200")
        val jpgUri = Uri.parse("content://media/external/images/media/201")
        val photoGroup = ImageGroup(
            baseName = "PHOTO_20260901_220000",
            dngUri = dngUri,
            jpgUri = jpgUri
        )

        val hasPhotoDng = photoGroup.dngUri != null || photoGroup.dngUri1 != null || photoGroup.dngUri2 != null || photoGroup.multiDngUris.isNotEmpty()
        val hasRawVid = photoGroup.isRawVideo || photoGroup.rawVideoUri != null
        val hasRaw = hasPhotoDng || hasRawVid
        val hasDerivatives = photoGroup.allDerivativeUris.isNotEmpty()
        val hasCdng = photoGroup.isCinemaDng || photoGroup.cinemaDngFolderUri != null || photoGroup.cinemaDngFirstFrameUri != null || photoGroup.cinemaDngFrameUris.isNotEmpty()

        val componentCount = (if (hasRaw) 1 else 0) + (if (hasDerivatives) 1 else 0) + (if (hasCdng) 1 else 0)
        assertTrue("Photo with RAW + JPG must be multi-component", componentCount > 1)

        val singleJpgGroup = ImageGroup(
            baseName = "PHOTO_JPG_ONLY",
            jpgUri = jpgUri
        )
        val singleComponentCount = (if (singleJpgGroup.dngUri != null) 1 else 0) + (if (singleJpgGroup.allDerivativeUris.isNotEmpty()) 1 else 0)
        assertEquals("Single format JPG should have component count 1", 1, singleComponentCount)
    }

    @Test
    fun testSingleItemDeleteExecutionModes() {
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

        // Mode 0: Entire Group
        val urisMode0 = mutableListOf<Uri>()
        urisMode0.addAll(group.allUris)
        group.cinemaDngFolderUri?.let { urisMode0.add(it) }
        assertTrue(urisMode0.contains(rawVideo))
        assertTrue(urisMode0.contains(mp4))
        assertTrue(urisMode0.contains(frame0))
        assertTrue(urisMode0.contains(folderUri))

        // Mode 1: RAW only (CinemaDNG + RAWVID)
        val urisMode1 = mutableListOf<Uri>()
        if (group.isCinemaDng || group.cinemaDngFolderUri != null) {
            group.cinemaDngFolderUri?.let { urisMode1.add(it) }
        }
        urisMode1.addAll(group.allMasterRawUris)
        assertTrue(urisMode1.contains(rawVideo))
        assertTrue(urisMode1.contains(frame0))
        assertFalse("Mode 1 must preserve exported derivatives", urisMode1.contains(mp4))

        // Mode 2: Derivatives only
        val urisMode2 = mutableListOf<Uri>()
        urisMode2.addAll(group.allDerivativeUris)
        assertTrue(urisMode2.contains(mp4))
        assertFalse(urisMode2.contains(rawVideo))
        assertFalse(urisMode2.contains(frame0))
    }
}
