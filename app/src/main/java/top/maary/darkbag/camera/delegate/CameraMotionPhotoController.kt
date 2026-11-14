package top.maary.darkbag.camera.delegate

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.view.View
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import top.maary.darkbag.R
import top.maary.darkbag.databinding.CameraUiContainerBinding
import top.maary.darkbag.fragments.SettingsFragment
import top.maary.darkbag.modes.CaptureMode
import top.maary.darkbag.motionphoto.MotionPhotoEncoder
import top.maary.darkbag.processor.LutSurfaceProcessor
import top.maary.darkbag.ui.CameraBentoDeckView
import top.maary.darkbag.viewmodel.CameraViewModel
import java.io.File

/** Host contract for the Motion Photo controller. */
interface CameraMotionPhotoHost {
    val hostContext: Context
    val isHostAdded: Boolean
    val lutProcessor: LutSurfaceProcessor?
    val cameraViewModel: CameraViewModel
    val cameraUiContainerBinding: CameraUiContainerBinding?
    /** Mode of the active coordinator, falling back to the persisted preference. */
    val effectiveCaptureMode: CaptureMode
    var pendingMotionPhotoTask: CompletableDeferred<Pair<String?, Long>>?
}

/**
 * Owns the Motion Photo pre-roll encoder lifecycle, its viewfinder encoder surface, the
 * toggle tile, and the still-time snapshot request used by capture coordinators.
 */
class CameraMotionPhotoController(private val host: CameraMotionPhotoHost) {

    private var encoder: MotionPhotoEncoder? = null
    var isEnabled = false
        private set

    private val prefs get() = host.hostContext.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)

    /** Re-reads preferences and starts/stops the encoder + LUT encoder surface accordingly. */
    fun updateEncoder() {
        if (!host.isHostAdded) return
        isEnabled = prefs.getBoolean(SettingsFragment.KEY_MOTION_PHOTO, false)
        host.cameraViewModel.setMotionPhotoEnabled(isEnabled)
        val isAudioEnabled = prefs.getBoolean(SettingsFragment.KEY_MOTION_PHOTO_AUDIO, false) &&
            ContextCompat.checkSelfPermission(host.hostContext, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

        if (isEnabled && host.effectiveCaptureMode.supportsMotionPhoto) {
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
            host.lutProcessor?.setEncoderSurface(encoder?.surface, ENCODER_WIDTH, ENCODER_HEIGHT)
        } else {
            release()
        }
        updateButton()
    }

    /** Detaches the encoder surface from the LUT processor and stops the encoder. */
    fun release() {
        host.lutProcessor?.setEncoderSurface(null, 0, 0)
        encoder?.stop()
        encoder = null
    }

    fun updateButton() {
        val btn = host.cameraUiContainerBinding?.motionPhotoButton ?: return
        val supportsMotion = host.effectiveCaptureMode.supportsMotionPhoto
        val spec = CameraBentoDeckView.resolveVisibilitySpec(prefs, host.cameraViewModel.uiState.value.uiTier)
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
        val btn = host.cameraUiContainerBinding?.motionPhotoButton ?: return
        updateButton()
        btn.setOnClickListener {
            if (!host.effectiveCaptureMode.supportsMotionPhoto) return@setOnClickListener
            isEnabled = !isEnabled
            prefs.edit().putBoolean(SettingsFragment.KEY_MOTION_PHOTO, isEnabled).apply()
            updateEncoder()
        }
    }

    /**
     * Requests a pre/post-roll MP4 around [timestamp]. Returns null (and clears the pending task)
     * when the encoder is not running.
     */
    fun triggerSnapshot(timestamp: Long, orientationDegrees: Int): CompletableDeferred<Pair<String?, Long>>? {
        val active = encoder
        if (active?.isEncoding != true) {
            host.pendingMotionPhotoTask = null
            return null
        }
        val deferred = CompletableDeferred<Pair<String?, Long>>()
        host.pendingMotionPhotoTask = deferred
        active.captureSnapshot(
            captureTimestampNs = System.nanoTime(),
            preDurationMs = 1500L,
            postDurationMs = 1500L,
            outputFile = File(host.hostContext.cacheDir, "motion_${timestamp}.mp4"),
            orientationDegrees = orientationDegrees
        ) { file, stillPtsUs ->
            deferred.complete(Pair(file?.absolutePath, stillPtsUs))
        }
        return deferred
    }

    private companion object {
        const val ENCODER_WIDTH = 1080
        const val ENCODER_HEIGHT = 1440
    }
}
