package top.maary.darkbag.processor

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

/**
 * Shared singleton accumulation dispatcher for Halide streaming accumulation.
 * Ensures that CPU-heavy Halide accumulation runs sequentially on a dedicated background thread
 * with lower priority than camera capture threads, completely preventing CPU core contention.
 */
object HdrPlusAccumulationDispatcher {
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "HdrPlusGlobalAccumWorker").apply {
            priority = Thread.NORM_PRIORITY - 1
        }
    }

    val dispatcher = executor.asCoroutineDispatcher()
    val scope = CoroutineScope(dispatcher + SupervisorJob())
}
