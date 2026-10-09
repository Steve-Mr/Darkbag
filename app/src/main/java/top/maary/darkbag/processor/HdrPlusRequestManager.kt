package top.maary.darkbag.processor

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import top.maary.darkbag.models.CaptureMetadata
import top.maary.darkbag.models.EditConfig
import top.maary.darkbag.utils.HalfFrameManager
import java.nio.ByteBuffer

data class HdrPlusRequest(
    val requestId: String,
    val megaBuffer: ByteBuffer? = null,
    val streamingSessionHandle: Long = 0L,
    val numFrames: Int,
    val width: Int,
    val height: Int,
    val orientation: Int,
    val whiteLevel: Int,
    val blackLevelPattern: IntArray,
    val lensShadingMap: FloatArray?,
    val lensShadingRows: Int,
    val lensShadingCols: Int,
    val useSensorColorMatrix: Boolean,
    val whiteBalance: FloatArray,
    val ccm: FloatArray,
    val ccmAlt: FloatArray?,
    val exportMatrixAB: Boolean,
    val cfaPattern: Int,
    val targetLogIndex: Int,
    val lutPath: String?,
    val digitalGain: Float,
    val zoomFactor: Float,
    val mirror: Boolean,
    val metadata: CaptureMetadata,
    val isSingleFrame: Boolean,
    val saveJpg: Boolean,
    val saveRaw: Boolean,
    val baseName: String,
    val fullResJpgPath: String,
    val linearDngPath: String,
    val zslTargetUriStr: String?,
    val jpgFolderUri: String?,
    val rawFolderUri: String?,
    val hfMetadata: HalfFrameManager.Metadata?,
    val editConfig: EditConfig?,
    val runAblationTest: Boolean,
    val motionPhotoMp4Path: String? = null,
    val motionPhotoStillPtsUs: Long = 0L,
    val enableMemoryColor: Boolean = false,
    val colorEngineMode: Int = 0,
    val colorMatrix1: FloatArray? = null,
    val colorMatrix2: FloatArray? = null,
    val forwardMatrix1: FloatArray? = null,
    val forwardMatrix2: FloatArray? = null,
    val calibrationIlluminant1: Int = 21,
    val calibrationIlluminant2: Int = 17,
    val neutralColorPoint: FloatArray? = null,
    val timing: top.maary.darkbag.models.StandardTimingTracker? = null,
    val dngCompressionMode: Int = 0,
    val rawOutputType: Int = 0,
    val dynamicBlackLevel: FloatArray? = null,
    val noiseProfile: DoubleArray? = null,
    val activeArray: IntArray? = null,
    val fusionMode: Int = 0,
    val spec: top.maary.darkbag.pipeline.model.CaptureTaskSpec? = null
) {
    fun toSpec(): top.maary.darkbag.pipeline.model.CaptureTaskSpec {
        if (spec != null) return spec
        val hw = top.maary.darkbag.pipeline.model.HardwareProfile(
            cfaPattern = cfaPattern,
            whiteLevel = whiteLevel,
            blackLevelPattern = blackLevelPattern,
            dynamicBlackLevel = dynamicBlackLevel,
            colorMatrix1 = colorMatrix1,
            colorMatrix2 = colorMatrix2,
            forwardMatrix1 = forwardMatrix1,
            forwardMatrix2 = forwardMatrix2,
            calibrationIlluminant1 = calibrationIlluminant1,
            calibrationIlluminant2 = calibrationIlluminant2,
            activeArray = activeArray,
            noiseProfile = noiseProfile,
            useSensorColorMatrix = useSensorColorMatrix
        )
        val frameMeta = top.maary.darkbag.pipeline.model.CaptureFrameMetadata(
            timestamp = metadata.dateTimeOriginal ?: 0L,
            iso = metadata.iso ?: 100,
            exposureTimeNs = metadata.exposureTime ?: 10_000_000L,
            lensShadingMap = lensShadingMap,
            lensShadingRows = lensShadingRows,
            lensShadingCols = lensShadingCols,
            whiteBalance = whiteBalance,
            ccm = ccm,
            ccmAlt = ccmAlt,
            exportMatrixAB = exportMatrixAB,
            neutralColorPoint = neutralColorPoint,
            postRawSensitivityBoost = 1.0f,
            captureMetadata = metadata
        )
        val recipe = top.maary.darkbag.pipeline.model.RenderRecipe(
            targetLogIndex = targetLogIndex,
            lutPath = lutPath,
            digitalGain = digitalGain,
            exposure = editConfig?.exposure ?: 0f,
            contrast = editConfig?.contrast ?: 0f,
            saturation = editConfig?.saturation ?: 0f,
            highlights = editConfig?.highlights ?: 0f,
            shadows = editConfig?.shadows ?: 0f,
            whites = editConfig?.whites ?: 0f,
            blacks = editConfig?.blacks ?: 0f,
            colorEngineMode = colorEngineMode,
            faithfulHighlights = isSingleFrame,
            enableMemoryColor = enableMemoryColor,
            editConfig = editConfig
        )
        return top.maary.darkbag.pipeline.model.CaptureTaskSpec(
            taskId = requestId,
            width = width,
            height = height,
            orientation = orientation,
            zoomFactor = zoomFactor,
            mirror = mirror,
            isSingleFrame = isSingleFrame,
            hardwareProfile = hw,
            frameMetadata = frameMeta,
            renderRecipe = recipe,
            timing = timing,
            dngCompressionMode = dngCompressionMode,
            rawOutputType = rawOutputType,
            fusionMode = fusionMode,
            baseName = baseName,
            fullResJpgPath = fullResJpgPath,
            linearDngPath = linearDngPath,
            saveJpg = saveJpg,
            saveRaw = saveRaw,
            jpgFolderUri = jpgFolderUri,
            rawFolderUri = rawFolderUri,
            hfMetadata = hfMetadata,
            motionPhotoMp4Path = motionPhotoMp4Path,
            motionPhotoStillPtsUs = motionPhotoStillPtsUs
        )
    }

    companion object {
        fun fromSpec(
            spec: top.maary.darkbag.pipeline.model.CaptureTaskSpec,
            megaBuffer: ByteBuffer? = null,
            streamingSessionHandle: Long = 0L,
            numFrames: Int = 1
        ): HdrPlusRequest {
            return HdrPlusRequest(
                requestId = spec.taskId,
                megaBuffer = megaBuffer,
                streamingSessionHandle = streamingSessionHandle,
                numFrames = numFrames,
                width = spec.width,
                height = spec.height,
                orientation = spec.orientation,
                whiteLevel = spec.hardwareProfile.whiteLevel,
                blackLevelPattern = spec.hardwareProfile.blackLevelPattern,
                lensShadingMap = spec.frameMetadata.lensShadingMap,
                lensShadingRows = spec.frameMetadata.lensShadingRows,
                lensShadingCols = spec.frameMetadata.lensShadingCols,
                useSensorColorMatrix = spec.hardwareProfile.useSensorColorMatrix,
                whiteBalance = spec.frameMetadata.whiteBalance,
                ccm = spec.frameMetadata.ccm,
                ccmAlt = spec.frameMetadata.ccmAlt,
                exportMatrixAB = spec.frameMetadata.exportMatrixAB,
                cfaPattern = spec.hardwareProfile.cfaPattern,
                targetLogIndex = spec.renderRecipe.targetLogIndex,
                lutPath = spec.renderRecipe.lutPath,
                digitalGain = spec.renderRecipe.digitalGain,
                zoomFactor = spec.zoomFactor,
                mirror = spec.mirror,
                metadata = spec.frameMetadata.captureMetadata,
                isSingleFrame = spec.isSingleFrame,
                saveJpg = spec.saveJpg,
                saveRaw = spec.saveRaw,
                baseName = spec.baseName,
                fullResJpgPath = spec.fullResJpgPath,
                linearDngPath = spec.linearDngPath,
                zslTargetUriStr = null,
                jpgFolderUri = spec.jpgFolderUri,
                rawFolderUri = spec.rawFolderUri,
                hfMetadata = spec.hfMetadata,
                editConfig = spec.renderRecipe.editConfig,
                runAblationTest = false,
                motionPhotoMp4Path = spec.motionPhotoMp4Path,
                motionPhotoStillPtsUs = spec.motionPhotoStillPtsUs,
                enableMemoryColor = spec.renderRecipe.enableMemoryColor,
                colorEngineMode = spec.renderRecipe.colorEngineMode,
                colorMatrix1 = spec.hardwareProfile.colorMatrix1,
                colorMatrix2 = spec.hardwareProfile.colorMatrix2,
                forwardMatrix1 = spec.hardwareProfile.forwardMatrix1,
                forwardMatrix2 = spec.hardwareProfile.forwardMatrix2,
                calibrationIlluminant1 = spec.hardwareProfile.calibrationIlluminant1,
                calibrationIlluminant2 = spec.hardwareProfile.calibrationIlluminant2,
                neutralColorPoint = spec.frameMetadata.neutralColorPoint,
                timing = spec.timing,
                dngCompressionMode = spec.dngCompressionMode,
                rawOutputType = spec.rawOutputType,
                dynamicBlackLevel = spec.hardwareProfile.dynamicBlackLevel,
                noiseProfile = spec.hardwareProfile.noiseProfile,
                activeArray = spec.hardwareProfile.activeArray,
                fusionMode = spec.fusionMode,
                spec = spec
            )
        }
    }
}


