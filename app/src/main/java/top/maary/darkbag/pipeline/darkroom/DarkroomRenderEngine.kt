package top.maary.darkbag.pipeline.darkroom

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.maary.darkbag.fragments.SettingsFragment
import top.maary.darkbag.models.BasicAdjustments
import top.maary.darkbag.pipeline.composer.HalfFrameComposer
import top.maary.darkbag.pipeline.darkroom.model.DarkroomRenderResult
import top.maary.darkbag.pipeline.darkroom.model.DarkroomRenderSpec
import top.maary.darkbag.pipeline.darkroom.model.DarkroomSource
import top.maary.darkbag.pipeline.darkroom.model.RenderOutputFormat
import top.maary.darkbag.pipeline.darkroom.model.toBasic
import top.maary.darkbag.pipeline.darkroom.sink.BitmapPreviewSink
import top.maary.darkbag.pipeline.darkroom.sink.MediaStoreExportSink
import top.maary.darkbag.pipeline.darkroom.sink.SandboxExportSink
import top.maary.darkbag.pipeline.darkroom.sink.ShareFileSink
import top.maary.darkbag.processor.ColorProcessor
import top.maary.darkbag.utils.ImageUtils
import top.maary.darkbag.utils.LutManager
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream

/**
 * 统一后期暗房冲洗引擎 (DarkroomRenderEngine).
 * 严格遵循 Architectural Invariant 1 (CaptureSink / ExportSink 契约输出无关性)
 * 与 Invariant 2 (Domain Parameter Orthogonalization 参数正交化).
 *
 * 将分散在 ImageViewerFragment、PlaygroundViewerFragment 与 PlaygroundRepository
 * 的重复过程式调色、解码、半格拼接及 IO 导出完全收拢为此引擎统一驱动。
 */
object DarkroomRenderEngine {
    private const val TAG = "DarkroomRenderEngine"

