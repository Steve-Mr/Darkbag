package top.maary.darkbag.camera.zsl

/**
 * ZSL 单行 JSON 遥测的固定字段与版本（防止各阶段字段漂移）。
 *
 * 约定：新增/改名任何字段都必须同步 [SCHEMA_VERSION] +1，否则 Reviewer 直接打回。
 */
object ZslTelemetryFields {
    /** 遥测 schema 版本；S0 交付 v1。 */
    const val SCHEMA_VERSION = 1

    /** Logcat tag。 */
    const val TAG = "ZslTelemetry"

    /** 单行日志前缀，便于 `logcat | grep ZSL_METRICS`。 */
    const val PREFIX = "ZSL_METRICS"

    const val FIELD_VERSION = "v"
    const val FIELD_MODE = "mode"
    const val FIELD_FRAMES = "frames"
    const val FIELD_T1_MS = "t1Ms"
    const val FIELD_FALLBACK_REASON = "fallbackReason"
    const val FIELD_TIMESTAMP_MS = "ts"
}
