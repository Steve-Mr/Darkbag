package top.maary.darkbag.rawvideo

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

class AudioRecorder(
    private val sampleRate: Int = 48000,
    private val channelConfig: Int = AudioFormat.CHANNEL_IN_MONO,
    private val audioFormat: Int = AudioFormat.ENCODING_PCM_16BIT,
    private val onAudioData: (buffer: ByteBuffer, size: Int, timestampNs: Long, sampleCount: Int) -> Unit
) {
    companion object {
        private const val TAG = "AudioRecorder"
    }

    private var audioRecord: AudioRecord? = null
    private val isRecording = AtomicBoolean(false)
    private var recordingThread: Thread? = null
    private var bufferSize: Int = 0

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (isRecording.get()) return true

        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "Invalid audio parameters for AudioRecord")
            return false
        }

        bufferSize = (minBufferSize * 2).coerceAtLeast(4096)
        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.CAMCORDER,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )
        } catch (e: Exception) {
            Log.w(TAG, "CAMCORDER source failed, falling back to MIC", e)
            try {
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize
                )
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to initialize AudioRecord", e2)
                return false
            }
        }

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord not initialized")
            audioRecord?.release()
            audioRecord = null
            return false
        }

        try {
            audioRecord?.startRecording()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AudioRecord", e)
            audioRecord?.release()
            audioRecord = null
            return false
        }

        isRecording.set(true)
        recordingThread = Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            val directBuffer = ByteBuffer.allocateDirect(bufferSize).order(ByteOrder.nativeOrder())
            val bytesPerSample = if (audioFormat == AudioFormat.ENCODING_PCM_16BIT) 2 else 1
            val channelCount = if (channelConfig == AudioFormat.CHANNEL_IN_STEREO) 2 else 1

            while (isRecording.get()) {
                directBuffer.clear()
                val readBytes = audioRecord?.read(directBuffer, bufferSize) ?: -1
                if (readBytes > 0) {
                    val timestampNs = android.os.SystemClock.elapsedRealtimeNanos()
                    val sampleCount = readBytes / (bytesPerSample * channelCount)
                    onAudioData(directBuffer, readBytes, timestampNs, sampleCount)
                }
            }
        }, "RawVideoAudioRecorderThread").apply { start() }

        Log.i(TAG, "AudioRecorder started ($sampleRate Hz)")
        return true
    }

    fun stop() {
        if (!isRecording.getAndSet(false)) return

        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping AudioRecord", e)
        }

        recordingThread?.join(1000)
        recordingThread = null

        audioRecord?.release()
        audioRecord = null
        Log.i(TAG, "AudioRecorder stopped")
    }
}
