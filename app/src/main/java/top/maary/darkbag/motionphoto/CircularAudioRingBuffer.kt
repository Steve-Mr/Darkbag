package top.maary.darkbag.motionphoto

import android.media.MediaCodec
import android.util.Log
import java.util.ArrayDeque

/**
 * Represents a single encoded AAC audio sample in memory.
 */
data class EncodedAudioSample(
    val data: ByteArray,
    val presentationTimeUs: Long,
    val flags: Int
) {
    val isCodecConfig: Boolean
        get() = (flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as EncodedAudioSample
        if (!data.contentEquals(other.data)) return false
        if (presentationTimeUs != other.presentationTimeUs) return false
        if (flags != other.flags) return false
        return true
    }

    override fun hashCode(): Int {
        var result = data.contentHashCode()
        result = 31 * result + presentationTimeUs.hashCode()
        result = 31 * result + flags
        return result
    }
}

/**
 * Sliced continuous audio window matching the video slice.
 */
data class MotionPhotoAudioSlice(
    val csd0Buffer: ByteArray?,
    val samples: List<EncodedAudioSample>,
    val startPtsUs: Long,
    val endPtsUs: Long
)

/**
 * Thread-safe circular ring buffer for storing encoded AAC audio frames
 * to support zero-lag pre-roll capturing for Motion Photos with sound.
 */
class CircularAudioRingBuffer(
    val maxRetentionDurationUs: Long = 3_500_000L // 3.5 seconds retention
) {
    companion object {
        private const val TAG = "CircularAudioRingBuffer"
    }

    private val lock = Any()
    private val bufferQueue = ArrayDeque<EncodedAudioSample>()
    private var csd0Buffer: ByteArray? = null

    fun setCodecConfig(buffer: ByteArray) {
        synchronized(lock) {
            csd0Buffer = buffer.copyOf()
        }
    }

    fun addSample(data: ByteArray, presentationTimeUs: Long, flags: Int) {
        synchronized(lock) {
            val sample = EncodedAudioSample(data.copyOf(), presentationTimeUs, flags)
            if (sample.isCodecConfig) {
                csd0Buffer = sample.data
                return
            }

            bufferQueue.addLast(sample)
            pruneOldSamplesLocked(presentationTimeUs)
        }
    }

    private fun pruneOldSamplesLocked(currentTimestampUs: Long) {
        val minAllowedTimeUs = currentTimestampUs - maxRetentionDurationUs
        while (bufferQueue.isNotEmpty()) {
            val first = bufferQueue.peekFirst() ?: break
            if (first.presentationTimeUs < minAllowedTimeUs) {
                bufferQueue.removeFirst()
            } else {
                break
            }
        }
    }

    /**
     * Extracts all audio samples whose presentation time falls into (startTimeUs..endTimeUs).
     *
     * @param startTimeUs Start timestamp of the video slice in microseconds.
     * @param endTimeUs End timestamp of the video slice in microseconds.
     * @return [MotionPhotoAudioSlice] or null if no samples in range.
     */
    fun slice(startTimeUs: Long, endTimeUs: Long): MotionPhotoAudioSlice? {
        synchronized(lock) {
            if (bufferQueue.isEmpty()) {
                Log.w(TAG, "Cannot slice audio: buffer is empty")
                return null
            }

            val allSamples = bufferQueue.toList()
            val sliced = allSamples.filter { it.presentationTimeUs in startTimeUs..endTimeUs }
            if (sliced.isEmpty()) {
                // If exact range has no samples, find nearest overlapping samples to avoid cutting sound completely
                val nearest = allSamples.filter { it.presentationTimeUs in (startTimeUs - 50_000L)..(endTimeUs + 50_000L) }
                if (nearest.isEmpty()) return null
                return MotionPhotoAudioSlice(
                    csd0Buffer = csd0Buffer?.copyOf(),
                    samples = nearest,
                    startPtsUs = nearest.first().presentationTimeUs,
                    endPtsUs = nearest.last().presentationTimeUs
                )
            }

            return MotionPhotoAudioSlice(
                csd0Buffer = csd0Buffer?.copyOf(),
                samples = sliced,
                startPtsUs = sliced.first().presentationTimeUs,
                endPtsUs = sliced.last().presentationTimeUs
            )
        }
    }

    fun clear() {
        synchronized(lock) {
            bufferQueue.clear()
            csd0Buffer = null
        }
    }
}
