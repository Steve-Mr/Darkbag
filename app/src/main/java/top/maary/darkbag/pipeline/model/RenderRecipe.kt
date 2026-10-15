package top.maary.darkbag.pipeline.model

import top.maary.darkbag.models.EditConfig

/**
 * 图像渲染调色配方（纯数学与色彩后处理意图）。
 */
data class RenderRecipe(
    val targetLogIndex: Int = 0,
    val lutPath: String? = null,
    val digitalGain: Float = 1.0f,
    val exposure: Float = 0.0f,
    val contrast: Float = 0.0f,
    val saturation: Float = 0.0f,
    val highlights: Float = 0.0f,
    val shadows: Float = 0.0f,
    val whites: Float = 0.0f,
    val blacks: Float = 0.0f,
    val colorEngineMode: Int = 2,
    val faithfulHighlights: Boolean = false,
    val enableMemoryColor: Boolean = false,
    val editConfig: EditConfig? = null
)
