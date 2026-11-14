package top.maary.darkbag.camera.delegate

import android.Manifest
import android.animation.ValueAnimator
import android.content.Context
import android.content.pm.PackageManager
import android.view.OrientationEventListener
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.core.content.ContextCompat
import kotlinx.coroutines.sync.Semaphore
import top.maary.darkbag.camera.video.CameraVideoController
import top.maary.darkbag.databinding.CameraUiContainerBinding
import top.maary.darkbag.fragments.SettingsFragment
import top.maary.darkbag.models.StandardTimingTracker
import top.maary.darkbag.modes.CaptureModeCoordinator
import top.maary.darkbag.ui.ExpressiveShutterButton
import top.maary.darkbag.viewmodel.CameraViewModel

/** Host contract for the shutter button controller. */
interface CameraShutterHost {
    val hostContext: Context
    val cameraViewModel: CameraViewModel
    val cameraUiContainerBinding: CameraUiContainerBinding?
    val activeCoordinator: CaptureModeCoordinator?
    val processingSemaphore: Semaphore
    var deviceOrientationDegrees: Int
    var isBurstActive: Boolean

    /** Returns the active coordinator, resolving and activating the persisted mode if none yet. */
    fun ensureActiveCoordinator(): CaptureModeCoordinator?
    fun shouldShowAudioPermissionRationale(): Boolean
    fun requestAudioPermission()
}

/**
 * Owns the shutter button: tap dispatch to the active coordinator (no mode branches), long-press
 * video recording, burst/processing visuals, and orientation-driven dot rotation.
 */
class CameraShutterController(
    private val host: CameraShutterHost,
    private val overlayController: CameraOverlayController,
    private val focusMeteringController: CameraFocusMeteringController,
    private val videoController: CameraVideoController
) {
    private val orientationListener by lazy {
        object : OrientationEventListener(host.hostContext) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                val degrees = when (orientation) {
                    in 45 until 135 -> 90
                    in 135 until 225 -> 180
                    in 225 until 315 -> 270
                    else -> 0
                }
                if (degrees != host.deviceOrientationDegrees) {
                    host.deviceOrientationDegrees = degrees
                    val target = getDotTargetRotation()
                    host.cameraViewModel.setShutterDotRotation(target)
                    rotateShutter(target)
                }
            }
        }
    }

    private var activeDotAnimator: ValueAnimator? = null

    fun enableOrientationTracking() = orientationListener.enable()

    fun disableOrientationTracking() = orientationListener.disable()

    fun onDestroy() {
        disableOrientationTracking()
        activeDotAnimator?.cancel()
        activeDotAnimator = null
    }

    /** Wires tap + long-press handling. */
    fun bindShutter() {
        val shutter = host.cameraUiContainerBinding?.cameraCaptureButton ?: return
        shutter.isLongPressHoldEnabled = true
        shutter.onLongPressHoldStarted = {
            if (host.activeCoordinator?.onShutterLongPressed() != true &&
                !host.isBurstActive && host.activeCoordinator?.supportsLongPressVideo() == true
            ) {
                startLongPressRecording()
            }
        }
        shutter.onLongPressHoldReleased = { videoController.handleShutterLongPressReleased() }
        shutter.setOnClickListener {
            val timing = StandardTimingTracker(shutterClick = System.currentTimeMillis())
            host.ensureActiveCoordinator()?.onShutterTriggered(timing)
        }
    }

    private fun startLongPressRecording() {
        val prefs = host.hostContext.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        val hasAudio = ContextCompat.checkSelfPermission(host.hostContext, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        videoController.handleShutterLongPress(
            action = prefs.getString(SettingsFragment.KEY_SHUTTER_LONG_PRESS_ACTION, SettingsFragment.SHUTTER_LONG_PRESS_MP4),
            isAudioGranted = hasAudio,
            shouldShowRationale = host.shouldShowAudioPermissionRationale(),
            onShowRationale = { onContinueSilent ->
                videoController.showAudioPermissionRationaleDialog(
                    onRequestPermission = { host.requestAudioPermission() },
                    onContinueSilent = onContinueSilent
                )
            },
            onLaunchPermissionRequest = { host.requestAudioPermission() }
        )
    }

    /** Locks the shutter and starts blackout / processing visuals for an accepted capture. */
    fun showShutterVisuals() {
        host.isBurstActive = true
        host.cameraViewModel.setBurstActive(true)
        host.cameraUiContainerBinding?.cameraCaptureButton?.apply {
            setProgress(0f)
            startRotation()
            isEnabled = false
        }
        overlayController.showShutterBlackout()
        overlayController.showProcessingAnimation()
    }

    /** Restores the shutter after a capture finished and releases the focus/metering lock. */
    fun resetBurstUi() {
        host.cameraUiContainerBinding?.cameraCaptureButton?.apply {
            setProgress(0f)
            stopRotation()
            isEnabled = host.processingSemaphore.availablePermits > 0
        }
        host.isBurstActive = false
        focusMeteringController.unlockFocusAndMetering()
    }

    fun updateShutterOrientation() = rotateShutter(getDotTargetRotation())

    fun getDotTargetRotation(): Float =
        host.activeCoordinator?.getShutterDotRotation(host.deviceOrientationDegrees)
            ?: -host.deviceOrientationDegrees.toFloat()

    private fun rotateShutter(targetRotation: Float) {
        val shutter = host.cameraUiContainerBinding?.cameraCaptureButton as? ExpressiveShutterButton ?: return
        val current = shutter.getDotRotation()
        val diff = (targetRotation - current) % 360
        val shortestDiff = when {
            diff > 180 -> diff - 360
            diff < -180 -> diff + 360
            else -> diff
        }
        activeDotAnimator?.cancel()
        activeDotAnimator = ValueAnimator.ofFloat(current, current + shortestDiff).apply {
            duration = ANIMATION_SLOW_MILLIS
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { shutter.setDotRotation(it.animatedValue as Float) }
            start()
        }
    }

    private companion object {
        const val ANIMATION_SLOW_MILLIS = 100L
    }
}
