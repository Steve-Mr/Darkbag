package top.maary.darkbag.calibration

/**
 * User-calibrated lens distortion parameters.
 *
 * @property lensId Target physical or logical camera ID (e.g. "0", "2").
 * @property focalLength Target focal length in millimeters (e.g. 2.1f for ultrawide).
 * @property k1 Primary radial distortion coefficient for Brown-Conrady polynomial.
 * @property k2 Secondary radial distortion coefficient for Brown-Conrady polynomial (optional, default 0.0f).
 * @property isEnabled Whether this calibration profile is active.
 */
data class CustomLensCalibration(
    val lensId: String = "0",
    val focalLength: Float = 0f,
    val k1: Float = 0f,
    val k2: Float = 0f,
    val isEnabled: Boolean = true
) {
    /**
     * Converts to a 5-element float array matching Camera2 LENS_DISTORTION format:
     * [k1, k2, 0f, 0f, 0f]
     */
    fun toCoefficientsArray(): FloatArray = floatArrayOf(k1, k2, 0f, 0f, 0f)
}
