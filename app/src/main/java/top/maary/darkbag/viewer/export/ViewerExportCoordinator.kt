package top.maary.darkbag.viewer.export

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.util.Log
import android.view.LayoutInflater
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.*
import top.maary.darkbag.R
import top.maary.darkbag.fragments.RawVideoExportSheet
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
import top.maary.darkbag.viewer.strategy.ViewerMediaStrategy
import java.io.File

/**
 * 图像/视频导出与外发协调器 (ViewerExportCoordinator).
 * 封装导出进度弹窗调度、CinemaDNG/MP4 导出委托、JPEG 导出、TIFF 渲染外发及多图分享。
 * 严格遵循 Invariant 1 (CaptureSink & 统一导出路径隔离) 与 Invariant 4 (零 UI 阻塞与协程异步流)。
 */
class ViewerExportCoordinator(
    private val context: Context,
    private val scope: CoroutineScope,
    private val layoutInflater: LayoutInflater
) {

    var activeExportDialog: AlertDialog? = null

    private val cinemaDngExportHelper = ViewerCinemaDngExportHelper(
        context = context,
        scope = scope,
        showProgressDialog = { title, onCancel -> showExportProgressDialog(title, onCancel) },
        dismissIfActive = { dialog ->
            if (dialog.isShowing) {
                try { dialog.dismiss() } catch (_: Exception) {}
            }
            if (activeExportDialog === dialog) {
                activeExportDialog = null
            }
        }
    )

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
    ) = cinemaDngExportHelper.performExportCinemaDng(group, onComplete)

    /**
     * 执行 MP4 调色视频重编码导出。
     */
    fun performExportMp4(
        group: ImageGroup,
        config: EditConfig = group.editConfig ?: EditConfig(),
        targetResolution: Int = 1080,
        onComplete: suspend (baseName: String) -> Unit
    ) = cinemaDngExportHelper.performExportMp4(group, config, targetResolution, onComplete)

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
    ) = cinemaDngExportHelper.exportCinemaDngFrame(
        group = group,
        frameUri = frameUri,
        frameIndex = frameIndex,
        exportType = exportType,
        editConfig = editConfig,
        repository = repository,
        onStart = onStart,
        onFinished = onFinished
    )

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
                    top.maary.darkbag.ui.DarkbagFeedback.showSnackbar(context, R.string.toast_export_jpg_success)
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
            top.maary.darkbag.ui.DarkbagFeedback.showSnackbar(context, R.string.error_no_share_app)
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
            top.maary.darkbag.ui.DarkbagFeedback.showSnackbar(context, R.string.error_no_share_app)
        }
    }

    fun openExportHub(
        group: ImageGroup,
        currentEditConfig: EditConfig?,
        frameIndex: Int,
        repository: ImageRepository,
        fragmentManager: androidx.fragment.app.FragmentManager
    ) {
        scope.launch {
            val fullyLoaded = if (!group.metadataLoaded) repository.loadMetadata(group) else group
            val config = currentEditConfig ?: fullyLoaded.editConfig
            RawVideoExportSheet.newInstance(fullyLoaded, config?.log, config?.lut, frameIndex)
                .show(fragmentManager, RawVideoExportSheet.TAG)
        }
    }

    fun shareGroupMedia(
        group: ImageGroup,
        format: String,
        onShowHalfFrameShare: (ImageGroup) -> Unit
    ) {
        if (format == "DNG" && group.isHalfFrame()) {
            onShowHalfFrameShare(group)
            return
        }
        val uri = when (format) {
            "JPG" -> group.jpgUri
            "MP4" -> group.mp4VideoUri
            "DNG" -> group.dngUri ?: group.dngUri1 ?: group.dngUri2 ?: group.cinemaDngFirstFrameUri
            else -> group.firstAvailableUri
        }
        uri?.let { shareImages(listOf(it)) }
            ?: top.maary.darkbag.ui.DarkbagFeedback.showSnackbar(context, R.string.error_no_external_viewer)
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
