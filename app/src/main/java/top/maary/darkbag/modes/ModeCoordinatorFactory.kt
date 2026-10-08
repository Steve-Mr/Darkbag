package top.maary.darkbag.modes

/**
 * 拍摄模式协调器工厂。
 */
object ModeCoordinatorFactory {
    fun create(mode: CaptureMode, host: ModeExecutionContext): CaptureModeCoordinator {
        return when (mode) {
            CaptureMode.NORMAL -> NormalModeCoordinator(host)
            CaptureMode.HALF_FRAME_SBS, CaptureMode.HALF_FRAME_TB -> HalfFrameModeCoordinator(mode, host)
            CaptureMode.MULTI_CAMERA -> MultiCameraModeCoordinator(host)
        }
    }
}
