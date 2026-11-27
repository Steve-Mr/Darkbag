package top.maary.darkbag.viewer.motion

import org.junit.Assert.assertEquals
import org.junit.Test

class MotionPhotoExportHelperTest {

    @Test
    fun testFormatRelativeTime() {
        assertEquals("0.00s", MotionPhotoExportHelper.formatRelativeTime(1_000_000L, 1_000_000L))
        assertEquals("-0.42s", MotionPhotoExportHelper.formatRelativeTime(580_000L, 1_000_000L))
        assertEquals("+0.25s", MotionPhotoExportHelper.formatRelativeTime(1_250_000L, 1_000_000L))
    }

    @Test
    fun testBuildFileNames() {
        assertEquals("IMG_001_frame_450ms.jpg", MotionPhotoExportHelper.buildFrameFileName("IMG_001", 450L))
        assertEquals("IMG_001_motion.mp4", MotionPhotoExportHelper.buildVideoFileName("IMG_001"))
        assertEquals("Darkbag_motion.mp4", MotionPhotoExportHelper.buildVideoFileName(""))
    }
}
