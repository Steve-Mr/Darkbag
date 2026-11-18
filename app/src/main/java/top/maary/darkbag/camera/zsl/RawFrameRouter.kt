package top.maary.darkbag.camera.zsl

import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

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

    private enum class Mode { IDLE, RING, CAPTURE }

    private val lock = Any()
    private var mode = Mode.IDLE
    private var ring: ZslRawRing? = null
    private var captureConsumer: ((Image) -> Unit)? = null
    private var closed = false
    @Volatile private var captureStarted = false
    @Volatile private var captureStartTimestampNs = Long.MIN_VALUE
    private val captureGeneration = AtomicLong(0)
    @Volatile private var activeCaptureGeneration = 0L

    private val listener = ImageReader.OnImageAvailableListener { r ->
        val currentMode = synchronized(lock) { mode }
        val image = try {
            if (currentMode == Mode.CAPTURE) r.acquireNextImage() else r.acquireLatestImage()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire RAW image", e)
            null
        } ?: return@OnImageAvailableListener

        try {
            when (currentMode) {
                Mode.RING -> {
                    val activeRing = synchronized(lock) { ring }
                    if (activeRing != null) {
                        activeRing.offer(image, resultLookup(image.timestamp))
                    } else {
                        image.close()
                    }
                }

                Mode.CAPTURE -> {
                    val started = captureStarted
                    val startTs = captureStartTimestampNs
                    val consumer = synchronized(lock) { captureConsumer }
                    if (!isFreshCaptureFrame(started, startTs, image.timestamp)) {
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

                Mode.IDLE -> image.close()
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
                if (activeCaptureGeneration != 0L) {
                    Log.w(TAG, "Ignoring ring install while a capture is active")
                    return@post
                }
                captureConsumer = null
                mode = if (newRing != null) Mode.RING else Mode.IDLE
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
                mode = Mode.CAPTURE
                captureStarted = false
                captureStartTimestampNs = Long.MIN_VALUE
                activeCaptureGeneration = generation
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
                    if (activeCaptureGeneration == generation && mode == Mode.CAPTURE) {
                        activeCaptureGeneration = 0L
                        mode = Mode.IDLE
                        captureConsumer = null
                        captureStarted = false
                        captureStartTimestampNs = Long.MIN_VALUE
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
                mode = Mode.IDLE
                captureStarted = false
                captureStartTimestampNs = Long.MIN_VALUE
                activeCaptureGeneration = 0L
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
        synchronized(lock) {
            if (mode != Mode.CAPTURE) return
            if (!captureStarted) {
                captureStartTimestampNs = timestampNs
                captureStarted = true
            }
        }
    }

    fun endCapture(restoreRing: ZslRawRing? = null) {
        captureGeneration.incrementAndGet()
        handler.post {
            synchronized(lock) {
                if (closed) return@post
                captureConsumer = null
                ring = restoreRing
                mode = if (restoreRing != null) Mode.RING else Mode.IDLE
                captureStarted = false
                captureStartTimestampNs = Long.MIN_VALUE
                activeCaptureGeneration = 0L
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
                mode = Mode.IDLE
                captureStarted = false
                captureStartTimestampNs = Long.MIN_VALUE
                activeCaptureGeneration = 0L
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
                mode = Mode.IDLE
                captureStarted = false
                captureStartTimestampNs = Long.MIN_VALUE
                activeCaptureGeneration = 0L
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
