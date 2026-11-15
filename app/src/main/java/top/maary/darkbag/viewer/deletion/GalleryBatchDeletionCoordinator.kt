package top.maary.darkbag.viewer.deletion

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.activity.result.IntentSenderRequest
import androidx.fragment.app.FragmentManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.maary.darkbag.fragments.DarkbagBatchDeleteSheet
import top.maary.darkbag.fragments.GallerySelectedItem
import top.maary.darkbag.utils.MediaStoreUtils

/**
 * 画廊与查看器共享的 MediaStore/SAF 批量删除协调器 (GalleryBatchDeletionCoordinator).
 * 统一收口批量删除目标 URI 解析、IO 协程安全删除以及 Android 11+ MediaStore.createDeleteRequest 授权封装，
 * 消除 Fragment 层对 MediaStore 的直接依赖。
 */
class GalleryBatchDeletionCoordinator(
    private val context: Context,
    private val scope: CoroutineScope,
    private val launchDeleteRequest: (IntentSenderRequest) -> Unit,
    private val onBeforeDelete: (selectedCount: Int) -> Unit,
    private val onClearSelection: () -> Unit,
    private val onAfterDeleteComplete: () -> Unit
) {

    data class BatchDeleteDialogOptions(
        val hasAnyRaw: Boolean,
        val hasAnyDerivatives: Boolean,
        val hasAnyCinemaDng: Boolean
    )

    data class BatchDeleteOutcome(
        val attemptedCount: Int,
        val deleteRequest: IntentSenderRequest?,
        val promptCreationFailed: Boolean = false
    )

    fun showBatchDeleteDialog(
        selectedItems: List<GallerySelectedItem>,
        fragmentManager: FragmentManager
    ) {
        if (selectedItems.isEmpty()) return
        val options = computeDialogOptions(selectedItems)
        val sheet = DarkbagBatchDeleteSheet.newInstance(
            selectedCount = selectedItems.size,
            hasRaw = options.hasAnyRaw,
            hasDerivatives = options.hasAnyDerivatives,
            hasCinemaDng = options.hasAnyCinemaDng
        )
        sheet.onDeleteConfirmed = { deleteMode ->
            deleteBatchImages(selectedItems, deleteMode)
        }
        sheet.show(fragmentManager, DarkbagBatchDeleteSheet.TAG)
    }

    fun deleteBatchImages(selectedItems: List<GallerySelectedItem>, deleteMode: Int) {
        if (selectedItems.isEmpty()) return
        onBeforeDelete(selectedItems.size)

        scope.launch {
            val urisToDelete = resolveBatchDeleteUris(selectedItems, deleteMode)
            val outcome = executeUriBatchDeletion(context, urisToDelete)

            onClearSelection()

            if (outcome.deleteRequest != null) {
                try {
                    launchDeleteRequest(outcome.deleteRequest)
                    return@launch
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to launch MediaStore batch delete request", e)
                }
            }

            onAfterDeleteComplete()
        }
    }

    companion object {
        private const val TAG = "GalleryBatchDeletion"

        fun computeDialogOptions(selectedItems: List<GallerySelectedItem>): BatchDeleteDialogOptions {
            val hasAnyRaw = selectedItems.any { item ->
                if (item.specificLens != null) {
                    item.specificLens.dngUri != null
                } else {
                    item.group.hasMasterRaw
                }
            }
            val hasAnyDerivatives = selectedItems.any { item ->
                if (item.specificLens != null) {
                    item.specificLens.jpgUri != null
                } else {
                    item.group.hasDerivatives
                }
            }
            val hasAnyCinemaDng = selectedItems.any { item ->
                val g = item.group
                g.isCinemaDng ||
                    g.cinemaDngFolderUri != null ||
                    g.cinemaDngFirstFrameUri != null ||
                    g.cinemaDngFrameUris.isNotEmpty()
            }
            return BatchDeleteDialogOptions(
                hasAnyRaw = hasAnyRaw,
                hasAnyDerivatives = hasAnyDerivatives,
                hasAnyCinemaDng = hasAnyCinemaDng
            )
        }

        fun resolveBatchDeleteUris(
            selectedItems: List<GallerySelectedItem>,
            deleteMode: Int
        ): List<Uri> {
            val urisToDelete = mutableListOf<Uri>()
            for (item in selectedItems) {
                val group = item.group
                val lens = item.specificLens
                if (lens != null) {
                    when (deleteMode) {
                        DarkbagBatchDeleteSheet.MODE_ENTIRE_GROUP -> {
                            lens.jpgUri?.let { urisToDelete.add(it) }
                            lens.dngUri?.let { urisToDelete.add(it) }
                        }
                        DarkbagBatchDeleteSheet.MODE_RAW_ONLY -> {
                            lens.dngUri?.let { urisToDelete.add(it) }
                        }
                        DarkbagBatchDeleteSheet.MODE_DERIVATIVES_ONLY -> {
                            lens.jpgUri?.let { urisToDelete.add(it) }
                        }
                        DarkbagBatchDeleteSheet.MODE_CINEMADNG_ONLY -> Unit
                    }
                } else {
                    when (deleteMode) {
                        DarkbagBatchDeleteSheet.MODE_ENTIRE_GROUP -> {
                            group.cinemaDngFolderUri?.let { urisToDelete.add(it) }
                            urisToDelete.addAll(group.allUris)
                        }
                        DarkbagBatchDeleteSheet.MODE_RAW_ONLY -> {
                            group.cinemaDngFolderUri?.let { urisToDelete.add(it) }
                            urisToDelete.addAll(group.allMasterRawUris)
                        }
                        DarkbagBatchDeleteSheet.MODE_DERIVATIVES_ONLY -> {
                            urisToDelete.addAll(group.allDerivativeUris)
                        }
                        DarkbagBatchDeleteSheet.MODE_CINEMADNG_ONLY -> {
                            group.cinemaDngFolderUri?.let { urisToDelete.add(it) }
                            urisToDelete.addAll(group.allCinemaDngUris)
                        }
                    }
                }
            }
            return urisToDelete.distinct()
        }

        suspend fun executeUriBatchDeletion(
            context: Context,
            uris: List<Uri>
        ): BatchDeleteOutcome {
            val distinctUris = uris.distinct()
            val securityExceptionUris = mutableListOf<Uri>()

            withContext(Dispatchers.IO) {
                for (uri in distinctUris) {
                    try {
                        MediaStoreUtils.deleteUriSafe(context, uri)
                    } catch (_: SecurityException) {
                        if (uri.scheme == "content" && uri.authority == MediaStore.AUTHORITY) {
                            securityExceptionUris.add(uri)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to delete $uri", e)
                    }
                }
            }

            if (securityExceptionUris.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                return try {
                    val pendingIntent = MediaStore.createDeleteRequest(
                        context.contentResolver,
                        securityExceptionUris
                    )
                    BatchDeleteOutcome(
                        attemptedCount = distinctUris.size,
                        deleteRequest = IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to request batch deletion via MediaStore", e)
                    BatchDeleteOutcome(
                        attemptedCount = distinctUris.size,
                        deleteRequest = null,
                        promptCreationFailed = true
                    )
                }
            }

            return BatchDeleteOutcome(
                attemptedCount = distinctUris.size,
                deleteRequest = null
            )
        }
    }
}
