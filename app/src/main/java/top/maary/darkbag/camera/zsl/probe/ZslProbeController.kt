package top.maary.darkbag.camera.zsl.probe

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import top.maary.darkbag.camera.zsl.RingConfig
import java.util.concurrent.atomic.AtomicBoolean

/** 探测结果：完成（含报告）/ 失败（固定原因码）/ 用户中止。 */
sealed class ZslProbeResult {
    data class Completed(val report: ZslProbeReport) : ZslProbeResult()

    data class Failed(val reason: ZslProbeFailure, val detail: String? = null) : ZslProbeResult()

    data object Aborted : ZslProbeResult()
}

/**
 * 主动探测编排：W0 基线 1500 ms → W1 双流 4000 ms → W2 恢复 1000 ms，看门狗 15 s。
 *
 * 约束（§3.8.3 / §3.8.7）：
 * - 只通过 [ZslProbeSession] 端口操作相机，不 import 任何 Camera2 类型；
 * - 等待一律走 [ZslProbeScheduler]，按 [PAUSE_SLICE_MS] 分片，每片检查中止标志与协程活性，
 *   因此 [abort] 后最多一片（100 ms）即退出（S1-B1 F3）；整体另有 `withTimeoutOrNull` 硬上界；
 * - 帧回调只计数/记录（`synchronized` 保护），不拷贝、不 IPC；
 * - **任何路径**（正常 / 启动失败 / 帧不足 / 异常 / 看门狗 / 中止）都恰好一次
 *   [ZslProbeSession.endDualStream] 且恰好一次 `setFrameObserver(null)`（幂等守卫）；
 * - 恢复失败时结论强制 `Failed(CAPTURE_FAILED, "restore failed")`，且**优先于** Completed / Aborted：
 *   "报告成功但相机仍停在双流请求上"比失败更危险（S1-B1 F2）；
 * - 协作式取消（[CancellationException]）不吞成 Failed：先恢复，再原样抛出。
 *
 * 预热耗时用 W1 首充填（第 [WARMUP_FRAMES] 帧）近似；W3 复测需端口支持重新武装，本阶段不做。
 */
