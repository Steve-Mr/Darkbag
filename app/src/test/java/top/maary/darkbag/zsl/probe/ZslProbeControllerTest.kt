package top.maary.darkbag.zsl.probe

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.maary.darkbag.camera.zsl.RingConfig
import top.maary.darkbag.camera.zsl.ZslStaticCapabilities
import top.maary.darkbag.camera.zsl.probe.*

/**
 * 探测编排（§3.8.3 / S1-A + S1-B1 F2/F3/F6）：正常、双流启动失败、帧数不足、中止、回调异常、
 * 看门狗、恢复失败收口、看门狗硬超时、取消透传、调用方帧率驱动标称间隔。
 *
 * 每条路径都断言恢复语义：`endDualStream` 恰好一次、observer 恰好置空一次、`isRunning` 复位。
 */
class ZslProbeControllerTest {
    private val deviceFingerprint = "Google/Pixel 8/14"
    private val lensId = "0"

    /** 基线窗口 1500 ms ÷ 100 ms 分片。 */
    private val BASELINE_SLICES = 15

    private class FakeClock(
        var nanoTime: Long = 5_000_000_000L,
        var epochMillis: Long = 1_700_000_000_000L
    ) : ZslProbeClock {
        /** 置 true 后下一次 [nowNanos] 抛异常，用于模拟"观察者回调内部抛异常"。 */
        var throwOnNextNanosRead = false

        override fun nowNanos(): Long {
            if (throwOnNextNanosRead) {
                throwOnNextNanosRead = false
                throw IllegalStateException("clock exploded inside observer")
            }
            return nanoTime
        }

        override fun currentTimeMillis(): Long = epochMillis

        fun advanceMs(ms: Long) {
            nanoTime += ms * 1_000_000L
            epochMillis += ms
        }
    }

    private class FakeProbeSession(
        private val clock: FakeClock,
        private val beginResult: Boolean = true,
        private val rawFrameCount: Int = 120,
        private val frameIntervalMs: Long = 33L,
        private val throwOnBegin: Boolean = false,
        private val throwOnObserverCallback: Boolean = false,
        private val captureFailureCount: Int = 0,
        private val endResult: Boolean = true,
        private val throwOnEnd: Boolean = false
    ) : ZslProbeSession {
        override val capabilities = ZslStaticCapabilities(
            hardwareLevel = 3, rawSupported = true, rawWidth = 4000, rawHeight = 3000,
            rawMinFrameDurationMs = 33L, maxRawFrameRate = 1000f / 33L, hasDeviceZslKey = true,
            supportsPrivateReprocessing = true, supportsYuvReprocessing = false,
            syncMaxLatency = 2, pipelineMaxDepth = 3, supportsRawCapability = true
        )
        var observer: ZslProbeFrameObserver? = null
        var beginCount = 0
        var endCount = 0
        var observerClearCount = 0
        var lastTargetFps = -1
        private var memoryReads = 0
        private var thermalReads = 0

        fun emitPreviewFrames(count: Int) {
            val current = observer ?: return
            repeat(count) { current.onPreviewResult(clock.nowNanos()) }
        }

        override fun beginDualStream(targetFps: Int): Boolean {
            beginCount++
            lastTargetFps = targetFps
            if (throwOnBegin) {
                repeat(rawFrameCount) { emitRawFrame() }
                throw IllegalStateException("camera session exploded")
            }
            if (throwOnObserverCallback) {
                repeat(5) { emitRawFrame() }
                clock.throwOnNextNanosRead = true
                observer?.onRawFrame(clock.nanoTime - 25_000_000L, 4L, true)
                return true
            }
            if (!beginResult) return false
            repeat(rawFrameCount) { emitRawFrame() }
            repeat(captureFailureCount) { observer?.onCaptureFailure() }
            return true
        }

        private fun emitRawFrame() {
            clock.advanceMs(frameIntervalMs)
            observer?.onRawFrame(clock.nowNanos() - 25_000_000L, 4L, true)
        }

        override fun endDualStream(): Boolean {
            endCount++
            if (throwOnEnd) throw IllegalStateException("restore exploded")
            return endResult
        }

        override fun setFrameObserver(observer: ZslProbeFrameObserver?) {
            if (observer == null) observerClearCount++
            this.observer = observer
        }

        override fun thermalStatus(): Int? = if (thermalReads++ == 0) 0 else 1

        override fun availableMemoryBytes(): Long =
            if (memoryReads++ == 0) 500_000_000L else 480_000_000L
    }

    private class FakeScheduler(
        private val clock: FakeClock,
        private val session: FakeProbeSession
    ) : ZslProbeScheduler {
        val pauses = mutableListOf<Long>()
        var onPause: ((Long) -> Unit)? = null

        override suspend fun pause(ms: Long) {
            pauses += ms
            clock.advanceMs(ms)
            session.emitPreviewFrames((ms * 30L / 1000L).toInt())
            onPause?.invoke(ms)
        }
    }

