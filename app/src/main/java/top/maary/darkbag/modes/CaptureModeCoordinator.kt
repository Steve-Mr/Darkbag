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

    /** 用户长按快门动作分发 (返回 true 表示事件已被模式消费) */
    fun onShutterLongPressed(): Boolean = false

    /** 获取图像旋转对应的有效设备旋转角度 (用于修正 EXIF 与排版) */
    fun getEffectiveOrientation(deviceOrientationDegrees: Int): Int = deviceOrientationDegrees

    /** 获取快门指示点目标旋转角度 (驱动 ExpressiveShutterButton 导向真实图像上方) */
    fun getShutterDotRotation(deviceOrientationDegrees: Int): Float = -deviceOrientationDegrees.toFloat()

    /** 用户点击切换前后置摄像头按钮 (多摄模式返回 true 并切换画中画) */
    fun onSwitchCameraClicked(): Boolean = false

    /** 针对当前拍摄模式筛选可用镜头列表 */
    fun filterAvailableLenses(
        lenses: List<top.maary.darkbag.models.Lens>,
        repoFacing: Int
    ): List<top.maary.darkbag.models.Lens> = lenses.filter { it.facing == repoFacing }

    /** 用户点击镜头切换按钮 (多摄模式返回 true 并切换主预览) */
    fun onLensSelected(lens: top.maary.darkbag.models.Lens): Boolean = false

    /** 是否应当隐藏相册缩略图按钮 (半格模式处于 step 1 期间返回 true) */
    fun shouldHideGalleryThumbnail(): Boolean = false

    /** 是否在抓拍完成入队时清空相册缩略图占位 */
    fun shouldClearThumbnailOnCapture(): Boolean = false

    /** 抓拍完成后是否保留进度动画 (半格第 1 帧返回 true) */
    fun keepsProcessingAnimationActive(): Boolean = false

    /** 是否支持专业模式 PRO 同步联动按钮 */
    fun supportsProLink(): Boolean = false

    /** 是否由协调器接管相机底层 Session 配置 (如多摄并发 Session) */
    fun bindCameraSession(): Boolean = false
}
