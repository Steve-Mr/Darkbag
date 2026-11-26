package top.maary.darkbag.camera.multicam

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.RggbChannelVector
import android.util.Log
import android.util.Rational
import top.maary.darkbag.processor.SensorCalibrationHelper
import kotlin.math.roundToInt

/**
 * Multi-camera white balance & color matrix harmonization engine.
 *
 * Transfers converged scene white balance and illuminant chromaticity from the primary
 * preview camera to secondary cameras by projecting through device-calibrated CIE XYZ space:
 *
 *   Neutral_prim (Sensor A) -> M_prim^{-1} -> XYZ_illuminant -> M_sec -> Neutral_sec (Sensor B)
 *
 * This guarantees that even when secondary cameras have cold-boot unconverged 3A or
 * differing physical CFA spectral curves, their JPEG and DNG outputs match the primary camera's
 * neutral color balance.
 */
object MultiCamColorHarmonizer {
    private const val TAG = "MultiCamHarmonizer"

    data class HarmonizedProfile(
        val neutralPoint: FloatArray,
        val gains: RggbChannelVector,
        val gainArray: FloatArray,
        val cctKelvin: Float,
        val colorTransform: ColorSpaceTransform? = null
    )

    fun harmonize(
        primaryChars: CameraCharacteristics?,
        primaryResult: CaptureResult?,
        secondaryChars: CameraCharacteristics?
    ): HarmonizedProfile? {
        if (primaryChars == null || primaryResult == null || secondaryChars == null) return null

        val primGains = primaryResult.get(CaptureResult.COLOR_CORRECTION_GAINS)
        val primNp = primaryResult.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)
        val primNeutral = when {
            primNp != null && primNp.size >= 3 && primNp[0].numerator > 0 && primNp[1].numerator > 0 && primNp[2].numerator > 0 -> {
                floatArrayOf(
                    primNp[0].numerator.toFloat() / primNp[0].denominator.toFloat(),
                    primNp[1].numerator.toFloat() / primNp[1].denominator.toFloat(),
                    primNp[2].numerator.toFloat() / primNp[2].denominator.toFloat()
                )
            }
            primGains != null && primGains.red > 1e-4f && primGains.blue > 1e-4f -> {
                val gAvg = (primGains.greenEven + primGains.greenOdd) / 2.0f
                floatArrayOf(
                    (gAvg / primGains.red),
                    1.0f,
                    (gAvg / primGains.blue)
                )
            }
            else -> return null
        }

        val primCm1 = extractColorMatrix(primaryChars, CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)
            ?: floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        val primCm2 = extractColorMatrix(primaryChars, CameraCharacteristics.SENSOR_COLOR_TRANSFORM2, fallback = primCm1)
            ?: primCm1
        val secCm1 = extractColorMatrix(secondaryChars, CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)
            ?: floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        val secCm2 = extractColorMatrix(secondaryChars, CameraCharacteristics.SENSOR_COLOR_TRANSFORM2, fallback = secCm1)
            ?: secCm1

