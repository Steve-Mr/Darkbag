package top.maary.darkbag.viewer.export

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.util.Log
import android.view.LayoutInflater
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.*
import top.maary.darkbag.R
import top.maary.darkbag.fragments.RawVideoExportSheet
import top.maary.darkbag.fragments.SettingsFragment
import top.maary.darkbag.models.EditConfig
import top.maary.darkbag.models.ImageGroup
import top.maary.darkbag.pipeline.darkroom.DarkroomRenderEngine
import top.maary.darkbag.pipeline.darkroom.model.DarkroomRenderSpec
import top.maary.darkbag.pipeline.darkroom.model.RenderOutputFormat
import top.maary.darkbag.pipeline.darkroom.sink.MediaStoreExportSink
import top.maary.darkbag.pipeline.darkroom.sink.ShareFileSink
import top.maary.darkbag.provider.DarkbagDocumentsProvider
import top.maary.darkbag.rawvideo.RawVideoExporter
import top.maary.darkbag.repository.ImageRepository
import top.maary.darkbag.utils.HalfFrameManager
import top.maary.darkbag.utils.ImageSaver
import top.maary.darkbag.viewer.strategy.ViewerMediaStrategy
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 图像/视频导出与外发协调器 (ViewerExportCoordinator).
 * 封装 CinemaDNG 全流程序列导出、MP4 调色重编码、单帧提取、JPEG 导出、TIFF 渲染外发及 M3 进度弹窗。
 * 严格遵循 Invariant 1 (CaptureSink & 统一导出路径隔离) 与 Invariant 4 (零 UI 阻塞与协程异步流)。
 */
