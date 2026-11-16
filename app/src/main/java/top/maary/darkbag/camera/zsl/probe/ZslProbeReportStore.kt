package top.maary.darkbag.camera.zsl.probe

import android.util.Log
import top.maary.darkbag.repository.SettingsRepository

/**
 * 探测报告持久化端口实现（设计文档 §3.8.5）。
 *
 * 报告以单行 JSON 存在 DataStore 的 `zsl_probe_report` 键，并同步写 `zsl_probe_version`
 * （= 报告解码出的 schema 版本），让"报告体"与"报告版本"在存储层也可独立审计：
 * UI（S1-B3）可以在不解码整份 JSON 的前提下显示/比对版本，未来 schema 升级也能靠该键快速
 * 判断存量报告是否过期；清空报告时版本一并归零，表示"无生效报告"。
 *
 * **原子性（S1-B2 R11）**：两个键通过
 * [SettingsRepository.setZslProbeBundle] 在单次 DataStore `edit` 事务里一起写，且保存前先用
 * [ZslProbeReport.fromJson] 解码校验——只有能解码的报告才会被写入，写进去的版本号就是解码出的
 * 版本号，因此不存在"半更新报告"或"版本与报告体不一致"的窗口。无法解码的 JSON **视为清空**
 * 并记一条日志（不把垃圾留在存储里冒充有效报告）。
 *
 * 本类只做编解码与键映射，不做设备/镜头失效判定（那是
 * [ZslProbeReport.isApplicable] 的职责），也不缓存任何硬件状态。
 */
class ZslProbeReportStore(private val settingsRepository: SettingsRepository) {

    /**
     * 读取并解码当前报告。
     *
     * @return 无报告 / JSON 损坏 / 版本不符 / 正向 verdict 缺合法环 → `null`（视为未检测）
     */
    fun load(): ZslProbeReport? =
        ZslProbeReport.fromJson(settingsRepository.current().zslProbeReportJson)

    /** 保存一份报告；`null` 等价于 [clear]。 */
    suspend fun save(report: ZslProbeReport?) = save(report?.toJson())

    /**
     * 按原始 JSON 保存（探测刚完成、或 S1-B3 导入外部报告时使用）。
     *
     * 空白字符串表示 **清空报告**：移除 `zsl_probe_report` 并把 `zsl_probe_version` 归零，
     * 使"是否有生效报告"只需看两个键即可判断。
     *
     * @return 实际落库的版本号（清空/无法解码时为 0），便于日志与调用方断言
     */
    suspend fun save(json: String?): Int {
        val normalized = json?.takeIf { it.isNotBlank() }
        val decoded = ZslProbeReport.fromJson(normalized)
        if (decoded == null) {
            if (normalized != null) {
                Log.w(TAG, "Discarding undecodable ZSL probe report (${normalized.length} chars)")
            }
            settingsRepository.setZslProbeBundle(null, NO_REPORT_VERSION)
            return NO_REPORT_VERSION
        }
        settingsRepository.setZslProbeBundle(normalized, decoded.version)
        return decoded.version
    }

    /** 清空报告与版本（等价于 `save("")`）。 */
    suspend fun clear(): Int = save(null as String?)

    private companion object {
        const val TAG = "ZslProbeReportStore"

        /** 无生效报告时的版本值（与 DataStore 键默认值一致）。 */
        const val NO_REPORT_VERSION = 0
    }
}
