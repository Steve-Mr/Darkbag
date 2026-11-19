package top.maary.darkbag.viewer.editor

import android.graphics.Bitmap
import top.maary.darkbag.models.EditConfig

/**
 * 图像编辑器响应式 UI 状态 (DarkroomEditorUiState).
 * 严格遵循 MVI 单向数据流与 Architectural Invariant 4 (State-driven UI).
 */
data class DarkroomEditorUiState(
    val isEditing: Boolean = false,
    val isAdjusted: Boolean = false,
    val activeConfig: EditConfig? = null,
    val originalConfig: EditConfig? = null,
    val selectedTab: Int = 0, // 0: Light, 1: Range, 2: Tone, 3: Color
    val selectedHalfFrameIndex: Int = 0, // 0: Frame 1, 1: Frame 2
    val isHalfFrame: Boolean = false,
    val isGeneratingPreview: Boolean = false,
    val slider1Label: String = "Exposure",
    val slider1Value: Float = 0.0f,
    val slider1Min: Float = -3.0f,
    val slider1Max: Float = 3.0f,
    val slider1DisplayValue: String = "0.00 EV",
    val slider2Label: String = "Contrast",
    val slider2Value: Float = 0.0f,
    val slider2Min: Float = -1.0f,
    val slider2Max: Float = 1.0f,
    val slider2DisplayValue: String = "0.00",
    val isSlider2Visible: Boolean = true,
    val currentLutName: String = "None",
    val currentLogName: String = "None",
    val showTimestamp: Boolean = false,
    val flareType: Int = -1,
    val isSwapped: Boolean = false,
    val previewBitmap: Bitmap? = null,
    val previewVersion: Long = 0L
)