    /** 真实挂起等待，用于验证 `withTimeoutOrNull` 硬上界。 */
    private class RealDelayScheduler : ZslProbeScheduler {
        override suspend fun pause(ms: Long) {
            delay(ms)
        }
    }

    /** 在第一个窗口内以取消退出，用于验证"取消不被吞成 Failed"。 */
    private class CancellingScheduler : ZslProbeScheduler {
        override suspend fun pause(ms: Long) {
            throw CancellationException("cancelled by test")
        }
    }

    private class Harness(
        val clock: FakeClock,
        val session: FakeProbeSession,
        val scheduler: FakeScheduler,
        val controller: ZslProbeController
    )

    private fun harness(
        beginResult: Boolean = true,
        rawFrameCount: Int = 120,
        frameIntervalMs: Long = 33L,
        throwOnBegin: Boolean = false,
        throwOnObserverCallback: Boolean = false,
        captureFailureCount: Int = 0,
        endResult: Boolean = true,
        throwOnEnd: Boolean = false,
        watchdogMs: Long = 15_000L
    ): Harness {
        val clock = FakeClock()
        val session = FakeProbeSession(
            clock = clock,
            beginResult = beginResult,
            rawFrameCount = rawFrameCount,
            frameIntervalMs = frameIntervalMs,
            throwOnBegin = throwOnBegin,
            throwOnObserverCallback = throwOnObserverCallback,
            captureFailureCount = captureFailureCount,
            endResult = endResult,
            throwOnEnd = throwOnEnd
        )
        val scheduler = FakeScheduler(clock, session)
        return Harness(
            clock, session, scheduler,
            ZslProbeController(
                session = session,
                scheduler = scheduler,
                clock = clock,
                deviceFingerprint = deviceFingerprint,
                lensId = lensId,
                watchdogMs = watchdogMs
            )
        )
    }

    private fun runProbe(h: Harness, targetFps: Int = 30): ZslProbeResult =
        runBlocking { h.controller.run(targetFps) }

    private fun completed(result: ZslProbeResult): ZslProbeReport {
        assertTrue("expected Completed but was $result", result is ZslProbeResult.Completed)
        return (result as ZslProbeResult.Completed).report
    }

    private fun failed(result: ZslProbeResult): ZslProbeResult.Failed {
        assertTrue("expected Failed but was $result", result is ZslProbeResult.Failed)
        return result as ZslProbeResult.Failed
    }

    private fun assertRestored(h: Harness) {
        assertEquals(1, h.session.endCount)
        assertEquals(1, h.session.observerClearCount)
        assertNull(h.session.observer)
        assertFalse(h.controller.isRunning)
    }

    @Test
    fun testNormalPathProducesRecommendedReportAndRestoresOnce() {
        val h = harness()
        var runningDuringWindow = false
        h.scheduler.onPause = { runningDuringWindow = h.controller.isRunning }
        val report = completed(runProbe(h))
        assertEquals(ZslProbeVerdict.RECOMMENDED, report.verdict)
        assertTrue(report.failures.isEmpty())
        assertEquals(ZslProbeReport.SCHEMA_VERSION, report.version)
        assertEquals(deviceFingerprint, report.deviceFingerprint)
        assertEquals(lensId, report.lensId)
        assertEquals(4000, report.rawWidth)
        assertEquals(3000, report.rawHeight)
        assertEquals(RingConfig(3, 24, 2), report.ringConfig)
        assertEquals(h.clock.epochMillis, report.timestampMs)
        assertEquals(120, report.sample.rawFrameCount)
        assertEquals(30f, report.sample.rawFps, 0.001f)
        assertEquals(33f, report.sample.gapP95Ms, 0.001f)
        assertEquals(25f, report.sample.ageP95Ms, 0.001f)
        assertEquals(4f, report.sample.copyP95Ms, 0.001f)
        assertEquals(30f, report.sample.previewFpsBaseline, 0.001f)
        assertEquals(30f, report.sample.previewFpsDual, 0.001f)
        assertEquals(0f, report.sample.previewDropRatio, 0f)
        assertEquals(1f, report.sample.metadataHitRate, 0f)
        assertEquals(99L, report.sample.warmupMs)
        assertEquals(0, report.sample.thermalStatusBefore)
        assertEquals(1, report.sample.thermalStatusAfter)
        assertEquals(-20_000_000L, report.sample.memoryDeltaBytes)
        assertEquals(1, h.session.beginCount)
        assertEquals(30, h.session.lastTargetFps)
        // F3：窗口等待被切成 100 ms 分片（15 + 40 + 10），总时长与旧实现一致
        assertEquals(65, h.scheduler.pauses.size)
        assertTrue(h.scheduler.pauses.all { it == 100L })
        assertEquals(6500L, h.scheduler.pauses.sum())
        assertTrue(runningDuringWindow)
        assertRestored(h)
    }

