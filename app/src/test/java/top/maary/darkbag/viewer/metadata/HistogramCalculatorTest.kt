package top.maary.darkbag.viewer.metadata

import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistogramCalculatorTest {

    @Test
    fun testComputeFromPixelsEmptyReturnsZeros() {
        val hist = HistogramCalculator.computeFromPixels(intArrayOf())
        assertEquals(256, hist.red.size)
        assertEquals(0f, hist.red[0], 0.0001f)
        assertEquals(0f, hist.luma[255], 0.0001f)
    }

    @Test
    fun testComputeFromPixelsDetectsPureRedPeak() {
        val argb = (0xFF shl 24) or (200 shl 16) or (20 shl 8) or 10
        val pixels = IntArray(64) { argb }
        val hist = HistogramCalculator.computeFromPixels(pixels)
        assertTrue(hist.red[200] > 0.9f)
        assertTrue(hist.green[20] > 0.9f)
        assertTrue(hist.blue[10] > 0.9f)
        assertEquals(0f, hist.red[0], 0.0001f)
    }
}
