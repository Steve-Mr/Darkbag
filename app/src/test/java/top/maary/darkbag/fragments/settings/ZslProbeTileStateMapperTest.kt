package top.maary.darkbag.fragments.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.maary.darkbag.R
import top.maary.darkbag.camera.zsl.RingConfig
import top.maary.darkbag.camera.zsl.probe.ZslProbeFailure
import top.maary.darkbag.camera.zsl.probe.ZslProbeReport
import top.maary.darkbag.camera.zsl.probe.ZslProbeSample
import top.maary.darkbag.camera.zsl.probe.ZslProbeVerdict
import top.maary.darkbag.repository.DarkbagPreferences
import top.maary.darkbag.ui.ZslProbeReasonStrings

/**
 * 托盘三态映射（设计 §3.12.3）：无报告 / 待执行 / 支持 / 受限 / 不支持 / 用户关闭，
 * 以及"不支持时开关必须禁用"与"原因码回落"两条边界。
 *
 * 纯 JVM 测试：mapper 不持有 Context，`R.string` 只当整型比对。
 */
class ZslProbeTileStateMapperTest {

    private fun prefs(
        zslExperimentalEnabled: Boolean = false,
        zslProbePending: Boolean = false
    ) = DarkbagPreferences(
        zslExperimentalEnabled = zslExperimentalEnabled,
        zslProbePending = zslProbePending
    )

    private fun sample() = ZslProbeSample(
        rawFrameCount = 120,
        rawFps = 29.5f,
        gapP50Ms = 33.5f,
        gapP95Ms = 45.5f,
        gapMaxMs = 80f,
        ageP50Ms = 40.5f,
        ageP95Ms = 60.5f,
        copyP50Ms = 2.5f,
        copyP95Ms = 4.5f,
        previewFpsBaseline = 30f,
        previewFpsDual = 27.5f,
        previewDropRatio = 0.083f,
        metadataHitRate = 0.995f,
        warmupMs = 120L,
        thermalStatusBefore = 0,
        thermalStatusAfter = 1,
        memoryDeltaBytes = -1_234_567L,
        captureFailures = 0
    )

    private fun report(
        verdict: ZslProbeVerdict = ZslProbeVerdict.RECOMMENDED,
        failures: List<ZslProbeFailure> = emptyList(),
        ring: RingConfig = RingConfig(3, 24, 2)
    ) = ZslProbeReport(
        version = ZslProbeReport.SCHEMA_VERSION,
        timestampMs = 1_700_000_000_123L,
        deviceFingerprint = "Google/Pixel 8/14",
        lensId = "0",
        rawWidth = 4000,
        rawHeight = 3000,
        verdict = verdict,
        failures = failures,
        sample = sample(),
        ringConfig = ring
    )

    @Test
    fun testNoReport_showsUntestedRunTestAndClickableSwitch() {
        val state = ZslProbeTileStateMapper.map(prefs(), null)

        assertEquals(R.string.zsl_state_untested, state.statusRes)
        assertTrue(state.statusArgs.isEmpty())
        assertEquals(R.string.zsl_badge_experimental, state.badgeRes)
        assertTrue(state.switchEnabled)
        assertFalse(state.switchChecked)
        assertEquals(R.string.zsl_action_run, state.primaryActionRes)
    }

    @Test
    fun testNoReportPending_locksSwitchAndOffersCancel() {
        val state = ZslProbeTileStateMapper.map(prefs(zslProbePending = true), null)

        assertEquals(R.string.zsl_state_pending, state.statusRes)
        assertTrue(state.statusArgs.isEmpty())
        // 探测已排队：开关锁定，主按钮变"取消"（F2）
        assertFalse(state.switchEnabled)
        assertFalse(state.switchChecked)
        assertEquals(R.string.cancel, state.primaryActionRes)
    }

    @Test
    fun testPendingWinsOverExistingReport_doesNotReEnableSwitch() {
        val state = ZslProbeTileStateMapper.map(
            prefs(zslExperimentalEnabled = true, zslProbePending = true),
            report()
        )

        assertEquals(R.string.zsl_state_pending, state.statusRes)
        assertFalse(state.switchEnabled)
        assertEquals(R.string.cancel, state.primaryActionRes)
    }

