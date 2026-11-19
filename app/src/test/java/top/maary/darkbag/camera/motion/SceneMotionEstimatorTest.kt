package top.maary.darkbag.camera.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.util.Random

class SceneMotionEstimatorTest {

    @Test
    fun testStaticScene() {
        val prevGrid = FloatArray(SceneMotionEstimator.TOTAL_CELLS) { 100.0f }
        val currGrid = FloatArray(SceneMotionEstimator.TOTAL_CELLS) { 100.0f }
        val prevMean = 100.0f
        val currMean = 100.0f

        val result = SceneMotionEstimator.compareGrids(prevGrid, prevMean, currGrid, currMean)

        assertEquals(0.0f, result.globalDelta, 1e-5f)
        assertEquals(0.0f, result.movingAreaRatio, 1e-5f)
        assertEquals(MotionLevel.STILL, result.level)
    }

    @Test
    fun testGlobalExposureShiftNoMotion() {
        // Heterogeneous brightness pattern across grid cells
        val prevGrid = FloatArray(SceneMotionEstimator.TOTAL_CELLS) { (it * 1.2f + 20f) }
        val prevMean = prevGrid.average().toFloat()

        // 10% brightness scaling across all pixels (e.g. AE micro-fluctuation)
        val currGrid = FloatArray(SceneMotionEstimator.TOTAL_CELLS) { prevGrid[it] * 1.10f }
        val currMean = currGrid.average().toFloat()

        val result = SceneMotionEstimator.compareGrids(prevGrid, prevMean, currGrid, currMean)

        // The scale factor s = prevMean / currMean cancels out the uniform 10% scaling
        assertEquals(0.0f, result.globalDelta, 1e-4f)
        assertEquals(0.0f, result.movingAreaRatio, 1e-4f)
        assertEquals(MotionLevel.STILL, result.level)
    }

    @Test
    fun testLowLightNoiseNoMotion() {
        // Low light scene with brightness below sigma_floor (10.0f)
        val prevGrid = FloatArray(SceneMotionEstimator.TOTAL_CELLS) { 6.0f }
        val prevMean = 6.0f

        // Small random noise below sigma_floor (e.g. +/- 1.2)
        val random = Random(42)
        val currGrid = FloatArray(SceneMotionEstimator.TOTAL_CELLS) {
            6.0f + (random.nextFloat() * 2.4f - 1.2f)
        }
        val currMean = currGrid.average().toFloat()

        val result = SceneMotionEstimator.compareGrids(prevGrid, prevMean, currGrid, currMean)

        // Denominator is max(prevGrid[k], 10.0f) = 10.0f
        // dk <= 1.2 / 10.0 = 0.12 <= 0.15 (BLOCK_DIFF_THRESHOLD)
        // No cells counted as moving, globalDelta < 0.12f
        assertEquals(0.0f, result.movingAreaRatio, 1e-4f)
        assertTrue("globalDelta (${result.globalDelta}) should be < 0.12f", result.globalDelta < 0.12f)
        assertEquals(MotionLevel.STILL, result.level)
    }

    @Test
    fun testLocalMotion_HandWaving() {
        // 16 cols x 12 rows = 192 cells
        val prevGrid = FloatArray(SceneMotionEstimator.TOTAL_CELLS) { 80.0f }
        val prevMean = 80.0f

        val currGrid = prevGrid.clone()
        // Modify a 20% patch: 4 rows x 10 cols = 40 cells out of 192 (20.83%)
        var modifiedCount = 0
        for (r in 4 until 8) {
            for (c in 3 until 13) {
                val idx = r * SceneMotionEstimator.GRID_COLS + c
                currGrid[idx] = 15.0f // Hand enters, significantly altering this patch
                modifiedCount++
            }
        }
        assertEquals(40, modifiedCount)
        val currMean = currGrid.average().toFloat()

        val result = SceneMotionEstimator.compareGrids(prevGrid, prevMean, currGrid, currMean)

        assertTrue(
            "movingAreaRatio (${result.movingAreaRatio}) should be >= 0.20f",
            result.movingAreaRatio >= 0.20f
        )
        assertTrue(
            "level (${result.level}) should be MODERATE or FAST",
            result.level == MotionLevel.MODERATE || result.level == MotionLevel.FAST
        )
    }

    @Test
    fun testGlobalFastShake_Panning() {
        // High-contrast scene pattern
        val prevGrid = FloatArray(SceneMotionEstimator.TOTAL_CELLS) {
            val c = it % SceneMotionEstimator.GRID_COLS
            (c * 15f + 10f)
        }
        val prevMean = prevGrid.average().toFloat()

        // Large camera pan: horizontal shift by half the screen
        val currGrid = FloatArray(SceneMotionEstimator.TOTAL_CELLS) {
            val c = (it % SceneMotionEstimator.GRID_COLS + SceneMotionEstimator.GRID_COLS / 2) % SceneMotionEstimator.GRID_COLS
            val r = it / SceneMotionEstimator.GRID_COLS
            prevGrid[r * SceneMotionEstimator.GRID_COLS + c]
        }
        val currMean = currGrid.average().toFloat()

        val result = SceneMotionEstimator.compareGrids(prevGrid, prevMean, currGrid, currMean)

        assertTrue("globalDelta (${result.globalDelta}) should be >= 0.30f", result.globalDelta >= 0.30f)
        assertEquals(MotionLevel.FAST, result.level)
    }

    @Test
    fun testComputeGrid_UniformImage() {
        val width = 640
        val height = 480
        val rowStride = 640
        val pixelStride = 1
        val buffer = ByteBuffer.allocateDirect(width * height)
        for (i in 0 until width * height) {
            buffer.put(128.toByte())
        }
        buffer.flip()

        val outGrid = FloatArray(SceneMotionEstimator.TOTAL_CELLS)
        val mean = SceneMotionEstimator.computeGrid(buffer, width, height, rowStride, pixelStride, outGrid)

        assertEquals(128.0f, mean, 1e-4f)
        for (i in 0 until SceneMotionEstimator.TOTAL_CELLS) {
            assertEquals(128.0f, outGrid[i], 1e-4f)
        }
    }

    @Test
    fun testComputeGrid_NonUnitStride() {
        val width = 320
        val height = 240
        val rowStride = 350 // Padding at the end of each row
        val pixelStride = 1
        val buffer = ByteBuffer.allocateDirect(rowStride * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                buffer.put(y * rowStride + x, 200.toByte())
            }
        }

        val outGrid = FloatArray(SceneMotionEstimator.TOTAL_CELLS)
        val mean = SceneMotionEstimator.computeGrid(buffer, width, height, rowStride, pixelStride, outGrid)

        assertEquals(200.0f, mean, 1e-4f)
        for (i in 0 until SceneMotionEstimator.TOTAL_CELLS) {
            assertEquals(200.0f, outGrid[i], 1e-4f)
        }
    }

    @Test
    fun testComputeGrid_InvalidDimensionsReturnsZero() {
        val buffer = ByteBuffer.allocate(10)
        val outGrid = FloatArray(SceneMotionEstimator.TOTAL_CELLS) { 50f }

        val mean = SceneMotionEstimator.computeGrid(buffer, 0, 0, 0, 0, outGrid)

        assertEquals(0f, mean, 1e-5f)
        for (v in outGrid) {
            assertEquals(0f, v, 1e-5f)
        }
    }
}
