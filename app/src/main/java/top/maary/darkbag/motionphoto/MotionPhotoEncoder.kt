package top.maary.darkbag.motionphoto

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
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
open class MotionPhotoEncoder(
    private val width: Int = 1080,
    private val height: Int = 1440,
    private val frameRate: Int = 30,
    private val bitRate: Int = 10_000_000, // 10 Mbps
    var isAudioEnabled: Boolean = false
) {
    companion object {
        private const val TAG = "MotionPhotoEncoder"
        private const val MIME_TYPE_HEVC = MediaFormat.MIMETYPE_VIDEO_HEVC
        private const val MIME_TYPE_AVC = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val I_FRAME_INTERVAL = 1 // 1 second between keyframes

        /**
         * Queries [MediaCodecList] to check whether any hardware encoder (or fallback encoder)
         * supports [width]x[height] at [frameRate] fps for [mimeType].
         * Returns true when codec enumeration is unavailable (e.g., JVM test environments).
         */
        fun isCodecSizeSupported(
            mimeType: String,
            width: Int,
            height: Int,
            frameRate: Int
        ): Boolean {
            return try {
                val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
                val encoders = codecList.codecInfos.filter { info ->
                    info.isEncoder && info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }
                }
                if (encoders.isEmpty()) {
                    return true
                }
                val candidateEncoders = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    encoders.filter { it.isHardwareAccelerated }.ifEmpty { encoders }
                } else {
                    encoders
                }
                candidateEncoders.any { info ->
                    val caps = runCatching { info.getCapabilitiesForType(mimeType) }.getOrNull()
                        ?.videoCapabilities ?: return@any false
                    caps.isSizeSupported(width, height) &&
                        caps.areSizeAndRateSupported(width, height, frameRate.toDouble())
                }
            } catch (_: Throwable) {
                true
            }
        }
    }

    private val ringBuffer = CircularVideoRingBuffer(maxRetentionDurationUs = 4_500_000L)
    private var mediaCodec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var mediaFormat: MediaFormat? = null

    private var audioRecorder: MotionPhotoAudioRecorder? = null

    private var encoderThread: HandlerThread? = null
    private var encoderHandler: Handler? = null

    private val isRunning = AtomicBoolean(false)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    open val surface: Surface?
        get() = inputSurface

    open val isEncoding: Boolean
        get() = isRunning.get()

    private fun createVideoFormat(mimeType: String): MediaFormat {
        return MediaFormat.createVideoFormat(mimeType, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
            if (mimeType == MIME_TYPE_HEVC) {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain)
            } else {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileMain)
            }
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
        }
    }

    private fun tryConfigureAndStartCodec(mimeType: String): MediaCodec {
        val format = createVideoFormat(mimeType)
        val codec = MediaCodec.createEncoderByType(mimeType)
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = codec.createInputSurface()
            codec.start()
            return codec
        } catch (e: Exception) {
            try {
                inputSurface?.release()
            } catch (_: Exception) {}
            inputSurface = null
            try {
                codec.release()
            } catch (_: Exception) {}
            throw e
        }
    }

    /**
     * Initializes and starts the video encoder (HEVC preferred, H.264 fallback) and optional audio recorder.
     *
     * @return true if the encoder started successfully, false otherwise (cleaning up any partial resources).
     */
    @Synchronized
    open fun start(): Boolean {
        if (isRunning.get()) return true

        try {
            var activeMime = MIME_TYPE_HEVC
            val codec = try {
                tryConfigureAndStartCodec(MIME_TYPE_HEVC)
            } catch (hevcError: Exception) {
                Log.w(TAG, "HEVC encoder failed for ${width}x${height}, falling back to H.264", hevcError)
                activeMime = MIME_TYPE_AVC
                tryConfigureAndStartCodec(MIME_TYPE_AVC)
            }

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

            Log.i(TAG, "MotionPhotoEncoder started ($activeMime): ${width}x${height} @ ${frameRate}fps, ${bitRate / 1_000_000}Mbps, audio=$isAudioEnabled")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start MotionPhotoEncoder (${width}x${height})", e)
            isRunning.set(false)
            cleanupResourcesLocked()
            return false
        }
    }

    /**
     * Starts or stops audio recording dynamically while video encoding is active.
     */
    @Synchronized
    open fun updateAudioEnabled(enabled: Boolean) {
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
    open fun captureSnapshot(
        captureTimestampNs: Long,
        preDurationMs: Long = 1500L,
        postDurationMs: Long = 1500L,
        outputFile: File,
        orientationDegrees: Int = 0,
        stillTimestampProvider: (() -> Long)? = null,
        onComplete: (file: File?, stillPtsUs: Long) -> Unit
    ) {
        if (!isRunning.get()) {
            onComplete(null, 0L)
            return
        }

        scope.launch {
            // Wait for post-capture frames to be recorded
            delay(postDurationMs + 100L)
            // ZSL may resolve its selected sensor frame after the shutter; let it update the
            // still timestamp before the slice is muxed.
            val resolvedTimestampNs = stillTimestampProvider?.invoke() ?: captureTimestampNs
            val captureTimestampUs = resolvedTimestampNs / 1000L

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

            val expectedStillUs = (captureTimestampUs - slice.samples.first().presentationTimeUs)
                .coerceIn(0L, slice.durationUs)
            Log.d(
                TAG,
                "Motion still PTS: selected=${captureTimestampUs}us expectedRelative=${expectedStillUs}us " +
                    "actual=${stillPtsUs ?: 0L}us delta=${(stillPtsUs ?: 0L) - expectedStillUs}us"
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
    open fun stop() {
        if (!isRunning.getAndSet(false)) return
        cleanupResourcesLocked()
        Log.i(TAG, "MotionPhotoEncoder stopped")
    }

    private fun cleanupResourcesLocked() {
        runCatching { stopAudioRecorderInternal() }
            .onFailure { Log.w(TAG, "Error stopping audio recorder during cleanup", it) }

        runCatching {
            encoderHandler?.removeCallbacksAndMessages(null)
            encoderThread?.quitSafely()
            encoderThread?.join(500)
        }.onFailure { Log.w(TAG, "Error stopping encoder thread during cleanup", it) }
        encoderThread = null
        encoderHandler = null

        val codec = mediaCodec
        mediaCodec = null
        if (codec != null) {
            runCatching { codec.stop() }
                .onFailure { Log.w(TAG, "Error stopping MediaCodec during cleanup", it) }
            runCatching { codec.release() }
                .onFailure { Log.w(TAG, "Error releasing MediaCodec during cleanup", it) }
        }

        val surf = inputSurface
        inputSurface = null
        if (surf != null) {
            runCatching { surf.release() }
                .onFailure { Log.w(TAG, "Error releasing input surface during cleanup", it) }
        }

        mediaFormat = null
        ringBuffer.clear()
    }
}