object HdrPlusRequestManager {
    // Bounded capacity to enforce pipeline backpressure during high-frequency capture bursts
    const val MAX_IN_FLIGHT_REQUESTS = 16
    internal val requestChannel = Channel<HdrPlusRequest>(capacity = MAX_IN_FLIGHT_REQUESTS)
    
    val requestFlow = requestChannel.receiveAsFlow()

    private val _pendingTasksCount = MutableStateFlow(0)
    val pendingTasksCount: StateFlow<Int> = _pendingTasksCount.asStateFlow()

    private val _pendingForegroundTasksCount = MutableStateFlow(0)
    val pendingForegroundTasksCount: StateFlow<Int> = _pendingForegroundTasksCount.asStateFlow()

    fun onTaskStarted() {
        _pendingTasksCount.update { it + 1 }
        _pendingForegroundTasksCount.update { it + 1 }
    }

    fun onForegroundTaskFinished() {
        _pendingForegroundTasksCount.update { (it - 1).coerceAtLeast(0) }
    }

    fun enqueue(request: HdrPlusRequest, alreadyTracked: Boolean = false) {
        if (!alreadyTracked) {
            _pendingTasksCount.update { it + 1 }
            _pendingForegroundTasksCount.update { it + 1 }
        }
        val result = requestChannel.trySend(request)
        if (!result.isSuccess) {
            if (!alreadyTracked) {
                _pendingTasksCount.update { (it - 1).coerceAtLeast(0) }
                _pendingForegroundTasksCount.update { (it - 1).coerceAtLeast(0) }
            }
            throw IllegalStateException("Failed to enqueue HdrPlusRequest (pipeline queue full/rejected): ${request.requestId}")
        }
    }

    fun onTaskFinished(foregroundAlreadyFinished: Boolean = false) {
        _pendingTasksCount.update { (it - 1).coerceAtLeast(0) }
        if (!foregroundAlreadyFinished) {
            _pendingForegroundTasksCount.update { (it - 1).coerceAtLeast(0) }
        }
    }

    fun canAcceptNewTask(maxAllowedQueue: Int, context: Context): Boolean {
        if (_pendingTasksCount.value >= maxAllowedQueue) return false
        val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        val memInfo = android.app.ActivityManager.MemoryInfo()
        actManager?.getMemoryInfo(memInfo)
        if (memInfo.lowMemory || memInfo.availMem < 600L * 1024L * 1024L) {
            return false
        }
        return true
    }
}
