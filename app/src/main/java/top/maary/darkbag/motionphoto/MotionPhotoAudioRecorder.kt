package top.maary.darkbag.motionphoto

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Background audio recorder and AAC encoder providing a continuous sliding window
 * of encoded audio for Motion Photos.
 *
 * Strictly synchronized with System.nanoTime() to align with video surface timestamps.
 */
class MotionPhotoAudioRecorder(
    private val sampleRate: Int = 48000,
    private val channelCount: Int = 1,
    private val bitRate: Int = 128_000,
    val ringBuffer: CircularAudioRingBuffer = CircularAudioRingBuffer()
) {
    companion object {
        private const val TAG = "MotionPhotoAudioRec"
        private const val MIME_TYPE = MediaFormat.MIMETYPE_AUDIO_AAC
    }

    private var audioRecord: AudioRecord? = null
    private var mediaCodec: MediaCodec? = null
    private var outputMediaFormat: MediaFormat? = null

    private var workerThread: Thread? = null
    private val isRunning = AtomicBoolean(false)

    val isRecording: Boolean
        get() = isRunning.get()

    val mediaFormat: MediaFormat?
        get() = outputMediaFormat

    /**
     * Starts continuous audio capture and encoding.
     * Degrades gracefully (returns false) if permissions are missing or audio hardware is busy.
     */
    @SuppressLint("MissingPermission")
    @Synchronized
    fun start(): Boolean {
        if (isRunning.get()) return true

        val channelConfig = if (channelCount == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, pcmEncoding)
        if (minBufferSize <= 0) {
            Log.w(TAG, "AudioRecord parameter invalid: minBufferSize=$minBufferSize")
            return false
        }

        val bufferSize = (minBufferSize * 2).coerceAtLeast(4096)

        // 1. Initialize AudioRecord
        var record: AudioRecord? = null
        try {
            record = AudioRecord(
                MediaRecorder.AudioSource.CAMCORDER,
                sampleRate,
                channelConfig,
                pcmEncoding,
                bufferSize
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to init CAMCORDER source, trying MIC fallback", e)
        }

        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            try {
                record?.release()
                record = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelConfig,
                    pcmEncoding,
                    bufferSize
                )
            } catch (e2: Exception) {
                Log.w(TAG, "Failed to init MIC audio source", e2)
                record?.release()
                return false
            }
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "AudioRecord failed to initialize")
            record.release()
            return false
        }

        // 2. Initialize AAC Encoder
        var codec: MediaCodec? = null
        try {
            val format = MediaFormat.createAudioFormat(MIME_TYPE, sampleRate, channelCount).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, bufferSize)
            }
            codec = MediaCodec.createEncoderByType(MIME_TYPE).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to configure MediaCodec AAC encoder", e)
            record.release()
            codec?.release()
            return false
        }

        try {
            record.startRecording()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start AudioRecord", e)
            record.release()
            codec.stop()
            codec.release()
            return false
        }

        audioRecord = record
        mediaCodec = codec
        outputMediaFormat = codec.outputFormat

        isRunning.set(true)
        ringBuffer.clear()

        workerThread = Thread({
            runRecordLoop(bufferSize, record, codec)
        }, "MotionPhotoAudioThread").apply {
            start()
        }

        Log.i(TAG, "MotionPhotoAudioRecorder started successfully ($sampleRate Hz, ${channelCount}ch, ${bitRate / 1000}kbps)")
        return true
    }

    private fun runRecordLoop(bufferSize: Int, record: AudioRecord, codec: MediaCodec) {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
        val pcmBuffer = ByteArray(bufferSize)
        val bytesPerSample = 2 * channelCount
        var totalSamplesWritten = 0L
        val anchorNanoTime = System.nanoTime()

        val bufferInfo = MediaCodec.BufferInfo()

        while (isRunning.get()) {
            try {
                val readBytes = record.read(pcmBuffer, 0, bufferSize)
                if (readBytes > 0) {
                    val samples = readBytes / bytesPerSample
                    val ptsUs = (anchorNanoTime / 1000L) + (totalSamplesWritten * 1_000_000L / sampleRate)

                    // Feed PCM to encoder
                    val inputBufferIndex = codec.dequeueInputBuffer(5_000L)
                    if (inputBufferIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputBufferIndex)
                        if (inputBuffer != null) {
                            inputBuffer.clear()
                            inputBuffer.put(pcmBuffer, 0, readBytes)
                            codec.queueInputBuffer(inputBufferIndex, 0, readBytes, ptsUs, 0)
                            totalSamplesWritten += samples
                        }
                    }
                }

                // Drain encoded AAC buffers
                while (isRunning.get()) {
                    val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
                    if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        break
                    } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        outputMediaFormat = codec.outputFormat
                        val csd0 = outputMediaFormat?.getByteBuffer("csd-0")
                        if (csd0 != null) {
                            val csdBytes = ByteArray(csd0.remaining())
                            csd0.get(csdBytes)
                            ringBuffer.setCodecConfig(csdBytes)
                        }
                    } else if (outputBufferIndex >= 0) {
                        val encodedBuffer = codec.getOutputBuffer(outputBufferIndex)
                        if (encodedBuffer != null) {
                            encodedBuffer.position(bufferInfo.offset)
                            encodedBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            val outData = ByteArray(bufferInfo.size)
                            encodedBuffer.get(outData)

                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                ringBuffer.setCodecConfig(outData)
                            } else if (bufferInfo.size > 0) {
                                ringBuffer.addSample(outData, bufferInfo.presentationTimeUs, bufferInfo.flags)
                            }
                        }
                        codec.releaseOutputBuffer(outputBufferIndex, false)
                    }
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.w(TAG, "Exception in MotionPhotoAudio loop", e)
                }
                break
            }
        }
    }

    /**
     * Stops audio recording and releases hardware and codec resources immediately.
     */
    @Synchronized
    fun stop() {
        if (!isRunning.getAndSet(false)) return

        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping AudioRecord", e)
        }

        try {
            workerThread?.interrupt()
            workerThread?.join(500)
        } catch (_: Exception) {}
        workerThread = null

        try {
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null

        try {
            mediaCodec?.stop()
            mediaCodec?.release()
        } catch (_: Exception) {}
        mediaCodec = null

        ringBuffer.clear()
        Log.i(TAG, "MotionPhotoAudioRecorder stopped")
    }
}
