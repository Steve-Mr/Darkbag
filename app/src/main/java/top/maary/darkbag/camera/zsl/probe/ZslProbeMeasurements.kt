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
 * 窗口口径（S1-B2 R5，设计 §3.8.3）：只有 W0/W1 的指标参与判定；**W2 是 settle window，
 * 不产出任何数值判据**——恢复是否正确由真机 `DUMP`（请求字段与探测前逐字段一致）与冒烟矩阵
 * 验证，因此本类**没有**"恢复度"字段，也不得为 W2 新增字段。
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

    /**
     * 单帧拷贝吞吐（MiB/s），**判据口径**（S1-FIX-R2）。
     *
     * 绝对毫秒门限天然绑定分辨率：同一块内存在 12 MP 下 15 ms 达标、在 50 MP 下必然超限，
     * 与内存实际性能无关。因此判定改用吞吐：
     * - [copyThroughputP5MiBps] = 逐帧吞吐的 5% 分位（最慢那批帧的吞吐下限），供 `COPY_SLOW` 判定；
     * - [copyThroughputP50MiBps] = 中位吞吐，仅入报告。
     *
     * **两者为 0 时含义取决于 [copyThroughputMeasured]**：`true` 表示真的测得 0（异常情形），
     * `false` 表示**未测到**（单帧字节数未知，即能力读取失败）。
     */
    val copyThroughputP5MiBps: Float,
    val copyThroughputP50MiBps: Float,

    /**
     * 吞吐是否有有效测量（S1-FIX-R2 复审 MAJOR-3）。
     *
     * 必须与"测得 0"区分：单帧字节数未知（`session.capabilities` 读取失败）时无法换算吞吐，
     * 这是**阈值未知**而非**已知不可行**，判定层不得据此判 `COPY_SLOW`，而应判
     * `COPY_UNMEASURED` 并要求重测——否则会把"读不到能力"误报成"拷贝慢"。
     */
    val copyThroughputMeasured: Boolean,

    /** 每帧拷贝字节数（宽×高×2）；未知时为 0（此时 [copyThroughputMeasured] 为 `false`）。 */
    val copyFrameBytes: Long,
    val previewFpsBaseline: Float,
    val previewFpsDual: Float,
    val previewDropRatio: Float,
    val metadataHitRate: Float,
    val warmupMs: Long?,
    val thermalStatusBefore: Int?,
    val thermalStatusAfter: Int?,
    val memoryDeltaBytes: Long,
    val captureFailures: Int
) {
    companion object {
        /**
         * 全零样本：探测**未开窗**时用于构造报告（如设备无 RAW 能力，S1-B2 R3）。
         * 与 [ZslProbeAggregator] 在"零帧零窗口"下的产出语义一致（热状态未知为 `null`）。
         */
        fun empty(): ZslProbeSample = ZslProbeSample(
            rawFrameCount = 0,
            rawFps = 0f,
            gapP50Ms = 0f,
            gapP95Ms = 0f,
            gapMaxMs = 0f,
            ageP50Ms = 0f,
            ageP95Ms = 0f,
            copyP50Ms = 0f,
            copyP95Ms = 0f,
            copyThroughputP5MiBps = 0f,
            copyThroughputP50MiBps = 0f,
            copyThroughputMeasured = false,
            copyFrameBytes = 0L,
            previewFpsBaseline = 0f,
            previewFpsDual = 0f,
            previewDropRatio = 0f,
            metadataHitRate = 0f,
            warmupMs = null,
            thermalStatusBefore = null,
            thermalStatusAfter = null,
            memoryDeltaBytes = 0L,
            captureFailures = 0
        )
    }
}

