package top.maary.darkbag.camera.zsl

import android.app.ActivityManager
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.os.Build
import android.os.Handler
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.util.Range
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import top.maary.darkbag.camera.delegate.CameraCoordinatorHost
import top.maary.darkbag.camera.delegate.CameraEnvironmentHost
import top.maary.darkbag.camera.delegate.CameraUiHost
import top.maary.darkbag.camera.dispatch.CameraCapturePipelineDispatcher
import top.maary.darkbag.camera.session.CameraHardwareStreamController
import top.maary.darkbag.models.CaptureTimingMode
import top.maary.darkbag.models.StandardTimingTracker
import top.maary.darkbag.modes.CaptureMode
import top.maary.darkbag.pipeline.sink.CaptureSink
import top.maary.darkbag.repository.SettingsRepository
import top.maary.darkbag.utils.CameraRepository
import top.maary.darkbag.utils.HalfFrameManager
import kotlin.math.abs

/**
 * Lean facade owning experimental ZSL capture dispatch, frame selection, and hardware handoff.
 * Delegates lifecycle, probes, and ring attachment to [ZslRingSupervisor], and admission/EV rules
 * to [ZslArmingPolicy].
 */
class ZslCaptureController(
    private val env: CameraEnvironmentHost,
    private val ui: CameraUiHost,
    private val coordinator: CameraCoordinatorHost,
    private val pipeline: CameraCapturePipelineDispatcher,
    private val stream: CameraHardwareStreamController
) {
    companion object {
        private const val TAG = "ZslCaptureController"
    }

    private val settings: SettingsRepository get() = SettingsRepository.getInstance(env.hostContext)
    private val cameraHandler: Handler? get() = env.sessionArbiter.camera2Handler

    @Volatile private var sessionActive = false
    @Volatile private var started = false
    private var profile = ZslProfile.OFF
    private var realtimeTimestamp = true
    private val jobs = mutableListOf<Job>()

    private val supervisor = ZslRingSupervisor(env, stream) { evaluate() }

    val ring: ZslRawRing? get() = supervisor.ring

    private val trimListener: () -> Unit = {
        cameraHandler?.post { supervisor.disarm("memory_trim", profile); profile = ZslProfile.OFF; supervisor.clearBufferPools() }
    }

    fun start() {
        if (started) return
        started = true
        ZslRuntimeHooks.registerTrimListener(trimListener)
        jobs += env.lifecycleScope.launch {
            settings.preferences.map { PrefSnapshot(it.zslEnabled, it.zslLazyRing, it.hdrBurstCount, it.hdrUnderexposureMode, it.force60Fps) }
                .distinctUntilChanged().collect { scheduleEvaluate() }
        }
        jobs += env.lifecycleScope.launch {
            env.cameraViewModel.uiState.map { it.activeLensId to it.isHdrPlusEnabled }
                .distinctUntilChanged().collect { scheduleEvaluate() }
        }
    }

    fun stop() {
        if (!started) return
        started = false
        ZslRuntimeHooks.unregisterTrimListener(trimListener)
        jobs.forEach { it.cancel() }; jobs.clear()
        sessionActive = false
        disarmOnCameraThread("stop")
    }

    fun onSessionConfigured() {
        sessionActive = true
        cameraHandler?.post {
            supervisor.disarm("reconfigured", profile)
            profile = ZslProfile.OFF
            evaluate()
        } ?: scheduleEvaluate()
    }

    fun onSessionClosed() {
        sessionActive = false
        disarmOnCameraThread("session_closed")
    }

    private fun disarmOnCameraThread(reason: String) {
        val action = { supervisor.cancelPendingTasks(); supervisor.disarm(reason, profile); profile = ZslProfile.OFF }
        cameraHandler?.post(action) ?: action()
    }

    fun onLensOrZoomChanged() { scheduleEvaluate() }
    fun onCaptureStateChanged() { scheduleEvaluate() }

    fun onForwardCaptureStarting(timing: StandardTimingTracker? = null) {
        if (timing != null && settings.current().zslEnabled) {
            if (timing.zslFallbackReason.isBlank()) timing.zslFallbackReason = "forward"
            supervisor.noteCapture("forward: ${timing.zslFallbackReason}")
        }
        if (profile != ZslProfile.OFF) {
            supervisor.disarm("forward_capture", profile); profile = ZslProfile.OFF; supervisor.scheduleRearm()
        }
    }

    private fun validateCapture(expected: ZslProfile, timing: StandardTimingTracker): String? {
        timing.zslFallbackReason = ""
        if (!settings.current().zslEnabled) return "zsl_disabled"
        if (env.sessionArbiter.camera2Session == null || env.sessionArbiter.rawImageReader?.imageFormat != ImageFormat.RAW_SENSOR) return "no_session"
        if (profile != expected) return "profile"
        if (supervisor.ring == null) return "not_armed"
        if (!ringMatchesCurrentLens()) return "zoom_changed"
        return null
    }

    fun tryCaptureSingle(
        sink: CaptureSink?, isFrame1Trigger: Boolean, hfMetadata: HalfFrameManager.Metadata?, timing: StandardTimingTracker
    ): ZslDispatchOutcome {
        val err = validateCapture(ZslProfile.SINGLE_RAW, timing)
        if (err != null) { timing.zslFallbackReason = err; return ZslDispatchOutcome.Fallback(err) }
        val t0 = pressTimestampNs(timing)
        val candidate = supervisor.ring?.nearest(t0) ?: run { timing.zslFallbackReason = "no_frame"; return ZslDispatchOutcome.Fallback("no_frame") }
        val taken = supervisor.ring?.take(listOf(candidate)).orEmpty()
        if (taken.isEmpty()) { timing.zslFallbackReason = "frame_race"; return ZslDispatchOutcome.Fallback("frame_race") }
        return dispatchSingle(taken.firstOwnedFrame() ?: taken.first(), sink, isFrame1Trigger, hfMetadata, timing, 1.0f, "single", t0)
    }

    fun tryCaptureHdrPlus(
        sink: CaptureSink?, isFrame1Trigger: Boolean, hfMetadata: HalfFrameManager.Metadata?, timing: StandardTimingTracker
    ): ZslDispatchOutcome {
        val err = validateCapture(ZslProfile.HDR_PLUS, timing)
        if (err != null) { timing.zslFallbackReason = err; return ZslDispatchOutcome.Fallback(err) }

        val seed = prepareHdrPlusSeed(timing)
        if (seed == null) {
            if (ZslAdaptivePolicy.shouldUseInstantSingle(settings.current().zslAdaptive, timing.zslFallbackReason)) {
                val instant = captureInstantSingle(sink, isFrame1Trigger, hfMetadata, timing, timing.zslFallbackReason)
                if (instant is ZslDispatchOutcome.Consumed) return instant
            }
            return ZslDispatchOutcome.Fallback(timing.zslFallbackReason.ifBlank { "no_seed" })
        }
        timing.captureMode = CaptureTimingMode.HDR_BURST; timing.zslRoute = ZslRoute.RAW_RING.key
        timing.zslPreRollFrames = seed.preRollCount; timing.zslPostRollFrames = seed.remainingCount

        val isHybrid = seed.remainingCount > 0
        if (isHybrid) { supervisor.disarm("hybrid_burst", profile, resetPreviewGain = false); profile = ZslProfile.OFF }

        val started = try {
            pipeline.triggerHdrPlusBurstCamera2(sink, isFrame1Trigger, hfMetadata, timing, seed)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start HDR+ ZSL burst", e); false
        }
        if (started) {
            supervisor.noteCapture("hdr+ · pre ${timing.zslPreRollFrames} · post ${timing.zslPostRollFrames} · offset ${timing.zslFrameOffsetMs}ms")
            env.lifecycleScope.launch(Dispatchers.Main) { ui.showZslCaptureFeedback() }
            if (!isHybrid) supervisor.publishDebug(armed = true, reason = "", activeProfile = profile)
        } else {
            if (isHybrid) env.sessionArbiter.lutProcessor?.setPreviewGain(1.0f)
            timing.zslFallbackReason = "burst_start_failed"; timing.zslRoute = ""
            timing.zslPreRollFrames = 0; timing.zslPostRollFrames = 0
        }
        if (!started || isHybrid || profile == ZslProfile.OFF) supervisor.scheduleRearm()

        return if (started) ZslDispatchOutcome.Consumed(seed.frames.firstOrNull()?.timestamp ?: 0L, realtimeTimestamp, seed.preRollCount, seed.remainingCount)
        else ZslDispatchOutcome.Fallback(timing.zslFallbackReason.ifBlank { "burst_failed" })
    }

    private fun captureInstantSingle(
        sink: CaptureSink?, isFrame1Trigger: Boolean, hfMetadata: HalfFrameManager.Metadata?, timing: StandardTimingTracker, reason: String
    ): ZslDispatchOutcome {
        val activeRing = supervisor.ring ?: return ZslDispatchOutcome.Fallback("not_armed")
        val frame = activeRing.latestValid() ?: return ZslDispatchOutcome.Fallback("no_valid_frame")
        if (ZslAdaptivePolicy.isLowLight(frame.iso, frame.exposureTimeNs, env.runtimeState.isoRange?.upper, env.runtimeState.exposureTimeRange?.upper)) {
            timing.zslFallbackReason = "low_light"; return ZslDispatchOutcome.Fallback("low_light")
        }
        val t0 = pressTimestampNs(timing)
        if (abs(frame.timestamp - t0) > activeRing.config.maxAgeNs) {
            timing.zslFallbackReason = "instant_too_old"; return ZslDispatchOutcome.Fallback("instant_too_old")
        }
        val taken = activeRing.take(listOf(frame))
        if (taken.isEmpty()) return ZslDispatchOutcome.Fallback("frame_race")
        timing.zslFallbackReason = reason
        return dispatchSingle(taken.firstOwnedFrame() ?: taken.first(), sink, isFrame1Trigger, hfMetadata, timing, supervisor.hdrDigitalGain, reason, t0)
    }

    private fun dispatchSingle(
        frame: ZslFrame, sink: CaptureSink?, isFrame1: Boolean,
        hfMetadata: HalfFrameManager.Metadata?, timing: StandardTimingTracker,
        gain: Float, tag: String, t0Ns: Long
    ): ZslDispatchOutcome {
        if (!pipeline.dispatchZslSingleFrame(frame, sink, isFrame1, hfMetadata, timing, gain)) return ZslDispatchOutcome.Fallback("dispatch_failed")
        timing.captureMode = CaptureTimingMode.SINGLE_RAW; timing.zslRoute = ZslRoute.RAW_RING.key
        timing.zslPreRollFrames = 1; timing.zslPostRollFrames = 0
        timing.zslFrameOffsetMs = abs(frame.timestamp - t0Ns) / 1_000_000L
        supervisor.noteCapture("$tag · pre 1 · offset ${timing.zslFrameOffsetMs}ms")
        return ZslDispatchOutcome.Consumed(frame.timestamp, realtimeTimestamp, 1, 0)
    }

    fun prepareHdrPlusSeed(timing: StandardTimingTracker): ZslBurstSeed? = supervisor.prepareHdrPlusSeed(
        timing = timing, adaptive = settings.current().zslAdaptive, requestedBurst = settings.current().hdrBurstCount,
        isoUpper = env.runtimeState.isoRange?.upper, exposureUpper = env.runtimeState.exposureTimeRange?.upper,
        hdrDigitalGain = supervisor.hdrDigitalGain, liveIso = env.runtimeState.liveIso, liveExposure = env.runtimeState.liveExposureTime,
        t0Ns = pressTimestampNs(timing), motionLevel = env.sessionArbiter.sceneMotionMonitor.currentLevel
    )

    private fun evaluate() {
        if (env.runtimeState.isBurstActive || !sessionActive) return
        val prefs = settings.current()
        val lens = env.runtimeState.currentLens
        val cameraId = lens?.id ?: "0"
        val report = ZslCapabilityDetector.inspect(env.hostContext, cameraId)
        val facts = ZslArmingPolicy.ContextFacts(
            zslEnabled = prefs.zslEnabled, sessionActive = sessionActive,
            isSupportedCaptureMode = coordinator.effectiveCaptureMode == CaptureMode.NORMAL || coordinator.effectiveCaptureMode.isHalfFrame,
            force60Fps = prefs.force60Fps, isMultiCamera = env.sessionArbiter.isMultiCameraMode,
            isManualExposure = env.runtimeState.isManualExposure, isFlashActive = env.runtimeState.isFlashEnabled,
            isPowerSaveOrThermalSevere = isThermalSevere(), isHdrPlusEnabled = env.isHdrPlusEnabled, capabilityReport = report
        )
        val mem = ActivityManager.MemoryInfo().also { (env.hostContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.getMemoryInfo(it) }
        val plan = ZslArmingPolicy.planArming(
            facts, env.sessionArbiter.rawImageReader?.imageFormat == ImageFormat.RAW_SENSOR,
            env.sessionArbiter.lutProcessor != null, prefs.zslLazyRing,
            supervisor.lazyRingFailed, prefs.hdrBurstCount, prefs.hdrUnderexposureMode, mem.availMem
        )
        if (plan.profile == ZslProfile.OFF || plan.ringConfig == null) {
            supervisor.disarm(plan.disarmReason, profile); profile = ZslProfile.OFF; return
        }
        val zoom = pipeline.specAssembler.resolveEffectiveZoom(lens, null)
        if (profile == plan.profile && supervisor.activeConfig == plan.ringConfig && supervisor.activeEvSteps == (plan.evConfig?.steps ?: 0) && supervisor.hdrDigitalGain == (plan.evConfig?.digitalGain ?: 1.0f) && supervisor.ring?.matches(cameraId, lens?.id, lens?.physicalId, zoom) == true) {
            supervisor.publishDebug(armed = true, reason = "", activeProfile = profile, report = plan.report); return
        }
        if (env.sessionArbiter.rawFrameRouter == null) { supervisor.disarm("no_router", profile); profile = ZslProfile.OFF; return }
        val rotation = try { CameraRepository.getCharacteristics(env.camera2Manager, cameraId).get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0 } catch (_: Exception) { 0 }
        profile = plan.profile
        realtimeTimestamp = plan.report?.timestampRealtime == true
        supervisor.arm(
            plan.ringConfig, plan.profile, cameraId, lens?.id, lens?.physicalId,
            zoom, rotation, plan.evConfig?.steps ?: 0, plan.evConfig?.digitalGain ?: 1.0f,
            plan.report?.hdrFpsRange?.let { Range(it.first, it.last) }, plan.report
        )
    }

    private fun isThermalSevere(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
        (env.hostContext.getSystemService(PowerManager::class.java)?.currentThermalStatus ?: 0) >= PowerManager.THERMAL_STATUS_SEVERE

    private fun ringMatchesCurrentLens(): Boolean = env.runtimeState.currentLens.let { l ->
        supervisor.ring?.matches(l?.id ?: "0", l?.id, l?.physicalId, pipeline.specAssembler.resolveEffectiveZoom(l, null)) == true
    }

    private fun scheduleEvaluate() { cameraHandler?.post { evaluate() } }

    private fun pressTimestampNs(timing: StandardTimingTracker): Long =
        if (!realtimeTimestamp) SystemClock.uptimeMillis() * 1_000_000L
        else if (timing.shutterPressElapsedNs > 0L) timing.shutterPressElapsedNs
        else SystemClock.elapsedRealtimeNanos()

    private data class PrefSnapshot(val zsl: Boolean, val lazy: Boolean, val burst: Int, val ev: String, val f60: Boolean)
}
