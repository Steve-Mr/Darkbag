package top.maary.darkbag.camera.dispatch

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import top.maary.darkbag.models.StandardTimingTracker
import top.maary.darkbag.pipeline.sink.CaptureSink
import top.maary.darkbag.utils.HalfFrameManager
import top.maary.darkbag.utils.LensInfo

/**
 * Cohesive sensor, optics, and capture context passed to [CameraCapturePipelineDispatcher.processStreamingHdrPlusBurst]
 * so burst completion adheres to Invariant 2 (no 20+ primitive argument signatures).
 */
data class StreamingBurstCaptureContext(
    val sink: CaptureSink? = null,
    val digitalGain: Float,
    val hfMetadata: HalfFrameManager.Metadata? = null,
    val timing: StandardTimingTracker? = null,
    val width: Int,
    val height: Int,
    val combinedOrientation: Int,
    val targetCharId: String,
    val chars: CameraCharacteristics,
    val result: CaptureResult?,
    val whiteLevel: Int,
    val blackLevelPattern: IntArray,
    val wb: FloatArray,
    val ccm: FloatArray,
    val ccmAlt: FloatArray?,
    val exportMatrixAB: Boolean,
    val cfa: Int,
    val lensShadingMapData: FloatArray?,
    val lensShadingRows: Int,
    val lensShadingCols: Int,
    val useSensorColorMatrix: Boolean,
    val noiseProfileFlat: DoubleArray?,
    val capturedLens: LensInfo? = null,
    val zoomFactor: Float = 1.0f
)
