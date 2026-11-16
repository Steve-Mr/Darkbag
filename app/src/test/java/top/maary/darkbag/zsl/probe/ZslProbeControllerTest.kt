package top.maary.darkbag.zsl.probe

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.maary.darkbag.camera.zsl.RingConfig
import top.maary.darkbag.camera.zsl.ZslStaticCapabilities
import top.maary.darkbag.camera.zsl.probe.*

/**
 * 探测编排（§3.8.3 / S1-4）：正常、双流启动失败、帧数不足、中止、回调异常、看门狗。
 * 每条路径都断言恢复语义：`endDualStream` 恰好一次、observer 恰好置空一次、`isRunning` 复位。
 */
class ZslProbeControllerTest {
    private val deviceFingerprint = "Google/Pixel 8/14"
    private val lensId = "0"

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
        private val throwOnBegin: Boolean = false,
        private val throwOnObserverCallback: Boolean = false,
        private val captureFailureCount: Int = 0
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
            clock.advanceMs(33L)
            observer?.onRawFrame(clock.nowNanos() - 25_000_000L, 4L, true)
        }

        override fun endDualStream(): Boolean {
            endCount++
            return true
        }

        override fun setFrameObserver(observer: ZslProbeFrameObserver?) {
            if (observer == null) observerClearCount++
            this.observer = observer
        }

        override fun thermalStatus(): Int = if (thermalReads++ == 0) 0 else 1

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

    private class Harness(
        val clock: FakeClock,
        val session: FakeProbeSession,
        val scheduler: FakeScheduler,
        val controller: ZslProbeController
    )

    private fun harness(
        beginResult: Boolean = true,
        rawFrameCount: Int = 120,
        throwOnBegin: Boolean = false,
        throwOnObserverCallback: Boolean = false,
        captureFailureCount: Int = 0
    ): Harness {
        val clock = FakeClock()
        val session = FakeProbeSession(
            clock = clock,
            beginResult = beginResult,
            rawFrameCount = rawFrameCount,
            throwOnBegin = throwOnBegin,
            throwOnObserverCallback = throwOnObserverCallback,
            captureFailureCount = captureFailureCount
        )
        val scheduler = FakeScheduler(clock, session)
        return Harness(
            clock, session, scheduler,
            ZslProbeController(session, scheduler, clock, deviceFingerprint, lensId)
        )
    }

    private fun completed(result: ZslProbeResult): ZslProbeReport {
        assertTrue(result is ZslProbeResult.Completed)
        return (result as ZslProbeResult.Completed).report
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
        val report = completed(runBlocking { h.controller.run() })
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
        assertEquals(listOf(1500L, 4000L, 1000L), h.scheduler.pauses)
        assertTrue(runningDuringWindow)
        assertRestored(h)
    }

    @Test
    fun testBeginDualStreamFalseFailsButStillRestores() {
        val h = harness(beginResult = false)
        val result = runBlocking { h.controller.run() }
        assertTrue(result is ZslProbeResult.Failed)
        val failed = result as ZslProbeResult.Failed
        assertEquals(ZslProbeFailure.CAPTURE_FAILED, failed.reason)
        assertTrue(failed.detail!!.contains("false"))
        assertEquals(listOf(1500L), h.scheduler.pauses)
        assertRestored(h)
    }

    @Test
    fun testTooFewRawFramesYieldsUnsupportedRawFpsLow() {
        val h = harness(rawFrameCount = 3)
        val report = completed(runBlocking { h.controller.run() })
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
        assertEquals(ZslProbeResult.Aborted, runBlocking { h.controller.run() })
        assertEquals(0, h.session.beginCount)
        assertEquals(listOf(1500L), h.scheduler.pauses)
        assertRestored(h)
    }

    @Test
    fun testExceptionFromBeginDualStreamFailsAndRestores() {
        val h = harness(throwOnBegin = true)
        val result = runBlocking { h.controller.run() }
        assertTrue(result is ZslProbeResult.Failed)
        val failed = result as ZslProbeResult.Failed
        assertEquals(ZslProbeFailure.CAPTURE_FAILED, failed.reason)
        assertTrue(failed.detail!!.contains("camera session exploded"))
        assertRestored(h)
    }

    @Test
    fun testObserverCallbackExceptionFailsAndRestores() {
        val h = harness(throwOnObserverCallback = true)
        val result = runBlocking { h.controller.run() }
        assertTrue(result is ZslProbeResult.Failed)
        val failed = result as ZslProbeResult.Failed
        assertEquals(ZslProbeFailure.CAPTURE_FAILED, failed.reason)
        assertTrue(failed.detail!!.contains("clock exploded inside observer"))
        assertRestored(h)
    }

    @Test
    fun testWatchdogTimeoutFailsWithRawStall() {
        val h = harness()
        h.scheduler.onPause = { h.clock.advanceMs(20_000L) }
        val result = runBlocking { h.controller.run() }
        assertTrue(result is ZslProbeResult.Failed)
        val failed = result as ZslProbeResult.Failed
        assertEquals(ZslProbeFailure.RAW_STALL, failed.reason)
        assertTrue(failed.detail!!.contains("watchdog"))
        assertEquals(0, h.session.beginCount)
        assertRestored(h)
    }

    @Test
    fun testSecondRunWhileRunningIsRejectedWithoutTouchingSession() {
        val h = harness()
        var nested: ZslProbeResult? = null
        h.scheduler.onPause = { nested = runBlocking { h.controller.run() } }
        completed(runBlocking { h.controller.run() })
        assertTrue(nested is ZslProbeResult.Failed)
        assertEquals("probe already running", (nested as ZslProbeResult.Failed).detail)
        assertEquals(1, h.session.beginCount)
        assertRestored(h)
    }

    @Test
    fun testCaptureFailureCallbacksAreAggregatedAndScored() {
        val h = harness(captureFailureCount = 2)
        val report = completed(runBlocking { h.controller.run() })
        assertEquals(2, report.sample.captureFailures)
        assertEquals(ZslProbeVerdict.UNSUPPORTED, report.verdict)
        assertEquals(listOf(ZslProbeFailure.CAPTURE_FAILED), report.failures)
        assertRestored(h)
    }
}
