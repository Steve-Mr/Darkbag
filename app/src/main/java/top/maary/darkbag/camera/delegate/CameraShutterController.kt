package top.maary.darkbag.camera.delegate

import android.Manifest
import android.animation.ValueAnimator
import android.content.pm.PackageManager
import android.view.OrientationEventListener
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.core.content.ContextCompat
import top.maary.darkbag.camera.video.CameraVideoController
import top.maary.darkbag.models.StandardTimingTracker
import top.maary.darkbag.repository.SettingsRepository
import top.maary.darkbag.ui.ExpressiveShutterButton

/**
 * Owns the shutter button: tap dispatch to the active coordinator (no mode branches), long-press
 * video recording, burst/processing visuals, and orientation-driven dot rotation.
 */
class CameraShutterController(
    private val env: CameraEnvironmentHost,
    private val ui: CameraUiHost,
    private val coordinator: CameraCoordinatorHost,
    private val overlayController: CameraOverlayController,
    private val focusMeteringController: CameraFocusMeteringController,
    private val videoController: CameraVideoController
) {
    private val orientationListener by lazy {
        object : OrientationEventListener(env.hostContext) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                val degrees = when (orientation) {
                    in 45 until 135 -> 90
                    in 135 until 225 -> 180
                    in 225 until 315 -> 270
                    else -> 0
                }
                if (degrees != env.runtimeState.deviceOrientationDegrees) {
                    env.runtimeState.deviceOrientationDegrees = degrees
                    val target = getDotTargetRotation()
                    env.cameraViewModel.setShutterDotRotation(target)
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
        val shutter = ui.cameraUiContainerBinding?.cameraCaptureButton ?: return
        shutter.isLongPressHoldEnabled = true
        shutter.onLongPressHoldStarted = {
            if (coordinator.activeCoordinator?.onShutterLongPressed() != true &&
                !env.runtimeState.isBurstActive && coordinator.activeCoordinator?.supportsLongPressVideo() == true
            ) {
                startLongPressRecording()
            }
        }
        shutter.onLongPressHoldReleased = { videoController.handleShutterLongPressReleased() }
        shutter.setOnClickListener {
            val timing = StandardTimingTracker(shutterClick = System.currentTimeMillis())
            coordinator.ensureActiveCoordinator()?.onShutterTriggered(timing)
        }
    }

    private fun startLongPressRecording() {
        val action = SettingsRepository.getInstance(env.hostContext).current().shutterLongPressAction
        val hasAudio = ContextCompat.checkSelfPermission(env.hostContext, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        videoController.handleShutterLongPress(
            action = action,
            isAudioGranted = hasAudio,
            shouldShowRationale = env.shouldShowAudioPermissionRationale(),
            onShowRationale = { onContinueSilent ->
                videoController.showAudioPermissionRationaleDialog(
                    onRequestPermission = { env.requestAudioPermission() },
                    onContinueSilent = onContinueSilent
                )
            },
            onLaunchPermissionRequest = { env.requestAudioPermission() }
        )
    }

    /** Locks the shutter and starts blackout / processing visuals for an accepted capture. */
    fun showShutterVisuals() {
        env.runtimeState.isBurstActive = true
        env.cameraViewModel.setBurstActive(true)
        ui.cameraUiContainerBinding?.cameraCaptureButton?.apply {
            setProgress(0f)
            startRotation()
            isEnabled = false
        }
        overlayController.showShutterBlackout()
        overlayController.showProcessingAnimation()
    }

    /** Restores the shutter after a capture finished and releases the focus/metering lock. */
    fun resetBurstUi() {
        ui.cameraUiContainerBinding?.cameraCaptureButton?.apply {
            setProgress(0f)
            stopRotation()
            isEnabled = true
        }
        env.runtimeState.isBurstActive = false
        focusMeteringController.unlockFocusAndMetering()
    }

    fun updateShutterOrientation() = rotateShutter(getDotTargetRotation())

    fun getDotTargetRotation(): Float =
        coordinator.activeCoordinator?.getShutterDotRotation(env.runtimeState.deviceOrientationDegrees)
            ?: -env.runtimeState.deviceOrientationDegrees.toFloat()

    private fun rotateShutter(targetRotation: Float) {
        val shutter = ui.cameraUiContainerBinding?.cameraCaptureButton as? ExpressiveShutterButton ?: return
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
