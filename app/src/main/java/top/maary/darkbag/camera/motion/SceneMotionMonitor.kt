package top.maary.darkbag.camera.motion

import android.media.Image
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.ArrayDeque

/**
 * Android component owning scene motion detection state.
 *
 * Consumes downsampled analysis frames (YUV_420_888 Y-plane) to evaluate scene motion level
 * and exposes continuous reactive state for adaptive capture strategies.
 */
class SceneMotionMonitor(
    private val historyWindowSize: Int = DEFAULT_HISTORY_WINDOW
) {
    init {
        require(historyWindowSize > 0) { "historyWindowSize must be > 0" }
    }
    companion object {
        private const val TAG = "SceneMotionMonitor"
        const val DEFAULT_HISTORY_WINDOW = 3
    }

    private val lock = Any()
    private val prevGrid = FloatArray(SceneMotionEstimator.TOTAL_CELLS)
    private val currGrid = FloatArray(SceneMotionEstimator.TOTAL_CELLS)
    private var prevMean = 0f
    private var hasPrevGrid = false
    private var lastFrameTimeNs = 0L

    private val history = ArrayDeque<MotionResult>(historyWindowSize)

    @Volatile
    var currentLevel: MotionLevel = MotionLevel.STILL
        private set

    @Volatile
    var lastResult: MotionResult? = null
        private set

    private val _motionState = MutableStateFlow(MotionLevel.STILL)
    val motionState: StateFlow<MotionLevel> = _motionState.asStateFlow()

    /**
     * Extracts Y-plane from an analysis frame, evaluates scene motion against the previous frame,
     * and updates [currentLevel] and [motionState] through a temporal smoothing window.
     *
     * Safely wrapped in try-catch to guarantee zero pipeline crashes.
     */
    fun processAnalysisImage(image: Image) {
        try {
            val planes = image.planes
            if (planes.isNullOrEmpty()) return
            val yPlane = planes[0] ?: return
            val buffer = yPlane.buffer ?: return
            val width = image.width
            val height = image.height
            val rowStride = yPlane.rowStride
            val pixelStride = yPlane.pixelStride
            val timestampNs = image.timestamp

            synchronized(lock) {
                val currMean = SceneMotionEstimator.computeGrid(
                    buffer = buffer,
                    width = width,
                    height = height,
                    rowStride = rowStride,
                    pixelStride = pixelStride,
                    outGrid = currGrid
                )

                updateWithGridLocked(currGrid, currMean, timestampNs)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to process analysis image for scene motion: ${e.message}")
        }
    }

    internal fun processGridForTest(grid: FloatArray, mean: Float, timestampNs: Long = 0L): MotionResult? {
        synchronized(lock) {
            return updateWithGridLocked(grid, mean, timestampNs)
        }
    }

    private fun updateWithGridLocked(grid: FloatArray, mean: Float, timestampNs: Long): MotionResult? {
        if (!hasPrevGrid) {
            System.arraycopy(grid, 0, prevGrid, 0, SceneMotionEstimator.TOTAL_CELLS)
            prevMean = mean
            lastFrameTimeNs = timestampNs
            hasPrevGrid = true
            return null
        }

        val deltaMs = if (lastFrameTimeNs > 0L && timestampNs > lastFrameTimeNs) {
            (timestampNs - lastFrameTimeNs) / 1_000_000L
        } else {
            0L
        }

        if (deltaMs > 500L || (lastFrameTimeNs > 0L && timestampNs < lastFrameTimeNs)) {
            System.arraycopy(grid, 0, prevGrid, 0, SceneMotionEstimator.TOTAL_CELLS)
            prevMean = mean
            lastFrameTimeNs = timestampNs
            hasPrevGrid = true
            history.clear()
            lastResult = null
            currentLevel = MotionLevel.STILL
            _motionState.value = MotionLevel.STILL
            return null
        }

        val result = SceneMotionEstimator.compareGrids(
            prevGrid = prevGrid,
            prevMean = prevMean,
            currGrid = grid,
            currMean = mean,
            deltaMs = deltaMs
        )

        System.arraycopy(grid, 0, prevGrid, 0, SceneMotionEstimator.TOTAL_CELLS)
        prevMean = mean
        lastFrameTimeNs = timestampNs

        if (history.size >= historyWindowSize) {
            history.removeFirst()
        }
        history.addLast(result)

        var sumDelta = 0f
        var sumArea = 0f
        for (item in history) {
            sumDelta += item.globalDelta
            sumArea += item.movingAreaRatio
        }
        val count = history.size.toFloat()
        val avgDelta = sumDelta / count
        val avgArea = sumArea / count
        val smoothedLevel = SceneMotionEstimator.classify(avgDelta, avgArea)

        lastResult = result
        currentLevel = smoothedLevel
        _motionState.value = smoothedLevel

        return result
    }

    /**
     * Clears history and resets motion detection state to [MotionLevel.STILL].
     * Invoked upon session start, lens switch, or hardware teardown.
     */
    fun reset() {
        synchronized(lock) {
            hasPrevGrid = false
            prevMean = 0f
            lastFrameTimeNs = 0L
            history.clear()
            prevGrid.fill(0f)
            currGrid.fill(0f)
            lastResult = null
            currentLevel = MotionLevel.STILL
            _motionState.value = MotionLevel.STILL
        }
    }
}
