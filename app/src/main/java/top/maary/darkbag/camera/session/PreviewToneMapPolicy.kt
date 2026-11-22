package top.maary.darkbag.camera.session

import android.hardware.camera2.CaptureRequest
import android.util.Log

enum class PreviewToneMapStrategy(val shaderInputDomain: Int) {
    PRESET_CURVE_SRGB(0),
    GAMMA_VALUE_22(2),
    FAST_INVERSE_ISP(1)
}

object PreviewToneMapPolicy {
    private const val GAMMA_22 = 2.2f

    fun resolvePreviewToneMapStrategy(
        availableModes: IntArray?,
        maxCurvePoints: Int
    ): PreviewToneMapStrategy {
        if (availableModes == null || availableModes.isEmpty()) {
            return PreviewToneMapStrategy.FAST_INVERSE_ISP
        }
        return when {
            availableModes.contains(CaptureRequest.TONEMAP_MODE_PRESET_CURVE) ->
                PreviewToneMapStrategy.PRESET_CURVE_SRGB
            availableModes.contains(CaptureRequest.TONEMAP_MODE_GAMMA_VALUE) ->
                PreviewToneMapStrategy.GAMMA_VALUE_22
            else ->
                PreviewToneMapStrategy.FAST_INVERSE_ISP
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
                    PreviewToneMapStrategy.GAMMA_VALUE_22 -> {
                        request.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_GAMMA_VALUE)
                        request.set(CaptureRequest.TONEMAP_GAMMA, GAMMA_22)
                    }
                    PreviewToneMapStrategy.FAST_INVERSE_ISP -> {
                        request.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_FAST)
                    }
                }
                return current
            } catch (e: IllegalArgumentException) {
                val next = nextFallbackStrategy(current, availableModes, maxCurvePoints)
                Log.w(tag, "ToneMap strategy $current rejected by device, falling back to $next", e)
                if (next == current) {
                    request.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_FAST)
                    return PreviewToneMapStrategy.FAST_INVERSE_ISP
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
                availableModes?.contains(CaptureRequest.TONEMAP_MODE_GAMMA_VALUE) == true ->
                    PreviewToneMapStrategy.GAMMA_VALUE_22
                else -> PreviewToneMapStrategy.FAST_INVERSE_ISP
            }
            PreviewToneMapStrategy.GAMMA_VALUE_22,
            PreviewToneMapStrategy.FAST_INVERSE_ISP -> PreviewToneMapStrategy.FAST_INVERSE_ISP
        }
    }
}
