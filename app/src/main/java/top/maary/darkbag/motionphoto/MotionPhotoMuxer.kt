package top.maary.darkbag.motionphoto

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * Utility to mux a [MotionPhotoSlice] into a standard MP4 file.
 */
object MotionPhotoMuxer {
    private const val TAG = "MotionPhotoMuxer"

    /**
     * Muxes sliced encoded video frames into a standalone MP4 file.
     *
     * @param outputFile The destination MP4 file.
     * @param mediaFormat The video format describing the track (width, height, csd-0, csd-1, etc.).
     * @param slice The sliced video samples from [CircularVideoRingBuffer].
     * @return The exact presentation timestamp in microseconds of the still image in the resulting MP4, or null on error.
     */
    fun muxSliceToMp4(
        outputFile: File,
        mediaFormat: MediaFormat,
        slice: MotionPhotoSlice,
        orientationDegrees: Int = 0
    ): Long? {
        if (slice.samples.isEmpty()) {
            Log.e(TAG, "Cannot mux empty slice")
            return null
        }

        var muxer: MediaMuxer? = null
        try {
            if (outputFile.exists()) {
                outputFile.delete()
            }
            outputFile.parentFile?.mkdirs()

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            if (orientationDegrees in listOf(0, 90, 180, 270)) {
                muxer.setOrientationHint(orientationDegrees)
            }
            val trackIndex = muxer.addTrack(mediaFormat)
            muxer.start()

            val basePts = slice.samples.first().presentationTimeUs
            val bufferInfo = MediaCodec.BufferInfo()

            for (sample in slice.samples) {
                val byteBuffer = ByteBuffer.wrap(sample.data)
                // Normalize PTS so that the first sample starts at 0us
                val relativePtsUs = (sample.presentationTimeUs - basePts).coerceAtLeast(0L)

                bufferInfo.set(
                    0,
                    sample.data.size,
                    relativePtsUs,
                    sample.flags
                )

                muxer.writeSampleData(trackIndex, byteBuffer, bufferInfo)
            }

            muxer.stop()
            muxer.release()
            muxer = null

            val normalizedStillPtsUs = slice.stillPresentationTimestampUs
            Log.d(TAG, "Successfully muxed MP4: ${outputFile.length()} bytes, duration=${slice.durationUs}us, stillPts=${normalizedStillPtsUs}us")
            return normalizedStillPtsUs
        } catch (e: Exception) {
            Log.e(TAG, "Failed to mux Motion Photo MP4", e)
            try {
                muxer?.release()
            } catch (_: Exception) {}
            if (outputFile.exists()) {
                outputFile.delete()
            }
            return null
        }
    }
}
