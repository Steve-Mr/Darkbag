package top.maary.darkbag.camera.zsl

import android.hardware.camera2.TotalCaptureResult
import top.maary.darkbag.fragments.HdrPlusBurst

/**
 * Pre-roll frames selected at shutter time, base frame first.
 *
 * Ownership of every [ZslFrame.buffer] transfers to this seed. If the burst is not started,
 * [release] must be called; otherwise the HDR+ streaming burst owns and releases the buffers.
 */
data class ZslBurstSeed(
    val frames: List<ZslFrame>,
    val baseResult: TotalCaptureResult,
    val iso: Int,
    val exposureTimeNs: Long,
    val digitalGain: Float,
    val targetFrameCount: Int
) {
    val preRollCount: Int get() = frames.size
    val remainingCount: Int get() = (targetFrameCount - frames.size).coerceAtLeast(0)
    private val releasedIndices = mutableSetOf<Int>()

    fun release() = releaseFrom(0)

    /** Releases frames from [startIndex] onward, exactly once per index. */
    fun releaseFrom(startIndex: Int) {
        synchronized(releasedIndices) {
            for (i in startIndex.coerceAtLeast(0) until frames.size) {
                if (releasedIndices.add(i)) {
                    HdrPlusBurst.releaseBuffer(frames[i].buffer)
                }
            }
        }
    }
}
