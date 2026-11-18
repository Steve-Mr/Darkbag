package top.maary.darkbag.camera.zsl

import android.app.ActivityManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.os.Build
import android.os.Handler
import android.os.PowerManager
import android.os.SystemClock
import android.util.Range
import android.util.Log
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import top.maary.darkbag.camera.delegate.CameraCoordinatorHost
import top.maary.darkbag.camera.delegate.CameraEnvironmentHost
import top.maary.darkbag.camera.delegate.CameraUiHost
import top.maary.darkbag.camera.dispatch.CameraCapturePipelineDispatcher
import top.maary.darkbag.camera.dispatch.RawImageHolder
import top.maary.darkbag.camera.session.CameraHardwareStreamController
import top.maary.darkbag.models.CaptureTimingMode
import top.maary.darkbag.models.StandardTimingTracker
import top.maary.darkbag.modes.CaptureMode
import top.maary.darkbag.pipeline.sink.CaptureSink
import top.maary.darkbag.processor.HdrPlusRequestManager
import top.maary.darkbag.processor.StreamingBufferPool
import top.maary.darkbag.fragments.HdrPlusBurst
import top.maary.darkbag.repository.DarkbagPreferences
import top.maary.darkbag.repository.SettingsRepository
import top.maary.darkbag.utils.CameraRepository
import top.maary.darkbag.utils.HalfFrameManager
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Owns experimental ZSL arming, frame selection, and handoff.
 *
 * Runs on the camera2 handler. The UI thread only samples the shutter timestamp and asks this
 * controller whether the capture was consumed.
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
        private const val MEMORY_HEADROOM_BYTES = 600L * 1024L * 1024L
        private const val REARM_RETRY_MS = 1500L
        private const val MAX_REARM_RETRIES = 8
        private const val ARM_PROBE_MS = 2000L
        private const val MIN_PROBE_FPS = 15f
    }

    private enum class Profile { OFF, SINGLE_RAW, HDR_PLUS }

    private val settings: SettingsRepository
        get() = SettingsRepository.getInstance(env.hostContext)

    private val cameraHandler: Handler?
        get() = env.sessionArbiter.camera2Handler

    @Volatile private var sessionActive = false
    @Volatile private var started = false
    private var profile = Profile.OFF
    private var ring: ZslRawRing? = null
    private var activeConfig: ZslRingConfig? = null
    private var hdrEvSteps = 0
    private var hdrDigitalGain = 1.0f
    private var realtimeTimestamp = true
    private var rearmRetries = 0
    private val jobs = mutableListOf<Job>()
    private val rearmRunnable = Runnable { attemptRearm() }
    private val armProbeRunnable = Runnable { runArmProbe() }
    private var probeAttempted = false

    private val trimListener: () -> Unit = {
        cameraHandler?.post {
            disarm("memory_trim")
            if (!env.runtimeState.isBurstActive) {
                StreamingBufferPool.clear()
                HdrPlusBurst.clearPool()
            }
        }
    }

    fun start() {
        if (started) return
        started = true
        ZslRuntimeHooks.registerTrimListener(trimListener)
        jobs += env.lifecycleScope.launch {
            env.cameraViewModel.uiState
                .map { it.isHdrPlusEnabled }
                .distinctUntilChanged()
                .collect { scheduleEvaluate() }
        }
        jobs += env.lifecycleScope.launch {
            settings.preferences
                .map {
                    ZslPreferenceSnapshot(
                        enabled = it.zslEnabled,
                        burstCount = it.hdrBurstCount,
                        underexposureMode = it.hdrUnderexposureMode,
                        motionPhoto = it.motionPhotoEnabled,
                        force60Fps = it.force60Fps
                    )
                }
                .distinctUntilChanged()
                .collect { scheduleEvaluate() }
        }
    }

    fun stop() {
        ZslRuntimeHooks.unregisterTrimListener(trimListener)
        jobs.forEach { it.cancel() }
        jobs.clear()
        cameraHandler?.removeCallbacks(rearmRunnable)
        cameraHandler?.removeCallbacks(armProbeRunnable)
        sessionActive = false
        started = false
        val handler = cameraHandler
        if (handler != null) {
            handler.post {
                if (profile != Profile.OFF || ring != null) disarm("stopped")
                restorePoolLimits()
            }
        } else {
            if (profile != Profile.OFF || ring != null) disarm("stopped")
            restorePoolLimits()
        }
    }

    fun onSessionConfigured() {
        sessionActive = true
        // A lens/rebind creates a new RAW router; never keep the previous ring attached to it.
        // All profile/ring mutation stays serialized on the camera2 handler.
        cameraHandler?.post {
            probeAttempted = false
            disarm("reconfigured")
            evaluate()
        }
    }

    fun onSessionClosed() {
        sessionActive = false
        cameraHandler?.removeCallbacks(rearmRunnable)
        cameraHandler?.removeCallbacks(armProbeRunnable)
        val handler = cameraHandler
        if (handler != null) handler.post { disarm("session_closed") } else disarm("session_closed")
    }

    fun onCaptureStateChanged() {
        scheduleEvaluate()
    }

    /** Called before a normal forward capture so the ring cannot steal that capture's RAW frames. */
    fun onForwardCaptureStarting(timing: StandardTimingTracker? = null) {
        val zslWanted = settings.current().zslEnabled
        if (timing != null && zslWanted && timing.zslFallbackReason.isBlank()) {
            timing.zslFallbackReason = "forward"
        }
        if (timing != null && zslWanted) {
            noteCapture("forward: ${timing.zslFallbackReason}")
        }
        if (profile != Profile.OFF) {
            disarm("forward_capture")
            scheduleRearm()
        }
    }

    /**
     * @return true when the single frame was consumed from the ring; false means the caller
     * must run the normal forward single capture.
     */
    fun tryCaptureSingle(
        sink: CaptureSink?,
        isFrame1Trigger: Boolean,
        hfMetadata: HalfFrameManager.Metadata?,
        timing: StandardTimingTracker
    ): Boolean {
        if (!settings.current().zslEnabled) return false
        timing.zslFallbackReason = ""
        if (env.sessionArbiter.camera2Session == null ||
            env.sessionArbiter.rawImageReader?.imageFormat != ImageFormat.RAW_SENSOR
        ) {
            timing.zslFallbackReason = "no_session"
            return false
        }
        val ring = ring ?: run {
            timing.zslFallbackReason = "not_armed"
            return false
        }
        if (!ringMatchesCurrentLens()) {
            timing.zslFallbackReason = "zoom_changed"
            return false
        }
        if (profile != Profile.SINGLE_RAW) {
            timing.zslFallbackReason = "profile"
            return false
        }
        val t0 = pressTimestampNs(timing)
        val frame = ring.nearest(t0) ?: run {
            timing.zslFallbackReason = "no_frame"
            return false
        }
        val taken = ring.take(listOf(frame))
        if (taken.isEmpty()) {
            timing.zslFallbackReason = "frame_race"
            return false
        }

        val lens = env.runtimeState.currentLens
        val holder = RawImageHolder(
            data = frame.buffer,
            width = frame.width,
            height = frame.height,
            timestamp = frame.timestamp,
            rotationDegrees = frame.rotationDegrees,
            combinedOrientation = coordinator.getCombinedOrientation(),
            zoomRatio = frame.zoomRatio,
            physicalId = frame.lensId,
            timing = timing,
            halfFrameMetadata = hfMetadata,
            digitalGain = 1.0f,
            sink = sink,
            capturedLens = lens,
            captureResult = frame.result
        )
        timing.captureMode = CaptureTimingMode.SINGLE_RAW
        timing.zslRoute = ZslRoute.RAW_RING.key
        timing.zslPreRollFrames = 1
        timing.zslFrameOffsetMs = abs(frame.timestamp - t0) / 1_000_000L
        HdrPlusRequestManager.onTaskStarted()
        if (!pipeline.enqueueZslSingle(holder)) {
            timing.zslFallbackReason = "enqueue_failed"
            HdrPlusBurst.releaseBuffer(frame.buffer)
            HdrPlusRequestManager.onTaskFinished()
            timing.zslRoute = ""
            timing.zslPreRollFrames = 0
            timing.zslFrameOffsetMs = 0L
            return false
        }
        timing.recordFrameArrival()
        timing.recordShutterReady()
        env.runtimeState.isBurstActive = false
        env.cameraViewModel.setBurstActive(false)
        noteCapture("single · offset ${timing.zslFrameOffsetMs}ms")
        env.lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Main) {
            ui.resetBurstUi()
            ui.showProcessingAnimation()
        }
        // The ring can refill while the single frame is processed.
        return true
    }

    /**
     * @return true when an HDR+ ZSL burst was started; false means the caller must use the
     * normal forward HDR+ path.
     */
    fun tryCaptureHdrPlus(
        sink: CaptureSink?,
        isFrame1Trigger: Boolean,
        hfMetadata: HalfFrameManager.Metadata?,
        timing: StandardTimingTracker
    ): Boolean {
        if (!settings.current().zslEnabled) return false
        timing.zslFallbackReason = ""
        if (env.sessionArbiter.camera2Session == null ||
            env.sessionArbiter.rawImageReader?.imageFormat != ImageFormat.RAW_SENSOR
        ) {
            timing.zslFallbackReason = "no_session"
            return false
        }
        if (profile != Profile.HDR_PLUS) {
            timing.zslFallbackReason = "profile"
            return false
        }
        if (!ringMatchesCurrentLens()) {
            timing.zslFallbackReason = "zoom_changed"
            return false
        }
        val seed = prepareHdrPlusSeed(timing) ?: return false
        if (settings.current().motionPhotoEnabled && seed.remainingCount > 0) {
            // A partial pre-roll needs a new captureBurst, which changes preview exposure while
            // Motion Photo is still recording. Keep the conservative forward fallback.
            timing.zslFallbackReason = "motion_photo_postroll"
            return false
        }
        timing.captureMode = CaptureTimingMode.HDR_BURST
        timing.zslRoute = ZslRoute.RAW_RING.key
        timing.zslPreRollFrames = seed.preRollCount
        timing.zslPostRollFrames = seed.remainingCount
        // Full pre-roll needs no new camera request: keep RAW target/profile armed so Motion
        // Photo's post-roll sees constant exposure. A partial burst must detach the router.
        val shotDebug = if (seed.remainingCount > 0) disarmForShot() else null
        val started = try {
            pipeline.triggerHdrPlusBurstCamera2(
                sink = sink,
                isFrame1Trigger = isFrame1Trigger,
                hfMetadata = hfMetadata,
                timing = timing,
                seed = seed
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start HDR+ ZSL burst", e)
            false
        }
        if (started) {
            noteCapture("hdr+ · pre ${timing.zslPreRollFrames} · post ${timing.zslPostRollFrames} · offset ${timing.zslFrameOffsetMs}ms")
        } else {
            timing.zslFallbackReason = "burst_start_failed"
            timing.zslRoute = ""
            timing.zslPreRollFrames = 0
            timing.zslPostRollFrames = 0
            timing.zslFrameOffsetMs = 0L
        }
        if (shotDebug != null) {
            publishDebug(
                armed = false,
                reason = if (started) "hdr_zsl_shot" else "burst_start_failed",
                fps = shotDebug.fps,
                profileOverride = shotDebug.profile,
                depthOverride = shotDebug.depth,
                framesOverride = shotDebug.frames
            )
        } else if (started) {
            publishDebug(
                armed = true,
                reason = "",
                fps = ZslDebugRegistry.state.value.fps
            )
        }
        Log.i(TAG, "ZSL HDR+ shot started=$started postRoll=${seed.remainingCount}")
        scheduleRearm()
        return started
    }

    fun prepareHdrPlusSeed(timing: StandardTimingTracker): ZslBurstSeed? {
        val ring = ring ?: run {
            timing.zslFallbackReason = "not_armed"
            return null
        }
        val t0 = pressTimestampNs(timing)
        val burst = settings.current().hdrBurstCount.coerceIn(3, 8)
        val selected = ring.stableRun(t0, burst)
        if (selected.size < 3) {
            timing.zslFallbackReason = "no_stable_run"
            return null
        }
        val taken = ring.take(selected)
        if (taken.size < 3) {
            timing.zslFallbackReason = "frame_race"
            ring.releaseFrames(taken)
            return null
        }
        val byTimestamp = taken.associateBy { it.timestamp }
        val ordered = selected.mapNotNull { byTimestamp[it.timestamp] }
        if (ordered.size < 3) {
            timing.zslFallbackReason = "frame_race"
            ring.releaseFrames(taken)
            return null
        }
        val base = ordered.first()
        val baseResult = base.result
        val iso = base.iso
        val exposure = base.exposureTimeNs
        if (baseResult == null || iso == null || exposure == null || exposure <= 0) {
            timing.zslFallbackReason = "missing_metadata"
            ring.releaseFrames(taken)
            return null
        }
        val isoUpper = env.runtimeState.isoRange?.upper
        val exposureUpper = env.runtimeState.exposureTimeRange?.upper ?: Long.MAX_VALUE
        val exposureCap = minOf(66_666_667L, exposureUpper)
        if (isoUpper != null && iso.toFloat() >= isoUpper * 0.8f && exposure >= exposureCap) {
            timing.zslFallbackReason = "low_light"
            ring.releaseFrames(taken)
            return null
        }
        timing.zslFrameOffsetMs = abs(base.timestamp - t0) / 1_000_000L
        return ZslBurstSeed(
            frames = ordered,
            baseResult = baseResult,
            iso = iso,
            exposureTimeNs = exposure,
            digitalGain = hdrDigitalGain,
            targetFrameCount = burst
        )
    }

    private fun currentZoom(): Float =
        pipeline.specAssembler.resolveEffectiveZoom(env.runtimeState.currentLens, null)

    private fun ringMatchesCurrentLens(): Boolean {
        val ring = ring ?: return false
        val cameraId = env.runtimeState.currentLens?.id ?: "0"
        return ring.matches(
            cameraId,
            env.runtimeState.currentLens?.id,
            env.runtimeState.currentLens?.physicalId,
            currentZoom()
        )
    }

    private fun scheduleEvaluate() {
        cameraHandler?.post { evaluate() }
    }

    private fun evaluate() {
        // Never detach the router while an HDR+ burst is in flight.
        if (env.runtimeState.isBurstActive) return
        if (!sessionActive) {
            disarm("session_closed")
            return
        }
        val prefs = settings.current()
        val mode = coordinator.effectiveCaptureMode
        val inactiveReason = when {
            !prefs.zslEnabled -> "zsl_off"
            mode != CaptureMode.NORMAL -> "mode"
            env.runtimeState.isManualExposure -> "manual_exposure"
            env.runtimeState.isFlashEnabled -> "flash"
            prefs.force60Fps -> "force_60fps"
            env.sessionArbiter.isMultiCameraMode -> "multi_camera"
            else -> null
        }
        if (inactiveReason != null) {
            disarm(inactiveReason)
            return
        }
        var thermalStatus = PowerManager.THERMAL_STATUS_NONE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            thermalStatus = env.hostContext.getSystemService(PowerManager::class.java)
                ?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE
            if (thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) {
                disarm("thermal_severe")
                return
            }
        }

        val cameraId = env.runtimeState.currentLens?.id ?: "0"
        val report = ZslCapabilityDetector.inspect(env.hostContext, cameraId)
        if (report.route != ZslRoute.RAW_RING) {
            disarm(
                if (report.route == ZslRoute.REPROCESS_RAW) "reprocess_not_enabled"
                else report.reason.key
            )
            return
        }
        // The evaluator trusts RAW output sizes, but the session may still have created a
        // JPEG fallback reader. Never arm a ring that would store compressed bytes as RAW.
        if (env.sessionArbiter.rawImageReader?.imageFormat != ImageFormat.RAW_SENSOR) {
            disarm("reader_not_raw")
            return
        }

        val wantHdrPlus = env.isHdrPlusEnabled
        if (wantHdrPlus && !report.supportsHdrPlusZsl) {
            disarm("hdr_unsupported")
            return
        }
        val targetProfile = if (wantHdrPlus) Profile.HDR_PLUS else Profile.SINGLE_RAW
        if (targetProfile == Profile.HDR_PLUS && env.sessionArbiter.lutProcessor == null) {
            disarm("lut_missing")
            return
        }
        val frameBytes = report.frameBytes
        if (frameBytes <= 0L) {
            disarm("no_frame_size")
            return
        }
        val desiredConfig = if (targetProfile == Profile.HDR_PLUS) {
            ZslRingConfig.forHdrPlus(prefs.hdrBurstCount, frameBytes)
        } else {
            ZslRingConfig.forSingle(frameBytes)
        } ?: run {
            disarm("ring_config")
            return
        }
        var limitedConfig = limitByMemory(desiredConfig, targetProfile) ?: run {
            disarm("low_memory")
            return
        }
        if (thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE) {
            val cap = if (targetProfile == Profile.HDR_PLUS) ZslRingConfig.MIN_HDR_DEPTH else 2
            val depth = minOf(limitedConfig.depth, cap)
            if (depth < (if (targetProfile == Profile.HDR_PLUS) ZslRingConfig.MIN_HDR_DEPTH else 1)) {
                disarm("thermal_depth")
                return
            }
            limitedConfig = limitedConfig.copy(depth = depth)
        }

        val lens = env.runtimeState.currentLens
        val zoom = pipeline.specAssembler.resolveEffectiveZoom(lens, null)
        if (profile == targetProfile && activeConfig == limitedConfig &&
            ring?.matches(cameraId, lens?.id, lens?.physicalId, zoom) == true
        ) {
            publishDebug(armed = true, reason = "", report = report)
            return
        }

        disarm("rearming")
        val rotation = try {
            CameraRepository.getCharacteristics(env.camera2Manager, cameraId)
                .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        } catch (_: Exception) {
            0
        }
        val aeStepEv = report.aeCompensationStepEv.takeIf { it > 0f } ?: (1f / 3f)
        hdrEvSteps = if (targetProfile == Profile.HDR_PLUS) {
            val desiredSteps = (resolveHdrTargetEv(prefs) / aeStepEv).roundToInt()
            maxOf(desiredSteps, report.minAeCompensationSteps)
        } else {
            0
        }
        realtimeTimestamp = report.timestampRealtime
        hdrDigitalGain = 2.0.pow(-(hdrEvSteps * aeStepEv).toDouble()).toFloat()
        val newRing = ZslRawRing(limitedConfig, cameraId, lens?.id, lens?.physicalId, zoom, rotation)
        val router = env.sessionArbiter.rawFrameRouter
        if (router == null) {
            disarm("no_router")
            return
        }
        profile = targetProfile
        activeConfig = limitedConfig
        ring = newRing
        StreamingBufferPool.setRetentionLimit(0)
        HdrPlusBurst.setPoolLimit(0)
        val hdrRange = report.hdrFpsRange?.let { Range(it.first, it.last) }
        stream.setZslPreviewProfile(
            enabled = targetProfile == Profile.HDR_PLUS,
            aeCompensationSteps = hdrEvSteps,
            fpsRange = if (targetProfile == Profile.HDR_PLUS) hdrRange else null
        )
        stream.setZslRawTargetEnabled(true)
        env.sessionArbiter.lutProcessor?.setPreviewGain(hdrDigitalGain)
        router.installRing(newRing)
        Log.i(TAG, "ZSL armed: ${targetProfile.name} depth=${limitedConfig.depth} raw=${report.rawWidth}x${report.rawHeight}")
        publishDebug(armed = true, reason = "", report = report)
        if (!probeAttempted) {
            probeAttempted = true
            cameraHandler?.removeCallbacks(armProbeRunnable)
            cameraHandler?.postDelayed(armProbeRunnable, ARM_PROBE_MS)
        }
    }

    /**
     * Minimal arm probe: a ring that receives no usable frames, or fewer than 15 fps of sensor
     * frames, is not allowed to stay armed. Full preview-drop/copy probes remain device work.
     */
    private fun runArmProbe() {
        if (!sessionActive || profile == Profile.OFF) return
        val activeRing = ring ?: return
        val count = activeRing.receivedFrames
        val minFrames = if (profile == Profile.HDR_PLUS) ZslRingConfig.MIN_HDR_DEPTH else 1
        if (count < minFrames) {
            Log.w(TAG, "ZSL arm probe failed: only $count RAW frames in ${ARM_PROBE_MS}ms")
            disarm("probe_no_frames")
            return
        }
        val spanNs = activeRing.lastFrameTimestampNs - activeRing.firstFrameTimestampNs
        if (spanNs <= 0L) return
        val fps = (count - 1) * 1_000_000_000f / spanNs.toFloat()
        if (fps < MIN_PROBE_FPS) {
            Log.w(TAG, "ZSL arm probe failed: ${"%.1f".format(fps)} fps < $MIN_PROBE_FPS")
            disarm("probe_low_fps")
            return
        }
        publishDebug(armed = true, reason = "", fps = fps)
    }

    private fun publishDebug(
        armed: Boolean,
        reason: String,
        report: ZslCapabilityReport? = null,
        fps: Float? = null,
        profileOverride: String? = null,
        depthOverride: Int? = null,
        framesOverride: Int? = null
    ) {
        val prefs = settings.current()
        ZslDebugRegistry.update { current ->
            current.copy(
                enabled = prefs.zslEnabled,
                armed = armed,
                profile = profileOverride ?: profile.name.lowercase(),
                reason = reason,
                route = report?.route?.key ?: current.route,
                cameraId = report?.cameraId ?: current.cameraId,
                rawSize = if (report != null && report.rawWidth > 0) {
                    "${report.rawWidth}x${report.rawHeight}"
                } else {
                    current.rawSize
                },
                depth = depthOverride ?: activeConfig?.depth ?: current.depth,
                frames = framesOverride ?: ring?.size ?: 0,
                fps = fps ?: current.fps
            )
        }
    }

    private fun noteCapture(text: String) {
        ZslDebugRegistry.update { it.copy(lastCapture = text) }
    }

    private fun disarm(reason: String = "unknown") {
        val previous = ZslDebugRegistry.state.value
        val snapshot = previous.afterDisarm(
            reason = reason,
            profile = if (profile != Profile.OFF) profile.name.lowercase() else null,
            depth = if (ring != null || activeConfig != null) activeConfig?.depth else null,
            frames = ring?.size
        )
        val lastProfile = snapshot.profile
        val lastDepth = snapshot.depth
        val lastFrames = snapshot.frames
        val lastFps = previous.fps
        cameraHandler?.removeCallbacks(armProbeRunnable)
        detachAndClearRing()
        ring = null
        activeConfig = null
        if (profile != Profile.OFF) {
            stream.setZslRawTargetEnabled(false)
            stream.setZslPreviewProfile(false)
            env.sessionArbiter.lutProcessor?.setPreviewGain(1.0f)
        }
        profile = Profile.OFF
        hdrEvSteps = 0
        hdrDigitalGain = 1.0f
        restorePoolLimits()
        Log.i(TAG, "ZSL disarm: $reason")
        publishDebug(
            armed = false,
            reason = reason,
            fps = lastFps,
            profileOverride = lastProfile,
            depthOverride = lastDepth,
            framesOverride = lastFrames
        )
    }

    private fun disarmForShot(): ZslShotDebugSnapshot {
        val snapshot = ZslShotDebugSnapshot(
            profile = profile.name.lowercase(),
            depth = activeConfig?.depth ?: 0,
            frames = ring?.size ?: 0,
            fps = ZslDebugRegistry.state.value.fps
        )
        detachAndClearRing()
        ring = null
        activeConfig = null
        stream.setZslRawTargetEnabled(false)
        stream.setZslPreviewProfile(false)
        env.sessionArbiter.lutProcessor?.setPreviewGain(1.0f)
        profile = Profile.OFF
        return snapshot
    }

    private data class ZslShotDebugSnapshot(
        val profile: String,
        val depth: Int,
        val frames: Int,
        val fps: Float?
    )

    private fun detachAndClearRing() {
        val old = env.sessionArbiter.rawFrameRouter?.detachRing()
        old?.clear()
        if (old !== ring) ring?.clear()
    }

    private fun restorePoolLimits() {
        StreamingBufferPool.setRetentionLimit(12)
        HdrPlusBurst.setPoolLimit(3)
    }

    private fun scheduleRearm() {
        cameraHandler?.removeCallbacks(rearmRunnable)
        rearmRetries = 0
        cameraHandler?.postDelayed(rearmRunnable, REARM_RETRY_MS)
    }

    private fun attemptRearm() {
        if (!sessionActive) return
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
        evaluate()
    }

    private fun limitByMemory(config: ZslRingConfig, target: Profile): ZslRingConfig? {
        val memoryInfo = ActivityManager.MemoryInfo().also {
            (env.hostContext.getSystemService(android.content.Context.ACTIVITY_SERVICE) as? ActivityManager)
                ?.getMemoryInfo(it)
        }
        val usable = memoryInfo.availMem - MEMORY_HEADROOM_BYTES
        if (usable < config.frameBytes) return null
        val maxDepth = (usable / config.frameBytes).toInt().coerceAtMost(config.depth)
        val minDepth = if (target == Profile.HDR_PLUS) ZslRingConfig.MIN_HDR_DEPTH else 1
        if (maxDepth < minDepth) return null
        return config.copy(depth = maxDepth)
    }

    private fun resolveHdrTargetEv(prefs: DarkbagPreferences): Float = when {
        prefs.hdrUnderexposureMode.startsWith("Off") -> 0f
        prefs.hdrUnderexposureMode.startsWith("-2") -> -2f
        else -> -1f // -1 EV and Dynamic; clipping feedback is unreliable on an underexposed preview.
    }

    private fun pressTimestampNs(timing: StandardTimingTracker): Long {
        if (!realtimeTimestamp) return SystemClock.uptimeMillis() * 1_000_000L
        return if (timing.shutterPressElapsedNs > 0L) timing.shutterPressElapsedNs
        else SystemClock.uptimeMillis() * 1_000_000L
    }

    private data class ZslPreferenceSnapshot(
        val enabled: Boolean,
        val burstCount: Int,
        val underexposureMode: String,
        val motionPhoto: Boolean,
        val force60Fps: Boolean
    )
}
