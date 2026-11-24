package top.maary.darkbag.camera.session

import android.hardware.camera2.CaptureRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PreviewToneMapPolicyTest {

    @Test
    fun `PreviewToneMapStrategy contains exactly three tiers with expected shader domains`() {
        val names = PreviewToneMapStrategy.values().map { it.name }
        assertEquals(listOf("PRESET_CURVE_SRGB", "GAMMA_VALUE_22", "FAST_INVERSE_ISP"), names)
        assertFalse("CONTRAST_CURVE_SRGB must not exist", names.contains("CONTRAST_CURVE_SRGB"))

        assertEquals(0, PreviewToneMapStrategy.PRESET_CURVE_SRGB.shaderInputDomain)
        assertEquals(2, PreviewToneMapStrategy.GAMMA_VALUE_22.shaderInputDomain)
        assertEquals(1, PreviewToneMapStrategy.FAST_INVERSE_ISP.shaderInputDomain)
    }

    @Test
    fun `resolvePreviewToneMapStrategy selects three tiers in strict priority`() {
        // Tier 1: PRESET_CURVE_SRGB wins whenever TONEMAP_MODE_PRESET_CURVE (4) is present
        val tier1 = PreviewToneMapPolicy.resolvePreviewToneMapStrategy(
            intArrayOf(
                CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE,
                CaptureRequest.TONEMAP_MODE_FAST,
                CaptureRequest.TONEMAP_MODE_HIGH_QUALITY,
                CaptureRequest.TONEMAP_MODE_GAMMA_VALUE,
                CaptureRequest.TONEMAP_MODE_PRESET_CURVE
            ),
            maxCurvePoints = 64
        )
        assertEquals(PreviewToneMapStrategy.PRESET_CURVE_SRGB, tier1)
        assertEquals(0, tier1.shaderInputDomain)

        // Tier 2: GAMMA_VALUE_22 is selected when GAMMA_VALUE is present and PRESET_CURVE is absent
        val tier2 = PreviewToneMapPolicy.resolvePreviewToneMapStrategy(
            intArrayOf(
                CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE,
                CaptureRequest.TONEMAP_MODE_FAST,
                CaptureRequest.TONEMAP_MODE_HIGH_QUALITY,
                CaptureRequest.TONEMAP_MODE_GAMMA_VALUE
            ),
            maxCurvePoints = 64
        )
        assertEquals(PreviewToneMapStrategy.GAMMA_VALUE_22, tier2)
        assertEquals(2, tier2.shaderInputDomain)

        // Tier 3: Moto XT2409-5 (ZY22KMP2XK) reports [0, 1, 2] -> hits FAST_INVERSE_ISP (shaderInputDomain = 1)
        val motoDevice = PreviewToneMapPolicy.resolvePreviewToneMapStrategy(
            intArrayOf(
                CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE,
                CaptureRequest.TONEMAP_MODE_FAST,
                CaptureRequest.TONEMAP_MODE_HIGH_QUALITY
            ),
            maxCurvePoints = 64
        )
        assertEquals(PreviewToneMapStrategy.FAST_INVERSE_ISP, motoDevice)
        assertEquals(1, motoDevice.shaderInputDomain)

        // FAST/HIGH_QUALITY only or null/empty falls back to FAST_INVERSE_ISP
        val fastOnly = PreviewToneMapPolicy.resolvePreviewToneMapStrategy(
            intArrayOf(
                CaptureRequest.TONEMAP_MODE_FAST,
                CaptureRequest.TONEMAP_MODE_HIGH_QUALITY
            ),
            maxCurvePoints = 64
        )
        assertEquals(PreviewToneMapStrategy.FAST_INVERSE_ISP, fastOnly)
        assertEquals(1, fastOnly.shaderInputDomain)

        val nullModes = PreviewToneMapPolicy.resolvePreviewToneMapStrategy(null, maxCurvePoints = 64)
        assertEquals(PreviewToneMapStrategy.FAST_INVERSE_ISP, nullModes)
        assertEquals(1, nullModes.shaderInputDomain)

        val emptyModes = PreviewToneMapPolicy.resolvePreviewToneMapStrategy(intArrayOf(), maxCurvePoints = 64)
        assertEquals(PreviewToneMapStrategy.FAST_INVERSE_ISP, emptyModes)
        assertEquals(1, emptyModes.shaderInputDomain)
    }

    @Test
    fun `nextFallbackStrategy downgrades through tiers predictably`() {
        val modesWithGamma = intArrayOf(
            CaptureRequest.TONEMAP_MODE_FAST,
            CaptureRequest.TONEMAP_MODE_GAMMA_VALUE
        )
        assertEquals(
            PreviewToneMapStrategy.GAMMA_VALUE_22,
            PreviewToneMapPolicy.nextFallbackStrategy(
                PreviewToneMapStrategy.PRESET_CURVE_SRGB,
                modesWithGamma,
                64
            )
        )

        val modesWithoutGamma = intArrayOf(
            CaptureRequest.TONEMAP_MODE_FAST,
            CaptureRequest.TONEMAP_MODE_HIGH_QUALITY
        )
        assertEquals(
            PreviewToneMapStrategy.FAST_INVERSE_ISP,
            PreviewToneMapPolicy.nextFallbackStrategy(
                PreviewToneMapStrategy.PRESET_CURVE_SRGB,
                modesWithoutGamma,
                64
            )
        )

        assertEquals(
            PreviewToneMapStrategy.FAST_INVERSE_ISP,
            PreviewToneMapPolicy.nextFallbackStrategy(
                PreviewToneMapStrategy.GAMMA_VALUE_22,
                modesWithGamma,
                64
            )
        )

        assertEquals(
            PreviewToneMapStrategy.FAST_INVERSE_ISP,
            PreviewToneMapPolicy.nextFallbackStrategy(
                PreviewToneMapStrategy.FAST_INVERSE_ISP,
                modesWithGamma,
                64
            )
        )
    }
}
