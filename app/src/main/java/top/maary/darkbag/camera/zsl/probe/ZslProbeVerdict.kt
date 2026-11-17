package top.maary.darkbag.camera.zsl.probe

import top.maary.darkbag.camera.zsl.RingConfig
import kotlin.math.floor

/** 探测结论三档（优先级 RECOMMENDED > SUPPORTED > UNSUPPORTED）。 */
enum class ZslProbeVerdict { RECOMMENDED, SUPPORTED, UNSUPPORTED }

/** 探测失败原因码（固定集合，UI 只做单语言短句映射）。 */
enum class ZslProbeFailure {
    RAW_FPS_LOW,
    RAW_JITTER,
    RAW_STALL,
    FRAME_AGE_HIGH,
    COPY_SLOW,

    /**
     * 拷贝吞吐无法测量（单帧字节数未知，`session.capabilities` 读取失败）。
     *
     * 与 `COPY_SLOW` 区分（S1-FIX-R2 复审 MAJOR-3）：这是**阈值未知**，不是**已知不可行**。
     * 结论同样是 UNSUPPORTED 并要求重测，但原因文案必须指向"能力读取失败"而非"拷贝慢"，
     * 否则诊断信息会误导。
     */
    COPY_UNMEASURED,
    PREVIEW_DEGRADED,
    METADATA_MISS,
    WARMUP_SLOW,
    THERMAL_RISK,
    CAPTURE_FAILED,

    /** 预检不通过（会话未 Active / 录制 / 多摄 / PiP / 电量 / 内存 / 热），**未开窗**（S1-B2 R12）。 */
    PRECONDITION_FAILED,

    /** 设备/镜头没有 `RAW_SENSOR` 输出：探测无意义，直接产出 UNSUPPORTED 报告（S1-B2 R3）。 */
    RAW_UNSUPPORTED
}

/** 评分结果：结论 + 失败原因（仅 UNSUPPORTED 非空） + 推荐环参数。 */
data class ZslProbeOutcome(
    val verdict: ZslProbeVerdict,
    val failures: List<ZslProbeFailure>,
    val ringConfig: RingConfig
)

/**
 * 阈值评分（设计文档 §3.8.4，纯函数）。
 *
 * 判定顺序：
 * 1. 若全部指标满足 RECOMMENDED 档 → [ZslProbeVerdict.RECOMMENDED]（无失败码）；
 * 2. 否则若全部指标满足 SUPPORTED 档 → [ZslProbeVerdict.SUPPORTED]（无失败码）；
 * 3. 否则 → [ZslProbeVerdict.UNSUPPORTED]，列出所有未达 SUPPORTED 档的失败码，
 *    且推荐环参数回落 [RingConfig.DISABLED]。
 *
 * 边界一律含等号（`≥` / `≤`）。`warmupMs == null` 与 `thermalStatusAfter == null` 都表示"该项未测到"：
 * 它们不会导致 UNSUPPORTED（阈值未知，不能据此否定设备），但也不足以判定 RECOMMENDED，
 * 因此此时结论最高为 SUPPORTED（失败码列表为空），推荐环深度按 2 处理。
 *
 * 例外：拷贝吞吐为 **0** 有两种含义，**必须区分**（S1-FIX-R2 复审 MAJOR-3）：
 * - [ZslProbeSample.copyThroughputMeasured] 为 `false`：单帧字节数未知（能力读取失败），属于
 *   "阈值未知"，判 `COPY_UNMEASURED`（不指责拷贝慢）；
 * - 为 `true` 但吞吐确实为 0：属于"已知不可行"，判 `COPY_SLOW`。
 * 两者都是 UNSUPPORTED 并建议重测，只是原因码不同。
 */
object ZslProbeScoring {
    private const val RECOMMENDED_MIN_RAW_FPS = 24f
    private const val SUPPORTED_MIN_RAW_FPS = 15f
    private const val RECOMMENDED_JITTER_FACTOR = 1.5f
    private const val SUPPORTED_JITTER_FACTOR = 1.8f
    private const val RECOMMENDED_STALL_FACTOR = 4f
    private const val SUPPORTED_STALL_FACTOR = 6f
    private const val RECOMMENDED_MAX_AGE_MS = 100f
    private const val SUPPORTED_MAX_AGE_MS = 140f

