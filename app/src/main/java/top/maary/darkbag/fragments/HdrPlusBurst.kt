package top.maary.darkbag.fragments

import top.maary.darkbag.processor.ColorProcessor
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Data class to hold a single frame of RAW data for HDR+ processing.
 * Uses a Direct ByteBuffer to store pixel data off-heap to prevent OOM.
 */
data class HdrFrame(
    // We no longer keep a separate buffer per frame, it is now all in megaBuffer
    val width: Int,
    val height: Int,
    val timestamp: Long,
    val rotationDegrees: Int,
    val physicalId: String? = null
) {
    /**
     * Explicitly clears the buffer reference to assist GC.
     */
    fun close() {
    }
}

data class BurstResult(
    val megaBuffer: ByteBuffer,
    val frames: List<HdrFrame>
)

/**
 * Helper class to manage HDR+ burst capture.
 * Stores frames until the desired burst size is reached, then triggers processing.
 */
class HdrPlusBurst(
    private val frameCount: Int,
    private val onBurstComplete: (BurstResult) -> Unit
) {
    companion object {
        private const val MAX_POOL_SIZE = 3
        private val bufferPool = ConcurrentLinkedQueue<ByteBuffer>()

        /**
         * Clears all pooled ByteBuffers to free native memory.
         */
        fun clearPool() {
            var buf = bufferPool.poll()
            while (buf != null) {
                ColorProcessor.freeDirectBuffer(buf)
                buf = bufferPool.poll()
            }
        }

        /**
         * Returns a Direct ByteBuffer of at least [capacity] from the pool,
         * or allocates a new one if necessary using native memory to avoid Dalvik OOM.
         */
        fun acquireBuffer(capacity: Int): ByteBuffer {
            var buffer = bufferPool.poll()
            if (buffer != null && buffer.capacity() < capacity) {
                ColorProcessor.freeDirectBuffer(buffer)
                buffer = null
            }
            if (buffer == null) {
                buffer = ColorProcessor.allocateDirectBuffer(capacity.toLong())
                    ?: ByteBuffer.allocateDirect(capacity)
            }
            buffer.clear()
            return buffer
        }

        /**
         * Returns a buffer to the pool for reuse, or frees it if pool is full.
         */
        fun releaseBuffer(buffer: ByteBuffer?) {
            if (buffer != null && buffer.isDirect) {
                if (bufferPool.size < MAX_POOL_SIZE) {
                    bufferPool.offer(buffer)
                } else {
                    ColorProcessor.freeDirectBuffer(buffer)
                }
            }
        }
    }

    private val frames = mutableListOf<HdrFrame>()
    private var megaBuffer: ByteBuffer? = null

    /**
     * Entry point for manual Camera2 frames where we already have the buffer and metadata.
     */
    fun addManualFrame(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        timestamp: Long,
        rotationDegrees: Int,
        physicalId: String? = null
    ) {
        if (frames.size < frameCount) {
            try {
                val frame = copyData(
                    buffer, width, height, rowStride, pixelStride,
                    timestamp, rotationDegrees, physicalId
                )
                frames.add(frame)
                if (frames.size == frameCount) {
                    val resultBuffer = megaBuffer!!
                    megaBuffer = null // Transfer ownership
                    onBurstComplete(BurstResult(resultBuffer, frames.toList()))
                    frames.clear()
                }
            } catch (e: Exception) {
                megaBuffer?.let { releaseBuffer(it) }
                megaBuffer = null
                frames.clear()
                throw e
            }
        }
    }

    /**
     * Flushes whatever frames have been captured so far into a BurstResult,
     * transferring ownership of megaBuffer and clearing internal frames.
     * Used for partial burst fallback on watchdog timeout.
     */
    fun flush(): BurstResult? {
        if (frames.isNotEmpty() && megaBuffer != null) {
            val resultBuffer = megaBuffer!!
            megaBuffer = null
            val resultFrames = frames.toList()
            frames.clear()
            return BurstResult(resultBuffer, resultFrames)
        }
        return null
    }

    fun reset() {
        megaBuffer?.let { releaseBuffer(it) }
        megaBuffer = null
        frames.clear()
    }

    private fun copyData(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        timestamp: Long,
        rotationDegrees: Int,
        physicalId: String? = null
    ): HdrFrame {
        val rowLength = width * pixelStride
        val dataLength = rowLength * height

        if (megaBuffer == null) {
            megaBuffer = acquireBuffer(dataLength * frameCount)
        }
        val cleanData = megaBuffer!!
        cleanData.position(frames.size * dataLength)
        cleanData.limit(cleanData.position() + dataLength)

        if (buffer.isDirect && cleanData.isDirect) {
            ColorProcessor.copyBayerWithStride(
                buffer, buffer.position(),
                cleanData, cleanData.position(),
                width, height, rowStride, pixelStride
            )
        } else {
            val oldPos = buffer.position()
            buffer.rewind()
            if (rowStride == rowLength) {
                if (buffer.remaining() == dataLength) {
                    cleanData.put(buffer)
                } else {
                    val oldLimit = buffer.limit()
                    buffer.limit(buffer.position() + dataLength)
                    cleanData.put(buffer)
                    buffer.limit(oldLimit)
                }
            } else {
                val oldLimit = buffer.limit()
                for (y in 0 until height) {
                    val rowStart = y * rowStride
                    if (rowStart + rowLength > buffer.capacity()) break
                    buffer.position(rowStart)
                    buffer.limit(rowStart + rowLength)
                    cleanData.put(buffer)
                }
                buffer.limit(oldLimit)
            }
            buffer.position(oldPos)
        }
        
        // Reset limit and position of megaBuffer for future ops, though we rely on position management
        cleanData.limit(cleanData.capacity())

        return HdrFrame(
            width = width,
            height = height,
            timestamp = timestamp,
            rotationDegrees = rotationDegrees,
            physicalId = physicalId
        )
    }
}
