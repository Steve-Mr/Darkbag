package top.maary.darkbag.motionphoto

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.*
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hardware accelerated video encoder and audio recorder managing the continuous
 * encoding pipeline and circular buffers for Motion Photo recording.
 */
class MotionPhotoEncoder(
    private val width: Int = 1080,
    private val height: Int = 1440,
    private val frameRate: Int = 30,
    private val bitRate: Int = 10_000_000, // 10 Mbps
    var isAudioEnabled: Boolean = false
) {
    companion object {
        private const val TAG = "MotionPhotoEncoder"
        private const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val I_FRAME_INTERVAL = 1 // 1 second between keyframes
    }

    private val ringBuffer = CircularVideoRingBuffer(maxRetentionDurationUs = 3_500_000L)
    private var mediaCodec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var mediaFormat: MediaFormat? = null

    private var audioRecorder: MotionPhotoAudioRecorder? = null

    private var encoderThread: HandlerThread? = null
    private var encoderHandler: Handler? = null

    private val isRunning = AtomicBoolean(false)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    val surface: Surface?
        get() = inputSurface

    val isEncoding: Boolean
        get() = isRunning.get()

    /**
     * Initializes and starts the video encoder and optional audio recorder.
     */
    @Synchronized
    fun start() {
        if (isRunning.get()) return

        try {
            val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
                // Use Main profile and Level 4 for 1080p resolution compatibility across players
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileMain)
                setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel4)
            }

            val codec = MediaCodec.createEncoderByType(MIME_TYPE)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = codec.createInputSurface()
            codec.start()

            mediaCodec = codec
            mediaFormat = codec.outputFormat

            encoderThread = HandlerThread("MotionPhotoEncoderThread").apply { start() }
            encoderHandler = Handler(encoderThread!!.looper)

            isRunning.set(true)
            ringBuffer.clear()

            // Start draining output buffers asynchronously
            encoderHandler?.post { drainEncoderLoop() }

            // Start audio recorder if enabled
            if (isAudioEnabled) {
                startAudioRecorderInternal()
            }

            Log.i(TAG, "MotionPhotoEncoder started: ${width}x${height} @ ${frameRate}fps, ${bitRate / 1_000_000}Mbps, audio=$isAudioEnabled")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start MotionPhotoEncoder", e)
            stop()
        }
    }

    /**
     * Starts or stops audio recording dynamically while video encoding is active.
     */
    @Synchronized
    fun updateAudioEnabled(enabled: Boolean) {
        isAudioEnabled = enabled
        if (!isRunning.get()) return

        if (enabled && audioRecorder == null) {
            startAudioRecorderInternal()
        } else if (!enabled && audioRecorder != null) {
            stopAudioRecorderInternal()
        }
    }

    private fun startAudioRecorderInternal() {
        try {
            val recorder = MotionPhotoAudioRecorder()
            if (recorder.start()) {
                audioRecorder = recorder
                Log.i(TAG, "MotionPhoto audio recorder attached successfully")
            } else {
                Log.w(TAG, "MotionPhoto audio recorder failed to start, audio track disabled")
                recorder.stop()
                audioRecorder = null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error starting MotionPhoto audio recorder", e)
            audioRecorder = null
        }
    }

    private fun stopAudioRecorderInternal() {
        try {
            audioRecorder?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping MotionPhoto audio recorder", e)
        }
        audioRecorder = null
    }

    /**
     * Continuous loop draining encoded buffers from MediaCodec into the CircularVideoRingBuffer.
     */
    private fun drainEncoderLoop() {
        val codec = mediaCodec ?: return
        val bufferInfo = MediaCodec.BufferInfo()

        while (isRunning.get()) {
            try {
                val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000) // 10ms timeout
                if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    continue
                } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    mediaFormat = codec.outputFormat
                    Log.d(TAG, "Encoder output format changed: $mediaFormat")
                } else if (outputBufferIndex >= 0) {
                    val encodedBuffer = codec.getOutputBuffer(outputBufferIndex)
                    if (encodedBuffer != null) {
                        encodedBuffer.position(bufferInfo.offset)
                        encodedBuffer.limit(bufferInfo.offset + bufferInfo.size)

                        val data = ByteArray(bufferInfo.size)
                        encodedBuffer.get(data)

                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            ringBuffer.setCodecConfig(data)
                        } else if (bufferInfo.size > 0) {
                            ringBuffer.addSample(data, bufferInfo.presentationTimeUs, bufferInfo.flags)
                        }
                    }
                    codec.releaseOutputBuffer(outputBufferIndex, false)
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.w(TAG, "Exception during drainEncoderLoop", e)
                }
                break
            }
        }
    }

    /**
     * Triggers a snapshot capture around [captureTimestampNs].
     * Will wait for [postDurationMs] to complete recording, then slice and mux the MP4 file.
     *
     * @param captureTimestampNs Nanosecond timestamp of the still image.
     * @param preDurationMs Milliseconds of video to keep before shutter.
     * @param postDurationMs Milliseconds of video to record after shutter.
     * @param outputFile Destination MP4 file.
     * @param orientationDegrees Orientation hint in degrees (0, 90, 180, 270).
     * @param onComplete Callback with the generated MP4 file (or null on failure) and the still PTS in us.
     */
    fun captureSnapshot(
        captureTimestampNs: Long,
        preDurationMs: Long = 1500L,
        postDurationMs: Long = 750L,
        outputFile: File,
        orientationDegrees: Int = 0,
        onComplete: (file: File?, stillPtsUs: Long) -> Unit
    ) {
        if (!isRunning.get()) {
            onComplete(null, 0L)
            return
        }

        val captureTimestampUs = captureTimestampNs / 1000L

        scope.launch {
            // Wait for post-capture frames to be recorded
            delay(postDurationMs + 100L)

            val currentFormat = mediaFormat
            if (currentFormat == null) {
                Log.e(TAG, "Cannot capture snapshot: mediaFormat is null")
                onComplete(null, 0L)
                return@launch
            }

            val slice = ringBuffer.slice(
                triggerTimestampUs = captureTimestampUs,
                preDurationUs = preDurationMs * 1000L,
                postDurationUs = postDurationMs * 1000L
            )

            if (slice == null || slice.samples.isEmpty()) {
                Log.w(TAG, "Failed to extract slice from ring buffer")
                onComplete(null, 0L)
                return@launch
            }

            // Extract matching audio slice if audio is active
            val activeAudioRecorder = audioRecorder
            val audioSlice = if (activeAudioRecorder?.isRecording == true) {
                val videoStartPtsUs = slice.samples.first().presentationTimeUs
                val videoEndPtsUs = slice.samples.last().presentationTimeUs
                activeAudioRecorder.ringBuffer.slice(videoStartPtsUs, videoEndPtsUs)
            } else {
                null
            }
            val audioFormat = activeAudioRecorder?.mediaFormat

            val stillPtsUs = MotionPhotoMuxer.muxSliceToMp4(
                outputFile = outputFile,
                mediaFormat = currentFormat,
                slice = slice,
                orientationDegrees = orientationDegrees,
                audioFormat = audioFormat,
                audioSlice = audioSlice
            )

            if (stillPtsUs != null && outputFile.exists() && outputFile.length() > 0) {
                onComplete(outputFile, stillPtsUs)
            } else {
                onComplete(null, 0L)
            }
        }
    }

    /**
     * Stops and releases encoder and audio resources.
     */
    @Synchronized
    fun stop() {
        if (!isRunning.getAndSet(false)) return

        try {
            stopAudioRecorderInternal()

            encoderHandler?.removeCallbacksAndMessages(null)
            encoderThread?.quitSafely()
            encoderThread?.join(500)
            encoderThread = null
            encoderHandler = null

            mediaCodec?.stop()
            mediaCodec?.release()
            mediaCodec = null

            inputSurface?.release()
            inputSurface = null

            ringBuffer.clear()
            Log.i(TAG, "MotionPhotoEncoder stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error while stopping MotionPhotoEncoder", e)
        }
    }
}
