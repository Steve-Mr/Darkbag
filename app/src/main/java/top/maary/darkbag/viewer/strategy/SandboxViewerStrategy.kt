package top.maary.darkbag.viewer.strategy

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.appcompat.widget.PopupMenu
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.maary.darkbag.R
import top.maary.darkbag.models.EditConfig
import top.maary.darkbag.models.ImageGroup
import top.maary.darkbag.pipeline.darkroom.DarkroomRenderEngine
import top.maary.darkbag.pipeline.darkroom.model.DarkroomRenderSpec
import top.maary.darkbag.pipeline.darkroom.model.RenderOutputFormat
import top.maary.darkbag.pipeline.darkroom.sink.SandboxExportSink
import top.maary.darkbag.repository.ImageRepository
import top.maary.darkbag.utils.HalfFrameManager
import top.maary.darkbag.utils.ImageSaver
import java.io.File

class SandboxViewerStrategy : ViewerMediaStrategy {

    companion object {
        private const val TAG = "SandboxViewerStrategy"
    }

    override val isGallerySupported: Boolean = false
    override val isSandbox: Boolean = true

    override suspend fun loadInitialGroups(
        context: Context,
        args: Bundle?,
        repository: ImageRepository
    ): List<ImageGroup>? = withContext(Dispatchers.IO) {
        val playgroundPaths = args?.getStringArray("playground_dng_paths")
        if (playgroundPaths.isNullOrEmpty()) return@withContext null

        val filesDir = context.filesDir
        val playgroundDir = File(filesDir, "playground_dngs")
        if (!playgroundDir.exists()) playgroundDir.mkdirs()

        val group = if (playgroundPaths.size == 1) {
            val firstPath = playgroundPaths[0]
            val baseName = File(firstPath).nameWithoutExtension
            val potentialJpg = File(playgroundDir, "$baseName.jpg")
            val jpgUri = if (potentialJpg.exists()) Uri.fromFile(potentialJpg) else null
            val dngUri = Uri.fromFile(File(firstPath))

            ImageGroup(
                baseName = baseName,
                dngUri = dngUri,
                jpgUri = jpgUri,
                captureTime = System.currentTimeMillis(),
                lastModified = System.currentTimeMillis()
            )
        } else {
            val path1 = playgroundPaths[0]
            val path2 = playgroundPaths[1]
            val name1 = File(path1).nameWithoutExtension

            val mergedBaseName = if (name1.endsWith("_1")) {
                name1.removeSuffix("_1")
            } else {
                name1 + "_merged"
            }

            val potentialMergedJpg = File(playgroundDir, "$mergedBaseName.jpg")
            val mergedJpgUri = if (potentialMergedJpg.exists()) Uri.fromFile(potentialMergedJpg) else null
            val layout = if (mergedJpgUri == null) {
                args.getString("playground_hf_layout") ?: "SBS"
            } else {
                "SBS"
            }

            ImageGroup(
                baseName = mergedBaseName,
                dngUri1 = Uri.fromFile(File(path1)),
                dngUri2 = Uri.fromFile(File(path2)),
                jpgUri = mergedJpgUri,
                hfLayout = layout,
                captureTime = System.currentTimeMillis(),
                lastModified = System.currentTimeMillis()
            )
        }

        val fullyLoaded = repository.loadMetadata(group)
        listOf(fullyLoaded)
    }

