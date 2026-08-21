package top.maary.darkbag.fragments

import androidx.camera.core.ImageProxy
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
            bufferPool.clear()
        }

        /**
         * Returns a Direct ByteBuffer of at least [capacity] from the pool,
         * or allocates a new one if necessary.
         */
        fun acquireBuffer(capacity: Int): ByteBuffer {
            var buffer = bufferPool.poll()
            if (buffer == null || buffer.capacity() < capacity) {
                buffer = ByteBuffer.allocateDirect(capacity)
            }
            buffer.clear()
            return buffer
        }

        /**
         * Returns a buffer to the pool for reuse.
         */
        fun releaseBuffer(buffer: ByteBuffer?) {
            if (buffer != null && buffer.isDirect && bufferPool.size < MAX_POOL_SIZE) {
                bufferPool.offer(buffer)
            }
        }
    }

    private val frames = mutableListOf<HdrFrame>()
    private var megaBuffer: ByteBuffer? = null

    fun addFrame(image: ImageProxy, physicalId: String? = null) {
        if (frames.size < frameCount) {
            try {
                val frame = copyFrame(image, physicalId)
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
            } finally {
                image.close()
            }
        } else {
            image.close()
        }
    }

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

    fun reset() {
        megaBuffer?.let { releaseBuffer(it) }
        megaBuffer = null
        frames.clear()
    }

    private fun copyFrame(image: ImageProxy, physicalId: String? = null): HdrFrame {
        val plane = image.planes[0]
        return copyData(
            plane.buffer,
            image.width,
            image.height,
            plane.rowStride,
            plane.pixelStride,
            image.imageInfo.timestamp,
            image.imageInfo.rotationDegrees,
            physicalId
        )
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