class ViewerExportCoordinator(
    private val context: Context,
    private val scope: CoroutineScope,
    private val layoutInflater: LayoutInflater
) {

    var activeExportDialog: AlertDialog? = null

    /**
     * 构建并展示导出进度弹窗。
     */
    fun showExportProgressDialog(
        title: String,
        onCancel: (() -> Unit)? = null
    ): Pair<AlertDialog, (Int, Int) -> Unit> {
        val dialogView = layoutInflater.inflate(R.layout.layout_export_progress_dialog, null)
        val titleView = dialogView.findViewById<TextView>(R.id.export_dialog_title)
        val progressBar = dialogView.findViewById<LinearProgressIndicator>(R.id.export_progress_bar)
        val progressText = dialogView.findViewById<TextView>(R.id.export_progress_text)

        titleView.text = title
        progressBar.isIndeterminate = false
        progressBar.progress = 0
        progressText.text = context.getString(R.string.export_progress_preparing)

        val builder = MaterialAlertDialogBuilder(context)
            .setView(dialogView)
            .setCancelable(false)

        if (onCancel != null) {
            builder.setNegativeButton(R.string.cancel) { _, _ ->
                onCancel()
            }
        }

        val dialog = builder.create()
        dialog.show()
        activeExportDialog = dialog

        val updateProgress: (Int, Int) -> Unit = { current, total ->
            if (total > 0) {
                val percent = (current * 100) / total
                scope.launch(Dispatchers.Main) {
                    if (dialog.isShowing) {
                        progressBar.progress = percent
                        progressText.text = context.getString(R.string.export_progress_format, percent, current, total)
                    }
                }
            }
        }
        return Pair(dialog, updateProgress)
    }

    /**
     * 执行 CinemaDNG 序列导出。
     */
    fun performExportCinemaDng(
        group: ImageGroup,
        onComplete: suspend (baseName: String) -> Unit
    ) {
        val uri = group.rawVideoUri ?: return
        val prefs = context.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        val rawFolderUriStr = prefs.getString(SettingsFragment.KEY_RAW_STORAGE_URI, null)
        val clipName = "CDNG_${group.baseName}"

        val isUserCancelled = AtomicBoolean(false)
        var exportJob: Job? = null

        val (progressDialog, updateProgress) = showExportProgressDialog(
            title = context.getString(R.string.export_progress_exporting_cdng),
            onCancel = {
                isUserCancelled.set(true)
                exportJob?.cancel()
            }
        )

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
                        Toast.makeText(context, R.string.toast_export_cancelled, Toast.LENGTH_SHORT).show()
                        return@launch
                    }

                    if (resultDir != null && resultDir.exists()) {
                        val exportedClipName = resultDir.name
                        withContext(Dispatchers.IO) {
                            try {
                                val treeUri = Uri.parse(rawFolderUriStr)
                                val treeDoc = DocumentFile.fromTreeUri(context, treeUri)
                                val clipDirDoc = treeDoc?.createDirectory(exportedClipName)
                                if (clipDirDoc != null) {
                                    resultDir.listFiles()?.forEach { file ->
                                        if (isUserCancelled.get() || !isActive) return@forEach
                                        val mime = if (file.name.endsWith(".wav")) "audio/wav" else "image/x-adobe-dng"
                                        val targetDoc = clipDirDoc.createFile(mime, file.name)
                                        if (targetDoc != null) {
                                            context.contentResolver.openOutputStream(targetDoc.uri)?.use { out ->
                                                file.inputStream().use { it.copyTo(out) }
                                            }
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e("ViewerExportCoordinator", "Failed to copy CinemaDNG to SAF folder", e)
                            } finally {
                                tempDir.deleteRecursively()
                            }
                        }

                        if (isUserCancelled.get() || !isActive) {
                            Toast.makeText(context, R.string.toast_export_cancelled, Toast.LENGTH_SHORT).show()
                            return@launch
                        }

                        onComplete(group.baseName)
                        Toast.makeText(context, "CinemaDNG exported: $exportedClipName", Toast.LENGTH_LONG).show()
                    } else {
                        withContext(NonCancellable + Dispatchers.IO) { tempDir.deleteRecursively() }
                        Toast.makeText(context, "CinemaDNG export failed", Toast.LENGTH_SHORT).show()
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
                        Toast.makeText(context, R.string.toast_export_cancelled, Toast.LENGTH_SHORT).show()
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
                        Toast.makeText(context, "CinemaDNG exported: $clipName", Toast.LENGTH_LONG).show()
                    } else {
                        withContext(NonCancellable + Dispatchers.IO) { targetDir.deleteRecursively() }
                        Toast.makeText(context, "CinemaDNG export failed", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: CancellationException) {
                withContext(NonCancellable + Dispatchers.IO) {
                    tempExportDir?.deleteRecursively()
                    destDir?.deleteRecursively()
                }
                Toast.makeText(context, R.string.toast_export_cancelled, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                withContext(NonCancellable + Dispatchers.IO) {
                    tempExportDir?.deleteRecursively()
                    destDir?.deleteRecursively()
                }
                if (isUserCancelled.get()) {
                    Toast.makeText(context, R.string.toast_export_cancelled, Toast.LENGTH_SHORT).show()
                } else {
                    Log.e("ViewerExportCoordinator", "CinemaDNG export error", e)
                    Toast.makeText(context, "CinemaDNG export error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } finally {
                if (progressDialog.isShowing) {
                    try { progressDialog.dismiss() } catch (_: Exception) {}
                }
                if (activeExportDialog === progressDialog) {
                    activeExportDialog = null
                }
            }
        }
    }

    /**
     * 执行 MP4 调色视频重编码导出。
     */
    fun performExportMp4(
        group: ImageGroup,
        config: EditConfig = group.editConfig ?: EditConfig(),
        targetResolution: Int = 1080,
        onComplete: suspend (baseName: String) -> Unit
    ) {
        val uri = group.rawVideoUri ?: return
        val prefs = context.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        val mediaFolderUri = prefs.getString(SettingsFragment.KEY_JPG_STORAGE_URI, null)
        val tempMp4File = File(context.cacheDir, "${group.baseName}_graded.mp4")

        val isUserCancelled = AtomicBoolean(false)
        var exportJob: Job? = null

        val (progressDialog, updateProgress) = showExportProgressDialog(
            title = context.getString(R.string.export_progress_exporting_mp4),
            onCancel = {
                isUserCancelled.set(true)
                exportJob?.cancel()
            }
        )

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
                    Toast.makeText(context, R.string.toast_export_cancelled, Toast.LENGTH_SHORT).show()
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
                        Toast.makeText(context, "Graded MP4 saved: ${group.baseName}_graded.mp4", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(context, "Failed to save MP4 to storage", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    withContext(NonCancellable + Dispatchers.IO) { tempMp4File.delete() }
                    Toast.makeText(context, "MP4 export failed", Toast.LENGTH_SHORT).show()
                }
            } catch (e: CancellationException) {
                withContext(NonCancellable + Dispatchers.IO) { tempMp4File.delete() }
                Toast.makeText(context, R.string.toast_export_cancelled, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                withContext(NonCancellable + Dispatchers.IO) { tempMp4File.delete() }
                if (isUserCancelled.get()) {
                    Toast.makeText(context, R.string.toast_export_cancelled, Toast.LENGTH_SHORT).show()
                } else {
                    Log.e("ViewerExportCoordinator", "MP4 export error", e)
                    Toast.makeText(context, "MP4 export error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } finally {
                if (progressDialog.isShowing) {
                    try { progressDialog.dismiss() } catch (_: Exception) {}
                }
                if (activeExportDialog === progressDialog) {
                    activeExportDialog = null
                }
            }
        }
    }

    /**
     * 导出 CinemaDNG 单帧图像。
     */
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
            Toast.makeText(context, R.string.cinemadng_export_failed, Toast.LENGTH_SHORT).show()
            return
        }

        onStart()
        Toast.makeText(context, R.string.cinemadng_exporting_frame, Toast.LENGTH_SHORT).show()

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
                Toast.makeText(context, R.string.cinemadng_export_success, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, R.string.cinemadng_export_failed, Toast.LENGTH_SHORT).show()
            }
            onFinished(success)
        }
    }

    /**
     * 处理 RawVideoExportSheet 的结果分发。
     */
    fun handleExportSheetResult(
        bundle: Bundle,
        currentGroup: ImageGroup,
        currentEditConfig: EditConfig?,
        frameInfo: Pair<Int, Uri?>?,
        repository: ImageRepository,
        onStartExport: () -> Unit,
        onEndExport: () -> Unit,
        onRefreshGroup: suspend (String) -> Unit
    ) {
        val log = bundle.getString(RawVideoExportSheet.RESULT_LOG) ?: "None"
        val lut = bundle.getString(RawVideoExportSheet.RESULT_LUT) ?: "None"
        val resolution = bundle.getInt(RawVideoExportSheet.RESULT_RESOLUTION, 1080)
        val action = bundle.getString(RawVideoExportSheet.RESULT_ACTION)
        val frameIndex = bundle.getInt(RawVideoExportSheet.RESULT_FRAME_INDEX, 0)
        val config = (currentEditConfig ?: currentGroup.editConfig ?: EditConfig()).copy(log = log, lut = lut)

        val frameUri = currentGroup.cinemaDngFrameUris.getOrNull(frameIndex) ?: frameInfo?.second ?: currentGroup.cinemaDngFirstFrameUri ?: currentGroup.dngUri

        when (action) {
            RawVideoExportSheet.ACTION_EXPORT_MP4 -> performExportMp4(currentGroup, config, resolution) { onRefreshGroup(it) }
            RawVideoExportSheet.ACTION_EXPORT_CINEMADNG -> performExportCinemaDng(currentGroup) { onRefreshGroup(it) }
            RawVideoExportSheet.ACTION_FRAME_JPG -> exportCinemaDngFrame(currentGroup, frameUri, frameIndex, RawVideoExporter.FrameExportType.GRADED_JPG_ONLY, config, repository, onStartExport) { success ->
                onEndExport()
                if (success) scope.launch { onRefreshGroup(currentGroup.baseName) }
            }
            RawVideoExportSheet.ACTION_FRAME_DNG -> exportCinemaDngFrame(currentGroup, frameUri, frameIndex, RawVideoExporter.FrameExportType.RAW_DNG_ONLY, null, repository, onStartExport) { success ->
                onEndExport()
                if (success) scope.launch { onRefreshGroup(currentGroup.baseName) }
            }
            RawVideoExportSheet.ACTION_FRAME_PAIR -> exportCinemaDngFrame(currentGroup, frameUri, frameIndex, RawVideoExporter.FrameExportType.RAW_AND_GRADED_PAIR, config, repository, onStartExport) { success ->
                onEndExport()
                if (success) scope.launch { onRefreshGroup(currentGroup.baseName) }
            }
            else -> {
                val exportMode = bundle.getInt(RawVideoExportSheet.RESULT_EXPORT_MODE, RawVideoExportSheet.MODE_VIDEO)
                when (exportMode) {
                    RawVideoExportSheet.MODE_VIDEO -> performExportMp4(currentGroup, config, resolution) { onRefreshGroup(it) }
                    RawVideoExportSheet.MODE_SINGLE_FRAME_JPG -> exportCinemaDngFrame(currentGroup, frameUri, frameIndex, RawVideoExporter.FrameExportType.GRADED_JPG_ONLY, config, repository, onStartExport) { success ->
                        onEndExport()
                        if (success) scope.launch { onRefreshGroup(currentGroup.baseName) }
                    }
                    RawVideoExportSheet.MODE_SINGLE_FRAME_PAIR -> exportCinemaDngFrame(currentGroup, frameUri, frameIndex, RawVideoExporter.FrameExportType.RAW_AND_GRADED_PAIR, config, repository, onStartExport) { success ->
                        onEndExport()
                        if (success) scope.launch { onRefreshGroup(currentGroup.baseName) }
                    }
                }
            }
        }
    }

    /**
     * 执行暗房调色导出 JPEG 图片到公共媒体库。
     */
    fun exportToJpg(
        group: ImageGroup,
        config: EditConfig,
        isEditingAdjustments: Boolean,
        cachedBytes1: ByteArray?,
        cachedBytes2: ByteArray?,
        repository: ImageRepository,
        onStartExport: () -> Unit,
        onEndExport: () -> Unit,
        onSaveReplacement: () -> Unit
    ) {
        if (isEditingAdjustments) {
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.export_to_jpg)
                .setMessage(R.string.export_jpg_dialog_message)
                .setPositiveButton(R.string.save_and_export) { _, _ ->
                    onSaveReplacement()
                    processAndExportJpg(group, config, cachedBytes1, cachedBytes2, repository, onStartExport, onEndExport)
                }
                .setNegativeButton(R.string.export_without_saving) { _, _ ->
                    processAndExportJpg(group, config, cachedBytes1, cachedBytes2, repository, onStartExport, onEndExport)
                }
                .setNeutralButton(R.string.cancel, null)
                .show()
        } else {
            processAndExportJpg(group, config, cachedBytes1, cachedBytes2, repository, onStartExport, onEndExport)
        }
    }

    private fun processAndExportJpg(
        group: ImageGroup,
        config: EditConfig,
        cachedBytes1: ByteArray?,
        cachedBytes2: ByteArray?,
        repository: ImageRepository,
        onStartExport: () -> Unit,
        onEndExport: () -> Unit
    ) {
        onStartExport()
        val appContext = context.applicationContext
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val mediaStoreSink = MediaStoreExportSink(
                        baseName = group.baseName,
                        isReplacement = false
                    )
                    val spec = DarkroomRenderSpec.fromImageGroup(
                        context = appContext,
                        group = group,
                        editConfig = config,
                        sink = mediaStoreSink,
                        outputFormat = RenderOutputFormat.JPEG,
                        cachedBytes1 = cachedBytes1,
                        cachedBytes2 = cachedBytes2,
                        downsampleHalfFrame = HalfFrameManager(appContext).downsample,
                        repository = repository
                    )
                    DarkroomRenderEngine.render(appContext, spec)
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(appContext, appContext.getString(R.string.toast_export_jpg_success), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("ViewerExportCoordinator", "Failed to export JPG", e)
            } finally {
                onEndExport()
            }
        }
    }

    /**
     * 执行保存暗房调色修改。
     */
    fun saveEdit(
        group: ImageGroup,
        config: EditConfig,
        isReplacement: Boolean,
        cachedBytes1: ByteArray?,
        cachedBytes2: ByteArray?,
        repository: ImageRepository,
        mediaStrategy: ViewerMediaStrategy,
        onStartSave: () -> Unit,
        onEndSave: () -> Unit,
        onSuccess: suspend () -> Unit
    ) {
        onStartSave()
        val appContext = context.applicationContext
        scope.launch {
            try {
                val success = mediaStrategy.saveEdit(
                    context = appContext,
                    group = group,
                    config = config,
                    isReplacement = isReplacement,
                    repository = repository,
                    cachedBytes1 = cachedBytes1,
                    cachedBytes2 = cachedBytes2
                )
                if (success) {
                    onSuccess()
                }
            } catch (e: Throwable) {
                Log.e("ViewerExportCoordinator", "Failed to save edit", e)
            } finally {
                onEndSave()
            }
        }
    }

    /**
     * 弹出 TIFF 外发对话框或直接外发。
     */
    fun performShareAsTiff(
        group: ImageGroup,
        config: EditConfig,
        isEditingAdjustments: Boolean,
        cachedBytes1: ByteArray?,
        cachedBytes2: ByteArray?,
        repository: ImageRepository,
        onStartRender: () -> Unit,
        onEndRender: () -> Unit,
        onSaveReplacement: () -> Unit
    ) {
        if (isEditingAdjustments) {
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.share_as_tiff_dialog_title)
                .setMessage(R.string.share_as_tiff_dialog_message)
                .setPositiveButton(R.string.save_and_share) { _, _ ->
                    onSaveReplacement()
                    processAndShareTiff(group, config, cachedBytes1, cachedBytes2, repository, onStartRender, onEndRender)
                }
                .setNegativeButton(R.string.share_without_saving) { _, _ ->
                    processAndShareTiff(group, config, cachedBytes1, cachedBytes2, repository, onStartRender, onEndRender)
                }
                .setNeutralButton(R.string.cancel, null)
                .show()
        } else {
            processAndShareTiff(group, config, cachedBytes1, cachedBytes2, repository, onStartRender, onEndRender)
        }
    }

    fun processAndShareTiff(
        group: ImageGroup,
        config: EditConfig,
        cachedBytes1: ByteArray?,
        cachedBytes2: ByteArray?,
        repository: ImageRepository,
        onStartRender: () -> Unit,
        onEndRender: () -> Unit
    ) {
        onStartRender()
        val appContext = context.applicationContext
        scope.launch {
            val tiffUri = withContext(Dispatchers.IO) {
                try {
                    val exportDir = File(appContext.filesDir, "shared_exports")
                    if (!exportDir.exists()) exportDir.mkdirs()

                    val tempTiff = File(exportDir, "latest_share_export.tif")
                    val shareSink = ShareFileSink(tempTiff, mimeType = "image/tiff")
                    val spec = DarkroomRenderSpec.fromImageGroup(
                        context = appContext,
                        group = group,
                        editConfig = config,
                        sink = shareSink,
                        outputFormat = RenderOutputFormat.TIFF,
                        cachedBytes1 = cachedBytes1,
                        cachedBytes2 = cachedBytes2,
                        customTiffPath = tempTiff.absolutePath,
                        repository = repository
                    )
                    val result = DarkroomRenderEngine.render(appContext, spec)
                    if (result.success && tempTiff.exists()) {
                        DocumentsContract.buildDocumentUri(
                            DarkbagDocumentsProvider.AUTHORITY,
                            "${DarkbagDocumentsProvider.ROOT_ID_EXPORTS}:${tempTiff.name}"
                        )
                    } else null
                } catch (e: Exception) {
                    Log.e("ViewerExportCoordinator", "Failed to process TIFF for sharing", e)
                    null
                }
            }

            onEndRender()
            tiffUri?.let { uri ->
                val childrenUri = DocumentsContract.buildChildDocumentsUri(
                    DarkbagDocumentsProvider.AUTHORITY,
                    DarkbagDocumentsProvider.ROOT_ID_EXPORTS
                )
                appContext.contentResolver.notifyChange(childrenUri, null)
                shareTiff(uri)
            }
        }
    }

    fun toShareableUri(uri: Uri): Uri {
        if (uri.scheme == "file") {
            val file = File(uri.path ?: return uri)
            return try {
                FileProvider.getUriForFile(
                    context.applicationContext,
                    "${context.packageName}.fileprovider",
                    file
                )
            } catch (e: Exception) {
                Log.e("ViewerExportCoordinator", "Failed to convert file URI to content URI: $uri", e)
                uri
            }
        }
        return uri
    }

    fun shareTiff(uri: Uri) {
        val shareUri = toShareableUri(uri)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/tiff"
            putExtra(Intent.EXTRA_STREAM, shareUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            context.startActivity(Intent.createChooser(intent, "Share TIFF"))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "No app found to share TIFF.", Toast.LENGTH_SHORT).show()
        }
    }

    fun shareImages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val shareableUris = uris.map { toShareableUri(it) }
        val mimeType = if (shareableUris.size == 1) {
            val u = shareableUris[0]
            val type = context.contentResolver?.getType(u)
            type ?: when {
                u.toString().endsWith(".dng", true) -> "image/x-adobe-dng"
                u.toString().endsWith(".mp4", true) -> "video/mp4"
                u.toString().endsWith(".wav", true) -> "audio/wav"
                else -> "image/jpeg"
            }
        } else {
            val types = shareableUris.mapNotNull {
                context.contentResolver?.getType(it) ?: (if (it.toString().endsWith(".dng", true)) "image/x-adobe-dng" else "image/jpeg")
            }
            if (types.all { it.startsWith("image/") }) "image/*" else "*/*"
        }

        val intent = if (shareableUris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, shareableUris[0])
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = mimeType
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(shareableUris))
            }
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            context.startActivity(Intent.createChooser(intent, "Share"))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "No app found to share.", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 释放当前活跃的弹窗资源。
     */
    fun dismissActiveDialog() {
        activeExportDialog?.let {
            if (it.isShowing) {
                try { it.dismiss() } catch (_: Exception) {}
            }
        }
        activeExportDialog = null
    }
}
