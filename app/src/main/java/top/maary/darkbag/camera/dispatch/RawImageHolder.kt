package top.maary.darkbag.camera.dispatch

import top.maary.darkbag.models.StandardTimingTracker
import top.maary.darkbag.pipeline.sink.CaptureSink
import top.maary.darkbag.utils.HalfFrameManager
import top.maary.darkbag.utils.LensInfo
import java.nio.ByteBuffer

/**
 * Encapsulates an acquired RAW/JPEG camera frame buffer and its hardware capture parameters
 * for asynchronous computational pipeline consumption.
 */
data class RawImageHolder(
    val data: ByteBuffer,
    val width: Int,
    val height: Int,
    val timestamp: Long,
    val rotationDegrees: Int, // Sensor Orientation
    val combinedOrientation: Int, // Combined with Display
    val zoomRatio: Float,
    val physicalId: String? = null,
    val timing: StandardTimingTracker? = null,
    val halfFrameMetadata: HalfFrameManager.Metadata? = null,
    val digitalGain: Float = 1.0f,
    val motionPhotoMp4Path: String? = null,
    val motionPhotoStillPtsUs: Long = 0L,
    val sink: CaptureSink? = null,
    val capturedLens: LensInfo? = null
)
