package top.maary.darkbag.motionphoto

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import android.media.Image
import android.os.SystemClock
import top.maary.darkbag.processor.LutSurfaceProcessor
import top.maary.darkbag.rawvideo.RawVideoNative
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
    private val nativeSubsampler: (src: ByteBuffer, w: Int, h: Int, stride: Int, dst: ByteBuffer) -> Int = { src, w, h, stride, dst ->
        RawVideoNative.nativeSubsampleBayer4x4(src, w, h, stride, dst)
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
        timestampNs: Long
    ) -> Unit)? = null
) {
    companion object {
        private const val TAG = "RawMotionPhotoRelay"
        const val INITIAL_BUFFER_CAPACITY = 1024 * 768 * 2
        const val MIN_FRAME_INTERVAL_NS = 33_000_000L // Cap live stream at ~30 fps
    }

    private val bufferLock = Any()
    private var buffers = arrayOf(
        ByteBuffer.allocateDirect(INITIAL_BUFFER_CAPACITY),
        ByteBuffer.allocateDirect(INITIAL_BUFFER_CAPACITY)
    )
    private val bufferIndex = AtomicInteger(0)
    private val isRunning = AtomicBoolean(false)
    private var lastSensorTimestampNs = 0L

    private val cachedCcm = FloatArray(9) { if (it % 4 == 0) 1.0f else 0.0f }
    private val cachedWbGains = floatArrayOf(2.0f, 1.0f, 1.6f)
    private val cachedBlackLevel = floatArrayOf(64f, 64f, 64f, 64f)

    val currentBufferCapacity: Int
        get() = synchronized(bufferLock) { buffers[0].capacity() }

    fun start() {
        isRunning.set(true)
        lastSensorTimestampNs = 0L
    }

    fun stop() {
        isRunning.set(false)
    }

    private fun ensureBufferCapacity(requiredBytes: Int) {
        synchronized(bufferLock) {
            if (buffers[0].capacity() < requiredBytes) {
                val newCap = maxOf(requiredBytes, buffers[0].capacity() * 2)
                buffers = arrayOf(
                    ByteBuffer.allocateDirect(newCap),
                    ByteBuffer.allocateDirect(newCap)
                )
            }
        }
    }

    fun onRawFrame(image: Image, result: TotalCaptureResult?) {
        if (!isRunning.get()) return

        // Throttle to <= 30 fps using the camera sensor timestamp
        if (image.timestamp - lastSensorTimestampNs < MIN_FRAME_INTERVAL_NS) {
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

        val expectedDstWidth = ((srcWidth / 4) and 1.inv())
        val expectedDstHeight = ((srcHeight / 4) and 1.inv())
        val requiredBytes = expectedDstWidth * expectedDstHeight * 2
        ensureBufferCapacity(requiredBytes)

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

        // Pick next buffer in double-buffer rotation
        val idx = bufferIndex.getAndIncrement() and 1
        val dstBuffer = synchronized(bufferLock) { buffers[idx] }
        dstBuffer.clear()

        srcBuffer.position(0)
        val packedDims = try {
            nativeSubsampler(srcBuffer, srcWidth, srcHeight, rowStride, dstBuffer)
        } catch (_: UnsatisfiedLinkError) {
            0
        }
        if (packedDims == 0) return

        val dstWidth = (packedDims ushr 16) and 0xFFFF
        val dstHeight = packedDims and 0xFFFF
        if (dstWidth == 0 || dstHeight == 0) return

        dstBuffer.position(0)
        dstBuffer.limit(dstWidth * dstHeight * 2)

        lastSensorTimestampNs = image.timestamp

        val activeConsumer = frameConsumer ?: { buf, w, h, cfa, bl, wl, wb, ccm, ts ->
            processor?.submitRawMotionFrame(buf, w, h, cfa, bl, wl, wb, ccm, ts)
        }

        activeConsumer(
            dstBuffer,
            dstWidth,
            dstHeight,
            cfaPattern,
            blackLevel,
            whiteLevel,
            wbGains,
            activeCcm,
            monotonicTs
        )
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
