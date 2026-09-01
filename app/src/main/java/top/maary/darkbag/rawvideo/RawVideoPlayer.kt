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

    private val frameMeta = LongArray(3)
    private var cachedLutPath: String? = null
    private var cachedTargetLogIndex: Int = -1

    private var glRendererHandle: Long = 0L
    private var currentSurface: android.view.Surface? = null

    val isVideoLoaded: Boolean
        get() = nativeHandle != 0L

    val frameCount: Int
        get() = totalFrames

    val currentFrame: Int
        get() = currentFrameIndex

    val orientation: Int
        get() = header?.orientation ?: 0

    val fps: Float
        get() = header?.fps ?: 24.0f

    fun setSurface(surface: android.view.Surface?) {
        currentSurface = surface
        if (glRendererHandle == 0L && surface != null) {
            glRendererHandle = RawVideoNative.nativeCreateGLRenderer()
        }
        if (glRendererHandle != 0L) {
            RawVideoNative.nativeSetGLSurface(glRendererHandle, surface)
        }
    }

    private fun updateResolvedLutAndLog() {
        val hdr = header
        val effectiveLog = currentEditConfig?.log ?: hdr?.activeLogName?.takeIf { it.isNotBlank() }
        cachedTargetLogIndex = if (effectiveLog != null && effectiveLog != "None") {
            top.maary.darkbag.fragments.SettingsFragment.LOG_CURVES.indexOf(effectiveLog).takeIf { it >= 0 } ?: -1
        } else -1

        val effectiveLut = currentEditConfig?.lut ?: hdr?.activeLutName?.takeIf { it.isNotBlank() }
        val lutManager = top.maary.darkbag.utils.LutManager(context)
        cachedLutPath = if (effectiveLut != null && effectiveLut != "None" && effectiveLut.isNotBlank()) {
            val f = java.io.File(lutManager.lutDir, effectiveLut)
            if (f.exists()) f.absolutePath else {
                val f2 = java.io.File(java.io.File(context.filesDir, "luts"), effectiveLut)
                if (f2.exists()) f2.absolutePath else null
            }
        } else null
    }

    fun load(uri: Uri): Boolean {
        release()

        try {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return false
            currentPfd = pfd
            val fd = pfd.fd

            nativeHandle = RawVideoNative.nativeOpenReaderFd(fd)
            if (nativeHandle == 0L) {
                Log.e(TAG, "Failed to open raw video via fd: $uri (fd=$fd)")
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
            val orient = header!!.orientation
            val swapDims = (orient == 90 || orient == 270)
            val bmpW = if (swapDims) h else w
            val bmpH = if (swapDims) w else h
            frameBuffer = ByteBuffer.allocateDirect(w * h * 2)
            renderBitmap = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)

            updateResolvedLutAndLog()

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
        val frameIntervalNs = (1_000_000_000.0 / fps).toLong()

        playbackJob = CoroutineScope(Dispatchers.Default).launch {
            var nextFrameTimeNs = System.nanoTime()
            while (isPlaying.get()) {
                val startNs = System.nanoTime()

                renderFrame(currentFrameIndex)

                nextFrameTimeNs += frameIntervalNs
                val nowNs = System.nanoTime()
                val sleepNs = nextFrameTimeNs - nowNs

                if (sleepNs > 0) {
                    val sleepMs = sleepNs / 1_000_000L
                    if (sleepMs > 0) {
                        delay(sleepMs)
                    }
                    currentFrameIndex = (currentFrameIndex + 1) % totalFrames
                } else {
                    // Frame rendering took longer than frameIntervalNs:
                    // Drop lagging frames to keep pace with real time and audio clock
                    val behindNs = -sleepNs
                    val framesToSkip = ((behindNs / frameIntervalNs).toInt() + 1).coerceAtMost(totalFrames - 1)
                    currentFrameIndex = (currentFrameIndex + framesToSkip) % totalFrames
                    nextFrameTimeNs = nowNs + frameIntervalNs
                }
            }
        }

        // Stream audio packets from container to AudioTrack
        val handle = nativeHandle
        if (handle != 0L && audioTrack != null) {
            audioJob = CoroutineScope(Dispatchers.IO).launch {
                val audioBuf = ByteBuffer.allocateDirect(16384)
                val tempBytes = ByteArray(16384)
                var packetIndex = 0
                while (isPlaying.get()) {
                    audioBuf.clear()
                    val read = RawVideoNative.nativeReadAudioPacket(handle, packetIndex, audioBuf)
                    if (read <= 0) break
                    audioBuf.position(0)
                    audioBuf.get(tempBytes, 0, read)
                    audioTrack?.write(tempBytes, 0, read)
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
        updateResolvedLutAndLog()
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
        val readBytes = RawVideoNative.nativeReadFrame(handle, frameIdx, frameMeta, buf)
        if (readBytes > 0) {
            val exposure = currentEditConfig?.exposure ?: hdr.exposure
            val contrast = currentEditConfig?.contrast ?: hdr.contrast
            val saturation = currentEditConfig?.saturation ?: hdr.saturation

            // 1. Fast GPU direct-to-surface rendering if Surface is available
            val surf = currentSurface
            if (glRendererHandle != 0L && surf != null && surf.isValid) {
                val rendered = RawVideoNative.nativeRenderGLFrame(
                    rendererHandle = glRendererHandle,
                    bayerBuffer = buf,
                    width = hdr.width,
                    height = hdr.height,
                    orientation = hdr.orientation,
                    cfaPattern = hdr.cfaPattern,
                    whiteLevel = hdr.whiteLevel,
                    blackLevel = hdr.blackLevel.firstOrNull() ?: 64f,
                    neutralPoint = hdr.neutralPoint,
                    targetLog = cachedTargetLogIndex,
                    lutPath = cachedLutPath,
                    exposure = exposure,
                    contrast = contrast,
                    saturation = saturation
                )
                if (rendered) {
                    return
                }
            }

            // 2. CPU fallback to Bitmap
            val debayered = RawVideoNative.nativeDebayerFrameToBitmap(
                bayerBuffer = buf,
                width = hdr.width,
                height = hdr.height,
                orientation = hdr.orientation,
                cfaPattern = hdr.cfaPattern,
                whiteLevel = hdr.whiteLevel,
                blackLevel = hdr.blackLevel.firstOrNull() ?: 64f,
                neutralPoint = hdr.neutralPoint,
                targetLog = cachedTargetLogIndex,
                lutPath = cachedLutPath,
                exposure = exposure,
                contrast = contrast,
                saturation = saturation,
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
        if (glRendererHandle != 0L) {
            RawVideoNative.nativeDestroyGLRenderer(glRendererHandle)
            glRendererHandle = 0L
        }
        currentSurface = null
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
