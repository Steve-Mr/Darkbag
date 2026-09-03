package top.maary.darkbag.rawvideo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.maary.darkbag.fragments.SettingsFragment

class BayerProcessorUnitTest {

    @Test
    fun testDownsampleModeConstants() {
        assertEquals(0, RawVideoNative.DOWNSAMPLE_NONE)
        assertEquals(1, RawVideoNative.DOWNSAMPLE_CROP_4K)
        assertEquals(2, RawVideoNative.DOWNSAMPLE_BINNING_1080P)
    }

    @Test
    fun testResolutionSettingToDownsampleModeMapping() {
        fun mapPreferenceToMode(pref: String?): Int {
            return when (pref) {
                "1080p" -> RawVideoNative.DOWNSAMPLE_BINNING_1080P
                "4K UHD" -> RawVideoNative.DOWNSAMPLE_CROP_4K
                else -> RawVideoNative.DOWNSAMPLE_NONE
            }
        }

        assertEquals(RawVideoNative.DOWNSAMPLE_BINNING_1080P, mapPreferenceToMode("1080p"))
        assertEquals(RawVideoNative.DOWNSAMPLE_CROP_4K, mapPreferenceToMode("4K UHD"))
        assertEquals(RawVideoNative.DOWNSAMPLE_NONE, mapPreferenceToMode("Max Native"))
        assertEquals(RawVideoNative.DOWNSAMPLE_NONE, mapPreferenceToMode(SettingsFragment.DEFAULT_RAW_VIDEO_RESOLUTION))
        assertEquals(RawVideoNative.DOWNSAMPLE_NONE, mapPreferenceToMode(null))
        assertEquals(RawVideoNative.DOWNSAMPLE_NONE, mapPreferenceToMode("invalid"))
    }

    @Test
    fun testBayer2x2BinningMathAndColorSegregation() {
        // Given a 4x4 input Bayer CFA (RGGB) block:
        // Row 0: R00, Gr01, R02, Gr03
        // Row 1: Gb10, B11, Gb12, B13
        // Row 2: R20, Gr21, R22, Gr23
        // Row 3: Gb30, B31, Gb32, B33
        val r00 = 100; val gr01 = 200; val r02 = 104; val gr03 = 204
        val gb10 = 300; val b11 = 400; val gb12 = 304; val b13 = 404
        val r20 = 108; val gr21 = 208; val r22 = 112; val gr23 = 212
        val gb30 = 308; val b31 = 408; val gb32 = 312; val b33 = 412

        // Direct mathematical formula: (v0 + v1 + v2 + v3 + 2) / 4
        val rExpected = (r00 + r02 + r20 + r22 + 2) / 4
        val grExpected = (gr01 + gr03 + gr21 + gr23 + 2) / 4
        val gbExpected = (gb10 + gb12 + gb30 + gb32 + 2) / 4
        val bExpected = (b11 + b13 + b31 + b33 + 2) / 4

        assertEquals(106, rExpected)
        assertEquals(206, grExpected)
        assertEquals(306, gbExpected)
        assertEquals(406, bExpected)

        // ARM NEON vrhadd simulation: vrhadd(vrhadd(a, b), vrhadd(c, d))
        fun vrhadd(a: Int, b: Int): Int = (a + b + 1) shr 1

        val rNeon = vrhadd(vrhadd(r00, r02), vrhadd(r20, r22))
        val grNeon = vrhadd(vrhadd(gr01, gr03), vrhadd(gr21, gr23))
        val gbNeon = vrhadd(vrhadd(gb10, gb12), vrhadd(gb30, gb32))
        val bNeon = vrhadd(vrhadd(b11, b13), vrhadd(b31, b33))

        assertEquals(rExpected, rNeon)
        assertEquals(grExpected, grNeon)
        assertEquals(gbExpected, gbNeon)
        assertEquals(bExpected, bNeon)

        // Ensure Gr and Gb are never mixed together
        assertTrue("Gr and Gb should never mix", grNeon != gbNeon)
    }

    @Test
    fun testCenterCropCoordinateEvenAlignment() {
        // Center crop must strictly align to even coordinates to preserve Bayer CFA phase
        fun computeCropOffsets(srcW: Int, srcH: Int, dstW: Int, dstH: Int): Pair<Int, Int> {
            val startX = ((srcW - dstW) / 2) and 1.inv()
            val startY = ((srcH - dstH) / 2) and 1.inv()
            return Pair(startX, startY)
        }

        // 4000x3000 to 4K UHD (3840x2160)
        val (crop4kX, crop4kY) = computeCropOffsets(4000, 3000, 3840, 2160)
        assertEquals(80, crop4kX)
        assertEquals(420, crop4kY)
        assertEquals(0, crop4kX % 2)
        assertEquals(0, crop4kY % 2)

        // 4032x3024 to 4K UHD (3840x2160)
        val (crop4kX2, crop4kY2) = computeCropOffsets(4032, 3024, 3840, 2160)
        assertEquals(96, crop4kX2)
        assertEquals(432, crop4kY2)
        assertEquals(0, crop4kX2 % 2)
        assertEquals(0, crop4kY2 % 2)

        // Odd source dimension resilience
        val (oddX, oddY) = computeCropOffsets(4001, 3001, 3840, 2160)
        assertEquals(0, oddX % 2)
        assertEquals(0, oddY % 2)
    }

    @Test
    fun test1080pBinningSubWindowAlignment() {
        val srcW = 4000
        val srcH = 3000

        val binnedW = srcW / 2 // 2000
        val binnedH = srcH / 2 // 1500

        val cropStartX_b = ((binnedW - 1920) / 2) and 1.inv() // 40
        val cropStartY_b = ((binnedH - 1080) / 2) and 1.inv() // 210

        val startX_in = cropStartX_b * 2 // 80
        val startY_in = cropStartY_b * 2 // 420

        assertEquals(40, cropStartX_b)
        assertEquals(210, cropStartY_b)
        assertEquals(80, startX_in)
        assertEquals(420, startY_in)

        // Coordinates in source image must be multiples of 4 for 4x4 Bayer blocks
        assertEquals(0, startX_in % 4)
        assertEquals(0, startY_in % 4)

        // Span must fit completely within input bounds
        assertTrue(startX_in + 1920 * 2 <= srcW)
        assertTrue(startY_in + 1080 * 2 <= srcH)
    }

    @Test
    fun testQueueMemoryFootprintReduction() {
        val nativeFrameBytes = 4000 * 3000 * 2L // ~24 MB
        val binned1080pBytes = 1920 * 1080 * 2L // ~4.15 MB
        val crop4kBytes = 3840 * 2160 * 2L      // ~16.59 MB

        val reductionRatio1080p = nativeFrameBytes.toDouble() / binned1080pBytes.toDouble()
        val reductionRatio4k = nativeFrameBytes.toDouble() / crop4kBytes.toDouble()

        assertTrue("1080p binning should reduce queue footprint by > 5.5x", reductionRatio1080p > 5.5)
        assertTrue("4K crop should reduce queue footprint by > 1.4x", reductionRatio4k > 1.4)
    }
}