class ZslProbeController(
    private val session: ZslProbeSession,
    private val scheduler: ZslProbeScheduler,
    private val clock: ZslProbeClock,
    private val deviceFingerprint: String,
    private val lensId: String,
    /** 看门狗上限；生产固定 [WATCHDOG_MS]，测试可注入极小值验证 `withTimeoutOrNull` 路径。 */
    private val watchdogMs: Long = WATCHDOG_MS
) {
    private val running = AtomicBoolean(false)
    private val aborted = AtomicBoolean(false)

    val isRunning: Boolean get() = running.get()

    /** 置中止标志；`run` 在下一个分片检查点返回 [ZslProbeResult.Aborted] 并完成恢复。 */
    fun abort() {
        aborted.set(true)
    }

    /**
     * 执行一次完整探测。
     *
     * @param targetFps 目标帧率，由调用方依据静态能力选择（见 [ZslProbeTargetFps.choose]）；
     *   标称帧间隔即 `1000 / targetFps`，不再写死 30，否则 15 fps 设备会被按 33 ms 标称误判
     *   RAW_JITTER / RAW_STALL（S1-B1 F6）。
     */
    suspend fun run(targetFps: Int): ZslProbeResult {
        if (!running.compareAndSet(false, true)) {
            return ZslProbeResult.Failed(ZslProbeFailure.CAPTURE_FAILED, "probe already running")
        }
        aborted.set(false)
        val collector = SampleCollector(clock)
        val startNs = clock.nowNanos()
        var restored = false
        var restoreOk = false

        // 恢复只做一次（幂等）：先 endDualStream，无论成败都清一次观察者，返回整体成功标志。
        fun restoreOnce(): Boolean {
            if (restored) return restoreOk
            restored = true
            collector.setPhase(ProbePhase.IDLE)
            val ended = try {
                session.endDualStream()
            } catch (_: Throwable) {
                false
            }
            try {
                session.setFrameObserver(null)
            } catch (_: Throwable) {
                // 观察者清理尽力而为；失败已由 endDualStream 的标志反映
            }
            restoreOk = ended
            return restoreOk
        }

        var cancelled: CancellationException? = null
        var result: ZslProbeResult? = null
        try {
            // 硬上界：分片等待只能感知标志，看门狗兜底任何"卡死在某次 IPC 上"的路径
            result = withTimeoutOrNull(watchdogMs) {
                runWindows(targetFps, collector, startNs) { restoreOnce() }
            } ?: ZslProbeResult.Failed(ZslProbeFailure.RAW_STALL, watchdogDetail())
        } catch (ce: CancellationException) {
            cancelled = ce
        } catch (t: Throwable) {
            result = ZslProbeResult.Failed(
                reason = ZslProbeFailure.CAPTURE_FAILED,
                detail = t.message ?: t.javaClass.simpleName
            )
        } finally {
            restoreOk = restoreOnce()
            running.set(false)
        }

        // 取消优先：恢复已在 finally 完成，这里把取消原样抛回（不得吞成 Failed）
        cancelled?.let { throw it }
        // 恢复失败优先于 Completed / Aborted（S1-B1 F2）
        if (!restoreOk) {
            return ZslProbeResult.Failed(ZslProbeFailure.CAPTURE_FAILED, RESTORE_FAILED_DETAIL)
        }
        return result ?: ZslProbeResult.Failed(ZslProbeFailure.CAPTURE_FAILED, NO_RESULT_DETAIL)
    }

    /** 三段测量窗口主体（由 [run] 在看门狗与恢复守卫内调用）。 */
    private suspend fun runWindows(
        targetFps: Int,
        collector: SampleCollector,
        startNs: Long,
        restore: () -> Unit
    ): ZslProbeResult {
        session.setFrameObserver(collector)
        collector.setPhase(ProbePhase.BASELINE)

        // W0：基线窗口（保持现状，无 RAW target）
        if (!awaitWindow(WINDOW_BASELINE_MS)) return ZslProbeResult.Aborted
        if (watchdogExpired(startNs)) return watchdogFailure()
        val memoryBefore = safeAvailableMemory()
        val thermalBefore = safeThermalStatus()
        val baselinePreviewCount = collector.baselinePreviewCount()

        // W1：双流窗口（追加 RAW target，锁帧率）
        collector.setPhase(ProbePhase.DUAL)
        collector.markDualStart(clock.nowNanos())
        val dualStarted = try {
            session.beginDualStream(targetFps)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            return ZslProbeResult.Failed(
                reason = ZslProbeFailure.CAPTURE_FAILED,
                detail = "beginDualStream: ${t.message ?: t.javaClass.simpleName}"
            )
        }
        if (!dualStarted) {
            return ZslProbeResult.Failed(
                reason = ZslProbeFailure.CAPTURE_FAILED,
                detail = "beginDualStream returned false"
            )
        }
        if (!awaitWindow(WINDOW_DUAL_MS)) return ZslProbeResult.Aborted
        if (watchdogExpired(startNs)) return watchdogFailure()
        val dualPreviewCount = collector.dualPreviewCount()
        val snapshot = collector.snapshot()

        // W2：恢复窗口（先还原请求，再留 1000 ms 观察恢复）
        restore()
        collector.setPhase(ProbePhase.RECOVERY)
        if (!awaitWindow(WINDOW_RECOVERY_MS)) return ZslProbeResult.Aborted
        if (watchdogExpired(startNs)) return watchdogFailure()

        val sample = ZslProbeAggregator.aggregate(
            rawTimestampsNs = snapshot.rawTimestampsNs,
            ageSamplesMs = snapshot.ageSamplesMs,
            copySamplesMs = snapshot.copySamplesMs,
            previewBaselineCount = baselinePreviewCount,
            previewDualCount = dualPreviewCount,
            metadataHits = snapshot.metadataHits,
            metadataTotal = snapshot.rawTimestampsNs.size,
            warmupMs = snapshot.warmupMs,
            thermalBefore = thermalBefore,
            thermalAfter = safeThermalStatus(),
            memoryBefore = memoryBefore,
            memoryAfter = safeAvailableMemory(),
            captureFailures = snapshot.captureFailures,
            baselineWindowMs = WINDOW_BASELINE_MS,
            dualWindowMs = WINDOW_DUAL_MS
        )
        // 帧数过少时直接判 UNSUPPORTED（RAW_FPS_LOW），不进入阈值评分
        val outcome = if (sample.rawFrameCount < MIN_RAW_FRAMES) {
            ZslProbeOutcome(
                verdict = ZslProbeVerdict.UNSUPPORTED,
                failures = listOf(ZslProbeFailure.RAW_FPS_LOW),
                ringConfig = RingConfig.DISABLED
            )
        } else {
            ZslProbeScoring.evaluate(sample, 1000f / targetFps.coerceAtLeast(1))
        }
        val capabilities = runCatching { session.capabilities }.getOrNull()
        return ZslProbeResult.Completed(
            ZslProbeReport(
                version = ZslProbeReport.SCHEMA_VERSION,
                timestampMs = clock.currentTimeMillis(),
                deviceFingerprint = deviceFingerprint,
                lensId = lensId,
                rawWidth = capabilities?.rawWidth ?: 0,
                rawHeight = capabilities?.rawHeight ?: 0,
                verdict = outcome.verdict,
                failures = outcome.failures,
                sample = sample,
                ringConfig = outcome.ringConfig
            )
        )
    }

    /** 分片等待一个窗口：每 [PAUSE_SLICE_MS] 检查中止标志与协程活性；`false` 表示已中止。 */
    private suspend fun awaitWindow(ms: Long): Boolean {
        var remaining = ms
        while (remaining > 0L) {
            if (aborted.get()) return false
            currentCoroutineContext().ensureActive()
            val slice = remaining.coerceAtMost(PAUSE_SLICE_MS)
            scheduler.pause(slice)
            remaining -= slice
        }
        return !aborted.get()
    }

    private fun watchdogExpired(startNs: Long): Boolean =
        clock.nowNanos() - startNs > watchdogMs * 1_000_000L

    private fun watchdogFailure(): ZslProbeResult =
        ZslProbeResult.Failed(ZslProbeFailure.RAW_STALL, watchdogDetail())

    private fun watchdogDetail(): String = "watchdog: probe exceeded ${watchdogMs}ms"

    /** 热状态未知（`null`）原样上抛给判定层，不再伪装成 `THERMAL_STATUS_NONE`。 */
    private fun safeThermalStatus(): Int? = try {
        session.thermalStatus()
    } catch (_: AssertionError) {
        null
    } catch (_: RuntimeException) {
        null
    }

    private fun safeAvailableMemory(): Long = try {
        session.availableMemoryBytes()
    } catch (_: AssertionError) {
        0L
    } catch (_: RuntimeException) {
        0L
    }
}

private const val WINDOW_BASELINE_MS = 1500L
private const val WINDOW_DUAL_MS = 4000L
private const val WINDOW_RECOVERY_MS = 1000L
private const val WATCHDOG_MS = 15_000L
private const val PAUSE_SLICE_MS = 100L
private const val MIN_RAW_FRAMES = 5
private const val RESTORE_FAILED_DETAIL = "restore failed"
private const val NO_RESULT_DETAIL = "probe produced no result"
