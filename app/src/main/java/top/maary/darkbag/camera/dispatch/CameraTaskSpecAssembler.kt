package top.maary.darkbag.camera.dispatch

import android.content.Context
import android.content.SharedPreferences
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import top.maary.darkbag.calibration.LensCalibrationStore
import top.maary.darkbag.fragments.SettingsFragment
import top.maary.darkbag.models.BasicAdjustments
import top.maary.darkbag.models.CaptureMetadata
import top.maary.darkbag.models.EditConfig
import top.maary.darkbag.models.StandardTimingTracker
import top.maary.darkbag.pipeline.model.CaptureFrameMetadata
import top.maary.darkbag.pipeline.model.CaptureTaskSpec
import top.maary.darkbag.pipeline.model.HardwareProfile
import top.maary.darkbag.pipeline.model.RenderRecipe
import top.maary.darkbag.pipeline.sink.CaptureSink
import top.maary.darkbag.utils.DarkbagIdentity
import top.maary.darkbag.utils.HalfFrameManager
import top.maary.darkbag.utils.HalfFrameSessionStore
import top.maary.darkbag.utils.LensInfo
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID

/**
 * Cohesive parameter aggregate for assembling a [CaptureTaskSpec] across single-frame,
 * non-streaming burst, and streaming HDR+ burst pipelines (AGENTS.md Invariant 2).
 */
data class TaskSpecAssemblyRequest(
    val width: Int,
    val height: Int,
    val orientation: Int,
    val zoomFactor: Float,
    val mirror: Boolean,
    val isSingleFrame: Boolean,
    val isHdrPlus: Boolean,
    val fusionModeOverride: Int? = null,
    val targetCharId: String,
    val chars: CameraCharacteristics?,
    val captureResult: CaptureResult?,
    val captureMetadata: CaptureMetadata,
    val cfa: Int,
    val whiteLevel: Int,
    val blackLevelPattern: IntArray,
    val wb: FloatArray,
    val ccm: FloatArray,
    val ccmAlt: FloatArray? = null,
    val exportMatrixAB: Boolean = false,
    val useSensorColorMatrix: Boolean = true,
    val calibration: SensorCalibration,
    val lensShadingMapData: FloatArray?,
    val lensShadingRows: Int,
    val lensShadingCols: Int,
    val noiseProfile: DoubleArray?,
    val digitalGain: Float,
    val faithfulHighlights: Boolean,
    val hfMetadata: HalfFrameManager.Metadata? = null,
    val timing: StandardTimingTracker? = null,
    val motionPhotoMp4Path: String? = null,
    val motionPhotoStillPtsUs: Long = 0L,
    val sink: CaptureSink? = null
)

/**
 * Unifies [HardwareProfile], [CaptureFrameMetadata], [EditConfig], [RenderRecipe],
 * [CaptureTaskSpec], and failure spec assembly across all capture pipelines.
 */
