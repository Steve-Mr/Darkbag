package top.maary.darkbag.modes

import top.maary.darkbag.models.StandardTimingTracker

/**
 * 拍摄模式协调器契约。
 * 将各种拍摄模式（普通单拍/连拍、半格双帧拼接、多摄同拍）的业务流程与相机底层解耦。
 */
interface CaptureModeCoordinator {
    /** 当前负责的模式类型 */
    val mode: CaptureMode

    /** 模式激活时回调 (如绘制辅助参考线、重置状态机) */
    fun onActivated()

    /** 模式休眠/退出时回调 (如清理暂存、恢复默认UI) */
    fun onDeactivated()

    /** 用户点击快门动作分发 */
    fun onShutterTriggered(timing: StandardTimingTracker)

    /** 获取快门指示点目标旋转角度 (驱动 ExpressiveShutterButton 导向真实图像上方) */
    fun getShutterDotRotation(deviceOrientationDegrees: Int): Float = -deviceOrientationDegrees.toFloat()
}
