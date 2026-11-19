package top.maary.darkbag.rawvideo

import android.media.MediaCodec
import android.media.MediaMuxer
import java.nio.ByteBuffer

internal class AudioVideoMuxerSession(
    val videoEncoder: MediaCodec,
    private val audioEncoder: MediaCodec?,
    private val muxer: MediaMuxer,
    private val audioDirectBuf: ByteBuffer,
    hasInitialAudio: Boolean,
    audioChannels: Int,
    private val audioSampleRate: Int
) {
    internal data class DrainResult(val isEos: Boolean, val hadOutput: Boolean)

    var muxerStarted: Boolean = false
        private set
    private var videoTrackIndex = -1
    private var audioTrackIndex = -1
    private val effectiveHasAudio = audioEncoder != null
    private var audioPacketIndex = if (hasInitialAudio) 1 else 0
    private var noMoreAudioPackets = !hasInitialAudio
    private var audioPtsUs = 0L
    private val bytesPerAudioSample = audioChannels * 2
    private var audioEosQueued = false
    private val audioBufferInfo = MediaCodec.BufferInfo()
    private val videoBufferInfo = MediaCodec.BufferInfo()
    private val pendingMuxerSamples = mutableListOf<RawVideoExporter.PendingMuxerSample>()

    fun pumpDuringFrame(nativeHandle: Long) {
        if (effectiveHasAudio) {
            feedAudio(nativeHandle)
            drainAudio()
        }
        drainVideo()
    }

    fun flushPendingSamples() {
        for (sample in pendingMuxerSamples) {
            val track = if (sample.isAudio) audioTrackIndex else videoTrackIndex
            if (track >= 0) {
                muxer.writeSampleData(track, ByteBuffer.wrap(sample.data), sample.info)
            }
        }
        pendingMuxerSamples.clear()
    }

    fun checkStartMuxer() {
        if (!muxerStarted && videoTrackIndex >= 0 && (!effectiveHasAudio || audioTrackIndex >= 0)) {
            muxer.start()
            muxerStarted = true
            flushPendingSamples()
        }
    }

    fun drainAudio(timeoutUs: Long = 0): DrainResult {
        val enc = audioEncoder ?: return DrainResult(isEos = true, hadOutput = false)
        return drainEncoder(
            encoder = enc,
            bufferInfo = audioBufferInfo,
            timeoutUs = timeoutUs,
            isAudio = true
        )
    }

    fun drainVideo(timeoutUs: Long = 0): DrainResult = drainEncoder(
        encoder = videoEncoder,
        bufferInfo = videoBufferInfo,
        timeoutUs = timeoutUs,
        isAudio = false
    )

    private fun drainEncoder(
        encoder: MediaCodec,
        bufferInfo: MediaCodec.BufferInfo,
        timeoutUs: Long,
        isAudio: Boolean
    ): DrainResult {
        var isEos = false
        var hadOutput = false
        while (true) {
            val outIdx = encoder.dequeueOutputBuffer(bufferInfo, timeoutUs)
            if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val track = muxer.addTrack(encoder.outputFormat)
                if (isAudio) audioTrackIndex = track else videoTrackIndex = track
                checkStartMuxer()
                hadOutput = true
            } else if (outIdx >= 0) {
                hadOutput = true
                val encodedData = encoder.getOutputBuffer(outIdx)
                val trackIndex = if (isAudio) audioTrackIndex else videoTrackIndex
                if (encodedData != null && bufferInfo.size > 0) {
                    if (muxerStarted && trackIndex >= 0) {
                        encodedData.position(bufferInfo.offset)
                        encodedData.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(trackIndex, encodedData, bufferInfo)
                    } else {
                        val bytes = ByteArray(bufferInfo.size)
                        encodedData.position(bufferInfo.offset)
                        encodedData.get(bytes)
                        val copyInfo = MediaCodec.BufferInfo().apply {
                            set(0, bytes.size, bufferInfo.presentationTimeUs, bufferInfo.flags)
                        }
                        pendingMuxerSamples.add(RawVideoExporter.PendingMuxerSample(isAudio, bytes, copyInfo))
                    }
                }
                val isLastBuffer = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                if (isLastBuffer) isEos = true
                encoder.releaseOutputBuffer(outIdx, false)
                if (isLastBuffer) break
            } else {
                break
            }
        }
        return DrainResult(isEos, hadOutput)
    }

    fun feedAudio(nativeHandle: Long) {
        val aEnc = audioEncoder ?: return
        while (!audioEosQueued) {
            if (!audioDirectBuf.hasRemaining() && !noMoreAudioPackets) {
                audioDirectBuf.clear()
                val read = RawVideoNative.nativeReadAudioPacket(nativeHandle, audioPacketIndex, audioDirectBuf)
                if (read > 0) {
                    audioDirectBuf.position(0)
                    audioDirectBuf.limit(read)
                    audioPacketIndex++
                } else {
                    noMoreAudioPackets = true
                    audioDirectBuf.position(0)
                    audioDirectBuf.limit(0)
                }
            }

            val inIdx = aEnc.dequeueInputBuffer(0)
            if (inIdx < 0) break

            val inBuf = aEnc.getInputBuffer(inIdx)
            if (audioDirectBuf.hasRemaining() && inBuf != null) {
                inBuf.clear()
                val toWrite = minOf(audioDirectBuf.remaining(), inBuf.remaining())
                val chunkBytes = (toWrite / bytesPerAudioSample) * bytesPerAudioSample
                if (chunkBytes > 0) {
                    val oldLimit = audioDirectBuf.limit()
                    audioDirectBuf.limit(audioDirectBuf.position() + chunkBytes)
                    inBuf.put(audioDirectBuf)
                    audioDirectBuf.limit(oldLimit)
                    aEnc.queueInputBuffer(inIdx, 0, chunkBytes, audioPtsUs, 0)
                    val sampleCount = chunkBytes / bytesPerAudioSample
                    audioPtsUs += (sampleCount * 1_000_000L) / audioSampleRate
                } else {
                    aEnc.queueInputBuffer(inIdx, 0, 0, audioPtsUs, 0)
                    break
                }
            } else if (noMoreAudioPackets && !audioDirectBuf.hasRemaining()) {
                aEnc.queueInputBuffer(inIdx, 0, 0, audioPtsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                audioEosQueued = true
                break
            } else {
                aEnc.queueInputBuffer(inIdx, 0, 0, audioPtsUs, 0)
                break
            }
        }
    }

    fun drainAudioEos(nativeHandle: Long, isCancelled: (() -> Boolean)?): Boolean {
        if (!effectiveHasAudio || audioEosQueued) return true
        if (isCancelled?.invoke() == true) return false
        var retries = 0
        while (!audioEosQueued && retries < 100) {
            feedAudio(nativeHandle)
            val r = drainAudio(timeoutUs = 10000)
            if (!r.hadOutput && !audioEosQueued) retries++ else retries = 0
        }
        if (!audioEosQueued) {
            audioEncoder?.let { aEnc ->
                val inIdx = aEnc.dequeueInputBuffer(10000)
                if (inIdx >= 0) {
                    aEnc.queueInputBuffer(inIdx, 0, 0, audioPtsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    audioEosQueued = true
                }
            }
        }
        return true
    }

    fun drainRemainingStreams(isCancelled: (() -> Boolean)?): Pair<Boolean, Boolean> {
        var videoEos = false
        var audioEos = !effectiveHasAudio
        var idleLoops = 0
        while ((!videoEos || !audioEos) && idleLoops < 100) {
            if (isCancelled?.invoke() == true) return false to videoEos
            var activity = false
            if (!videoEos) {
                val r = drainVideo(timeoutUs = 10000)
                if (r.isEos) videoEos = true
                if (r.hadOutput) activity = true
            }
            if (!audioEos) {
                val r = drainAudio(timeoutUs = 10000)
                if (r.isEos) audioEos = true
                if (r.hadOutput) activity = true
            }
            if (activity) idleLoops = 0 else idleLoops++
        }
        return true to videoEos
    }

    fun release() {
        try { videoEncoder.stop() } catch (_: Exception) {}
        try { videoEncoder.release() } catch (_: Exception) {}
        try { audioEncoder?.stop() } catch (_: Exception) {}
        try { audioEncoder?.release() } catch (_: Exception) {}
        try { if (muxerStarted) muxer.stop() } catch (_: Exception) {}
        try { muxer.release() } catch (_: Exception) {}
    }
}
