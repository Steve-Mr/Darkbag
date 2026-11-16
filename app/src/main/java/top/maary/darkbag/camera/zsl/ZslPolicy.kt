package top.maary.darkbag.camera.zsl

import top.maary.darkbag.camera.zsl.probe.ZslProbeReport
import top.maary.darkbag.camera.zsl.probe.ZslProbeVerdict

/** ZSL 运行模式。 */
enum class ZslMode {
    /** 完全不介入采集路径（基线等价）。 */
    DISABLED,

    /** 按探测报告推荐参数自动武装。 */
    AUTO
}

/** 环曝光策略。S1 不介入曝光（保持 AE），Z3 才切到 [FIXED_BURST_EXPOSURE]。 */
enum class ZslExposurePolicy {
    /** 环帧沿用当前 AE 曝光。 */
    AE_CONTINUOUS,

    /** 环帧使用固定的 burst 曝光（Z3 才启用）。 */
    FIXED_BURST_EXPOSURE
}

/**
 * RAW 环参数（纯值对象）。
 *
 * @property depth 环深度（帧数）
 * @property targetFps 目标帧率
 * @property minValidFrames 判定"环有效"所需的最少帧数
 */
data class RingConfig(
    val depth: Int,
    val targetFps: Int,
    val minValidFrames: Int
) {
    /**
     * 三个维度全部为正才算可执行环参数。
     *
     * 用于两道防线（S1-B1 F4）：报告解码时拒绝"正向 verdict 但环非法"的 JSON，
     * 以及 [ZslPolicy.fromReport] 对内存中手工构造的非法报告防御性回落 [Disabled]。
     */
    fun isUsable(): Boolean = depth > 0 && targetFps > 0 && minValidFrames > 0

    companion object {
        /** 关闭态：调用方据此跳过任何环逻辑。 */
        val DISABLED = RingConfig(0, 0, 0)
    }
}

/**
 * 帧来源策略：由探测报告纯函数映射而来，不持有任何运行时状态或 Android 依赖。
 *
 * 映射规则（设计文档 §3.8.5 / §3.7）：
 * - 用户关闭实验开关 → [Disabled]；
 * - 无报告（未检测 / 已失效）→ [Disabled]；
 * - 报告判定为 [ZslProbeVerdict.UNSUPPORTED] → [Disabled]；
 * - 报告推荐环非法（任一维度 ≤0，含 `RingConfig.DISABLED`）→ [Disabled]：绝不武装 0 深度/0 fps 的环；
 * - [ZslProbeVerdict.SUPPORTED] / [ZslProbeVerdict.RECOMMENDED] → [ZslMode.AUTO]，
 *   环参数直接取报告推荐值，曝光在 S1 阶段恒为 [ZslExposurePolicy.AE_CONTINUOUS]。
 */
data class ZslPolicy(
    val mode: ZslMode = ZslMode.DISABLED,
    val ring: RingConfig = RingConfig.DISABLED,
    val exposure: ZslExposurePolicy = ZslExposurePolicy.AE_CONTINUOUS
) {
    companion object {
        val Disabled = ZslPolicy()

        /** 报告 + 用户开关 → 策略。任意无效输入都安全回落 [Disabled]。 */
        fun fromReport(report: ZslProbeReport?, enabledByUser: Boolean): ZslPolicy {
            if (!enabledByUser) return Disabled
            if (report == null) return Disabled
            if (report.verdict == ZslProbeVerdict.UNSUPPORTED) return Disabled
            if (!report.ringConfig.isUsable()) return Disabled
            return ZslPolicy(
                mode = ZslMode.AUTO,
                ring = report.ringConfig,
                exposure = ZslExposurePolicy.AE_CONTINUOUS
            )
        }
    }
}
