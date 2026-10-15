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
        // 多摄模式激活
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
}
