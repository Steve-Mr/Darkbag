package top.maary.darkbag.viewer.export

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.appcompat.app.AlertDialog
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.maary.darkbag.R
import top.maary.darkbag.models.EditConfig
import top.maary.darkbag.models.ImageGroup
import top.maary.darkbag.rawvideo.RawVideoExporter
import top.maary.darkbag.repository.ImageRepository
import top.maary.darkbag.repository.SettingsRepository
import top.maary.darkbag.utils.ImageSaver
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CinemaDNG 序列、调色 MP4 及单帧提取导出助手 (ViewerCinemaDngExportHelper).
 * 将视频/RAW 序列导出流从单图/半格导出协调器中解耦。
 */
internal class ViewerCinemaDngExportHelper(
    private val context: Context,
    private val scope: CoroutineScope,
    private val showProgressDialog: (title: String, onCancel: () -> Unit) -> Pair<AlertDialog, (Int, Int) -> Unit>,
    private val dismissIfActive: (AlertDialog) -> Unit
) {

    fun performExportCinemaDng(
        group: ImageGroup,
        onComplete: suspend (baseName: String) -> Unit
    ) {
        val uri = group.rawVideoUri ?: return
        val rawFolderUriStr = SettingsRepository.getInstance(context).current().rawStorageUri
        val clipName = "CDNG_${group.baseName}"

        val isUserCancelled = AtomicBoolean(false)
        var exportJob: Job? = null

        val (progressDialog, updateProgress) = showProgressDialog(
            context.getString(R.string.export_progress_exporting_cdng)
        ) {
            isUserCancelled.set(true)
            exportJob?.cancel()
        }

        exportJob = scope.launch {
            var tempExportDir: File? = null
            var destDir: File? = null
            try {
                if (rawFolderUriStr != null) {
                    val tempDir = File(context.cacheDir, "cdng_${System.currentTimeMillis()}").apply { mkdirs() }
                    tempExportDir = tempDir

                    val resultDir = RawVideoExporter.exportToCinemaDng(
                        context = context,
                        rawVideoUri = uri,
                        outputDir = tempDir,
                        clipName = clipName,
                        isCancelled = { isUserCancelled.get() || !isActive },
                        onProgress = updateProgress
                    )

                    if (isUserCancelled.get() || !isActive) {
                        withContext(NonCancellable + Dispatchers.IO) { tempDir.deleteRecursively() }
                        return@launch
                    }

                    if (resultDir != null && resultDir.exists()) {
                        val exportedClipName = resultDir.name
                        copyResultDirToSaf(resultDir, rawFolderUriStr, exportedClipName, tempDir) {
                            isUserCancelled.get() || !isActive
                        }

                        if (isUserCancelled.get() || !isActive) {
                            return@launch
                        }

                        onComplete(group.baseName)
                        top.maary.darkbag.ui.DarkbagFeedback.showSnackbar(
                            context,
                            context.getString(R.string.cinemadng_clip_exported, exportedClipName),
                            com.google.android.material.snackbar.Snackbar.LENGTH_LONG
                        )
                    } else {
                        withContext(NonCancellable + Dispatchers.IO) { tempDir.deleteRecursively() }
                        top.maary.darkbag.ui.DarkbagFeedback.showSnackbar(
                            context,
                            R.string.cinemadng_clip_export_failed,
                            isError = true
                        )
                    }
                } else {
                    val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                    val targetDir = File(picturesDir, "Darkbag/CinemaDNG/$clipName").apply { mkdirs() }
                    destDir = targetDir

                    val resultDir = RawVideoExporter.exportToCinemaDng(
                        context = context,
                        rawVideoUri = uri,
                        outputDir = targetDir,
                        clipName = clipName,
                        isCancelled = { isUserCancelled.get() || !isActive },
                        onProgress = updateProgress
                    )

                    if (isUserCancelled.get() || !isActive) {
                        withContext(NonCancellable + Dispatchers.IO) { targetDir.deleteRecursively() }
                        return@launch
                    }

                    if (resultDir != null && resultDir.exists()) {
                        withContext(Dispatchers.IO) {
                            val filePaths = resultDir.listFiles()?.map { it.absolutePath }?.toTypedArray()
                            if (!filePaths.isNullOrEmpty()) {
                                MediaScannerConnection.scanFile(context, filePaths, null, null)
                            }
                        }
                        onComplete(group.baseName)
                        top.maary.darkbag.ui.DarkbagFeedback.showSnackbar(
                            context,
                            context.getString(R.string.cinemadng_clip_exported, clipName),
                            com.google.android.material.snackbar.Snackbar.LENGTH_LONG
                        )
                    } else {
                        withContext(NonCancellable + Dispatchers.IO) { targetDir.deleteRecursively() }
                        top.maary.darkbag.ui.DarkbagFeedback.showSnackbar(
                            context,
                            R.string.cinemadng_clip_export_failed,
                            isError = true
                        )
                    }
                }
            } catch (e: CancellationException) {
                withContext(NonCancellable + Dispatchers.IO) {
                    tempExportDir?.deleteRecursively()
                    destDir?.deleteRecursively()
                }
            } catch (e: Exception) {
                withContext(NonCancellable + Dispatchers.IO) {
                    tempExportDir?.deleteRecursively()
                    destDir?.deleteRecursively()
                }
                if (!isUserCancelled.get()) {
                    Log.e("ViewerExportCoordinator", "CinemaDNG export error", e)
                    top.maary.darkbag.ui.DarkbagFeedback.showSnackbar(
                        context,
                        context.getString(R.string.cinemadng_clip_export_error, e.message ?: ""),
                        isError = true
                    )
                }
            } finally {
                dismissIfActive(progressDialog)
            }
        }
    }

    private suspend fun copyResultDirToSaf(
        resultDir: File,
        rawFolderUriStr: String,
        exportedClipName: String,
        tempDir: File,
        isCancelledOrInactive: () -> Boolean
    ) = withContext(Dispatchers.IO) {
        try {
            val treeDoc = DocumentFile.fromTreeUri(context, Uri.parse(rawFolderUriStr))
            val clipDirDoc = treeDoc?.createDirectory(exportedClipName) ?: return@withContext
            resultDir.listFiles()?.forEach { file ->
                if (isCancelledOrInactive()) return@forEach
                val mime = if (file.name.endsWith(".wav")) "audio/wav" else "image/x-adobe-dng"
                clipDirDoc.createFile(mime, file.name)?.let { targetDoc ->
                    context.contentResolver.openOutputStream(targetDoc.uri)?.use { out ->
                        file.inputStream().use { it.copyTo(out) }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("ViewerExportCoordinator", "Failed to copy CinemaDNG to SAF folder", e)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    fun performExportMp4(
        group: ImageGroup,
        config: EditConfig = group.editConfig ?: EditConfig(),
        targetResolution: Int = 1080,
        onComplete: suspend (baseName: String) -> Unit
    ) {
        val uri = group.rawVideoUri ?: return
        val mediaFolderUri = SettingsRepository.getInstance(context).current().jpgStorageUri
        val tempMp4File = File(context.cacheDir, "${group.baseName}_graded.mp4")

        val isUserCancelled = AtomicBoolean(false)
        var exportJob: Job? = null

        val (progressDialog, updateProgress) = showProgressDialog(
            context.getString(R.string.export_progress_exporting_mp4)
        ) {
            isUserCancelled.set(true)
            exportJob?.cancel()
        }

        exportJob = scope.launch {
            try {
                val success = RawVideoExporter.exportToMp4(
                    context = context,
                    rawVideoUri = uri,
                    outputFile = tempMp4File,
                    editConfig = config,
                    targetResolution = targetResolution,
                    isCancelled = { isUserCancelled.get() || !isActive },
                    onProgress = updateProgress
                )

                if (isUserCancelled.get() || !isActive) {
                    withContext(NonCancellable + Dispatchers.IO) { tempMp4File.delete() }
                    return@launch
                }

                if (success && tempMp4File.exists() && tempMp4File.length() > 0) {
                    val (savedUri, _) = ImageSaver.saveMp4Video(
                        context = context,
                        mp4File = tempMp4File,
                        baseName = "${group.baseName}_graded",
                        mediaFolderUri = mediaFolderUri
                    )
                    if (savedUri != null) {
                        onComplete(group.baseName)
                        top.maary.darkbag.ui.DarkbagFeedback.showSnackbar(
                            context,
                            context.getString(R.string.mp4_graded_saved, "${group.baseName}_graded.mp4"),
                            com.google.android.material.snackbar.Snackbar.LENGTH_LONG
                        )
                    } else {
                        top.maary.darkbag.ui.DarkbagFeedback.showSnackbar(
                            context,
                            R.string.mp4_save_storage_failed,
                            isError = true
                        )
                    }
                } else {
                    withContext(NonCancellable + Dispatchers.IO) { tempMp4File.delete() }
                    top.maary.darkbag.ui.DarkbagFeedback.showSnackbar(
                        context,
                        R.string.mp4_export_failed,
                        isError = true
                    )
                }
            } catch (e: CancellationException) {
                withContext(NonCancellable + Dispatchers.IO) { tempMp4File.delete() }
            } catch (e: Exception) {
                withContext(NonCancellable + Dispatchers.IO) { tempMp4File.delete() }
                if (!isUserCancelled.get()) {
                    Log.e("ViewerExportCoordinator", "MP4 export error", e)
                    top.maary.darkbag.ui.DarkbagFeedback.showSnackbar(
                        context,
                        context.getString(R.string.mp4_export_error, e.message ?: ""),
                        isError = true
                    )
                }
            } finally {
                dismissIfActive(progressDialog)
            }
        }
    }

    fun exportCinemaDngFrame(
        group: ImageGroup,
        frameUri: Uri?,
        frameIndex: Int,
        exportType: RawVideoExporter.FrameExportType,
        editConfig: EditConfig? = null,
        repository: ImageRepository,
        onStart: () -> Unit,
        onFinished: (success: Boolean) -> Unit
    ) {
        if (frameUri == null) {
            top.maary.darkbag.ui.DarkbagFeedback.showSnackbar(
                context,
                R.string.cinemadng_export_failed,
                isError = true
            )
            return
        }

        onStart()

        scope.launch {
            val result = withContext(Dispatchers.IO) {
                val fullyLoadedGroup = if (!group.metadataLoaded) {
                    repository.loadMetadata(group)
                } else group
                val config = editConfig ?: fullyLoadedGroup.editConfig
                RawVideoExporter.exportSingleFrameFromCinemaDng(
                    context = context.applicationContext,
                    frameDngUri = frameUri,
                    baseName = group.baseName,
                    frameIndex = frameIndex,
                    editConfig = config,
                    exportType = exportType
                )
            }

            val (rawUri, jpgUri) = result
            val success = rawUri != null || jpgUri != null
            if (success) {
                top.maary.darkbag.ui.DarkbagFeedback.showSnackbar(context, R.string.cinemadng_export_success)
            } else {
                top.maary.darkbag.ui.DarkbagFeedback.showSnackbar(
                    context,
                    R.string.cinemadng_export_failed,
                    isError = true
                )
            }
            onFinished(success)
        }
    }
}
