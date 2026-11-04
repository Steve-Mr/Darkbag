package top.maary.darkbag.motionphoto

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * Utility to mux a [MotionPhotoSlice] and optional [MotionPhotoAudioSlice] into a standard MP4 file.
 */
object MotionPhotoMuxer {
    private const val TAG = "MotionPhotoMuxer"

    private data class PreparedTrackSample(
        val trackIndex: Int,
        val ptsUs: Long,
        val data: ByteArray,
        val flags: Int
    )

    /**
     * Muxes sliced encoded video frames and optional audio frames into a standalone MP4 file.
     *
     * @param outputFile The destination MP4 file.
     * @param mediaFormat The video format describing the video track (width, height, csd-0, csd-1, etc.).
     * @param slice The sliced video samples from [CircularVideoRingBuffer].
     * @param orientationDegrees Orientation hint to set on the MP4 file (0, 90, 180, 270).
     * @param audioFormat Optional audio format describing the AAC track.
     * @param audioSlice Optional sliced audio samples from [CircularAudioRingBuffer].
     * @return The exact presentation timestamp in microseconds of the still image in the resulting MP4, or null on error.
     */
    fun muxSliceToMp4(
        outputFile: File,
        mediaFormat: MediaFormat,
        slice: MotionPhotoSlice,
        orientationDegrees: Int = 0,
        audioFormat: MediaFormat? = null,
        audioSlice: MotionPhotoAudioSlice? = null
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

            val basePts = slice.samples.first().presentationTimeUs
            val preparedVideoSamples = mutableListOf<PreparedTrackSample>()

            // 1. Prepare video samples with relative PTS starting at 0
            var lastVideoPtsUs = -1L
            for (sample in slice.samples) {
                var relativePtsUs = (sample.presentationTimeUs - basePts).coerceAtLeast(0L)
                if (relativePtsUs <= lastVideoPtsUs) {
                    relativePtsUs = lastVideoPtsUs + 1000L
                }
                lastVideoPtsUs = relativePtsUs
                preparedVideoSamples.add(
                    PreparedTrackSample(
                        trackIndex = 0, // Placeholder, assigned after track creation
                        ptsUs = relativePtsUs,
                        data = sample.data,
                        flags = sample.flags
                    )
                )
            }

            // 2. Prepare audio samples if available
            val hasAudio = audioFormat != null && audioSlice != null && audioSlice.samples.isNotEmpty()
            val preparedAudioSamples = mutableListOf<PreparedTrackSample>()

            if (hasAudio) {
                var lastAudioPtsUs = -1L
                val minAudioPts = basePts - 25_000L
                for (sample in audioSlice!!.samples) {
                    if (sample.presentationTimeUs < minAudioPts) continue
                    var relativePtsUs = (sample.presentationTimeUs - basePts).coerceAtLeast(0L)
                    if (relativePtsUs <= lastAudioPtsUs) {
                        relativePtsUs = lastAudioPtsUs + 1000L
                    }
                    lastAudioPtsUs = relativePtsUs
                    preparedAudioSamples.add(
                        PreparedTrackSample(
                            trackIndex = 1, // Placeholder
                            ptsUs = relativePtsUs,
                            data = sample.data,
                            flags = sample.flags
                        )
                    )
                }
            }

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            if (orientationDegrees in listOf(0, 90, 180, 270)) {
                muxer.setOrientationHint(orientationDegrees)
            }

            val videoTrackIndex = muxer.addTrack(mediaFormat)
            val effectiveAudio = hasAudio && preparedAudioSamples.isNotEmpty()
            val audioTrackIndex = if (effectiveAudio) {
                // Ensure audioFormat has a valid, unconsumed csd-0 buffer
                val csdBytes = audioSlice?.csd0Buffer ?: run {
                    val buf = if (audioFormat!!.containsKey("csd-0")) audioFormat.getByteBuffer("csd-0") else null
                    buf?.let {
                        val dup = it.duplicate()
                        dup.rewind()
                        val bytes = ByteArray(dup.remaining())
                        dup.get(bytes)
                        bytes
                    }
                } ?: run {
                    // Fallback synthesis for AAC-LC AudioSpecificConfig (2 bytes)
                    val sampleRate = if (audioFormat!!.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    } else 48000
                    val channelCount = if (audioFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    } else 1
                    val freqIdx = when (sampleRate) {
                        96000 -> 0; 88200 -> 1; 64000 -> 2; 48000 -> 3; 44100 -> 4
                        32000 -> 5; 24000 -> 6; 22050 -> 7; 16000 -> 8; 12000 -> 9
                        11025 -> 10; 8000 -> 11; 7350 -> 12; else -> 3
                    }
                    val configVal = (2 shl 11) or (freqIdx shl 7) or (channelCount shl 3)
                    byteArrayOf(((configVal shr 8) and 0xFF).toByte(), (configVal and 0xFF).toByte())
                }
                audioFormat!!.setByteBuffer("csd-0", ByteBuffer.wrap(csdBytes))
                muxer.addTrack(audioFormat)
            } else {
                -1
            }

            muxer.start()

            // 3. Interleave samples across tracks in monotonic PTS order
            val finalSamples = if (effectiveAudio) {
                val remappedVideo = preparedVideoSamples.map { it.copy(trackIndex = videoTrackIndex) }
                val remappedAudio = preparedAudioSamples.map { it.copy(trackIndex = audioTrackIndex) }
                (remappedVideo + remappedAudio).sortedWith(
                    compareBy<PreparedTrackSample> { it.ptsUs }.thenBy { it.trackIndex }
                )
            } else {
                preparedVideoSamples.map { it.copy(trackIndex = videoTrackIndex) }
            }

            val bufferInfo = MediaCodec.BufferInfo()
            for (sample in finalSamples) {
                val byteBuffer = ByteBuffer.wrap(sample.data)
                bufferInfo.set(0, sample.data.size, sample.ptsUs, sample.flags)
                muxer.writeSampleData(sample.trackIndex, byteBuffer, bufferInfo)
            }

            muxer.stop()
            muxer.release()
            muxer = null

            val normalizedStillPtsUs = slice.stillPresentationTimestampUs
            Log.d(TAG, "Successfully muxed MP4: ${outputFile.length()} bytes (hasAudio=$effectiveAudio), duration=${slice.durationUs}us, stillPts=${normalizedStillPtsUs}us")
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
