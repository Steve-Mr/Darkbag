package top.maary.darkbag.processor

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Shared singleton accumulation dispatcher for Halide streaming accumulation.
 * Uses a dual-worker thread pool (MAX_CONCURRENT_ACCUM_WORKERS = 2) to allow consecutive
 * burst captures to accumulate concurrently, eliminating multi-second queue head-of-line blocking
 * while maintaining lower thread priority than camera preview/capture threads.
 */
object HdrPlusAccumulationDispatcher {
    const val MAX_CONCURRENT_ACCUM_WORKERS = 2
    private val workerId = AtomicInteger(1)

    private val executor = Executors.newFixedThreadPool(MAX_CONCURRENT_ACCUM_WORKERS) { r ->
        Thread(r, "HdrPlusAccumWorker-${workerId.getAndIncrement()}").apply {
            priority = Thread.NORM_PRIORITY - 1
        }
    }

    val dispatcher = executor.asCoroutineDispatcher()
    val scope = CoroutineScope(dispatcher + SupervisorJob())
}
