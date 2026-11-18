package top.maary.darkbag.camera.zsl

/**
 * ZSL capability model. Pure Kotlin; safe to unit-test without Android.
 *
 * These are *declared* capabilities only. Runtime arming/probing lives in
 * [ZslCaptureController]; a device may declare support and still fail the arm probe.
 */
enum class ZslRoute(val key: String) {
    NONE("none"),
    RAW_RING("raw_ring"),
    REPROCESS_RAW("reprocess_raw");

    val isAvailable: Boolean get() = this != NONE

    companion object {
        fun fromKey(key: String?): ZslRoute =
            entries.firstOrNull { it.key == key } ?: NONE
    }
}

enum class ZslReason(val key: String) {
    NONE("none"),
    API_TOO_LOW("api"),
    NO_CAMERA("no_camera"),
    NO_RAW("no_raw"),
    RAW_TOO_LARGE("raw_too_large"),
    NO_READ_SENSOR_SETTINGS("no_sensor_settings"),
    LOW_RAM("low_ram"),
    LOW_MEMORY("low_memory"),
    NO_REPROCESS_RAW("no_reprocess_raw"),
    HDR_UNSUPPORTED("hdr_unsupported"),
    UNKNOWN("unknown");

    companion object {
        fun fromKey(key: String?): ZslReason =
            entries.firstOrNull { it.key == key } ?: NONE
    }
}

/**
 * Static facts read from CameraCharacteristics. Keep this free of Android types so the
 * admission rules can be tested exhaustively on the JVM.
 */
data class ZslCameraFacts(
    val apiLevel: Int,
    val hardwareLevel: Int,
    val capabilities: Set<Int> = emptySet(),
    val rawSizes: List<Pair<Int, Int>> = emptyList(),
    val readSensorSettings: Boolean = false,
    val manualPostProcessing: Boolean = false,
    val timestampSource: Int = 0,
    val aeCompensationRange: IntRange? = null,
    val fpsRanges: List<IntRange> = emptyList(),
    val isLowRam: Boolean = false,
    val availableMemoryBytes: Long = Long.MAX_VALUE,
    val inputFormats: Set<Int> = emptySet(),
    val reprocessOutputFormats: Set<Int> = emptySet(),
    val flashAvailable: Boolean = false
)

data class ZslCapabilityReport(
    val route: ZslRoute,
    val reason: ZslReason = ZslReason.NONE,
    val cameraId: String? = null,
    val rawWidth: Int = 0,
    val rawHeight: Int = 0,
    val frameBytes: Long = 0L,
    val maxPreRollFrames: Int = 0,
    val supportsHdrPlusZsl: Boolean = false,
    val supportsRawReprocess: Boolean = false,
    val minAeCompensationSteps: Int = 0,
    val hdrFpsRange: IntRange? = null,
    val timestampRealtime: Boolean = true
) {
    val available: Boolean get() = route.isAvailable
}

private object ZslImageFormats {
    // Camera2 ImageFormat values kept local so the admission rules stay JVM-pure.
    const val RAW_SENSOR = 32
    const val PRIVATE = 34
}

/** Ring sizing rules. All time budgets are deliberately simple and measurable. */
data class ZslRingConfig(
    val depth: Int,
    val targetFps: Int,
    val maxBytes: Long,
    val frameBytes: Long,
    val maxAgeNs: Long
) {
    val totalBytes: Long get() = depth * frameBytes

    companion object {
        const val MAX_RING_BYTES: Long = 128L * 1024L * 1024L
        const val SINGLE_DEPTH: Int = 3
        const val MIN_HDR_DEPTH: Int = 3
        const val TARGET_FPS: Int = 30
        const val MAX_AGE_NS: Long = 300_000_000L

        fun maxByBudget(frameBytes: Long): Int {
            if (frameBytes <= 0L) return 0
            return (MAX_RING_BYTES / frameBytes).toInt().coerceAtLeast(0)
        }

        fun forSingle(frameBytes: Long): ZslRingConfig? {
            val depth = minOf(SINGLE_DEPTH, maxByBudget(frameBytes))
            if (depth <= 0) return null
            return ZslRingConfig(depth, TARGET_FPS, MAX_RING_BYTES, frameBytes, MAX_AGE_NS)
        }

        fun forHdrPlus(burstCount: Int, frameBytes: Long): ZslRingConfig? {
            val depth = minOf(burstCount.coerceAtLeast(0), 8, maxByBudget(frameBytes))
            if (depth < MIN_HDR_DEPTH) return null
            return ZslRingConfig(depth, TARGET_FPS, MAX_RING_BYTES, frameBytes, MAX_AGE_NS)
        }
    }
}

