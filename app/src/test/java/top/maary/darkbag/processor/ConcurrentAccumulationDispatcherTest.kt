package top.maary.darkbag.processor

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ConcurrentAccumulationDispatcherTest {

    @Before
    fun setUp() {
        StreamingBufferPool.clear()
    }

    @After
    fun tearDown() {
        StreamingBufferPool.clear()
    }

    @Test
    fun testDispatcher_DualWorkerConcurrencyAndPriority() = runBlocking {
        assertEquals(2, HdrPlusAccumulationDispatcher.MAX_CONCURRENT_ACCUM_WORKERS)

        val latchStarted = CountDownLatch(2)
        val latchRelease = CountDownLatch(1)
        val workerThreadNames = mutableListOf<String>()
        val workerPriorities = mutableListOf<Int>()
        val executedCount = AtomicInteger(0)

        val job1 = HdrPlusAccumulationDispatcher.scope.launch {
            synchronized(workerThreadNames) {
                workerThreadNames.add(Thread.currentThread().name)
                workerPriorities.add(Thread.currentThread().priority)
            }
            latchStarted.countDown()
            latchRelease.await(5, TimeUnit.SECONDS)
            executedCount.incrementAndGet()
        }

        val job2 = HdrPlusAccumulationDispatcher.scope.launch {
            synchronized(workerThreadNames) {
                workerThreadNames.add(Thread.currentThread().name)
                workerPriorities.add(Thread.currentThread().priority)
            }
            latchStarted.countDown()
            latchRelease.await(5, TimeUnit.SECONDS)
            executedCount.incrementAndGet()
        }

        // Both jobs MUST start concurrently without waiting for the other to complete
        val bothStarted = latchStarted.await(2, TimeUnit.SECONDS)
        assertTrue("Both dual accumulation workers should execute concurrently", bothStarted)

        latchRelease.countDown()
        job1.join()
        job2.join()

        assertEquals(2, executedCount.get())
        synchronized(workerThreadNames) {
            assertEquals(2, workerThreadNames.size)
            workerThreadNames.forEach { name ->
                assertTrue("Worker thread name should match pattern: $name", name.startsWith("HdrPlusAccumWorker-"))
            }
            workerPriorities.forEach { priority ->
                assertEquals("Worker thread priority should be NORM_PRIORITY - 1", Thread.NORM_PRIORITY - 1, priority)
            }
        }
    }

    @Test
    fun testStreamingBufferPool_AcquireAndReleaseRecycling() {
        val capacity = 1024 * 1024 // 1MB
        val buf1 = StreamingBufferPool.acquire(capacity)
        assertNotNull(buf1)
        assertTrue(buf1.isDirect)
        assertEquals(0, buf1.position())
        assertTrue(buf1.capacity() >= capacity)

        // Write some dummy data
        buf1.put(0, 0x5A.toByte())

        // Release back to pool
        StreamingBufferPool.release(buf1)

        // Acquire again should recycle the existing buffer
        val buf2 = StreamingBufferPool.acquire(capacity)
        assertNotNull(buf2)
        assertSame("Acquired buffer should be recycled from the pool", buf1, buf2)
        assertEquals(0, buf2.position())

        StreamingBufferPool.release(buf2)
    }

    @Test
    fun testStreamingBufferPool_MaxLimitTwelveBuffers() {
        val capacity = 1024
        val buffers = mutableListOf<ByteBuffer>()

        // Acquire 15 buffers
        for (i in 0 until 15) {
            buffers.add(StreamingBufferPool.acquire(capacity))
        }

        // Release all 15 buffers back to pool
        buffers.forEach { StreamingBufferPool.release(it) }

        // Pool caches at most 12 buffers
        // We should be able to acquire 12 buffers without allocating new ones
        val recycled = mutableListOf<ByteBuffer>()
        for (i in 0 until 12) {
            recycled.add(StreamingBufferPool.acquire(capacity))
        }

        // All 12 acquired buffers should belong to the originally released buffers set
        recycled.forEach { buf ->
            assertTrue("Recycled buffer should be one of the pooled buffers", buffers.contains(buf))
        }

        recycled.forEach { StreamingBufferPool.release(it) }
    }

    @Test
    fun testStreamingBufferPool_ClearEmptiesPool() {
        val capacity = 1024
        val buf = StreamingBufferPool.acquire(capacity)
        StreamingBufferPool.release(buf)

        // Clear the pool
        StreamingBufferPool.clear()

        // Acquire again: should still return a valid direct buffer
        val newBuf = StreamingBufferPool.acquire(capacity)
        assertNotNull(newBuf)
        assertTrue(newBuf.isDirect)
        StreamingBufferPool.release(newBuf)
    }

    @Test
    fun testStreamingBufferPool_MultiThreadedStress() {
        val threads = 8
        val iterationsPerThread = 50
        val latch = CountDownLatch(threads)
        val errors = AtomicInteger(0)

        for (i in 0 until threads) {
            Thread {
                try {
                    for (j in 0 until iterationsPerThread) {
                        val buf = StreamingBufferPool.acquire(4096)
                        assertNotNull(buf)
                        assertEquals(0, buf.position())
                        buf.put(0, (j and 0xFF).toByte())
                        StreamingBufferPool.release(buf)
                    }
                } catch (t: Throwable) {
                    errors.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }.start()
        }

        val completed = latch.await(5, TimeUnit.SECONDS)
        assertTrue("All threads should complete without timing out", completed)
        assertEquals("No errors should occur during concurrent pool stress", 0, errors.get())
    }
}
