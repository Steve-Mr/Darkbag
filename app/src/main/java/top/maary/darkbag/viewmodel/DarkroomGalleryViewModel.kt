package top.maary.darkbag.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import top.maary.darkbag.fragments.GallerySelectedItem
import top.maary.darkbag.models.ImageGroup
import top.maary.darkbag.models.MultiCameraLensItem
import top.maary.darkbag.repository.ImageRepository
import top.maary.darkbag.ui.bento.BentoContainerRole
import top.maary.darkbag.utils.GalleryLayoutState

/**
 * 暗房画廊不可变 UI 状态.
 */
data class DarkroomGalleryUiState(
    val items: List<ImageGroup> = emptyList(),
    val isSelectionMode: Boolean = false,
    val selectedKeys: Set<String> = emptySet(),
    val columnCount: Int = 3,
    val isWaterfallMode: Boolean = false,
    val containerRole: BentoContainerRole = BentoContainerRole.SURFACE_CONTAINER,
    val isLoading: Boolean = false,
    val isDeleting: Boolean = false,
    val deletingCount: Int = 0
) {
    val selectedCount: Int
        get() = selectedKeys.size

    val totalSelectableCount: Int
        get() = items.sumOf { if (it.isMultiCamera && it.multiCameraLenses.isNotEmpty()) it.multiCameraLenses.size else 1 }

    val isAllSelected: Boolean
        get() = selectedCount > 0 && selectedCount >= totalSelectableCount

    fun getSelectedItems(): List<GallerySelectedItem> {
        val result = mutableListOf<GallerySelectedItem>()
        for (group in items) {
            if (group.isMultiCamera && group.multiCameraLenses.isNotEmpty()) {
                for (lens in group.multiCameraLenses) {
                    val key = "${group.baseName}#${lens.lensTag}"
                    if (selectedKeys.contains(key)) {
                        result.add(GallerySelectedItem(group, lens))
                    }
                }
            } else {
                if (selectedKeys.contains(group.baseName)) {
                    result.add(GallerySelectedItem(group, null))
                }
            }
        }
        return result
    }
}

/**
 * 暗房画廊一次性单向副作用.
 */
sealed class DarkroomGalleryEffect {
    data class ShowToast(val message: String) : DarkroomGalleryEffect()
    data class ShowToastRes(val resId: Int, val formatArgs: List<Any> = emptyList()) : DarkroomGalleryEffect()
    data class ScrollToPosition(val position: Int) : DarkroomGalleryEffect()
}

/**
 * 暗房画廊用户交互意图 (MVI User Intent).
 */
sealed class DarkroomGalleryUserIntent {
    data class LoadImages(val targetUri: String? = null) : DarkroomGalleryUserIntent()
    data class ToggleSingleSelection(val group: ImageGroup) : DarkroomGalleryUserIntent()
    data class ToggleLensSelection(val group: ImageGroup, val lens: MultiCameraLensItem) : DarkroomGalleryUserIntent()
    object ToggleSelectAll : DarkroomGalleryUserIntent()
    object ClearSelection : DarkroomGalleryUserIntent()
    data class SetSelectionMode(val enabled: Boolean) : DarkroomGalleryUserIntent()
    data class CycleLayout(val isLandscape: Boolean) : DarkroomGalleryUserIntent()
    data class SetDeleting(val isDeleting: Boolean, val count: Int = 0) : DarkroomGalleryUserIntent()
    data class SetLoading(val isLoading: Boolean) : DarkroomGalleryUserIntent()
}

/**
 * 暗房画廊视图模型 (MVI 状态机).
 * 严格遵从 Invariant 4，管理暗房画廊的单向数据流与选择集状态机。
 * 使用 @JvmOverloads constructor 保证 AndroidViewModelFactory 反射可见，彻底杜绝反射实例化闪退。
 */
