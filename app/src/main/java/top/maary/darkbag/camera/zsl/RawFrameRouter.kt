package top.maary.darkbag.camera.zsl

import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.graphics.ImageFormat
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong


internal enum class RawRouterMode { IDLE, RING, CAPTURE }

/** Pure routing state; all fields are guarded by RawFrameRouter.lock. */
internal data class RawRouterState(
    val mode: RawRouterMode = RawRouterMode.IDLE,
    val captureStarted: Boolean = false,
    val captureStartTimestampNs: Long = Long.MIN_VALUE,
    val activeCaptureGeneration: Long = 0L,
    val requireCaptureStarted: Boolean = true
) {
    fun beginCapture(generation: Long, requireCaptureStarted: Boolean = true): RawRouterState = RawRouterState(
        mode = RawRouterMode.CAPTURE,
        captureStarted = false,
        captureStartTimestampNs = Long.MIN_VALUE,
        activeCaptureGeneration = generation,
        requireCaptureStarted = requireCaptureStarted
    )

    fun onCaptureStarted(timestampNs: Long): RawRouterState =
        if (mode != RawRouterMode.CAPTURE || captureStarted) this
        else copy(captureStarted = true, captureStartTimestampNs = timestampNs)

    fun canDeliver(imageTimestampNs: Long): Boolean =
        if (!requireCaptureStarted) mode == RawRouterMode.CAPTURE
        else RawFrameRouter.isFreshCaptureFrame(captureStarted, captureStartTimestampNs, imageTimestampNs)

    fun endCapture(hasRing: Boolean): RawRouterState =
        RawRouterState(mode = if (hasRing) RawRouterMode.RING else RawRouterMode.IDLE)

    fun idle(): RawRouterState = RawRouterState()
}

/**
 * Single owner of the RAW ImageReader listener.
 *
 * The ring and the capture executors never call [ImageReader.setOnImageAvailableListener]
 * themselves while a router exists. Mode changes are serialized on the ZSL RAW thread so an
 * in-flight ring copy cannot be stolen by a capture (or vice versa).
 */
