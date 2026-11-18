package top.maary.darkbag.camera.zsl

import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.util.Log
import kotlin.math.abs
import top.maary.darkbag.processor.ColorProcessor
import top.maary.darkbag.fragments.HdrPlusBurst
import java.nio.ByteBuffer
import java.util.ArrayDeque

/**
 * One copied RAW frame plus the exact CaptureResult it belongs to.
 *
 * The ring never retains [android.media.Image] objects: it copies into a direct buffer and closes
 * the Image immediately. That keeps [android.media.ImageReader] slots available for bursts.
 */
data class ZslFrame(
    val buffer: ByteBuffer,
    val width: Int,
    val height: Int,
    val rowStride: Int,
    val timestamp: Long,
    @Volatile var result: TotalCaptureResult?,
    @Volatile var iso: Int?,
    @Volatile var exposureTimeNs: Long?,
    @Volatile var wb: FloatArray?,
    @Volatile var cropKey: String?,
    @Volatile var afFocused: Boolean,
    val lensId: String?,
    val zoomRatio: Float,
    val rotationDegrees: Int,
    val rowLength: Int
)

/**
 * Bounded RAW ring for application-side ZSL. Copy happens on the ZSL RAW thread.
 * Selection is deterministic and pure enough to be tested through the public snapshots.
 */
