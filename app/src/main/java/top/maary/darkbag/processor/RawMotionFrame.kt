package top.maary.darkbag.processor

import java.nio.ByteBuffer

/**
 * Cohesive per-frame descriptor passed from [top.maary.darkbag.motionphoto.RawMotionPhotoRelay]
 * to [LutSurfaceProcessor] / [LutGlShaderPipeline] for GPU debayering and Motion Photo encoding.
 */
class RawMotionFrame(
    val bayerBuffer: ByteBuffer,
    val width: Int,
    val height: Int,
    val cfaPattern: Int,
    val blackLevel: FloatArray,
    val whiteLevel: Float,
    val wbGains: FloatArray,
    val dngCcm: FloatArray,
    val timestampNs: Long,
    val sensorOrientationDegrees: Int = 90,
    val lensShadingMap: FloatArray? = null,
    val lensShadingRows: Int = 0,
    val lensShadingCols: Int = 0,
    val digitalGain: Float = 1.0f
)
