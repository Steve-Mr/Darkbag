package top.maary.darkbag.viewer.editor

import android.app.Application
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.maary.darkbag.models.BasicAdjustments
import top.maary.darkbag.models.EditConfig
import top.maary.darkbag.models.ImageGroup
import top.maary.darkbag.pipeline.darkroom.DarkroomRenderEngine
import top.maary.darkbag.pipeline.darkroom.model.DarkroomRenderSpec
import top.maary.darkbag.pipeline.darkroom.model.RenderOutputFormat
import top.maary.darkbag.pipeline.darkroom.sink.BitmapPreviewSink
import top.maary.darkbag.repository.ImageRepository
import java.util.Locale

/**
 * 图像编辑器响应式 ViewModel (DarkroomEditorViewModel).
 * 统一管理编辑状态、滑块数值换算、半格参数隔离与防抖预览渲染。
 */
class DarkroomEditorViewModel(
    application: Application
) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "DarkroomEditorViewModel"
        const val EXPOSURE_MIN = -3.0f
        const val EXPOSURE_MAX = 3.0f
        const val ADJUSTMENT_MIN = -1.0f
        const val ADJUSTMENT_MAX = 1.0f
        private const val PREVIEW_DEBOUNCE_MS = 150L
    }

    private val repository by lazy { ImageRepository(getApplication()) }

    private val _uiState = MutableStateFlow(DarkroomEditorUiState())
    val uiState: StateFlow<DarkroomEditorUiState> = _uiState.asStateFlow()

    private var activeGroup: ImageGroup? = null
    private var sourceDngBytes1: ByteArray? = null
    private var sourceDngBytes2: ByteArray? = null
    private var cachedBitmap1: Bitmap? = null
    private var cachedBitmap2: Bitmap? = null
    private var lastPreviewConfig: EditConfig? = null
    private var previewJob: Job? = null

    fun processIntent(intent: DarkroomEditorUserIntent) {
        when (intent) {
            is DarkroomEditorUserIntent.SetActiveGroup -> handleSetActiveGroup(intent.group)
            is DarkroomEditorUserIntent.EnterEditMode -> handleEnterEditMode(intent.group, intent.dngBytes1, intent.dngBytes2)
            is DarkroomEditorUserIntent.ExitEditMode -> handleExitEditMode(intent.apply)
            is DarkroomEditorUserIntent.SelectTab -> handleSelectTab(intent.tabIndex)
            is DarkroomEditorUserIntent.SelectHalfFrameIndex -> handleSelectHalfFrameIndex(intent.index)
            is DarkroomEditorUserIntent.UpdateSlider1 -> handleUpdateSlider(1, intent.value)
            is DarkroomEditorUserIntent.UpdateSlider2 -> handleUpdateSlider(2, intent.value)
            is DarkroomEditorUserIntent.SetLut -> handleSetLut(intent.lutFileName)
            is DarkroomEditorUserIntent.SetLog -> handleSetLog(intent.logName)
            is DarkroomEditorUserIntent.SetFlareType -> handleSetFlareType(intent.flareType)
            DarkroomEditorUserIntent.ToggleTimestamp -> handleToggleTimestamp()
            DarkroomEditorUserIntent.ToggleSwap -> handleToggleSwap()
            DarkroomEditorUserIntent.ResetAdjustments -> handleResetAdjustments()
            is DarkroomEditorUserIntent.SetDngBytes -> {
                sourceDngBytes1 = intent.bytes1
                sourceDngBytes2 = intent.bytes2
            }
        }
    }

    private fun handleSetActiveGroup(group: ImageGroup) {
        activeGroup = group
        val isHalfFrame = group.isHalfFrame()
        val baseConfig = group.editConfig?.copy() ?: EditConfig()
        val sliderState = calculateSliderState(
            tabPos = _uiState.value.selectedTab,
            config = baseConfig,
            isHalfFrame = isHalfFrame,
            selectedHalfFrameIndex = _uiState.value.selectedHalfFrameIndex
        )

        val lutName = if (!baseConfig.lut.isNullOrBlank() && baseConfig.lut != "None") {
            baseConfig.lut.substringBeforeLast(".")
        } else {
            "None"
        }
        val logName = if (!baseConfig.log.isNullOrBlank() && baseConfig.log != "None") baseConfig.log else "None"

        _uiState.update { current ->
            current.copy(
                isHalfFrame = isHalfFrame,
                activeConfig = baseConfig,
                originalConfig = baseConfig.copy(),
                isAdjusted = false,
                currentLutName = lutName,
                currentLogName = logName,
                showTimestamp = baseConfig.showTimestamp,
                flareType = baseConfig.flareType,
                isSwapped = baseConfig.isSwapped,
                slider1Label = sliderState.label1,
                slider1Min = sliderState.min1,
                slider1Max = sliderState.max1,
                slider1Value = sliderState.value1,
                slider1DisplayValue = sliderState.display1,
                slider2Label = sliderState.label2,
                slider2Min = sliderState.min2,
                slider2Max = sliderState.max2,
                slider2Value = sliderState.value2,
                slider2DisplayValue = sliderState.display2,
                isSlider2Visible = sliderState.isSlider2Visible,
                previewBitmap = null
            )
        }
    }

    private fun handleEnterEditMode(group: ImageGroup, dngBytes1: ByteArray?, dngBytes2: ByteArray?) {
        activeGroup = group
        if (dngBytes1 != null) sourceDngBytes1 = dngBytes1
        if (dngBytes2 != null) sourceDngBytes2 = dngBytes2

        val isHalfFrame = group.isHalfFrame()
        val baseConfig = _uiState.value.activeConfig ?: group.editConfig?.copy() ?: EditConfig()
        val sliderState = calculateSliderState(
            tabPos = _uiState.value.selectedTab,
            config = baseConfig,
            isHalfFrame = isHalfFrame,
            selectedHalfFrameIndex = _uiState.value.selectedHalfFrameIndex
        )

        _uiState.update { current ->
            current.copy(
                isEditing = true,
                isHalfFrame = isHalfFrame,
                activeConfig = baseConfig,
                originalConfig = current.originalConfig ?: baseConfig.copy(),
                slider1Label = sliderState.label1,
                slider1Min = sliderState.min1,
                slider1Max = sliderState.max1,
                slider1Value = sliderState.value1,
                slider1DisplayValue = sliderState.display1,
                slider2Label = sliderState.label2,
                slider2Min = sliderState.min2,
                slider2Max = sliderState.max2,
                slider2Value = sliderState.value2,
                slider2DisplayValue = sliderState.display2,
                isSlider2Visible = sliderState.isSlider2Visible
            )
        }
    }

    private fun handleExitEditMode(apply: Boolean) {
        if (!apply) {
            val restoredConfig = _uiState.value.originalConfig?.copy()
            val isHalfFrame = _uiState.value.isHalfFrame
            val sliderState = calculateSliderState(
                tabPos = _uiState.value.selectedTab,
                config = restoredConfig ?: EditConfig(),
                isHalfFrame = isHalfFrame,
                selectedHalfFrameIndex = _uiState.value.selectedHalfFrameIndex
            )

            _uiState.update { current ->
                current.copy(
                    isEditing = false,
                    isAdjusted = false,
                    activeConfig = restoredConfig,
                    slider1Value = sliderState.value1,
                    slider1DisplayValue = sliderState.display1,
                    slider2Value = sliderState.value2,
                    slider2DisplayValue = sliderState.display2,
                    previewBitmap = null
                )
            }
            if (restoredConfig != null) {
                scheduleDebouncedPreview(restoredConfig)
            }
        } else {
            _uiState.update { it.copy(isEditing = false) }
        }
    }

    private fun handleSelectTab(tabIndex: Int) {
        val config = _uiState.value.activeConfig ?: EditConfig()
        val sliderState = calculateSliderState(
            tabPos = tabIndex,
            config = config,
            isHalfFrame = _uiState.value.isHalfFrame,
            selectedHalfFrameIndex = _uiState.value.selectedHalfFrameIndex
        )

        _uiState.update { current ->
            current.copy(
                selectedTab = tabIndex,
                slider1Label = sliderState.label1,
                slider1Min = sliderState.min1,
                slider1Max = sliderState.max1,
                slider1Value = sliderState.value1,
                slider1DisplayValue = sliderState.display1,
                slider2Label = sliderState.label2,
                slider2Min = sliderState.min2,
                slider2Max = sliderState.max2,
                slider2Value = sliderState.value2,
                slider2DisplayValue = sliderState.display2,
                isSlider2Visible = sliderState.isSlider2Visible
            )
        }
    }

    private fun handleSelectHalfFrameIndex(index: Int) {
        val config = _uiState.value.activeConfig ?: EditConfig()
        val sliderState = calculateSliderState(
            tabPos = _uiState.value.selectedTab,
            config = config,
            isHalfFrame = _uiState.value.isHalfFrame,
            selectedHalfFrameIndex = index
        )

        _uiState.update { current ->
            current.copy(
                selectedHalfFrameIndex = index,
                slider1Value = sliderState.value1,
                slider1DisplayValue = sliderState.display1,
                slider2Value = sliderState.value2,
                slider2DisplayValue = sliderState.display2
            )
        }
    }

    private fun handleUpdateSlider(sliderId: Int, value: Float) {
        val currentConfig = _uiState.value.activeConfig ?: EditConfig()
        val updatedConfig = applySliderChange(
            sliderId = sliderId,
            value = value,
            tabPos = _uiState.value.selectedTab,
            currentConfig = currentConfig,
            isHalfFrame = _uiState.value.isHalfFrame,
            selectedHalfFrameIndex = _uiState.value.selectedHalfFrameIndex
        )

        val sliderState = calculateSliderState(
            tabPos = _uiState.value.selectedTab,
            config = updatedConfig,
            isHalfFrame = _uiState.value.isHalfFrame,
            selectedHalfFrameIndex = _uiState.value.selectedHalfFrameIndex
        )

        _uiState.update { current ->
            current.copy(
                isAdjusted = true,
                activeConfig = updatedConfig,
                slider1Value = sliderState.value1,
                slider1DisplayValue = sliderState.display1,
                slider2Value = sliderState.value2,
                slider2DisplayValue = sliderState.display2
            )
        }

        scheduleDebouncedPreview(updatedConfig)
    }

    private fun handleSetLut(lutFileName: String?) {
        val currentConfig = _uiState.value.activeConfig ?: EditConfig()
        val updatedConfig = currentConfig.copy(lut = lutFileName ?: "None")
        val lutName = if (!lutFileName.isNullOrBlank() && lutFileName != "None") {
            lutFileName.substringBeforeLast(".")
        } else {
            "None"
        }

        _uiState.update { current ->
            current.copy(
                isAdjusted = true,
                activeConfig = updatedConfig,
                currentLutName = lutName
            )
        }
        scheduleDebouncedPreview(updatedConfig)
    }

    private fun handleSetLog(logName: String?) {
        val currentConfig = _uiState.value.activeConfig ?: EditConfig()
        val updatedConfig = currentConfig.copy(log = logName ?: "None")
        val cleanLog = if (!logName.isNullOrBlank() && logName != "None") logName else "None"

        _uiState.update { current ->
            current.copy(
                isAdjusted = true,
                activeConfig = updatedConfig,
                currentLogName = cleanLog
            )
        }
        scheduleDebouncedPreview(updatedConfig)
    }

    private fun handleSetFlareType(flareType: Int) {
        val currentConfig = _uiState.value.activeConfig ?: EditConfig()
        val updatedConfig = currentConfig.copy(flareType = flareType)

        _uiState.update { current ->
            current.copy(
                isAdjusted = true,
                activeConfig = updatedConfig,
                flareType = flareType
            )
        }
        scheduleDebouncedPreview(updatedConfig)
    }

    private fun handleToggleTimestamp() {
        val currentConfig = _uiState.value.activeConfig ?: EditConfig()
        val nextTimestamp = !currentConfig.showTimestamp
        val updatedConfig = currentConfig.copy(showTimestamp = nextTimestamp)

        _uiState.update { current ->
            current.copy(
                isAdjusted = true,
                activeConfig = updatedConfig,
                showTimestamp = nextTimestamp
            )
        }
        scheduleDebouncedPreview(updatedConfig)
    }

    private fun handleToggleSwap() {
        val currentConfig = _uiState.value.activeConfig ?: EditConfig()
        val nextSwap = !currentConfig.isSwapped
        val updatedConfig = currentConfig.copy(isSwapped = nextSwap)

        val sliderState = calculateSliderState(
            tabPos = _uiState.value.selectedTab,
            config = updatedConfig,
            isHalfFrame = _uiState.value.isHalfFrame,
            selectedHalfFrameIndex = _uiState.value.selectedHalfFrameIndex
        )

        _uiState.update { current ->
            current.copy(
                isAdjusted = true,
                activeConfig = updatedConfig,
                isSwapped = nextSwap,
                slider1Value = sliderState.value1,
                slider1DisplayValue = sliderState.display1,
                slider2Value = sliderState.value2,
                slider2DisplayValue = sliderState.display2
            )
        }
        scheduleDebouncedPreview(updatedConfig)
    }

    private fun handleResetAdjustments() {
        previewJob?.cancel()
        cachedBitmap1 = null
        cachedBitmap2 = null
        lastPreviewConfig = null

        val baseConfig = activeGroup?.editConfig?.copy() ?: EditConfig()
        val isHalfFrame = activeGroup?.isHalfFrame() == true
        val sliderState = calculateSliderState(
            tabPos = 0,
            config = baseConfig,
            isHalfFrame = isHalfFrame,
            selectedHalfFrameIndex = 0
        )

        val lutName = if (!baseConfig.lut.isNullOrBlank() && baseConfig.lut != "None") {
            baseConfig.lut.substringBeforeLast(".")
        } else {
            "None"
        }
        val logName = if (!baseConfig.log.isNullOrBlank() && baseConfig.log != "None") baseConfig.log else "None"

        _uiState.update { current ->
            current.copy(
                isEditing = false,
                isAdjusted = false,
                activeConfig = baseConfig,
                originalConfig = baseConfig.copy(),
                selectedTab = 0,
                selectedHalfFrameIndex = 0,
                currentLutName = lutName,
                currentLogName = logName,
                showTimestamp = baseConfig.showTimestamp,
                flareType = baseConfig.flareType,
                isSwapped = baseConfig.isSwapped,
                slider1Label = sliderState.label1,
                slider1Min = sliderState.min1,
                slider1Max = sliderState.max1,
                slider1Value = sliderState.value1,
                slider1DisplayValue = sliderState.display1,
                slider2Label = sliderState.label2,
                slider2Min = sliderState.min2,
                slider2Max = sliderState.max2,
                slider2Value = sliderState.value2,
                slider2DisplayValue = sliderState.display2,
                isSlider2Visible = sliderState.isSlider2Visible,
                previewBitmap = null,
                previewVersion = current.previewVersion + 1
            )
        }
    }

    private fun scheduleDebouncedPreview(config: EditConfig) {
        val group = activeGroup ?: return
        if (group.isRawVideo || group.rawVideoUri != null) return

        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            delay(PREVIEW_DEBOUNCE_MS)
            _uiState.update { it.copy(isGeneratingPreview = true) }

            val compositeBitmap = withContext(Dispatchers.IO) {
                try {
                    val context = getApplication<Application>()
                    val forceUpdate1 = lastPreviewConfig == null ||
                            lastPreviewConfig?.log != config.log ||
                            lastPreviewConfig?.lut != config.lut ||
                            lastPreviewConfig?.adjustments?.getOrNull(0) != config.adjustments?.getOrNull(0)

                    val forceUpdate2 = lastPreviewConfig == null ||
                            lastPreviewConfig?.log != config.log ||
                            lastPreviewConfig?.lut != config.lut ||
                            lastPreviewConfig?.adjustments?.getOrNull(1) != config.adjustments?.getOrNull(1)

                    val previewSink = BitmapPreviewSink()
                    val spec = DarkroomRenderSpec.fromImageGroup(
                        context = context,
                        group = group,
                        editConfig = config,
                        sink = previewSink,
                        outputFormat = RenderOutputFormat.BITMAP,
                        downsampleFactor = 0,
                        cachedBytes1 = sourceDngBytes1,
                        cachedBytes2 = sourceDngBytes2,
                        cachedFrame1Bitmap = if (forceUpdate1) null else cachedBitmap1,
                        cachedFrame2Bitmap = if (forceUpdate2) null else cachedBitmap2,
                        downsampleHalfFrame = false,
                        repository = repository
                    )
                    val result = DarkroomRenderEngine.render(context, spec)
                    if (result.success && result.bitmap != null) {
                        if (result.frame1Bitmap != null) cachedBitmap1 = result.frame1Bitmap
                        if (result.frame2Bitmap != null) cachedBitmap2 = result.frame2Bitmap
                        lastPreviewConfig = config.copy()
                        result.bitmap
                    } else {
                        null
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to render debounced preview", e)
                    null
                }
            }

            _uiState.update { current ->
                current.copy(
                    isGeneratingPreview = false,
                    previewBitmap = compositeBitmap ?: current.previewBitmap,
                    previewVersion = if (compositeBitmap != null) current.previewVersion + 1 else current.previewVersion
                )
            }
        }
    }

    fun getCachedDngBytes(): Pair<ByteArray?, ByteArray?> = Pair(sourceDngBytes1, sourceDngBytes2)

    override fun onCleared() {
        super.onCleared()
        previewJob?.cancel()
        cachedBitmap1 = null
        cachedBitmap2 = null
        sourceDngBytes1 = null
        sourceDngBytes2 = null
    }

    private data class SliderState(
        val label1: String,
        val min1: Float,
        val max1: Float,
        val value1: Float,
        val display1: String,
        val label2: String,
        val min2: Float,
        val max2: Float,
        val value2: Float,
        val display2: String,
        val isSlider2Visible: Boolean
    )

    private fun calculateSliderState(
        tabPos: Int,
        config: EditConfig,
        isHalfFrame: Boolean,
        selectedHalfFrameIndex: Int
    ): SliderState {
        val target = if (isHalfFrame) {
            val adjIndex = if (config.isSwapped) 1 - selectedHalfFrameIndex else selectedHalfFrameIndex
            config.adjustments?.getOrNull(adjIndex) ?: BasicAdjustments()
        } else {
            BasicAdjustments(
                exposure = config.exposure,
                contrast = config.contrast,
                saturation = config.saturation,
                highlights = config.highlights,
                shadows = config.shadows,
                whites = config.whites,
                blacks = config.blacks
            )
        }

        return when (tabPos) {
            0 -> SliderState(
                label1 = "Exposure",
                min1 = EXPOSURE_MIN,
                max1 = EXPOSURE_MAX,
                value1 = target.exposure.coerceIn(EXPOSURE_MIN, EXPOSURE_MAX),
                display1 = String.format(Locale.US, "%.2f EV", target.exposure),
                label2 = "Contrast",
                min2 = ADJUSTMENT_MIN,
                max2 = ADJUSTMENT_MAX,
                value2 = target.contrast.coerceIn(ADJUSTMENT_MIN, ADJUSTMENT_MAX),
                display2 = String.format(Locale.US, "%.2f", target.contrast),
                isSlider2Visible = true
            )
            1 -> SliderState(
                label1 = "Highlights",
                min1 = ADJUSTMENT_MIN,
                max1 = ADJUSTMENT_MAX,
                value1 = target.highlights.coerceIn(ADJUSTMENT_MIN, ADJUSTMENT_MAX),
                display1 = String.format(Locale.US, "%.2f", target.highlights),
                label2 = "Shadows",
                min2 = ADJUSTMENT_MIN,
                max2 = ADJUSTMENT_MAX,
                value2 = target.shadows.coerceIn(ADJUSTMENT_MIN, ADJUSTMENT_MAX),
                display2 = String.format(Locale.US, "%.2f", target.shadows),
                isSlider2Visible = true
            )
            2 -> SliderState(
                label1 = "Whites",
                min1 = ADJUSTMENT_MIN,
                max1 = ADJUSTMENT_MAX,
                value1 = target.whites.coerceIn(ADJUSTMENT_MIN, ADJUSTMENT_MAX),
                display1 = String.format(Locale.US, "%.2f", target.whites),
                label2 = "Blacks",
                min2 = ADJUSTMENT_MIN,
                max2 = ADJUSTMENT_MAX,
                value2 = target.blacks.coerceIn(ADJUSTMENT_MIN, ADJUSTMENT_MAX),
                display2 = String.format(Locale.US, "%.2f", target.blacks),
                isSlider2Visible = true
            )
            else -> SliderState(
                label1 = "Saturation",
                min1 = ADJUSTMENT_MIN,
                max1 = ADJUSTMENT_MAX,
                value1 = target.saturation.coerceIn(ADJUSTMENT_MIN, ADJUSTMENT_MAX),
                display1 = String.format(Locale.US, "%.2f", target.saturation),
                label2 = "",
                min2 = ADJUSTMENT_MIN,
                max2 = ADJUSTMENT_MAX,
                value2 = 0f,
                display2 = "",
                isSlider2Visible = false
            )
        }
    }

    private fun applySliderChange(
        sliderId: Int,
        value: Float,
        tabPos: Int,
        currentConfig: EditConfig,
        isHalfFrame: Boolean,
        selectedHalfFrameIndex: Int
    ): EditConfig {
        val updateBasic: (BasicAdjustments) -> BasicAdjustments = { old ->
            when (tabPos) {
                0 -> if (sliderId == 1) old.copy(exposure = value) else old.copy(contrast = value)
                1 -> if (sliderId == 1) old.copy(highlights = value) else old.copy(shadows = value)
                2 -> if (sliderId == 1) old.copy(whites = value) else old.copy(blacks = value)
                3 -> if (sliderId == 1) old.copy(saturation = value) else old
                else -> old
            }
        }

        return if (isHalfFrame) {
            val adjs = currentConfig.adjustments?.toMutableList() ?: mutableListOf(BasicAdjustments(), BasicAdjustments())
            while (adjs.size < 2) adjs.add(BasicAdjustments())
            val adjIndex = if (currentConfig.isSwapped) 1 - selectedHalfFrameIndex else selectedHalfFrameIndex
            adjs[adjIndex] = updateBasic(adjs[adjIndex])
            currentConfig.copy(adjustments = adjs)
        } else {
            val basic = updateBasic(
                BasicAdjustments(
                    exposure = currentConfig.exposure,
                    contrast = currentConfig.contrast,
                    saturation = currentConfig.saturation,
                    highlights = currentConfig.highlights,
                    shadows = currentConfig.shadows,
                    whites = currentConfig.whites,
                    blacks = currentConfig.blacks
                )
            )
            currentConfig.copy(
                exposure = basic.exposure,
                contrast = basic.contrast,
                highlights = basic.highlights,
                shadows = basic.shadows,
                whites = basic.whites,
                blacks = basic.blacks,
                saturation = basic.saturation
            )
        }
    }
}
