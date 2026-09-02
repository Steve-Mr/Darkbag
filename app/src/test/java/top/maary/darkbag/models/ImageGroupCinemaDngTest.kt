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
class ImageGroupCinemaDngTest {

    @Test
    fun testCinemaDngBaseNameExtraction() {
        val cdngFrame1 = "DBAG_CDNG_20260901_220000_000000.dng"
        val cdngFrame2 = "DBAG_CDNG_20260901_220000_000123.dng"
        val rawvidFrame1 = "DBAG_RAWVID_20260901_220000_000000.dng"
        val cdngAudio = "DBAG_CDNG_20260901_220000.wav"
        val cdngSubIndex = "DBAG_CDNG_20260901_220000_1_000050.dng"

        assertEquals("CDNG_20260901_220000", ImageUtils.getBaseName(cdngFrame1))
        assertEquals("CDNG_20260901_220000", ImageUtils.getBaseName(cdngFrame2))
        assertEquals("RAWVID_20260901_220000", ImageUtils.getBaseName(rawvidFrame1))
        assertEquals("CDNG_20260901_220000", ImageUtils.getBaseName(cdngAudio))
        assertEquals("CDNG_20260901_220000_1", ImageUtils.getBaseName(cdngSubIndex))
    }

    @Test
    fun testCinemaDngImageGroupProperties() {
        val folderUri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADCIM/document/primary%3ADCIM%2FDBAG_CDNG_20260901_220000")
        val frame0 = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADCIM/document/primary%3ADCIM%2FDBAG_CDNG_20260901_220000%2FDBAG_CDNG_20260901_220000_000000.dng")
        val frame1 = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADCIM/document/primary%3ADCIM%2FDBAG_CDNG_20260901_220000%2FDBAG_CDNG_20260901_220000_000001.dng")
        val audioUri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADCIM/document/primary%3ADCIM%2FDBAG_CDNG_20260901_220000%2FDBAG_CDNG_20260901_220000.wav")
        val exportedMp4 = Uri.parse("content://media/external/video/media/501")

        val group = ImageGroup(
            baseName = "CDNG_20260901_220000",
            isCinemaDng = true,
            cinemaDngFolderUri = folderUri,
            cinemaDngFirstFrameUri = frame0,
            cinemaDngAudioUri = audioUri,
            cinemaDngFrameCount = 2,
            cinemaDngFrameUris = listOf(frame0, frame1),
            mp4VideoUri = exportedMp4
        )

        assertTrue(group.hasAny())
        assertTrue(group.hasMasterRaw)
        assertTrue(group.hasDerivatives)
        assertFalse(group.hasMultipleDerivatives)
        assertFalse(group.isSingleFormat())

        assertEquals(listOf(exportedMp4), group.allDerivativeUris)
        
        val masterUris = group.allMasterRawUris
        assertTrue(masterUris.contains(folderUri))
        assertTrue(masterUris.contains(frame0))
        assertTrue(masterUris.contains(frame1))
        assertTrue(masterUris.contains(audioUri))

        val allUris = group.allUris
        assertTrue(allUris.contains(folderUri))
        assertTrue(allUris.contains(frame0))
        assertTrue(allUris.contains(frame1))
        assertTrue(allUris.contains(audioUri))
        assertTrue(allUris.contains(exportedMp4))

        // When derivative MP4 exists, firstAvailableUri is the MP4 derivative
        assertEquals(exportedMp4, group.firstAvailableUri)

        // Without derivative, firstAvailableUri defaults to first frame or folder
        val rawOnlyGroup = group.copy(mp4VideoUri = null)
        assertEquals(frame0, rawOnlyGroup.firstAvailableUri)
    }

    @Test
    fun testCinemaDngWithoutFramesHasAnyAndMasterRaw() {
        val folderOnly = ImageGroup(
            baseName = "CDNG_20260901_220000",
            isCinemaDng = true,
            cinemaDngFolderUri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADCIM")
        )
        assertTrue(folderOnly.hasAny())
        assertTrue(folderOnly.hasMasterRaw)
        assertFalse(folderOnly.hasDerivatives)
        assertTrue(folderOnly.isSingleFormat())
    }

