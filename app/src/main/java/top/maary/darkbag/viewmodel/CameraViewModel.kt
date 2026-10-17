package top.maary.darkbag.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import top.maary.darkbag.modes.CaptureMode

/**
 * 相机核心视图模型 (MVI / UDF).
 * 管理相机的单向数据流、UI 状态、瞬态副作用派发。
 */
class CameraViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(CameraUiState())
    val uiState = _uiState.asStateFlow()

    private val _effects = Channel<CameraEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    fun processIntent(intent: CameraUserIntent) {
        when (intent) {
            is CameraUserIntent.ChangeMode -> {
                _uiState.update { it.copy(currentMode = intent.mode) }
            }
            is CameraUserIntent.SwitchLens -> {
                _uiState.update { it.copy(activeLensId = intent.lensId) }
            }
            is CameraUserIntent.SetZoomRatio -> {
                _uiState.update { it.copy(zoomRatio = intent.ratio) }
            }
            is CameraUserIntent.SetHdrPlusEnabled -> {
                _uiState.update { it.copy(isHdrPlusEnabled = intent.enabled) }
            }
            is CameraUserIntent.SetBurstActive -> {
                val wasActive = _uiState.value.isBurstActive
                _uiState.update { it.copy(isBurstActive = intent.active) }
                if (!intent.active && wasActive) {
                    emitEffect(CameraEffect.ResetShutterUi)
                }
            }
            is CameraUserIntent.UpdateTaskCounts -> {
                _uiState.update {
                    it.copy(
                        pendingTasksCount = intent.totalCount,
                        pendingForegroundTasksCount = intent.foregroundCount,
                        isProcessing = intent.foregroundCount > 0
                    )
                }
            }
            is CameraUserIntent.SetHalfFrameStep -> {
                _uiState.update { it.copy(halfFrameStep = intent.step) }
            }
            is CameraUserIntent.UpdateThumbnail -> {
                _uiState.update {
                    it.copy(
                        latestThumbnailUri = intent.uri,
                        latestThumbnailPath = intent.path
                    )
                }
            }
            is CameraUserIntent.UpdateShutterDotRotation -> {
                _uiState.update { it.copy(shutterDotRotation = intent.rotation) }
            }
            is CameraUserIntent.SetSwitchingLens -> {
                _uiState.update { it.copy(isSwitchingLens = intent.switching) }
            }
            is CameraUserIntent.SetUiTier -> {
                _uiState.update {
                    it.copy(
                        uiTier = intent.tier,
                        activeManualTab = if (intent.tier != CameraUiTier.PRO) null else it.activeManualTab
                    )
                }
            }
            is CameraUserIntent.CycleUiTier -> {
                _uiState.update {
                    val nextTier = it.uiTier.next()
                    it.copy(
                        uiTier = nextTier,
                        activeManualTab = if (nextTier != CameraUiTier.PRO) null else it.activeManualTab
                    )
                }
            }
            is CameraUserIntent.UpdateColorRecipe -> {
                _uiState.update {
                    it.copy(
                        activeLogName = intent.logName,
                        activeLutName = intent.lutName
                    )
                }
            }
            is CameraUserIntent.SetMotionPhotoEnabled -> {
                _uiState.update { it.copy(isMotionPhotoEnabled = intent.enabled) }
            }
            is CameraUserIntent.SetFlashEnabled -> {
                _uiState.update { it.copy(isFlashEnabled = intent.enabled) }
            }
            is CameraUserIntent.SetUnderexposureMode -> {
                _uiState.update { it.copy(underexposureMode = intent.mode) }
            }
            is CameraUserIntent.SetActiveManualTab -> {
                _uiState.update { it.copy(activeManualTab = intent.tab) }
            }
            is CameraUserIntent.UpdateManualFlags -> {
                _uiState.update {
                    it.copy(
                        isManualFocus = intent.isManualFocus,
                        isManualIso = intent.isManualIso,
                        isManualShutter = intent.isManualShutter,
                        isProLinked = intent.isProLinked
                    )
                }
            }
        }
    }

    fun emitEffect(effect: CameraEffect) {
        viewModelScope.launch {
            _effects.send(effect)
        }
    }

    fun setMode(mode: CaptureMode) = processIntent(CameraUserIntent.ChangeMode(mode))
    fun setLensId(lensId: String) = processIntent(CameraUserIntent.SwitchLens(lensId))
    fun setZoomRatio(ratio: Float) = processIntent(CameraUserIntent.SetZoomRatio(ratio))
    fun setHdrPlusEnabled(enabled: Boolean) = processIntent(CameraUserIntent.SetHdrPlusEnabled(enabled))
    fun setBurstActive(active: Boolean) = processIntent(CameraUserIntent.SetBurstActive(active))
    fun setTaskCounts(totalCount: Int, foregroundCount: Int) = processIntent(CameraUserIntent.UpdateTaskCounts(totalCount, foregroundCount))
    fun setHalfFrameStep(step: Int) = processIntent(CameraUserIntent.SetHalfFrameStep(step))
    fun updateThumbnail(uri: Uri?, path: String?) = processIntent(CameraUserIntent.UpdateThumbnail(uri, path))
    fun setShutterDotRotation(rotation: Float) = processIntent(CameraUserIntent.UpdateShutterDotRotation(rotation))
    fun setSwitchingLens(switching: Boolean) = processIntent(CameraUserIntent.SetSwitchingLens(switching))
    fun setUiTier(tier: CameraUiTier) = processIntent(CameraUserIntent.SetUiTier(tier))
    fun cycleUiTier() = processIntent(CameraUserIntent.CycleUiTier)
    fun updateColorRecipe(logName: String, lutName: String) = processIntent(CameraUserIntent.UpdateColorRecipe(logName, lutName))
    fun setMotionPhotoEnabled(enabled: Boolean) = processIntent(CameraUserIntent.SetMotionPhotoEnabled(enabled))
    fun setFlashEnabled(enabled: Boolean) = processIntent(CameraUserIntent.SetFlashEnabled(enabled))
    fun setUnderexposureMode(mode: String) = processIntent(CameraUserIntent.SetUnderexposureMode(mode))
    fun setActiveManualTab(tab: String?) = processIntent(CameraUserIntent.SetActiveManualTab(tab))
    fun updateManualFlags(
        isManualFocus: Boolean,
        isManualIso: Boolean,
        isManualShutter: Boolean,
        isProLinked: Boolean = true
    ) = processIntent(
        CameraUserIntent.UpdateManualFlags(
            isManualFocus = isManualFocus,
            isManualIso = isManualIso,
            isManualShutter = isManualShutter,
            isProLinked = isProLinked
        )
    )
}
