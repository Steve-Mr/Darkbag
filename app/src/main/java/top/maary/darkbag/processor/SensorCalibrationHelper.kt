package top.maary.darkbag.processor

object SensorCalibrationHelper {
    val M_XYZ_D50_TO_SRGB = floatArrayOf(
        3.1338561f, -1.6168667f, -0.4906146f,
        -0.9787684f,  1.9161415f,  0.0334540f,
        0.0719453f, -0.2289914f,  1.4052427f
    )

    // 31-entry table from Wyszecki & Stiles, "Color Science", 2nd edition, page 228 (Adobe DNG SDK dng_temperature.cpp)
    // Each row: (r: mired, u: CIE 1960 UCS, v: CIE 1960 UCS, t: slope)
    private val ROBERTSON_TABLE = arrayOf(
        floatArrayOf(0f, 0.18006f, 0.26352f, -0.24341f),
        floatArrayOf(10f, 0.18066f, 0.26589f, -0.25479f),
        floatArrayOf(20f, 0.18133f, 0.26846f, -0.26876f),
        floatArrayOf(30f, 0.18208f, 0.27119f, -0.28539f),
        floatArrayOf(40f, 0.18293f, 0.27407f, -0.30470f),
        floatArrayOf(50f, 0.18388f, 0.27709f, -0.32675f),
        floatArrayOf(60f, 0.18494f, 0.28021f, -0.35156f),
        floatArrayOf(70f, 0.18611f, 0.28342f, -0.37915f),
        floatArrayOf(80f, 0.18740f, 0.28668f, -0.40955f),
        floatArrayOf(90f, 0.18880f, 0.28997f, -0.44278f),
        floatArrayOf(100f, 0.19032f, 0.29326f, -0.47888f),
        floatArrayOf(125f, 0.19462f, 0.30141f, -0.58204f),
        floatArrayOf(150f, 0.19962f, 0.30921f, -0.70471f),
        floatArrayOf(175f, 0.20525f, 0.31647f, -0.84901f),
        floatArrayOf(200f, 0.21142f, 0.32312f, -1.0182f),
        floatArrayOf(225f, 0.21807f, 0.32909f, -1.2168f),
        floatArrayOf(250f, 0.22511f, 0.33439f, -1.4512f),
        floatArrayOf(275f, 0.23247f, 0.33904f, -1.7298f),
        floatArrayOf(300f, 0.24010f, 0.34308f, -2.0637f),
        floatArrayOf(325f, 0.24702f, 0.34655f, -2.4681f),
        floatArrayOf(350f, 0.25591f, 0.34951f, -2.9641f),
        floatArrayOf(375f, 0.26400f, 0.35200f, -3.5814f),
        floatArrayOf(400f, 0.27218f, 0.35407f, -4.3633f),
        floatArrayOf(425f, 0.28039f, 0.35577f, -5.3762f),
        floatArrayOf(450f, 0.28863f, 0.35714f, -6.7262f),
        floatArrayOf(475f, 0.29685f, 0.35821f, -8.4955f),
        floatArrayOf(500f, 0.30505f, 0.35903f, -10.841f),
        floatArrayOf(525f, 0.31320f, 0.35961f, -13.994f),
        floatArrayOf(550f, 0.32129f, 0.35998f, -18.285f),
        floatArrayOf(575f, 0.32931f, 0.36017f, -24.222f),
        floatArrayOf(600f, 0.33724f, 0.36020f, -32.613f)
    )

    fun robertsonXyToTemp(x: Float, y: Float): Float {
        val denom = 1.5f - x + 6.0f * y
        if (kotlin.math.abs(denom) < 1e-6f) return 6500f
        val u = 2.0f * x / denom
        val v = 3.0f * y / denom
        var lastDt = 0.0f
        for (i in 1 until ROBERTSON_TABLE.size) {
            val row = ROBERTSON_TABLE[i]
            val dv0 = row[3]
            val len = kotlin.math.sqrt(1.0f + dv0 * dv0)
            val du = 1.0f / len
            val dv = dv0 / len
            val uu = u - row[1]
            val vv = v - row[2]
            var dt = -uu * dv + vv * du
            if (dt <= 0.0f || i == ROBERTSON_TABLE.size - 1) {
                if (dt > 0.0f) dt = 0.0f
                dt = -dt
                val f = if (i == 1) 0.0f else (dt / (lastDt + dt).coerceAtLeast(1e-6f))
                val r = ROBERTSON_TABLE[i - 1][0] * f + row[0] * (1.0f - f)
                return if (r > 1e-4f) (1e6f / r) else 6500f
            }
            lastDt = dt
        }
        return 6500f
    }

