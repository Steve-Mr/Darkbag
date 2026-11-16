package top.maary.darkbag.camera.zsl

import android.util.Log
import org.json.JSONObject

/**
 * 拍摄路径单行 JSON 遥测（schema v1，字段见 [ZslTelemetryFields]）。
 *
 * 每次拍摄输出恰好一行：`ZSL_METRICS {"v":1,"mode":"HDR_BURST","frames":5,"t1Ms":210,...}`。
 * 纯函数 [buildCaptureSummaryJson] 与副作用 [emitCaptureSummary] 分离，便于单测断言字段。
 */
object ZslTelemetry {
    /** mode 缺失时的占位值，保证字段类型稳定（始终是字符串）。 */
    const val UNKNOWN_MODE = "UNKNOWN"

    /**
     * 构造捕获摘要 JSON（单行、固定字段）。
     *
     * - [t1Ms] 负数裁剪为 0（计时器未就绪时的占位值不应污染统计）。
     * - [mode] 为 null 时写 [UNKNOWN_MODE]。
     * - [fallbackReason] 为 null 时写 JSON null。
     */
    fun buildCaptureSummaryJson(
        mode: String?,
        frameCount: Int,
        t1Ms: Long,
        fallbackReason: String? = null,
        timestampMs: Long = System.currentTimeMillis()
    ): String {
        val json = JSONObject()
            .put(ZslTelemetryFields.FIELD_VERSION, ZslTelemetryFields.SCHEMA_VERSION)
            .put(ZslTelemetryFields.FIELD_MODE, mode ?: UNKNOWN_MODE)
            .put(ZslTelemetryFields.FIELD_FRAMES, frameCount)
            .put(ZslTelemetryFields.FIELD_T1_MS, t1Ms.coerceAtLeast(0L))
            .put(ZslTelemetryFields.FIELD_FALLBACK_REASON, fallbackReason ?: JSONObject.NULL)
            .put(ZslTelemetryFields.FIELD_TIMESTAMP_MS, timestampMs)
        return json.toString()
    }

    /**
     * 发射单行遥测日志（`ZSL_METRICS {json}`）。
     *
     * 语义：每次**成功**拍摄恰输出 1 行（HDR+ burst 走 [buildCaptureSummaryJson] 的 burst 钩子，
     * 单帧走 onSingleFrameAccepted）；失败路径（0 帧 watchdog / 捕获异常）本阶段不发射，
     * 其 `fallbackReason` 遥测在 S2 统一接入。
     */
    fun emitCaptureSummary(
        mode: String?,
        frameCount: Int,
        t1Ms: Long,
        fallbackReason: String? = null,
        timestampMs: Long = System.currentTimeMillis()
    ) {
        val json = buildCaptureSummaryJson(mode, frameCount, t1Ms, fallbackReason, timestampMs)
        Log.i(ZslTelemetryFields.TAG, "${ZslTelemetryFields.PREFIX} $json")
    }
}