/** Immutable decision produced by the pure admission evaluator. */
object ZslCapabilityEvaluator {
    private const val MAX_FRAME_BYTES = 32L * 1024L * 1024L
    private const val MIN_HDR_NEGATIVE_EV_STEPS = -3 // -1 EV in 1/3 steps
    private const val MIN_FPS_UPPER = 24
    private const val MIN_FPS_LOWER = 15

    fun evaluate(facts: ZslCameraFacts): ZslCapabilityReport {
        if (facts.apiLevel < 26) {
            return ZslCapabilityReport(ZslRoute.NONE, ZslReason.API_TOO_LOW)
        }
        if (facts.isLowRam) {
            return ZslCapabilityReport(ZslRoute.NONE, ZslReason.LOW_RAM)
        }

        val rawCandidate = facts.rawSizes
            .map { (w, h) -> Triple(w, h, w.toLong() * h.toLong() * 2L) }
            .maxByOrNull { it.third }
        val supportsReprocess = ZslImageFormats.PRIVATE in facts.inputFormats &&
            ZslImageFormats.RAW_SENSOR in facts.reprocessOutputFormats

        // RAW output sizes are the actual ImageReader constraint. Trust them instead of
        // the declared capability bit: a wrong/missing bit must not disable a working DNG
        // path, and the arm probe plus forward fallback cover a reader that cannot start.
        // (Do not compare ImageFormat.RAW_SENSOR (32) with capability ids; RAW capability is 3.)
        if (facts.rawSizes.isEmpty()) {
            return if (supportsReprocess) {
                ZslCapabilityReport(
                    route = ZslRoute.REPROCESS_RAW,
                    supportsRawReprocess = true
                )
            } else {
                ZslCapabilityReport(ZslRoute.NONE, ZslReason.NO_RAW)
            }
        }
        val hasRaw = rawCandidate != null && rawCandidate.third <= MAX_FRAME_BYTES
        if (!hasRaw) {
            return ZslCapabilityReport(
                route = ZslRoute.NONE,
                reason = ZslReason.RAW_TOO_LARGE,
                supportsRawReprocess = supportsReprocess
            )
        }

        val (rawW, rawH, frameBytes) = rawCandidate!!
        if (!facts.readSensorSettings) {
            return ZslCapabilityReport(
                ZslRoute.NONE, ZslReason.NO_READ_SENSOR_SETTINGS,
                rawWidth = rawW, rawHeight = rawH, frameBytes = frameBytes
            )
        }
        val ring = ZslRingConfig.forSingle(frameBytes)
        if (ring == null) {
            return ZslCapabilityReport(
                ZslRoute.NONE, ZslReason.RAW_TOO_LARGE,
                rawWidth = rawW, rawHeight = rawH, frameBytes = frameBytes
            )
        }

        val hasNegativeEv = facts.aeCompensationRange?.let { it.first <= MIN_HDR_NEGATIVE_EV_STEPS } == true
        val hdrFpsRange = facts.fpsRanges
            .filter { it.first <= MIN_FPS_LOWER && it.last >= MIN_FPS_UPPER }
            .minWithOrNull(compareBy<IntRange>({ it.last }, { kotlin.math.abs(it.first - MIN_FPS_LOWER) }))
        val hdrPlus = facts.manualPostProcessing && hasNegativeEv && hdrFpsRange != null

        return ZslCapabilityReport(
            route = ZslRoute.RAW_RING,
            rawWidth = rawW,
            rawHeight = rawH,
            frameBytes = frameBytes,
            maxPreRollFrames = ZslRingConfig.maxByBudget(frameBytes).coerceAtMost(8),
            supportsHdrPlusZsl = hdrPlus,
            supportsRawReprocess = supportsReprocess,
            minAeCompensationSteps = facts.aeCompensationRange?.first ?: 0,
            hdrFpsRange = hdrFpsRange,
            timestampRealtime = facts.timestampSource == 1
        )
    }
}
