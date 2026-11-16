package top.maary.darkbag.camera.zsl.probe

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
 * 线程/时序约束（设计文档 §3.8.3 / §3.8.7）：
 * - 只通过 [ZslProbeSession] 端口操作相机，不 import 任何 Camera2 类型；
 * - `run` 是 suspend，等待一律走 [ZslProbeScheduler]，不阻塞线程；
 * - 帧回调只做计数/记录（内部 `synchronized` 保护），不拷贝、不 IPC；
 * - **任何路径（正常 / 启动失败 / 帧不足 / 异常 / 看门狗 / 中止）都恰好调用一次
 *   [ZslProbeSession.endDualStream] 并恰好一次 `setFrameObserver(null)`**，由幂等守卫保证。
 *
 * 预热耗时说明：S1-A 用 W1 首充填（第 [WARMUP_FRAMES] 帧到达）相对双流开始的时差近似；
 * 设计文档中的 W3 复测需要 S1-B 端口扩展（重新武装），本阶段不做第二次 begin。
 */
class ZslProbeController(
    private val session: ZslProbeSession,
    private val scheduler: ZslProbeScheduler,
    private val clock: ZslProbeClock,
    private val deviceFingerprint: String,
    private val lensId: String
) {
    private val running = AtomicBoolean(false)
    private val aborted = AtomicBoolean(false)

    val isRunning: Boolean get() = running.get()

    /** 置中止标志；`run` 在下一个检查点返回 [ZslProbeResult.Aborted] 并完成恢复。 */
    fun abort() {
        aborted.set(true)
    }

    suspend fun run(targetFps: Int = 30): ZslProbeResult {
        if (!running.compareAndSet(false, true)) {
            return ZslProbeResult.Failed(
                reason = ZslProbeFailure.CAPTURE_FAILED,
                detail = "probe already running"
            )
        }
        aborted.set(false)
        val startNs = clock.nowNanos()
        val collector = SampleCollector(clock)
        var restored = false

        fun restoreOnce() {
            if (restored) return
            restored = true
            collector.setPhase(ProbePhase.IDLE)
            runCatching { session.endDualStream() }
            runCatching { session.setFrameObserver(null) }
        }

        try {
            session.setFrameObserver(collector)
            collector.setPhase(ProbePhase.BASELINE)

            // W0：基线窗口（保持现状，无 RAW target）
            scheduler.pause(WINDOW_BASELINE_MS)
            if (watchdogExpired(startNs)) {
                return ZslProbeResult.Failed(ZslProbeFailure.RAW_STALL, WATCHDOG_DETAIL)
            }
            if (aborted.get()) return ZslProbeResult.Aborted

            val memoryBefore = safeAvailableMemory()
            val thermalBefore = safeThermalStatus()
            val baselinePreviewCount = collector.baselinePreviewCount()

            // W1：双流窗口（追加 RAW target，锁帧率）
            collector.setPhase(ProbePhase.DUAL)
            collector.markDualStart(clock.nowNanos())
            val dualStarted = try {
                session.beginDualStream(targetFps)
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

            scheduler.pause(WINDOW_DUAL_MS)
            if (watchdogExpired(startNs)) {
                return ZslProbeResult.Failed(ZslProbeFailure.RAW_STALL, WATCHDOG_DETAIL)
            }
            if (aborted.get()) return ZslProbeResult.Aborted

            val dualPreviewCount = collector.dualPreviewCount()
            val snapshot = collector.snapshot()

            // W2：恢复窗口（先还原请求，再留 1000 ms 观察恢复）
            restoreOnce()
            collector.setPhase(ProbePhase.RECOVERY)
            scheduler.pause(WINDOW_RECOVERY_MS)
            if (watchdogExpired(startNs)) {
                return ZslProbeResult.Failed(ZslProbeFailure.RAW_STALL, WATCHDOG_DETAIL)
            }
            if (aborted.get()) return ZslProbeResult.Aborted

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

            // 帧数过少时直接判 UNSUPPORTED（RAW_FPS_LOW），不进入阈值评分。
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
        } catch (t: Throwable) {
            return ZslProbeResult.Failed(
                reason = ZslProbeFailure.CAPTURE_FAILED,
                detail = t.message ?: t.javaClass.simpleName
            )
        } finally {
            restoreOnce()
            running.set(false)
        }
    }

    private fun watchdogExpired(startNs: Long): Boolean = clock.nowNanos() - startNs > WATCHDOG_NS

    private fun safeThermalStatus(): Int =
        runCatching { session.thermalStatus() }.getOrDefault(THERMAL_STATUS_UNKNOWN)

    private fun safeAvailableMemory(): Long =
        runCatching { session.availableMemoryBytes() }.getOrDefault(0L)

    private companion object {
        const val WATCHDOG_DETAIL = "watchdog: probe exceeded 15s"
        const val THERMAL_STATUS_UNKNOWN = 0
    }
}

private const val WINDOW_BASELINE_MS = 1500L
private const val WINDOW_DUAL_MS = 4000L
private const val WINDOW_RECOVERY_MS = 1000L
private const val WATCHDOG_NS = 15_000_000_000L
private const val MIN_RAW_FRAMES = 5
private const val WARMUP_FRAMES = 3

private enum class ProbePhase { IDLE, BASELINE, DUAL, RECOVERY }

/**
 * 帧回调收集器：只计数/记录，所有可变状态由 `synchronized(this)` 保护。
 * 回调可能来自相机线程，控制器只会在窗口结束后读取快照。
 */
private class SampleCollector(private val clock: ZslProbeClock) : ZslProbeFrameObserver {

    private var phase = ProbePhase.IDLE
    private var dualStartNs = 0L
    private var baselinePreview = 0
    private var dualPreview = 0
    private val rawTimestamps = ArrayList<Long>()
    private val ageSamples = ArrayList<Float>()
    private val copySamples = ArrayList<Float>()
    private var metadataHits = 0
    private var captureFailures = 0
    private var warmupMs: Long? = null

    @Synchronized
    fun setPhase(newPhase: ProbePhase) {
        phase = newPhase
    }

    @Synchronized
    fun markDualStart(timestampNs: Long) {
        dualStartNs = timestampNs
    }

    @Synchronized
    fun baselinePreviewCount(): Int = baselinePreview

    @Synchronized
    fun dualPreviewCount(): Int = dualPreview

    @Synchronized
    fun snapshot(): CollectorSnapshot = CollectorSnapshot(
        rawTimestampsNs = rawTimestamps.toList(),
        ageSamplesMs = ageSamples.toList(),
        copySamplesMs = copySamples.toList(),
        metadataHits = metadataHits,
        captureFailures = captureFailures,
        warmupMs = warmupMs
    )

    override fun onPreviewResult(timestampNs: Long) {
        synchronized(this) {
            when (phase) {
                ProbePhase.BASELINE -> baselinePreview++
                ProbePhase.DUAL -> dualPreview++
                ProbePhase.IDLE, ProbePhase.RECOVERY -> Unit
            }
        }
    }

    override fun onRawFrame(timestampNs: Long, copyMs: Long, metadataHit: Boolean) {
        synchronized(this) {
            if (phase != ProbePhase.DUAL) return
            rawTimestamps.add(timestampNs)
            ageSamples.add(
                ((clock.nowNanos() - timestampNs) / 1_000_000f).coerceAtLeast(0f)
            )
            copySamples.add(copyMs.coerceAtLeast(0L).toFloat())
            if (metadataHit) metadataHits++
            if (warmupMs == null && dualStartNs > 0L && rawTimestamps.size >= WARMUP_FRAMES) {
                warmupMs = ((clock.nowNanos() - dualStartNs) / 1_000_000L).coerceAtLeast(0L)
            }
        }
    }

    override fun onCaptureFailure() {
        synchronized(this) {
            if (phase == ProbePhase.DUAL) captureFailures++
        }
    }
}

private data class CollectorSnapshot(
    val rawTimestampsNs: List<Long>,
    val ageSamplesMs: List<Float>,
    val copySamplesMs: List<Float>,
    val metadataHits: Int,
    val captureFailures: Int,
    val warmupMs: Long?
)
