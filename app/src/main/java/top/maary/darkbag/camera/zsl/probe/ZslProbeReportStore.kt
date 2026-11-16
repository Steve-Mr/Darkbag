package top.maary.darkbag.camera.zsl.probe

import top.maary.darkbag.repository.SettingsRepository

/**
 * 探测报告持久化端口实现（设计文档 §3.8.5）。
 *
 * 报告以单行 JSON 存在 DataStore 的 `zsl_probe_report` 键，并同步写 `zsl_probe_version`
 * （= [ZslProbeReport.SCHEMA_VERSION]），让"报告体"与"报告版本"在存储层也可独立审计：
 * UI（S1-B2）可以在不解码整份 JSON 的前提下显示/比对版本，未来 schema 升级也能靠该键快速
 * 判断存量报告是否过期；清空报告时版本一并归零，表示"无生效报告"。
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
    fun save(report: ZslProbeReport?) = save(report?.toJson())

    /**
     * 按原始 JSON 保存（探测刚完成、或 S1-B2 导入外部报告时使用）。
     *
     * 空白字符串表示 **清空报告**：移除 `zsl_probe_report` 并把 `zsl_probe_version` 归零，
     * 使"是否有生效报告"只需看两个键即可判断。
     */
    fun save(json: String?) {
        val normalized = json?.takeIf { it.isNotBlank() }
        settingsRepository.setZslProbeReportJson(normalized)
        settingsRepository.setZslProbeVersion(
            if (normalized == null) NO_REPORT_VERSION else ZslProbeReport.SCHEMA_VERSION
        )
    }

    /** 清空报告与版本（等价于 `save("")`）。 */
    fun clear() = save(null as String?)

    private companion object {
        /** 无生效报告时的版本值（与 DataStore 键默认值一致）。 */
        const val NO_REPORT_VERSION = 0
    }
}
