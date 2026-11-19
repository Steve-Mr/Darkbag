package top.maary.darkbag.viewer.strategy

import android.os.Bundle

/**
 * 图像查看器数据源多态策略工厂 (ViewerMediaStrategyFactory).
 * 根据导航参数判定并提供对应的数据源策略实例。
 */
object ViewerMediaStrategyFactory {

    const val KEY_PLAYGROUND_DNG_PATHS = "playground_dng_paths"
    const val KEY_PLAYGROUND_HF_LAYOUT = "playground_hf_layout"

    fun create(args: Bundle?): ViewerMediaStrategy {
        val playgroundPaths = args?.getStringArray(KEY_PLAYGROUND_DNG_PATHS)
        return if (!playgroundPaths.isNullOrEmpty()) {
            SandboxViewerStrategy()
        } else {
            MediaStoreViewerStrategy()
        }
    }
}