    @Test
    fun testBeginDualStreamFalseFailsButStillRestores() {
        val h = harness(beginResult = false)
        val failed = failed(runProbe(h))
        assertEquals(ZslProbeFailure.CAPTURE_FAILED, failed.reason)
        assertTrue(failed.detail!!.contains("false"))
        assertEquals(1500L, h.scheduler.pauses.sum())
        assertRestored(h)
    }

    @Test
    fun testTooFewRawFramesYieldsUnsupportedRawFpsLow() {
        val h = harness(rawFrameCount = 3)
        val report = completed(runProbe(h))
        assertEquals(ZslProbeVerdict.UNSUPPORTED, report.verdict)
        assertEquals(listOf(ZslProbeFailure.RAW_FPS_LOW), report.failures)
        assertEquals(RingConfig.DISABLED, report.ringConfig)
        assertEquals(3, report.sample.rawFrameCount)
        assertRestored(h)
    }

    @Test
    fun testAbortDuringBaselineReturnsAbortedAndRestores() {
        val h = harness()
        h.scheduler.onPause = { h.controller.abort() }
        assertEquals(ZslProbeResult.Aborted, runProbe(h))
        assertEquals(0, h.session.beginCount)
        // F3：中止后最多再等一个 100 ms 分片
        assertEquals(100L, h.scheduler.pauses.sum())
        assertEquals(1, h.scheduler.pauses.size)
        assertRestored(h)
    }

    @Test
    fun testExceptionFromBeginDualStreamFailsAndRestores() {
        val h = harness(throwOnBegin = true)
        val failed = failed(runProbe(h))
        assertEquals(ZslProbeFailure.CAPTURE_FAILED, failed.reason)
        assertTrue(failed.detail!!.contains("camera session exploded"))
        assertRestored(h)
    }

    @Test
    fun testObserverCallbackExceptionFailsAndRestores() {
        val h = harness(throwOnObserverCallback = true)
        val failed = failed(runProbe(h))
        assertEquals(ZslProbeFailure.CAPTURE_FAILED, failed.reason)
        assertTrue(failed.detail!!.contains("clock exploded inside observer"))
        assertRestored(h)
    }

    @Test
    fun testWatchdogTimeoutFailsWithRawStall() {
        val h = harness()
        h.scheduler.onPause = { h.clock.advanceMs(20_000L) }
        val failed = failed(runProbe(h))
        assertEquals(ZslProbeFailure.RAW_STALL, failed.reason)
        assertTrue(failed.detail!!.contains("watchdog"))
        assertEquals(0, h.session.beginCount)
        assertEquals(1500L, h.scheduler.pauses.sum())
        assertRestored(h)
    }

    @Test
    fun testSecondRunWhileRunningIsRejectedWithoutTouchingSession() {
        val h = harness()
        var nested: ZslProbeResult? = null
        h.scheduler.onPause = { nested = runBlocking { h.controller.run(30) } }
        completed(runProbe(h))
        assertNotNull(nested)
        assertTrue(nested is ZslProbeResult.Failed)
        assertEquals("probe already running", (nested as ZslProbeResult.Failed).detail)
        assertEquals(1, h.session.beginCount)
        assertRestored(h)
    }

    @Test
    fun testCaptureFailureCallbacksAreAggregatedAndScored() {
        val h = harness(captureFailureCount = 2)
        val report = completed(runProbe(h))
        assertEquals(2, report.sample.captureFailures)
        assertEquals(ZslProbeVerdict.UNSUPPORTED, report.verdict)
        assertEquals(listOf(ZslProbeFailure.CAPTURE_FAILED), report.failures)
        assertRestored(h)
    }

    // ---------------------------------------------------------------------
    // S1-B1 F2：恢复失败必须收口为 Failed，且优先于 Completed / Aborted
    // ---------------------------------------------------------------------

    @Test
    fun testRestoreReturningFalseTurnsCompletedIntoFailure() {
        val h = harness(endResult = false)
        val failed = failed(runProbe(h))
        assertEquals(ZslProbeFailure.CAPTURE_FAILED, failed.reason)
        assertEquals("restore failed", failed.detail)
        assertEquals(1, h.session.endCount)
        assertEquals(1, h.session.observerClearCount)
        assertNull(h.session.observer)
        assertFalse(h.controller.isRunning)
    }