    override suspend fun saveEdit(
        context: Context,
        group: ImageGroup,
        config: EditConfig,
        isReplacement: Boolean,
        repository: ImageRepository,
        cachedBytes1: ByteArray?,
        cachedBytes2: ByteArray?
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val finalConfig = config.copy(hfLayout = config.hfLayout ?: group.hfLayout)
            val editTimestamp = java.text.SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", java.util.Locale.US).format(java.util.Date())
            val newBaseName = if (isReplacement) group.baseName else "${group.baseName}_edited_$editTimestamp"

            val playgroundDir = File(context.filesDir, "playground_dngs")
            if (!playgroundDir.exists()) playgroundDir.mkdirs()

            val targetJpgFile = File(playgroundDir, "$newBaseName.jpg")
            val sandboxSink = SandboxExportSink(targetFile = targetJpgFile)

            val spec = DarkroomRenderSpec.fromImageGroup(
                context = context,
                group = group,
                editConfig = finalConfig,
                sink = sandboxSink,
                outputFormat = RenderOutputFormat.JPEG,
                cachedBytes1 = cachedBytes1,
                cachedBytes2 = cachedBytes2,
                downsampleHalfFrame = HalfFrameManager(context).downsample,
                repository = repository
            )

            val renderResult = DarkroomRenderEngine.render(context, spec)
            if (!renderResult.success || !targetJpgFile.exists()) {
                return@withContext false
            }

            // Companion DNG handling
            val dngUri1 = group.dngUri ?: group.dngUri1
            if (!isReplacement && !group.isHalfFrame() && dngUri1 != null) {
                val newDngFile = File(playgroundDir, "$newBaseName.dng")
                context.contentResolver.openInputStream(dngUri1)?.use { input ->
                    newDngFile.outputStream().use { output -> input.copyTo(output) }
                }
            } else if (!isReplacement && group.isHalfFrame()) {
                dngUri1?.let { uri ->
                    val newDngFile1 = File(playgroundDir, "${newBaseName}_1.dng")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        newDngFile1.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                group.dngUri2?.let { uri ->
                    val newDngFile2 = File(playgroundDir, "${newBaseName}_2.dng")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        newDngFile2.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            } else if (isReplacement && group.isHalfFrame()) {
                val isFirstSave = group.jpgUri == null
                if (isFirstSave) {
                    group.dngUri1?.let { uri ->
                        if (uri.scheme == "file") {
                            uri.path?.let { path ->
                                val oldFile = File(path)
                                val newFile = File(playgroundDir, "${newBaseName}_1.dng")
                                if (oldFile.exists() && oldFile.absolutePath != newFile.absolutePath) {
                                    val hasAssociatedJpg = File(oldFile.parent, "${oldFile.nameWithoutExtension}.jpg").exists()
                                    if (oldFile.nameWithoutExtension.endsWith("_1") || oldFile.nameWithoutExtension.endsWith("_2") || hasAssociatedJpg) {
                                        oldFile.copyTo(newFile, overwrite = true)
                                    } else {
                                        if (!oldFile.renameTo(newFile)) {
                                            oldFile.copyTo(newFile, overwrite = true)
                                            oldFile.delete()
                                        }
                                    }
                                }
                            }
                        }
                    }
                    group.dngUri2?.let { uri ->
                        if (uri.scheme == "file") {
                            uri.path?.let { path ->
                                val oldFile = File(path)
                                val newFile = File(playgroundDir, "${newBaseName}_2.dng")
                                if (oldFile.exists() && oldFile.absolutePath != newFile.absolutePath) {
                                    val hasAssociatedJpg = File(oldFile.parent, "${oldFile.nameWithoutExtension}.jpg").exists()
                                    if (oldFile.nameWithoutExtension.endsWith("_1") || oldFile.nameWithoutExtension.endsWith("_2") || hasAssociatedJpg) {
                                        oldFile.copyTo(newFile, overwrite = true)
                                    } else {
                                        if (!oldFile.renameTo(newFile)) {
                                            oldFile.copyTo(newFile, overwrite = true)
                                            oldFile.delete()
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            val finalJpgUri = Uri.fromFile(targetJpgFile)
            val captureMetadata = (group.jpgUri ?: group.dngUri ?: group.dngUri1)?.let { repository.getCaptureMetadata(it) }
            ImageSaver.writeMetadataToExif(context, finalJpgUri, finalConfig, captureMetadata)
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to save sandbox edit", e)
            false
        }
    }

    override suspend fun deleteImage(
        context: Context,
        group: ImageGroup,
        targetUris: List<Uri>?,
        deleteGroup: Boolean,
        repository: ImageRepository
    ): Boolean = withContext(Dispatchers.IO) {
        val filesToDelete = mutableListOf<File>()

        if (targetUris != null) {
            for (u in targetUris) {
                if (u.scheme == "file") u.path?.let { p -> filesToDelete.add(File(p)) }
            }
        } else {
            group.jpgUri?.let { if (it.scheme == "file") it.path?.let { p -> filesToDelete.add(File(p)) } }
            group.dngUri?.let { if (it.scheme == "file") it.path?.let { p -> filesToDelete.add(File(p)) } }
            group.dngUri1?.let { if (it.scheme == "file") it.path?.let { p -> filesToDelete.add(File(p)) } }
            group.dngUri2?.let { if (it.scheme == "file") it.path?.let { p -> filesToDelete.add(File(p)) } }
        }

        var deletedCount = 0
        for (f in filesToDelete) {
            if (f.exists() && f.delete()) {
                deletedCount++
            }
        }
        deletedCount > 0
    }

    override fun showSaveMenu(
        context: Context,
        anchor: View,
        currentGroup: ImageGroup,
        onAction: (ViewerSaveAction) -> Unit
    ) {
        val popup = PopupMenu(context, anchor)
        popup.menu.add(0, 1001, 0, context.getString(R.string.save_as_new)).apply {
            setIcon(R.drawable.ic_save_as)
        }
        popup.menu.add(0, 1002, 0, context.getString(R.string.share_as_tiff)).apply {
            setIcon(R.drawable.ic_photo)
        }
        popup.menu.add(0, 1003, 0, context.getString(R.string.save_processed_jpg)).apply {
            setIcon(R.drawable.ic_save)
        }

        try {
            val fieldMPopup = PopupMenu::class.java.getDeclaredField("mPopup")
            fieldMPopup.isAccessible = true
            val mPopup = fieldMPopup.get(popup)
            mPopup.javaClass.getDeclaredMethod("setForceShowIcon", Boolean::class.java).invoke(mPopup, true)
        } catch (_: Exception) {}

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1001 -> onAction(ViewerSaveAction.SAVE_AS_NEW)
                1002 -> onAction(ViewerSaveAction.SHARE_TIFF)
                1003 -> onAction(ViewerSaveAction.EXPORT_JPG_TO_PICTURES)
            }
            true
        }
        popup.show()
    }

    override fun handleDiscard(
        currentGroup: ImageGroup,
        groups: List<ImageGroup>,
        args: Bundle?
    ): Boolean {
        val playgroundPaths = args?.getStringArray("playground_dng_paths")
        // 若在工坊中刚刚合并双 DNG 进入半格且尚未保存，直接放弃回退，无需弹窗
        return (groups.size == 1 && playgroundPaths != null && playgroundPaths.size == 2 && currentGroup.jpgUri == null)
    }
}
