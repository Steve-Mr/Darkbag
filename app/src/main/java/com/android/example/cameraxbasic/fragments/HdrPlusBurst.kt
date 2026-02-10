package com.android.example.cameraxbasic.fragments

import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer

/**
 * Data class to hold a single frame of RAW data for HDR+ processing.
 * Uses a Direct ByteBuffer to store pixel data off-heap to prevent OOM.
 */
data class HdrFrame(
    var buffer: ByteBuffer?,
    val width: Int,
    val height: Int,
    val timestamp: Long,
    val rotationDegrees: Int
) {
    /**
     * Explicitly clears the buffer reference to assist GC.
     */
    fun close() {
        buffer = null
    }
}

import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Helper class to manage HDR+ burst capture.
 * Stores frames until the desired burst size is reached, then triggers processing.
 */
class HdrPlusBurst(
    private val frameCount: Int,
    private val onBurstComplete: (List<HdrFrame>) -> Unit
) {
    companion object {
        private const val MAX_POOL_SIZE = 10
        private val bufferPool = ConcurrentLinkedQueue<ByteBuffer>()

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

    fun addFrame(image: ImageProxy) {
        if (frames.size < frameCount) {
            try {
                // Extract frame data immediately to release the ImageProxy buffer
                val frame = copyFrame(image)
                frames.add(frame)

                if (frames.size == frameCount) {
                    onBurstComplete(frames.toList())
                    frames.clear() // Ownership transferred to consumer
                }
            } catch (e: Exception) {
                // If allocation fails (OOM), we clear and abort.
                // Do NOT call image.close() here, as it's handled in finally block.
                frames.forEach {
                    releaseBuffer(it.buffer)
                    it.close()
                }
                frames.clear()
                throw e
            } finally {
                // Always close the original image to free the camera pipeline buffer
                image.close()
            }
        } else {
            image.close()
        }
    }

    fun reset() {
        frames.forEach {
            releaseBuffer(it.buffer)
            it.close()
        }
        frames.clear()
    }

    private fun copyFrame(image: ImageProxy): HdrFrame {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val width = image.width
        val height = image.height
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride // Should be 2 for RAW16

        // Calculate tight-packed size
        val rowLength = width * pixelStride
        val dataLength = rowLength * height

        // Use pooled Direct ByteBuffer
        val cleanData = acquireBuffer(dataLength)

        // Copy logic (handling stride padding)
        buffer.rewind()
        if (rowStride == rowLength) {
            // Fast path: Data is already tightly packed
            if (buffer.remaining() == dataLength) {
                cleanData.put(buffer)
            } else {
                // Buffer might be larger due to alignment, limit it
                val oldLimit = buffer.limit()
                buffer.limit(buffer.position() + dataLength)
                cleanData.put(buffer)
                buffer.limit(oldLimit)
            }
        } else {
            // Slow path: Remove padding bytes from each row
            val rowData = ByteArray(rowLength)
            for (y in 0 until height) {
                val rowStart = y * rowStride
                if (rowStart + rowLength > buffer.capacity()) break

                buffer.position(rowStart)
                buffer.get(rowData)
                cleanData.put(rowData)
            }
        }

        cleanData.flip() // Prepare for reading

        return HdrFrame(
            buffer = cleanData,
            width = width,
            height = height,
            timestamp = image.imageInfo.timestamp,
            rotationDegrees = image.imageInfo.rotationDegrees
        )
    }
}
