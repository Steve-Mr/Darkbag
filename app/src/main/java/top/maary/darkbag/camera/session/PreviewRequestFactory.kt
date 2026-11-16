package top.maary.darkbag.camera.session

import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureRequest
import android.view.Surface

/**
 * 单相机预览 repeating 请求的目标计划。
 *
 * [targets] 的顺序即 [CaptureRequest.Builder.addTarget] 的调用顺序，语义固定为
 * preview → analysis（可选）→ extraTargets（可选）。
 */
data class PreviewRequestPlan(val targets: List<Surface>) {
    companion object {
        /**
         * analysis 面附着规则：手动曝光或 HDR+ 开启，且当前镜头为主摄（1x）时附着。
         * 与迁移前各调用点内联的真值判定完全一致。
         */
        fun shouldAttachAnalysis(
            isManualExposure: Boolean,
            isHdrPlusEnabled: Boolean,
            isMainLens: Boolean
        ): Boolean = (isManualExposure || isHdrPlusEnabled) && isMainLens

        /**
         * 解析目标列表：preview → analysis（仅当 [attachAnalysis] 且非空）→ [extraTargets]。
         *
         * null 的 preview/analysis 面直接跳过；[extraTargets] 原样透传（为 S2 的 RAW target 预留）。
         */
        fun resolve(
            previewSurface: Surface?,
            analysisSurface: Surface?,
            attachAnalysis: Boolean,
            extraTargets: List<Surface> = emptyList()
        ): PreviewRequestPlan {
            val targets = ArrayList<Surface>(2 + extraTargets.size)
            previewSurface?.let { targets.add(it) }
            if (attachAnalysis) {
                analysisSurface?.let { targets.add(it) }
            }
            targets.addAll(extraTargets)
            return PreviewRequestPlan(targets)
        }
    }
}

/**
 * 全仓库唯一的单相机预览 repeating 请求构造点。
 *
 * 会话配置、变焦/参数更新、RAW 视频恢复都必须经由本工厂，避免各调用点重复内联目标解析
 * 而静默丢失 analysis（或后续的 RAW）target。
 */
class PreviewRequestFactory {
    /** 按 [PreviewRequestPlan] 顺序添加 target，随后调用一次 [configure]，最后 build。 */
    fun build(
        device: CameraDevice,
        plan: PreviewRequestPlan,
        configure: (CaptureRequest.Builder) -> Unit
    ): CaptureRequest {
        val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        for (target in plan.targets) {
            builder.addTarget(target)
        }
        configure(builder)
        return builder.build()
    }
}
