package top.maary.darkbag.camera.session.zsl

import android.util.Log

/**
 * 双流启动的超时闸门（S1-B2 R4）。
 *
 * [ZslProbeHandlerDispatch.runBlocking] 只能等到"latch 超时"，而 handler block 可能只是**迟到**、稍后仍会执行。
 * 若不拦截，迟到 block 会把 repeating 改成双流请求，而调用方早已按失败收口——留下孤儿双流
 * （RAW target + 锁帧率）直到下一次会话重建。闸门提供两件事：
 * 1. [abandon]：置放弃标志，block 在入口与提交前各检查一次，见到即退出；
 * 2. [compensate]：若 block 已把 `begun` 置 true（请求可能已被改写），由调用方补偿调用
 *    `endDualStream()` 还原；还原抛异常不影响主流程（best-effort）。
 *
 * 纯逻辑、不依赖 Handler，便于在无相机的 CI 上钉住超时收口语义。
 */
internal class ZslProbeBeginGate {

    @Volatile
    var isAbandoned: Boolean = false
        private set

    /** 标记放弃（超时/中断时调用）。 */
    fun abandon() {
        isAbandoned = true
    }

    /**
     * 超时补偿：仅当 block 已经 begin（`begun == true`）时执行 [restore]。
     *
     * @return `true` 表示执行了补偿且 [restore] 未抛异常；`false` 表示无需补偿或补偿失败
     */
    fun compensate(alreadyBegun: Boolean, restore: () -> Unit): Boolean {
        if (!alreadyBegun) return false
        return try {
            restore()
            true
        } catch (t: Throwable) {
            Log.w("ZslProbeSession", "Begin-timeout compensation failed", t)
            false
        }
    }
}
