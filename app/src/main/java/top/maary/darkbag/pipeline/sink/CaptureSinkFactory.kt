package top.maary.darkbag.pipeline.sink

import top.maary.darkbag.pipeline.model.CaptureTaskSpec
import top.maary.darkbag.utils.HalfFrameSessionStore

/**
 * 拍摄产物交付 Sink 工厂。
 * 依据 Spec 的业务元数据分发合适的产物交付策略。
 */
object CaptureSinkFactory {
    fun createDefaultSink(spec: CaptureTaskSpec): CaptureSink {
        val isHalfFrame = spec.hfMetadata != null && spec.hfMetadata.profile != HalfFrameSessionStore.PROFILE_NORMAL
        return if (isHalfFrame) {
            HalfFrameCacheSink()
        } else {
            DirectMediaStoreSink()
        }
    }
}