    /**
     * 拷贝吞吐下限（MiB/s，**P5**：最慢 5% 帧的吞吐），S1-FIX-R2 带宽口径。
     *
     * 口径变更理由：旧门限是绝对耗时（RECOMMENDED ≤6 ms / SUPPORTED ≤12 ms）。对 12 MP 的
     * 24 MiB 帧，6 ms 需要约 4000 MiB/s 的单核拷贝吞吐——这属于桌面 DDR5 量级，当前移动端
     * SoC 难以企及。**证据强度说明**：目前仅有一台设备的实测（moto XT2409-5：
     * 15.15 ms ≈ 1584 MiB/s，与 LPDDR5 带宽上限同量级），"手机内存不可能达到 4000 MiB/s"
     * 是基于内存规格的推断而非多机采样，更多机型数据可支撑后续校准。
     * 更根本的缺陷是绝对毫秒天然绑定分辨率：同一块内存跑 50 MP 帧时耗时约为 12 MP 的 4.17 倍，
     * 即便内存性能相同也会被判失败。
     *
     * 新门限锚定物理预算——拷贝必须在一帧时间内完成，否则双流跟不上传感器节拍：
     * - 30 fps 的帧预算为 33.3 ms，24 MiB / 33.3 ms ≈ 717 MiB/s 是**绝对下限**；
     * - SUPPORTED 取 900 MiB/s：留给 HAL/回调/预览回调约 2/3 的帧时间余量，
     *   低于此值说明拷贝会显著挤占双流时隙；
     * - RECOMMENDED 取 1800 MiB/s：略高于上述单机实测 P5 吞吐（1584 MiB/s）的保守取值，
     *   达标者拷贝占帧预算一半以内，可支撑 ringDepth=3。若后续多机数据显示该值偏严，
     *   应据实下调而非放宽测量。
     */
    private const val RECOMMENDED_MIN_COPY_MIB_PER_S = 1800f
    private const val SUPPORTED_MIN_COPY_MIB_PER_S = 900f

    private const val RECOMMENDED_MAX_PREVIEW_DROP = 0.10f
    private const val SUPPORTED_MAX_PREVIEW_DROP = 0.20f
    private const val RECOMMENDED_MIN_METADATA_HIT_RATE = 0.99f
    private const val SUPPORTED_MIN_METADATA_HIT_RATE = 0.97f
    private const val RECOMMENDED_MAX_WARMUP_MS = 200L
    private const val SUPPORTED_MAX_WARMUP_MS = 400L
    private const val RECOMMENDED_MAX_THERMAL_STATUS = 1
    private const val SUPPORTED_MAX_THERMAL_STATUS = 2
    private const val SUPPORTED_MAX_CAPTURE_FAILURES = 1

    private const val RING_DEPTH_RECOMMENDED = 3
    private const val RING_DEPTH_SUPPORTED = 2
    private const val RING_TARGET_FPS_HIGH = 24
    private const val RING_TARGET_FPS_LOW = 15
    private const val RING_FPS_STEP = 5
    private const val RING_MIN_VALID_FRAMES = 2

    fun evaluate(sample: ZslProbeSample, nominalFrameIntervalMs: Float): ZslProbeOutcome {
        val nominal = nominalFrameIntervalMs.coerceAtLeast(1f)

        if (unmetThresholds(sample, nominal, recommended = true).isEmpty()) {
            return ZslProbeOutcome(
                verdict = ZslProbeVerdict.RECOMMENDED,
                failures = emptyList(),
                ringConfig = recommendedRingConfig(sample)
            )
        }

        val supportedFailures = unmetThresholds(sample, nominal, recommended = false)
        if (supportedFailures.isEmpty()) {
            return ZslProbeOutcome(
                verdict = ZslProbeVerdict.SUPPORTED,
                failures = emptyList(),
                ringConfig = recommendedRingConfig(sample)
            )
        }

        return ZslProbeOutcome(
            verdict = ZslProbeVerdict.UNSUPPORTED,
            failures = supportedFailures,
            ringConfig = RingConfig.DISABLED
        )
    }

    /**
     * 推荐环参数派生（设计文档 §3.8.4 定稿，S1-B2 R6）：
     * - `depth = 3`（预热 ≤200 ms）否则 `2`；
     * - `targetFps` 三档：
     *   - `rawFps ≥ 24` → 24（实测 24 fps 不再被 `floor(24/5)*5 = 20` 降到 20）；
     *   - `15 ≤ rawFps < 24` → 15（15–23.9 一律 15，避免推荐帧率高于档位）；
     *   - 更低 → `floor(rawFps / 5) * 5`，下限夹到 5（14.9 → 10、4 → 5）；
     * - `minValidFrames = 2`。
     */
    internal fun recommendedRingConfig(sample: ZslProbeSample): RingConfig {
        val depth = if (sample.warmupMs != null && sample.warmupMs <= RECOMMENDED_MAX_WARMUP_MS) {
            RING_DEPTH_RECOMMENDED
        } else {
            RING_DEPTH_SUPPORTED
        }
        return RingConfig(depth, recommendedTargetFps(sample.rawFps), RING_MIN_VALID_FRAMES)
    }

