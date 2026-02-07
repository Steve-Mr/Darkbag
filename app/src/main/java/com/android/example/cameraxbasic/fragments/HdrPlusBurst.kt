package com.android.example.cameraxbasic.fragments

import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer

/**
 * Data class to hold a single frame of RAW data for HDR+ processing.
 * Uses a Direct ByteBuffer to store pixel data off-heap to prevent OOM.
 */
data class HdrFrame(
    val buffer: ByteBuffer,
    val width: Int,
    val height: Int,
    val timestamp: Long,
    val rotationDegrees: Int
) {
    /**
     * Explicitly clears the buffer.
     * Although Direct ByteBuffers are GC'd, this is a placeholder for potential future manual management.
     */
    fun close() {
        // No-op for standard ByteBuffer, but useful for tracking lifecycle.
    }
}

/**
 * Helper class to manage HDR+ burst capture.
 * Stores frames until the desired burst size is reached, then triggers processing.
 */
class HdrPlusBurst(
    private val frameCount: Int,
    private val onBurstComplete: (List<HdrFrame>) -> Unit
) {
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
                // If allocation fails (OOM), we should probably clear and abort,
                // but let's just log (though we don't have a logger here easily without TAG).
                // Ideally, we rethrow so the caller knows.
                image.close()
                frames.forEach { it.close() }
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
        frames.forEach { it.close() }
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

        // Allocate Direct ByteBuffer (Off-Heap)
        val cleanData = ByteBuffer.allocateDirect(dataLength)

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
