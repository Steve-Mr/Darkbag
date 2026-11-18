package top.maary.darkbag.camera.motion

import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max

data class MotionResult(
    val globalDelta: Float,
    val movingAreaRatio: Float,
    val level: MotionLevel
)

/**
 * Pure Kotlin scene motion estimator operating on normalized block-grid brightness features.
 *
 * Designed to detect both localized high-speed motion (e.g. hand waving, moving subjects)
 * and global scene shifts (camera shake / panning) without false-triggering on AE micro-adjustments
 * or low-light sensor noise.
 *
 * Zero Android SDK dependencies to guarantee 100% plain JVM unit testability.
 */
object SceneMotionEstimator {
    const val GRID_COLS = 16
    const val GRID_ROWS = 12
    const val TOTAL_CELLS = GRID_COLS * GRID_ROWS // 192

    const val SAMPLES_PER_CELL_DIM = 4
    const val SAMPLES_PER_CELL = SAMPLES_PER_CELL_DIM * SAMPLES_PER_CELL_DIM // 16

    const val SIGMA_FLOOR = 10.0f
    const val BLOCK_DIFF_THRESHOLD = 0.15f

    const val FAST_AREA_RATIO_THRESHOLD = 0.25f
    const val FAST_GLOBAL_DELTA_THRESHOLD = 0.30f

    const val MODERATE_AREA_RATIO_THRESHOLD = 0.08f
    const val MODERATE_GLOBAL_DELTA_THRESHOLD = 0.12f

    /**
     * Samples a 4x4 point lattice per grid cell from the Y plane buffer to calculate
     * cell average brightness and frame mean brightness \mu.
     *
     * @return Frame mean brightness \mu.
     */
    fun computeGrid(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        outGrid: FloatArray
    ): Float {
        require(outGrid.size >= TOTAL_CELLS) {
            "outGrid size must be at least $TOTAL_CELLS (was ${outGrid.size})"
        }
        if (width <= 0 || height <= 0 || rowStride <= 0 || pixelStride <= 0) {
            outGrid.fill(0f, 0, TOTAL_CELLS)
            return 0f
        }

        val bufLimit = buffer.limit()
        var totalGridSum = 0.0f

        for (r in 0 until GRID_ROWS) {
            val yStart = r * height / GRID_ROWS
            val yEnd = (r + 1) * height / GRID_ROWS
            val cellH = max(1, yEnd - yStart)

            for (c in 0 until GRID_COLS) {
                val xStart = c * width / GRID_COLS
                val xEnd = (c + 1) * width / GRID_COLS
                val cellW = max(1, xEnd - xStart)

                var cellSum = 0.0f
                var validSamples = 0

                for (sy in 0 until SAMPLES_PER_CELL_DIM) {
                    val sampleY = (yStart + (sy * 2 + 1) * cellH / (2 * SAMPLES_PER_CELL_DIM)).coerceIn(0, height - 1)
                    val rowOffset = sampleY * rowStride

                    for (sx in 0 until SAMPLES_PER_CELL_DIM) {
                        val sampleX = (xStart + (sx * 2 + 1) * cellW / (2 * SAMPLES_PER_CELL_DIM)).coerceIn(0, width - 1)
                        val offset = rowOffset + sampleX * pixelStride

                        if (offset in 0 until bufLimit) {
                            val v = buffer.get(offset).toInt() and 0xFF
                            cellSum += v
                            validSamples++
                        }
                    }
                }

                val cellAvg = if (validSamples > 0) cellSum / validSamples else 0f
                val cellIndex = r * GRID_COLS + c
                outGrid[cellIndex] = cellAvg
                totalGridSum += cellAvg
            }
        }

        return totalGridSum / TOTAL_CELLS
    }

    /**
     * Compares previous and current block grids with global exposure normalization and
     * low-light floor protection.
     *
     * @param prevGrid Grid brightness values from the previous frame.
     * @param prevMean Global mean brightness of the previous frame.
     * @param currGrid Grid brightness values from the current frame.
     * @param currMean Global mean brightness of the current frame.
     * @param deltaMs Elapsed time between frames in milliseconds.
     * @return Motion evaluation result including global delta, moving area ratio, and classified MotionLevel.
     */
    fun compareGrids(
        prevGrid: FloatArray,
        prevMean: Float,
        currGrid: FloatArray,
        currMean: Float,
        deltaMs: Long = 0L
    ): MotionResult {
        require(prevGrid.size >= TOTAL_CELLS && currGrid.size >= TOTAL_CELLS) {
            "Grid arrays must have at least $TOTAL_CELLS elements"
        }

        val s = prevMean / max(currMean, 0.001f)
        var residualSum = 0.0f
        var movingCount = 0

        for (k in 0 until TOTAL_CELLS) {
            val diff = abs(prevGrid[k] - s * currGrid[k])
            val denom = max(prevGrid[k], SIGMA_FLOOR)
            val dk = diff / denom
            residualSum += dk
            if (dk > BLOCK_DIFF_THRESHOLD) {
                movingCount++
            }
        }

        val globalDelta = residualSum / TOTAL_CELLS.toFloat()
        val movingAreaRatio = movingCount / TOTAL_CELLS.toFloat()
        val level = classify(globalDelta, movingAreaRatio)

        return MotionResult(
            globalDelta = globalDelta,
            movingAreaRatio = movingAreaRatio,
            level = level
        )
    }

    /**
     * Classifies motion metrics into discrete [MotionLevel] thresholds.
     */
    fun classify(globalDelta: Float, movingAreaRatio: Float): MotionLevel = when {
        movingAreaRatio >= FAST_AREA_RATIO_THRESHOLD || globalDelta >= FAST_GLOBAL_DELTA_THRESHOLD -> MotionLevel.FAST
        movingAreaRatio >= MODERATE_AREA_RATIO_THRESHOLD || globalDelta >= MODERATE_GLOBAL_DELTA_THRESHOLD -> MotionLevel.MODERATE
        else -> MotionLevel.STILL
    }
}
