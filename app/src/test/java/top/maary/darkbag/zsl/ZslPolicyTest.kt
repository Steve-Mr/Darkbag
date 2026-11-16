package top.maary.darkbag.zsl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import top.maary.darkbag.camera.zsl.RingConfig
import top.maary.darkbag.camera.zsl.ZslExposurePolicy
import top.maary.darkbag.camera.zsl.ZslMode
import top.maary.darkbag.camera.zsl.ZslPolicy
import top.maary.darkbag.camera.zsl.probe.ZslProbeReport
import top.maary.darkbag.camera.zsl.probe.ZslProbeSample
import top.maary.darkbag.camera.zsl.probe.ZslProbeVerdict

/**
 * 报告 → 策略映射（§3.8.5）：四类输入（null / unsupported / supported / user-off）。
 */
class ZslPolicyTest {

    private fun sample() = ZslProbeSample(
        rawFrameCount = 0,
        rawFps = 0f,
        gapP50Ms = 0f,
        gapP95Ms = 0f,
        gapMaxMs = 0f,
        ageP50Ms = 0f,
        ageP95Ms = 0f,
        copyP50Ms = 0f,
        copyP95Ms = 0f,
        previewFpsBaseline = 0f,
        previewFpsDual = 0f,
        previewDropRatio = 0f,
        metadataHitRate = 0f,
        warmupMs = null,
        thermalStatusBefore = 0,
        thermalStatusAfter = 0,
        memoryDeltaBytes = 0L,
        captureFailures = 0
    )

    private fun report(
        verdict: ZslProbeVerdict,
        ringConfig: RingConfig = RingConfig(3, 24, 2)
    ) = ZslProbeReport(
        version = ZslProbeReport.SCHEMA_VERSION,
        timestampMs = 1_700_000_000_000L,
        deviceFingerprint = "test-device",
        lensId = "0",
        rawWidth = 4000,
        rawHeight = 3000,
        verdict = verdict,
        failures = emptyList(),
        sample = sample(),
        ringConfig = ringConfig
    )

    @Test
    fun testNullReportFallsBackToDisabled() {
        assertEquals(ZslPolicy.Disabled, ZslPolicy.fromReport(null, enabledByUser = true))
    }

    @Test
    fun testUserOffFallsBackToDisabledEvenWithRecommendedReport() {
        val policy = ZslPolicy.fromReport(
            report(ZslProbeVerdict.RECOMMENDED),
            enabledByUser = false
        )

        assertEquals(ZslMode.DISABLED, policy.mode)
        assertEquals(RingConfig.DISABLED, policy.ring)
        assertEquals(ZslExposurePolicy.AE_CONTINUOUS, policy.exposure)
    }

    @Test
    fun testUnsupportedVerdictFallsBackToDisabledRegardlessOfReportRing() {
        val policy = ZslPolicy.fromReport(
            report(ZslProbeVerdict.UNSUPPORTED, ringConfig = RingConfig(3, 24, 2)),
            enabledByUser = true
        )

        assertEquals(ZslMode.DISABLED, policy.mode)
        assertEquals(RingConfig.DISABLED, policy.ring)
    }

    @Test
    fun testSupportedReportEnablesAutoWithReportRing() {
        val ring = RingConfig(depth = 2, targetFps = 15, minValidFrames = 2)
        val policy = ZslPolicy.fromReport(
            report(ZslProbeVerdict.SUPPORTED, ringConfig = ring),
            enabledByUser = true
        )

        assertEquals(ZslMode.AUTO, policy.mode)
        assertEquals(ring, policy.ring)
        assertEquals(ZslExposurePolicy.AE_CONTINUOUS, policy.exposure)
    }

    @Test
    fun testRecommendedReportEnablesAutoWithReportRing() {
        val ring = RingConfig(depth = 3, targetFps = 24, minValidFrames = 2)
        val policy = ZslPolicy.fromReport(
            report(ZslProbeVerdict.RECOMMENDED, ringConfig = ring),
            enabledByUser = true
        )

        assertEquals(ZslMode.AUTO, policy.mode)
        assertEquals(ring, policy.ring)
        assertEquals(ZslExposurePolicy.AE_CONTINUOUS, policy.exposure)
    }

    @Test
    fun testDefaultPolicyAndDisabledConstantAreDisabled() {
        assertEquals(ZslMode.DISABLED, ZslPolicy().mode)
        assertEquals(RingConfig.DISABLED, ZslPolicy().ring)
        assertSame(ZslPolicy.Disabled, ZslPolicy.Disabled)
        assertEquals(ZslPolicy.Disabled, ZslPolicy())
    }
}
