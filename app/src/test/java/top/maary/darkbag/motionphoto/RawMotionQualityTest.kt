package top.maary.darkbag.motionphoto

import org.junit.Assert.assertEquals
import org.junit.Test
import top.maary.darkbag.R

class RawMotionQualityTest {

    @Test
    fun testEnumModesAndResourceIds() {
        assertEquals(0, RawMotionQuality.SUBSAMPLE_4X4.mode)
        assertEquals(R.string.raw_motion_quality_4x4, RawMotionQuality.SUBSAMPLE_4X4.titleRes)
        assertEquals(R.string.raw_motion_quality_4x4_desc, RawMotionQuality.SUBSAMPLE_4X4.descRes)

        assertEquals(1, RawMotionQuality.BINNING_2X2.mode)
        assertEquals(R.string.raw_motion_quality_2x2, RawMotionQuality.BINNING_2X2.titleRes)
        assertEquals(R.string.raw_motion_quality_2x2_desc, RawMotionQuality.BINNING_2X2.descRes)

        assertEquals(2, RawMotionQuality.NATIVE.mode)
        assertEquals(R.string.raw_motion_quality_native, RawMotionQuality.NATIVE.titleRes)
        assertEquals(R.string.raw_motion_quality_native_desc, RawMotionQuality.NATIVE.descRes)
    }

    @Test
    fun testFromMode() {
        assertEquals(RawMotionQuality.SUBSAMPLE_4X4, RawMotionQuality.fromMode(0))
        assertEquals(RawMotionQuality.BINNING_2X2, RawMotionQuality.fromMode(1))
        assertEquals(RawMotionQuality.NATIVE, RawMotionQuality.fromMode(2))
        // Fallback
        assertEquals(RawMotionQuality.BINNING_2X2, RawMotionQuality.fromMode(999))
    }

    @Test
    fun testFromLegacyWithVariousTypes() {
        // Direct Int
        assertEquals(RawMotionQuality.SUBSAMPLE_4X4, RawMotionQuality.fromLegacy(0))
        assertEquals(RawMotionQuality.BINNING_2X2, RawMotionQuality.fromLegacy(1))
        assertEquals(RawMotionQuality.NATIVE, RawMotionQuality.fromLegacy(2))

        // Numeric string
        assertEquals(RawMotionQuality.SUBSAMPLE_4X4, RawMotionQuality.fromLegacy("0"))
        assertEquals(RawMotionQuality.NATIVE, RawMotionQuality.fromLegacy("2"))

        // Legacy descriptive string
        assertEquals(RawMotionQuality.NATIVE, RawMotionQuality.fromLegacy("Native (Max Quality)"))
        assertEquals(RawMotionQuality.NATIVE, RawMotionQuality.fromLegacy("Native"))
        assertEquals(RawMotionQuality.NATIVE, RawMotionQuality.fromLegacy("原生全像素"))

        assertEquals(RawMotionQuality.SUBSAMPLE_4X4, RawMotionQuality.fromLegacy("4x4 Subsample (Fast)"))
        assertEquals(RawMotionQuality.SUBSAMPLE_4X4, RawMotionQuality.fromLegacy("4x4 Subsample"))
        assertEquals(RawMotionQuality.SUBSAMPLE_4X4, RawMotionQuality.fromLegacy("4x4 抽样"))

        assertEquals(RawMotionQuality.BINNING_2X2, RawMotionQuality.fromLegacy("2x2 Binning (Balanced)"))
        assertEquals(RawMotionQuality.BINNING_2X2, RawMotionQuality.fromLegacy("2x2 Binning"))
        assertEquals(RawMotionQuality.BINNING_2X2, RawMotionQuality.fromLegacy("2x2 合并"))

        // Null or unknown
        assertEquals(RawMotionQuality.BINNING_2X2, RawMotionQuality.fromLegacy(null))
        assertEquals(RawMotionQuality.BINNING_2X2, RawMotionQuality.fromLegacy("unknown_value"))
    }
}
