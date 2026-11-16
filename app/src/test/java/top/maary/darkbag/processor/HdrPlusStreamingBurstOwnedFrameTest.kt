package top.maary.darkbag.processor

import kotlinx.coroutines.launch
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 零拷贝帧移交（S0-6）：[HdrPlusStreamingBurst.addOwnedFrame] 的准入/满员/关闭语义。
 *
 * native 库在 host 单测中不可用，worker 内的 native 调用失败会被内部 catch 吞掉并走
 * onBurstFailed（此处传空 lambda），因此测试只断言入队返回值与不抛异常。
 */
class HdrPlusStreamingBurstOwnedFrameTest {

    /** 堆上 ByteBuffer：addOwnedFrame 不做池分配/拷贝，堆缓冲足以驱动准入逻辑。 */
    private fun heapFrame(size: Int = 16): ByteBuffer = ByteBuffer.allocate(size)

    @Test
    fun testAddOwnedFrame_admitsFrameCountThenRejectsExcess() {
        // 占住唯一的累加工作线程，避免 worker 在准入断言期间消费首帧并 abort（native 不可用）。
        val gate = CountDownLatch(1)
        val workerOccupied = CountDownLatch(1)
        val blockerDone = CountDownLatch(1)
        HdrPlusAccumulationDispatcher.scope.launch {
            workerOccupied.countDown()
            gate.await(10, TimeUnit.SECONDS)
            blockerDone.countDown()
        }
        assertTrue(workerOccupied.await(10, TimeUnit.SECONDS))

        val settled = CountDownLatch(1)
        val burst = HdrPlusStreamingBurst(
            sessionHandle = 0L,
            frameCount = 2,
            onBurstComplete = { settled.countDown() },
            onBurstFailed = { settled.countDown() }
        )
        try {
            assertTrue(burst.addOwnedFrame(heapFrame(), 4, 4, 1L, 1_000L, 0, "0"))
            assertTrue(burst.addOwnedFrame(heapFrame(), 4, 4, 2L, 1_000L, 0, "0"))
            // 满员后准入关闭：第 3 帧必须被拒绝
            assertFalse(burst.addOwnedFrame(heapFrame(), 4, 4, 3L, 1_000L, 0, "0"))
        } finally {
            gate.countDown()
            assertTrue(blockerDone.await(10, TimeUnit.SECONDS))
        }
        // worker 随后在 native 不可用时走 onBurstFailed，不得让测试崩溃
        assertTrue(settled.await(10, TimeUnit.SECONDS))
    }

    @Test
    fun testAddOwnedFrame_rejectedAfterFlush() {
        val burst = HdrPlusStreamingBurst(
            sessionHandle = 0L,
            frameCount = 2,
            onBurstComplete = {},
            onBurstFailed = {}
        )
        burst.flush()

        assertFalse(burst.addOwnedFrame(heapFrame(), 4, 4, 1L, 1_000L, 0, "0"))
    }

    @Test
    fun testFlushAndAbortAreIdempotentAndCloseAdmission() {
        val burst = HdrPlusStreamingBurst(
            sessionHandle = 0L,
            frameCount = 2,
            onBurstComplete = {},
            onBurstFailed = {}
        )
        val buffer = heapFrame(32)
        // 首帧结果不参与断言：worker 可能已在并发路径上失败
        burst.addOwnedFrame(buffer, 4, 4, 1L, 1_000L, 0, "0")

        burst.flush()
        burst.flush()
        burst.abort()
        burst.abort()

        // abort 之后准入必须永久关闭
        assertFalse(burst.addOwnedFrame(buffer, 4, 4, 2L, 1_000L, 0, "0"))
    }
}
