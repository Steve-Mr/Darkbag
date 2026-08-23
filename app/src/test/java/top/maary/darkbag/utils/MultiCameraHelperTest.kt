package top.maary.darkbag.utils

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class MultiCameraHelperTest {

    private fun createMockLens(id: String, name: String, multiplier: Float, type: LensType): PhysicalLensInfo {
        return PhysicalLensInfo(
            physicalId = id,
            name = name,
            focalLength = 5.0f * multiplier,
            equivalentFocalLength = 24.0f * multiplier,
            multiplier = multiplier,
            type = type,
            characteristics = null
        )
    }

    @Test
    fun testResolveActivePhysicalLenses_AutoMaxTriple() {
        val lensUW = createMockLens("2", "0.6x", 0.6f, LensType.ULTRA_WIDE)
        val lensWide = createMockLens("0", "1.0x", 1.0f, LensType.WIDE)
        val lensTele = createMockLens("3", "3.0x", 3.0f, LensType.TELE)

        val info = LogicalMultiCameraInfo(
            logicalCameraId = "0",
            isLogicalMultiCamera = true,
            syncType = 1,
            physicalLenses = listOf(lensUW, lensWide, lensTele)
        )

        val resolved = MultiCameraHelper.resolveActivePhysicalLenses(
            logicalInfo = info,
            countPref = MultiCameraCountPreference.AUTO_MAX,
            pairPref = DualLensPairPreference.WIDE_ULTRAWIDE,
            maxHardwareSupported = 3
        )

        assertEquals(3, resolved.size)
        assertEquals("0.6x", resolved[0].name)
        assertEquals("1.0x", resolved[1].name)
        assertEquals("3.0x", resolved[2].name)
    }

    @Test
    fun testResolveActivePhysicalLenses_DualWideTele() {
        val lensUW = createMockLens("2", "0.6x", 0.6f, LensType.ULTRA_WIDE)
        val lensWide = createMockLens("0", "1.0x", 1.0f, LensType.WIDE)
        val lensTele = createMockLens("3", "3.0x", 3.0f, LensType.TELE)

        val info = LogicalMultiCameraInfo(
            logicalCameraId = "0",
            isLogicalMultiCamera = true,
            syncType = 1,
            physicalLenses = listOf(lensUW, lensWide, lensTele)
        )

        val resolved = MultiCameraHelper.resolveActivePhysicalLenses(
            logicalInfo = info,
            countPref = MultiCameraCountPreference.DUAL,
            pairPref = DualLensPairPreference.WIDE_TELE,
            maxHardwareSupported = 3
        )

        assertEquals(2, resolved.size)
        assertEquals("1.0x", resolved[0].name)
        assertEquals("3.0x", resolved[1].name)
    }

    @Test
    fun testResolveActivePhysicalLenses_TripleFallbackWhenHardwareOnlySupports2() {
        val lensUW = createMockLens("2", "0.6x", 0.6f, LensType.ULTRA_WIDE)
        val lensWide = createMockLens("0", "1.0x", 1.0f, LensType.WIDE)
        val lensTele = createMockLens("3", "3.0x", 3.0f, LensType.TELE)

        val info = LogicalMultiCameraInfo(
            logicalCameraId = "0",
            isLogicalMultiCamera = true,
            syncType = 1,
            physicalLenses = listOf(lensUW, lensWide, lensTele)
        )

        // User selected TRIPLE, but hardware max is 2
        val resolved = MultiCameraHelper.resolveActivePhysicalLenses(
            logicalInfo = info,
            countPref = MultiCameraCountPreference.TRIPLE,
            pairPref = DualLensPairPreference.WIDE_ULTRAWIDE,
            maxHardwareSupported = 2
        )

        assertEquals(2, resolved.size)
        assertEquals("0.6x", resolved[0].name)
        assertEquals("1.0x", resolved[1].name)
    }
}
