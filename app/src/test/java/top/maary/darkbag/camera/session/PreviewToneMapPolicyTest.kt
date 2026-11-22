package top.maary.darkbag.camera.session

import android.hardware.camera2.CaptureRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs
import kotlin.math.pow

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PreviewToneMapPolicyTest {

    private fun trueSrgbOetf(x: Double): Double =
        if (x <= 0.0031308) 12.92 * x else 1.055 * x.pow(1.0 / 2.4) - 0.055

    private fun evalPiecewiseLinear(pts: FloatArray, x: Double): Double {
        if (x <= 0.0) return 0.0
        if (x >= 1.0) return 1.0
        val count = pts.size / 2
        for (i in 0 until count - 1) {
            val x0 = pts[i * 2].toDouble()
            val x1 = pts[(i + 1) * 2].toDouble()
            if (x in x0..x1) {
                val y0 = pts[i * 2 + 1].toDouble()
                val y1 = pts[(i + 1) * 2 + 1].toDouble()
                val t = if (x1 > x0) (x - x0) / (x1 - x0) else 0.0
                return y0 + t * (y1 - y0)
            }
        }
        return pts[pts.size - 1].toDouble()
    }

    @Test
    fun `resolvePreviewToneMapStrategy selects four tiers in strict priority`() {
        // Tier 1: PRESET_CURVE_SRGB wins whenever TONEMAP_MODE_PRESET_CURVE (4) is present
        assertEquals(
            PreviewToneMapStrategy.PRESET_CURVE_SRGB,
            PreviewToneMapPolicy.resolvePreviewToneMapStrategy(
                intArrayOf(
                    CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE,
                    CaptureRequest.TONEMAP_MODE_FAST,
                    CaptureRequest.TONEMAP_MODE_HIGH_QUALITY,
                    CaptureRequest.TONEMAP_MODE_GAMMA_VALUE,
                    CaptureRequest.TONEMAP_MODE_PRESET_CURVE
                ),
                maxCurvePoints = 64
            )
        )

        // Tier 2: Moto XT2409-5 (ZY22KMP2XK) reports [0, 1, 2] with maxCurvePoints = 64
        assertEquals(
            PreviewToneMapStrategy.CONTRAST_CURVE_SRGB,
            PreviewToneMapPolicy.resolvePreviewToneMapStrategy(
                intArrayOf(
                    CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE,
                    CaptureRequest.TONEMAP_MODE_FAST,
                    CaptureRequest.TONEMAP_MODE_HIGH_QUALITY
                ),
                maxCurvePoints = 64
            )
        )

        // Tier 3: CONTRAST_CURVE with insufficient curve points (< 16) falls through to GAMMA_VALUE_22
        assertEquals(
            PreviewToneMapStrategy.GAMMA_VALUE_22,
            PreviewToneMapPolicy.resolvePreviewToneMapStrategy(
                intArrayOf(
                    CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE,
                    CaptureRequest.TONEMAP_MODE_FAST,
                    CaptureRequest.TONEMAP_MODE_GAMMA_VALUE
                ),
                maxCurvePoints = 12
            )
        )

        // Tier 4: Only FAST/HIGH_QUALITY or null/empty falls back to FAST_FALLBACK
        assertEquals(
            PreviewToneMapStrategy.FAST_FALLBACK,
            PreviewToneMapPolicy.resolvePreviewToneMapStrategy(
                intArrayOf(
                    CaptureRequest.TONEMAP_MODE_FAST,
                    CaptureRequest.TONEMAP_MODE_HIGH_QUALITY
                ),
                maxCurvePoints = 64
            )
        )
        assertEquals(
            PreviewToneMapStrategy.FAST_FALLBACK,
            PreviewToneMapPolicy.resolvePreviewToneMapStrategy(null, maxCurvePoints = 64)
        )
    }

    @Test
    fun `buildSrgbTonemapCurvePoints has exact endpoints, strict monotonicity, and max error under 0_003`() {
        val pts = PreviewToneMapPolicy.buildSrgbTonemapCurvePoints(maxCurvePoints = 64)
        assertEquals(64, pts.size) // 32 (x, y) pairs

        // Exact endpoints
        assertEquals(0.0f, pts[0], 0.0f)
        assertEquals(0.0f, pts[1], 0.0f)
        assertEquals(1.0f, pts[pts.size - 2], 0.0f)
        assertEquals(1.0f, pts[pts.size - 1], 0.0f)

        // Strictly monotonically increasing x and y
        for (i in 1 until 32) {
            assertTrue("x[$i] <= x[${i - 1}]", pts[i * 2] > pts[(i - 1) * 2])
            assertTrue("y[$i] <= y[${i - 1}]", pts[i * 2 + 1] > pts[(i - 1) * 2 + 1])
        }

        // Piecewise-linear interpolation error across [0, 1] vs true sRGB OETF < 0.003 (< 1/255)
        var maxError = 0.0
        for (step in 0..10000) {
            val x = step / 10000.0
            val approx = evalPiecewiseLinear(pts, x)
            val exact = trueSrgbOetf(x)
            val err = abs(approx - exact)
            if (err > maxError) maxError = err
        }
        assertTrue("maxError=$maxError must be < 0.003", maxError < 0.003)
    }

    @Test
    fun `getOrCreateSrgbTonemapCurve caches TonemapCurve instance by N`() {
        val c1 = PreviewToneMapPolicy.getOrCreateSrgbTonemapCurve(64)
        val c2 = PreviewToneMapPolicy.getOrCreateSrgbTonemapCurve(32)
        assertSame(c1, c2)
        assertEquals(32, c1.getPointCount(0))
    }
}