class RawFrameRouter(
    private val reader: ImageReader,
    private val handler: Handler,
    private val resultLookup: (Long) -> TotalCaptureResult?
) {
    companion object {
        private const val TAG = "RawFrameRouter"
        private const val SWITCH_TIMEOUT_MS = 250L
        private const val MAX_PENDING_PRE_START = 2

        /**
         * A frame is allowed into a still/burst consumer only after onCaptureStarted and only
         * if its sensor timestamp is not older than that capture's first exposure timestamp.
         */
        internal fun isFreshCaptureFrame(
            captureStarted: Boolean,
            captureStartTimestampNs: Long,
            imageTimestampNs: Long
        ): Boolean = captureStarted && imageTimestampNs >= captureStartTimestampNs
    }

    val callbackHandler: Handler
        get() = handler

    val hasActiveRepeatingConsumer: Boolean
        get() = synchronized(lock) { ring != null } || rawMotionTap != null

    private val lock = Any()
    private var state = RawRouterState()
    private var ring: ZslRawRing? = null
    private var captureConsumer: ((Image) -> Unit)? = null
    private val pendingPreStartImages = ArrayDeque<Image>()
    private var closed = false
    private val captureGeneration = AtomicLong(0)
    @Volatile var droppedImages: Long = 0L
        private set
    @Volatile var rawMotionTap: ((Image, TotalCaptureResult?) -> Unit)? = null
    @Volatile private var latestCaptureResult: TotalCaptureResult? = null

    internal fun resolveMotionTapResult(timestampNs: Long): TotalCaptureResult? =
        resultLookup(timestampNs) ?: latestCaptureResult

    private val listener = ImageReader.OnImageAvailableListener { r ->
        val currentMode = synchronized(lock) { state.mode }
        val image = try {
            if (currentMode == RawRouterMode.CAPTURE) r.acquireNextImage() else r.acquireLatestImage()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire RAW image", e)
            null
        }
        if (image == null) {
            if (currentMode != RawRouterMode.IDLE) droppedImages++
            return@OnImageAvailableListener
        }

        val tap = rawMotionTap
        if (image.format == ImageFormat.RAW_SENSOR && tap != null) {
            try {
                val tapResult = resolveMotionTapResult(image.timestamp)
                tap(image, tapResult)
            } catch (e: Exception) {
                Log.w(TAG, "rawMotionTap callback failed", e)
            }
        }

        try {
            when (currentMode) {
                RawRouterMode.RING -> {
                    val activeRing = synchronized(lock) { ring }
                    if (activeRing != null && image.format == ImageFormat.RAW_SENSOR) {
                        activeRing.offer(image, resultLookup(image.timestamp), resultLookup)
                    } else {
                        image.close()
                    }
                }

                RawRouterMode.CAPTURE -> {
                    var shouldBuffer = false
                    val (snapshot, consumer) = synchronized(lock) {
                        val s = state
                        val c = captureConsumer
                        if (s.requireCaptureStarted && !s.captureStarted && !closed && c != null) {
                            if (pendingPreStartImages.size >= MAX_PENDING_PRE_START) {
                                pendingPreStartImages.removeFirst().close()
                            }
                            pendingPreStartImages.addLast(image)
                            shouldBuffer = true
                        }
                        Pair(s, c)
                    }
                    if (shouldBuffer) {
                        return@OnImageAvailableListener
                    }
                    if (!snapshot.canDeliver(image.timestamp)) {
                        // A frame exposed before onCaptureStarted belongs to the old repeating
                        // request; feeding it to the still/burst consumer would capture the
                        // wrong moment.
                        image.close()
                    } else if (consumer != null) {
                        consumer(image)
                    } else {
                        image.close()
                    }
                }

                RawRouterMode.IDLE -> image.close()
            }
        } catch (e: Exception) {
            Log.w(TAG, "RAW frame dispatch failed", e)
            image.close()
        }
    }

    init {
        reader.setOnImageAvailableListener(listener, handler)
    }

    fun onCaptureResult(result: TotalCaptureResult) {
        latestCaptureResult = result
        val timestamp = result.get(android.hardware.camera2.CaptureResult.SENSOR_TIMESTAMP) ?: return
        synchronized(lock) { ring }?.attachResult(timestamp, result)
    }

    fun installRing(newRing: ZslRawRing?) {
        handler.post {
            synchronized(lock) {
                if (closed) return@post
                if (state.activeCaptureGeneration != 0L) {
                    Log.w(TAG, "Ignoring ring install while a capture is active")
                    return@post
                }
                captureConsumer = null
                state = state.endCapture(hasRing = newRing != null)
                ring = newRing
            }
            drainQueued()
        }
    }

    fun beginCapture(consumer: (Image) -> Unit, requireCaptureStarted: Boolean = true): Boolean {
        val latch = CountDownLatch(1)
        val generation = captureGeneration.incrementAndGet()
        val abandoned = AtomicBoolean(false)
        val activated = AtomicBoolean(false)
        var detachedRing: ZslRawRing? = null
        handler.post {
            synchronized(lock) {
                if (closed || abandoned.get() || generation != captureGeneration.get()) {
                    latch.countDown()
                    return@post
                }
                clearPendingPreStartLocked()
                detachedRing = ring
                ring = null
                captureConsumer = consumer
                state = state.beginCapture(generation, requireCaptureStarted)
                activated.set(true)
            }
            drainQueued()
            latch.countDown()
        }
        val switched = try {
            latch.await(SWITCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!switched) {
            abandoned.set(true)
            captureGeneration.incrementAndGet()
            handler.post {
                synchronized(lock) {
                    if (state.activeCaptureGeneration == generation && state.mode == RawRouterMode.CAPTURE) {
                        clearPendingPreStartLocked()
                        state = state.idle()
                        captureConsumer = null
                    }
                }
                drainQueued()
            }
        }
        detachedRing?.clear()
        return switched && activated.get()
    }

    /**
     * Synchronously stops ring delivery and returns the ring so the caller can clear it
     * without racing an in-flight offer(). Used before forward captures and disarm.
     */
    fun detachRing(): ZslRawRing? {
        captureGeneration.incrementAndGet()
        val latch = CountDownLatch(1)
        var detached: ZslRawRing? = null
        handler.post {
            synchronized(lock) {
                clearPendingPreStartLocked()
                detached = ring
                ring = null
                captureConsumer = null
                state = state.idle()
            }
            drainQueued()
            latch.countDown()
        }
        try {
            latch.await(SWITCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return detached
    }

    fun onCaptureStarted(timestampNs: Long) {
        val (snapshot, consumer, toFlush) = synchronized(lock) {
            state = state.onCaptureStarted(timestampNs)
            val buffered = if (pendingPreStartImages.isNotEmpty()) {
                ArrayList<Image>(pendingPreStartImages.size).also { list ->
                    while (pendingPreStartImages.isNotEmpty()) {
                        list.add(pendingPreStartImages.removeFirst())
                    }
                }
            } else emptyList()
            Triple(state, captureConsumer, buffered)
        }
        if (toFlush.isNotEmpty()) {
            val flushBlock = Runnable {
                for (img in toFlush) {
                    if (snapshot.canDeliver(img.timestamp) && consumer != null) {
                        try {
                            consumer(img)
                        } catch (e: Exception) {
                            Log.w(TAG, "Buffered pre-start RAW frame dispatch failed", e)
                            img.close()
                        }
                    } else {
                        img.close()
                    }
                }
            }
            if (Looper.myLooper() == handler.looper) {
                flushBlock.run()
            } else {
                handler.post(flushBlock)
            }
        }
    }

    fun endCapture(restoreRing: ZslRawRing? = null) {
        captureGeneration.incrementAndGet()
        handler.post {
            synchronized(lock) {
                if (closed) return@post
                clearPendingPreStartLocked()
                captureConsumer = null
                ring = restoreRing
                state = state.endCapture(hasRing = restoreRing != null)
            }
        }
    }

    fun idle() {
        captureGeneration.incrementAndGet()
        handler.post {
            synchronized(lock) {
                if (closed) return@post
                clearPendingPreStartLocked()
                ring = null
                captureConsumer = null
                state = state.idle()
            }
        }
    }

    fun close() {
        latestCaptureResult = null
        val latch = CountDownLatch(1)
        handler.post {
            synchronized(lock) {
                closed = true
                clearPendingPreStartLocked()
                ring = null
                captureConsumer = null
                state = state.idle()
            }
            reader.setOnImageAvailableListener(null, null)
            drainQueued()
            latch.countDown()
        }
        try {
            latch.await(SWITCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun clearPendingPreStartLocked() {
        while (pendingPreStartImages.isNotEmpty()) {
            pendingPreStartImages.removeFirst().close()
        }
    }

    private fun drainQueued() {
        repeat(8) {
            val image = try {
                reader.acquireNextImage()
            } catch (_: Exception) {
                null
            } ?: return
            image.close()
        }
    }
}
