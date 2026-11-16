package top.maary.darkbag.camera.session.zsl

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Handler 派发 + 同步等待（从适配器抽出的可测单元）。
 *
 * 与旧实现语义一致：已在该 Handler 线程上时直接执行（避免自死锁）；投递失败 / 等待超时 /
 * 块内异常 → `false`。新增 [onTimeout]：超时（含等待被中断）时回调，让调用方放弃**迟到**的 block
 * （S1-B2 R4）。
 */
internal object ZslProbeHandlerDispatch {

    fun runBlocking(
        handler: Handler,
        timeoutMs: Long,
        onTimeout: () -> Unit = {},
        block: () -> Boolean
    ): Boolean {
        if (Looper.myLooper() === handler.looper) {
            return try {
                block()
            } catch (t: Throwable) {
                Log.e(TAG, "Probe request block failed on handler thread", t)
                false
            }
        }
        val outcome = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        val posted = handler.post {
            try {
                outcome.set(block())
            } catch (t: Throwable) {
                Log.e(TAG, "Probe request block failed", t)
            } finally {
                latch.countDown()
            }
        }
        if (!posted) return false
        val finished = try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!finished) {
            try {
                onTimeout()
            } catch (t: Throwable) {
                Log.w(TAG, "Probe dispatch onTimeout callback failed", t)
            }
        }
        return finished && outcome.get()
    }

    private const val TAG = "ZslProbeSession"
}