class ZslRawRing(
    val config: ZslRingConfig,
    val cameraId: String,
    private val lensId: String?,
    private val physicalId: String?,
    private val zoomRatio: Float,
    private val rotationDegrees: Int
) {
    fun matches(cameraId: String, lensId: String?, physicalId: String?, zoomRatio: Float): Boolean =
        this.cameraId == cameraId &&
            this.lensId == lensId &&
            this.physicalId == physicalId &&
            abs(this.zoomRatio - zoomRatio) < 0.01f

    companion object {
        private const val TAG = "ZslRawRing"
    }

    private val lock = Any()
    private val frames = ArrayDeque<ZslFrame>()

    @Volatile var receivedFrames: Int = 0
        private set
    @Volatile var firstFrameTimestampNs: Long = 0L
        private set
    @Volatile var lastFrameTimestampNs: Long = 0L
        private set

    val size: Int
        get() = synchronized(lock) { frames.size }

    fun offer(
        image: Image,
        result: TotalCaptureResult?,
        resultLookup: ((Long) -> TotalCaptureResult?)? = null
    ): Boolean {
        val plane = image.planes.firstOrNull() ?: run {
            image.close()
            return false
        }
        val width = image.width
        val height = image.height
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val rowLength = width * pixelStride
        val dataLength = rowLength * height
        if (width <= 0 || height <= 0 || dataLength <= 0) {
            image.close()
            return false
        }
        var buffer = synchronized(lock) {
            if (frames.size >= config.depth && frames.isNotEmpty()) {
                frames.removeFirst().buffer
            } else {
                null
            }
        }
        if (buffer == null || buffer.capacity() < dataLength) {
            HdrPlusBurst.releaseBuffer(buffer)
            buffer = HdrPlusBurst.acquireBuffer(dataLength)
        }
        try {
            copyPlane(plane.buffer, rowStride, rowLength, width, height, buffer, dataLength)
            buffer.position(0)
            buffer.limit(dataLength)
            synchronized(lock) {
                // The Image can arrive before its CaptureResult. Re-read the result while
                // holding the ring lock, after the copy, so attachResult() cannot miss the frame.
                val resolvedResult = result ?: resultLookup?.invoke(image.timestamp)
                val frame = ZslFrame(
                    buffer = buffer,
                    width = width,
                    height = height,
                    rowStride = rowStride,
                    timestamp = image.timestamp,
                    result = resolvedResult,
                    iso = resolvedResult?.get(CaptureResult.SENSOR_SENSITIVITY),
                    exposureTimeNs = resolvedResult?.get(CaptureResult.SENSOR_EXPOSURE_TIME),
                    wb = extractWb(resolvedResult),
                    cropKey = resolvedResult?.get(CaptureResult.SCALER_CROP_REGION)?.toString(),
                    afFocused = isAfFocused(resolvedResult),
                    lensId = lensId,
                    zoomRatio = zoomRatio,
                    rotationDegrees = rotationDegrees,
                    rowLength = rowLength
                )
                while (frames.size >= config.depth) {
                    if (frames.isEmpty()) break
                    releaseFrame(frames.removeFirst())
                }
                frames.addLast(frame)
                receivedFrames++
                if (firstFrameTimestampNs == 0L) firstFrameTimestampNs = frame.timestamp
                lastFrameTimestampNs = frame.timestamp
            }
            return true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to copy ZSL RAW frame", e)
            HdrPlusBurst.releaseBuffer(buffer)
            return false
        } finally {
            image.close()
        }
    }

    fun snapshot(): List<ZslFrame> = synchronized(lock) { frames.toList() }

    /** Binds a CaptureResult that arrived after its Image (sync latency > 0). */
    fun attachResult(timestamp: Long, result: TotalCaptureResult) {
        synchronized(lock) {
            val frame = frames.firstOrNull { it.timestamp == timestamp } ?: return
            frame.result = result
            frame.iso = result.get(CaptureResult.SENSOR_SENSITIVITY)
            frame.exposureTimeNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
            frame.wb = extractWb(result)
            frame.cropKey = result.get(CaptureResult.SCALER_CROP_REGION)?.toString()
            frame.afFocused = isAfFocused(result)
        }
    }

    /** Removes exactly the supplied frame timestamps and transfers ownership to the caller. */
    fun take(framesToTake: List<ZslFrame>): List<ZslFrame> {
        if (framesToTake.isEmpty()) return emptyList()
        val wanted = framesToTake.map { it.timestamp }.toHashSet()
        synchronized(lock) {
            val taken = frames.filter { it.timestamp in wanted }
            frames.removeAll { it.timestamp in wanted }
            return taken
        }
    }

    /** Newest frame with enough metadata for the instant single-frame fallback. */
    fun latestValid(): ZslFrame? = synchronized(lock) {
        frames.lastOrNull { it.result != null && it.iso != null && it.exposureTimeNs != null }
    }

    fun nearest(t0Ns: Long): ZslFrame? {
        val all = synchronized(lock) { frames.toList() }
        val index = ZslFrameSelector.nearestIndex(all.map { it.toSample() }, t0Ns, config.maxAgeNs) ?: return null
        return all[index]
    }

    /**
     * Returns a stable pre-roll run for HDR+ ZSL, base-frame first.
     * Returns an empty list when fewer than 3 frames share exposure/WB/crop/AF state.
     */
    fun stableRun(t0Ns: Long, targetCount: Int): List<ZslFrame> {
        val all = synchronized(lock) { frames.toList() }
        val indices = ZslFrameSelector.stableRunIndices(
            samples = all.map { it.toSample() },
            t0Ns = t0Ns,
            targetCount = targetCount,
            maxAgeNs = config.maxAgeNs
        )
        if (indices.size < 3) return emptyList()
        return indices.map { all[it] }
    }

    fun clear() {
        val old = synchronized(lock) {
            val copy = frames.toList()
            frames.clear()
            copy
        }
        old.forEach { releaseFrame(it) }
    }

    fun releaseFrames(framesToRelease: List<ZslFrame>) {
        framesToRelease.forEach { releaseFrame(it) }
    }

    private fun ZslFrame.toSample(): ZslFrameSample = ZslFrameSample(
        timestamp = timestamp,
        hasResult = result != null,
        iso = iso,
        exposureTimeNs = exposureTimeNs,
        wb = wb,
        cropKey = cropKey,
        afFocused = afFocused
    )

    private fun extractWb(result: TotalCaptureResult?): FloatArray? {
        val gains = result?.get(CaptureResult.COLOR_CORRECTION_GAINS) ?: return null
        return floatArrayOf(gains.red, gains.greenEven, gains.greenOdd, gains.blue)
    }

    private fun isAfFocused(result: TotalCaptureResult?): Boolean {
        if (result == null) return false
        // Manual focus has no AF convergence state; accept it explicitly.
        if (result.get(CaptureResult.CONTROL_AF_MODE) == CaptureResult.CONTROL_AF_MODE_OFF) {
            return true
        }
        val af = result.get(CaptureResult.CONTROL_AF_STATE)
        return when (af) {
            CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED,
            CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED -> true
            CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN,
            CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN,
            CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED,
            CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED,
            CaptureResult.CONTROL_AF_STATE_INACTIVE -> false
            else -> result.get(CaptureResult.LENS_STATE) == CaptureResult.LENS_STATE_STATIONARY
        }
    }

    private fun copyPlane(
        source: ByteBuffer,
        rowStride: Int,
        rowLength: Int,
        width: Int,
        height: Int,
        target: ByteBuffer,
        targetCapacity: Int
    ) {
        target.clear()
        if (source.isDirect) {
            ColorProcessor.copyBayerWithStride(
                source, source.position(),
                target, 0,
                rowLength / 2, height, rowStride, 2
            )
        } else {
            val oldPos = source.position()
            val oldLimit = source.limit()
            try {
                for (y in 0 until height) {
                    val rowStart = y * rowStride
                    if (rowStart + rowLength > source.capacity()) break
                    source.position(rowStart)
                    source.limit(rowStart + rowLength)
                    target.put(source)
                }
            } finally {
                source.limit(oldLimit)
                source.position(oldPos)
            }
        }
        target.position(0)
        target.limit(targetCapacity)
    }

    private fun releaseFrame(frame: ZslFrame) {
        HdrPlusBurst.releaseBuffer(frame.buffer)
    }
}
