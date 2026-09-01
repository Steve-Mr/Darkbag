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
}
