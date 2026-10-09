package top.maary.darkbag.pipeline.sink

import android.content.Context
import android.util.Log
import top.maary.darkbag.pipeline.model.CaptureTaskSpec
import top.maary.darkbag.utils.HalfFrameManager
import top.maary.darkbag.utils.ImageSaver

/**
 * 半格模式产物交付 Sink。
 * 遵循半格模式的双帧契约：
 * 帧 1：由 Native Stage 2 写入私有临时文件后截获并暂存，严格禁止向 MediaStore 写入任何公开记录；
 * 帧 2：结合帧 1 执行拼接，渲染排版与时间戳，最后统一发布入库。
 */
class HalfFrameCacheSink : CaptureSink {

    companion object {
        private const val TAG = "HalfFrameCacheSink"
    }

    override fun prepareTargets(context: Context, spec: CaptureTaskSpec): SinkOutputTargets {
        val hfManager = HalfFrameManager(context)
        val shouldSaveRaw = hfManager.saveRaw

        val dngPathToUse = if (spec.rawOutputType == 0 && spec.linearDngPath.endsWith("_linear.dng")) {
            spec.linearDngPath.removeSuffix("_linear.dng") + ".dng"
        } else {
            spec.linearDngPath
        }

        return SinkOutputTargets(
            outJpgFd = -1,
            outJpgPath = if (spec.saveJpg) spec.fullResJpgPath else null,
            outDngFd = -1,
            outDngPath = if (shouldSaveRaw) dngPathToUse else null
        )
    }

    override suspend fun onImageExported(
        context: Context,
        spec: CaptureTaskSpec,
        success: Boolean,
        outputPath: String?,
        outputFd: Int
    ) {
        // 半格模式单帧不直接发布 MediaStore，等待 onComplete 统一处理拼接
        Log.d(TAG, "Frame exported to staging cache: $outputPath (success=$success)")
    }

    override suspend fun onRawExported(
        context: Context,
        spec: CaptureTaskSpec,
        success: Boolean,
        outputPath: String?,
        outputFd: Int
    ) {
        // DNG 落盘至私有路径，等待 onComplete 存库
        Log.d(TAG, "Raw exported to staging cache: $outputPath (success=$success)")
    }

    override suspend fun onComplete(
        context: Context,
        spec: CaptureTaskSpec,
        jpgSuccess: Boolean,
        rawSuccess: Boolean
    ) {
        val hfManager = HalfFrameManager(context)
        val shouldSaveRaw = hfManager.saveRaw

        val dngPathToUse = if (spec.rawOutputType == 0 && spec.linearDngPath.endsWith("_linear.dng")) {
            spec.linearDngPath.removeSuffix("_linear.dng") + ".dng"
        } else {
            spec.linearDngPath
        }

        ImageSaver.saveProcessedImage(
            context = context,
            inputBitmap = null,
            bmpPath = if (jpgSuccess) spec.fullResJpgPath else null,
            rotationDegrees = 0,
            zoomFactor = spec.zoomFactor,
            baseName = spec.baseName,
            linearDngPath = if (shouldSaveRaw && rawSuccess) dngPathToUse else null,
            saveJpg = jpgSuccess,
            saveRaw = shouldSaveRaw && rawSuccess,
            jpgFolderUri = spec.jpgFolderUri,
            rawFolderUri = spec.rawFolderUri,
            mirror = false,
            isFastPath = false,
            halfFrameMetadata = spec.hfMetadata,
            editConfig = spec.renderRecipe.editConfig,
            digitalGain = spec.renderRecipe.digitalGain,
            captureMetadata = spec.frameMetadata.captureMetadata,
            isAlreadyCropped = true,
            motionPhotoMp4Path = null, // 半格模式严格禁止动态照片
            motionPhotoStillPtsUs = 0L
        )
    }

    override suspend fun onError(context: Context, spec: CaptureTaskSpec, error: Throwable) {
        Log.e(TAG, "HalfFrameCacheSink encountered error for ${spec.taskId}", error)
    }
}
