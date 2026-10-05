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
    private const val MAX_CACHED_BUFFERS = 12 // Up to 12 frames (~288MB max), supports dual concurrent bursts with zero allocation
    private val pool = ConcurrentLinkedQueue<ByteBuffer>()

    private fun safeAllocate(capacity: Long): ByteBuffer? {
        return try {
            ColorProcessor.allocateDirectBuffer(capacity)
        } catch (e: UnsatisfiedLinkError) {
            null
        }
    }

    private fun safeFree(buf: ByteBuffer) {
        try {
            ColorProcessor.freeDirectBuffer(buf)
        } catch (e: UnsatisfiedLinkError) {
            // No-op in host unit test environments without native library
        }
    }

    @Synchronized
    fun acquire(capacity: Int): ByteBuffer {
        var buf = pool.poll()
        if (buf != null && buf.capacity() < capacity) {
            safeFree(buf)
            buf = null
        }
        if (buf == null) {
            buf = safeAllocate(capacity.toLong())
                ?: ByteBuffer.allocateDirect(capacity)
            Log.d(TAG, "Allocated direct buffer of capacity $capacity (pool size: ${pool.size})")
        }
        buf.clear()
        return buf
    }

    @Synchronized
    fun release(buffer: ByteBuffer?) {
        if (buffer != null && buffer.isDirect) {
            if (pool.size < MAX_CACHED_BUFFERS) {
                buffer.clear()
                pool.offer(buffer)
            } else {
                safeFree(buffer)
            }
        }
    }

    @Synchronized
    fun clear() {
        var buf = pool.poll()
        var count = 0
        while (buf != null) {
            safeFree(buf)
            count++
            buf = pool.poll()
        }
        Log.d(TAG, "Cleared $count buffers from StreamingBufferPool")
    }
}
