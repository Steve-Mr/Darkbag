package top.maary.darkbag.motionphoto

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import android.media.Image
import android.os.SystemClock
import android.util.Log
import top.maary.darkbag.processor.LutSurfaceProcessor
import top.maary.darkbag.processor.RawMotionFrame
import top.maary.darkbag.processor.SensorCalibrationHelper
import top.maary.darkbag.rawvideo.RawVideoNative
import top.maary.darkbag.repository.PreferenceKeys
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Feeds live subsampled RAW_SENSOR frames from the camera repeating stream to [LutSurfaceProcessor]
 * for direct GPU-accelerated debayering and pre-roll encoding into Motion Photos.
 */
class RawMotionPhotoRelay(
    private val lutSurfaceProcessorProvider: () -> LutSurfaceProcessor?,
    private val characteristicsProvider: () -> CameraCharacteristics?,
    private val qualityModeProvider: () -> Int = { RawMotionQuality.DEFAULT_MODE },
    private val nativeProcessor: (src: ByteBuffer, w: Int, h: Int, stride: Int, dst: ByteBuffer, mode: Int) -> Int = { src, w, h, stride, dst, mode ->
        RawVideoNative.nativeProcessRawMotionBayer(src, w, h, stride, dst, mode)
    },
    private val frameConsumer: ((
        bayerBuffer: ByteBuffer,
        width: Int,
        height: Int,
        cfaPattern: Int,
        blackLevel: FloatArray,
        whiteLevel: Float,
        wbGains: FloatArray,
        dngCcm: FloatArray,
        timestampNs: Long,
        lensShadingMap: FloatArray?,
        lensShadingRows: Int,
        lensShadingCols: Int
    ) -> Unit)? = null
) {
    constructor(
        lutSurfaceProcessorProvider: () -> LutSurfaceProcessor?,
        characteristicsProvider: () -> CameraCharacteristics?,
        nativeSubsampler: (src: ByteBuffer, w: Int, h: Int, stride: Int, dst: ByteBuffer) -> Int,
        frameConsumer: ((
            bayerBuffer: ByteBuffer,
            width: Int,
            height: Int,
            cfaPattern: Int,
            blackLevel: FloatArray,
            whiteLevel: Float,
            wbGains: FloatArray,
            dngCcm: FloatArray,
            timestampNs: Long
        ) -> Unit)? = null
    ) : this(
        lutSurfaceProcessorProvider = lutSurfaceProcessorProvider,
        characteristicsProvider = characteristicsProvider,
        qualityModeProvider = { RawMotionQuality.DEFAULT_MODE },
        nativeProcessor = { src, w, h, stride, dst, _ -> nativeSubsampler(src, w, h, stride, dst) },
        frameConsumer = if (frameConsumer != null) {
            { b, w, h, cfa, bl, wl, wb, ccm, ts, _, _, _ ->
                frameConsumer(b, w, h, cfa, bl, wl, wb, ccm, ts)
            }
        } else null
    )
    companion object {
        private const val TAG = "RawMotionPhotoRelay"
        const val INITIAL_BUFFER_CAPACITY = 1024 * 768 * 2
        const val OVERSIZED_BUFFER_THRESHOLD_BYTES = 8 * 1024 * 1024
        const val MIN_FRAME_INTERVAL_NS = 33_000_000L // Cap live stream at ~30 fps

        fun parseQualityMode(quality: String?): Int = RawMotionQuality.fromLegacy(quality).mode

        /** Factory method for legacy String quality providers */
        fun createWithLegacyQuality(
            lutSurfaceProcessorProvider: () -> LutSurfaceProcessor?,
            characteristicsProvider: () -> CameraCharacteristics?,
            stringQualityProvider: () -> String,
            nativeProcessor: (src: ByteBuffer, w: Int, h: Int, stride: Int, dst: ByteBuffer, mode: Int) -> Int = { src, w, h, stride, dst, mode ->
                RawVideoNative.nativeProcessRawMotionBayer(src, w, h, stride, dst, mode)
            },
            frameConsumer: ((
                bayerBuffer: ByteBuffer,
                width: Int,
                height: Int,
                cfaPattern: Int,
                blackLevel: FloatArray,
                whiteLevel: Float,
                wbGains: FloatArray,
                dngCcm: FloatArray,
                timestampNs: Long,
                lensShadingMap: FloatArray?,
                lensShadingRows: Int,
                lensShadingCols: Int
            ) -> Unit)? = null
        ): RawMotionPhotoRelay = RawMotionPhotoRelay(
            lutSurfaceProcessorProvider = lutSurfaceProcessorProvider,
            characteristicsProvider = characteristicsProvider,
            qualityModeProvider = { RawMotionQuality.fromLegacy(stringQualityProvider()).mode },
            nativeProcessor = nativeProcessor,
            frameConsumer = frameConsumer
        )
    }

    private val bufferLock = Any()
    private var buffers = arrayOf(
        ByteBuffer.allocateDirect(INITIAL_BUFFER_CAPACITY),
        ByteBuffer.allocateDirect(INITIAL_BUFFER_CAPACITY)
    )
    private val bufferIndex = AtomicInteger(0)
    private val isRunning = AtomicBoolean(false)
    @Volatile private var lastSensorTimestampNs = 0L
    private var lastLoggedQualityMode = -1
    private var lastLoggedWidth = 0
    private var lastLoggedHeight = 0
    @Volatile private var loggedNativeClamp = false

    private val cachedCcm = FloatArray(9) { if (it % 4 == 0) 1.0f else 0.0f }
    private val cachedWbGains = floatArrayOf(2.0f, 1.0f, 1.6f)
    private val cachedBlackLevel = floatArrayOf(64f, 64f, 64f, 64f)

    val currentBufferCapacity: Int
        get() = synchronized(bufferLock) { buffers[0].capacity() }

    fun start() {
        isRunning.set(true)
        lastSensorTimestampNs = 0L
        loggedNativeClamp = false
    }

    fun stop() {
        isRunning.set(false)
        loggedNativeClamp = false
        synchronized(bufferLock) {
            if (buffers[0].capacity() > OVERSIZED_BUFFER_THRESHOLD_BYTES) {
                buffers = arrayOf(
                    ByteBuffer.allocateDirect(INITIAL_BUFFER_CAPACITY),
                    ByteBuffer.allocateDirect(INITIAL_BUFFER_CAPACITY)
                )
            }
        }
    }

    private fun ensureAndAcquireBuffer(requiredBytes: Int): ByteBuffer? {
        synchronized(bufferLock) {
            if (!isRunning.get()) return null
            if (buffers[0].capacity() < requiredBytes) {
                val newCap = maxOf(requiredBytes, INITIAL_BUFFER_CAPACITY)
                buffers = arrayOf(
                    ByteBuffer.allocateDirect(newCap),
                    ByteBuffer.allocateDirect(newCap)
                )
            }
            val idx = bufferIndex.getAndIncrement() and 1
            return buffers[idx]
        }
    }

    fun onRawFrame(image: Image, result: TotalCaptureResult?) {
        if (!isRunning.get()) return

        // Throttle to <= 30 fps using the camera sensor timestamp
        val prevTs = lastSensorTimestampNs
        if (prevTs != 0L && image.timestamp - prevTs < MIN_FRAME_INTERVAL_NS) {
            return
        }

        val chars = characteristicsProvider()

        // Convert camera sensor timestamp to monotonic timebase matching MediaCodec / EGL
        val isRealtime = chars?.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
            CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
        val sensorNow = if (isRealtime) SystemClock.elapsedRealtimeNanos() else SystemClock.uptimeMillis() * 1_000_000L
        val monotonicTs = MotionPhotoTimebase.sensorToMonotonic(
            sensorTimestampNs = image.timestamp,
            realtime = isRealtime,
            sensorNowNs = sensorNow,
            monotonicNowNs = System.nanoTime()
        )

        val processor = lutSurfaceProcessorProvider()
        if (processor == null && frameConsumer == null) return

        val planes = image.planes
        if (planes.isEmpty()) return
        val plane = planes[0]
        val srcBuffer = plane.buffer ?: return
        val srcWidth = image.width
        val srcHeight = image.height
        val rowStride = plane.rowStride

        val requestedMode = qualityModeProvider()
        val qualityMode = RawMotionQuality.resolveEffectiveMode(requestedMode, srcWidth, srcHeight)
        if (qualityMode != requestedMode && !loggedNativeClamp) {
            loggedNativeClamp = true
            Log.w(
                TAG,
                "Sensor ${srcWidth}x${srcHeight} exceeds ${RawMotionQuality.MAX_NATIVE_RAW_PIXELS} pixels; clamping Native RAW Motion mode to 2x2 Binning"
            )
        }

        val (expectedDstWidth, expectedDstHeight) = RawMotionQuality.computeScaledDimensions(
            srcWidth = srcWidth,
            srcHeight = srcHeight,
            effectiveMode = qualityMode
        )
        val requiredBytes = expectedDstWidth * expectedDstHeight * 2

        if (lastLoggedQualityMode != qualityMode || lastLoggedWidth != expectedDstWidth || lastLoggedHeight != expectedDstHeight) {
            lastLoggedQualityMode = qualityMode
            lastLoggedWidth = expectedDstWidth
            lastLoggedHeight = expectedDstHeight
            val qualityEnum = RawMotionQuality.fromMode(qualityMode)
            Log.i(TAG, "RAW Motion Photo stream active: mode=$qualityMode (${qualityEnum.name}), dims=${expectedDstWidth}x${expectedDstHeight}")
        }

        // White level
        val whiteLevel = chars?.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)?.toFloat() ?: 1023f

        // CFA pattern
        val cfaPattern = chars?.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: 0

        // Black level (R, Gr, Gb, B)
        val dynBl = result?.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)
        val blackLevel = if (dynBl != null && dynBl.size >= 4) {
            floatArrayOf(dynBl[0], dynBl[1], dynBl[2], dynBl[3])
        } else {
            val pat = chars?.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
            if (pat != null) {
                floatArrayOf(
                    pat.getOffsetForIndex(0, 0).toFloat(),
                    pat.getOffsetForIndex(1, 0).toFloat(),
                    pat.getOffsetForIndex(0, 1).toFloat(),
                    pat.getOffsetForIndex(1, 1).toFloat()
                )
            } else {
                cachedBlackLevel
            }
        }

        // WB gains & neutral point
        val colorGains = result?.get(CaptureResult.COLOR_CORRECTION_GAINS)
        val wbGains = if (colorGains != null && colorGains.red > 1e-4f && colorGains.blue > 1e-4f) {
            val gAvg = 0.5f * (colorGains.greenEven + colorGains.greenOdd)
            val effG = if (gAvg > 1e-4f) gAvg else 1.0f
            floatArrayOf(colorGains.red / effG, 1.0f, colorGains.blue / effG)
        } else {
            cachedWbGains
        }

        val neutralPoint = floatArrayOf(
            1.0f / maxOf(0.1f, wbGains[0]),
            1.0f,
            1.0f / maxOf(0.1f, wbGains[2])
        )

        // DNG CCM from ForwardMatrix1 / ForwardMatrix2
        val fm1 = chars?.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1)?.let { colorSpaceTransformToFloatArray(it) }
        val fm2 = chars?.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2)?.let { colorSpaceTransformToFloatArray(it) }
        val illum1 = chars?.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1)?.toInt() ?: 21
        val illum2 = chars?.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)?.toInt() ?: 17

        val dngCcm = FloatArray(9)
        val ccmOk = try {
            RawVideoNative.nativeComputeAdaptiveColorMatrix(
                neutralPoint = neutralPoint,
                forwardMatrix1 = fm1,
                forwardMatrix2 = fm2,
                calibIllum1 = illum1,
                calibIllum2 = illum2,
                outColMajor = dngCcm
            )
        } catch (_: UnsatisfiedLinkError) {
            false
        }
        val activeCcm = if (ccmOk) dngCcm else cachedCcm

        // Lens Shading Map (LSC)
        val lscTriple = SensorCalibrationHelper.extractLensShading(result)
        val lscMap = lscTriple.first
        val lscRows = lscTriple.second
        val lscCols = lscTriple.third

        // Atomically verify running state + buffer capacity and pick next buffer in double-buffer rotation
        val dstBuffer = ensureAndAcquireBuffer(requiredBytes) ?: return
        dstBuffer.clear()

        srcBuffer.position(0)
        val packedDims = try {
            nativeProcessor(srcBuffer, srcWidth, srcHeight, rowStride, dstBuffer, qualityMode)
        } catch (_: UnsatisfiedLinkError) {
            0
        }
        if (packedDims == 0) return

        val dstWidth = (packedDims ushr 16) and 0xFFFF
        val dstHeight = packedDims and 0xFFFF
        if (dstWidth == 0 || dstHeight == 0) return

        dstBuffer.position(0)
        dstBuffer.limit(dstWidth * dstHeight * 2)

        val sensorOrientation = chars?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

        lastSensorTimestampNs = image.timestamp

        val consumer = frameConsumer
        if (consumer != null) {
            consumer(
                dstBuffer,
                dstWidth,
                dstHeight,
                cfaPattern,
                blackLevel,
                whiteLevel,
                wbGains,
                activeCcm,
                monotonicTs,
                lscMap,
                lscRows,
                lscCols
            )
        } else {
            processor?.submitRawMotionFrame(
                RawMotionFrame(
                    bayerBuffer = dstBuffer,
                    width = dstWidth,
                    height = dstHeight,
                    cfaPattern = cfaPattern,
                    blackLevel = blackLevel,
                    whiteLevel = whiteLevel,
                    wbGains = wbGains,
                    dngCcm = activeCcm,
                    timestampNs = monotonicTs,
                    sensorOrientationDegrees = sensorOrientation,
                    lensShadingMap = lscMap,
                    lensShadingRows = lscRows,
                    lensShadingCols = lscCols
                )
            )
        }
    }

    private fun colorSpaceTransformToFloatArray(cst: ColorSpaceTransform): FloatArray {
        val out = FloatArray(9)
        for (r in 0 until 3) {
            for (c in 0 until 3) {
                val rat = cst.getElement(c, r)
                out[r * 3 + c] = if (rat.denominator != 0) rat.numerator.toFloat() / rat.denominator.toFloat() else 0f
            }
        }
        return out
    }
}