    /**
     * 异步执行完整的后期冲洗任务。
     */
    suspend fun render(
        context: Context,
        spec: DarkroomRenderSpec
    ): DarkroomRenderResult = withContext(Dispatchers.IO) {
        try {
            val logName = spec.editConfig.log
            val logIndex = if (logName != null && logName != "None") {
                SettingsFragment.LOG_CURVES.indexOf(logName).takeIf { it >= 0 } ?: 0
            } else {
                0
            }

            val lutName = spec.editConfig.lut
            val lutPath = if (lutName != null && lutName != "None") {
                val lutManager = LutManager(context)
                File(lutManager.lutDir, lutName).takeIf { it.exists() }?.absolutePath
            } else {
                null
            }

            when (val source = spec.source) {
                is DarkroomSource.Single -> {
                    renderSingle(context, source, spec, logIndex, lutPath)
                }
                is DarkroomSource.HalfFrame -> {
                    renderHalfFrame(context, source, spec, logIndex, lutPath)
                }
                is DarkroomSource.MultiCamera -> {
                    renderMultiCamera(context, source, spec, logIndex, lutPath)
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to execute DarkroomRenderSpec", e)
            spec.sink.onError(context, spec, e)
            DarkroomRenderResult(success = false, error = e)
        }
    }

    private suspend fun renderSingle(
        context: Context,
        source: DarkroomSource.Single,
        spec: DarkroomRenderSpec,
        logIndex: Int,
        lutPath: String?
    ): DarkroomRenderResult {
        val adj = spec.effectiveAdjustments(0)
        val isPreview = spec.outputFormat == RenderOutputFormat.BITMAP || spec.sink is BitmapPreviewSink

        if (isPreview) {
            val bmp = renderSingleToBitmap(
                context = context,
                single = source,
                spec = spec,
                adj = adj,
                logIndex = logIndex,
                lutPath = lutPath,
                downsampleFactor = spec.downsampleFactor
            )
            return if (bmp != null) {
                deliverBitmapResult(context, spec, bmp)
            } else {
                val err = IllegalStateException("ColorProcessor.processRaw failed to render bitmap")
                spec.sink.onError(context, spec, err)
                DarkroomRenderResult(success = false, error = err)
            }
        }

        if (spec.outputFormat == RenderOutputFormat.TIFF) {
            val targetTiff = spec.customTiffPath?.let { File(it) } ?: File(
                context.cacheDir,
                "temp_export_${System.currentTimeMillis()}.tif"
            )
            targetTiff.parentFile?.mkdirs()
            val success = renderSingleToFile(
                context = context,
                single = source,
                spec = spec,
                adj = adj,
                logIndex = logIndex,
                lutPath = lutPath,
                outputJpgPath = null,
                outputTiffPath = targetTiff.absolutePath
            )
            return if (success && targetTiff.exists()) {
                val uri = spec.sink.onFileExported(context, targetTiff, "image/tiff", spec)
                val res = DarkroomRenderResult(success = true, uri = uri, file = targetTiff)
                spec.sink.onComplete(context, spec, res)
                res
            } else {
                val err = IllegalStateException("ColorProcessor.processRaw failed to export TIFF")
                spec.sink.onError(context, spec, err)
                DarkroomRenderResult(success = false, error = err)
            }
        }

        // JPEG 导出：若无需缩放裁剪且支持直接文件输出，使用零 Bitmap 拷贝快路径
        if (spec.outputFormat == RenderOutputFormat.JPEG && spec.zoomFactor <= 1.05f) {
            val tempJpgFile = File(context.cacheDir, "darkroom_export_${System.currentTimeMillis()}.jpg")
            val success = renderSingleToFile(
                context = context,
                single = source,
                spec = spec,
                adj = adj,
                logIndex = logIndex,
                lutPath = lutPath,
                outputJpgPath = tempJpgFile.absolutePath,
                outputTiffPath = null
            )
            if (success && tempJpgFile.exists()) {
                val uri = spec.sink.onFileExported(context, tempJpgFile, "image/jpeg", spec)
                val res = DarkroomRenderResult(success = true, uri = uri, file = tempJpgFile)
                spec.sink.onComplete(context, spec, res)
                tempJpgFile.delete()
                return res
            }
        }

        // 缩放裁剪或回退路径：渲染全尺寸 Bitmap 再交付
        val fullBmp = renderSingleToBitmap(
            context = context,
            single = source,
            spec = spec,
            adj = adj,
            logIndex = logIndex,
            lutPath = lutPath,
            downsampleFactor = 1
        )
        return if (fullBmp != null) {
            deliverBitmapResult(context, spec, fullBmp)
        } else {
            val err = IllegalStateException("ColorProcessor.processRaw failed to render full bitmap")
            spec.sink.onError(context, spec, err)
            DarkroomRenderResult(success = false, error = err)
        }
    }

    private suspend fun renderHalfFrame(
        context: Context,
        source: DarkroomSource.HalfFrame,
        spec: DarkroomRenderSpec,
        logIndex: Int,
        lutPath: String?
    ): DarkroomRenderResult {
        val adj1 = spec.effectiveAdjustments(0)
        val adj2 = spec.effectiveAdjustments(1)

        val isPreview = spec.outputFormat == RenderOutputFormat.BITMAP || spec.sink is BitmapPreviewSink
        val ds = if (isPreview) {
            if (spec.downsampleFactor > 1) spec.downsampleFactor else 0
        } else {
            1
        }

        val bmp1 = source.cachedFrame1Bitmap ?: renderSingleToBitmap(
            context = context,
            single = source.frame1,
            spec = spec,
            adj = adj1,
            logIndex = logIndex,
            lutPath = lutPath,
            downsampleFactor = ds
        )
        val bmp2 = source.cachedFrame2Bitmap ?: renderSingleToBitmap(
            context = context,
            single = source.frame2,
            spec = spec,
            adj = adj2,
            logIndex = logIndex,
            lutPath = lutPath,
            downsampleFactor = ds
        )

        val composeSpec = source.composeSpec
        val finalComposite = HalfFrameComposer.compose(
            frame1 = bmp1,
            frame2 = bmp2,
            layout = composeSpec.layout,
            isSwapped = composeSpec.isSwapped,
            showTimestamp = composeSpec.showTimestamp,
            flareType = composeSpec.flareType,
            time1 = composeSpec.time1 ?: System.currentTimeMillis(),
            time2 = composeSpec.time2 ?: System.currentTimeMillis(),
            downsample = composeSpec.downsample
        )

        // 回收中间创建的 Bitmap (若外部未提供缓存且不是最终合成产物)
        if (source.cachedFrame1Bitmap == null && bmp1 != finalComposite) {
            bmp1?.recycle()
        }
        if (source.cachedFrame2Bitmap == null && bmp2 != finalComposite) {
            bmp2?.recycle()
        }

        if (finalComposite == null) {
            val error = IllegalStateException("HalfFrameComposer returned null composite bitmap")
            spec.sink.onError(context, spec, error)
            return DarkroomRenderResult(success = false, error = error)
        }

        if (spec.outputFormat == RenderOutputFormat.TIFF) {
            val targetTiff = spec.customTiffPath?.let { File(it) } ?: File(
                context.cacheDir,
                "temp_export_${System.currentTimeMillis()}.tif"
            )
            targetTiff.parentFile?.mkdirs()
            val meta = source.frame1.captureMetadata ?: source.frame2.captureMetadata ?: top.maary.darkbag.models.CaptureMetadata()
            ColorProcessor.saveBitmapToTiff(finalComposite, targetTiff.absolutePath, meta)
            val uri = spec.sink.onFileExported(context, targetTiff, "image/tiff", spec)
            finalComposite.recycle()
            val res = DarkroomRenderResult(success = true, uri = uri, file = targetTiff)
            spec.sink.onComplete(context, spec, res)
            return res
        }

        return deliverBitmapResult(
            context = context,
            spec = spec,
            bitmap = finalComposite,
            f1 = bmp1,
            f2 = bmp2
        )
    }

    private suspend fun renderMultiCamera(
        context: Context,
        source: DarkroomSource.MultiCamera,
        spec: DarkroomRenderSpec,
        logIndex: Int,
        lutPath: String?
    ): DarkroomRenderResult {
        var anySuccess = false
        var lastUri: Uri? = null
        for (i in source.lenses.indices) {
            val lens = source.lenses[i]
            val dngUri = lens.dngUri ?: continue
            val single = DarkroomSource.Single(
                dngUri = dngUri,
                baseName = "${source.rootBaseName}_MULTI_${lens.lensTag}"
            )
            val lensSink = spec.sink.createChildSink(
                childBaseName = single.baseName,
                childTargetUri = if (source.isReplacement) lens.jpgUri else null,
                isReplacement = source.isReplacement
            )
            val lensSpec = spec.copy(
                source = single,
                sink = lensSink
            )
            val res = renderSingle(context, single, lensSpec, logIndex, lutPath)
            if (res.success) {
                anySuccess = true
                lastUri = res.uri
            }
        }
        val overall = DarkroomRenderResult(success = anySuccess, uri = lastUri)
        spec.sink.onComplete(context, spec, overall)
        return overall
    }

    private suspend fun deliverBitmapResult(
        context: Context,
        spec: DarkroomRenderSpec,
        bitmap: Bitmap,
        f1: Bitmap? = null,
        f2: Bitmap? = null
    ): DarkroomRenderResult {
        val retainedBySink = spec.sink.onBitmapExported(context, bitmap, spec)
        val uri = when (val sink = spec.sink) {
            is MediaStoreExportSink -> sink.savedUri
            else -> null
        }
        val file = when (val sink = spec.sink) {
            is SandboxExportSink -> sink.exportedFile
            is ShareFileSink -> sink.deliveredFile
            else -> null
        }
        val result = DarkroomRenderResult(
            success = true,
            bitmap = bitmap,
            frame1Bitmap = f1,
            frame2Bitmap = f2,
            uri = uri,
            file = file
        )
        spec.sink.onComplete(context, spec, result)
        if (!retainedBySink && spec.sink !is BitmapPreviewSink) {
            bitmap.recycle()
        }
        return result
    }

    private fun renderSingleToBitmap(
        context: Context,
        single: DarkroomSource.Single,
        spec: DarkroomRenderSpec,
        adj: BasicAdjustments,
        logIndex: Int,
        lutPath: String?,
        downsampleFactor: Int
    ): Bitmap? {
        val bytes = resolveDngBytes(context, single) ?: return null
        val rotDegrees = resolveRotationDegrees(context, single, bytes)
        val (fullW, fullH) = resolveDimensions(context, single, bytes, rotDegrees)

        val ds = if (downsampleFactor > 1) {
            downsampleFactor
        } else if (downsampleFactor <= 0) {
            ImageUtils.calculateInSampleSize(fullW, fullH, 1024, 1024)
        } else {
            1
        }

        val scaledW = (fullW / ds / spec.zoomFactor).toInt().coerceAtLeast(1)
        val scaledH = (fullH / ds / spec.zoomFactor).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(scaledW, scaledH, Bitmap.Config.ARGB_8888)

        val ret = ColorProcessor.processRaw(
            dngData = bytes,
            targetLog = logIndex,
            lutPath = lutPath,
            exposure = adj.exposure,
            contrast = adj.contrast,
            saturation = adj.saturation,
            highlights = adj.highlights,
            shadows = adj.shadows,
            whites = adj.whites,
            blacks = adj.blacks,
            digitalGain = adj.digitalGain,
            outputJpgPath = null,
            outputTiffPath = null,
            useGpu = spec.useGpu,
            orientation = rotDegrees,
            mirror = false,
            outputBitmap = bitmap,
            downsampleFactor = ds,
            zoomFactor = spec.zoomFactor,
            metadata = single.captureMetadata,
            colorEngineMode = spec.editConfig.colorEngineMode
        )

        return if (ret >= 0) {
            bitmap
        } else {
            bitmap.recycle()
            null
        }
    }

    private fun renderSingleToFile(
        context: Context,
        single: DarkroomSource.Single,
        spec: DarkroomRenderSpec,
        adj: BasicAdjustments,
        logIndex: Int,
        lutPath: String?,
        outputJpgPath: String?,
        outputTiffPath: String?
    ): Boolean {
        val bytes = resolveDngBytes(context, single) ?: return false
        val rotDegrees = resolveRotationDegrees(context, single, bytes)

        val ret = ColorProcessor.processRaw(
            dngData = bytes,
            targetLog = logIndex,
            lutPath = lutPath,
            exposure = adj.exposure,
            contrast = adj.contrast,
            saturation = adj.saturation,
            highlights = adj.highlights,
            shadows = adj.shadows,
            whites = adj.whites,
            blacks = adj.blacks,
            digitalGain = adj.digitalGain,
            outputJpgPath = outputJpgPath,
            outputTiffPath = outputTiffPath,
            useGpu = spec.useGpu,
            orientation = rotDegrees,
            mirror = false,
            outputBitmap = null,
            downsampleFactor = 1,
            zoomFactor = spec.zoomFactor,
            metadata = single.captureMetadata,
            colorEngineMode = spec.editConfig.colorEngineMode
        )

        return ret >= 0
    }

    fun resolveDngBytes(context: Context, single: DarkroomSource.Single): ByteArray? {
        if (single.dngBytes != null && single.dngBytes.isNotEmpty()) {
            return single.dngBytes
        }
        if (single.dngFile != null && single.dngFile.exists()) {
            return try {
                FileInputStream(single.dngFile).use { it.readBytes() }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read dngFile: ${single.dngFile}", e)
                null
            }
        }
        if (single.dngUri != null) {
            return try {
                context.contentResolver.openFileDescriptor(single.dngUri, "r")?.use { pfd ->
                    FileInputStream(pfd.fileDescriptor).use { it.readBytes() }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read dngUri: ${single.dngUri}", e)
                null
            }
        }
        return null
    }

    fun resolveRotationDegrees(context: Context, single: DarkroomSource.Single, bytes: ByteArray?): Int {
        if (single.orientationDegrees != null) return single.orientationDegrees
        val orientation = try {
            when {
                single.dngFile != null && single.dngFile.exists() -> {
                    ExifInterface(single.dngFile.absolutePath).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL
                    )
                }
                single.dngUri != null -> {
                    context.contentResolver.openInputStream(single.dngUri)?.use { input ->
                        ExifInterface(input).getAttributeInt(
                            ExifInterface.TAG_ORIENTATION,
                            ExifInterface.ORIENTATION_NORMAL
                        )
                    } ?: ExifInterface.ORIENTATION_NORMAL
                }
                bytes != null && bytes.isNotEmpty() -> {
                    ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL
                    )
                }
                else -> ExifInterface.ORIENTATION_NORMAL
            }
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }

        return when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    }

    fun resolveDimensions(
        context: Context,
        single: DarkroomSource.Single,
        bytes: ByteArray?,
        rotDegrees: Int
    ): Pair<Int, Int> {
        var rawW = single.rawWidth
        var rawH = single.rawHeight

        if (rawW <= 0 || rawH <= 0) {
            try {
                when {
                    single.dngFile != null && single.dngFile.exists() -> {
                        val exif = ExifInterface(single.dngFile.absolutePath)
                        rawW = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
                        rawH = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)
                    }
                    single.dngUri != null -> {
                        context.contentResolver.openFileDescriptor(single.dngUri, "r")?.use { pfd ->
                            val exif = ExifInterface(pfd.fileDescriptor)
                            rawW = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
                            rawH = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)
                        }
                    }
                    bytes != null && bytes.isNotEmpty() -> {
                        val exif = ExifInterface(ByteArrayInputStream(bytes))
                        rawW = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
                        rawH = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)
                    }
                }
            } catch (e: Exception) {
                // Ignore
            }
        }

        if (rawW <= 0 || rawH <= 0) {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            if (bytes != null && bytes.isNotEmpty()) {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            } else if (single.dngFile != null && single.dngFile.exists()) {
                BitmapFactory.decodeFile(single.dngFile.absolutePath, options)
            } else if (single.dngUri != null) {
                try {
                    context.contentResolver.openInputStream(single.dngUri)?.use {
                        BitmapFactory.decodeStream(it, null, options)
                    }
                } catch (e: Exception) {
                    // Ignore
                }
            }
            rawW = options.outWidth
            rawH = options.outHeight
        }

        val isRotated90or270 = (rotDegrees == 90 || rotDegrees == 270)
        val fullW = if (isRotated90or270) rawH else rawW
        val fullH = if (isRotated90or270) rawW else rawH
        return Pair(fullW.coerceAtLeast(1), fullH.coerceAtLeast(1))
    }
}
