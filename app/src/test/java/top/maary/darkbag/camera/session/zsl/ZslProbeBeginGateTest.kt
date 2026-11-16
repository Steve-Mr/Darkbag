package top.maary.darkbag.camera.session.zsl

import android.os.Handler
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 双流启动超时收口（S1-B2 R4）：
 *
 * - [ZslProbeBeginGate] 的放弃标志与补偿语义（纯逻辑）；
 * - [ZslProbeHandlerDispatch] 超时后**迟到**的 handler block 必须被放弃标志拦下，
 *   不再提交 repeating（用"被占满的 camera2Handler"伪造阻塞：主 looper 处于 paused 模式，
 *   block 在超时之后才被 `idle()` 放行）。
 *
 * 这段逻辑是"latch 超时 → 孤儿双流"的唯一防线，因此必须在无相机的 CI 上可钉。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ZslProbeBeginGateTest {

    @Test
    fun testAbandonFlagIsStickyAndBlocksLateBlock() {
        val gate = ZslProbeBeginGate()

        assertFalse(gate.isAbandoned)
        gate.abandon()
        assertTrue(gate.isAbandoned)
        gate.abandon()
        assertTrue(gate.isAbandoned)
    }

    @Test
    fun testCompensateDoesNothingWhenBeginNeverStarted() {
        val gate = ZslProbeBeginGate()
        var restoreCalls = 0

        val compensated = gate.compensate(alreadyBegun = false) { restoreCalls++ }

        assertFalse(compensated)
        assertEquals(0, restoreCalls)
    }

    @Test
    fun testCompensateRestoresOnceWhenBeginAlreadyMutatedRequest() {
        val gate = ZslProbeBeginGate()
        var restoreCalls = 0

        val compensated = gate.compensate(alreadyBegun = true) { restoreCalls++ }

        assertTrue(compensated)
        assertEquals(1, restoreCalls)
    }

    @Test
    fun testCompensateSwallowsRestoreFailure() {
        val gate = ZslProbeBeginGate()

        val compensated = gate.compensate(alreadyBegun = true) {
            throw IllegalStateException("restore exploded during compensation")
        }

        assertFalse("补偿失败只记日志，不能掩盖启动失败", compensated)
    }

    @Test
    fun testSameThreadDispatchExecutesInlineWithoutWaiting() {
        val handler = Handler(Looper.getMainLooper())
        var executed = false

        val result = ZslProbeHandlerDispatch.runBlocking(handler, timeoutMs = 5L) {
            executed = true
            true
        }

        assertTrue(result)
        assertTrue(executed)
    }

    @Test
    fun testTimedOutDispatchAbandonsLateBlockSoItNeverSubmitsRequest() {
        val gate = ZslProbeBeginGate()
        val submitted = AtomicBoolean(false)
        val blockFinished = CountDownLatch(1)
        val handler = Handler(Looper.getMainLooper())
        var dispatched: Boolean? = null

        // 从**非** handler 线程调用：主 looper 处于 paused 模式，block 不会及时执行 → 超时
        val caller = Thread {
            dispatched = ZslProbeHandlerDispatch.runBlocking(
                handler = handler,
                timeoutMs = 50L,
                onTimeout = { gate.abandon() }
            ) {
                if (gate.isAbandoned) {
                    blockFinished.countDown()
                    return@runBlocking false
                }
                submitted.set(true)
                blockFinished.countDown()
                true
            }
        }
        caller.start()
        caller.join(5_000L)

        assertEquals(false, dispatched)
        assertTrue("超时必须置放弃标志", gate.isAbandoned)

        // 放行主 looper：迟到的 block 现在才执行，但必须被放弃标志拦下
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("迟到的 block 必须执行过（否则本测试没测到东西）", blockFinished.await(5, TimeUnit.SECONDS))
        assertFalse("超时后到达的 block 不得再提交 repeating", submitted.get())
    }
}
