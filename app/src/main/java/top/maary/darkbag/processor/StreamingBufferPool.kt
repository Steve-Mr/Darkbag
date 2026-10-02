package top.maary.darkbag.processor

import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Global static DirectByteBuffer pool for HDR+ streaming burst capture.
 * Reuses native DirectByteBuffers across bursts to achieve zero-allocation,
 * zero-page-fault fast capture.
 */
object StreamingBufferPool {
    private const val TAG = "StreamingBufferPool"
    private const val MAX_CACHED_BUFFERS = 8 // Up to 8 frames (~192MB max), matches burst size for zero-allocation recycling
    private val pool = ConcurrentLinkedQueue<ByteBuffer>()

    @Synchronized
    fun acquire(capacity: Int): ByteBuffer {
        var buf = pool.poll()
        if (buf != null && buf.capacity() < capacity) {
            ColorProcessor.freeDirectBuffer(buf)
            buf = null
        }
        if (buf == null) {
            buf = ColorProcessor.allocateDirectBuffer(capacity.toLong())
                ?: ByteBuffer.allocateDirect(capacity)
            Log.d(TAG, "Allocated direct buffer of capacity $capacity (pool size: ${pool.size})")
        }
        buf.clear()
        return buf
    }

    fun release(buffer: ByteBuffer?) {
        if (buffer != null && buffer.isDirect) {
            if (pool.size < MAX_CACHED_BUFFERS) {
                buffer.clear()
                pool.offer(buffer)
            } else {
                ColorProcessor.freeDirectBuffer(buffer)
            }
        }
    }

    fun clear() {
        var buf = pool.poll()
        var count = 0
        while (buf != null) {
            ColorProcessor.freeDirectBuffer(buf)
            count++
            buf = pool.poll()
        }
        Log.d(TAG, "Cleared $count buffers from StreamingBufferPool")
    }
}
