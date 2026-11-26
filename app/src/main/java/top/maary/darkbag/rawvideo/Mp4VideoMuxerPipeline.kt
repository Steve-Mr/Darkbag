package top.maary.darkbag.rawvideo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import top.maary.darkbag.models.EditConfig
import top.maary.darkbag.repository.PreferenceKeys
import top.maary.darkbag.utils.LutManager
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

internal object Mp4VideoMuxerPipeline {
    private const val TAG = "Mp4VideoMuxerPipeline"

    private data class GradingParams(
        val targetLogIndex: Int,
        val lutPath: String?,
        val exposure: Float,
        val contrast: Float,
        val saturation: Float
    )

    fun calculateMeasuredFps(
        totalFrames: Int,
        firstFrameNs: Long,
        lastFrameNs: Long,
        fallbackFps: Float
    ): Float {
        return if (totalFrames > 1 && lastFrameNs > firstFrameNs) {
            ((totalFrames - 1) * 1_000_000_000.0 / (lastFrameNs - firstFrameNs)).toFloat().coerceIn(1.0f, 120.0f)
        } else {
            fallbackFps.takeIf { it > 0 } ?: 24.0f
        }
    }

    fun calculatePtsUs(
        frameIndex: Int,
        frameTsNs: Long,
        firstFrameNs: Long,
        lastPtsUs: Long,
        fallbackIntervalUs: Long
    ): Long {
        val rawPtsUs = if (firstFrameNs > 0L && frameTsNs >= firstFrameNs) {
            (frameTsNs - firstFrameNs) / 1000L
        } else {
            frameIndex * fallbackIntervalUs
        }
        return if (lastPtsUs < 0L) {
            rawPtsUs.coerceAtLeast(0L)
        } else {
            maxOf(rawPtsUs, lastPtsUs + 1000L) // Enforce strictly monotonic increase for MediaCodec
        }
    }

    fun isExportSuccessful(muxerStarted: Boolean, videoEos: Boolean, outputFile: File): Boolean {
        return muxerStarted && videoEos && outputFile.exists() && outputFile.length() > 0
    }

