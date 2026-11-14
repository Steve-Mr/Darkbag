package top.maary.darkbag.camera.dispatch

/**
 * Sensor color calibration matrices and illuminants extracted from CameraCharacteristics.
 */
data class SensorCalibration(
    val colorMatrix1: FloatArray,
    val colorMatrix2: FloatArray,
    val forwardMatrix1: FloatArray?,
    val forwardMatrix2: FloatArray?,
    val calibrationIlluminant1: Int,
    val calibrationIlluminant2: Int,
    val neutralColorPoint: FloatArray?,
    val renderCcm: FloatArray? = null
)
