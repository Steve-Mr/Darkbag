package top.maary.darkbag.imaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.nio.ByteBuffer

class ImagingNativeTest {

    @Test
    fun testCfaConstants() {
        assertEquals(0, ImagingNative.CFA_RGGB)
        assertEquals(1, ImagingNative.CFA_GRBG)
        assertEquals(2, ImagingNative.CFA_GBRG)
        assertEquals(3, ImagingNative.CFA_BGGR)
    }

    @Test
    fun testBurstCompactResultDataClass() {
        val result = ImagingNative.BurstCompactResult(
            anchorIndex = 2,
            acceptedCount = 3,
            acceptedIndices = intArrayOf(2, 0, 4)
        )
        assertEquals(2, result.anchorIndex)
        assertEquals(3, result.acceptedCount)
        assertEquals(3, result.acceptedIndices.size)
        assertEquals(2, result.acceptedIndices[0])
        assertEquals(0, result.acceptedIndices[1])
        assertEquals(4, result.acceptedIndices[2])
    }

    @Test
    fun testStridedThumbnailStepCalculation() {
        val width = 4000
        val height = 3000
        val targetW = 256
        val targetH = 256

        val stepX = (width / targetW) and 1.inv()
        val stepY = (height / targetH) and 1.inv()

        assertEquals(14, stepX) // 4000 / 256 = 15; 15 & ~1 = 14 (even!)
        assertEquals(10, stepY) // 3000 / 256 = 11; 11 & ~1 = 10 (even!)
        assertEquals(0, stepX % 2)
        assertEquals(0, stepY % 2)
    }
}
