package top.maary.darkbag.camera.zsl.probe

import top.maary.darkbag.camera.zsl.RingConfig

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
    CAPTURE_FAILED
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
 * 边界一律含等号（`≥` / `≤`）。`warmupMs == null` 表示可选预热窗口未执行：
 * 它不会导致 UNSUPPORTED（阈值未知，不能据此否定设备），但也不足以判定 RECOMMENDED，
 * 因此此时结论最高为 SUPPORTED，推荐环深度按 2 处理。
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
     * 推荐环参数派生：
     * - `depth = 3`（预热 ≤200 ms）否则 `2`；
     * - `targetFps = 24`（实测 ≥24 fps）否则 `15`，并夹到实测 fps 以内防止"推荐帧率 > 实测帧率"倒挂；
     * - `minValidFrames = 2`。
     */
    internal fun recommendedRingConfig(sample: ZslProbeSample): RingConfig {
        val depth = if (sample.warmupMs != null && sample.warmupMs <= RECOMMENDED_MAX_WARMUP_MS) {
            RING_DEPTH_RECOMMENDED
        } else {
            RING_DEPTH_SUPPORTED
        }
        val targetFps = (if (sample.rawFps >= RECOMMENDED_MIN_RAW_FPS) {
            RING_TARGET_FPS_HIGH
        } else {
            RING_TARGET_FPS_LOW
        }).coerceAtMost(sample.rawFps.toInt().coerceAtLeast(1))
        return RingConfig(depth, targetFps, RING_MIN_VALID_FRAMES)
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
        if (sample.thermalStatusAfter > maxThermalStatus) add(ZslProbeFailure.THERMAL_RISK)

        val maxCaptureFailures = if (recommended) 0 else SUPPORTED_MAX_CAPTURE_FAILURES
        if (sample.captureFailures > maxCaptureFailures) add(ZslProbeFailure.CAPTURE_FAILED)
    }
}
