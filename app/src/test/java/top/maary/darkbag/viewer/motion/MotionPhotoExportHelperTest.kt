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

    @Test
    fun testMotionStudioAspectRatioAndFitting() {
        // Portrait 3:4 video stored as 1440x1080 with 90deg rotation -> 0.75 display aspect ratio
        val portraitRatio = MotionPhotoStudioSheet.computeDisplayAspectRatio(1440, 1080, 90)
        assertEquals(0.75f, portraitRatio, 0.001f)

        // Container width 900px: 900 / 0.75 = 1200px -> clamped to maxHeight 1020px
        val stageH = MotionPhotoStudioSheet.computePreviewContainerHeightPx(900, portraitRatio, 540, 1020)
        assertEquals(1020, stageH)

        // Video fitted inside 900x1020 at 0.75 ratio -> height-limited: 765 x 1020
        val (fitW, fitH) = MotionPhotoStudioSheet.computeFittedVideoSize(900, stageH, portraitRatio)
        assertEquals(765, fitW)
        assertEquals(1020, fitH)

        // Landscape 16:9 video -> 1920x1080 with 0deg rotation -> 1.777
        val landscapeRatio = MotionPhotoStudioSheet.computeDisplayAspectRatio(1920, 1080, 0)
        assertEquals(16f / 9f, landscapeRatio, 0.001f)
        val landH = MotionPhotoStudioSheet.computePreviewContainerHeightPx(900, landscapeRatio, 450, 1020)
        assertEquals(506, landH)
    }
}
