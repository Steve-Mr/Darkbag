package top.maary.darkbag.processor

object SensorCalibrationHelper {
    val M_XYZ_D50_TO_SRGB = floatArrayOf(
        3.1338561f, -1.6168667f, -0.4906146f,
        -0.9787684f,  1.9161415f,  0.0334540f,
        0.0719453f, -0.2289914f,  1.4052427f
    )

    fun illuminantToTemperature(illuminant: Int): Float = when (illuminant) {
        17, 3 -> 2856f // Standard Light A / Tungsten
        21 -> 6504f   // D65
        20 -> 5500f   // D55
        22 -> 7500f   // D75
        1, 4 -> 5500f // Daylight / Flash
        2 -> 4000f    // Fluorescent
        else -> if (illuminant == 17) 2856f else 6504f
    }

    /**
     * Computes an adaptive 3x3 sensor-to-sRGB render CCM using ForwardMatrix1/2 dual-illuminant
     * interpolation based on CCT estimated from neutralColorPoint (or white balance reciprocal).
     *
     * In Adobe DNG / Google Camera specifications, ForwardMatrix maps white-balanced camera coordinates
     * [1, 1, 1] to CIE XYZ D50 illuminant white point. Multiplying by standard Bradford D50->sRGB
     * matrix M_XYZ_D50_TO_SRGB maps [1, 1, 1] exactly to sRGB [1, 1, 1], guaranteeing neutral preservation.
     */
    fun computeRenderCcm(
        forwardMatrix1: FloatArray?,
        forwardMatrix2: FloatArray?,
        calibrationIlluminant1: Int = 21,
        calibrationIlluminant2: Int = 17,
        neutralColorPoint: FloatArray? = null,
        wb: FloatArray? = null
    ): FloatArray? {
        if (forwardMatrix1 == null) return null
        val fm1 = forwardMatrix1
        val fm2 = forwardMatrix2 ?: forwardMatrix1

        val wbR = neutralColorPoint?.getOrNull(0) ?: if (wb != null && wb.isNotEmpty() && wb[0] > 1e-4f) (1.0f / wb[0]) else 1.0f
        val wbB = neutralColorPoint?.getOrNull(2) ?: if (wb != null && wb.size > 3 && wb[3] > 1e-4f) (1.0f / wb[3]) else 1.0f
        val ratio = wbB / maxOf(wbR, 1e-4f)
        val cct = 2000f + ratio * 3500f

        val t1 = illuminantToTemperature(calibrationIlluminant1)
        val t2 = illuminantToTemperature(calibrationIlluminant2)
        val m1 = 1e6f / t1
        val m2 = 1e6f / t2
        val mCur = 1e6f / cct.coerceIn(2000f, 10000f)
        val weight = if (kotlin.math.abs(m1 - m2) > 1e-4f) {
            ((mCur - m2) / (m1 - m2)).coerceIn(0f, 1f)
        } else 1.0f

        val interpFm = FloatArray(9) { i ->
            weight * fm1[i] + (1f - weight) * fm2[i]
        }

        val renderCcm = FloatArray(9)
        for (r in 0 until 3) {
            for (c in 0 until 3) {
                var sum = 0f
                for (k in 0 until 3) {
                    sum += M_XYZ_D50_TO_SRGB[r * 3 + k] * interpFm[k * 3 + c]
                }
                renderCcm[r * 3 + c] = sum
            }
        }
        return renderCcm
    }
}
