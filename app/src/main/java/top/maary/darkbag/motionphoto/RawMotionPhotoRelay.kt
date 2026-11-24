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
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Cohesive white-balance strategy & manual override descriptor for RAW Motion Photo rendering.
 */
data class RawMotionWbConfig(
    val wbMode: Int = 0,
    val wbTempOffset: Float = 0f,
    val wbTintOffset: Float = 0f,
    val wbManualTempK: Float = 5500f
)

/**
 * Feeds live subsampled RAW_SENSOR frames from the camera repeating stream to [LutSurfaceProcessor]
 * for direct GPU-accelerated debayering and pre-roll encoding into Motion Photos.
 */
class RawMotionPhotoRelay(
    private val lutSurfaceProcessorProvider: () -> LutSurfaceProcessor?,
    private val characteristicsProvider: () -> CameraCharacteristics?,
    private val qualityModeProvider: () -> Int = { RawMotionQuality.DEFAULT_MODE },
    private val wbConfigProvider: () -> RawMotionWbConfig = { RawMotionWbConfig() },
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

        private val DEFAULT_WB_GAINS = floatArrayOf(2.0f, 1.0f, 1.6f)
        private val DEFAULT_NEUTRAL_POINT = floatArrayOf(0.5f, 1.0f, 1.0f / 1.6f)
        private val DEFAULT_BLACK_LEVEL = floatArrayOf(64f, 64f, 64f, 64f)
        private val IDENTITY_CCM = floatArrayOf(
            1f, 0f, 0f,
            0f, 1f, 0f,
            0f, 0f, 1f
        )

        fun parseQualityMode(quality: String?): Int = RawMotionQuality.fromLegacy(quality).mode

        /**
         * Extracts canonical [wbGains (R,G=1,B), neutralPoint (nR,1,nB)] from [result] when it
         * explicitly contains `SENSOR_NEUTRAL_COLOR_POINT` or `COLOR_CORRECTION_GAINS`.
         * Returns `null` if neither key is present so the relay can preserve its last valid cache.
         */
        internal fun extractCanonicalWbFromResult(result: CaptureResult?): Pair<FloatArray, FloatArray>? {
            if (result == null) return null
            val np = result.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)
            val neutralArray = if (np != null && np.size >= 3 &&
                np[0].numerator > 0 && np[0].denominator > 0 &&
                np[1].numerator > 0 && np[1].denominator > 0 &&
                np[2].numerator > 0 && np[2].denominator > 0
            ) {
                floatArrayOf(
                    np[0].numerator.toFloat() / np[0].denominator.toFloat(),
                    np[1].numerator.toFloat() / np[1].denominator.toFloat(),
                    np[2].numerator.toFloat() / np[2].denominator.toFloat()
                )
            } else null

            val cg = result.get(CaptureResult.COLOR_CORRECTION_GAINS)
            val gainsArray = if (cg != null &&
                cg.red.isFinite() && cg.red > 1e-4f &&
                cg.blue.isFinite() && cg.blue > 1e-4f &&
                ((cg.greenEven.isFinite() && cg.greenEven > 1e-4f) ||
                    (cg.greenOdd.isFinite() && cg.greenOdd > 1e-4f))
            ) {
                floatArrayOf(cg.red, cg.greenEven, cg.greenOdd, cg.blue)
            } else null

            if (neutralArray == null && gainsArray == null) return null
            val (canonWb, canonNeutral) = SensorCalibrationHelper.resolveCanonicalWbAndNeutral(neutralArray, gainsArray)
            return Pair(floatArrayOf(canonWb[0], 1.0f, canonWb[3]), canonNeutral)
        }

        /**
         * Maps 2x2 CFA grid black levels `[TL(0,0), TR(1,0), BL(0,1), BR(1,1)]`
         * into `[R, Gr, Gb, B]` order expected by `LutGlShaderPipeline`.
         */
        internal fun mapBlackLevelToRgbg(gridBl: FloatArray, cfaPattern: Int): FloatArray {
            if (gridBl.size < 4) return DEFAULT_BLACK_LEVEL.copyOf()
            val g0 = gridBl[0]
            val g1 = gridBl[1]
            val g2 = gridBl[2]
            val g3 = gridBl[3]
            return when (cfaPattern) {
                0 -> floatArrayOf(g0, g1, g2, g3) // RGGB: (0,0)=R, (1,0)=Gr, (0,1)=Gb, (1,1)=B
                1 -> floatArrayOf(g1, g0, g3, g2) // GRBG: (0,0)=Gr, (1,0)=R, (0,1)=B, (1,1)=Gb
                2 -> floatArrayOf(g2, g3, g0, g1) // GBRG: (0,0)=Gb, (1,0)=B, (0,1)=R, (1,1)=Gr
                3 -> floatArrayOf(g3, g2, g1, g0) // BGGR: (0,0)=B, (1,0)=Gb, (0,1)=Gr, (1,1)=R
                else -> floatArrayOf(g0, g1, g2, g3)
            }
        }

        /**
         * Computes the adaptive sensor-to-sRGB render CCM in OpenGL column-major order,
         * matching `ColorPipe` / `SensorCalibrationHelper` (including `ColorMatrix1/2` Robertson
         * CCT estimation and `wbConfig` strategy/manual override).
         */
        internal fun computeRenderCcmColMajor(
            fm1: FloatArray?,
            fm2: FloatArray?,
            cm1: FloatArray?,
            cm2: FloatArray?,
            illum1: Int,
            illum2: Int,
            neutralPoint: FloatArray,
            wbGains: FloatArray,
            wbConfig: RawMotionWbConfig = RawMotionWbConfig()
        ): FloatArray {
            val wb4 = floatArrayOf(
                wbGains.getOrElse(0) { 1f },
                1f,
                1f,
                wbGains.getOrElse(2) { 1f }
            )
            val rowMajor = SensorCalibrationHelper.computeRenderCcm(
                forwardMatrix1 = fm1,
                forwardMatrix2 = fm2,
                calibrationIlluminant1 = illum1,
                calibrationIlluminant2 = illum2,
                neutralColorPoint = neutralPoint,
                wb = wb4,
                colorMatrix1 = cm1,
                colorMatrix2 = cm2,
                wbMode = wbConfig.wbMode,
                wbTempOffset = wbConfig.wbTempOffset,
                wbTintOffset = wbConfig.wbTintOffset,
                wbManualTempK = wbConfig.wbManualTempK
            ) ?: return IDENTITY_CCM.copyOf()

            val colMajor = FloatArray(9)
            for (r in 0 until 3) {
                for (c in 0 until 3) {
                    colMajor[c * 3 + r] = rowMajor[r * 3 + c]
                }
            }
            return colMajor
        }

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

    private class CachedLsc(val map: FloatArray, val rows: Int, val cols: Int)

    @Volatile private var cachedWbGains: FloatArray? = null
    @Volatile private var cachedNeutralPoint: FloatArray? = null
    @Volatile private var cachedDynBlackLevel: FloatArray? = null
    @Volatile private var cachedLsc: CachedLsc? = null

    val currentBufferCapacity: Int
        get() = synchronized(bufferLock) { buffers[0].capacity() }

    private fun resetCalibrationCache() {
        cachedWbGains = null
        cachedNeutralPoint = null
        cachedDynBlackLevel = null
        cachedLsc = null
    }

    fun start() {
        isRunning.set(true)
        lastSensorTimestampNs = 0L
        lastLoggedQualityMode = -1
        lastLoggedWidth = 0
        lastLoggedHeight = 0
        loggedNativeClamp = false
        resetCalibrationCache()
    }

    fun stop() {
        isRunning.set(false)
        loggedNativeClamp = false
        resetCalibrationCache()
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

        // Black level (mapped from 2x2 CFA grid order to [R, Gr, Gb, B])
        val dynBl = result?.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)
        val rawGridBl = if (dynBl != null && dynBl.size >= 4) {
            floatArrayOf(dynBl[0], dynBl[1], dynBl[2], dynBl[3]).also { cachedDynBlackLevel = it }
        } else {
            cachedDynBlackLevel ?: run {
                val pat = chars?.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
                if (pat != null) {
                    floatArrayOf(
                        pat.getOffsetForIndex(0, 0).toFloat(),
                        pat.getOffsetForIndex(1, 0).toFloat(),
                        pat.getOffsetForIndex(0, 1).toFloat(),
                        pat.getOffsetForIndex(1, 1).toFloat()
                    )
                } else {
                    DEFAULT_BLACK_LEVEL
                }
            }
        }
        val blackLevel = mapBlackLevelToRgbg(rawGridBl, cfaPattern)

        // WB gains & neutral point (Priority 1: SENSOR_NEUTRAL_COLOR_POINT, Priority 2: COLOR_CORRECTION_GAINS, else cached)
        val extractedWb = extractCanonicalWbFromResult(result)
        val (wbGains, neutralPoint) = if (extractedWb != null) {
            cachedWbGains = extractedWb.first
            cachedNeutralPoint = extractedWb.second
            extractedWb
        } else {
            val prevWb = cachedWbGains
            val prevNp = cachedNeutralPoint
            if (prevWb != null && prevNp != null) {
                prevWb to prevNp
            } else {
                DEFAULT_WB_GAINS.copyOf() to DEFAULT_NEUTRAL_POINT.copyOf()
            }
        }

        // DNG CCM from ForwardMatrix1/2 & ColorMatrix1/2 with Robertson CCT + WB strategy
        val fm1 = chars?.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1)?.let { colorSpaceTransformToFloatArray(it) }
        val fm2 = chars?.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2)?.let { colorSpaceTransformToFloatArray(it) }
        val cm1 = chars?.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)?.let { colorSpaceTransformToFloatArray(it) }
        val cm2 = chars?.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2)?.let { colorSpaceTransformToFloatArray(it) }
        val illum1 = chars?.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1)?.toInt() ?: 21
        val illum2 = chars?.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)?.toInt() ?: 17
        val activeCcm = computeRenderCcmColMajor(
            fm1 = fm1,
            fm2 = fm2,
            cm1 = cm1,
            cm2 = cm2,
            illum1 = illum1,
            illum2 = illum2,
            neutralPoint = neutralPoint,
            wbGains = wbGains,
            wbConfig = wbConfigProvider()
        )

        // Lens Shading Map (LSC), falling back to last valid frame LSC if result arrived late
        val lscTriple = if (result != null) SensorCalibrationHelper.extractLensShading(result) else Triple(null, 0, 0)
        val lscSnapshot = if (lscTriple.first != null && lscTriple.second > 1 && lscTriple.third > 1) {
            CachedLsc(lscTriple.first!!, lscTriple.second, lscTriple.third).also { cachedLsc = it }
        } else {
            cachedLsc
        }
        val lscMap = lscSnapshot?.map
        val lscRows = lscSnapshot?.rows ?: 0
        val lscCols = lscSnapshot?.cols ?: 0

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
