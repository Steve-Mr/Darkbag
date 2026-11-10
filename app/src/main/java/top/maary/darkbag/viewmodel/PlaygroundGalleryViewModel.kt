package top.maary.darkbag.viewmodel

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import top.maary.darkbag.R
import top.maary.darkbag.models.PlaygroundItem
import top.maary.darkbag.repository.PlaygroundRepository
import top.maary.darkbag.ui.bento.BentoContainerRole
import java.io.File

/**
 * 工坊画廊不可变 UI 状态.
 */
data class PlaygroundGalleryUiState(
    val items: List<PlaygroundItem> = emptyList(),
    val isSelectionMode: Boolean = false,
    val selectedFiles: Set<File> = emptySet(),
    val expandedGroupPaths: Set<String> = emptySet(),
    val isLoading: Boolean = false,
    val isDeleting: Boolean = false,
    val isExporting: Boolean = false,
    val columnCount: Int = 2,
    val isWaterfallMode: Boolean = true,
    val containerRole: BentoContainerRole = BentoContainerRole.SURFACE_CONTAINER
)

/**
 * 工坊画廊一次性单向副作用.
 */
sealed class PlaygroundEffect {
    data class ShowToast(val message: String) : PlaygroundEffect()
    data class ShowToastRes(val resId: Int, val formatArgs: List<Any> = emptyList()) : PlaygroundEffect()
    data class OpenViewer(val paths: List<String>, val hfLayout: String?) : PlaygroundEffect()
    object RequestRefresh : PlaygroundEffect()
}

/**
 * 工坊画廊用户交互意图 (MVI User Intent).
 */
sealed class PlaygroundUserIntent {
    data class LoadFiles(val forceRefresh: Boolean = false) : PlaygroundUserIntent()
    data class ToggleSelection(val file: File) : PlaygroundUserIntent()
    object ToggleSelectAll : PlaygroundUserIntent()
    object ClearSelection : PlaygroundUserIntent()
    data class ToggleGroupExpansion(val group: PlaygroundItem.Group) : PlaygroundUserIntent()
    object ToggleLayout : PlaygroundUserIntent()
    data class ImportDngs(val uris: List<Uri>, val contentResolver: ContentResolver) : PlaygroundUserIntent()
    object DeleteSelected : PlaygroundUserIntent()
    object DisbandSelected : PlaygroundUserIntent()
    object ExportSelected : PlaygroundUserIntent()
}

/**
 * 工坊画廊视图模型 (MVI 状态机).
 * 严格遵从 Invariant 4，管理工坊画廊的单向数据流、选择集状态机与 IO 仓储交互。
 */
