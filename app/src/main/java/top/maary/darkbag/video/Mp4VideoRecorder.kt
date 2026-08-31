package top.maary.darkbag.video

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import android.view.Surface
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class Mp4VideoRecorder(private val context: Context) {
    companion object {
        private const val TAG = "Mp4VideoRecorder"
    }

    private var mediaRecorder: MediaRecorder? = null
    private val isRecording = AtomicBoolean(false)
    private var currentOutputFile: File? = null
    private var startTimestampMs: Long = 0L

    val recording: Boolean
        get() = isRecording.get()

    val surface: Surface?
        get() = mediaRecorder?.surface

    data class RecordingResult(
        val file: File,
        val durationMs: Long
    )

    fun prepare(outputFile: File, width: Int = 1920, height: Int = 1080, fps: Int = 30): Surface? {
        if (isRecording.get()) return null

        try {
            outputFile.parentFile?.mkdirs()
            currentOutputFile = outputFile

            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            recorder.setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setOutputFile(outputFile.absolutePath)
            recorder.setVideoEncodingBitRate(15_000_000)
            recorder.setVideoFrameRate(fps)
            recorder.setVideoSize(width, height)
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioEncodingBitRate(128_000)
            recorder.setAudioSamplingRate(48_000)
            recorder.prepare()

            mediaRecorder = recorder
            return recorder.surface
        } catch (e: Exception) {
            Log.e(TAG, "Failed to prepare MediaRecorder", e)
            release()
            return null
        }
    }

    fun start(): Boolean {
        if (mediaRecorder == null || isRecording.get()) return false
        try {
            mediaRecorder?.start()
            startTimestampMs = System.currentTimeMillis()
            isRecording.set(true)
            Log.i(TAG, "Mp4VideoRecorder started")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start MediaRecorder", e)
            release()
            return false
        }
    }

    fun stop(): RecordingResult? {
        if (!isRecording.getAndSet(false)) {
            release()
            return null
        }

        val duration = System.currentTimeMillis() - startTimestampMs
        val file = currentOutputFile

        try {
            mediaRecorder?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping MediaRecorder (clip may be too short)", e)
        } finally {
            release()
        }

        if (file != null && file.exists() && file.length() > 0) {
            Log.i(TAG, "Recorded MP4 video: ${file.absolutePath} ($duration ms)")
            return RecordingResult(file, duration)
        }
        return null
    }

    fun release() {
        isRecording.set(false)
        try {
            mediaRecorder?.reset()
            mediaRecorder?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing MediaRecorder", e)
        }
        mediaRecorder = null
        currentOutputFile = null
    }
}