    fun bitmapToNv12(bitmap: Bitmap, argb: IntArray, nv12: ByteArray, width: Int, height: Int) {
        bitmap.getPixels(argb, 0, width, 0, 0, width, height)

        val frameSize = width * height
        var yIndex = 0
        var uvIndex = frameSize

        for (j in 0 until height) {
            for (i in 0 until width) {
                val pixel = argb[j * width + i]
                val r = (pixel shr 16) and 0xff
                val g = (pixel shr 8) and 0xff
                val b = pixel and 0xff

                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                nv12[yIndex++] = y.coerceIn(0, 255).toByte()

                if (j % 2 == 0 && i % 2 == 0) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    nv12[uvIndex++] = u.coerceIn(0, 255).toByte()
                    nv12[uvIndex++] = v.coerceIn(0, 255).toByte()
                }
            }
        }
    }

    /**
     * Exports a .rawvid clip into a tone-mapped MP4 video with orientation hint and BT.709 colorimetry.
     * Primary path: GPU Surface direct encoding with eglPresentationTimeANDROID.
     * Fallback path: CPU debayering to Bitmap and NV12 byte buffers.
     */
    suspend fun exportToMp4(
        context: Context,
        rawVideoUri: Uri,
        outputFile: File,
        editConfig: EditConfig?,
        targetResolution: Int = 1080,
        isCancelled: (() -> Boolean)? = null,
        onProgress: (current: Int, total: Int) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        var isSuccess = false
        try {
            if (isCancelled?.invoke() == true) {
                Log.i(TAG, "Export cancelled by user before starting MP4 export, aborting...")
                return@withContext false
            }

            val gpuSuccess = try {
                exportPipeline(
                    context = context,
                    rawVideoUri = rawVideoUri,
                    outputFile = outputFile,
                    editConfig = editConfig,
                    targetResolution = targetResolution,
                    useGpu = true,
                    isCancelled = isCancelled,
                    onProgress = onProgress
                )
            } catch (e: Exception) {
                Log.w(TAG, "GPU Surface MP4 export failed or unsupported, falling back to CPU pipeline", e)
                false
            }

            if (gpuSuccess) {
                isSuccess = true
                return@withContext true
            }

            if (isCancelled?.invoke() == true) {
                Log.i(TAG, "Export cancelled by user after GPU attempt, aborting without CPU fallback...")
                return@withContext false
            }

            Log.i(TAG, "Executing CPU fallback MP4 export pipeline")
            try { outputFile.delete() } catch (_: Exception) {}
            val cpuSuccess = try {
                exportPipeline(
                    context = context,
                    rawVideoUri = rawVideoUri,
                    outputFile = outputFile,
                    editConfig = editConfig,
                    targetResolution = targetResolution,
                    useGpu = false,
                    isCancelled = isCancelled,
                    onProgress = onProgress
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed MP4 export", e)
                false
            }
            isSuccess = cpuSuccess
            return@withContext cpuSuccess
        } finally {
            if (!isSuccess && outputFile.exists()) {
                try { outputFile.delete() } catch (_: Exception) {}
            }
        }
    }

    private suspend fun exportPipeline(
        context: Context,
        rawVideoUri: Uri,
        outputFile: File,
        editConfig: EditConfig?,
        targetResolution: Int,
        useGpu: Boolean,
        isCancelled: (() -> Boolean)?,
        onProgress: (current: Int, total: Int) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        if (isCancelled?.invoke() == true) {
            Log.i(TAG, "Export cancelled by user before pipeline setup (useGpu=$useGpu), aborting...")
            return@withContext false
        }

        val pfd = try {
            context.contentResolver.openFileDescriptor(rawVideoUri, "r")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open rawVideoUri: $rawVideoUri", e)
            null
        } ?: return@withContext false

        var nativeHandle = 0L
        var muxerSession: AudioVideoMuxerSession? = null
        var inputSurface: Surface? = null
        var glExecutor: ExecutorService? = null
        var isSuccess = false

        pfd.use { parcelFd ->
            try {
                nativeHandle = RawVideoNative.nativeOpenReaderFd(parcelFd.fd)
                if (nativeHandle == 0L) {
                    Log.e(TAG, "Failed to open native reader for MP4 export (fd=${parcelFd.fd}, uri=$rawVideoUri)")
                    return@withContext false
                }

                val header = RawVideoNative.readHeader(nativeHandle) ?: return@withContext false
                val totalFrames = RawVideoNative.nativeGetFrameCount(nativeHandle)
                if (totalFrames <= 0) return@withContext false

                val rawW = header.width
                val rawH = header.height
                val bayerBuf = ByteBuffer.allocateDirect(rawW * rawH * 2)
                val firstMeta = LongArray(5)
                val lastMeta = LongArray(5)
                RawVideoNative.nativeReadFrame(nativeHandle, 0, firstMeta, bayerBuf)
                RawVideoNative.nativeReadFrame(nativeHandle, totalFrames - 1, lastMeta, bayerBuf)
                val firstFrameNs = firstMeta[0]
                val lastFrameNs = lastMeta[0]
                val measuredFps = calculateMeasuredFps(totalFrames, firstFrameNs, lastFrameNs, header.fps)

                val bitRate = if (targetResolution >= 2160) 40_000_000 else 20_000_000
                val maxDim = if (targetResolution >= 2160) 3840 else 1920
                val scale = if (maxOf(rawW, rawH) > maxDim) maxDim.toFloat() / maxOf(rawW, rawH) else 1.0f
                val exportW = (((rawW * scale).toInt() + 15) / 16) * 16
                val exportH = (((rawH * scale).toInt() + 15) / 16) * 16

                val colorFormat = if (useGpu) {
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                } else {
                    // CPU fallback writes raw NV12 semi-planar bytes directly into encoder input ByteBuffer.
                    @Suppress("DEPRECATION")
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
                }
                val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, exportW, exportH).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
                    setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                    setFloat(MediaFormat.KEY_FRAME_RATE, measuredFps)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                    setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
                    setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
                    setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
                }

                val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                    configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                }
                if (useGpu) {
                    inputSurface = enc.createInputSurface()
                }
                enc.start()

                val mux = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).apply {
                    if (header.orientation != 0) setOrientationHint(header.orientation)
                }

                val audioChannels = header.audioChannels.takeIf { it > 0 } ?: 1
                val audioSampleRate = header.audioSampleRate.takeIf { it > 0 } ?: 48000
                val audioDirectBuf = ByteBuffer.allocateDirect(65536)
                audioDirectBuf.clear()
                val firstAudioPacketSize = RawVideoNative.nativeReadAudioPacket(nativeHandle, 0, audioDirectBuf)
                val hasAudio = firstAudioPacketSize > 0
                val audioEncoder = if (hasAudio) createAudioEncoder(audioSampleRate, audioChannels) else null

                if (hasAudio) {
                    audioDirectBuf.position(0)
                    audioDirectBuf.limit(firstAudioPacketSize)
                } else {
                    audioDirectBuf.position(0)
                    audioDirectBuf.limit(0)
                }

                val session = AudioVideoMuxerSession(
                    videoEncoder = enc,
                    audioEncoder = audioEncoder,
                    muxer = mux,
                    audioDirectBuf = audioDirectBuf,
                    hasInitialAudio = hasAudio,
                    audioChannels = audioChannels,
                    audioSampleRate = audioSampleRate
                )
                muxerSession = session

                val fallbackIntervalUs = (1_000_000L / measuredFps).toLong().coerceAtLeast(1000L)
                val grading = resolveGradingParams(context, editConfig, useGpu)

                val loopCompleted = if (useGpu) {
                    val exec = Executors.newSingleThreadExecutor { Thread(it, "RawVideoExportGL") }
                    glExecutor = exec
                    runGpuFrameLoop(
                        glExecutor = exec,
                        surface = inputSurface!!,
                        nativeHandle = nativeHandle,
                        header = header,
                        bayerBuf = bayerBuf,
                        totalFrames = totalFrames,
                        firstFrameNs = firstFrameNs,
                        fallbackIntervalUs = fallbackIntervalUs,
                        grading = grading,
                        session = session,
                        isCancelled = isCancelled,
                        onProgress = onProgress
                    )
                } else {
                    runCpuFrameLoop(
                        nativeHandle = nativeHandle,
                        header = header,
                        bayerBuf = bayerBuf,
                        totalFrames = totalFrames,
                        exportW = exportW,
                        exportH = exportH,
                        firstFrameNs = firstFrameNs,
                        fallbackIntervalUs = fallbackIntervalUs,
                        grading = grading,
                        session = session,
                        isCancelled = isCancelled,
                        onProgress = onProgress
                    )
                }

                if (!loopCompleted || isCancelled?.invoke() == true) {
                    Log.i(TAG, "Frame loop cancelled or incomplete (useGpu=$useGpu), aborting...")
                    return@withContext false
                }

                if (!session.drainAudioEos(nativeHandle, isCancelled)) return@withContext false

                val (drainCompleted, videoEos) = session.drainRemainingStreams(isCancelled)
                if (!drainCompleted) return@withContext false

                isSuccess = isExportSuccessful(session.muxerStarted, videoEos, outputFile)
                if (!isSuccess) {
                    Log.w(
                        TAG,
                        "Export ended prematurely (useGpu=$useGpu): muxerStarted=${session.muxerStarted}, " +
                            "videoEos=$videoEos, fileLength=${outputFile.length()}"
                    )
                }
                return@withContext isSuccess
            } catch (e: Exception) {
                Log.w(TAG, "Exception during MP4 export (useGpu=$useGpu)", e)
                throw e
            } finally {
                try { glExecutor?.shutdown() } catch (_: Exception) {}
                try { inputSurface?.release() } catch (_: Exception) {}
                muxerSession?.release()
                if (nativeHandle != 0L) {
                    try { RawVideoNative.nativeCloseReader(nativeHandle) } catch (_: Exception) {}
                }
                if (!isSuccess && outputFile.exists()) {
                    try { outputFile.delete() } catch (_: Exception) {}
                }
            }
        }
    }

    private suspend fun runGpuFrameLoop(
        glExecutor: ExecutorService,
        surface: Surface,
        nativeHandle: Long,
        header: RawVideoNative.Header,
        bayerBuf: ByteBuffer,
        totalFrames: Int,
        firstFrameNs: Long,
        fallbackIntervalUs: Long,
        grading: GradingParams,
        session: AudioVideoMuxerSession,
        isCancelled: (() -> Boolean)?,
        onProgress: (current: Int, total: Int) -> Unit
    ): Boolean = withContext(glExecutor.asCoroutineDispatcher()) {
        val renderer = RawVideoNative.nativeCreateGLRenderer()
        if (renderer == 0L) throw IllegalStateException("Failed to create native GL renderer")
        var lastPtsUs = -1L
        try {
            RawVideoNative.nativeSetGLSurface(renderer, surface)
            for (i in 0 until totalFrames) {
                if (isCancelled?.invoke() == true) return@withContext false

                bayerBuf.clear()
                val meta = LongArray(5)
                val readBytes = RawVideoNative.nativeReadFrame(nativeHandle, i, meta, bayerBuf)
                if (readBytes <= 0) continue

                val ptsUs = calculatePtsUs(i, meta[0], firstFrameNs, lastPtsUs, fallbackIntervalUs)
                lastPtsUs = ptsUs

                val rendered = RawVideoNative.nativeRenderGLFrame(
                    rendererHandle = renderer,
                    bayerBuffer = bayerBuf,
                    width = header.width,
                    height = header.height,
                    orientation = 0,
                    cfaPattern = header.cfaPattern,
                    whiteLevel = header.whiteLevel,
                    blackLevels = header.blackLevel,
                    neutralPoint = header.neutralPoint,
                    forwardMatrix1 = header.forwardMatrix1,
                    forwardMatrix2 = header.forwardMatrix2,
                    calibIllum1 = header.calibrationIlluminant1,
                    calibIllum2 = header.calibrationIlluminant2,
                    targetLog = grading.targetLogIndex,
                    lutPath = grading.lutPath,
                    exposure = grading.exposure,
                    contrast = grading.contrast,
                    saturation = grading.saturation,
                    ptsNs = ptsUs * 1000L
                )
                if (!rendered) throw IllegalStateException("GPU GL frame render failed for frame $i")

                session.pumpDuringFrame(nativeHandle)
                onProgress(i + 1, totalFrames)
                if (isCancelled?.invoke() == true) return@withContext false
            }
            session.videoEncoder.signalEndOfInputStream()
            true
        } finally {
            try { RawVideoNative.nativeSetGLSurface(renderer, null) } catch (_: Exception) {}
            try { RawVideoNative.nativeDestroyGLRenderer(renderer) } catch (_: Exception) {}
        }
    }

    private fun runCpuFrameLoop(
        nativeHandle: Long,
        header: RawVideoNative.Header,
        bayerBuf: ByteBuffer,
        totalFrames: Int,
        exportW: Int,
        exportH: Int,
        firstFrameNs: Long,
        fallbackIntervalUs: Long,
        grading: GradingParams,
        session: AudioVideoMuxerSession,
        isCancelled: (() -> Boolean)?,
        onProgress: (current: Int, total: Int) -> Unit
    ): Boolean {
        val rawW = header.width
        val rawH = header.height
        val fullBmp = Bitmap.createBitmap(rawW, rawH, Bitmap.Config.ARGB_8888)
        val scaledBmp = if (exportW == rawW && exportH == rawH) fullBmp else Bitmap.createBitmap(exportW, exportH, Bitmap.Config.ARGB_8888)
        val canvas = if (scaledBmp !== fullBmp) Canvas(scaledBmp) else null
        val yuvBuf = ByteArray(exportW * exportH * 3 / 2)
        val reusableArgb = IntArray(exportW * exportH)
        var lastPtsUs = -1L

        try {
            for (i in 0 until totalFrames) {
                if (isCancelled?.invoke() == true) return false

                bayerBuf.clear()
                val meta = LongArray(5)
                val readBytes = RawVideoNative.nativeReadFrame(nativeHandle, i, meta, bayerBuf)
                if (readBytes <= 0) continue

                RawVideoNative.nativeDebayerFrameToBitmap(
                    bayerBuffer = bayerBuf,
                    width = rawW,
                    height = rawH,
                    orientation = 0,
                    cfaPattern = header.cfaPattern,
                    whiteLevel = header.whiteLevel,
                    blackLevel = header.blackLevel.firstOrNull() ?: 64f,
                    neutralPoint = header.neutralPoint,
                    forwardMatrix1 = header.forwardMatrix1,
                    forwardMatrix2 = header.forwardMatrix2,
                    calibIllum1 = header.calibrationIlluminant1,
                    calibIllum2 = header.calibrationIlluminant2,
                    targetLog = grading.targetLogIndex,
                    lutPath = grading.lutPath,
                    exposure = grading.exposure,
                    contrast = grading.contrast,
                    saturation = grading.saturation,
                    outBitmap = fullBmp
                )

                val inputBmp = if (canvas != null && scaledBmp !== fullBmp) {
                    canvas.drawBitmap(fullBmp, null, Rect(0, 0, exportW, exportH), null)
                    scaledBmp
                } else {
                    fullBmp
                }
                bitmapToNv12(inputBmp, reusableArgb, yuvBuf, exportW, exportH)

                val inIndex = session.videoEncoder.dequeueInputBuffer(10000)
                if (inIndex >= 0) {
                    val inputBuffer = session.videoEncoder.getInputBuffer(inIndex)
                    inputBuffer?.clear()
                    inputBuffer?.put(yuvBuf)
                    val ptsUs = calculatePtsUs(i, meta[0], firstFrameNs, lastPtsUs, fallbackIntervalUs)
                    lastPtsUs = ptsUs
                    session.videoEncoder.queueInputBuffer(inIndex, 0, yuvBuf.size, ptsUs, 0)
                }

                session.pumpDuringFrame(nativeHandle)
                onProgress(i + 1, totalFrames)
                if (isCancelled?.invoke() == true) return false
            }

            if (isCancelled?.invoke() == true) return false

            val eosIndex = session.videoEncoder.dequeueInputBuffer(10000)
            if (eosIndex >= 0) {
                val eosPtsUs = (if (lastPtsUs >= 0L) lastPtsUs else 0L) + fallbackIntervalUs
                session.videoEncoder.queueInputBuffer(eosIndex, 0, 0, eosPtsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
            return true
        } finally {
            try { fullBmp.recycle() } catch (_: Exception) {}
            try { if (scaledBmp !== fullBmp) scaledBmp.recycle() } catch (_: Exception) {}
        }
    }

    private fun createAudioEncoder(sampleRate: Int, channels: Int): MediaCodec? {
        return try {
            val aFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            }
            MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(aFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to initialize AAC audio encoder, exporting video only", e)
            null
        }
    }

    private fun resolveGradingParams(context: Context, editConfig: EditConfig?, useGpu: Boolean): GradingParams {
        val targetLogIndex = if (editConfig?.log != null && editConfig.log != "None") {
            PreferenceKeys.LOG_CURVES.indexOf(editConfig.log).takeIf { it >= 0 } ?: -1
        } else -1
        val lutManager = LutManager(context)
        val lutPath = if (editConfig?.lut != null && editConfig.lut != "None" && editConfig.lut.isNotBlank()) {
            val f = File(lutManager.lutDir, editConfig.lut)
            if (f.exists()) f.absolutePath else {
                File(File(context.filesDir, "luts"), editConfig.lut).takeIf { it.exists() }?.absolutePath
            }
        } else null
        return GradingParams(
            targetLogIndex = targetLogIndex,
            lutPath = lutPath,
            exposure = editConfig?.exposure ?: 0f,
            contrast = editConfig?.contrast ?: 0f,
            saturation = editConfig?.saturation ?: 0f
        )
    }
}