    @Test
    fun testRecommended_showsSupportedWithRuntimeRingNumbers() {
        val state = ZslProbeTileStateMapper.map(prefs(zslExperimentalEnabled = true), report())

        assertEquals(R.string.zsl_state_supported, state.statusRes)
        assertEquals(listOf<Any>(24, 3), state.statusArgs)
        assertEquals(R.string.zsl_probe_badge_supported, state.badgeRes)
        assertTrue(state.switchEnabled)
        assertTrue(state.switchChecked)
        assertEquals(R.string.zsl_action_retest, state.primaryActionRes)
    }

    @Test
    fun testSupportedVerdict_showsLimitedTier() {
        val state = ZslProbeTileStateMapper.map(
            prefs(),
            report(verdict = ZslProbeVerdict.SUPPORTED, ring = RingConfig(2, 20, 2))
        )

        assertEquals(R.string.zsl_state_limited, state.statusRes)
        assertEquals(listOf<Any>(20, 2), state.statusArgs)
        assertEquals(R.string.zsl_probe_badge_limited, state.badgeRes)
        assertTrue(state.switchEnabled)
        assertEquals(R.string.zsl_action_retest, state.primaryActionRes)
    }

    @Test
    fun testUserOff_showsSupportedButUncheckedSwitch() {
        val state = ZslProbeTileStateMapper.map(prefs(zslExperimentalEnabled = false), report())

        assertEquals(R.string.zsl_state_supported, state.statusRes)
        assertTrue(state.switchEnabled)
        assertFalse(state.switchChecked)
    }

    @Test
    fun testUnsupported_disablesSwitchAndCarriesFailureCode() {
        val state = ZslProbeTileStateMapper.map(
            prefs(zslExperimentalEnabled = true),
            report(
                verdict = ZslProbeVerdict.UNSUPPORTED,
                failures = listOf(ZslProbeFailure.RAW_FPS_LOW),
                ring = RingConfig.DISABLED
            )
        )

        assertEquals(R.string.zsl_state_unsupported, state.statusRes)
        assertEquals(listOf<Any>(ZslProbeFailure.RAW_FPS_LOW), state.statusArgs)
        assertEquals(R.string.zsl_probe_badge_unsupported, state.badgeRes)
        assertFalse(state.switchEnabled)
        assertFalse(state.switchChecked)
        assertEquals(R.string.zsl_action_retest, state.primaryActionRes)
    }

    @Test
    fun testUnsupportedWithoutFailures_fallsBackToRawUnsupported() {
        val state = ZslProbeTileStateMapper.map(
            prefs(),
            report(verdict = ZslProbeVerdict.UNSUPPORTED, ring = RingConfig.DISABLED)
        )

        assertEquals(listOf<Any>(ZslProbeFailure.RAW_UNSUPPORTED), state.statusArgs)
    }

    @Test
    fun testReasonCodesMapToKnownStringsAndFallBackForUnknownCodes() {
        assertEquals(R.string.zsl_reason_raw_fps_low, ZslProbeReasonStrings.resId(ZslProbeFailure.RAW_FPS_LOW))
        assertEquals(
            R.string.zsl_reason_preview_degraded,
            ZslProbeReasonStrings.resId(ZslProbeFailure.PREVIEW_DEGRADED)
        )
        assertEquals(R.string.zsl_reason_thermal_risk, ZslProbeReasonStrings.resId(ZslProbeFailure.THERMAL_RISK))
        // 未在 UI 有专属短句的原因码统一回落通用"此设备不支持"
        assertEquals(R.string.zsl_reason_unsupported, ZslProbeReasonStrings.resId(ZslProbeFailure.RAW_JITTER))
        assertEquals(R.string.zsl_reason_unsupported, ZslProbeReasonStrings.resId(ZslProbeFailure.CAPTURE_FAILED))
    }
}