    @Test
    fun testRestoreThrowingIsAlsoReportedAsFailureAndStillClearsObserver() {
        val h = harness(throwOnEnd = true)
        val failed = failed(runProbe(h))
        assertEquals(ZslProbeFailure.CAPTURE_FAILED, failed.reason)
        assertEquals("restore failed", failed.detail)
        assertEquals(1, h.session.endCount)
        // endDualStream 抛异常时 setFrameObserver(null) 仍必须执行（恰好一次）
        assertEquals(1, h.session.observerClearCount)
        assertNull(h.session.observer)
        assertFalse(h.controller.isRunning)
    }

    @Test
    fun testRestoreFailureTakesPriorityOverAbort() {
        val h = harness(endResult = false)
        h.scheduler.onPause = { h.controller.abort() }
        val failed = failed(runProbe(h))
        assertEquals(ZslProbeFailure.CAPTURE_FAILED, failed.reason)
        assertEquals("restore failed", failed.detail)
        assertEquals(1, h.session.observerClearCount)
        assertFalse(h.controller.isRunning)
    }

    // ---------------------------------------------------------------------
    // S1-B1 F3：分片等待让 abort 即时生效；取消不被吞
    // ---------------------------------------------------------------------

    @Test
    fun testAbortDuringDualWindowExitsWithinOneSlice() {
        val h = harness()
        h.scheduler.onPause = { if (h.session.beginCount > 0) h.controller.abort() }
        assertEquals(ZslProbeResult.Aborted, runProbe(h))
        assertEquals(1, h.session.beginCount)

        // 基线 15 个分片（1500 ms）之后进入 W1；中止标志在 W1 首个分片内被置位，
        // 因此 W1 只多等 1 个分片（100 ms），远小于 4000 ms 窗口。
        val dualWindowPauses = h.scheduler.pauses.drop(BASELINE_SLICES)
        assertEquals(1, dualWindowPauses.size)
        assertEquals(100L, dualWindowPauses.single())
        assertTrue("abort 后退出耗时 ${dualWindowPauses.sum()} ms 必须 ≤150 ms", dualWindowPauses.sum() <= 150L)
        assertEquals(1600L, h.scheduler.pauses.sum())
        assertEquals(100L, h.scheduler.pauses.last())
        assertRestored(h)
    }

    @Test
    fun testWithTimeoutOrNullWatchdogReturnsRawStall() {
        val clock = FakeClock()
        val session = FakeProbeSession(clock = clock)
        val controller = ZslProbeController(
            session = session,
            scheduler = RealDelayScheduler(),
            clock = clock,
            deviceFingerprint = deviceFingerprint,
            lensId = lensId,
            watchdogMs = 50L
        )
        val result = runBlocking { controller.run(30) }

        val failed = failed(result)
        assertEquals(ZslProbeFailure.RAW_STALL, failed.reason)
        assertTrue(failed.detail!!.contains("watchdog"))
        assertEquals(0, session.beginCount)
        assertEquals(1, session.endCount)
        assertEquals(1, session.observerClearCount)
        assertFalse(controller.isRunning)
    }

    @Test
    fun testCancellationIsRethrownAfterRestore() {
        val clock = FakeClock()
        val session = FakeProbeSession(clock = clock)
        val controller = ZslProbeController(
            session = session,
            scheduler = CancellingScheduler(),
            clock = clock,
            deviceFingerprint = deviceFingerprint,
            lensId = lensId
        )

        var thrown: Throwable? = null
        try {
            runBlocking { controller.run(30) }
        } catch (t: Throwable) {
            thrown = t
        }

        assertNotNull("CancellationException must be rethrown", thrown)
        assertTrue("expected CancellationException but was $thrown", thrown is CancellationException)
        assertEquals("cancelled by test", thrown!!.message)
        assertEquals(1, session.endCount)
        assertEquals(1, session.observerClearCount)
        assertNull(session.observer)
        assertFalse(controller.isRunning)
    }

    // ---------------------------------------------------------------------
    // S1-B1 F6：标称间隔由调用方帧率决定
    // ---------------------------------------------------------------------

    @Test
    fun testCallerSelectedTargetFpsDrivesNominalIntervalAt15Fps() {
        val h = harness(rawFrameCount = 60, frameIntervalMs = 67L)
        val report = completed(runProbe(h, targetFps = 15))

        assertEquals(15, h.session.lastTargetFps)
        assertEquals(15f, report.sample.rawFps, 0.001f)
        assertEquals(67f, report.sample.gapP95Ms, 0.001f)
        assertEquals(67f, report.sample.gapMaxMs, 0.001f)
        // 标称 1000/15 ≈ 66.7 ms：67 ms 的实测间隔既不算抖动也不算停顿
        assertEquals(ZslProbeVerdict.SUPPORTED, report.verdict)
        assertTrue(report.failures.isEmpty())
        assertEquals(RingConfig(2, 15, 2), report.ringConfig)
        assertRestored(h)
    }
}