    fun invert3x3(m: FloatArray): FloatArray? {
        val a00 = m[0]; val a01 = m[1]; val a02 = m[2]
        val a10 = m[3]; val a11 = m[4]; val a12 = m[5]
        val a20 = m[6]; val a21 = m[7]; val a22 = m[8]

        val c00 = a11 * a22 - a12 * a21
        val c01 = a12 * a20 - a10 * a22
        val c02 = a10 * a21 - a11 * a20

        val det = a00 * c00 + a01 * c01 + a02 * c02
        if (kotlin.math.abs(det) < 1e-9f) return null
        val invDet = 1.0f / det

        val c10 = a02 * a21 - a01 * a22
        val c11 = a00 * a22 - a02 * a20
        val c12 = a01 * a20 - a00 * a21

        val c20 = a01 * a12 - a02 * a11
        val c21 = a02 * a10 - a00 * a12
        val c22 = a00 * a11 - a01 * a10

        return floatArrayOf(
            c00 * invDet, c10 * invDet, c20 * invDet,
            c01 * invDet, c11 * invDet, c21 * invDet,
            c02 * invDet, c12 * invDet, c22 * invDet
        )
    }

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
     * Estimates Correlated Color Temperature (CCT) and mired interpolation weight between two illuminants.
     * Uses verbatim Adobe DNG SDK damped fixed-point iteration (damping factor alpha = 0.5) with the
     * Robertson 31-entry table when ColorMatrix1 and ColorMatrix2 are available.
     * Smoothly falls back to reciprocal WB ratio if color matrices are not provided or if matrix inversion fails.
     *
     * @return Pair of (CCT in Kelvin, mired interpolation weight g in [0, 1])
     */
    fun estimateCctAndWeight(
        colorMatrix1: FloatArray?,
        colorMatrix2: FloatArray?,
        calibrationIlluminant1: Int = 21,
        calibrationIlluminant2: Int = 17,
        neutralColorPoint: FloatArray? = null,
        wb: FloatArray? = null
    ): Pair<Float, Float> {
        val t1 = illuminantToTemperature(calibrationIlluminant1)
        val t2 = illuminantToTemperature(calibrationIlluminant2)
        val m1 = 1e6f / t1
        val m2 = 1e6f / t2

        if (colorMatrix1 != null && colorMatrix2 != null) {
            val neutral = neutralColorPoint?.takeIf { it.size >= 3 } ?: if (wb != null && wb.isNotEmpty()) {
                floatArrayOf(
                    if (wb[0] > 1e-4f) 1.0f / wb[0] else 1.0f,
                    if (wb.size > 1 && wb[1] > 1e-4f) 1.0f / wb[1] else 1.0f,
                    if (wb.size > 3 && wb[3] > 1e-4f) 1.0f / wb[3] else 1.0f
                )
            } else {
                floatArrayOf(1.0f, 1.0f, 1.0f)
            }

            var lastX = 0.34567f // Initial guess: D50 xy
            var lastY = 0.35850f
            var iterationSuccess = true

            for (pass in 0 until 10) {
                val temp = robertsonXyToTemp(lastX, lastY)
                val g = if (kotlin.math.abs(m1 - m2) > 1e-4f) {
                    ((1e6f / temp - m2) / (m1 - m2)).coerceIn(0f, 1f)
                } else 1.0f

                val cm = FloatArray(9) { i -> g * colorMatrix1[i] + (1f - g) * colorMatrix2[i] }
                val invCm = invert3x3(cm)
                if (invCm == null) {
                    iterationSuccess = false
                    break
                }

                val xX = invCm[0] * neutral[0] + invCm[1] * neutral[1] + invCm[2] * neutral[2]
                val xY = invCm[3] * neutral[0] + invCm[4] * neutral[1] + invCm[5] * neutral[2]
                val xZ = invCm[6] * neutral[0] + invCm[7] * neutral[1] + invCm[8] * neutral[2]

                val sum = xX + xY + xZ
                if (kotlin.math.abs(sum) < 1e-6f) {
                    iterationSuccess = false
                    break
                }

                val rawNextX = xX / sum
                val rawNextY = xY / sum

                val nextX = 0.5f * lastX + 0.5f * rawNextX
                val nextY = 0.5f * lastY + 0.5f * rawNextY

                if (kotlin.math.abs(nextX - lastX) + kotlin.math.abs(nextY - lastY) < 1e-7f) {
                    lastX = nextX
                    lastY = nextY
                    break
                }
                lastX = nextX
                lastY = nextY
            }

            if (iterationSuccess) {
                val finalT = robertsonXyToTemp(lastX, lastY)
                val finalWeight = if (kotlin.math.abs(m1 - m2) > 1e-4f) {
                    ((1e6f / finalT - m2) / (m1 - m2)).coerceIn(0f, 1f)
                } else 1.0f
                return Pair(finalT, finalWeight)
            }
        }

        // Fallback: Reciprocal WB ratio formula
        val wbR = neutralColorPoint?.getOrNull(0) ?: if (wb != null && wb.isNotEmpty() && wb[0] > 1e-4f) (1.0f / wb[0]) else 1.0f
        val wbB = neutralColorPoint?.getOrNull(2) ?: if (wb != null && wb.size > 3 && wb[3] > 1e-4f) (1.0f / wb[3]) else 1.0f
        val ratio = wbB / maxOf(wbR, 1e-4f)
        val cct = 2000f + ratio * 3500f
        val mCur = 1e6f / cct.coerceIn(2000f, 10000f)
        val weight = if (kotlin.math.abs(m1 - m2) > 1e-4f) {
            ((mCur - m2) / (m1 - m2)).coerceIn(0f, 1f)
        } else 1.0f
        return Pair(cct, weight)
    }