class PlaygroundGalleryViewModel @JvmOverloads constructor(
    application: Application,
    private val repository: PlaygroundRepository = PlaygroundRepository(application)
) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences(
        top.maary.darkbag.fragments.SettingsFragment.PREFS_NAME,
        android.content.Context.MODE_PRIVATE
    )

    private val initialCols = prefs.getInt(top.maary.darkbag.fragments.SettingsFragment.KEY_PLAYGROUND_COLUMNS, 2)

    private val _uiState = MutableStateFlow(PlaygroundGalleryUiState(columnCount = initialCols))
    val uiState = _uiState.asStateFlow()

    private val _effects = Channel<PlaygroundEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    init {
        loadFiles()
    }

    fun processIntent(intent: PlaygroundUserIntent) {
        when (intent) {
            is PlaygroundUserIntent.LoadFiles -> loadFiles(intent.forceRefresh)
            is PlaygroundUserIntent.ToggleSelection -> toggleSelection(intent.file)
            is PlaygroundUserIntent.ToggleSelectAll -> toggleSelectAll()
            is PlaygroundUserIntent.ClearSelection -> clearSelection()
            is PlaygroundUserIntent.ToggleGroupExpansion -> toggleGroupExpansion(intent.group)
            is PlaygroundUserIntent.ToggleLayout -> toggleLayout()
            is PlaygroundUserIntent.ImportDngs -> importDngs(intent.uris, intent.contentResolver)
            is PlaygroundUserIntent.DeleteSelected -> deleteSelected()
            is PlaygroundUserIntent.DisbandSelected -> disbandSelected()
            is PlaygroundUserIntent.ExportSelected -> exportSelected()
        }
    }

    fun loadFiles(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            repository.getPlaygroundItemsFlow(forceRefresh).collect { freshItems ->
                _uiState.update { state ->
                    val updatedItems = freshItems.map { item ->
                        if (item is PlaygroundItem.Group) {
                            item.copy(isExpanded = state.expandedGroupPaths.contains(item.jpgFile.absolutePath))
                        } else {
                            item
                        }
                    }
                    state.copy(
                        items = updatedItems,
                        isLoading = false
                    )
                }
            }
        }
    }

    fun toggleSelection(file: File) {
        _uiState.update { state ->
            val newSelected = state.selectedFiles.toMutableSet()
            if (newSelected.contains(file)) {
                newSelected.remove(file)
            } else {
                newSelected.add(file)
            }

            val newExpanded = state.expandedGroupPaths.toMutableSet()
            val targetGroup = state.items.filterIsInstance<PlaygroundItem.Group>().find {
                it.jpgFile == file || it.dng1 == file || it.dng2 == file
            }

            if (targetGroup != null) {
                if (file == targetGroup.jpgFile && newSelected.contains(file)) {
                    // 主图被选中时自动展开子项
                    newExpanded.add(targetGroup.jpgFile.absolutePath)
                } else if (!newSelected.contains(targetGroup.jpgFile) &&
                    !newSelected.contains(targetGroup.dng1) &&
                    !newSelected.contains(targetGroup.dng2)) {
                    // 若组内均未被选中，自动收起
                    newExpanded.remove(targetGroup.jpgFile.absolutePath)
                }
            }

            val isSelection = newSelected.isNotEmpty()
            if (!isSelection) {
                newExpanded.clear()
            }

            val updatedItems = state.items.map { item ->
                if (item is PlaygroundItem.Group) {
                    item.copy(isExpanded = newExpanded.contains(item.jpgFile.absolutePath))
                } else {
                    item
                }
            }

            state.copy(
                items = updatedItems,
                selectedFiles = newSelected,
                expandedGroupPaths = newExpanded,
                isSelectionMode = isSelection,
                containerRole = if (isSelection) BentoContainerRole.PRIMARY_CONTAINER else BentoContainerRole.SURFACE_CONTAINER
            )
        }
    }

    fun toggleSelectAll() {
        _uiState.update { state ->
            val allSelectable = mutableListOf<File>()
            for (item in state.items) {
                when (item) {
                    is PlaygroundItem.Single -> allSelectable.add(item.file)
                    is PlaygroundItem.Group -> allSelectable.add(item.jpgFile)
                }
            }

            val isAllSelected = state.selectedFiles.containsAll(allSelectable) && allSelectable.isNotEmpty()
            val newSelected = if (isAllSelected) emptySet() else allSelectable.toSet()
            val isSelection = newSelected.isNotEmpty()
            val newExpanded = if (isSelection) state.expandedGroupPaths else emptySet()

            val updatedItems = state.items.map { item ->
                if (item is PlaygroundItem.Group) {
                    item.copy(isExpanded = newExpanded.contains(item.jpgFile.absolutePath))
                } else {
                    item
                }
            }

            state.copy(
                items = updatedItems,
                selectedFiles = newSelected,
                expandedGroupPaths = newExpanded,
                isSelectionMode = isSelection,
                containerRole = if (isSelection) BentoContainerRole.PRIMARY_CONTAINER else BentoContainerRole.SURFACE_CONTAINER
            )
        }
    }

    fun clearSelection() {
        _uiState.update { state ->
            val updatedItems = state.items.map { item ->
                if (item is PlaygroundItem.Group && item.isExpanded) {
                    item.copy(isExpanded = false)
                } else {
                    item
                }
            }
            state.copy(
                items = updatedItems,
                selectedFiles = emptySet(),
                expandedGroupPaths = emptySet(),
                isSelectionMode = false,
                containerRole = BentoContainerRole.SURFACE_CONTAINER
            )
        }
    }

    fun toggleGroupExpansion(group: PlaygroundItem.Group) {
        _uiState.update { state ->
            val path = group.jpgFile.absolutePath
            val newExpanded = state.expandedGroupPaths.toMutableSet()
            if (newExpanded.contains(path)) {
                newExpanded.remove(path)
            } else {
                newExpanded.add(path)
            }

            val updatedItems = state.items.map { item ->
                if (item is PlaygroundItem.Group && item.jpgFile.absolutePath == path) {
                    item.copy(isExpanded = newExpanded.contains(path))
                } else {
                    item
                }
            }

            state.copy(
                items = updatedItems,
                expandedGroupPaths = newExpanded
            )
        }
    }

    fun toggleLayout() {
        _uiState.update { state ->
            val nextCols = if (state.columnCount == 2) 3 else 2
            prefs.edit().putInt(top.maary.darkbag.fragments.SettingsFragment.KEY_PLAYGROUND_COLUMNS, nextCols).apply()
            state.copy(columnCount = nextCols)
        }
    }

    fun importDngs(uris: List<Uri>, contentResolver: ContentResolver) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val count = repository.importDngs(uris, contentResolver)
            emitEffect(PlaygroundEffect.ShowToastRes(R.string.playground_import_success, listOf(count)))
            loadFiles(forceRefresh = true)
        }
    }

    fun exportSelected() {
        val filesToExport = _uiState.value.selectedFiles.toList()
        if (filesToExport.isEmpty()) {
            emitEffect(PlaygroundEffect.ShowToastRes(R.string.playground_export_hint))
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isExporting = true) }
            emitEffect(PlaygroundEffect.ShowToastRes(R.string.playground_exporting, listOf(filesToExport.size)))
            val successCount = repository.exportFiles(filesToExport, _uiState.value.items)
            emitEffect(PlaygroundEffect.ShowToastRes(R.string.playground_export_success, listOf(successCount)))
            clearSelection()
            _uiState.update { it.copy(isExporting = false) }
        }
    }

    fun deleteSelected() {
        val toDelete = _uiState.value.selectedFiles
        if (toDelete.isEmpty()) return

        viewModelScope.launch {
            _uiState.update { it.copy(isDeleting = true) }
            repository.deleteFiles(toDelete, _uiState.value.items)
            clearSelection()
            loadFiles(forceRefresh = true)
            _uiState.update { it.copy(isDeleting = false) }
        }
    }

    fun disbandSelected() {
        val state = _uiState.value
        val targets = state.selectedFiles.mapNotNull { file ->
            val item = state.items.find { it is PlaygroundItem.Group && it.jpgFile == file }
            (item as? PlaygroundItem.Group)?.jpgFile
        }.toSet()

        if (targets.isEmpty()) return

        viewModelScope.launch {
            repository.disbandGroups(targets)
            clearSelection()
            loadFiles(forceRefresh = true)
        }
    }

    fun emitEffect(effect: PlaygroundEffect) {
        viewModelScope.launch {
            _effects.send(effect)
        }
    }
}
