package top.maary.darkbag.camera.zsl.probe

import org.json.JSONArray
import org.json.JSONObject
import top.maary.darkbag.camera.zsl.RingConfig

/**
 * 一次能力探测的完整结论（可持久化、可分享的 JSON 报告）。
 *
 * 字段语义见设计文档 §3.8.4/§3.8.5；[version] 与 [SCHEMA_VERSION] 不等即视为失效，
 * 设备指纹 / 镜头 / RAW 尺寸任一变化也同样失效（见 [isApplicable]）。
 */
data class ZslProbeReport(
    val version: Int,
    val timestampMs: Long,
    val deviceFingerprint: String,
    val lensId: String,
    val rawWidth: Int,
    val rawHeight: Int,
    val verdict: ZslProbeVerdict,
    val failures: List<ZslProbeFailure>,
    val sample: ZslProbeSample,
    val ringConfig: RingConfig
) {
    /** 序列化为单行 JSON（固定 schema，见 [SCHEMA_VERSION]）。 */
    fun toJson(): String = JSONObject()
        .put(FIELD_VERSION, version)
        .put(FIELD_TIMESTAMP_MS, timestampMs)
        .put(FIELD_DEVICE_FINGERPRINT, deviceFingerprint)
        .put(FIELD_LENS_ID, lensId)
        .put(FIELD_RAW_WIDTH, rawWidth)
        .put(FIELD_RAW_HEIGHT, rawHeight)
        .put(FIELD_VERDICT, verdict.name)
        .put(FIELD_FAILURES, JSONArray(failures.map { it.name }))
        .put(FIELD_SAMPLE, sampleToJson(sample))
        .put(FIELD_RING_CONFIG, ringToJson(ringConfig))
        .toString()

    companion object {
        const val SCHEMA_VERSION = 1

        private const val FIELD_VERSION = "version"
        private const val FIELD_TIMESTAMP_MS = "timestampMs"
        private const val FIELD_DEVICE_FINGERPRINT = "deviceFingerprint"
        private const val FIELD_LENS_ID = "lensId"
        private const val FIELD_RAW_WIDTH = "rawWidth"
        private const val FIELD_RAW_HEIGHT = "rawHeight"
        private const val FIELD_VERDICT = "verdict"
        private const val FIELD_FAILURES = "failures"
        private const val FIELD_SAMPLE = "sample"
        private const val FIELD_RING_CONFIG = "ringConfig"

        private const val FIELD_RAW_FRAME_COUNT = "rawFrameCount"
        private const val FIELD_RAW_FPS = "rawFps"
        private const val FIELD_GAP_P50 = "gapP50Ms"
        private const val FIELD_GAP_P95 = "gapP95Ms"
        private const val FIELD_GAP_MAX = "gapMaxMs"
        private const val FIELD_AGE_P50 = "ageP50Ms"
        private const val FIELD_AGE_P95 = "ageP95Ms"
        private const val FIELD_COPY_P50 = "copyP50Ms"
        private const val FIELD_COPY_P95 = "copyP95Ms"
        private const val FIELD_PREVIEW_FPS_BASELINE = "previewFpsBaseline"
        private const val FIELD_PREVIEW_FPS_DUAL = "previewFpsDual"
        private const val FIELD_PREVIEW_DROP_RATIO = "previewDropRatio"
        private const val FIELD_METADATA_HIT_RATE = "metadataHitRate"
        private const val FIELD_WARMUP_MS = "warmupMs"
        private const val FIELD_THERMAL_BEFORE = "thermalStatusBefore"
        private const val FIELD_THERMAL_AFTER = "thermalStatusAfter"
        private const val FIELD_MEMORY_DELTA = "memoryDeltaBytes"
        private const val FIELD_CAPTURE_FAILURES = "captureFailures"

        private const val FIELD_RING_DEPTH = "depth"
        private const val FIELD_RING_TARGET_FPS = "targetFps"
        private const val FIELD_RING_MIN_VALID_FRAMES = "minValidFrames"

        /**
         * 解析报告。**绝不抛异常**：
         * - `null` / 空白 / 坏 JSON / 版本不符 / verdict 非法 / 缺少 `sample` 对象 → `null`；
         * - 正向 verdict（SUPPORTED / RECOMMENDED）但推荐环非法（缺字段 / 任一维度 ≤0）→ `null`：
         *   这种报告一旦被 [top.maary.darkbag.camera.zsl.ZslPolicy] 采信就会武装出 0 深度、0 fps 的环，
         *   宁可当作"无报告"要求重测（S1-B1 F4）；UNSUPPORTED 允许 `RingConfig.DISABLED`；
         * - 叶子字段缺失用 `opt*` 兜底（数值 0、`warmupMs` / 热状态为 `null`），未知失败码忽略，保证向前兼容。
         */
        fun fromJson(raw: String?): ZslProbeReport? {
            if (raw.isNullOrBlank()) return null
            return try {
                val json = JSONObject(raw)
                if (json.optInt(FIELD_VERSION, -1) != SCHEMA_VERSION) return null
                val verdict = parseVerdict(json.optString(FIELD_VERDICT, "")) ?: return null
                val sampleJson = json.optJSONObject(FIELD_SAMPLE) ?: return null
                val ringConfig = ringFromJson(json.optJSONObject(FIELD_RING_CONFIG))
                if (verdict != ZslProbeVerdict.UNSUPPORTED && !ringConfig.isUsable()) return null
                ZslProbeReport(
                    version = json.optInt(FIELD_VERSION, SCHEMA_VERSION),
                    timestampMs = json.optLong(FIELD_TIMESTAMP_MS, 0L),
                    deviceFingerprint = json.optString(FIELD_DEVICE_FINGERPRINT, ""),
                    lensId = json.optString(FIELD_LENS_ID, ""),
                    rawWidth = json.optInt(FIELD_RAW_WIDTH, 0),
                    rawHeight = json.optInt(FIELD_RAW_HEIGHT, 0),
                    verdict = verdict,
                    failures = parseFailures(json.optJSONArray(FIELD_FAILURES)),
                    sample = sampleFromJson(sampleJson),
                    ringConfig = ringConfig
                )
            } catch (_: Throwable) {
                null
            }
        }

        /**
         * 报告是否仍适用于当前设备/镜头/RAW 尺寸。
         * 同时校验 schema 版本与推荐环合法性（正向 verdict 必须有可执行的环参数），
         * 避免旧版本字段语义漂移或手工构造的非法报告被误用。
         */
        fun isApplicable(
            report: ZslProbeReport?,
            deviceFingerprint: String,
            lensId: String,
            rawWidth: Int,
            rawHeight: Int
        ): Boolean {
            if (report == null) return false
            if (report.version != SCHEMA_VERSION) return false
            if (report.deviceFingerprint != deviceFingerprint) return false
            if (report.lensId != lensId) return false
            if (report.rawWidth != rawWidth) return false
            if (report.rawHeight != rawHeight) return false
            if (report.verdict != ZslProbeVerdict.UNSUPPORTED && !report.ringConfig.isUsable()) {
                return false
            }
            return true
        }

        private fun sampleToJson(sample: ZslProbeSample): JSONObject = JSONObject()
            .put(FIELD_RAW_FRAME_COUNT, sample.rawFrameCount)
            .put(FIELD_RAW_FPS, sample.rawFps.toDouble())
            .put(FIELD_GAP_P50, sample.gapP50Ms.toDouble())
            .put(FIELD_GAP_P95, sample.gapP95Ms.toDouble())
            .put(FIELD_GAP_MAX, sample.gapMaxMs.toDouble())
            .put(FIELD_AGE_P50, sample.ageP50Ms.toDouble())
            .put(FIELD_AGE_P95, sample.ageP95Ms.toDouble())
            .put(FIELD_COPY_P50, sample.copyP50Ms.toDouble())
            .put(FIELD_COPY_P95, sample.copyP95Ms.toDouble())
            .put(FIELD_PREVIEW_FPS_BASELINE, sample.previewFpsBaseline.toDouble())
            .put(FIELD_PREVIEW_FPS_DUAL, sample.previewFpsDual.toDouble())
            .put(FIELD_PREVIEW_DROP_RATIO, sample.previewDropRatio.toDouble())
            .put(FIELD_METADATA_HIT_RATE, sample.metadataHitRate.toDouble())
            .put(FIELD_WARMUP_MS, sample.warmupMs ?: JSONObject.NULL)
            .put(FIELD_THERMAL_BEFORE, sample.thermalStatusBefore ?: JSONObject.NULL)
            .put(FIELD_THERMAL_AFTER, sample.thermalStatusAfter ?: JSONObject.NULL)
            .put(FIELD_MEMORY_DELTA, sample.memoryDeltaBytes)
            .put(FIELD_CAPTURE_FAILURES, sample.captureFailures)

        private fun ringToJson(config: RingConfig): JSONObject = JSONObject()
            .put(FIELD_RING_DEPTH, config.depth)
            .put(FIELD_RING_TARGET_FPS, config.targetFps)
            .put(FIELD_RING_MIN_VALID_FRAMES, config.minValidFrames)

        /** 可空整数字段：缺失或 JSON `null` → `null`（未知），与 0（`THERMAL_STATUS_NONE`）区分。 */
        private fun JSONObject.optNullableInt(field: String): Int? =
            if (has(field) && !isNull(field)) optInt(field, 0) else null

        private fun parseVerdict(raw: String): ZslProbeVerdict? =
            ZslProbeVerdict.entries.firstOrNull { it.name == raw }

        private fun parseFailures(array: JSONArray?): List<ZslProbeFailure> {
            if (array == null) return emptyList()
            val failures = ArrayList<ZslProbeFailure>(array.length())
            for (index in 0 until array.length()) {
                val name = array.optString(index, "")
                ZslProbeFailure.entries.firstOrNull { it.name == name }?.let { failures += it }
            }
            return failures
        }

        private fun sampleFromJson(json: JSONObject): ZslProbeSample = ZslProbeSample(
            rawFrameCount = json.optInt(FIELD_RAW_FRAME_COUNT, 0),
            rawFps = json.optDouble(FIELD_RAW_FPS, 0.0).toFloat(),
            gapP50Ms = json.optDouble(FIELD_GAP_P50, 0.0).toFloat(),
            gapP95Ms = json.optDouble(FIELD_GAP_P95, 0.0).toFloat(),
            gapMaxMs = json.optDouble(FIELD_GAP_MAX, 0.0).toFloat(),
            ageP50Ms = json.optDouble(FIELD_AGE_P50, 0.0).toFloat(),
            ageP95Ms = json.optDouble(FIELD_AGE_P95, 0.0).toFloat(),
            copyP50Ms = json.optDouble(FIELD_COPY_P50, 0.0).toFloat(),
            copyP95Ms = json.optDouble(FIELD_COPY_P95, 0.0).toFloat(),
            previewFpsBaseline = json.optDouble(FIELD_PREVIEW_FPS_BASELINE, 0.0).toFloat(),
            previewFpsDual = json.optDouble(FIELD_PREVIEW_FPS_DUAL, 0.0).toFloat(),
            previewDropRatio = json.optDouble(FIELD_PREVIEW_DROP_RATIO, 0.0).toFloat(),
            metadataHitRate = json.optDouble(FIELD_METADATA_HIT_RATE, 0.0).toFloat(),
            warmupMs = if (json.has(FIELD_WARMUP_MS) && !json.isNull(FIELD_WARMUP_MS)) {
                json.optLong(FIELD_WARMUP_MS, 0L)
            } else {
                null
            },
            thermalStatusBefore = json.optNullableInt(FIELD_THERMAL_BEFORE),
            thermalStatusAfter = json.optNullableInt(FIELD_THERMAL_AFTER),
            memoryDeltaBytes = json.optLong(FIELD_MEMORY_DELTA, 0L),
            captureFailures = json.optInt(FIELD_CAPTURE_FAILURES, 0)
        )

        private fun ringFromJson(json: JSONObject?): RingConfig {
            if (json == null) return RingConfig.DISABLED
            return RingConfig(
                depth = json.optInt(FIELD_RING_DEPTH, 0),
                targetFps = json.optInt(FIELD_RING_TARGET_FPS, 0),
                minValidFrames = json.optInt(FIELD_RING_MIN_VALID_FRAMES, 0)
            )
        }
    }
}
