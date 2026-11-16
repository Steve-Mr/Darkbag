package top.maary.darkbag.camera.zsl.probe

/**
 * 探测目标帧率选择（纯函数，设计文档 §3.8.3 / §3.8.4）。
 *
 * 为什么要单独选择：探测的"标称帧间隔"直接决定 RAW_JITTER / RAW_STALL 两档阈值。
 * 若对 15 fps 设备仍按写死的 30 fps（33.3 ms）标称，正常帧也会被判成抖动/停顿；
 * 反之对 30 fps 设备按 15 fps 标称则会把掉帧放过。因此帧率必须由**设备静态能力**决定。
 *
 * 档位（与 [top.maary.darkbag.camera.zsl.RingConfig] 推荐的 24 / 15 档一致）：
 * - `maxRawFrameRate >= 30` → 30（满速双流）
 * - `>= 24` → 24（RECOMMENDED 档）
 * - `>= 15` → 15（SUPPORTED 档）
 * - 其余（含 `null` 未知 / 0 / 负值）→ 15，下限兜底：宁可标称更宽也不误判设备
 */
object ZslProbeTargetFps {
    /** 满速双流档位。 */
    const val FULL_FPS = 30

    /** RECOMMENDED 档位。 */
    const val HIGH_FPS = 24

    /** SUPPORTED 档位，同时是未知能力时的兜底值。 */
    const val LOW_FPS = 15

    fun choose(maxRawFrameRate: Float?): Int = when {
        maxRawFrameRate == null -> LOW_FPS
        maxRawFrameRate >= FULL_FPS -> FULL_FPS
        maxRawFrameRate >= HIGH_FPS -> HIGH_FPS
        maxRawFrameRate >= LOW_FPS -> LOW_FPS
        else -> LOW_FPS
    }
}
