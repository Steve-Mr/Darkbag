package top.maary.darkbag.pipeline.sink

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import top.maary.darkbag.pipeline.model.CaptureTaskSpec
import top.maary.darkbag.processor.ColorProcessor
import top.maary.darkbag.utils.ImageSaver

/**
 * 普通模式产物交付 Sink。
 * 针对系统相册利用 MediaStore Pending PFD 实现直接直写，零拷贝达到亚秒级 T2 首张出片耗时。
 */
class DirectMediaStoreSink(
    private val pfdProvider: ((context: Context, displayName: String, mimeType: String) -> Pair<ParcelFileDescriptor, Uri>?)? = null
) : CaptureSink {

    companion object {
        private const val TAG = "DirectMediaStoreSink"
    }

    private var pfdJpg: Pair<ParcelFileDescriptor, Uri>? = null
    private var pfdDng: Pair<ParcelFileDescriptor, Uri>? = null
    private var pfdJpgFinalized = false
    private var pfdDngFinalized = false

    private fun createPfd(context: Context, displayName: String, mimeType: String): Pair<ParcelFileDescriptor, Uri>? {
        return pfdProvider?.invoke(context, displayName, mimeType)
            ?: ImageSaver.createMediaStorePendingPfd(context, displayName, mimeType)
    }

    override fun prepareTargets(context: Context, spec: CaptureTaskSpec): SinkOutputTargets {
        if (spec.saveJpg && spec.jpgFolderUri == null && spec.motionPhotoMp4Path == null) {
            pfdJpg = createPfd(
                context = context,
                displayName = "${spec.baseName}.jpg",
                mimeType = "image/jpeg"
            )
        }

        val dngFileName = if (spec.rawOutputType == 0) "${spec.baseName}.dng" else "${spec.baseName}_linear.dng"
        if (spec.saveRaw && spec.rawFolderUri == null) {
            pfdDng = createPfd(
                context = context,
                displayName = dngFileName,
                mimeType = "image/x-adobe-dng"
            )
        }

        val dngPathToUse = if (spec.rawOutputType == 0 && spec.linearDngPath.endsWith("_linear.dng")) {
            spec.linearDngPath.removeSuffix("_linear.dng") + ".dng"
        } else {
            spec.linearDngPath
        }

        return SinkOutputTargets(
            outJpgFd = pfdJpg?.first?.fd ?: -1,
            outJpgPath = if (pfdJpg == null && spec.saveJpg) spec.fullResJpgPath else null,
            outDngFd = pfdDng?.first?.fd ?: -1,
            outDngPath = if (pfdDng == null && spec.saveRaw) dngPathToUse else null
        )
    }

    override suspend fun onImageExported(
        context: Context,
        spec: CaptureTaskSpec,
        success: Boolean,
        outputPath: String?,
        outputFd: Int
    ) {
        val jpgPfd = pfdJpg
        if (jpgPfd != null) {
            pfdJpgFinalized = true
            ImageSaver.finalizeMediaStorePendingPfd(
                context = context,
                pfdPair = jpgPfd,
                success = success,
                editConfig = spec.renderRecipe.editConfig,
                captureMetadata = spec.frameMetadata.captureMetadata
            )
            if (success) {
                val dngPathToUse = if (spec.rawOutputType == 0 && spec.linearDngPath.endsWith("_linear.dng")) {
                    spec.linearDngPath.removeSuffix("_linear.dng") + ".dng"
                } else {
                    spec.linearDngPath
                }
                ColorProcessor.backgroundSaveFlow.tryEmit(
                    ColorProcessor.BackgroundSaveEvent(
                        baseName = spec.baseName,
                        dngPath = if (spec.saveRaw) dngPathToUse else null,
                        jpgPath = null,
                        targetUri = jpgPfd.second.toString(),
                        zoomFactor = spec.zoomFactor,
                        orientation = spec.orientation,
                        saveJpg = true
                    )
                )
            }
        }
    }

    override suspend fun onRawExported(
        context: Context,
        spec: CaptureTaskSpec,
        success: Boolean,
        outputPath: String?,
        outputFd: Int
    ) {
        val dngPfd = pfdDng
        if (dngPfd != null) {
            pfdDngFinalized = true
            ImageSaver.finalizeMediaStorePendingPfd(
                context = context,
                pfdPair = dngPfd,
                success = success,
                editConfig = spec.renderRecipe.editConfig,
                captureMetadata = spec.frameMetadata.captureMetadata
            )
            if (success && pfdJpg == null) {
                val dngPathToUse = if (spec.rawOutputType == 0 && spec.linearDngPath.endsWith("_linear.dng")) {
                    spec.linearDngPath.removeSuffix("_linear.dng") + ".dng"
                } else {
                    spec.linearDngPath
                }
                ColorProcessor.backgroundSaveFlow.tryEmit(
                    ColorProcessor.BackgroundSaveEvent(
                        baseName = spec.baseName,
                        dngPath = dngPathToUse,
                        jpgPath = null,
                        targetUri = dngPfd.second.toString(),
                        zoomFactor = spec.zoomFactor,
                        orientation = spec.orientation,
                        saveJpg = false
                    )
                )
            }
        }
    }

    override suspend fun onComplete(
        context: Context,
        spec: CaptureTaskSpec,
        jpgSuccess: Boolean,
        rawSuccess: Boolean
    ) {
        val needsSecondaryJpg = spec.saveJpg && pfdJpg == null
        val needsSecondaryRaw = spec.saveRaw && pfdDng == null

        val dngPathToUse = if (spec.rawOutputType == 0 && spec.linearDngPath.endsWith("_linear.dng")) {
            spec.linearDngPath.removeSuffix("_linear.dng") + ".dng"
        } else {
            spec.linearDngPath
        }

        if (needsSecondaryJpg || needsSecondaryRaw) {
            ImageSaver.saveProcessedImage(
                context = context,
                inputBitmap = null,
                bmpPath = if (needsSecondaryJpg) spec.fullResJpgPath else null,
                rotationDegrees = 0,
                zoomFactor = spec.zoomFactor,
                baseName = spec.baseName,
                linearDngPath = if (needsSecondaryRaw) dngPathToUse else null,
                saveJpg = needsSecondaryJpg,
                saveRaw = needsSecondaryRaw,
                jpgFolderUri = spec.jpgFolderUri,
                rawFolderUri = spec.rawFolderUri,
                mirror = false,
                isFastPath = false,
                halfFrameMetadata = null,
                editConfig = spec.renderRecipe.editConfig,
                digitalGain = spec.renderRecipe.digitalGain,
                captureMetadata = spec.frameMetadata.captureMetadata,
                isAlreadyCropped = true,
                motionPhotoMp4Path = spec.motionPhotoMp4Path,
                motionPhotoStillPtsUs = spec.motionPhotoStillPtsUs
            )
        }
    }

    override suspend fun onError(context: Context, spec: CaptureTaskSpec, error: Throwable) {
        Log.e(TAG, "DirectMediaStoreSink encountered error for ${spec.taskId}", error)
        if (!pfdJpgFinalized) {
            pfdJpg?.let {
                pfdJpgFinalized = true
                ImageSaver.finalizeMediaStorePendingPfd(context, it, false, null, null)
            }
        }
        if (!pfdDngFinalized) {
            pfdDng?.let {
                pfdDngFinalized = true
                ImageSaver.finalizeMediaStorePendingPfd(context, it, false, null, null)
            }
        }
    }
}
