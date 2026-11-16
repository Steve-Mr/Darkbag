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
    private const val RECOMMENDED_MAX_COPY_MS = 6f
    private const val SUPPORTED_MAX_COPY_MS = 12f
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

        val maxCopyMs = if (recommended) RECOMMENDED_MAX_COPY_MS else SUPPORTED_MAX_COPY_MS
        if (sample.copyP95Ms > maxCopyMs) add(ZslProbeFailure.COPY_SLOW)

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
