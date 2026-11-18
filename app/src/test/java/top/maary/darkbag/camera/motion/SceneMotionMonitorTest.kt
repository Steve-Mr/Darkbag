package top.maary.darkbag.camera.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SceneMotionMonitorTest {

    @Test
    fun testInitialStateIsStill() {
        val monitor = SceneMotionMonitor()

        assertEquals(MotionLevel.STILL, monitor.currentLevel)
        assertEquals(MotionLevel.STILL, monitor.motionState.value)
        assertNull(monitor.lastResult)
    }

    @Test
    fun testFirstFrameInitializesBaselineWithoutMotion() {
        val monitor = SceneMotionMonitor()
        val grid = FloatArray(SceneMotionEstimator.TOTAL_CELLS) { 80.0f }

        val res = monitor.processGridForTest(grid, 80.0f, timestampNs = 1000L)

        assertNull(res)
        assertEquals(MotionLevel.STILL, monitor.currentLevel)
        assertEquals(MotionLevel.STILL, monitor.motionState.value)
    }

    @Test
    fun testStaticFramesMaintainStill() {
        val monitor = SceneMotionMonitor()
        val grid = FloatArray(SceneMotionEstimator.TOTAL_CELLS) { 100.0f }

        // Feed baseline frame
        monitor.processGridForTest(grid, 100.0f, timestampNs = 1_000_000_000L)

        // Feed subsequent identical frames
        for (i in 1..5) {
            val res = monitor.processGridForTest(grid, 100.0f, timestampNs = 1_000_000_000L + i * 33_000_000L)
            assertNotNull(res)
            assertEquals(0.0f, res!!.globalDelta, 1e-4f)
            assertEquals(0.0f, res.movingAreaRatio, 1e-4f)
            assertEquals(MotionLevel.STILL, monitor.currentLevel)
        }
    }

    @Test
    fun testTemporalSmoothingAveragesOutSingleGlitch() {
        val monitor = SceneMotionMonitor(historyWindowSize = 3)
        val staticGrid = FloatArray(SceneMotionEstimator.TOTAL_CELLS) { 100.0f }

        // Feed static frames to establish steady STILL history
        for (i in 0..3) {
            monitor.processGridForTest(staticGrid, 100.0f, timestampNs = i * 33_000_000L)
        }
        assertEquals(MotionLevel.STILL, monitor.currentLevel)

        // Single frame glitch (52 cells modified: 26 increased, 26 decreased to keep mean unchanged)
        // 52 / 192 = 27.08% area (> 25% threshold for FAST)
        val glitchGrid = staticGrid.clone()
        for (i in 0 until 26) {
            glitchGrid[i] = 135.0f
        }
        for (i in 26 until 52) {
            glitchGrid[i] = 65.0f
        }
        val glitchMean = glitchGrid.average().toFloat()

        val rawRes = monitor.processGridForTest(glitchGrid, glitchMean, timestampNs = 132_000_000L)
        assertNotNull(rawRes)
        assertEquals(MotionLevel.FAST, rawRes!!.level)

        // Due to 3-frame history smoothing [STILL, STILL, FAST], the smoothed level should NOT jump to FAST
        assertEquals(MotionLevel.MODERATE, monitor.currentLevel)
    }

    @Test
    fun testSustainedMotionReachesFast() {
        val monitor = SceneMotionMonitor(historyWindowSize = 3)
        val gridA = FloatArray(SceneMotionEstimator.TOTAL_CELLS) {
            val c = it % SceneMotionEstimator.GRID_COLS
            (c * 15f + 10f)
        }
        val gridB = FloatArray(SceneMotionEstimator.TOTAL_CELLS) {
            val c = (it % SceneMotionEstimator.GRID_COLS + SceneMotionEstimator.GRID_COLS / 2) % SceneMotionEstimator.GRID_COLS
            val r = it / SceneMotionEstimator.GRID_COLS
            gridA[r * SceneMotionEstimator.GRID_COLS + c]
        }

        // Frame 0: Baseline
        monitor.processGridForTest(gridA, gridA.average().toFloat(), timestampNs = 0L)

        // Sustained motion frames
        for (i in 1..4) {
            val activeGrid = if (i % 2 == 1) gridB else gridA
            monitor.processGridForTest(activeGrid, activeGrid.average().toFloat(), timestampNs = i * 33_000_000L)
        }

        assertEquals(MotionLevel.FAST, monitor.currentLevel)
        assertEquals(MotionLevel.FAST, monitor.motionState.value)
    }

    @Test
    fun testResetClearsHistoryAndState() {
        val monitor = SceneMotionMonitor()
        val gridA = FloatArray(SceneMotionEstimator.TOTAL_CELLS) { 50.0f }
        val gridB = FloatArray(SceneMotionEstimator.TOTAL_CELLS) { 200.0f }

        monitor.processGridForTest(gridA, 50.0f, timestampNs = 0L)
        monitor.processGridForTest(gridB, 200.0f, timestampNs = 33_000_000L)
        monitor.processGridForTest(gridA, 50.0f, timestampNs = 66_000_000L)
        monitor.processGridForTest(gridB, 200.0f, timestampNs = 99_000_000L)

        // Now reset
        monitor.reset()

        assertEquals(MotionLevel.STILL, monitor.currentLevel)
        assertEquals(MotionLevel.STILL, monitor.motionState.value)
        assertNull(monitor.lastResult)

        // Subsequent frame should act as frame 0 (baseline) again
        val res = monitor.processGridForTest(gridA, 50.0f, timestampNs = 132_000_000L)
        assertNull(res)
    }
}