        val primIllum1 = primaryChars.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1) ?: 21
        val primIllum2 = (primaryChars.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)?.toInt() ?: 17)

        val secIllum1 = secondaryChars.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1) ?: 21
        val secIllum2 = (secondaryChars.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)?.toInt() ?: 17)

        val (cct, _) = SensorCalibrationHelper.estimateCctAndWeight(
            colorMatrix1 = primCm1,
            colorMatrix2 = primCm2,
            calibrationIlluminant1 = primIllum1,
            calibrationIlluminant2 = primIllum2,
            neutralColorPoint = primNeutral
        )

        val tPrim1 = SensorCalibrationHelper.illuminantToTemperature(primIllum1)
        val tPrim2 = SensorCalibrationHelper.illuminantToTemperature(primIllum2)
        val mPrim1 = 1e6f / tPrim1
        val mPrim2 = 1e6f / tPrim2
        val mCur = 1e6f / cct.coerceIn(2000f, 10000f)
        val gPrim = if (kotlin.math.abs(mPrim1 - mPrim2) > 1e-4f) {
            ((mCur - mPrim2) / (mPrim1 - mPrim2)).coerceIn(0f, 1f)
        } else 1.0f

        val cmPrim = FloatArray(9) { i -> gPrim * primCm1[i] + (1f - gPrim) * primCm2[i] }

        val tSec1 = SensorCalibrationHelper.illuminantToTemperature(secIllum1)
        val tSec2 = SensorCalibrationHelper.illuminantToTemperature(secIllum2)
        val mSec1 = 1e6f / tSec1
        val mSec2 = 1e6f / tSec2
        val gSec = if (kotlin.math.abs(mSec1 - mSec2) > 1e-4f) {
            ((mCur - mSec2) / (mSec1 - mSec2)).coerceIn(0f, 1f)
        } else 1.0f

        val cmSec = FloatArray(9) { i -> gSec * secCm1[i] + (1f - gSec) * secCm2[i] }

        val invCmPrim = SensorCalibrationHelper.invert3x3(cmPrim) ?: cmPrim

        val xyzX = invCmPrim[0] * primNeutral[0] + invCmPrim[1] * primNeutral[1] + invCmPrim[2] * primNeutral[2]
        val xyzY = invCmPrim[3] * primNeutral[0] + invCmPrim[4] * primNeutral[1] + invCmPrim[5] * primNeutral[2]
        val xyzZ = invCmPrim[6] * primNeutral[0] + invCmPrim[7] * primNeutral[1] + invCmPrim[8] * primNeutral[2]

        val secR = cmSec[0] * xyzX + cmSec[1] * xyzY + cmSec[2] * xyzZ
        val secG = cmSec[3] * xyzX + cmSec[4] * xyzY + cmSec[5] * xyzZ
        val secB = cmSec[6] * xyzX + cmSec[7] * xyzY + cmSec[8] * xyzZ

        if (secG <= 1e-6f || secR <= 1e-6f || secB <= 1e-6f) {
            val rGain = (1.0f / primNeutral[0].coerceAtLeast(1e-4f)).coerceIn(0.5f, 8.0f)
            val bGain = (1.0f / primNeutral[2].coerceAtLeast(1e-4f)).coerceIn(0.5f, 8.0f)
            val rggb = RggbChannelVector(rGain, 1.0f, 1.0f, bGain)
            return HarmonizedProfile(primNeutral.copyOf(), rggb, floatArrayOf(rGain, 1.0f, 1.0f, bGain), cct)
        }

        val normSecNeutral = floatArrayOf(
            secR / secG,
            1.0f,
            secB / secG
        )

        val secRedGain = (1.0f / normSecNeutral[0]).coerceIn(0.5f, 8.0f)
        val secBlueGain = (1.0f / normSecNeutral[2]).coerceIn(0.5f, 8.0f)
        val rggb = RggbChannelVector(secRedGain, 1.0f, 1.0f, secBlueGain)
        val gainArray = floatArrayOf(secRedGain, 1.0f, 1.0f, secBlueGain)

        // Derive secondary sensor's own color correction transform matrix rather than copying primary's
        val secFm1 = extractColorMatrix(secondaryChars, CameraCharacteristics.SENSOR_FORWARD_MATRIX1)
        val secFm2 = extractColorMatrix(secondaryChars, CameraCharacteristics.SENSOR_FORWARD_MATRIX2, fallback = secFm1)

        val secRenderCcm = SensorCalibrationHelper.computeRenderCcm(
            forwardMatrix1 = secFm1,
            forwardMatrix2 = secFm2,
            calibrationIlluminant1 = secIllum1,
            calibrationIlluminant2 = secIllum2,
            neutralColorPoint = normSecNeutral,
            colorMatrix1 = secCm1,
            colorMatrix2 = secCm2
        ) ?: run {
            val invCmSec = SensorCalibrationHelper.invert3x3(cmSec)
            if (invCmSec != null) {
                val out = FloatArray(9)
                for (r in 0 until 3) {
                    var rowSum = 0f
                    for (c in 0 until 3) {
                        var sum = 0f
                        for (k in 0 until 3) {
                            sum += SensorCalibrationHelper.M_XYZ_D50_TO_SRGB[r * 3 + k] * invCmSec[k * 3 + c]
                        }
                        val scaled = sum * normSecNeutral[c]
                        out[r * 3 + c] = scaled
                        rowSum += scaled
                    }
                    if (kotlin.math.abs(rowSum) > 1e-6f) {
                        for (c in 0 until 3) {
                            out[r * 3 + c] /= rowSum
                        }
                    }
                }
                out
            } else null
        }

        val secTransform = secRenderCcm?.let { floatArrayToColorSpaceTransform(it) }

        Log.d(
            TAG,
            "Harmonized color: CCT=${cct.toInt()}K, PrimNP=[${primNeutral[0]}, 1.0, ${primNeutral[2]}] " +
                "-> SecNP=[${normSecNeutral[0]}, 1.0, ${normSecNeutral[2]}], gains=(R=$secRedGain, B=$secBlueGain)"
        )

        return HarmonizedProfile(
            neutralPoint = normSecNeutral,
            gains = rggb,
            gainArray = gainArray,
            cctKelvin = cct,
            colorTransform = secTransform
        )
    }

    private fun extractColorMatrix(
        chars: CameraCharacteristics,
        key: CameraCharacteristics.Key<ColorSpaceTransform>,
        fallback: FloatArray? = null
    ): FloatArray? {
        val cm = chars.get(key) ?: return fallback?.copyOf()
        val result = FloatArray(9)
        for (r in 0 until 3) {
            for (c in 0 until 3) {
                val rational = cm.getElement(c, r)
                result[r * 3 + c] = rational.numerator.toFloat() / rational.denominator.toFloat()
            }
        }
        return result
    }

    private fun floatArrayToColorSpaceTransform(matrix: FloatArray): ColorSpaceTransform? {
        if (matrix.size != 9) return null
        return try {
            val rationals = Array(9) { i ->
                val v = matrix[i]
                if (v.isNaN() || v.isInfinite()) return null
                val num = (v * 1000f).roundToInt().coerceIn(-8000, 8000)
                Rational(num, 1000)
            }
            ColorSpaceTransform(rationals)
        } catch (_: Exception) {
            null
        }
    }
}
