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
    val rotationDegrees: Int,
    val physicalId: String? = null
) {
    /**
     * Explicitly clears the buffer reference to assist GC.
     */
    fun close() {
        buffer = null
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

    fun addFrame(image: ImageProxy, physicalId: String? = null) {
        if (frames.size < frameCount) {
            try {
                val plane = image.planes[0]
                val frame = copyData(
                    plane.buffer,
                    image.width,
                    image.height,
                    plane.rowStride,
                    plane.pixelStride,
                    image.imageInfo.timestamp,
                    image.imageInfo.rotationDegrees,
                    physicalId
                )
                frames.add(frame)

                if (frames.size == frameCount) {
                    onBurstComplete(frames.toList())
                    frames.clear()
                }
            } catch (e: Exception) {
                frames.forEach { it.close() }
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
                    onBurstComplete(frames.toList())
                    frames.clear()
                }
            } catch (e: Exception) {
                frames.forEach { it.close() }
                frames.clear()
                throw e
            }
        }
    }

    fun reset() {
        frames.forEach { it.close() }
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
        val cleanData = ByteBuffer.allocateDirect(dataLength)

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
            val rowData = ByteArray(rowLength)
            for (y in 0 until height) {
                val rowStart = y * rowStride
                if (rowStart + rowLength > buffer.capacity()) break
                buffer.position(rowStart)
                buffer.get(rowData)
                cleanData.put(rowData)
            }
        }
        buffer.position(oldPos)
        cleanData.flip()

        return HdrFrame(
            buffer = cleanData,
            width = width,
            height = height,
            timestamp = timestamp,
            rotationDegrees = rotationDegrees,
            physicalId = physicalId
        )
    }
}
