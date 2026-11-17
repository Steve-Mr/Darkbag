package top.maary.darkbag.camera.session.zsl

import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import top.maary.darkbag.camera.zsl.probe.ZslMetadataBinding
import top.maary.darkbag.camera.zsl.probe.ZslProbeFrameObserver

/**
 * W0 基线预览计数（探测测量缺陷修复）。
 *
 * W0 阶段 repeating 仍由生产回调驱动，结果只进 `captureResultFlow`、不经探测适配器，
 * 因此基线必须订阅该 flow；否则 `previewFpsBaseline` 恒为 0，`PREVIEW_DEGRADED` 永假。
 *
 * replayCache 中的历史帧用"订阅时刻的最后一个非空时间戳"作截断点排除，只计 live 帧；
 * 进入 W1（`isDualStreamActive() == true`）后停用，避免与适配器回调重复计数。
 * 协程 scope 由调用方（适配器）持有，[stop] 只取消订阅 Job；适配器在 restoreOnce 中一定会
 * 调 `setFrameObserver(null)`，因此不会留下常驻协程。
 *
 * W0→W1 工厂重建窗口内可能多计/少计 1–3 帧（方向保守，不会造成同帧双计），已在设计文档
 * §3.8.3 记录。
 */
internal class ZslProbePreviewTap(
    private val flow: MutableSharedFlow<TotalCaptureResult>,
    private val scope: CoroutineScope,
    private val isDualStreamActive: () -> Boolean
) {
    private var job: Job? = null

    fun start(observer: ZslProbeFrameObserver) {
        stop()
        val cutNs = flow.replayCache.mapNotNull { it.get(CaptureResult.SENSOR_TIMESTAMP) }.lastOrNull()
            ?: Long.MIN_VALUE
        job = scope.launch {
            flow.collect { result ->
                val timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return@collect
                if (shouldCountBaselinePreview(timestamp, cutNs, isDualStreamActive())) {
                    observer.onPreviewResult(timestamp)
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}

/** 基线计数判定：排除 replay 历史帧，并在双流期间让位给适配器回调（避免双计）。 */
internal fun shouldCountBaselinePreview(timestampNs: Long, cutNs: Long, dualStreamActive: Boolean): Boolean =
    !dualStreamActive && timestampNs > cutNs

/** W2 settle 之后统一回查 RAW 帧 ↔ `CaptureResult` 绑定（精确 + 既有 5 ms 容差）。 */
internal object ZslProbeMetadataBinder {
    fun bind(timestampsNs: List<Long>, isBound: (Long) -> Boolean): ZslMetadataBinding {
        if (timestampsNs.isEmpty()) return ZslMetadataBinding(0, 0)
        var bound = 0
        for (timestamp in timestampsNs) {
            if (isBound(timestamp)) bound++
        }
        return ZslMetadataBinding(bound, timestampsNs.size)
    }
}
