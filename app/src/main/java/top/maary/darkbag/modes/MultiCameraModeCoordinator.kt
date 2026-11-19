package top.maary.darkbag.modes

import top.maary.darkbag.models.CaptureTimingMode
import top.maary.darkbag.models.StandardTimingTracker

/**
 * 多摄同拍模式协调器。
 * 协调主摄与副摄多路 Camera2 硬件流的同步抓拍与拼贴。
 */
class MultiCameraModeCoordinator(
    private val host: ModeExecutionContext
) : CaptureModeCoordinator {

    override val mode: CaptureMode = CaptureMode.MULTI_CAMERA

    override fun onActivated() {
        android.widget.Toast.makeText(
            host.context,
            top.maary.darkbag.R.string.multi_camera_mode_enabled_toast,
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    override fun onDeactivated() {
        // 多摄模式退出
    }

    override fun onShutterTriggered(timing: StandardTimingTracker) {
        if (!host.canTriggerCapture()) return
        timing.captureMode = CaptureTimingMode.MULTI_CAMERA
        host.showShutterVisuals()
        host.triggerMultiCameraPicture(timing)
    }

    override fun getShutterDotRotation(deviceOrientationDegrees: Int): Float {
        return -deviceOrientationDegrees.toFloat()
    }

    override fun onSwitchCameraClicked(): Boolean {
        host.toggleFrontPipInMultiCameraMode()
        return true
    }

    override fun filterAvailableLenses(
        lenses: List<top.maary.darkbag.utils.LensInfo>,
        repoFacing: Int
    ): List<top.maary.darkbag.utils.LensInfo> {
        val physical = lenses.filter { it.facing == repoFacing && !it.isLogicalAuto && !it.isZoomPreset }
        return if (physical.isNotEmpty()) physical else lenses.filter { it.facing == repoFacing && !it.isZoomPreset }
    }

    override fun onLensSelected(lens: top.maary.darkbag.utils.LensInfo): Boolean {
        host.switchMultiCameraPrimaryLens(lens.multiplier)
        return true
    }

    override fun supportsProLink(): Boolean = true

    override fun bindCameraSession(): Boolean {
        return host.bindMultiCameraSession()
    }
}