class CameraTaskSpecAssembler(
    private val metadataExtractor: CameraCaptureMetadataExtractor
) {
    fun resolveEffectiveZoom(
        capturedLens: LensInfo?,
        fallbackLens: LensInfo?,
        defaultZoom: Float = 1.0f
    ): Float {
        return when {
            capturedLens != null -> {
                if (capturedLens.isZoomPreset && capturedLens.targetZoomRatio != null) capturedLens.targetZoomRatio!! else 1.0f
            }
            fallbackLens?.isZoomPreset == true && fallbackLens.targetZoomRatio != null -> fallbackLens.targetZoomRatio!!
            else -> defaultZoom
        }
    }

    fun buildCaptureBaseName(
        hfMetadata: HalfFrameManager.Metadata?,
        isHdrPlus: Boolean,
        nowMillis: Long = System.currentTimeMillis()
    ): String {
        val suffixHdr = if (isHdrPlus) "_HDRPLUS" else ""
        return if (hfMetadata != null) {
            val hfSuffix = if (hfMetadata.frame1BaseName != null) "_HF2" else "_HF1"
            val group = hfMetadata.frame1BaseName ?: SimpleDateFormat(FILENAME_PATTERN, Locale.US).format(hfMetadata.captureTimeMillis)
            DarkbagIdentity.prefixedBaseName(group + hfSuffix + suffixHdr)
        } else {
            DarkbagIdentity.prefixedBaseName(SimpleDateFormat(FILENAME_PATTERN, Locale.US).format(nowMillis) + suffixHdr)
        }
    }

    fun resolveNativeLutPath(context: Context, activeLutName: String?): String? {
        if (activeLutName.isNullOrBlank()) return null
        val lutFile = File(File(context.filesDir, "luts"), activeLutName)
        return if (lutFile.exists()) lutFile.absolutePath else null
    }

    fun resolveFusionModeInt(prefs: SharedPreferences): Int {
        return when (prefs.getString(SettingsFragment.KEY_HDR_FUSION_MODE, SettingsFragment.HDR_FUSION_SABRE)) {
            SettingsFragment.HDR_FUSION_SPATIAL_RCD -> 1
            SettingsFragment.HDR_FUSION_SABRE -> 2
            SettingsFragment.HDR_FUSION_CLASSIC -> 3
            else -> 0
        }
    }

    suspend fun awaitPendingMotionPhoto(
        pendingTaskProvider: () -> CompletableDeferred<Pair<String?, Long>>?,
        clearPendingTask: () -> Unit,
        timeoutMs: Long = 3500L
    ): Pair<String?, Long> {
        val task = pendingTaskProvider() ?: return Pair(null, 0L)
        clearPendingTask()
        val res = withTimeoutOrNull(timeoutMs) { task.await() }
        return Pair(res?.first, res?.second ?: 0L)
    }

    fun buildHardwareProfile(
        context: Context,
        request: TaskSpecAssemblyRequest
    ): HardwareProfile {
        val chars = request.chars
        val result = request.captureResult
        return HardwareProfile(
            lensId = request.targetCharId,
            cfaPattern = request.cfa,
            whiteLevel = request.whiteLevel,
            blackLevelPattern = request.blackLevelPattern,
            dynamicBlackLevel = result?.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL),
            colorMatrix1 = request.calibration.colorMatrix1,
            colorMatrix2 = request.calibration.colorMatrix2,
            forwardMatrix1 = request.calibration.forwardMatrix1,
            forwardMatrix2 = request.calibration.forwardMatrix2,
            calibrationIlluminant1 = request.calibration.calibrationIlluminant1,
            calibrationIlluminant2 = request.calibration.calibrationIlluminant2,
            activeArray = metadataExtractor.extractActiveArray(chars),
            noiseProfile = request.noiseProfile,
            useSensorColorMatrix = request.useSensorColorMatrix,
            lensDistortion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) chars?.get(CameraCharacteristics.LENS_DISTORTION) else null,
            intrinsicCalibration = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) chars?.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION) else null,
            focalLength = result?.get(CaptureResult.LENS_FOCAL_LENGTH)
                ?: chars?.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
                ?: 0f,
            customCalibration = LensCalibrationStore.getInstance(context).getCalibration(request.targetCharId)
        )
    }

    fun buildCaptureFrameMetadata(
        timestamp: Long,
        request: TaskSpecAssemblyRequest
    ): CaptureFrameMetadata {
        return CaptureFrameMetadata(
            timestamp = timestamp,
            iso = request.captureMetadata.iso ?: 100,
            exposureTimeNs = request.captureMetadata.exposureTime ?: 10_000_000L,
            lensShadingMap = request.lensShadingMapData,
            lensShadingRows = request.lensShadingRows,
            lensShadingCols = request.lensShadingCols,
            whiteBalance = request.wb,
            ccm = request.ccm,
            ccmAlt = request.ccmAlt,
            exportMatrixAB = request.exportMatrixAB,
            neutralColorPoint = request.calibration.neutralColorPoint,
            postRawSensitivityBoost = 1.0f,
            captureMetadata = request.captureMetadata
        )
    }

    fun buildEditConfigAndRecipe(
        context: Context,
        prefs: SharedPreferences,
        digitalGain: Float,
        hfMetadata: HalfFrameManager.Metadata?,
        zoomFactor: Float,
        faithfulHighlights: Boolean
    ): Pair<EditConfig, RenderRecipe> {
        val targetLogName = prefs.getString(SettingsFragment.KEY_TARGET_LOG, "None")
        val targetLogIndex = SettingsFragment.LOG_CURVES.indexOf(targetLogName)
        val activeLutName = prefs.getString(SettingsFragment.KEY_ACTIVE_LUT, null)
        val nativeLutPath = resolveNativeLutPath(context, activeLutName)
        val colorEngineMode = prefs.getInt(SettingsFragment.KEY_COLOR_ENGINE_MODE, 2)

        val editConfig = EditConfig(
            log = targetLogName ?: "None",
            lut = activeLutName ?: "None",
            digitalGain = digitalGain,
            adjustments = if (hfMetadata?.profile != null && hfMetadata.profile != HalfFrameSessionStore.PROFILE_NORMAL) {
                listOf(
                    BasicAdjustments(digitalGain = hfMetadata.frame1DigitalGain),
                    BasicAdjustments(digitalGain = digitalGain)
                )
            } else null,
            hfLayout = when (hfMetadata?.profile) {
                HalfFrameSessionStore.PROFILE_HALF_TOP -> "TB"
                HalfFrameSessionStore.PROFILE_HALF_SIDE -> "SBS"
                else -> null
            },
            showTimestamp = hfMetadata?.dateStamp ?: false,
            flareType = hfMetadata?.flareType ?: -1,
            zoomFactor = zoomFactor,
            colorEngineMode = colorEngineMode
        )

        val renderRecipe = RenderRecipe(
            targetLogIndex = targetLogIndex,
            lutPath = nativeLutPath,
            digitalGain = digitalGain,
            exposure = editConfig.exposure,
            contrast = editConfig.contrast,
            saturation = editConfig.saturation,
            highlights = editConfig.highlights,
            shadows = editConfig.shadows,
            whites = editConfig.whites,
            blacks = editConfig.blacks,
            colorEngineMode = colorEngineMode,
            faithfulHighlights = faithfulHighlights,
            enableMemoryColor = false,
            editConfig = editConfig
        )
        return Pair(editConfig, renderRecipe)
    }

    fun assemble(
        context: Context,
        prefs: SharedPreferences,
        request: TaskSpecAssemblyRequest,
        frameTimestampOverride: Long? = null
    ): CaptureTaskSpec {
        val baseName = buildCaptureBaseName(request.hfMetadata, request.isHdrPlus)
        val fullResJpgFile = File(context.cacheDir, "${baseName}_full.jpg")
        val linearDngFile = File(context.cacheDir, "${baseName}_linear.dng")

        val hwProfile = buildHardwareProfile(context, request)
        val frameTimestamp = frameTimestampOverride ?: request.captureMetadata.dateTimeOriginal ?: System.currentTimeMillis()
        val frameMeta = buildCaptureFrameMetadata(frameTimestamp, request)
        val (_, renderRecipe) = buildEditConfigAndRecipe(
            context = context,
            prefs = prefs,
            digitalGain = request.digitalGain,
            hfMetadata = request.hfMetadata,
            zoomFactor = request.zoomFactor,
            faithfulHighlights = request.faithfulHighlights
        )

        return CaptureTaskSpec(
            taskId = UUID.randomUUID().toString(),
            width = request.width,
            height = request.height,
            orientation = request.orientation,
            zoomFactor = request.zoomFactor,
            mirror = request.mirror,
            isSingleFrame = request.isSingleFrame,
            hardwareProfile = hwProfile,
            frameMetadata = frameMeta,
            renderRecipe = renderRecipe,
            timing = request.timing,
            dngCompressionMode = prefs.getInt(SettingsFragment.KEY_DNG_COMPRESSION_MODE, 0),
            rawOutputType = prefs.getInt(SettingsFragment.KEY_RAW_OUTPUT_TYPE, 0),
            fusionMode = request.fusionModeOverride ?: resolveFusionModeInt(prefs),
            baseName = baseName,
            fullResJpgPath = fullResJpgFile.absolutePath,
            linearDngPath = linearDngFile.absolutePath,
            saveJpg = prefs.getBoolean(SettingsFragment.KEY_SAVE_JPG, true),
            saveRaw = prefs.getBoolean(SettingsFragment.KEY_SAVE_RAW, true),
            jpgFolderUri = prefs.getString(SettingsFragment.KEY_JPG_STORAGE_URI, null),
            rawFolderUri = prefs.getString(SettingsFragment.KEY_RAW_STORAGE_URI, null),
            hfMetadata = request.hfMetadata?.copy(digitalGain = request.digitalGain),
            motionPhotoMp4Path = request.motionPhotoMp4Path,
            motionPhotoStillPtsUs = request.motionPhotoStillPtsUs,
            sink = request.sink
        )
    }

    fun buildFailureSpec(
        width: Int = 0,
        height: Int = 0,
        orientation: Int = 0,
        zoomFactor: Float = 1.0f,
        lensId: String = "0",
        timestamp: Long = System.currentTimeMillis(),
        hfMetadata: HalfFrameManager.Metadata? = null,
        sink: CaptureSink
    ): CaptureTaskSpec {
        return CaptureTaskSpec(
            taskId = UUID.randomUUID().toString(),
            width = width,
            height = height,
            orientation = orientation,
            zoomFactor = zoomFactor,
            hardwareProfile = HardwareProfile(lensId = lensId),
            frameMetadata = CaptureFrameMetadata(timestamp = timestamp, iso = 100, exposureTimeNs = 10_000_000L),
            renderRecipe = RenderRecipe(),
            hfMetadata = hfMetadata,
            sink = sink
        )
    }

    private companion object {
        const val FILENAME_PATTERN = "yyyy-MM-dd-HH-mm-ss-SSS"
    }
}
