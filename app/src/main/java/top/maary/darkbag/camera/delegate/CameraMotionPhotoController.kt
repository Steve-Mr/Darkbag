package top.maary.darkbag.camera.delegate

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.media.MediaFormat
import android.os.SystemClock
import android.util.Log
import android.view.View
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import top.maary.darkbag.R
import top.maary.darkbag.motionphoto.MotionPhotoEncoder
import top.maary.darkbag.motionphoto.MotionPhotoStillTimestampSlot
import top.maary.darkbag.motionphoto.MotionPhotoTimebase
import top.maary.darkbag.motionphoto.RawMotionPhotoRelay
import top.maary.darkbag.motionphoto.RawMotionQuality
import top.maary.darkbag.repository.SettingsRepository
import top.maary.darkbag.ui.CameraBentoDeckView
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Owns the Motion Photo pre-roll encoder lifecycle, its viewfinder encoder surface, the
 * toggle tile, and the still-time snapshot request used by capture coordinators.
 */
class CameraMotionPhotoController(
    private val env: CameraEnvironmentHost,
    private val ui: CameraUiHost,
    private val coordinator: CameraCoordinatorHost,
    private val lifecycleExecutor: Executor = defaultLifecycleExecutor(),
    private val encoderFactory: (
        width: Int,
        height: Int,
        frameRate: Int,
        bitRate: Int,
        isAudioEnabled: Boolean
    ) -> MotionPhotoEncoder = { w, h, fps, br, audio ->
        MotionPhotoEncoder(w, h, fps, br, audio)
    },
    private val sizeSupportChecker: (
        mimeType: String,
        width: Int,
        height: Int,
        fps: Int
    ) -> Boolean = { mime, w, h, fps ->
        MotionPhotoEncoder.isCodecSizeSupported(mime, w, h, fps)
    },
    private val isSessionReady: () -> Boolean = { env.sessionArbiter.camera2Session != null }
) {
    @Volatile private var encoder: MotionPhotoEncoder? = null
    @Volatile private var currentEncoderWidth: Int = 0
    @Volatile private var currentEncoderHeight: Int = 0
    @Volatile private var currentRequestedWidth: Int = 0
    @Volatile private var currentRequestedHeight: Int = 0
    private val lifecycleGeneration = AtomicLong(0L)
    @Volatile private var pendingStillTimestampSlot: MotionPhotoStillTimestampSlot? = null
    var isEnabled = false
        private set

    internal val activeEncoderSize: Pair<Int, Int>?
        get() {
            val active = encoder
            return if (active?.isEncoding == true) currentEncoderWidth to currentEncoderHeight else null
        }

    private val settingsRepository by lazy { SettingsRepository.getInstance(env.hostContext) }

    private fun resolveActiveCameraCharacteristics(): CameraCharacteristics? {
        val cameraId = env.sessionArbiter.sessionController.cameraDevice?.id
            ?: env.runtimeState.currentToneMapCameraId
            ?: env.runtimeState.currentLens?.id
            ?: return null
        return try {
            top.maary.darkbag.utils.CameraRepository.getCharacteristics(env.camera2Manager, cameraId)
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveActiveSensorOrientation(): Int =
        resolveActiveCameraCharacteristics()?.get(CameraCharacteristics.SENSOR_ORIENTATION)
            ?: DEFAULT_SENSOR_ORIENTATION

    private val rawMotionRelay by lazy {
        RawMotionPhotoRelay(
            lutSurfaceProcessorProvider = { env.sessionArbiter.lutProcessor },
            characteristicsProvider = { resolveActiveCameraCharacteristics() },
            qualityModeProvider = { settingsRepository.current().expRawMotionPhotoQuality }
        )
    }

    private fun computeBitRate(width: Int, height: Int): Int {
        val ratio = (width.toDouble() * height.toDouble()) /
            (DEFAULT_ENCODER_WIDTH.toDouble() * DEFAULT_ENCODER_HEIGHT.toDouble())
        return (ratio * 10_000_000.0).toInt().coerceIn(6_000_000, 35_000_000)
    }

    /** Re-reads preferences and starts/stops the encoder + LUT encoder surface accordingly. */
    fun updateEncoder() {
        if (!env.isHostAdded) return
        val snapshot = settingsRepository.current()
        isEnabled = snapshot.motionPhotoEnabled
        env.cameraViewModel.setMotionPhotoEnabled(isEnabled)
        updateButton()

        if (!isEnabled || !coordinator.effectiveCaptureMode.supportsMotionPhoto) {
            release()
            return
        }

        // Wait for Camera2 session + rawImageReader + rawFrameRouter before configuring RAW Motion Photo
        if (snapshot.expRawMotionPhoto && !isSessionReady()) {
            rawMotionRelay.stop()
            env.sessionArbiter.lutProcessor?.setRawMotionEnabled(false)
            if (env.runtimeState.isRawSupported) {
                coordinator.setRawMotionPhotoTargetEnabled(true)
            }
            Log.i(TAG, "updateEncoder: deferring RAW motion encoder until Camera2 session & rawImageReader are ready")
            return
        }

        val isAudioEnabled = snapshot.motionPhotoAudioEnabled &&
            ContextCompat.checkSelfPermission(env.hostContext, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

        val rawReader = env.sessionArbiter.rawImageReader
        val isRawMotion = snapshot.expRawMotionPhoto && env.runtimeState.isRawSupported && rawReader != null

        val qualityMode = snapshot.expRawMotionPhotoQuality
        val qualityEnum = RawMotionQuality.fromMode(qualityMode)
        val sensorOrientation = if (isRawMotion) resolveActiveSensorOrientation() else DEFAULT_SENSOR_ORIENTATION
        val candidates = buildCandidateEncoderSizes(
            isRawMotion = isRawMotion,
            rawWidth = rawReader?.width ?: 0,
            rawHeight = rawReader?.height ?: 0,
            qualityMode = qualityMode,
            sensorOrientationDegrees = sensorOrientation
        )
        val supportedCandidates = selectSupportedCandidates(candidates, ENCODER_FPS, sizeSupportChecker)
        val (preferredW, preferredH) = supportedCandidates.first()

        Log.i(
            TAG,
            "updateEncoder: motionPhotoEnabled=$isEnabled, isRawMotion=$isRawMotion, " +
                "rawQualityMode=$qualityMode (${qualityEnum.name}), targetSize=${preferredW}x${preferredH}, " +
                "candidates=$supportedCandidates"
        )

        if (isRawMotion) {
            rawMotionRelay.start()
            env.sessionArbiter.lutProcessor?.setRawMotionEnabled(true)
            coordinator.setRawMotionPhotoTargetEnabled(true)
            env.sessionArbiter.rawFrameRouter?.rawMotionTap = { img, res ->
                rawMotionRelay.onRawFrame(img, res)
            }
        } else {
            stopRawMotion()
        }

        val gen = lifecycleGeneration.incrementAndGet()
        lifecycleExecutor.execute {
            if (gen != lifecycleGeneration.get()) return@execute

            val current = encoder
            if (current != null && current.isEncoding &&
                ((currentEncoderWidth == preferredW && currentEncoderHeight == preferredH) ||
                    (currentRequestedWidth == preferredW && currentRequestedHeight == preferredH))
            ) {
                current.updateAudioEnabled(isAudioEnabled)
                env.sessionArbiter.lutProcessor?.setEncoderSurface(
                    current.surface,
                    currentEncoderWidth,
                    currentEncoderHeight
                )
                return@execute
            }

            if (current != null) {
                env.sessionArbiter.lutProcessor?.setEncoderSurface(null, 0, 0)
                current.stop()
                encoder = null
                currentEncoderWidth = 0
                currentEncoderHeight = 0
                currentRequestedWidth = 0
                currentRequestedHeight = 0
            }

            for ((w, h) in supportedCandidates) {
                if (gen != lifecycleGeneration.get()) return@execute
                val candidate = encoderFactory(
                    w,
                    h,
                    ENCODER_FPS,
                    computeBitRate(w, h),
                    isAudioEnabled
                )
                if (candidate.start()) {
                    if (gen != lifecycleGeneration.get()) {
                        candidate.stop()
                        return@execute
                    }
                    encoder = candidate
                    currentEncoderWidth = w
                    currentEncoderHeight = h
                    currentRequestedWidth = preferredW
                    currentRequestedHeight = preferredH
                    env.sessionArbiter.lutProcessor?.setEncoderSurface(candidate.surface, w, h)
                    return@execute
                }
                Log.w(TAG, "MotionPhotoEncoder failed to start at ${w}x${h}; trying next fallback")
            }

            Log.e(TAG, "All MotionPhotoEncoder candidates failed: $supportedCandidates")
            encoder = null
            currentEncoderWidth = 0
            currentEncoderHeight = 0
            currentRequestedWidth = 0
            currentRequestedHeight = 0
            if (isRawMotion) {
                env.lifecycleScope.launch(Dispatchers.Main.immediate) {
                    if (gen == lifecycleGeneration.get()) {
                        stopRawMotion()
                    }
                }
            }
        }
    }

    /** Detaches the encoder surface from the LUT processor and stops the encoder. */
    fun release() {
        stopRawMotion()
        pendingStillTimestampSlot = null
        lifecycleGeneration.incrementAndGet()
        env.sessionArbiter.lutProcessor?.setEncoderSurface(null, 0, 0)
        lifecycleExecutor.execute {
            encoder?.stop()
            encoder = null
            currentEncoderWidth = 0
            currentEncoderHeight = 0
            currentRequestedWidth = 0
            currentRequestedHeight = 0
        }
    }

    private fun stopRawMotion() {
        rawMotionRelay.stop()
        env.sessionArbiter.rawFrameRouter?.rawMotionTap = null
        env.sessionArbiter.lutProcessor?.setRawMotionEnabled(false)
        coordinator.setRawMotionPhotoTargetEnabled(false)
    }

    fun updateButton() {
        val btn = ui.cameraUiContainerBinding?.motionPhotoButton ?: return
        val supportsMotion = coordinator.effectiveCaptureMode.supportsMotionPhoto
        val spec = CameraBentoDeckView.resolveVisibilitySpec(
            settingsRepository.current(),
            env.cameraViewModel.uiState.value.uiTier
        )
        if (!supportsMotion || !spec.showFilmRow || !spec.showMotionPhoto) {
            btn.visibility = View.GONE
            return
        }
        btn.visibility = View.VISIBLE
        btn.setIconResource(if (isEnabled) R.drawable.ic_motion_photo_on else R.drawable.ic_motion_photo_off)
        CameraBentoDeckView.styleBentoToggleTile(btn, isEnabled, isTertiaryAccent = false)
    }

    /** Wires the toggle tile click to persist the preference and refresh the encoder. */
    fun bindButton() {
        val btn = ui.cameraUiContainerBinding?.motionPhotoButton ?: return
        updateButton()
        btn.setOnClickListener {
            if (!coordinator.effectiveCaptureMode.supportsMotionPhoto) return@setOnClickListener
            isEnabled = !isEnabled
            settingsRepository.setMotionPhotoEnabled(isEnabled)
            updateEncoder()
        }
    }

    /** Called by ZSL after it selects a RAW frame; maps sensor time to encoder monotonic time. */
    fun updatePendingStillTimestamp(sensorTimestampNs: Long, realtime: Boolean) {
        val slot = pendingStillTimestampSlot ?: return
        pendingStillTimestampSlot = null
        val sensorNow = if (realtime) SystemClock.elapsedRealtimeNanos()
        else SystemClock.uptimeMillis() * 1_000_000L
        val mapped = MotionPhotoTimebase.sensorToMonotonic(
            sensorTimestampNs = sensorTimestampNs,
            realtime = realtime,
            sensorNowNs = sensorNow,
            monotonicNowNs = System.nanoTime()
        )
        slot.resolve(mapped)
    }

    /**
     * Requests a pre/post-roll MP4 around [timestamp]. Returns null (and clears the pending task)
     * when the encoder is not running.
     */
    fun triggerSnapshot(timestamp: Long, orientationDegrees: Int): CompletableDeferred<Pair<String?, Long>>? {
        val active = encoder
        if (active?.isEncoding != true) {
            pendingStillTimestampSlot = null
            env.runtimeState.pendingMotionPhotoTask = null
            return null
        }
        val deferred = CompletableDeferred<Pair<String?, Long>>()
        env.runtimeState.pendingMotionPhotoTask = deferred
        val triggerNs = System.nanoTime()
        val shotSlot = MotionPhotoStillTimestampSlot(triggerNs)
        pendingStillTimestampSlot = shotSlot
        active.captureSnapshot(
            captureTimestampNs = triggerNs,
            preDurationMs = 1500L,
            postDurationMs = 1500L,
            outputFile = File(env.hostContext.cacheDir, "motion_${timestamp}.mp4"),
            orientationDegrees = orientationDegrees,
            stillTimestampProvider = { shotSlot.get() }
        ) { file, stillPtsUs ->
            deferred.complete(Pair(file?.absolutePath, stillPtsUs))
        }
        return deferred
    }

    companion object {
        private const val TAG = "CameraMotionPhotoCtrl"
        const val DEFAULT_ENCODER_WIDTH = 1080
        const val DEFAULT_ENCODER_HEIGHT = 1440
        private const val ENCODER_FPS = 30
        private const val DEFAULT_SENSOR_ORIENTATION = 90

        private fun defaultLifecycleExecutor(): Executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "MotionPhotoLifecycle").apply { isDaemon = true }
        }

        /**
         * Builds a descending fallback ladder of output encoder sizes matching the sensor's
         * natural orientation (portrait when sensorOrientation is 90/270, landscape for 0/180).
         */
        internal fun buildCandidateEncoderSizes(
            isRawMotion: Boolean,
            rawWidth: Int,
            rawHeight: Int,
            qualityMode: Int,
            sensorOrientationDegrees: Int = DEFAULT_SENSOR_ORIENTATION
        ): List<Pair<Int, Int>> {
            if (!isRawMotion || rawWidth <= 0 || rawHeight <= 0) {
                return listOf(DEFAULT_ENCODER_WIDTH to DEFAULT_ENCODER_HEIGHT)
            }
            val effectiveMode = RawMotionQuality.resolveEffectiveMode(qualityMode, rawWidth, rawHeight)
            val tiers = when (effectiveMode) {
                RawMotionQuality.NATIVE.mode -> listOf(
                    RawMotionQuality.NATIVE.mode,
                    RawMotionQuality.BINNING_2X2.mode,
                    RawMotionQuality.SUBSAMPLE_4X4.mode
                )
                RawMotionQuality.BINNING_2X2.mode -> listOf(
                    RawMotionQuality.BINNING_2X2.mode,
                    RawMotionQuality.SUBSAMPLE_4X4.mode
                )
                else -> listOf(RawMotionQuality.SUBSAMPLE_4X4.mode)
            }
            val swapAxes = ((sensorOrientationDegrees % 180) + 180) % 180 == 90
            val candidates = tiers.map { tier ->
                val (scaledW, scaledH) = RawMotionQuality.computeScaledDimensions(rawWidth, rawHeight, tier)
                val outW = (if (swapAxes) scaledH else scaledW).coerceAtLeast(2) and 1.inv()
                val outH = (if (swapAxes) scaledW else scaledH).coerceAtLeast(2) and 1.inv()
                outW.coerceAtLeast(2) to outH.coerceAtLeast(2)
            }
            return (candidates + (DEFAULT_ENCODER_WIDTH to DEFAULT_ENCODER_HEIGHT)).distinct()
        }

        /**
         * Filters candidate encoder sizes to those advertised as supported by either HEVC or AVC.
         */
        internal fun selectSupportedCandidates(
            candidates: List<Pair<Int, Int>>,
            frameRate: Int = ENCODER_FPS,
            checker: (mimeType: String, width: Int, height: Int, fps: Int) -> Boolean
        ): List<Pair<Int, Int>> {
            val supported = candidates.filter { (w, h) ->
                checker(MediaFormat.MIMETYPE_VIDEO_HEVC, w, h, frameRate) ||
                    checker(MediaFormat.MIMETYPE_VIDEO_AVC, w, h, frameRate)
            }
            return supported.ifEmpty {
                listOf(DEFAULT_ENCODER_WIDTH to DEFAULT_ENCODER_HEIGHT)
            }
        }
    }
}
