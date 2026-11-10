package top.maary.darkbag.pipeline.darkroom.model

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import top.maary.darkbag.models.BasicAdjustments
import top.maary.darkbag.models.CaptureMetadata
import top.maary.darkbag.models.EditConfig
import top.maary.darkbag.models.ImageGroup
import top.maary.darkbag.models.MultiCameraLensItem
import top.maary.darkbag.pipeline.darkroom.sink.ExportSink
import top.maary.darkbag.pipeline.model.RenderRecipe
import top.maary.darkbag.repository.ImageRepository
import java.io.File

/**
 * 冲洗产物格式。
 */
enum class RenderOutputFormat {
    BITMAP,
    JPEG,
    TIFF
}

/**
 * 半格排版与装配参数。
 */
data class HalfFrameComposeSpec(
    val layout: String? = null, // "SBS" or "TB"
    val isSwapped: Boolean = false,
    val showTimestamp: Boolean = false,
    val flareType: Int = -1,
    val time1: Long? = null,
    val time2: Long? = null,
    val downsample: Boolean = false
)

/**
 * 暗房冲洗源数据类型抽象。
 */
sealed interface DarkroomSource {
    /**
     * 单张照片源 (普通拍照、微距或多摄单镜头)。
     */
    data class Single(
        val dngUri: Uri? = null,
        val dngFile: File? = null,
        val dngBytes: ByteArray? = null,
        val baseName: String = "",
        val captureMetadata: CaptureMetadata? = null,
        val orientationDegrees: Int? = null,
        val rawWidth: Int = 0,
        val rawHeight: Int = 0
    ) : DarkroomSource {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as Single
            if (dngUri != other.dngUri) return false
            if (dngFile != other.dngFile) return false
            if (baseName != other.baseName) return false
            if (captureMetadata != other.captureMetadata) return false
            if (orientationDegrees != other.orientationDegrees) return false
            if (rawWidth != other.rawWidth) return false
            if (rawHeight != other.rawHeight) return false
            if (dngBytes != null) {
                if (other.dngBytes == null) return false
                if (!dngBytes.contentEquals(other.dngBytes)) return false
            } else if (other.dngBytes != null) return false
            return true
        }

        override fun hashCode(): Int {
            var result = dngUri?.hashCode() ?: 0
            result = 31 * result + (dngFile?.hashCode() ?: 0)
            result = 31 * result + (dngBytes?.contentHashCode() ?: 0)
            result = 31 * result + baseName.hashCode()
            result = 31 * result + (captureMetadata?.hashCode() ?: 0)
            result = 31 * result + (orientationDegrees ?: 0)
            result = 31 * result + rawWidth
            result = 31 * result + rawHeight
            return result
        }
    }

    /**
     * 半格双联照片源。
     */
    data class HalfFrame(
        val frame1: Single,
        val frame2: Single,
        val composeSpec: HalfFrameComposeSpec = HalfFrameComposeSpec(),
        val baseName: String = "",
        val cachedFrame1Bitmap: Bitmap? = null,
        val cachedFrame2Bitmap: Bitmap? = null
    ) : DarkroomSource

    /**
     * 多摄镜头组照片源。
     */
    data class MultiCamera(
        val lenses: List<MultiCameraLensItem>,
        val rootBaseName: String,
        val isReplacement: Boolean = false
    ) : DarkroomSource
}

/**
 * 冲洗结果封装。
 */
data class DarkroomRenderResult(
    val success: Boolean,
    val bitmap: Bitmap? = null,
    val frame1Bitmap: Bitmap? = null,
    val frame2Bitmap: Bitmap? = null,
    val uri: Uri? = null,
    val file: File? = null,
    val error: Throwable? = null
)

/**
 * 统一后期暗房冲洗任务规格 (DarkroomRenderSpec).
 * 遵循 Architectural Invariant 2: Domain Parameter Orthogonalization.
 * 聚合源数据、后期调色配方、排版规格、降采样配置及目标 ExportSink.
 */