    /** 三档目标帧率（R6 定稿公式），抽出来便于逐档钉测试。 */
    private fun recommendedTargetFps(rawFps: Float): Int = when {
        rawFps >= RING_TARGET_FPS_HIGH.toFloat() -> RING_TARGET_FPS_HIGH
        rawFps >= RING_TARGET_FPS_LOW.toFloat() -> RING_TARGET_FPS_LOW
        else -> (floor(rawFps / RING_FPS_STEP).toInt() * RING_FPS_STEP)
            .coerceAtLeast(RING_FPS_STEP)
    }

    private fun unmetThresholds(
        sample: ZslProbeSample,
        nominalMs: Float,
        recommended: Boolean
    ): List<ZslProbeFailure> = buildList {
        val minRawFps = if (recommended) RECOMMENDED_MIN_RAW_FPS else SUPPORTED_MIN_RAW_FPS
        if (sample.rawFps < minRawFps) add(ZslProbeFailure.RAW_FPS_LOW)

        val jitterFactor = if (recommended) RECOMMENDED_JITTER_FACTOR else SUPPORTED_JITTER_FACTOR
        if (sample.gapP95Ms > nominalMs * jitterFactor) add(ZslProbeFailure.RAW_JITTER)

        val stallFactor = if (recommended) RECOMMENDED_STALL_FACTOR else SUPPORTED_STALL_FACTOR
        if (sample.gapMaxMs > nominalMs * stallFactor) add(ZslProbeFailure.RAW_STALL)

        val maxAgeMs = if (recommended) RECOMMENDED_MAX_AGE_MS else SUPPORTED_MAX_AGE_MS
        if (sample.ageP95Ms > maxAgeMs) add(ZslProbeFailure.FRAME_AGE_HIGH)

        // 带宽口径（S1-FIX-R2）：吞吐 **低于** 门限即失败，边界含等号（== 门限视为达标）。
        // 未测到吞吐时不判"慢"，只判"未测到"（MAJOR-3）。
        if (!sample.copyThroughputMeasured) {
            add(ZslProbeFailure.COPY_UNMEASURED)
        } else {
            val minCopyMiBps = if (recommended) {
                RECOMMENDED_MIN_COPY_MIB_PER_S
            } else {
                SUPPORTED_MIN_COPY_MIB_PER_S
            }
            if (sample.copyThroughputP5MiBps < minCopyMiBps) add(ZslProbeFailure.COPY_SLOW)
        }

        val maxPreviewDrop = if (recommended) {
            RECOMMENDED_MAX_PREVIEW_DROP
        } else {
            SUPPORTED_MAX_PREVIEW_DROP
        }
        if (sample.previewDropRatio > maxPreviewDrop) add(ZslProbeFailure.PREVIEW_DEGRADED)

        val minMetadataHitRate = if (recommended) {
            RECOMMENDED_MIN_METADATA_HIT_RATE
        } else {
            SUPPORTED_MIN_METADATA_HIT_RATE
        }
        if (sample.metadataHitRate < minMetadataHitRate) add(ZslProbeFailure.METADATA_MISS)

        val warmupMs = sample.warmupMs
        if (recommended) {
            if (warmupMs == null || warmupMs > RECOMMENDED_MAX_WARMUP_MS) {
                add(ZslProbeFailure.WARMUP_SLOW)
            }
        } else {
            if (warmupMs != null && warmupMs > SUPPORTED_MAX_WARMUP_MS) {
                add(ZslProbeFailure.WARMUP_SLOW)
            }
        }

        val maxThermalStatus = if (recommended) {
            RECOMMENDED_MAX_THERMAL_STATUS
        } else {
            SUPPORTED_MAX_THERMAL_STATUS
        }
        val thermalAfter = sample.thermalStatusAfter
        if (recommended) {
            // 未知热状态不足以判定 RECOMMENDED（与 warmupMs == null 同构），但 SUPPORTED 档不禁用。
            if (thermalAfter == null || thermalAfter > maxThermalStatus) {
                add(ZslProbeFailure.THERMAL_RISK)
            }
        } else {
            if (thermalAfter != null && thermalAfter > maxThermalStatus) {
                add(ZslProbeFailure.THERMAL_RISK)
            }
        }

        val maxCaptureFailures = if (recommended) 0 else SUPPORTED_MAX_CAPTURE_FAILURES
        if (sample.captureFailures > maxCaptureFailures) add(ZslProbeFailure.CAPTURE_FAILED)
    }
}
