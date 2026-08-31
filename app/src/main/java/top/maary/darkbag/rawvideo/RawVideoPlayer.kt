package top.maary.darkbag.rawvideo

import android.content.Context
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.*
import top.maary.darkbag.models.EditConfig
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.pow

class RawVideoPlayer(
    private val context: Context,
    private val onFrameRendered: (Bitmap, Int) -> Unit,
    private val onPlaybackStateChanged: (isPlaying: Boolean) -> Unit
) {
    companion object {
        private const val TAG = "RawVideoPlayer"
    }

    private var nativeHandle: Long = 0L
    private var header: RawVideoNative.Header? = null
    private var totalFrames: Int = 0
    private var currentFrameIndex: Int = 0

    private val isPlaying = AtomicBoolean(false)
    private var playbackJob: Job? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var audioTrack: AudioTrack? = null
    private var currentEditConfig: EditConfig? = null

    private var frameBuffer: ByteBuffer? = null
    private var renderBitmap: Bitmap? = null
    private var currentPfd: ParcelFileDescriptor? = null
    private var audioJob: Job? = null

    val isVideoLoaded: Boolean
        get() = nativeHandle != 0L

    val frameCount: Int
        get() = totalFrames

    val currentFrame: Int
        get() = currentFrameIndex

    fun load(uri: Uri): Boolean {
        release()

        try {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return false
            currentPfd = pfd
            val fd = pfd.fd
            val fdPath = "/proc/self/fd/$fd"

            nativeHandle = RawVideoNative.nativeOpenReader(fdPath)
            if (nativeHandle == 0L) {
                Log.e(TAG, "Failed to open raw video: $uri")
                release()
                return false
            }

            header = RawVideoNative.readHeader(nativeHandle)
            totalFrames = RawVideoNative.nativeGetFrameCount(nativeHandle)
            if (header == null || totalFrames <= 0) {
                Log.e(TAG, "Failed to read header or empty frames: $uri")
                release()
                return false
            }

            val w = header!!.width
            val h = header!!.height
            frameBuffer = ByteBuffer.allocateDirect(w * h * 2)
            renderBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

            // Setup audio track
            val sampleRate = header!!.audioSampleRate.takeIf { it > 0 } ?: 48000
            val channelConfig = if (header!!.audioChannels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
            val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuf > 0) {
                audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(channelConfig)
                            .build()
                    )
                    .setBufferSizeInBytes(minBuf * 2)
                    .build()
            }

            // Render first frame immediately
            currentFrameIndex = 0
            renderFrame(0)

            Log.i(TAG, "RawVideoPlayer loaded ($w x $h, $totalFrames frames @ ${header!!.fps} fps)")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error loading raw video player", e)
            release()
            return false
        }
    }

    fun play() {
        if (!isVideoLoaded || isPlaying.get()) return

        isPlaying.set(true)
        audioTrack?.play()
        onPlaybackStateChanged(true)

        val fps = header?.fps?.takeIf { it > 0 } ?: 24.0f
        val frameIntervalMs = (1000.0 / fps).toLong()

        playbackJob = CoroutineScope(Dispatchers.Default).launch {
            while (isPlaying.get()) {
                val start = System.currentTimeMillis()

                renderFrame(currentFrameIndex)
                currentFrameIndex = (currentFrameIndex + 1) % totalFrames

                val elapsed = System.currentTimeMillis() - start
                val sleepTime = (frameIntervalMs - elapsed).coerceAtLeast(1L)
                delay(sleepTime)
            }
        }

        // Stream audio packets from container to AudioTrack
        val handle = nativeHandle
        if (handle != 0L && audioTrack != null) {
            audioJob = CoroutineScope(Dispatchers.IO).launch {
                val audioBuf = ByteBuffer.allocateDirect(16384)
                var packetIndex = 0
                while (isPlaying.get()) {
                    audioBuf.clear()
                    val read = RawVideoNative.nativeReadAudioPacket(handle, packetIndex, audioBuf)
                    if (read <= 0) break
                    val bytes = ByteArray(read)
                    audioBuf.position(0)
                    audioBuf.get(bytes)
                    audioTrack?.write(bytes, 0, read)
                    packetIndex++
                }
            }
        }
    }

    fun pause() {
        if (!isPlaying.getAndSet(false)) return
        playbackJob?.cancel()
        playbackJob = null
        audioJob?.cancel()
        audioJob = null
        audioTrack?.pause()
        onPlaybackStateChanged(false)
    }

    fun togglePlayPause() {
        if (isPlaying.get()) {
            pause()
        } else {
            play()
        }
    }

    fun seekTo(frameIndex: Int) {
        if (!isVideoLoaded || totalFrames <= 0) return
        currentFrameIndex = frameIndex.coerceIn(0, totalFrames - 1)
        renderFrame(currentFrameIndex)
    }

    fun updateAdjustments(editConfig: EditConfig?) {
        currentEditConfig = editConfig
        if (!isPlaying.get() && isVideoLoaded) {
            renderFrame(currentFrameIndex)
        }
    }

    private fun renderFrame(frameIdx: Int) {
        val handle = nativeHandle
        val hdr = header ?: return
        val buf = frameBuffer ?: return
        val bmp = renderBitmap ?: return
        if (handle == 0L) return

        buf.clear()
        val meta = LongArray(3)
        val readBytes = RawVideoNative.nativeReadFrame(handle, frameIdx, meta, buf)
        if (readBytes > 0) {
            val exposureMultiplier = 2.0f.pow(currentEditConfig?.exposure ?: 0f) * (currentEditConfig?.digitalGain ?: 1.0f)
            val debayered = RawVideoNative.nativeDebayerFrameToBitmap(
                bayerBuffer = buf,
                width = hdr.width,
                height = hdr.height,
                cfaPattern = hdr.cfaPattern,
                whiteLevel = hdr.whiteLevel,
                blackLevel = hdr.blackLevel.firstOrNull() ?: 64f,
                exposureMultiplier = exposureMultiplier,
                outBitmap = bmp
            )

            if (debayered) {
                mainHandler.post {
                    onFrameRendered(bmp, frameIdx)
                }
            }
        }
    }

    fun release() {
        pause()
        if (nativeHandle != 0L) {
            RawVideoNative.nativeCloseReader(nativeHandle)
            nativeHandle = 0L
        }
        try {
            currentPfd?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing pfd", e)
        }
        currentPfd = null
        audioTrack?.release()
        audioTrack = null
        renderBitmap = null
        frameBuffer = null
    }
}
