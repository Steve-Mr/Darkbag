package top.maary.darkbag.viewer.deletion

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.fragment.app.FragmentManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.maary.darkbag.R
import top.maary.darkbag.fragments.DarkbagBatchDeleteSheet
import top.maary.darkbag.fragments.ImageViewerAdapter
import top.maary.darkbag.models.ImageGroup
import top.maary.darkbag.repository.ImageRepository
import top.maary.darkbag.viewer.strategy.ViewerMediaStrategy

/**
 * 查看器媒体删除控制器 (ViewerDeletionController).
 * 封装单组/多衍生版本删除判断、DarkbagBatchDeleteSheet 调度、SAF/MediaStore 安全删除与跨版本权限请求。
 * 遵循 Invariant 1 (CaptureSink & 媒体策略解耦) 与 Invariant 4 (零 UI 阻塞与协程安全删除)。
 */
class ViewerDeletionController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val repository: ImageRepository,
    private val mediaStrategy: ViewerMediaStrategy,
    private val launchDeleteRequest: (IntentSenderRequest) -> Unit,
    private val onNavigateUp: () -> Unit,
    private val onReloadImages: (nextTargetUri: String?) -> Unit,
    private val onUpdateAdapterGroups: (List<ImageGroup>) -> Unit,
    private val getAdapterItemCount: () -> Int,
    private val getAdapterGroupAt: (Int) -> ImageGroup?,
    private val getCurrentItemIndex: () -> Int
) {

    var pendingSingleDeleteGroup: ImageGroup? = null
    var pendingDeleteNextTargetUri: String? = null

    suspend fun deleteUriSafe(context: Context, uri: Uri): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                if (uri.scheme == "content" && DocumentsContract.isDocumentUri(context, uri)) {
                    DocumentsContract.deleteDocument(context.contentResolver, uri)
                } else if (uri.scheme == "content" && DocumentsContract.isTreeUri(uri)) {
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
                    DocumentsContract.deleteDocument(context.contentResolver, docUri)
                } else {
                    context.contentResolver.delete(uri, null, null) > 0
                }
            } catch (e: SecurityException) {
                throw e
            } catch (e: Exception) {
                Log.e("ViewerDeletionController", "Failed to delete uri $uri", e)
                false
            }
        }
    }

    /**
     * 显示单组删除提示弹窗或唤起 DarkbagBatchDeleteSheet。
     */
    fun showDeleteDialog(
        group: ImageGroup,
        derivatives: List<Uri>,
        activeDerivIdx: Int,
        fragmentManager: FragmentManager
    ) {
        val hasCdng = group.isCinemaDng || group.cinemaDngFolderUri != null || group.cinemaDngFirstFrameUri != null || group.cinemaDngFrameUris.isNotEmpty()
        val hasRawVid = group.isRawVideo || group.rawVideoUri != null
        val hasDerivatives = derivatives.isNotEmpty()
        val hasPhotoDng = group.dngUri != null || group.dngUri1 != null || group.dngUri2 != null || group.multiDngUris.isNotEmpty()
        val hasRaw = hasPhotoDng || hasRawVid

        val currentVersionName = if (derivatives.size >= 2) "V${activeDerivIdx + 1}" else null
        val derivativeCount = derivatives.size

        val componentCount = (if (hasRaw) 1 else 0) + (if (hasDerivatives) 1 else 0) + (if (hasCdng) 1 else 0)
        val hasMultipleComponents = componentCount > 1 || (derivatives.size >= 2)

        if (hasMultipleComponents) {
            pendingSingleDeleteGroup = group
            DarkbagBatchDeleteSheet.newInstance(
                selectedCount = 1,
                hasRaw = hasRaw,
                hasDerivatives = hasDerivatives,
                hasCinemaDng = hasCdng,
                currentVersionName = currentVersionName,
                derivativeCount = derivativeCount
            ).show(fragmentManager, DarkbagBatchDeleteSheet.TAG)
        } else {
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.delete_image_title)
                .setMessage(R.string.delete_image_message)
                .setPositiveButton(R.string.delete_button_alt) { _, _ ->
                    deleteImage(group, group.allUris, true)
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    /**
     * 执行单组根据模式删除。
     */
    fun executeSingleGroupDelete(
        group: ImageGroup,
        deleteMode: Int,
        derivatives: List<Uri>,
        activeDerivIdx: Int
    ) {
        val urisToDelete = mutableListOf<Uri>()
        var isEntireGroup = false
        when (deleteMode) {
            DarkbagBatchDeleteSheet.MODE_CURRENT_DERIVATIVE_ONLY -> {
                val activeUri = derivatives.getOrNull(activeDerivIdx) ?: group.jpgUri ?: group.mp4VideoUri
                activeUri?.let { urisToDelete.add(it) }
                val remainingUris = group.allUris.filter { !urisToDelete.contains(it) }
                isEntireGroup = remainingUris.isEmpty()
            }
            DarkbagBatchDeleteSheet.MODE_ENTIRE_GROUP -> {
                group.cinemaDngFolderUri?.let { urisToDelete.add(it) }
                urisToDelete.addAll(group.allUris)
                isEntireGroup = true
            }
            DarkbagBatchDeleteSheet.MODE_RAW_ONLY -> {
                if (group.isCinemaDng || group.cinemaDngFolderUri != null) {
                    group.cinemaDngFolderUri?.let { urisToDelete.add(it) }
                }
                urisToDelete.addAll(group.allMasterRawUris)
                val remainingUris = group.allUris.filter { !urisToDelete.contains(it) }
                isEntireGroup = remainingUris.isEmpty()
            }
            DarkbagBatchDeleteSheet.MODE_DERIVATIVES_ONLY -> {
                urisToDelete.addAll(group.allDerivativeUris)
                val remainingUris = group.allUris.filter { !urisToDelete.contains(it) }
                isEntireGroup = remainingUris.isEmpty()
            }
            DarkbagBatchDeleteSheet.MODE_CINEMADNG_ONLY -> {
                group.cinemaDngFolderUri?.let { urisToDelete.add(it) }
                urisToDelete.addAll(group.allCinemaDngUris)
                val remainingUris = group.allUris.filter { !urisToDelete.contains(it) }
                isEntireGroup = remainingUris.isEmpty()
            }
        }
        deleteImage(group, urisToDelete, isEntireGroup)
    }

    /**
     * 核心删除分发逻辑。
     */
    fun deleteImage(
        group: ImageGroup,
        targetUris: List<Uri>?,
        deleteGroup: Boolean,
        selectedFormat: String = ImageViewerAdapter.FORMAT_JPG,
        fallbackActiveDerivativeUri: Uri? = null
    ) {
        if (mediaStrategy.isSandbox) {
            scope.launch {
                val success = mediaStrategy.deleteImage(context, group, targetUris, deleteGroup, repository)
                if (success) {
                    if (deleteGroup) {
                        val currentList = repository.getGroupedImages(forceRefresh = true).toMutableList()
                        currentList.removeAll { it.baseName == group.baseName }
                        if (currentList.isEmpty()) {
                            onNavigateUp()
                        } else {
                            onUpdateAdapterGroups(currentList.toList())
                        }
                    } else {
                        val updatedGroups = mediaStrategy.loadInitialGroups(context.applicationContext, null, repository)
                        if (updatedGroups != null) {
                            onUpdateAdapterGroups(updatedGroups)
                        }
                    }
                }
            }
            return
        }

        scope.launch {
            var nextTargetUri: String? = null
            val currentIndex = getCurrentItemIndex()
            val urisToDelete = mutableListOf<Uri>()

            if (targetUris != null) {
                urisToDelete.addAll(targetUris)
            } else if (deleteGroup) {
                urisToDelete.addAll(group.allUris)
            } else {
                val isCdng = selectedFormat == "CDNG" || ((selectedFormat == ImageViewerAdapter.FORMAT_DNG || selectedFormat == "RAW") && (group.isCinemaDng || group.cinemaDngFolderUri != null || group.cinemaDngFirstFrameUri != null || group.cinemaDngFrameUris.isNotEmpty()))
                val isRawVid = selectedFormat == "RAWVID" || ((selectedFormat == ImageViewerAdapter.FORMAT_DNG || selectedFormat == "RAW") && (group.isRawVideo || group.rawVideoUri != null))
                val isPhotoDng = selectedFormat == ImageViewerAdapter.FORMAT_DNG || selectedFormat == "DNG" || selectedFormat == "RAW"

                when {
                    isCdng -> urisToDelete.addAll(group.allCinemaDngUris)
                    isRawVid -> urisToDelete.addAll(group.rawVideoMasterUris)
                    isPhotoDng -> urisToDelete.addAll(group.photoMasterUris)
                    else -> fallbackActiveDerivativeUri?.let { urisToDelete.add(it) }
                }
            }

            if (urisToDelete.any { group.allCinemaDngUris.contains(it) } && group.cinemaDngFolderUri != null) {
                if (!urisToDelete.contains(group.cinemaDngFolderUri)) {
                    group.cinemaDngFolderUri?.let { urisToDelete.add(it) }
                }
            }

            val itemCount = getAdapterItemCount()
            if (deleteGroup && itemCount > 1) {
                val nextIndex = if (currentIndex < itemCount - 1) currentIndex + 1 else currentIndex - 1
                val nextGroup = getAdapterGroupAt(nextIndex)
                nextTargetUri = nextGroup?.firstAvailableUri?.toString()
            }

            val securityExceptionUris = mutableListOf<Uri>()
            for (uri in urisToDelete.distinct()) {
                try {
                    deleteUriSafe(context, uri)
                } catch (e: SecurityException) {
                    if (uri.scheme == "content" && uri.authority == MediaStore.AUTHORITY) {
                        securityExceptionUris.add(uri)
                    }
                } catch (e: Exception) {
                    Log.e("ViewerDeletionController", "Failed to delete $uri", e)
                }
            }

            if (!deleteGroup) {
                repository.invalidateCache(clearStale = true)
                val remainingGroup = repository.getGroupedImages(forceRefresh = true).find { it.baseName == group.baseName }
                nextTargetUri = if (remainingGroup != null && remainingGroup.hasAny()) {
                    remainingGroup.firstAvailableUri?.toString()
                } else {
                    if (itemCount > 1) {
                        val nextIndex = if (currentIndex < itemCount - 1) currentIndex + 1 else currentIndex - 1
                        getAdapterGroupAt(nextIndex)?.firstAvailableUri?.toString()
                    } else null
                }
            }

            if (securityExceptionUris.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                pendingDeleteNextTargetUri = nextTargetUri
                try {
                    val deleteRequest = MediaStore.createDeleteRequest(context.contentResolver, securityExceptionUris)
                    launchDeleteRequest(IntentSenderRequest.Builder(deleteRequest.intentSender).build())
                } catch (e: Exception) {
                    Toast.makeText(context, R.string.gallery_delete_prompt_failed, Toast.LENGTH_SHORT).show()
                    repository.invalidateCache(clearStale = true)
                    onReloadImages(nextTargetUri)
                }
            } else {
                repository.invalidateCache(clearStale = true)
                onReloadImages(nextTargetUri)
            }
        }
    }

    /**
     * 处理 MediaStore IntentSender 删除回调。
     */
    fun handleDeleteResult(resultCode: Int) {
        val targetUri = pendingDeleteNextTargetUri
        pendingDeleteNextTargetUri = null
        if (resultCode == android.app.Activity.RESULT_OK) {
            repository.invalidateCache(clearStale = true)
            onReloadImages(targetUri)
        } else {
            Toast.makeText(context, R.string.gallery_delete_prompt_failed, Toast.LENGTH_SHORT).show()
            repository.invalidateCache(clearStale = true)
            onReloadImages(targetUri)
        }
    }
}
