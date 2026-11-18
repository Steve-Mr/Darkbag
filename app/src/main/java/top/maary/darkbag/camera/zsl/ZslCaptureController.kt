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

    fun start() {
        if (started) return
        started = true
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
        jobs.forEach { it.cancel() }
        jobs.clear()
        cameraHandler?.removeCallbacks(rearmRunnable)
        cameraHandler?.removeCallbacks(armProbeRunnable)
        sessionActive = false
        started = false
        val handler = cameraHandler
        if (handler != null) {
            handler.post {
                disarm()
                restorePoolLimits()
            }
        } else {
            disarm()
            restorePoolLimits()
        }
    }

    fun onSessionConfigured() {
        sessionActive = true
        // A lens/rebind creates a new RAW router; never keep the previous ring attached to it.
        // All profile/ring mutation stays serialized on the camera2 handler.
        cameraHandler?.post {
            probeAttempted = false
            disarm()
            evaluate()
        }
    }

    fun onSessionClosed() {
        sessionActive = false
        cameraHandler?.removeCallbacks(rearmRunnable)
        cameraHandler?.removeCallbacks(armProbeRunnable)
        val handler = cameraHandler
        if (handler != null) handler.post { disarm() } else disarm()
    }

    fun onCaptureStateChanged() {
        scheduleEvaluate()
    }

    /** Called before a normal forward capture so the ring cannot steal that capture's RAW frames. */
    fun onForwardCaptureStarting() {
        if (profile != Profile.OFF) {
            disarm()
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
        if (env.sessionArbiter.camera2Session == null ||
            env.sessionArbiter.rawImageReader?.imageFormat != ImageFormat.RAW_SENSOR
        ) return false
        val ring = ring ?: return false
        if (profile != Profile.SINGLE_RAW) return false
        val t0 = pressTimestampNs(timing)
        val frame = ring.nearest(t0) ?: return false
        val taken = ring.take(listOf(frame))
        if (taken.isEmpty()) return false

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
            StreamingBufferPool.release(frame.buffer)
            HdrPlusRequestManager.onTaskFinished()
            timing.zslRoute = ""
            timing.zslPreRollFrames = 0
            timing.zslFrameOffsetMs = 0L
            return false
        }
        timing.recordShutterReady()
        env.lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Main) {
            ui.resetBurstUi()
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
        if (env.sessionArbiter.camera2Session == null ||
            env.sessionArbiter.rawImageReader?.imageFormat != ImageFormat.RAW_SENSOR
        ) return false
        if (profile != Profile.HDR_PLUS) return false
        val seed = prepareHdrPlusSeed(timing) ?: return false
        timing.captureMode = CaptureTimingMode.HDR_BURST
        timing.zslRoute = ZslRoute.RAW_RING.key
        timing.zslPreRollFrames = seed.preRollCount
        timing.zslPostRollFrames = seed.remainingCount
        // Switch the RAW router to IDLE before the executor starts its own capture listener.
        disarmForShot()
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
        if (!started) {
            timing.zslRoute = ""
            timing.zslPreRollFrames = 0
            timing.zslPostRollFrames = 0
            timing.zslFrameOffsetMs = 0L
        }
        scheduleRearm()
        return started
    }

    fun prepareHdrPlusSeed(timing: StandardTimingTracker): ZslBurstSeed? {
        val ring = ring ?: return null
        val t0 = pressTimestampNs(timing)
        val burst = settings.current().hdrBurstCount.coerceIn(3, 8)
        val selected = ring.stableRun(t0, burst)
        if (selected.size < 3) return null
        val taken = ring.take(selected)
        if (taken.size < 3) {
            ring.releaseFrames(taken)
            return null
        }
        val byTimestamp = taken.associateBy { it.timestamp }
        val ordered = selected.mapNotNull { byTimestamp[it.timestamp] }
        if (ordered.size < 3) {
            ring.releaseFrames(taken)
            return null
        }
        val base = ordered.first()
        val baseResult = base.result
        val iso = base.iso
        val exposure = base.exposureTimeNs
        if (baseResult == null || iso == null || exposure == null || exposure <= 0) {
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

    private fun scheduleEvaluate() {
        cameraHandler?.post { evaluate() }
    }

    private fun evaluate() {
        // Never detach the router while an HDR+ burst is in flight.
        if (env.runtimeState.isBurstActive) return
        if (!sessionActive) {
            disarm()
            return
        }
        val prefs = settings.current()
        val mode = coordinator.effectiveCaptureMode
        if (!prefs.zslEnabled ||
            mode != CaptureMode.NORMAL ||
            env.runtimeState.isManualExposure ||
            env.runtimeState.isFlashEnabled ||
            prefs.force60Fps ||
            prefs.motionPhotoEnabled ||
            env.sessionArbiter.isMultiCameraMode
        ) {
            disarm()
            return
        }
        var thermalStatus = PowerManager.THERMAL_STATUS_NONE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            thermalStatus = env.hostContext.getSystemService(PowerManager::class.java)
                ?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE
            if (thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) {
                disarm()
                return
            }
        }

        val cameraId = env.runtimeState.currentLens?.id ?: "0"
        val report = ZslCapabilityDetector.inspect(env.hostContext, cameraId)
        if (report.route != ZslRoute.RAW_RING) {
            disarm()
            return
        }
        // The evaluator trusts RAW output sizes, but the session may still have created a
        // JPEG fallback reader. Never arm a ring that would store compressed bytes as RAW.
        if (env.sessionArbiter.rawImageReader?.imageFormat != ImageFormat.RAW_SENSOR) {
            disarm()
            return
        }

        val wantHdrPlus = env.isHdrPlusEnabled
        if (wantHdrPlus && !report.supportsHdrPlusZsl) {
            disarm()
            return
        }
        val targetProfile = if (wantHdrPlus) Profile.HDR_PLUS else Profile.SINGLE_RAW
        if (targetProfile == Profile.HDR_PLUS && env.sessionArbiter.lutProcessor == null) {
            disarm()
            return
        }
        val frameBytes = report.frameBytes
        if (frameBytes <= 0L) {
            disarm()
            return
        }
        val desiredConfig = if (targetProfile == Profile.HDR_PLUS) {
            ZslRingConfig.forHdrPlus(prefs.hdrBurstCount, frameBytes)
        } else {
            ZslRingConfig.forSingle(frameBytes)
        } ?: run {
            disarm()
            return
        }
        var limitedConfig = limitByMemory(desiredConfig, targetProfile) ?: run {
            disarm()
            return
        }
        if (thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE) {
            val cap = if (targetProfile == Profile.HDR_PLUS) ZslRingConfig.MIN_HDR_DEPTH else 2
            val depth = minOf(limitedConfig.depth, cap)
            if (depth < (if (targetProfile == Profile.HDR_PLUS) ZslRingConfig.MIN_HDR_DEPTH else 1)) {
                disarm()
                return
            }
            limitedConfig = limitedConfig.copy(depth = depth)
        }

        if (profile == targetProfile && activeConfig == limitedConfig && ring != null) {
            return
        }

        disarm()
        val lens = env.runtimeState.currentLens
        val rotation = try {
            CameraRepository.getCharacteristics(env.camera2Manager, cameraId)
                .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        } catch (_: Exception) {
            0
        }
        val zoom = pipeline.specAssembler.resolveEffectiveZoom(lens, null)
        hdrEvSteps = if (targetProfile == Profile.HDR_PLUS) {
            maxOf(resolveHdrEvSteps(prefs), report.minAeCompensationSteps)
        } else {
            0
        }
        realtimeTimestamp = report.timestampRealtime
        hdrDigitalGain = 2.0.pow((-hdrEvSteps).toDouble() / 3.0).toFloat()
        val newRing = ZslRawRing(limitedConfig, cameraId, zoom, rotation)
        val router = env.sessionArbiter.rawFrameRouter
        if (router == null) {
            disarm()
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
            disarm()
            return
        }
        val spanNs = activeRing.lastFrameTimestampNs - activeRing.firstFrameTimestampNs
        if (spanNs <= 0L) return
        val fps = (count - 1) * 1_000_000_000f / spanNs.toFloat()
        if (fps < MIN_PROBE_FPS) {
            Log.w(TAG, "ZSL arm probe failed: ${"%.1f".format(fps)} fps < $MIN_PROBE_FPS")
            disarm()
        }
    }

    private fun disarm() {
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
    }

    private fun disarmForShot() {
        detachAndClearRing()
        ring = null
        activeConfig = null
        stream.setZslRawTargetEnabled(false)
        stream.setZslPreviewProfile(false)
        env.sessionArbiter.lutProcessor?.setPreviewGain(1.0f)
        profile = Profile.OFF
    }

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

    private fun resolveHdrEvSteps(prefs: DarkbagPreferences): Int = when {
        prefs.hdrUnderexposureMode.startsWith("Off") -> 0
        prefs.hdrUnderexposureMode.startsWith("-2") -> -6
        prefs.hdrUnderexposureMode.startsWith("-1") -> -3
        else -> -3 // Dynamic is fixed for ZSL; clipping feedback is unreliable on an underexposed preview.
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
