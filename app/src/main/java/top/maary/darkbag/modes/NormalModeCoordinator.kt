package top.maary.darkbag.modes

import top.maary.darkbag.fragments.SettingsFragment
import top.maary.darkbag.models.CaptureTimingMode
import top.maary.darkbag.models.StandardTimingTracker
import top.maary.darkbag.pipeline.sink.DirectMediaStoreSink

/**
 * 普通拍摄模式协调器。
 * 负责普通的单张/HDR+连拍抓拍流程以及动态照片快照触发。
 */
class NormalModeCoordinator(
    private val host: ModeExecutionContext
) : CaptureModeCoordinator {

    override val mode: CaptureMode = CaptureMode.NORMAL

    override fun onActivated() {
        // 普通模式不加载特殊取景器参考线
    }

    override fun onDeactivated() {
        // 无需持久化额外暂存
    }

    override fun onShutterTriggered(timing: StandardTimingTracker) {
        if (!host.canTriggerCapture()) return

        val prefs = host.preferences
        val motionEnabled = prefs.getBoolean(SettingsFragment.KEY_MOTION_PHOTO, false)
        if (motionEnabled) {
            host.triggerMotionPhotoSnapshot(timing.shutterClick)
        }

        val sink = DirectMediaStoreSink()
        host.showShutterVisuals()

        if (host.isHdrPlusEnabled && host.isRawSupported) {
            timing.captureMode = CaptureTimingMode.HDR_BURST
            host.triggerHdrPlusBurst(
                sink = sink,
                isFrame1 = false,
                hfMetadata = null,
                timing = timing
            )
        } else {
            timing.captureMode = CaptureTimingMode.SINGLE_RAW
            host.triggerSinglePicture(
                sink = sink,
                isFrame1 = false,
                hfMetadata = null,
                timing = timing
            )
        }
    }

    override fun getShutterDotRotation(deviceOrientationDegrees: Int): Float {
        return -deviceOrientationDegrees.toFloat()
    }
}
