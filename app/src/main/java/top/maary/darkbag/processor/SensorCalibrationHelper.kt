package top.maary.darkbag.processor

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult

object SensorCalibrationHelper {
    val M_XYZ_D50_TO_SRGB = floatArrayOf(
        3.1338561f, -1.6168667f, -0.4906146f,
        -0.9787684f,  1.9161415f,  0.0334540f,
        0.0719453f, -0.2289914f,  1.4052427f
    )

    // Standard CIE White Points (Y = 1.0, precision 1e-7)
    val W_D50 = floatArrayOf(0.9642200f, 1.0000000f, 0.8252100f)
    val W_D65 = floatArrayOf(0.9504559f, 1.0000000f, 1.0890578f)

    // CIE 2016 CAT16 Matrices (Row-major, precision 1e-7)
    val M_CAT16 = floatArrayOf(
        +0.4012880f, +0.6501730f, -0.0514610f,
        -0.2502680f, +1.2044140f, +0.0458540f,
        -0.0020790f, +0.0489520f, +0.9531270f
    )

    val M_CAT16_INV = floatArrayOf(
        +1.8620679f, -1.0112546f, +0.1491868f,
        +0.3875265f, +0.6214474f, -0.0089740f,
        -0.0158415f, -0.0341229f, +1.0499644f
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

    fun xyToUv(x: Float, y: Float): Pair<Float, Float> {
        val denom = 1.5f - x + 6.0f * y
        if (kotlin.math.abs(denom) < 1e-6f) return Pair(0.20917f, 0.30727f)
        return Pair(2.0f * x / denom, 3.0f * y / denom)
    }

    fun uvToXy(u: Float, v: Float): Pair<Float, Float> {
        val denom = 2.0f * u - 8.0f * v + 4.0f
        if (kotlin.math.abs(denom) < 1e-6f) return Pair(0.3127f, 0.3290f)
        return Pair(3.0f * u / denom, 2.0f * v / denom)
    }

    fun xyToXyz(x: Float, y: Float): FloatArray {
        val safeY = maxOf(1e-5f, y)
        return floatArrayOf(x / safeY, 1.0f, (1.0f - x - y) / safeY)
    }

    fun xyToTempTint(x: Float, y: Float): Triple<Float, Float, Float> {
        val (u, v) = xyToUv(x, y)
        var lastDt = 0.0f
        for (i in 1 until ROBERTSON_TABLE.size) {
            val row = ROBERTSON_TABLE[i]
            val lenI = kotlin.math.sqrt(1.0f + row[3] * row[3])
            val duI = 1.0f / lenI
            val dvI = row[3] / lenI

            val uu = u - row[1]
            val vv = v - row[2]
            val dt = -uu * dvI + vv * duI

            if (dt <= 0.0f || i == ROBERTSON_TABLE.size - 1) {
                var clampedDt = dt
                if (clampedDt > 0.0f) clampedDt = 0.0f
                val negDt = -clampedDt
                val f = if (i == 1) 0.0f else (negDt / (lastDt + negDt).coerceAtLeast(1e-6f))

                val r = ROBERTSON_TABLE[i - 1][0] * f + row[0] * (1.0f - f)
                val tempK = if (r > 20.0f) (1e6f / r) else (1e6f / 20.0f)

                val u0 = ROBERTSON_TABLE[i - 1][1] * f + row[1] * (1.0f - f)
                val v0 = ROBERTSON_TABLE[i - 1][2] * f + row[2] * (1.0f - f)

                val lenPrev = kotlin.math.sqrt(1.0f + ROBERTSON_TABLE[i - 1][3] * ROBERTSON_TABLE[i - 1][3])
                val duPrev = 1.0f / lenPrev
                val dvPrev = ROBERTSON_TABLE[i - 1][3] / lenPrev

                val duPrime = f * duPrev + (1.0f - f) * duI
                val dvPrime = f * dvPrev + (1.0f - f) * dvI
                val dirLen = kotlin.math.sqrt(duPrime * duPrime + dvPrime * dvPrime)
                val du = duPrime / maxOf(1e-6f, dirLen)
                val dv = dvPrime / maxOf(1e-6f, dirLen)

                val tint = 3000.0f * ((u - u0) * du + (v - v0) * dv)
                val duv = -tint / 3000.0f
                return Triple(tempK, tint, duv)
            }
            lastDt = dt
        }
        return Triple(6504f, 0f, 0f)
    }

    fun tempTintToXy(tempK: Float, tint: Float): Pair<Float, Float> {
        val tc = tempK.coerceIn(1667.0f, 50000.0f)
        val r = 1e6f / tc

        var idx = 1
        for (i in 1 until ROBERTSON_TABLE.size) {
            if (r <= ROBERTSON_TABLE[i][0] || i == ROBERTSON_TABLE.size - 1) {
                idx = i
                break
            }
        }

        var f = (ROBERTSON_TABLE[idx][0] - r) / maxOf(1e-6f, ROBERTSON_TABLE[idx][0] - ROBERTSON_TABLE[idx - 1][0])
        f = f.coerceIn(0.0f, 1.0f)

        val u0 = f * ROBERTSON_TABLE[idx - 1][1] + (1.0f - f) * ROBERTSON_TABLE[idx][1]
        val v0 = f * ROBERTSON_TABLE[idx - 1][2] + (1.0f - f) * ROBERTSON_TABLE[idx][2]

        val lenPrev = kotlin.math.sqrt(1.0f + ROBERTSON_TABLE[idx - 1][3] * ROBERTSON_TABLE[idx - 1][3])
        val duPrev = 1.0f / lenPrev
        val dvPrev = ROBERTSON_TABLE[idx - 1][3] / lenPrev

        val lenCurr = kotlin.math.sqrt(1.0f + ROBERTSON_TABLE[idx][3] * ROBERTSON_TABLE[idx][3])
        val duCurr = 1.0f / lenCurr
        val dvCurr = ROBERTSON_TABLE[idx][3] / lenCurr

        val duPrime = f * duPrev + (1.0f - f) * duCurr
        val dvPrime = f * dvPrev + (1.0f - f) * dvCurr
        val dirLen = kotlin.math.sqrt(duPrime * duPrime + dvPrime * dvPrime)
        val du = duPrime / maxOf(1e-6f, dirLen)
        val dv = dvPrime / maxOf(1e-6f, dirLen)

        val u = u0 + (tint / 3000.0f) * du
        val v = v0 + (tint / 3000.0f) * dv

        return uvToXy(u, v)
    }

    fun robertsonXyToTemp(x: Float, y: Float): Float {
        return xyToTempTint(x, y).first
    }

    fun multiply3x3(a: FloatArray, b: FloatArray): FloatArray {
        val out = FloatArray(9)
        for (r in 0 until 3) {
            for (c in 0 until 3) {
                var sum = 0f
                for (k in 0 until 3) {
                    sum += a[r * 3 + k] * b[k * 3 + c]
                }
                out[r * 3 + c] = sum
            }
        }
        return out
    }

    fun multiply3x3Vec3(m: FloatArray, v: FloatArray): FloatArray {
        val out = FloatArray(3)
        for (r in 0 until 3) {
            out[r] = m[r * 3 + 0] * v[0] + m[r * 3 + 1] * v[1] + m[r * 3 + 2] * v[2]
        }
        return out
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

    fun cat16AdaptMatrix(srcWhiteXyz: FloatArray, dstWhiteXyz: FloatArray): FloatArray {
        val lmsSrc = multiply3x3Vec3(M_CAT16, srcWhiteXyz)
        val lmsDst = multiply3x3Vec3(M_CAT16, dstWhiteXyz)

        val diag = FloatArray(9)
        diag[0] = lmsDst[0] / (if (kotlin.math.abs(lmsSrc[0]) > 1e-5f) lmsSrc[0] else 1e-5f)
        diag[4] = lmsDst[1] / (if (kotlin.math.abs(lmsSrc[1]) > 1e-5f) lmsSrc[1] else 1e-5f)
        diag[8] = lmsDst[2] / (if (kotlin.math.abs(lmsSrc[2]) > 1e-5f) lmsSrc[2] else 1e-5f)

        val temp = multiply3x3(M_CAT16_INV, diag)
        return multiply3x3(temp, M_CAT16)
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
     * Resolves canonical sensor WB gains [R, Ge, Go, B] (G=1.0) and physical neutral point [nR, 1.0, nB].
     * Strict single-source priority contract (AGENTS.md Invariant 1 & Section 3.2).
     */
    fun resolveCanonicalWbAndNeutral(
        neutralPoint: FloatArray?,
        colorGains: FloatArray?
    ): Pair<FloatArray, FloatArray> {
        // Priority 1: SENSOR_NEUTRAL_COLOR_POINT / AsShotNeutral
        if (neutralPoint != null && neutralPoint.size >= 3 &&
            neutralPoint[0].isFinite() && neutralPoint[0] > 1e-4f &&
            neutralPoint[1].isFinite() && neutralPoint[1] > 1e-4f &&
            neutralPoint[2].isFinite() && neutralPoint[2] > 1e-4f
        ) {
            val nG = neutralPoint[1]
            val nR = neutralPoint[0] / nG
            val nB = neutralPoint[2] / nG
            val gR = 1.0f / nR
            val gB = 1.0f / nB
            val wb = floatArrayOf(gR, 1.0f, 1.0f, gB)
            val neutral = floatArrayOf(nR, 1.0f, nB)
            return Pair(wb, neutral)
        }

        // Priority 2: COLOR_CORRECTION_GAINS
        if (colorGains != null && colorGains.size >= 4 &&
            colorGains[0].isFinite() && colorGains[0] > 1e-4f &&
            colorGains[3].isFinite() && colorGains[3] > 1e-4f &&
            ((colorGains[1].isFinite() && colorGains[1] > 1e-4f) || (colorGains[2].isFinite() && colorGains[2] > 1e-4f))
        ) {
            val gGe = if (colorGains[1].isFinite() && colorGains[1] > 1e-4f) colorGains[1] else colorGains[2]
            val gGo = if (colorGains[2].isFinite() && colorGains[2] > 1e-4f) colorGains[2] else colorGains[1]
            val gAvg = 0.5f * (gGe + gGo)
            val effGAvg = if (gAvg > 1e-4f) gAvg else 1.0f
            val gR = colorGains[0] / effGAvg
            val gB = colorGains[3] / effGAvg
            val wb = floatArrayOf(gR, 1.0f, 1.0f, gB)
            val neutral = floatArrayOf(1.0f / gR, 1.0f, 1.0f / gB)
            return Pair(wb, neutral)
        }

        // Priority 3: Safe fallback
        return Pair(
            floatArrayOf(2.0f, 1.0f, 1.0f, 1.5f),
            floatArrayOf(0.50f, 1.00f, 1.0f / 1.5f)
        )
    }

    /**
     * Resolves canonical sensor WB gains and neutral point from Camera2 CaptureResult with zero-division guard.
     */
    fun resolveCanonicalWbAndNeutral(
        result: CaptureResult?,
        fallbackWb: FloatArray? = null
    ): Pair<FloatArray, FloatArray> {
        val np = result?.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)
        val neutralArray = if (np != null && np.size >= 3 &&
            np[0].numerator > 0 && np[0].denominator > 0 &&
            np[1].numerator > 0 && np[1].denominator > 0 &&
            np[2].numerator > 0 && np[2].denominator > 0
        ) {
            floatArrayOf(
                np[0].numerator.toFloat() / np[0].denominator.toFloat(),
                np[1].numerator.toFloat() / np[1].denominator.toFloat(),
                np[2].numerator.toFloat() / np[2].denominator.toFloat()
            )
        } else null

        val gains = result?.get(CaptureResult.COLOR_CORRECTION_GAINS)?.let {
            floatArrayOf(it.red, it.greenEven, it.greenOdd, it.blue)
        } ?: fallbackWb

        return resolveCanonicalWbAndNeutral(neutralArray, gains)
    }

    /**
     * Estimated scene illuminant parameters from dual-illuminant fixed point iteration.
     */
    data class SceneIlluminant(
        val tempK: Float,
        val tint: Float,
        val weight: Float,
        val x: Float,
        val y: Float
    )

    /**
     * Estimates scene illuminant using 10-iteration damped fixed-point iteration with 2D Robertson mapping.
     */
    fun estimateSceneIlluminant(
        colorMatrix1: FloatArray?,
        colorMatrix2: FloatArray?,
        calibrationIlluminant1: Int = 21,
        calibrationIlluminant2: Int = 17,
        neutralColorPoint: FloatArray? = null,
        wb: FloatArray? = null
    ): SceneIlluminant {
        val t1 = illuminantToTemperature(calibrationIlluminant1)
        val t2 = illuminantToTemperature(calibrationIlluminant2)
        val m1 = 1e6f / t1
        val m2 = 1e6f / t2

        val (_, canonNeutral) = resolveCanonicalWbAndNeutral(neutralColorPoint, wb)

        if (colorMatrix1 != null) {
            val cm1 = colorMatrix1
            val cm2 = colorMatrix2 ?: colorMatrix1

            var lastX = 0.34567f // Initial guess: D50 xy
            var lastY = 0.35850f
            var iterationSuccess = true

            for (pass in 0 until 10) {
                val (tempK, _) = xyToTempTint(lastX, lastY)
                val g = if (kotlin.math.abs(m1 - m2) > 1e-4f) {
                    ((1e6f / tempK - m2) / (m1 - m2)).coerceIn(0f, 1f)
                } else 1.0f

                val cm = FloatArray(9) { i -> g * cm1[i] + (1f - g) * cm2[i] }
                val invCm = invert3x3(cm)
                if (invCm == null) {
                    iterationSuccess = false
                    break
                }

                val xX = invCm[0] * canonNeutral[0] + invCm[1] * canonNeutral[1] + invCm[2] * canonNeutral[2]
                val xY = invCm[3] * canonNeutral[0] + invCm[4] * canonNeutral[1] + invCm[5] * canonNeutral[2]
                val xZ = invCm[6] * canonNeutral[0] + invCm[7] * canonNeutral[1] + invCm[8] * canonNeutral[2]

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
                val (finalT, finalTint) = xyToTempTint(lastX, lastY)
                val finalWeight = if (kotlin.math.abs(m1 - m2) > 1e-4f) {
                    ((1e6f / finalT - m2) / (m1 - m2)).coerceIn(0f, 1f)
                } else 1.0f
                return SceneIlluminant(finalT, finalTint, finalWeight, lastX, lastY)
            }
        }

        // Fallback: Reciprocal WB ratio formula
        val ratio = canonNeutral[2] / maxOf(canonNeutral[0], 1e-4f)
        val cct = 2000f + ratio * 3500f
        val mCur = 1e6f / cct.coerceIn(2000f, 10000f)
        val weight = if (kotlin.math.abs(m1 - m2) > 1e-4f) {
            ((mCur - m2) / (m1 - m2)).coerceIn(0f, 1f)
        } else 1.0f
        val (fallbackX, fallbackY) = tempTintToXy(cct, 0.0f)
        return SceneIlluminant(cct, 0.0f, weight, fallbackX, fallbackY)
    }

    /**
     * Estimates Correlated Color Temperature (CCT) and mired interpolation weight between two illuminants.
     * Uses 10-iteration damped fixed-point iteration with 2D Robertson mapping when ColorMatrix1 and
     * ColorMatrix2 are available.
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
        val illum = estimateSceneIlluminant(
            colorMatrix1, colorMatrix2,
            calibrationIlluminant1, calibrationIlluminant2,
            neutralColorPoint, wb
        )
        return Pair(illum.tempK, illum.weight)
    }

    /**
     * Computes an adaptive 3x3 sensor-to-sRGB render CCM using ForwardMatrix1/2 dual-illuminant
     * interpolation or CAT16 adaptation fallback with white point row normalization (Section 3.4).
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
        val (canonWb, canonNeutral) = resolveCanonicalWbAndNeutral(neutralColorPoint, wb)

        val fmMag = forwardMatrix1?.fold(0f) { acc, fl -> acc + kotlin.math.abs(fl) } ?: 0f
        val hasValidFm = forwardMatrix1 != null && fmMag > 1e-3f

        if (hasValidFm) {
            val fm1 = forwardMatrix1!!
            val fm2 = forwardMatrix2 ?: forwardMatrix1

            val (_, weight) = estimateCctAndWeight(
                colorMatrix1 = colorMatrix1,
                colorMatrix2 = colorMatrix2,
                calibrationIlluminant1 = calibrationIlluminant1,
                calibrationIlluminant2 = calibrationIlluminant2,
                neutralColorPoint = canonNeutral,
                wb = canonWb
            )

            val interpFm = FloatArray(9) { i ->
                weight * fm1[i] + (1f - weight) * fm2[i]
            }

            // White Point Row Normalization (Neutral Preservation Guard)
            val wFm0 = interpFm[0] + interpFm[1] + interpFm[2]
            val wFm1 = interpFm[3] + interpFm[4] + interpFm[5]
            val wFm2 = interpFm[6] + interpFm[7] + interpFm[8]

            if (wFm0 > 0.1f && wFm1 > 0.1f && wFm2 > 0.1f) {
                val fmD50 = FloatArray(9)
                val scale0 = W_D50[0] / wFm0
                val scale1 = W_D50[1] / wFm1
                val scale2 = W_D50[2] / wFm2

                for (c in 0 until 3) {
                    fmD50[0 * 3 + c] = interpFm[0 * 3 + c] * scale0
                    fmD50[1 * 3 + c] = interpFm[1 * 3 + c] * scale1
                    fmD50[2 * 3 + c] = interpFm[2 * 3 + c] * scale2
                }
                return multiply3x3(M_XYZ_D50_TO_SRGB, fmD50)
            }
        }

        // Path B Fallback: ColorMatrix-only with CAT16 adaptation (Section 3.4)
        if (colorMatrix1 != null) {
            val cm1 = colorMatrix1
            val cm2 = colorMatrix2 ?: colorMatrix1

            val (_, weight) = estimateCctAndWeight(
                colorMatrix1 = cm1,
                colorMatrix2 = cm2,
                calibrationIlluminant1 = calibrationIlluminant1,
                calibrationIlluminant2 = calibrationIlluminant2,
                neutralColorPoint = canonNeutral,
                wb = canonWb
            )

            val cm = FloatArray(9) { i ->
                weight * cm1[i] + (1f - weight) * cm2[i]
            }

            val invCm = invert3x3(cm) ?: return null

            val wRaw = multiply3x3Vec3(invCm, canonNeutral)
            val s = 1.0f / maxOf(1e-5f, wRaw[1])
            val wScene = floatArrayOf(s * wRaw[0], 1.0f, s * wRaw[2])

            val mCatAdapt = cat16AdaptMatrix(wScene, W_D50)

            val dwbInv = floatArrayOf(
                canonNeutral[0], 0f, 0f,
                0f, 1.0f, 0f,
                0f, 0f, canonNeutral[2]
            )

            val tempA = multiply3x3(mCatAdapt, invCm)
            val tempB = multiply3x3(tempA, dwbInv)
            val fmD50 = FloatArray(9) { i -> s * tempB[i] }

            return multiply3x3(M_XYZ_D50_TO_SRGB, fmD50)
        }

        return null
    }

    /**
     * Cross-camera CIE xy neutral point mapping (Section 3.7).
     * Maps primary sensor neutral to secondary sensor domain using CIE xy space.
     */
    fun mapNeutralAcrossCameras(
        mainNeutral: FloatArray,
        mainCm1: FloatArray,
        mainCm2: FloatArray?,
        mainIllum1: Int,
        mainIllum2: Int,
        subCm1: FloatArray,
        subCm2: FloatArray?,
        subIllum1: Int,
        subIllum2: Int
    ): Pair<FloatArray, FloatArray>? {
        val illum = estimateSceneIlluminant(
            colorMatrix1 = mainCm1,
            colorMatrix2 = mainCm2,
            calibrationIlluminant1 = mainIllum1,
            calibrationIlluminant2 = mainIllum2,
            neutralColorPoint = mainNeutral
        )
        val mainXyz = xyToXyz(illum.x, illum.y)

        val subT1 = illuminantToTemperature(subIllum1)
        val subT2 = illuminantToTemperature(subIllum2)
        val subM1 = 1e6f / subT1
        val subM2 = 1e6f / subT2
        val subG = if (kotlin.math.abs(subM1 - subM2) > 1e-4f) {
            ((1e6f / illum.tempK - subM2) / (subM1 - subM2)).coerceIn(0f, 1f)
        } else 1.0f

        val scm1 = subCm1
        val scm2 = subCm2 ?: subCm1
        val subCm = FloatArray(9) { i -> subG * scm1[i] + (1f - subG) * scm2[i] }

        val subRaw = multiply3x3Vec3(subCm, mainXyz)
        if (kotlin.math.abs(subRaw[1]) < 1e-5f) return null

        val nSubR = subRaw[0] / subRaw[1]
        val nSubB = subRaw[2] / subRaw[1]

        if (nSubR <= 1e-4f || nSubB <= 1e-4f) return null

        val gSubR = 1.0f / nSubR
        val gSubB = 1.0f / nSubB

        // Plausibility envelope check (Section 3.7)
        if (gSubR < 0.5f || gSubR > 5.0f || gSubB < 0.5f || gSubB > 5.0f) {
            return null
        }

        val subWb = floatArrayOf(gSubR, 1.0f, 1.0f, gSubB)
        val subNeutral = floatArrayOf(nSubR, 1.0f, nSubB)
        return Pair(subWb, subNeutral)
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

    data class CalibrationData(
        val colorMatrix1: FloatArray,
        val colorMatrix2: FloatArray,
        val forwardMatrix1: FloatArray?,
        val forwardMatrix2: FloatArray?,
        val calibrationIlluminant1: Int,
        val calibrationIlluminant2: Int,
        val neutralColorPoint: FloatArray?,
        val renderCcm: FloatArray? = null
    )

    fun extractCalibration(
        characteristics: CameraCharacteristics?,
        result: CaptureResult?,
        wb: FloatArray? = null,
        overrideNeutralColorPoint: FloatArray? = null
    ): CalibrationData {
        val colorMatrix1 = FloatArray(9) { 0f }
        val cm1 = characteristics?.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)
        if (cm1 != null) {
            for (r in 0 until 3) {
                for (c in 0 until 3) {
                    val rational = cm1.getElement(c, r)
                    colorMatrix1[r * 3 + c] = rational.numerator.toFloat() / rational.denominator.toFloat()
                }
            }
        } else {
            colorMatrix1[0] = 1f; colorMatrix1[4] = 1f; colorMatrix1[8] = 1f
        }

        val colorMatrix2 = FloatArray(9) { 0f }
        val cm2 = characteristics?.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2)
        if (cm2 != null) {
            for (r in 0 until 3) {
                for (c in 0 until 3) {
                    val rational = cm2.getElement(c, r)
                    colorMatrix2[r * 3 + c] = rational.numerator.toFloat() / rational.denominator.toFloat()
                }
            }
        } else {
            System.arraycopy(colorMatrix1, 0, colorMatrix2, 0, 9)
        }

        var forwardMatrix1: FloatArray? = null
        val fm1 = characteristics?.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1)
        if (fm1 != null) {
            val f1 = FloatArray(9) { 0f }
            for (r in 0 until 3) {
                for (c in 0 until 3) {
                    val rational = fm1.getElement(c, r)
                    f1[r * 3 + c] = rational.numerator.toFloat() / rational.denominator.toFloat()
                }
            }
            forwardMatrix1 = f1
        }

        var forwardMatrix2: FloatArray? = null
        val fm2 = characteristics?.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2)
        if (fm2 != null) {
            val f2 = FloatArray(9) { 0f }
            for (r in 0 until 3) {
                for (c in 0 until 3) {
                    val rational = fm2.getElement(c, r)
                    f2[r * 3 + c] = rational.numerator.toFloat() / rational.denominator.toFloat()
                }
            }
            forwardMatrix2 = f2
        } else if (forwardMatrix1 != null) {
            forwardMatrix2 = forwardMatrix1.copyOf()
        }