    /**
     * Computes an adaptive 3x3 sensor-to-sRGB render CCM using ForwardMatrix1/2 dual-illuminant
     * interpolation based on CCT estimated from neutralColorPoint and ColorMatrix1/2.
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
        wb: FloatArray? = null,
        colorMatrix1: FloatArray? = null,
        colorMatrix2: FloatArray? = null
    ): FloatArray? {
        if (forwardMatrix1 == null) return null
        val fm1 = forwardMatrix1
        val fm2 = forwardMatrix2 ?: forwardMatrix1

        val (_, weight) = estimateCctAndWeight(
            colorMatrix1 = colorMatrix1,
            colorMatrix2 = colorMatrix2,
            calibrationIlluminant1 = calibrationIlluminant1,
            calibrationIlluminant2 = calibrationIlluminant2,
            neutralColorPoint = neutralColorPoint,
            wb = wb
        )

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

    /**
     * Formats A1 Hardware Baseline Diagnostic Logging for audit trails and camera verification.
     */
    fun formatHardwareBaselineLog(
        cfaPattern: Int,
        blackLevelPattern: IntArray,
        lensShadingMap: FloatArray?,
        lensShadingRows: Int,
        lensShadingCols: Int,
        whiteBalance: FloatArray,
        digitalGain: Float
    ): String {
        val cfaStr = when (cfaPattern) {
            0 -> "RGGB"
            1 -> "GRBG"
            2 -> "GBRG"
            3 -> "BGGR"
            4 -> "RGB"
            else -> "UNKNOWN($cfaPattern)"
        }
        val blStr = blackLevelPattern.joinToString(", ")

        val wbStr = if (whiteBalance.size >= 4) {
            String.format(java.util.Locale.US, "[R=%.3f, Ge=%.3f, Go=%.3f, B=%.3f]", whiteBalance[0], whiteBalance[1], whiteBalance[2], whiteBalance[3])
        } else {
            whiteBalance.joinToString(", ") { String.format(java.util.Locale.US, "%.3f", it) }
        }

        val greenRatio = if (whiteBalance.size >= 3 && kotlin.math.abs(whiteBalance[2]) > 1e-4f) {
            whiteBalance[1] / whiteBalance[2]
        } else 1.0f

        val lscStr = if (lensShadingMap != null && lensShadingRows > 0 && lensShadingCols > 0 &&
            lensShadingMap.size >= lensShadingRows * lensShadingCols * 4) {
            val centerR = lensShadingRows / 2
            val centerC = lensShadingCols / 2
            fun gainAt(r: Int, c: Int): String {
                val idx = (r * lensShadingCols + c) * 4
                return String.format(
                    java.util.Locale.US,
                    "[R=%.3f, Ge=%.3f, Go=%.3f, B=%.3f]",
                    lensShadingMap[idx], lensShadingMap[idx + 1], lensShadingMap[idx + 2], lensShadingMap[idx + 3]
                )
            }
            """
              Dimensions: ${lensShadingRows}x${lensShadingCols}
              Center: ${gainAt(centerR, centerC)}
              TL: ${gainAt(0, 0)}, TR: ${gainAt(0, lensShadingCols - 1)}
              BL: ${gainAt(lensShadingRows - 1, 0)}, BR: ${gainAt(lensShadingRows - 1, lensShadingCols - 1)}
            """.trimIndent()
        } else {
            "None"
        }

        return """
            [A1 Hardware Baseline Diagnostic]
            - CFA Pattern: $cfaStr ($cfaPattern)
            - SENSOR_BLACK_LEVEL_PATTERN: [$blStr]
            - LensShadingMap:
            $lscStr
            - Green Ratio (Ge/Go): ${String.format(java.util.Locale.US, "%.4f", greenRatio)}
            - White Balance: $wbStr
            - Digital Gain: ${String.format(java.util.Locale.US, "%.4f", digitalGain)}
        """.trimIndent()
    }
}
