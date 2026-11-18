package top.maary.darkbag.camera.zsl

import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.graphics.ImageFormat
import android.media.ImageReader
import android.os.Handler
import android.util.Log
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
    val activeCaptureGeneration: Long = 0L
) {
    fun beginCapture(generation: Long): RawRouterState = RawRouterState(
        mode = RawRouterMode.CAPTURE,
        captureStarted = false,
        captureStartTimestampNs = Long.MIN_VALUE,
        activeCaptureGeneration = generation
    )

    fun onCaptureStarted(timestampNs: Long): RawRouterState =
        if (mode != RawRouterMode.CAPTURE || captureStarted) this
        else copy(captureStarted = true, captureStartTimestampNs = timestampNs)

    fun canDeliver(imageTimestampNs: Long): Boolean =
        RawFrameRouter.isFreshCaptureFrame(captureStarted, captureStartTimestampNs, imageTimestampNs)

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

    private val lock = Any()
    private var state = RawRouterState()
    private var ring: ZslRawRing? = null
    private var captureConsumer: ((Image) -> Unit)? = null
    private var closed = false
    private val captureGeneration = AtomicLong(0)

    private val listener = ImageReader.OnImageAvailableListener { r ->
        val currentMode = synchronized(lock) { state.mode }
        val image = try {
            if (currentMode == RawRouterMode.CAPTURE) r.acquireNextImage() else r.acquireLatestImage()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire RAW image", e)
            null
        } ?: return@OnImageAvailableListener

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
                    val snapshot = synchronized(lock) { state }
                    val consumer = synchronized(lock) { captureConsumer }
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

    fun beginCapture(consumer: (Image) -> Unit): Boolean {
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
                detachedRing = ring
                ring = null
                captureConsumer = consumer
                state = state.beginCapture(generation)
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
        synchronized(lock) { state = state.onCaptureStarted(timestampNs) }
    }

    fun endCapture(restoreRing: ZslRawRing? = null) {
        captureGeneration.incrementAndGet()
        handler.post {
            synchronized(lock) {
                if (closed) return@post
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
                ring = null
                captureConsumer = null
                state = state.idle()
            }
        }
    }

    fun close() {
        val latch = CountDownLatch(1)
        handler.post {
            synchronized(lock) {
                closed = true
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
