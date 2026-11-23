package top.maary.darkbag.camera.delegate

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import android.view.View
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import top.maary.darkbag.R
import top.maary.darkbag.motionphoto.MotionPhotoEncoder
import top.maary.darkbag.motionphoto.MotionPhotoStillTimestampSlot
import top.maary.darkbag.motionphoto.MotionPhotoTimebase
import top.maary.darkbag.motionphoto.RawMotionPhotoRelay
import top.maary.darkbag.repository.SettingsRepository
import top.maary.darkbag.ui.CameraBentoDeckView
import java.io.File

/**
 * Owns the Motion Photo pre-roll encoder lifecycle, its viewfinder encoder surface, the
 * toggle tile, and the still-time snapshot request used by capture coordinators.
 */
class CameraMotionPhotoController(
    private val env: CameraEnvironmentHost,
    private val ui: CameraUiHost,
    private val coordinator: CameraCoordinatorHost
) {
    private var encoder: MotionPhotoEncoder? = null
    @Volatile private var pendingStillTimestampSlot: MotionPhotoStillTimestampSlot? = null
    var isEnabled = false
        private set

    private val settingsRepository by lazy { SettingsRepository.getInstance(env.hostContext) }

    private val rawMotionRelay by lazy {
        RawMotionPhotoRelay(
            lutSurfaceProcessorProvider = { env.sessionArbiter.lutProcessor },
            characteristicsProvider = {
                val cameraId = env.sessionArbiter.sessionController.cameraDevice?.id
                    ?: env.runtimeState.currentToneMapCameraId
                    ?: env.runtimeState.currentLens?.id
                if (cameraId != null) {
                    top.maary.darkbag.utils.CameraRepository.getCharacteristics(env.camera2Manager, cameraId)
                } else null
            },
            qualityModeProvider = { settingsRepository.current().expRawMotionPhotoQuality }
        )
    }

    /** Re-reads preferences and starts/stops the encoder + LUT encoder surface accordingly. */
    fun updateEncoder() {
        if (!env.isHostAdded) return
        val snapshot = settingsRepository.current()
        isEnabled = snapshot.motionPhotoEnabled
        env.cameraViewModel.setMotionPhotoEnabled(isEnabled)
        val isAudioEnabled = snapshot.motionPhotoAudioEnabled &&
            ContextCompat.checkSelfPermission(env.hostContext, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

        val isRawMotion = isEnabled && snapshot.expRawMotionPhoto &&
            coordinator.effectiveCaptureMode.supportsMotionPhoto && env.runtimeState.isRawSupported

        val qualityMode = snapshot.expRawMotionPhotoQuality
        val qualityEnum = top.maary.darkbag.motionphoto.RawMotionQuality.fromMode(qualityMode)
        Log.i(TAG, "updateEncoder: motionPhotoEnabled=$isEnabled, isRawMotion=$isRawMotion, rawQualityMode=$qualityMode (${qualityEnum.name})")

        if (isEnabled && coordinator.effectiveCaptureMode.supportsMotionPhoto) {
            val current = encoder
            if (current == null) {
                encoder = MotionPhotoEncoder(
                    width = ENCODER_WIDTH,
                    height = ENCODER_HEIGHT,
                    frameRate = 30,
                    bitRate = 10_000_000,
                    isAudioEnabled = isAudioEnabled
                ).apply { start() }
            } else {
                current.updateAudioEnabled(isAudioEnabled)
            }
            env.sessionArbiter.lutProcessor?.setEncoderSurface(encoder?.surface, ENCODER_WIDTH, ENCODER_HEIGHT)

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
        } else {
            release()
        }
        updateButton()
    }

    /** Detaches the encoder surface from the LUT processor and stops the encoder. */
    fun release() {
        stopRawMotion()
        env.sessionArbiter.lutProcessor?.setEncoderSurface(null, 0, 0)
        encoder?.stop()
        encoder = null
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

    private companion object {
        private const val TAG = "CameraMotionPhotoCtrl"
        const val ENCODER_WIDTH = 1080
        const val ENCODER_HEIGHT = 1440
    }
}
