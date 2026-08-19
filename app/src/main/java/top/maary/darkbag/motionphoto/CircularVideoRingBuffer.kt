package top.maary.darkbag.motionphoto

import android.media.MediaCodec
import android.util.Log
import java.util.ArrayDeque

/**
 * Represents a single encoded video frame sample in memory.
 */
data class EncodedSample(
    val data: ByteArray,
    val presentationTimeUs: Long,
    val flags: Int
) {
    val isKeyFrame: Boolean
        get() = (flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0

    val isCodecConfig: Boolean
        get() = (flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as EncodedSample
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

data class MotionPhotoSlice(
    val spsPpsBuffers: List<ByteArray>,
    val samples: List<EncodedSample>,
    val stillPresentationTimestampUs: Long,
    val durationUs: Long
)

/**
 * Thread-safe circular ring buffer for storing encoded video frames (H.264/HEVC)
 * to support zero-lag pre-roll capturing for Motion Photos.
 */
class CircularVideoRingBuffer(
    val maxRetentionDurationUs: Long = 3_000_000L // 3.0 seconds window in microsecond
) {
    companion object {
        private const val TAG = "CircularVideoRingBuffer"
    }

    private val lock = Any()
    private val bufferQueue = ArrayDeque<EncodedSample>()
    private var spsPpsBuffers = mutableListOf<ByteArray>()

    fun setCodecConfig(buffer: ByteArray) {
        synchronized(lock) {
            spsPpsBuffers.clear()
            spsPpsBuffers.add(buffer.copyOf())
        }
    }

    fun addSample(data: ByteArray, presentationTimeUs: Long, flags: Int) {
        synchronized(lock) {
            val sample = EncodedSample(data.copyOf(), presentationTimeUs, flags)
            if (sample.isCodecConfig) {
                spsPpsBuffers.clear()
                spsPpsBuffers.add(sample.data)
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
     * Extracts a continuous slice of video frames surrounding [triggerTimestampUs].
     *
     * @param triggerTimestampUs Microsecond timestamp corresponding to the still image shutter.
     * @param preDurationUs Desired duration in microseconds before the shutter.
     * @param postDurationUs Desired duration in microseconds after the shutter.
     * @return [MotionPhotoSlice] or null if insufficient frames / no valid keyframe found.
     */
    fun slice(
        triggerTimestampUs: Long,
        preDurationUs: Long = 1_500_000L,
        postDurationUs: Long = 750_000L
    ): MotionPhotoSlice? {
        synchronized(lock) {
            if (bufferQueue.isEmpty()) {
                Log.w(TAG, "Cannot slice: buffer is empty")
                return null
            }

            val allSamples = bufferQueue.toList()
            if (allSamples.isEmpty()) return null

            val earliestSampleUs = allSamples.first().presentationTimeUs
            val latestSampleUs = allSamples.last().presentationTimeUs
            val effectiveTriggerUs = triggerTimestampUs.coerceIn(earliestSampleUs, latestSampleUs)

            val targetStartTimeUs = effectiveTriggerUs - preDurationUs
            val targetEndTimeUs = effectiveTriggerUs + postDurationUs

            // Find all samples up to targetEndTimeUs
            val validWindowSamples = allSamples.filter { it.presentationTimeUs <= targetEndTimeUs }.ifEmpty { allSamples }

            // Find the best keyframe before or closest to targetStartTimeUs
            var keyFrameIndex = -1
            for (i in validWindowSamples.indices) {
                val sample = validWindowSamples[i]
                if (sample.isKeyFrame) {
                    if (sample.presentationTimeUs <= targetStartTimeUs || keyFrameIndex == -1) {
                        keyFrameIndex = i
                    } else if (keyFrameIndex != -1 && validWindowSamples[keyFrameIndex].presentationTimeUs < targetStartTimeUs) {
                        if (sample.presentationTimeUs <= effectiveTriggerUs) {
                            keyFrameIndex = i
                        }
                    }
                }
            }

            if (keyFrameIndex == -1) {
                // Fallback: search backwards for the first available keyframe in the buffer
                keyFrameIndex = validWindowSamples.indexOfFirst { it.isKeyFrame }
            }

            if (keyFrameIndex == -1) {
                Log.w(TAG, "No keyframe found in ring buffer window")
                return null
            }

            val slicedSamples = validWindowSamples.subList(keyFrameIndex, validWindowSamples.size)
            if (slicedSamples.isEmpty()) return null

            val firstPts = slicedSamples.first().presentationTimeUs
            val lastPts = slicedSamples.last().presentationTimeUs
            val durationUs = (lastPts - firstPts).coerceAtLeast(0L)
            val stillPtsOffsetUs = (effectiveTriggerUs - firstPts).coerceIn(0L, durationUs)

            return MotionPhotoSlice(
                spsPpsBuffers = spsPpsBuffers.toList(),
                samples = slicedSamples,
                stillPresentationTimestampUs = stillPtsOffsetUs,
                durationUs = durationUs
            )
        }
    }

    fun clear() {
        synchronized(lock) {
            bufferQueue.clear()
            spsPpsBuffers.clear()
        }
    }
}