/**
 * 把探测窗口的原始观测聚合成 [ZslProbeSample]（纯函数）。
 *
 * 约定：
 * - `rawFps = 帧数 / 双流窗口秒数`（与设计文档 §3.8.3 的"RAW 帧数/帧率"一致）；
 * - 帧间隔基于按时间戳排序后的相邻差，负差（乱序回调）不会产生负间隔；
 * - 预览掉帧比例 = `(基线帧率 - 双流帧率) / 基线帧率`，夹到 `[0, 1]`；无基线数据时为 0；
 * - 元数据命中率在无样本时为 0（交给判定层按 `METADATA_MISS` 处理）；
 * - 内存差 = 结束后可用内存 - 开始前可用内存（负值表示探测期间内存被占用）；
 * - 热状态原样透传 `null`（未知），由判定层决定"未知不加失败码、最高 SUPPORTED"；
 * - 拷贝吞吐由**纳秒**样本换算（见 [copyThroughput]），判据用 P5 吞吐下限。
 */
object ZslProbeAggregator {
    fun aggregate(
        rawTimestampsNs: List<Long>,
        ageSamplesMs: List<Float>,
        copySamplesNs: List<Long>,
        frameBytes: Long,
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

        // 同时保留整帧耗时（毫秒，供报告/UI 展示）与吞吐（判据口径）。
        val copySamplesMs = copySamplesNs.map { it.coerceAtLeast(0L) / 1_000_000f }
        val throughput = copyThroughput(copySamplesNs, frameBytes)

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
            copyThroughputP5MiBps = throughput.p5,
            copyThroughputP50MiBps = throughput.p50,
            copyThroughputMeasured = throughput.measured,
            copyFrameBytes = frameBytes.coerceAtLeast(0L),
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

    /**
     * 吞吐统计结果（MiB/s）。
     *
     * [measured] 为 `false` 表示**未测到**（单帧字节数未知），此时 p5/p50 恒为 0 但不可当作测量值；
     * 与"测得 0"（[measured] 为 `true`）必须区分，否则读不到能力会被误报成拷贝慢。
     */
    internal data class CopyThroughput(val p5: Float, val p50: Float, val measured: Boolean) {
        companion object {
            val UNMEASURED = CopyThroughput(0f, 0f, measured = false)
        }
    }

    /**
     * 单帧拷贝吞吐（MiB/s）。
     *
     * 逐帧先求吞吐再取分位，而不是先取耗时分位再求倒数——后者在耗时分位数处非线性放大，
     * 且会被单帧异常值主导。`p5` 即"最差 5% 帧的吞吐下限"，是 [ZslProbeFailure.COPY_SLOW]
     * 的判定量：它回答"最慢的那批帧是否仍能跟上"。
     *
     * 零/负耗时（时钟异常或拷贝被短路）按 0 处理，避免除零产生 `Infinity` 污染评分。
     */
    internal fun copyThroughput(samplesNs: List<Long>, frameBytes: Long): CopyThroughput {
        if (samplesNs.isEmpty() || frameBytes <= 0L) return CopyThroughput.UNMEASURED
        val mib = frameBytes / BYTES_PER_MIB
        val throughputs = samplesNs.map { nanos ->
            if (nanos <= 0L) 0f else (mib / (nanos / NANOS_PER_SECOND)).toFloat()
        }
        return CopyThroughput(
            p5 = Percentiles.percentile(throughputs, 0.05f),
            p50 = Percentiles.percentile(throughputs, 0.50f),
            measured = true
        )
    }

    private const val NANOS_PER_SECOND = 1_000_000_000.0
    private const val BYTES_PER_MIB = 1024.0 * 1024.0
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
    private val copySamplesNs = ArrayList<Long>()
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
        copySamplesNs = copySamplesNs.toList(),
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

    override fun onRawFrame(timestampNs: Long, copyNanos: Long) {
        synchronized(this) {
            if (phase != ProbePhase.DUAL) return
            rawTimestamps.add(timestampNs)
            ageSamples.add(((clock.nowNanos() - timestampNs) / 1_000_000f).coerceAtLeast(0f))
            copySamplesNs.add(copyNanos.coerceAtLeast(0L))
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
    val copySamplesNs: List<Long>,
    val captureFailures: Int,
    val warmupMs: Long?
)
