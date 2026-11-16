package top.maary.darkbag.camera.state

import java.util.LinkedHashMap

/**
 * 有界元数据缓存：以插入序淘汰最旧条目，避免 30fps 预览结果在 [CameraRuntimeStateHolder]
 * 中无界增长（30 分钟 ≈ 5.4 万条 TotalCaptureResult）。
 *
 * 值类型泛化仅为让 JVM 单测可注入轻量假值（[android.hardware.camera2.TotalCaptureResult]
 * 在 host 单测中无法构造）；生产侧实例化即 `BoundedCaptureResultCache<TotalCaptureResult>`，
 * 仍是 `LinkedHashMap<Long, TotalCaptureResult>` 的子类，插入序查找语义不变。
 */
class BoundedCaptureResultCache<V : Any>(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES
) : LinkedHashMap<Long, V>() {

    companion object {
        /** 300 条 ≈ 10 s @30fps，足以覆盖一次连拍（≤ 20 帧）前后的精确时间戳查找。 */
        const val DEFAULT_MAX_ENTRIES = 300
    }

    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, V>): Boolean =
        size > maxEntries
}
