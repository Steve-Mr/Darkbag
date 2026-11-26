package top.maary.darkbag.fragments.gallery

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.fragment.app.FragmentManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.maary.darkbag.R
import top.maary.darkbag.fragments.GallerySelectedItem
import top.maary.darkbag.models.EditConfig
import top.maary.darkbag.repository.ImageRepository
import top.maary.darkbag.ui.BentoSelectionBottomSheet
import top.maary.darkbag.ui.ColorRecipeBottomSheet
import top.maary.darkbag.utils.GalleryCollageHelper
import top.maary.darkbag.utils.LutManager

/**
 * 暗房画廊批量动作协调助手 (GalleryBatchActionHelper).
 * 封装双图拼图合成、批量调色应用及多图分享分发。
 */
internal class GalleryBatchActionHelper(
    private val contextProvider: () -> Context?,
    private val scope: CoroutineScope,
    private val repository: ImageRepository,
    private val onStartWork: (count: Int) -> Unit,
    private val onEndWork: () -> Unit,
    private val onRefreshAfterSuccess: () -> Unit
) {

    fun performMergeCollage(
        selected: List<GallerySelectedItem>,
        fragmentManager: FragmentManager
    ) {
        if (selected.size != 2) return
        val ctx = contextProvider() ?: return
        val options = listOf(
            ctx.getString(R.string.collage_layout_sbs),
            ctx.getString(R.string.collage_layout_tb)
        )
        BentoSelectionBottomSheet.show(
            fragmentManager = fragmentManager,
            title = ctx.getString(R.string.gallery_action_merge),
            options = options,
            selectedIndex = 0,
            badge = "COLLAGE"
        ) { which ->
            val activeCtx = contextProvider() ?: return@show
            val isSideBySide = (which == 0)
            val item1 = selected[0]
            val item2 = selected[1]
            val uri1 = item1.specificLens?.jpgUri ?: item1.group.firstAvailableUri ?: item1.group.jpgUri
            val uri2 = item2.specificLens?.jpgUri ?: item2.group.firstAvailableUri ?: item2.group.jpgUri

            if (uri1 == null || uri2 == null) {
                top.maary.darkbag.ui.DarkbagFeedback.showSnackbar(activeCtx, R.string.gallery_collage_resolve_failed, isError = true)
                return@show
            }

            onStartWork(2)
            scope.launch {
                val insertedUri = GalleryCollageHelper.createAndSaveCollage(
                    context = activeCtx,
                    uri1 = uri1,
                    uri2 = uri2,
                    isSideBySide = isSideBySide
                )

                onEndWork()
                if (insertedUri != null) {
                    repository.invalidateCache()
                    onRefreshAfterSuccess()
                    top.maary.darkbag.ui.DarkbagFeedback.showSnackbar(activeCtx, R.string.gallery_merge_success)
                } else {
                    top.maary.darkbag.ui.DarkbagFeedback.showSnackbar(activeCtx, R.string.gallery_collage_create_failed, isError = true)
                }
            }
        }
    }

    fun performBatchGrade(selected: List<GallerySelectedItem>) {
        val ctx = contextProvider() ?: return
        val lutManager = LutManager(ctx)
        ColorRecipeBottomSheet.show(ctx, lutManager) { selectedLog, selectedLut ->
            val activeCtx = contextProvider() ?: return@show
            val appContext = activeCtx.applicationContext
            onStartWork(selected.size)

            scope.launch {
                val gradedCount = withContext(Dispatchers.IO) {
                    var count = 0
                    for (item in selected) {
                        val group = item.group
                        val targetConfig = (group.editConfig ?: EditConfig()).copy(
                            log = selectedLog,
                            lut = selectedLut ?: "None"
                        )
                        val uriToSave = group.firstAvailableUri ?: group.jpgUri ?: group.dngUri
                        if (uriToSave != null) {
                            try {
                                repository.saveEditConfig(uriToSave, targetConfig)
                                count++
                            } catch (e: Exception) {
                                Log.e("DarkroomGallery", "Failed to save batch grade config for $uriToSave", e)
                            }
                        }
                    }
                    count
                }

                onEndWork()
                repository.invalidateCache()
                onRefreshAfterSuccess()
                top.maary.darkbag.ui.DarkbagFeedback.showSnackbar(
                    activeCtx,
                    appContext.getString(R.string.gallery_batch_grade_success, gradedCount)
                )
            }
        }
    }

    fun buildShareIntent(context: Context, uris: List<Uri>): Intent {
        return if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = "image/*"
                putExtra(Intent.EXTRA_STREAM, uris[0])
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "image/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }

    suspend fun inspectImportedDarkbagUris(
        context: Context,
        uris: List<Uri>
    ): Int = withContext(Dispatchers.IO) {
        val cr = context.contentResolver
        var count = 0
        for (uri in uris) {
            try {
                if (top.maary.darkbag.utils.DarkbagIdentity.isDarkbagUri(uri, cr)) {
                    count++
                }
            } catch (e: Exception) {
                Log.e("DarkroomGallery", "Error inspecting import URI $uri", e)
            }
        }
        count
    }
}