    @Test
    fun testFormatFrameBaseName() {
        assertEquals("DBAG_PHOTO_20260901_220000_f0", top.maary.darkbag.rawvideo.RawVideoExporter.formatFrameBaseName("RAWVID_20260901_220000", 0))
        assertEquals("DBAG_PHOTO_20260901_220000_f5", top.maary.darkbag.rawvideo.RawVideoExporter.formatFrameBaseName("DBAG_CDNG_20260901_220000", 5))
        assertEquals("DBAG_PHOTO_20260901_220000_f12", top.maary.darkbag.rawvideo.RawVideoExporter.formatFrameBaseName("20260901_220000", 12))
        assertEquals("DBAG_PHOTO_20260901_220000_f5", top.maary.darkbag.rawvideo.RawVideoExporter.formatFrameBaseName("DBAG_PHOTO_20260901_220000_f5", 5))
    }

    @Test
    fun testExportedSingleFrameDngAndGradedJpgPairing() {
        val baseName = top.maary.darkbag.rawvideo.RawVideoExporter.formatFrameBaseName("RAWVID_20260901_220000", 5)
        val dngFileName = "$baseName.dng"
        val gradedJpgFileName = "${baseName}_graded.jpg"

        val dngBase = ImageUtils.getBaseName(dngFileName)
        val jpgBase = ImageUtils.getBaseName(gradedJpgFileName)

        assertEquals("PHOTO_20260901_220000_f5", dngBase)
        assertEquals("PHOTO_20260901_220000_f5", jpgBase)
        assertEquals(dngBase, jpgBase)

        val dngUri = Uri.parse("content://media/external/images/media/101")
        val jpgUri = Uri.parse("content://media/external/images/media/102")

        val pairedGroup = ImageGroup(
            baseName = dngBase,
            dngUri = dngUri,
            jpgUri = jpgUri
        )

        assertTrue(pairedGroup.hasAny())
        assertTrue(pairedGroup.hasMasterRaw)
        assertTrue(pairedGroup.hasDerivatives)
        assertEquals(dngUri, pairedGroup.dngUri)
        assertEquals(jpgUri, pairedGroup.jpgUri)
    }

    @Test
    fun testDistinctMasterRawUriAccessors() {
        val cdngFolder = Uri.parse("content://dng/folder")
        val cdngFrame = Uri.parse("content://dng/frame0")
        val rawvidUri = Uri.parse("content://rawvid/video.rawvid")
        val photoDngUri = Uri.parse("content://photo/photo.dng")

        val mixedGroup = ImageGroup(
            baseName = "MIXED_TEST",
            isCinemaDng = true,
            cinemaDngFolderUri = cdngFolder,
            cinemaDngFirstFrameUri = cdngFrame,
            rawVideoUri = rawvidUri,
            dngUri = photoDngUri
        )

        assertFalse(mixedGroup.isSingleFormat())

        assertEquals(listOf(cdngFolder, cdngFrame), mixedGroup.allCinemaDngUris)
        assertEquals(listOf(rawvidUri), mixedGroup.rawVideoMasterUris)
        assertEquals(listOf(photoDngUri), mixedGroup.photoMasterUris)

        val allMaster = mixedGroup.allMasterRawUris
        assertTrue(allMaster.contains(cdngFolder))
        assertTrue(allMaster.contains(cdngFrame))
        assertTrue(allMaster.contains(rawvidUri))
        assertTrue(allMaster.contains(photoDngUri))
        assertEquals(4, allMaster.size)
    }

    @Test
    fun testRawVideoExportSheetModes() {
        assertEquals(0, top.maary.darkbag.fragments.RawVideoExportSheet.MODE_VIDEO)
        assertEquals(1, top.maary.darkbag.fragments.RawVideoExportSheet.MODE_SINGLE_FRAME_JPG)
        assertEquals(2, top.maary.darkbag.fragments.RawVideoExportSheet.MODE_SINGLE_FRAME_PAIR)

        val sheet = top.maary.darkbag.fragments.RawVideoExportSheet.newInstance(
            currentLog = "Log-C",
            currentLut = "film.cube",
            exportMode = top.maary.darkbag.fragments.RawVideoExportSheet.MODE_SINGLE_FRAME_JPG,
            frameIndex = 7
        )

        val args = sheet.arguments
        assertNotNull(args)
        assertEquals("Log-C", args!!.getString(top.maary.darkbag.fragments.RawVideoExportSheet.ARG_LOG))
        assertEquals("film.cube", args.getString(top.maary.darkbag.fragments.RawVideoExportSheet.ARG_LUT))
        assertEquals(top.maary.darkbag.fragments.RawVideoExportSheet.MODE_SINGLE_FRAME_JPG, args.getInt(top.maary.darkbag.fragments.RawVideoExportSheet.ARG_EXPORT_MODE))
        assertEquals(7, args.getInt(top.maary.darkbag.fragments.RawVideoExportSheet.ARG_FRAME_INDEX))
    }
}
