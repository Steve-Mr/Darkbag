package top.maary.darkbag.processor

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Shared singleton accumulation dispatcher for Halide streaming accumulation.
 * Uses a single dedicated worker thread (MAX_CONCURRENT_ACCUM_WORKERS = 1) to enforce
 * strict sequential FIFO execution of streaming accumulation sessions.
 *
 * Consecutive burst captures queue cleanly in FIFO order without thrashing CPU big cores
 * or saturating mobile LPDDR DRAM bandwidth. This prevents dual concurrent sessions from
 * slowing down exponentially (from ~2.8s to 26.2s) under rapid burst firing.
 */
object HdrPlusAccumulationDispatcher {
    const val MAX_CONCURRENT_ACCUM_WORKERS = 1
    private val workerId = AtomicInteger(1)

    private val executor = Executors.newFixedThreadPool(MAX_CONCURRENT_ACCUM_WORKERS) { r ->
        Thread(r, "HdrPlusAccumWorker-${workerId.getAndIncrement()}").apply {
            priority = Thread.NORM_PRIORITY - 1
        }
    }

    val dispatcher = executor.asCoroutineDispatcher()
    val scope = CoroutineScope(dispatcher + SupervisorJob())
}