class DarkroomGalleryViewModel @JvmOverloads constructor(
    application: Application,
    private val repository: ImageRepository = ImageRepository(application)
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(DarkroomGalleryUiState())
    val uiState = _uiState.asStateFlow()

    private val _effects = Channel<DarkroomGalleryEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    private var loadJob: Job? = null

    fun processIntent(intent: DarkroomGalleryUserIntent) {
        when (intent) {
            is DarkroomGalleryUserIntent.LoadImages -> loadImages(intent.targetUri)
            is DarkroomGalleryUserIntent.ToggleSingleSelection -> toggleSingleSelection(intent.group)
            is DarkroomGalleryUserIntent.ToggleLensSelection -> toggleLensSelection(intent.group, intent.lens)
            is DarkroomGalleryUserIntent.ToggleSelectAll -> toggleSelectAll()
            is DarkroomGalleryUserIntent.ClearSelection -> clearSelection()
            is DarkroomGalleryUserIntent.SetSelectionMode -> setSelectionMode(intent.enabled)
            is DarkroomGalleryUserIntent.CycleLayout -> cycleLayout(intent.isLandscape)
            is DarkroomGalleryUserIntent.SetDeleting -> setDeleting(intent.isDeleting, intent.count)
            is DarkroomGalleryUserIntent.SetLoading -> setLoading(intent.isLoading)
        }
    }

    fun loadImages(targetUri: String? = null) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            repository.getGroupedImagesFlow(targetUri).collect { groups ->
                _uiState.update { state ->
                    val cleanSelectedKeys = state.selectedKeys.filter { key ->
                        groups.any { group ->
                            if (group.isMultiCamera && group.multiCameraLenses.isNotEmpty()) {
                                group.multiCameraLenses.any { lens -> "${group.baseName}#${lens.lensTag}" == key }
                            } else {
                                group.baseName == key
                            }
                        }
                    }.toSet()

                    val isSelection = cleanSelectedKeys.isNotEmpty() || state.isSelectionMode
                    state.copy(
                        items = groups,
                        selectedKeys = cleanSelectedKeys,
                        isSelectionMode = isSelection,
                        isLoading = false,
                        containerRole = if (isSelection && cleanSelectedKeys.isNotEmpty()) {
                            BentoContainerRole.PRIMARY_CONTAINER
                        } else {
                            BentoContainerRole.SURFACE_CONTAINER
                        }
                    )
                }
            }
        }
    }

    fun toggleSingleSelection(group: ImageGroup) {
        _uiState.update { state ->
            val newSelected = state.selectedKeys.toMutableSet()
            val key = group.baseName
            if (newSelected.contains(key)) {
                newSelected.remove(key)
            } else {
                newSelected.add(key)
            }

            val isSelection = newSelected.isNotEmpty()
            state.copy(
                selectedKeys = newSelected,
                isSelectionMode = isSelection,
                containerRole = if (isSelection) BentoContainerRole.PRIMARY_CONTAINER else BentoContainerRole.SURFACE_CONTAINER
            )
        }
    }

    fun toggleLensSelection(group: ImageGroup, lens: MultiCameraLensItem) {
        _uiState.update { state ->
            val newSelected = state.selectedKeys.toMutableSet()
            val key = "${group.baseName}#${lens.lensTag}"
            if (newSelected.contains(key)) {
                newSelected.remove(key)
            } else {
                newSelected.add(key)
            }

            val isSelection = newSelected.isNotEmpty()
            state.copy(
                selectedKeys = newSelected,
                isSelectionMode = isSelection,
                containerRole = if (isSelection) BentoContainerRole.PRIMARY_CONTAINER else BentoContainerRole.SURFACE_CONTAINER
            )
        }
    }

    fun toggleSelectAll() {
        _uiState.update { state ->
            if (state.isAllSelected) {
                state.copy(
                    selectedKeys = emptySet(),
                    isSelectionMode = false,
                    containerRole = BentoContainerRole.SURFACE_CONTAINER
                )
            } else {
                val allKeys = mutableSetOf<String>()
                for (group in state.items) {
                    if (group.isMultiCamera && group.multiCameraLenses.isNotEmpty()) {
                        for (lens in group.multiCameraLenses) {
                            allKeys.add("${group.baseName}#${lens.lensTag}")
                        }
                    } else {
                        allKeys.add(group.baseName)
                    }
                }
                state.copy(
                    selectedKeys = allKeys,
                    isSelectionMode = true,
                    containerRole = BentoContainerRole.PRIMARY_CONTAINER
                )
            }
        }
    }

    fun clearSelection() {
        _uiState.update { state ->
            state.copy(
                selectedKeys = emptySet(),
                isSelectionMode = false,
                containerRole = BentoContainerRole.SURFACE_CONTAINER
            )
        }
    }

    fun setSelectionMode(enabled: Boolean) {
        _uiState.update { state ->
            if (!enabled) {
                state.copy(
                    selectedKeys = emptySet(),
                    isSelectionMode = false,
                    containerRole = BentoContainerRole.SURFACE_CONTAINER
                )
            } else {
                state.copy(
                    isSelectionMode = true,
                    containerRole = if (state.selectedKeys.isNotEmpty()) BentoContainerRole.PRIMARY_CONTAINER else BentoContainerRole.SURFACE_CONTAINER
                )
            }
        }
    }

    fun cycleLayout(isLandscape: Boolean) {
        _uiState.update { state ->
            val currentLayout = GalleryLayoutState(state.columnCount, state.isWaterfallMode)
            val nextLayout = currentLayout.cycle(isLandscape)
            state.copy(
                columnCount = nextLayout.columnCount,
                isWaterfallMode = nextLayout.isWaterfallMode
            )
        }
    }

    fun setDeleting(isDeleting: Boolean, count: Int = 0) {
        _uiState.update { it.copy(isDeleting = isDeleting, deletingCount = count) }
    }

    fun setLoading(isLoading: Boolean) {
        _uiState.update { it.copy(isLoading = isLoading) }
    }
}
