package top.maary.darkbag.motionphoto

import android.media.MediaFormat
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class MotionPhotoMuxerTest {

    @Test
    fun testEmptySliceReturnsNull() {
        val tempFile = File.createTempFile("test_muxer_empty", ".mp4")
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1080, 1440)
        val emptySlice = MotionPhotoSlice(
            spsPpsBuffers = emptyList(),
            samples = emptyList(),
            stillPresentationTimestampUs = 0L,
            durationUs = 0L
        )

        val result = MotionPhotoMuxer.muxSliceToMp4(
            outputFile = tempFile,
            mediaFormat = format,
            slice = emptySlice
        )

        assertNull("Empty slice should return null without crashing", result)
        tempFile.delete()
    }
}
