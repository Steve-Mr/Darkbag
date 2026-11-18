package top.maary.darkbag.camera.zsl

import kotlin.math.abs

/**
 * Pure pre-roll selection used by [ZslRawRing].
 *
 * Keeping the time/exposure/WB/AF rules here makes the ZSL quality gate testable without
 * Camera2 or native buffers.
 */
data class ZslFrameSample(
    val timestamp: Long,
    val hasResult: Boolean,
    val iso: Int?,
    val exposureTimeNs: Long?,
    val wb: FloatArray?,
    val cropKey: String?,
    val afFocused: Boolean
)

object ZslFrameSelector {
    const val EXPOSURE_TOLERANCE = 0.05f
    const val WB_TOLERANCE = 0.05f
    const val AF_TIMEOUT_NS = 2_000_000_000L

    fun nearestIndex(samples: List<ZslFrameSample>, t0Ns: Long, maxAgeNs: Long): Int? {
        val valid = samples.indices.filter {
            samples[it].hasResult && samples[it].iso != null && samples[it].exposureTimeNs != null
        }
        val best = valid.minByOrNull { abs(samples[it].timestamp - t0Ns) } ?: return null
        if (abs(samples[best].timestamp - t0Ns) > maxAgeNs) return null
        return best
    }

    /**
     * Returns indices of a stable consecutive run, base frame first.
     * Returns empty when fewer than 3 frames share exposure/WB/crop/AF state.
     */
    fun stableRunIndices(
        samples: List<ZslFrameSample>,
        t0Ns: Long,
        targetCount: Int,
        maxAgeNs: Long
    ): List<Int> {
        if (targetCount < 3 || samples.size < 3) return emptyList()
        val all = samples.withIndex()
            .filter { it.value.hasResult && it.value.iso != null && it.value.exposureTimeNs != null }
            .sortedBy { it.value.timestamp }
        if (all.size < 3) return emptyList()

        val nearestPos = all.indices.minByOrNull { abs(all[it].value.timestamp - t0Ns) } ?: return emptyList()
        if (abs(all[nearestPos].value.timestamp - t0Ns) > maxAgeNs) return emptyList()

        val start = (nearestPos - targetCount + 1).coerceAtLeast(0)
        var run = all.subList(start, nearestPos + 1)
        if (run.size < 3) return emptyList()

        while (run.size >= 3) {
            if (isStable(run.map { it.value })) {
                val base = run.last().index
                val others = run.dropLast(1).sortedByDescending { it.value.timestamp }.map { it.index }
                return listOf(base) + others
            }
            run = run.drop(1)
        }
        return emptyList()
    }

    fun isStable(run: List<ZslFrameSample>): Boolean {
        val base = run.lastOrNull() ?: return false
        val baseIso = base.iso ?: return false
        val baseExposure = base.exposureTimeNs ?: return false
        if (baseExposure <= 0 || base.wb == null) return false
        return run.all { frame ->
            val iso = frame.iso ?: return@all false
            val exposure = frame.exposureTimeNs ?: return@all false
            if (exposure <= 0) return@all false
            if (abs(iso - baseIso).toFloat() / baseIso > EXPOSURE_TOLERANCE) return@all false
            if (abs(exposure - baseExposure).toFloat() / baseExposure > EXPOSURE_TOLERANCE) return@all false
            if (frame.cropKey != base.cropKey) return@all false
            if (!frame.afFocused && abs(frame.timestamp - base.timestamp) > AF_TIMEOUT_NS) return@all false
            wbClose(frame.wb, base.wb)
        }
    }

    private fun wbClose(a: FloatArray?, b: FloatArray?): Boolean {
        if (a == null || b == null || a.size < 4 || b.size < 4) return false
        for (i in 0 until 4) {
            val base = b[i]
            if (base <= 0f) continue
            if (abs(a[i] - base) / base > WB_TOLERANCE) return false
        }
        return true
    }
}
