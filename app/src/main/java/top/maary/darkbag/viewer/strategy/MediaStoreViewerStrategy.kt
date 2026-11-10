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
import top.maary.darkbag.fragments.SettingsFragment
import top.maary.darkbag.models.EditConfig
import top.maary.darkbag.models.ImageGroup
import top.maary.darkbag.pipeline.darkroom.DarkroomRenderEngine
import top.maary.darkbag.pipeline.darkroom.model.DarkroomRenderSpec
import top.maary.darkbag.pipeline.darkroom.model.RenderOutputFormat
import top.maary.darkbag.pipeline.darkroom.sink.MediaStoreExportSink
import top.maary.darkbag.repository.ImageRepository
import top.maary.darkbag.utils.HalfFrameManager

class MediaStoreViewerStrategy : ViewerMediaStrategy {

    companion object {
        private const val TAG = "MediaStoreViewerStrategy"
    }

    override val isGallerySupported: Boolean = true
    override val isSandbox: Boolean = false

    override suspend fun loadInitialGroups(
        context: Context,
        args: Bundle?,
        repository: ImageRepository
    ): List<ImageGroup>? {
        // MediaStore 数据集由 DarkroomGalleryViewModel 响应式流加载，此处返回 null
        return null
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

            if (group.isRawVideo || group.rawVideoUri != null) {
                return@withContext saveRawVideoEdit(context, group, finalConfig, isReplacement, repository)
            }

            val jpgFolderUri = context.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
                .getString(SettingsFragment.KEY_JPG_STORAGE_URI, null)

            val mediaStoreSink = MediaStoreExportSink(
                baseName = group.baseName,
                targetUri = if (isReplacement) group.jpgUri else null,
                isReplacement = isReplacement,
                jpgFolderUri = if (isReplacement) null else jpgFolderUri
            )

            val spec = DarkroomRenderSpec.fromImageGroup(
                context = context,
                group = group,
                editConfig = finalConfig,
                sink = mediaStoreSink,
                outputFormat = RenderOutputFormat.JPEG,
                cachedBytes1 = cachedBytes1,
                cachedBytes2 = cachedBytes2,
                downsampleHalfFrame = HalfFrameManager(context).downsample,
                repository = repository
            )

            val res = DarkroomRenderEngine.render(context, spec)
            res.success
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to save MediaStore edit", e)
            false
        }
    }

    private suspend fun saveRawVideoEdit(
        context: Context,
        group: ImageGroup,
        config: EditConfig,
        isReplacement: Boolean,
        repository: ImageRepository
    ): Boolean = withContext(Dispatchers.IO) {
        val rawVideoUri = group.rawVideoUri ?: return@withContext false
        try {
            // 1. In-place update .rawvid FileHeader with latest Log, LUT, and adjustments
            try {
                context.contentResolver.openFileDescriptor(rawVideoUri, "rw")?.use { rwFd ->
                    top.maary.darkbag.rawvideo.RawVideoNative.nativeUpdateHeaderMetadata(
                        fd = rwFd.fd,
                        activeLog = config.log,
                        activeLut = config.lut,
                        exposure = config.exposure,
                        contrast = config.contrast,
                        saturation = config.saturation
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to in-place update rawvid header", e)
            }

            // 2. If companion JPG exists, re-render it with orientation and save
            if (group.jpgUri != null) {
                val pfd = context.contentResolver.openFileDescriptor(rawVideoUri, "r")
                pfd?.use { parcelFd ->
                    val handle = top.maary.darkbag.rawvideo.RawVideoNative.nativeOpenReaderFd(parcelFd.fd)
                    if (handle != 0L) {
                        try {
                            val header = top.maary.darkbag.rawvideo.RawVideoNative.readHeader(handle)
                            if (header != null) {
                                val bayerBuf = java.nio.ByteBuffer.allocateDirect(header.width * header.height * 2)
                                val meta = LongArray(3)
                                val readBytes = top.maary.darkbag.rawvideo.RawVideoNative.nativeReadFrame(handle, 0, meta, bayerBuf)
                                if (readBytes > 0) {
                                    val bmp = android.graphics.Bitmap.createBitmap(header.width, header.height, android.graphics.Bitmap.Config.ARGB_8888)
                                    val targetLogIndex = if (config.log != null && config.log != "None") {
                                        SettingsFragment.LOG_CURVES.indexOf(config.log).takeIf { it >= 0 } ?: -1
                                    } else -1

                                    val lutManager = top.maary.darkbag.utils.LutManager(context)
                                    val lutPath = if (config.lut != null && config.lut != "None" && config.lut.isNotBlank()) {
                                        val f = java.io.File(lutManager.lutDir, config.lut)
                                        if (f.exists()) f.absolutePath else {
                                            val f2 = java.io.File(java.io.File(context.filesDir, "luts"), config.lut)
                                            if (f2.exists()) f2.absolutePath else null
                                        }
                                    } else null

                                    top.maary.darkbag.rawvideo.RawVideoNative.nativeDebayerFrameToBitmap(
                                        bayerBuffer = bayerBuf,
                                        width = header.width,
                                        height = header.height,
                                        orientation = header.orientation,
                                        cfaPattern = header.cfaPattern,
                                        whiteLevel = header.whiteLevel,
                                        blackLevel = header.blackLevel.firstOrNull() ?: 64f,
                                        neutralPoint = header.neutralPoint,
                                        targetLog = targetLogIndex,
                                        lutPath = lutPath,
                                        exposure = config.exposure,
                                        contrast = config.contrast,
                                        saturation = config.saturation,
                                        outBitmap = bmp
                                    )

                                    val orientation = try {
                                        context.contentResolver.openFileDescriptor(group.jpgUri, "r")?.use { jPfd ->
                                            androidx.exifinterface.media.ExifInterface(jPfd.fileDescriptor).getAttributeInt(
                                                androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                                                androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
                                            )
                                        } ?: androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
                                    } catch (e: Exception) { androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL }

                                    val rotDegrees = when (orientation) {
                                        androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
                                        androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
                                        androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
                                        else -> 0
                                    }

                                    val finalJpgBmp = if (rotDegrees != 0) {
                                        val m = android.graphics.Matrix().apply { postRotate(rotDegrees.toFloat()) }
                                        val r = android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                                        if (r != bmp) bmp.recycle()
                                        r
                                    } else bmp

                                    val rootBaseName = top.maary.darkbag.utils.DarkbagIdentity.prefixedBaseName(group.baseName)
                                    val baseName = if (isReplacement) {
                                        rootBaseName
                                    } else {
                                        val editTimestamp = java.text.SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", java.util.Locale.US).format(java.util.Date())
                                        "${rootBaseName}_edited_$editTimestamp"
                                    }
                                    val targetUri = if (isReplacement) group.jpgUri else null
                                    val jpgFolderUri = context.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
                                        .getString(SettingsFragment.KEY_JPG_STORAGE_URI, null)

                                    val captureMetadata = group.jpgUri?.let { repository.getCaptureMetadata(it) }

                                    top.maary.darkbag.utils.ImageSaver.saveProcessedImage(
                                        context = context,
                                        inputBitmap = finalJpgBmp,
                                        bmpPath = null,
                                        rotationDegrees = 0,
                                        zoomFactor = 1.0f,
                                        baseName = baseName,
                                        linearDngPath = null,
                                        saveJpg = true,
                                        saveRaw = false,
                                        targetUri = targetUri,
                                        jpgFolderUri = if (isReplacement) null else jpgFolderUri,
                                        editConfig = config,
                                        isAlreadyStitched = true,
                                        captureMetadata = captureMetadata
                                    )
                                    finalJpgBmp.recycle()
                                }
                            }
                        } finally {
                            top.maary.darkbag.rawvideo.RawVideoNative.nativeCloseReader(handle)
                        }
                    }
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save RAW video adjustments", e)
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
        val urisToDelete = if (targetUris != null) {
            targetUris
        } else if (deleteGroup) {
            listOfNotNull(
                group.jpgUri,
                group.dngUri,
                group.dngUri1,
                group.dngUri2,
                group.rawVideoUri,
                group.mp4VideoUri,
                group.cinemaDngFirstFrameUri,
                group.cinemaDngAudioUri
            ) + group.derivativeJpgUris + group.derivativeMp4Uris + group.multiJpgUris + group.multiDngUris
        } else {
            listOfNotNull(group.firstAvailableUri)
        }

        var anyDeleted = false
        for (u in urisToDelete) {
            if (u.scheme == "content") {
                try {
                    val count = context.contentResolver.delete(u, null, null)
                    if (count > 0) anyDeleted = true
                } catch (e: Exception) {
                    Log.w(TAG, "ContentResolver delete failed for $u", e)
                }
            } else if (u.scheme == "file") {
                u.path?.let { p ->
                    val f = java.io.File(p)
                    if (f.exists() && f.delete()) anyDeleted = true
                }
            }
        }
        anyDeleted
    }

    override fun showSaveMenu(
        context: Context,
        anchor: View,
        currentGroup: ImageGroup,
        onAction: (ViewerSaveAction) -> Unit
    ) {
        val popup = PopupMenu(context, anchor)
        popup.menu.add(0, 1, 0, context.getString(R.string.save_replacement)).apply {
            setIcon(R.drawable.ic_save)
        }
        popup.menu.add(0, 2, 0, context.getString(R.string.save_as_new)).apply {
            setIcon(R.drawable.ic_save_as)
        }
        popup.menu.add(0, 3, 0, context.getString(R.string.share_as_tiff)).apply {
            setIcon(R.drawable.ic_photo)
        }

        try {
            val fieldMPopup = PopupMenu::class.java.getDeclaredField("mPopup")
            fieldMPopup.isAccessible = true
            val mPopup = fieldMPopup.get(popup)
            mPopup.javaClass.getDeclaredMethod("setForceShowIcon", Boolean::class.java).invoke(mPopup, true)
        } catch (_: Exception) {}

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> onAction(ViewerSaveAction.SAVE_REPLACE)
                2 -> onAction(ViewerSaveAction.SAVE_AS_NEW)
                3 -> onAction(ViewerSaveAction.SHARE_TIFF)
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
        // MediaStore 模式走标准的确认弹窗提示
        return false
    }
}
