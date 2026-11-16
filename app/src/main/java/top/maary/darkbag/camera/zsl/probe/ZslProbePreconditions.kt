package top.maary.darkbag.camera.zsl.probe

/**
 * 主动探测的前置条件输入（纯数据，无 Android 依赖）。
 *
 * 由 `CameraZslProbeRunner` 从运行时环境采集后传入 [ZslProbePreconditions]：
 * - [sessionActive]：`sessionArbiter.camera2Session != null`（会话已建立、可改 repeating）
 * - [isRecording]：RAW 视频录制中 **或** 普通视频保存中
 * - [isMultiCamera] / [isFrontPip]：多摄 / 前摄 PiP 激活（会话被其它管理器接管）
 * - [batteryPercent]：`BatteryManager.BATTERY_PROPERTY_CAPACITY`，`null` 表示读取失败/未知
 * - [availableMemoryBytes]：`ActivityManager.MemoryInfo.availMem`，读取失败为 0
 * - [thermalStatus]：`PowerManager.currentThermalStatus`，`null` 表示未知（API < 29 / 读取失败）
 * - [rawSupported]：当前镜头是否有 `RAW_SENSOR` 输出（`runtimeState.isRawSupported`）
 */
data class ZslProbePreconditionInput(
    val sessionActive: Boolean,
    val isRecording: Boolean,
    val isMultiCamera: Boolean,
    val isFrontPip: Boolean,
    val batteryPercent: Int?,
    val availableMemoryBytes: Long,
    val thermalStatus: Int?,
    val rawSupported: Boolean
)

/**
 * 探测前置条件判定（设计 §3.8.2 / §3.8.4 表末两行，S1-B2 R12）。
 *
 * 纯函数、零副作用、零 Android 依赖：Runner 在**构造适配器之前**求值，不通过就不碰任何硬件。
 *
 * 判定顺序（先"现在不能测"，后"永远不能测"）：
 * 1. 会话未 Active / 录制中（含 RAW 录制与普通视频保存）/ 多摄 / PiP / 电量 <10% 拒绝（10% 通过）/
 *    `availMem < [MIN_AVAILABLE_MEMORY_BYTES]` / 热状态 > `MODERATE`
 *    → [ZslProbeFailure.PRECONDITION_FAILED]；
 * 2. 无 RAW 能力 → [ZslProbeFailure.RAW_UNSUPPORTED]。
 *
 * 口径（S1-B2 复审 F2）：共 **8 条规则** / **9 个判定分支**——"RAW 录制中"与"普通视频保存中"
 * 在入口合并为 [ZslProbePreconditionInput.isRecording] 这一条规则。旧口径"9 条规则"以此为准。
 *
 * 顺序有语义：环境类失败是**可重试**的（Runner 应报 `Failed(PRECONDITION_FAILED)` 且不落报告），
 * 而 RAW 能力缺失是**设备属性**（Runner 落一份 `UNSUPPORTED` 报告）。若两者同时出现，
 * 先报可重试的那一个，避免把"电量低"误写成"设备不支持 RAW"。
 *
 * 未知量不判失败：[batteryPercent]、[thermalStatus] 为 `null` 时视为"未测到"而放行
 * （与判定层对未知热状态的口径一致，S1-B1 F1）；内存为 0 表示读取失败，按规格保守判失败。
 */
object ZslProbePreconditions {

    /** 电量下限（%）：口径为"电量 <10% 拒绝，10% 通过"。 */
    const val MIN_BATTERY_PERCENT = 10

    /** 可用内存下限：`availMem ≥ 600 MB`（RAW scratch + 环缓冲的余量）。 */
    const val MIN_AVAILABLE_MEMORY_BYTES = 600L * 1024L * 1024L

    /** 热状态上限：`PowerManager.THERMAL_STATUS_MODERATE`（常量 2），超过即不测。 */
    const val MAX_THERMAL_STATUS = 2

    /**
     * 求值：`null` 表示通过；否则返回阻塞原因码。
     *
     * 调用方据此分流：`RAW_UNSUPPORTED` → 产出 `UNSUPPORTED` 报告；
     * 其它（`PRECONDITION_FAILED`）→ 直接失败，不构造适配器。
     */
    fun evaluate(input: ZslProbePreconditionInput): ZslProbeFailure? =
        firstViolation(input)?.failure

    /**
     * 第一处未通过规则的可读描述（用于 `Failed.detail` 与日志）；通过时返回 `null`。
     * 与 [evaluate] 共用同一判定函数，二者永不漂移。
     */
    fun describe(input: ZslProbePreconditionInput): String? =
        firstViolation(input)?.description

    private enum class Rule(val failure: ZslProbeFailure, val description: String) {
        SESSION_INACTIVE(ZslProbeFailure.PRECONDITION_FAILED, "session inactive"),
        RECORDING(ZslProbeFailure.PRECONDITION_FAILED, "recording in progress"),
        MULTI_CAMERA(ZslProbeFailure.PRECONDITION_FAILED, "multi-camera active"),
        FRONT_PIP(ZslProbeFailure.PRECONDITION_FAILED, "front PiP active"),
        BATTERY_LOW(ZslProbeFailure.PRECONDITION_FAILED, "battery below ${MIN_BATTERY_PERCENT}%"),
        MEMORY_LOW(
            ZslProbeFailure.PRECONDITION_FAILED,
            "available memory below ${MIN_AVAILABLE_MEMORY_BYTES / (1024L * 1024L)} MB"
        ),
        THERMAL_HIGH(ZslProbeFailure.PRECONDITION_FAILED, "thermal status above MODERATE"),
        RAW_UNSUPPORTED(ZslProbeFailure.RAW_UNSUPPORTED, "raw sensor not supported")
    }

    private fun firstViolation(input: ZslProbePreconditionInput): Rule? = when {
        !input.sessionActive -> Rule.SESSION_INACTIVE
        input.isRecording -> Rule.RECORDING
        input.isMultiCamera -> Rule.MULTI_CAMERA
        input.isFrontPip -> Rule.FRONT_PIP
        input.batteryPercent != null && input.batteryPercent < MIN_BATTERY_PERCENT -> Rule.BATTERY_LOW
        input.availableMemoryBytes < MIN_AVAILABLE_MEMORY_BYTES -> Rule.MEMORY_LOW
        input.thermalStatus != null && input.thermalStatus > MAX_THERMAL_STATUS -> Rule.THERMAL_HIGH
        !input.rawSupported -> Rule.RAW_UNSUPPORTED
        else -> null
    }
}