data class DarkroomRenderSpec(
    val source: DarkroomSource,
    val editConfig: EditConfig = EditConfig(),
    val renderRecipe: RenderRecipe? = null,
    val outputFormat: RenderOutputFormat = RenderOutputFormat.JPEG,
    val downsampleFactor: Int = 1,
    val zoomFactor: Float = 1.0f,
    val useGpu: Boolean = false,
    val sink: ExportSink,
    val customTiffPath: String? = null
) {
    val effectiveBaseName: String
        get() = when (source) {
            is DarkroomSource.Single -> source.baseName
            is DarkroomSource.HalfFrame -> source.baseName
            is DarkroomSource.MultiCamera -> source.rootBaseName
        }

    fun effectiveAdjustments(index: Int): BasicAdjustments {
        return if (source is DarkroomSource.HalfFrame) {
            editConfig.adjustments?.getOrNull(index) ?: editConfig.toBasic()
        } else {
            editConfig.toBasic()
        }
    }

    companion object {
        /**
         * 便捷工厂方法：由 [ImageGroup] 快速构建领域规格 [DarkroomRenderSpec]。
         */
        fun fromImageGroup(
            context: Context,
            group: ImageGroup,
            editConfig: EditConfig,
            sink: ExportSink,
            outputFormat: RenderOutputFormat = RenderOutputFormat.JPEG,
            downsampleFactor: Int = 1,
            cachedBytes1: ByteArray? = null,
            cachedBytes2: ByteArray? = null,
            cachedFrame1Bitmap: Bitmap? = null,
            cachedFrame2Bitmap: Bitmap? = null,
            downsampleHalfFrame: Boolean = false,
            customTiffPath: String? = null,
            repository: ImageRepository? = null
        ): DarkroomRenderSpec {
            val repo = repository ?: ImageRepository(context)

            val source = when {
                group.isMultiCamera || group.multiCameraLenses.isNotEmpty() -> {
                    val lenses = if (group.multiCameraLenses.isNotEmpty()) {
                        group.multiCameraLenses
                    } else {
                        emptyList()
                    }
                    DarkroomSource.MultiCamera(
                        lenses = lenses,
                        rootBaseName = group.baseName
                    )
                }
                group.isHalfFrame() -> {
                    val dngUri1 = group.dngUri1
                    val dngUri2 = group.dngUri2
                    val time1 = dngUri1?.let { repo.getCaptureMetadata(it)?.dateTimeOriginal } ?: group.captureTime
                    val time2 = dngUri2?.let { repo.getCaptureMetadata(it)?.dateTimeOriginal } ?: group.captureTime

                    val frame1 = DarkroomSource.Single(
                        dngUri = dngUri1,
                        dngBytes = cachedBytes1,
                        baseName = "${group.baseName}_1",
                        captureMetadata = dngUri1?.let { repo.getCaptureMetadata(it) }
                    )
                    val frame2 = DarkroomSource.Single(
                        dngUri = dngUri2,
                        dngBytes = cachedBytes2,
                        baseName = "${group.baseName}_2",
                        captureMetadata = dngUri2?.let { repo.getCaptureMetadata(it) }
                    )
                    val composeSpec = HalfFrameComposeSpec(
                        layout = editConfig.hfLayout ?: group.hfLayout,
                        isSwapped = editConfig.isSwapped,
                        showTimestamp = editConfig.showTimestamp,
                        flareType = editConfig.flareType,
                        time1 = time1,
                        time2 = time2,
                        downsample = downsampleHalfFrame
                    )
                    DarkroomSource.HalfFrame(
                        frame1 = frame1,
                        frame2 = frame2,
                        composeSpec = composeSpec,
                        baseName = group.baseName,
                        cachedFrame1Bitmap = cachedFrame1Bitmap,
                        cachedFrame2Bitmap = cachedFrame2Bitmap
                    )
                }
                else -> {
                    val primaryUri = group.dngUri ?: group.dngUri1 ?: group.dngUri2
                    val primaryBytes = cachedBytes1 ?: cachedBytes2
                    DarkroomSource.Single(
                        dngUri = primaryUri,
                        dngBytes = primaryBytes,
                        baseName = group.baseName,
                        captureMetadata = primaryUri?.let { repo.getCaptureMetadata(it) }
                    )
                }
            }

            return DarkroomRenderSpec(
                source = source,
                editConfig = editConfig,
                outputFormat = outputFormat,
                downsampleFactor = downsampleFactor,
                zoomFactor = editConfig.zoomFactor,
                sink = sink,
                customTiffPath = customTiffPath
            )
        }
    }
}

/**
 * 将 EditConfig 转换为 BasicAdjustments 的通用扩展。
 */
fun EditConfig.toBasic(): BasicAdjustments {
    return BasicAdjustments(
        exposure = exposure,
        contrast = contrast,
        saturation = saturation,
        highlights = highlights,
        shadows = shadows,
        whites = whites,
        blacks = blacks,
        digitalGain = digitalGain
    )
}