        val calibrationIlluminant1 = (characteristics?.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1) ?: 21).toInt()
        val calibrationIlluminant2 = (characteristics?.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2) ?: 17).toInt()

        val hasResultOrWb = result != null || wb != null || overrideNeutralColorPoint != null
        val (canonicalWb, canonicalNeutral) = if (overrideNeutralColorPoint != null && overrideNeutralColorPoint.size >= 3) {
            val nR = overrideNeutralColorPoint[0].coerceAtLeast(1e-4f)
            val nG = overrideNeutralColorPoint[1].coerceAtLeast(1e-4f)
            val nB = overrideNeutralColorPoint[2].coerceAtLeast(1e-4f)
            val normN = floatArrayOf(nR / nG, 1.0f, nB / nG)
            val gains = floatArrayOf(normN[1] / normN[0], 1.0f, 1.0f, normN[1] / normN[2])
            Pair(gains, normN)
        } else if (hasResultOrWb) {
            resolveCanonicalWbAndNeutral(result, wb)
        } else {
            Pair(null, null)
        }

        val renderCcm = computeRenderCcm(
            forwardMatrix1 = forwardMatrix1,
            forwardMatrix2 = forwardMatrix2,
            calibrationIlluminant1 = calibrationIlluminant1,
            calibrationIlluminant2 = calibrationIlluminant2,
            neutralColorPoint = canonicalNeutral,
            wb = canonicalWb,
            colorMatrix1 = colorMatrix1,
            colorMatrix2 = colorMatrix2
        )

        return CalibrationData(
            colorMatrix1 = colorMatrix1,
            colorMatrix2 = colorMatrix2,
            forwardMatrix1 = forwardMatrix1,
            forwardMatrix2 = forwardMatrix2,
            calibrationIlluminant1 = calibrationIlluminant1,
            calibrationIlluminant2 = calibrationIlluminant2,
            neutralColorPoint = canonicalNeutral,
            renderCcm = renderCcm
        )
    }

    fun extractLensShading(result: CaptureResult?): Triple<FloatArray?, Int, Int> {
        if (result == null) return Triple(null, 0, 0)
        val lsc = result.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP) ?: return Triple(null, 0, 0)
        val rows = lsc.rowCount
        val cols = lsc.columnCount
        if (rows <= 1 || cols <= 1) return Triple(null, 0, 0)
        val out = FloatArray(4 * rows * cols)
        fun idx(ch: Int, row: Int, col: Int): Int = ch * rows * cols + row * cols + col
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                out[idx(0, row, col)] = lsc.getGainFactor(0, col, row)
                out[idx(1, row, col)] = lsc.getGainFactor(1, col, row)
                out[idx(2, row, col)] = lsc.getGainFactor(2, col, row)
                out[idx(3, row, col)] = lsc.getGainFactor(3, col, row)
            }
        }
        return Triple(out, rows, cols)
    }
}
