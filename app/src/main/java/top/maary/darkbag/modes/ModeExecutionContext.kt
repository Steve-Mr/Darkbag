package top.maary.darkbag.modes

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import kotlinx.coroutines.CompletableDeferred
import top.maary.darkbag.models.StandardTimingTracker
import top.maary.darkbag.pipeline.sink.CaptureSink
import top.maary.darkbag.utils.HalfFrameManager

/**
 * 模式协调器与相机宿主（CameraFragment / CameraEngine）交互的上下文契约。
 * 屏蔽宿主的视图实现细节，仅暴露拍摄触发、状态查询与瞬态动效回调。
 */
interface ModeExecutionContext {
    val context: Context
    val preferences: SharedPreferences
    val isHdrPlusEnabled: Boolean
    val isRawSupported: Boolean
    val deviceOrientationDegrees: Int

    /** 检查当前是否允许触发抓拍 (队列未满、无进行中的连拍) */
    fun canTriggerCapture(): Boolean

    /** 触发快门视觉反馈 (旋转动画、黑屏过度、锁定快门键) */
    fun showShutterVisuals()

    /** 获取当前取景器位图快照 (用于半格转场动效) */
    fun captureViewFinderSnapshot(): Bitmap?

    /** 显示处理中进度条 */
    fun showProcessingAnimation()

    /** 更新半格模式 UI 标识与参考线 */
    fun updateHalfFrameUi(animate: Boolean)

    /** 清空缩略图占位符 (为多帧拼接显示过渡进度) */
    fun clearThumbnailPlaceholder()

    /** 触发动态照片快照录制 */
    fun triggerMotionPhotoSnapshot(timestamp: Long): CompletableDeferred<Pair<String?, Long>>?

    /** 触发 HDR+ 连拍抓拍 */
    fun triggerHdrPlusBurst(
        sink: CaptureSink,
        isFrame1: Boolean,
        hfMetadata: HalfFrameManager.Metadata?,
        timing: StandardTimingTracker
    )

    /** 触发单帧抓拍 */
    fun triggerSinglePicture(
        sink: CaptureSink,
        isFrame1: Boolean,
        hfMetadata: HalfFrameManager.Metadata?,
        timing: StandardTimingTracker
    )

    /** 触发多摄同拍抓拍 */
    fun triggerMultiCameraPicture(timing: StandardTimingTracker)
}
