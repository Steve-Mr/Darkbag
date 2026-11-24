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
    private val onAfterDeleteComplete: () -> Unit,
    private val onDeletePromptFailed: () -> Unit = {}
) {

    data class BatchDeleteDialogOptions(
        val hasAnyRaw: Boolean,
        val hasAnyDerivatives: Boolean,
        val hasAnyCinemaDng: Boolean
    )

    data class BatchDeleteOutcome(
        val attemptedCount: Int,
        val deleteRequest: IntentSenderRequest?,
        val promptCreationFailed: Boolean = false,
        val deletedCount: Int = 0,
        val securityExceptionCount: Int = 0
    )

    fun showBatchDeleteDialog(
        selectedItems: List<GallerySelectedItem>,
        fragmentManager: FragmentManager
    ) {
        if (selectedItems.isEmpty() || fragmentManager.isStateSaved) return
        if (fragmentManager.findFragmentByTag(DarkbagBatchDeleteSheet.TAG) != null) return
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
        val itemLabels = selectedItems.joinToString(",") { item ->
            if (item.specificLens != null) "${item.group.baseName}#${item.specificLens.lensTag}" else item.group.baseName
        }
        Log.d(TAG, "deleteBatchImages start: selectedCount=${selectedItems.size}, mode=$deleteMode, items=[$itemLabels]")
        onBeforeDelete(selectedItems.size)

        scope.launch {
            val urisToDelete = resolveBatchDeleteUris(selectedItems, deleteMode)
            Log.d(TAG, "Resolved ${urisToDelete.size} URI(s) for batch delete: $urisToDelete")
            val outcome = executeUriBatchDeletion(context, urisToDelete)
            Log.d(
                TAG,
                "executeUriBatchDeletion outcome: attempted=${outcome.attemptedCount}, deleted=${outcome.deletedCount}, " +
                    "securityExceptions=${outcome.securityExceptionCount}, hasDeleteRequest=${outcome.deleteRequest != null}, " +
                    "promptCreationFailed=${outcome.promptCreationFailed}"
            )

            onClearSelection()

            if (outcome.deleteRequest != null) {
                try {
                    launchDeleteRequest(outcome.deleteRequest)
                    return@launch
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to launch MediaStore batch delete request", e)
                    onDeletePromptFailed()
                }
            } else if (outcome.promptCreationFailed) {
                onDeletePromptFailed()
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
                if (item.specificLens != null) {
                    false
                } else {
                    val g = item.group
                    g.isCinemaDng ||
                        g.cinemaDngFolderUri != null ||
                        g.cinemaDngFirstFrameUri != null ||
                        g.cinemaDngFrameUris.isNotEmpty()
                }
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
            uris: List<Uri>,
            createDeleteIntentSender: (Context, List<Uri>) -> IntentSenderRequest = { ctx, targetUris ->
                val pendingIntent = MediaStore.createDeleteRequest(ctx.contentResolver, targetUris)
                IntentSenderRequest.Builder(pendingIntent.intentSender).build()
            }
        ): BatchDeleteOutcome {
            val distinctUris = uris.distinct()
            val securityExceptionUris = mutableListOf<Uri>()
            var deletedCount = 0

            withContext(Dispatchers.IO) {
                for (uri in distinctUris) {
                    try {
                        val deleted = MediaStoreUtils.deleteUriSafe(context, uri)
                        if (deleted) {
                            deletedCount++
                        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                            uri.scheme == "content" &&
                            uri.authority == MediaStore.AUTHORITY
                        ) {
                            val stillExists = try {
                                context.contentResolver.query(
                                    uri,
                                    arrayOf(MediaStore.MediaColumns._ID),
                                    null,
                                    null,
                                    null
                                )?.use { it.moveToFirst() } == true
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to verify existence of $uri after delete returned false", e)
                                false
                            }
                            if (stillExists) {
                                Log.w(TAG, "MediaStore delete returned false while URI still exists, queuing for createDeleteRequest: $uri")
                                securityExceptionUris.add(uri)
                            }
                        }
                    } catch (e: SecurityException) {
                        Log.w(TAG, "SecurityException deleting $uri (${e::class.java.simpleName}: ${e.message})")
                        if (uri.scheme == "content" && uri.authority == MediaStore.AUTHORITY) {
                            securityExceptionUris.add(uri)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to delete $uri", e)
                    }
                }
            }

            if (securityExceptionUris.isNotEmpty()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    return createMediaStoreDeleteOutcome(
                        context = context,
                        attemptedCount = distinctUris.size,
                        deletedCount = deletedCount,
                        securityExceptionUris = securityExceptionUris,
                        createDeleteIntentSender = createDeleteIntentSender
                    )
                }
                Log.w(
                    TAG,
                    "Batch delete prompt requires API 30+, cannot prompt for $securityExceptionUris on API ${Build.VERSION.SDK_INT}"
                )
                return BatchDeleteOutcome(
                    attemptedCount = distinctUris.size,
                    deleteRequest = null,
                    promptCreationFailed = true,
                    deletedCount = deletedCount,
                    securityExceptionCount = securityExceptionUris.size
                )
            }

            return BatchDeleteOutcome(
                attemptedCount = distinctUris.size,
                deleteRequest = null,
                deletedCount = deletedCount,
                securityExceptionCount = securityExceptionUris.size
            )
        }

        private fun createMediaStoreDeleteOutcome(
            context: Context,
            attemptedCount: Int,
            deletedCount: Int,
            securityExceptionUris: List<Uri>,
            createDeleteIntentSender: (Context, List<Uri>) -> IntentSenderRequest
        ): BatchDeleteOutcome {
            return try {
                BatchDeleteOutcome(
                    attemptedCount = attemptedCount,
                    deleteRequest = createDeleteIntentSender(context, securityExceptionUris),
                    deletedCount = deletedCount,
                    securityExceptionCount = securityExceptionUris.size
                )
            } catch (e: IllegalArgumentException) {
                val standardMediaUris = securityExceptionUris.filter { uri ->
                    val path = uri.path.orEmpty()
                    path.contains("/images/") || path.contains("/video/") || path.contains("/audio/")
                }
                if (standardMediaUris.isNotEmpty() && standardMediaUris.size < securityExceptionUris.size) {
                    val skipped = securityExceptionUris - standardMediaUris.toSet()
                    Log.w(TAG, "createDeleteRequest rejected non-standard media URIs $skipped; retrying with $standardMediaUris", e)
                    try {
                        return BatchDeleteOutcome(
                            attemptedCount = attemptedCount,
                            deleteRequest = createDeleteIntentSender(context, standardMediaUris),
                            deletedCount = deletedCount,
                            securityExceptionCount = securityExceptionUris.size
                        )
                    } catch (retryEx: Exception) {
                        Log.e(TAG, "Retry MediaStore.createDeleteRequest failed for $standardMediaUris", retryEx)
                    }
                } else {
                    Log.e(TAG, "Failed to request batch deletion via MediaStore for $securityExceptionUris", e)
                }
                BatchDeleteOutcome(
                    attemptedCount = attemptedCount,
                    deleteRequest = null,
                    promptCreationFailed = true,
                    deletedCount = deletedCount,
                    securityExceptionCount = securityExceptionUris.size
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to request batch deletion via MediaStore for $securityExceptionUris", e)
                BatchDeleteOutcome(
                    attemptedCount = attemptedCount,
                    deleteRequest = null,
                    promptCreationFailed = true,
                    deletedCount = deletedCount,
                    securityExceptionCount = securityExceptionUris.size
                )
            }
        }
    }
}
