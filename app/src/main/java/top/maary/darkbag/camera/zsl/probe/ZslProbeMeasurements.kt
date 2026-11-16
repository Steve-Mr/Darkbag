package top.maary.darkbag.camera.zsl.probe

import kotlin.math.ceil
import kotlin.math.floor

/**
 * 分位统计（纯函数，无 Android 依赖）。
 *
 * 采用线性插值（与 numpy 默认 `linear` 一致）：
 * - 空列表 → `0f`（调用方无需判空，避免 NaN 传播）；
 * - `p` 会被夹到 `[0, 1]`，越界不抛异常；
 * - 单值列表在任意 `p` 下都返回该值。
 */
object Percentiles {
    fun percentile(values: List<Float>, p: Float): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        if (sorted.size == 1) return sorted[0]
        val clamped = p.coerceIn(0f, 1f)
        val rank = clamped * (sorted.size - 1)
        val lower = floor(rank).toInt()
        val upper = ceil(rank).toInt()
        if (lower == upper) return sorted[lower]
        val weight = rank - lower
        return sorted[lower] * (1f - weight) + sorted[upper] * weight
    }
}

/**
 * 一次探测的原始聚合结果（三窗口 W0/W1 的量化指标）。
 *
 * 所有时间单位均为毫秒；[warmupMs] 为环首充填耗时，未测到（可选窗口未执行）时为 `null`；
 * [thermalStatusBefore] / [thermalStatusAfter] 为 `null` 表示热状态未知（API < 29 或读取失败）。
 */
data class ZslProbeSample(
    val rawFrameCount: Int,
    val rawFps: Float,
    val gapP50Ms: Float,
    val gapP95Ms: Float,
    val gapMaxMs: Float,
    val ageP50Ms: Float,
    val ageP95Ms: Float,
    val copyP50Ms: Float,
    val copyP95Ms: Float,
    val previewFpsBaseline: Float,
    val previewFpsDual: Float,
    val previewDropRatio: Float,
    val metadataHitRate: Float,
    val warmupMs: Long?,
    val thermalStatusBefore: Int?,
    val thermalStatusAfter: Int?,
    val memoryDeltaBytes: Long,
    val captureFailures: Int
)

/**
 * 把探测窗口的原始观测聚合成 [ZslProbeSample]（纯函数）。
 *
 * 约定：
 * - `rawFps = 帧数 / 双流窗口秒数`（与设计文档 §3.8.3 的"RAW 帧数/帧率"一致）；
 * - 帧间隔基于按时间戳排序后的相邻差，负差（乱序回调）不会产生负间隔；
 * - 预览掉帧比例 = `(基线帧率 - 双流帧率) / 基线帧率`，夹到 `[0, 1]`；无基线数据时为 0；
 * - 元数据命中率在无样本时为 0（交给判定层按 `METADATA_MISS` 处理）；
 * - 内存差 = 结束后可用内存 - 开始前可用内存（负值表示探测期间内存被占用）；
 * - 热状态原样透传 `null`（未知），由判定层决定"未知不加失败码、最高 SUPPORTED"。
 */
object ZslProbeAggregator {
    fun aggregate(
        rawTimestampsNs: List<Long>,
        ageSamplesMs: List<Float>,
        copySamplesMs: List<Float>,
        previewBaselineCount: Int,
        previewDualCount: Int,
        metadataHits: Int,
        metadataTotal: Int,
        warmupMs: Long?,
        thermalBefore: Int?,
        thermalAfter: Int?,
        memoryBefore: Long,
        memoryAfter: Long,
        captureFailures: Int,
        baselineWindowMs: Long,
        dualWindowMs: Long
    ): ZslProbeSample {
        val sortedTimestamps = rawTimestampsNs.sorted()
        val gaps = sortedTimestamps.zipWithNext { previous, current ->
            (current - previous) / 1_000_000f
        }.filter { it >= 0f }

        val dualWindowSeconds = dualWindowMs.coerceAtLeast(0L) / 1000f
        val baselineWindowSeconds = baselineWindowMs.coerceAtLeast(0L) / 1000f
        val rawFps = if (dualWindowSeconds > 0f) rawTimestampsNs.size / dualWindowSeconds else 0f
        val baselineFps = if (baselineWindowSeconds > 0f) {
            previewBaselineCount / baselineWindowSeconds
        } else {
            0f
        }
        val dualFps = if (dualWindowSeconds > 0f) previewDualCount / dualWindowSeconds else 0f
        val previewDropRatio = if (baselineFps > 0f) {
            ((baselineFps - dualFps) / baselineFps).coerceIn(0f, 1f)
        } else {
            0f
        }
        val metadataHitRate = if (metadataTotal > 0) {
            metadataHits.toFloat() / metadataTotal
        } else {
            0f
        }

        return ZslProbeSample(
            rawFrameCount = rawTimestampsNs.size,
            rawFps = rawFps,
            gapP50Ms = Percentiles.percentile(gaps, 0.50f),
            gapP95Ms = Percentiles.percentile(gaps, 0.95f),
            gapMaxMs = gaps.maxOrNull() ?: 0f,
            ageP50Ms = Percentiles.percentile(ageSamplesMs, 0.50f),
            ageP95Ms = Percentiles.percentile(ageSamplesMs, 0.95f),
            copyP50Ms = Percentiles.percentile(copySamplesMs, 0.50f),
            copyP95Ms = Percentiles.percentile(copySamplesMs, 0.95f),
            previewFpsBaseline = baselineFps,
            previewFpsDual = dualFps,
            previewDropRatio = previewDropRatio,
            metadataHitRate = metadataHitRate,
            warmupMs = warmupMs,
            thermalStatusBefore = thermalBefore,
            thermalStatusAfter = thermalAfter,
            memoryDeltaBytes = memoryAfter - memoryBefore,
            captureFailures = captureFailures
        )
    }
}

internal enum class ProbePhase { IDLE, BASELINE, DUAL, RECOVERY }

/** 环首充填判定帧数：第 3 帧到达即认为环已进入稳态（用于 warmup 近似）。 */
internal const val WARMUP_FRAMES = 3

/**
 * 帧回调收集器：只计数/记录，所有可变状态由 `synchronized(this)` 保护。
 * 回调可能来自相机线程，控制器只会在窗口结束后读取快照。
 */
internal class SampleCollector(private val clock: ZslProbeClock) : ZslProbeFrameObserver {
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
            ageSamples.add(((clock.nowNanos() - timestampNs) / 1_000_000f).coerceAtLeast(0f))
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

internal data class CollectorSnapshot(
    val rawTimestampsNs: List<Long>,
    val ageSamplesMs: List<Float>,
    val copySamplesMs: List<Float>,
    val metadataHits: Int,
    val captureFailures: Int,
    val warmupMs: Long?
)
