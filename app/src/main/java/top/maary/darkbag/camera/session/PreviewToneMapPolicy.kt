package top.maary.darkbag.camera.session

import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.TonemapCurve
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.pow

enum class PreviewToneMapStrategy {
    PRESET_CURVE_SRGB,
    CONTRAST_CURVE_SRGB,
    GAMMA_VALUE_22,
    FAST_FALLBACK
}

object PreviewToneMapPolicy {
    private const val MIN_CONTRAST_CURVE_POINTS = 16
    private const val TARGET_CURVE_POINTS = 32
    private const val GAMMA_22 = 2.2f

    private val curveCache = ConcurrentHashMap<Int, TonemapCurve>()

    fun resolvePreviewToneMapStrategy(
        availableModes: IntArray?,
        maxCurvePoints: Int
    ): PreviewToneMapStrategy {
        if (availableModes == null || availableModes.isEmpty()) {
            return PreviewToneMapStrategy.FAST_FALLBACK
        }
        return when {
            availableModes.contains(CaptureRequest.TONEMAP_MODE_PRESET_CURVE) ->
                PreviewToneMapStrategy.PRESET_CURVE_SRGB
            availableModes.contains(CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE) &&
                maxCurvePoints >= MIN_CONTRAST_CURVE_POINTS ->
                PreviewToneMapStrategy.CONTRAST_CURVE_SRGB
            availableModes.contains(CaptureRequest.TONEMAP_MODE_GAMMA_VALUE) ->
                PreviewToneMapStrategy.GAMMA_VALUE_22
            else ->
                PreviewToneMapStrategy.FAST_FALLBACK
        }
    }

    /**
     * Builds interleaved `[x0, y0, x1, y1, ...]` points (length `2 * N`, where
     * `N = maxCurvePoints.coerceIn(2, 32)`) mapping linear-ish ISP signal through
     * the standard sRGB OETF using `x_i = (i / (N - 1))^2.2` for denser shadow sampling.
     */
    fun buildSrgbTonemapCurvePoints(maxCurvePoints: Int): FloatArray {
        val n = maxCurvePoints.coerceIn(2, TARGET_CURVE_POINTS)
        val pts = FloatArray(n * 2)
        val denom = (n - 1).toDouble()
        for (i in 0 until n) {
            val x = when (i) {
                0 -> 0.0
                n - 1 -> 1.0
                else -> (i / denom).pow(2.2)
            }
            val y = when (i) {
                0 -> 0.0
                n - 1 -> 1.0
                else -> if (x <= 0.0031308) {
                    12.92 * x
                } else {
                    1.055 * x.pow(1.0 / 2.4) - 0.055
                }
            }
            pts[i * 2] = x.toFloat()
            pts[i * 2 + 1] = y.toFloat().coerceIn(0.0f, 1.0f)
        }
        return pts
    }

    fun getOrCreateSrgbTonemapCurve(maxCurvePoints: Int): TonemapCurve {
        val n = maxCurvePoints.coerceIn(2, TARGET_CURVE_POINTS)
        return curveCache.getOrPut(n) {
            val pts = buildSrgbTonemapCurvePoints(n)
            TonemapCurve(pts, pts, pts)
        }
    }

    /**
     * Applies [strategy] to [request], falling back to the next available strategy on
     * [IllegalArgumentException]. Returns the strategy that was actually applied.
     */
    fun applyStrategyToRequest(
        request: CaptureRequest.Builder,
        strategy: PreviewToneMapStrategy,
        availableModes: IntArray?,
        maxCurvePoints: Int,
        tag: String
    ): PreviewToneMapStrategy {
        var current = strategy
        while (true) {
            try {
                when (current) {
                    PreviewToneMapStrategy.PRESET_CURVE_SRGB -> {
                        request.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_PRESET_CURVE)
                        request.set(CaptureRequest.TONEMAP_PRESET_CURVE, CaptureRequest.TONEMAP_PRESET_CURVE_SRGB)
                    }
                    PreviewToneMapStrategy.CONTRAST_CURVE_SRGB -> {
                        request.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE)
                        request.set(CaptureRequest.TONEMAP_CURVE, getOrCreateSrgbTonemapCurve(maxCurvePoints))
                    }
                    PreviewToneMapStrategy.GAMMA_VALUE_22 -> {
                        request.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_GAMMA_VALUE)
                        request.set(CaptureRequest.TONEMAP_GAMMA, GAMMA_22)
                    }
                    PreviewToneMapStrategy.FAST_FALLBACK -> {
                        request.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_FAST)
                    }
                }
                return current
            } catch (e: IllegalArgumentException) {
                val next = nextFallbackStrategy(current, availableModes, maxCurvePoints)
                Log.w(tag, "ToneMap strategy $current rejected by device, falling back to $next", e)
                if (next == current) {
                    request.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_FAST)
                    return PreviewToneMapStrategy.FAST_FALLBACK
                }
                current = next
            }
        }
    }

    fun nextFallbackStrategy(
        failed: PreviewToneMapStrategy,
        availableModes: IntArray?,
        maxCurvePoints: Int
    ): PreviewToneMapStrategy {
        return when (failed) {
            PreviewToneMapStrategy.PRESET_CURVE_SRGB -> when {
                availableModes?.contains(CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE) == true &&
                    maxCurvePoints >= MIN_CONTRAST_CURVE_POINTS -> PreviewToneMapStrategy.CONTRAST_CURVE_SRGB
                availableModes?.contains(CaptureRequest.TONEMAP_MODE_GAMMA_VALUE) == true ->
                    PreviewToneMapStrategy.GAMMA_VALUE_22
                else -> PreviewToneMapStrategy.FAST_FALLBACK
            }
            PreviewToneMapStrategy.CONTRAST_CURVE_SRGB -> when {
                availableModes?.contains(CaptureRequest.TONEMAP_MODE_GAMMA_VALUE) == true ->
                    PreviewToneMapStrategy.GAMMA_VALUE_22
                else -> PreviewToneMapStrategy.FAST_FALLBACK
            }
            PreviewToneMapStrategy.GAMMA_VALUE_22,
            PreviewToneMapStrategy.FAST_FALLBACK -> PreviewToneMapStrategy.FAST_FALLBACK
        }
    }
}
