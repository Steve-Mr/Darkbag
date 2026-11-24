package top.maary.darkbag.camera.zsl

import android.os.Handler
import android.util.Log
import android.util.Range
import top.maary.darkbag.camera.delegate.CameraEnvironmentHost
import top.maary.darkbag.camera.motion.MotionLevel
import top.maary.darkbag.camera.session.CameraHardwareStreamController
import top.maary.darkbag.fragments.HdrPlusBurst
import top.maary.darkbag.models.StandardTimingTracker
import top.maary.darkbag.processor.HdrPlusRequestManager
import top.maary.darkbag.processor.StreamingBufferPool
import top.maary.darkbag.repository.SettingsRepository
import kotlin.math.abs

/**
 * Superintends ring buffer lifecycle, hardware router attachment, arm probes,
 * and rearm scheduling for ZSL.
 */
class ZslRingSupervisor(
    private val env: CameraEnvironmentHost,
    private val stream: CameraHardwareStreamController,
    private val onTriggerRearm: () -> Unit
) {
    companion object {
        private const val TAG = "ZslRingSupervisor"
        private const val REARM_RETRY_MS = 1500L
        private const val MAX_REARM_RETRIES = 8
        private const val ARM_PROBE_MS = 2000L
        private const val MIN_PROBE_FPS = 15f
    }

    private val cameraHandler: Handler? get() = env.sessionArbiter.camera2Handler

    var ring: ZslRawRing? = null; private set
    var activeConfig: ZslRingConfig? = null; private set
    var lazyRingFailed: Boolean = false; private set
    var probePassed: Boolean = false; private set
    var hdrDigitalGain: Float = 1.0f; private set
    var armedProfile: ZslProfile = ZslProfile.OFF; private set
    var activeEvSteps: Int = 0; private set

    private var rearmRetries = 0
    private var probeDroppedBaseline = 0L
    private val rearmRunnable = Runnable { attemptRearm() }
    private val armProbeRunnable = Runnable { runArmProbe() }

    fun arm(
        config: ZslRingConfig,
        profile: ZslProfile,
        cameraId: String,
        lensId: String?,
        physicalId: String?,
        zoom: Float,
        rotation: Int,
        evSteps: Int,
        digitalGain: Float,
        fpsRange: Range<Int>?,
        report: ZslCapabilityReport?
    ) {
        detachAndClearRing()
        val newRing = ZslRawRing(config, cameraId, lensId, physicalId, zoom, rotation)
        ring = newRing; activeConfig = config; armedProfile = profile; activeEvSteps = evSteps; hdrDigitalGain = digitalGain
        val rearm = probePassed
        probeDroppedBaseline = env.sessionArbiter.rawFrameRouter?.droppedImages ?: 0L
        env.sessionArbiter.rawFrameRouter?.installRing(newRing)
        StreamingBufferPool.setRetentionLimit(0)
        HdrPlusBurst.setPoolLimit(0)

        stream.updateZslStreamConfig(
            rawTargetEnabled = true,
            previewProfileEnabled = profile == ZslProfile.HDR_PLUS,
            aeCompensationSteps = evSteps,
            fpsRange = if (profile == ZslProfile.HDR_PLUS) fpsRange else null,
            targetPreviewGain = digitalGain
        )

        cameraHandler?.removeCallbacks(armProbeRunnable)
        if (!rearm) cameraHandler?.postDelayed(armProbeRunnable, ARM_PROBE_MS)
        publishDebug(armed = true, reason = "", activeProfile = profile, report = report)
    }

    fun disarm(reason: String = "unknown", activeProfile: ZslProfile, resetPreviewGain: Boolean = true) {
        val prev = ZslDebugRegistry.state.value
        val snapshot = prev.afterDisarm(
            reason = reason,
            profile = if (activeProfile != ZslProfile.OFF) activeProfile.name.lowercase() else null,
            depth = if (ring != null || activeConfig != null) activeConfig?.depth else null,
            frames = ring?.size
        )
        cameraHandler?.removeCallbacks(armProbeRunnable)
        probePassed = false
        detachAndClearRing()
        ring = null; activeConfig = null; armedProfile = ZslProfile.OFF; activeEvSteps = 0; hdrDigitalGain = 1.0f

        if (activeProfile != ZslProfile.OFF) {
            stream.updateZslStreamConfig(
                rawTargetEnabled = false,
                previewProfileEnabled = false,
                aeCompensationSteps = 0,
                fpsRange = null,
                targetPreviewGain = if (resetPreviewGain) 1.0f else null
            )
        }
        restorePoolLimits()
        Log.i(TAG, "ZSL disarm: $reason")
        publishDebug(false, reason, ZslProfile.OFF, fps = prev.fps, profileOverride = snapshot.profile, depthOverride = snapshot.depth, framesOverride = snapshot.frames)
    }

    fun detachAndClearRing() {
        val old = env.sessionArbiter.rawFrameRouter?.detachRing()
        old?.clear()
        if (old !== ring) ring?.clear()
    }

    fun restorePoolLimits() { StreamingBufferPool.setRetentionLimit(12); HdrPlusBurst.setPoolLimit(3) }

    fun clearBufferPools() {
        if (!env.runtimeState.isBurstActive) { StreamingBufferPool.clear(); HdrPlusBurst.clearPool() }
    }

    fun scheduleRearm() {
        cameraHandler?.removeCallbacks(rearmRunnable)
        rearmRetries = 0
        cameraHandler?.postDelayed(rearmRunnable, REARM_RETRY_MS)
    }

    fun attemptRearm() {
        if (env.sessionArbiter.camera2Session == null) return
        if (HdrPlusRequestManager.pendingTasksCount.value > 0 || env.runtimeState.isBurstActive) {
            if (rearmRetries++ < MAX_REARM_RETRIES) {
                cameraHandler?.postDelayed(rearmRunnable, REARM_RETRY_MS)
            } else {
                Log.w(TAG, "ZSL rearm gave up while processing; restoring buffer pools")
                restorePoolLimits()
            }
            return
        }
        rearmRetries = 0
        onTriggerRearm()
    }

    private fun runArmProbe() {
        if (env.sessionArbiter.camera2Session == null) return
        val activeRing = ring ?: return
        val count = activeRing.receivedFrames
        val dropped = (env.sessionArbiter.rawFrameRouter?.droppedImages ?: 0L) - probeDroppedBaseline
        val lazyMode = activeConfig?.mode == ZslRingMode.LAZY_IMAGE
        val minFrames = if (armedProfile == ZslProfile.HDR_PLUS) ZslRingConfig.MIN_HDR_DEPTH else 1

        if (lazyMode && dropped > 2L) {
            failProbe("lazy_dropped", "Lazy RAW ring dropped $dropped frames; fallback eager", lazyMode = true); return
        }
        if (count < minFrames) {
            failProbe("probe_no_frames", "ZSL arm probe failed: only $count frames in ${ARM_PROBE_MS}ms", lazyMode); return
        }
        val spanNs = activeRing.lastFrameTimestampNs - activeRing.firstFrameTimestampNs
        if (spanNs <= 0L) return
        val fps = (count - 1) * 1_000_000_000f / spanNs.toFloat()
        if (fps < MIN_PROBE_FPS) {
            failProbe("probe_low_fps", "ZSL probe failed: ${"%.1f".format(fps)} fps < $MIN_PROBE_FPS", lazyMode); return
        }
        probePassed = true
        publishDebug(armed = true, reason = "", activeProfile = armedProfile, fps = fps)
    }

    private fun failProbe(reason: String, message: String, lazyMode: Boolean) {
        if (lazyMode) lazyRingFailed = true
        Log.w(TAG, message)
        disarm(reason, armedProfile)
        if (lazyMode) scheduleRearm()
    }

    fun publishDebug(
        armed: Boolean,
        reason: String,
        activeProfile: ZslProfile,
        report: ZslCapabilityReport? = null,
        fps: Float? = null,
        profileOverride: String? = null,
        depthOverride: Int? = null,
        framesOverride: Int? = null
    ) {
        val zslEnabled = SettingsRepository.getInstance(env.hostContext).current().zslEnabled
        ZslDebugRegistry.update { current ->
            current.copy(
                enabled = zslEnabled, armed = armed,
                profile = profileOverride ?: activeProfile.name.lowercase(),
                reason = reason, route = report?.route?.key ?: current.route,
                cameraId = report?.cameraId ?: current.cameraId,
                rawSize = if (report != null && report.rawWidth > 0) "${report.rawWidth}x${report.rawHeight}" else current.rawSize,
                depth = depthOverride ?: activeConfig?.depth ?: current.depth,
                frames = framesOverride ?: ring?.size ?: 0,
                fps = fps ?: current.fps,
                ringMode = activeConfig?.mode?.name?.lowercase() ?: current.ringMode,
                slots = env.sessionArbiter.rawImageReader?.maxImages ?: current.slots,
                droppedImages = env.sessionArbiter.rawFrameRouter?.droppedImages ?: current.droppedImages
            )
        }
    }

    fun noteCapture(text: String) { ZslDebugRegistry.update { it.copy(lastCapture = text) } }

    fun cancelPendingTasks() {
        cameraHandler?.run { removeCallbacks(armProbeRunnable); removeCallbacks(rearmRunnable) }
    }

    fun prepareHdrPlusSeed(
        timing: StandardTimingTracker, adaptive: Boolean, requestedBurst: Int,
        isoUpper: Int?, exposureUpper: Long?, hdrDigitalGain: Float,
        liveIso: Int?, liveExposure: Long?, t0Ns: Long,
        motionLevel: MotionLevel = MotionLevel.STILL
    ): ZslBurstSeed? {
        val activeRing = ring ?: run { timing.zslFallbackReason = "not_armed"; return null }
        val probe = activeRing.latestWithMetadata()
        val pIso = probe?.iso ?: liveIso?.takeIf { it > 0 }
        val pExp = probe?.exposureTimeNs ?: liveExposure?.takeIf { it > 0L }
        val isLowLight = ZslAdaptivePolicy.isLowLight(pIso, pExp, isoUpper, exposureUpper)
        if (isLowLight) { timing.zslFallbackReason = "low_light"; return null }
        if (ZslAdaptivePolicy.shouldFallbackSingleOnMotion(motionLevel, adaptive, isLowLight)) {
            timing.zslFallbackReason = "motion_fast_single"; return null
        }
        val effectiveBurst = ZslAdaptivePolicy.resolveAdaptiveBurstCount(requestedBurst, motionLevel, adaptive, isLowLight)
        val maxRingBurst = minOf(effectiveBurst, activeRing.config.depth).coerceAtLeast(3)
        val minRequiredRun = if (adaptive) ZslAdaptivePolicy.MIN_HDR_FRAMES else ZslAdaptivePolicy.MIN_HYBRID_PRE_ROLL
        val ringSize = activeRing.size
        val selected = activeRing.stableRun(t0Ns, maxRingBurst, minRunCount = minRequiredRun)
        if (selected.size < minRequiredRun) {
            timing.zslFallbackReason = if (!adaptive && ringSize < minRequiredRun) "pre_roll_short" else "no_stable_run"
            return null
        }
        val preBase = selected.first()
        val (preRes, preIso, preExp) = Triple(preBase.result, preBase.iso, preBase.exposureTimeNs)
        if (preRes == null || preIso == null || preExp == null || preExp <= 0L) {
            timing.zslFallbackReason = "missing_metadata"; return null
        }
        if (ZslAdaptivePolicy.isLowLight(preIso, preExp, isoUpper, exposureUpper)) {
            timing.zslFallbackReason = "low_light"; return null
        }
        val taken = activeRing.take(selected)
        val byTs = taken.associateBy { it.timestamp }
        val ordered = selected.mapNotNull { byTs[it.timestamp] }
        if (ordered.size < minRequiredRun) {
            timing.zslFallbackReason = "frame_race"; activeRing.releaseFrames(taken); return null
        }
        val base = ordered.first()
        val (bRes, bIso, bExp) = Triple(base.result, base.iso, base.exposureTimeNs)
        if (bRes == null || bIso == null || bExp == null || bExp <= 0L) {
            timing.zslFallbackReason = "missing_metadata"; activeRing.releaseFrames(taken); return null
        }
        timing.zslFrameOffsetMs = abs(base.timestamp - t0Ns) / 1_000_000L
        val isExtending = !adaptive && ZslAdaptivePolicy.canExtendWindow(ordered.size, effectiveBurst)
        val targetCount = if (isExtending) effectiveBurst else (if (adaptive) ordered.size else effectiveBurst)
        return ZslBurstSeed(ordered, bRes, bIso, bExp, hdrDigitalGain, targetCount)
    }
}
