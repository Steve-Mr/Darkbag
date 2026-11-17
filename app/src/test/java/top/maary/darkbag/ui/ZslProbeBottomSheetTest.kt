package top.maary.darkbag.ui

import android.content.ClipboardManager
import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.maary.darkbag.R
import top.maary.darkbag.camera.delegate.CameraEnvironmentHost
import top.maary.darkbag.camera.delegate.CameraZslProbeRunner
import top.maary.darkbag.camera.delegate.ZslProbeState
import top.maary.darkbag.camera.zsl.RingConfig
import top.maary.darkbag.camera.zsl.probe.ZslProbeFailure
import top.maary.darkbag.camera.zsl.probe.ZslProbeReport
import top.maary.darkbag.camera.zsl.probe.ZslProbeReportStore
import top.maary.darkbag.camera.zsl.probe.ZslProbeResult
import top.maary.darkbag.camera.zsl.probe.ZslProbeSample
import top.maary.darkbag.camera.zsl.probe.ZslProbeVerdict
import top.maary.darkbag.databinding.BottomSheetZslProbeBinding
import top.maary.darkbag.repository.SettingsRepository

/** 只测 runner 用到的两项环境能力：`lifecycleScope` 能跑协程，`runtimeState` 故意抛错以快速落 Failed。 */
private class FakeZslEnv(override val hostContext: Context) : CameraEnvironmentHost {
    override val hostActivity get() = error("unused")
    override val isHostAdded = false
    override val lifecycleScope = CoroutineScope(Dispatchers.Unconfined)
    override val cameraViewModel get() = error("unused")
    override val runtimeState get() = error("unused")
    override val sessionArbiter get() = error("unused")
    override val camera2Manager get() = error("unused")
    override val cameraRepository get() = error("unused")
    override val imageRepository get() = error("unused")
    override val lutManager get() = error("unused")
    override val locationHelper get() = error("unused")
    override val displayRotation = 0
    override val shouldMirror = false
    override fun shouldShowAudioPermissionRationale() = false
    override fun requestAudioPermission() = Unit
}

/**
 * 探测 sheet 渲染与状态契约（§3.12.4）：六行指标、结论/进行中 badge 与按钮矩阵、DETAILS 只读面、
 * 复制报告内容，以及"关闭即中止 / 结论被消费后回 Idle"的动作决策。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ZslProbeBottomSheetTest {

    private lateinit var app: Context
    private lateinit var themedContext: ContextThemeWrapper
    private lateinit var binding: BottomSheetZslProbeBinding
    private lateinit var sheet: ZslProbeBottomSheet

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext()
        themedContext = ContextThemeWrapper(app, R.style.AppTheme)
        binding = BottomSheetZslProbeBinding.inflate(LayoutInflater.from(themedContext))
        sheet = ZslProbeBottomSheet()
        sheet.bindViews(binding)
    }

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
        copyThroughputP5MiBps = 2000f,
        copyThroughputP50MiBps = 2500f,
        copyThroughputMeasured = true,
        copyFrameBytes = 24L * 1024 * 1024,
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

    private fun labelAt(index: Int): String {
        val row = binding.containerZslMetrics.getChildAt(index) as ViewGroup
        return row.findViewById<TextView>(R.id.txt_metric_label).text.toString()
    }

    private fun valueAt(index: Int): String {
        val row = binding.containerZslMetrics.getChildAt(index) as ViewGroup
        return row.findViewById<TextView>(R.id.txt_metric_value).text.toString()
    }

    private fun text(view: TextView): String = view.text.toString()

    @Test
    fun testBindReport_rendersSixMetricRowsWithSampleValues() {
        sheet.bindReport(report())

        assertEquals(6, binding.containerZslMetrics.childCount)
        assertEquals(themedContext.getString(R.string.zsl_metric_raw_fps), labelAt(0))
        assertEquals(themedContext.getString(R.string.zsl_metric_gap_p95), labelAt(1))
        assertEquals(themedContext.getString(R.string.zsl_metric_age_p95), labelAt(2))
        assertEquals(themedContext.getString(R.string.zsl_metric_copy_throughput), labelAt(3))
        assertEquals(themedContext.getString(R.string.zsl_metric_preview_drop), labelAt(4))
        assertEquals(themedContext.getString(R.string.zsl_metric_thermal), labelAt(5))

        assertEquals("29.5 fps", valueAt(0))
        assertEquals("45.5 ms", valueAt(1))
        assertEquals("60.5 ms", valueAt(2))
        assertEquals("2000 MiB/s", valueAt(3))
        assertEquals("8%", valueAt(4))
        assertEquals("1", valueAt(5))

        assertEquals("SUPPORTED", text(binding.tvSheetBadge))
        assertEquals("Supported · 24 FPS · 3 frames", text(binding.txtZslProgress))
        // 结论面：Retest + Copy report + Done（M2）
        assertEquals(themedContext.getString(R.string.zsl_action_retest), text(binding.btnZslPrimary))
        assertEquals(themedContext.getString(R.string.zsl_action_copy_report), text(binding.btnZslSecondary))
        assertEquals(themedContext.getString(R.string.zsl_action_done), text(binding.btnZslTertiary))
    }

    @Test
    fun testBindRunning_showsRunningBadgeCancelPrimaryAndUnknownMetrics() {
        sheet.bindRunning()

        assertEquals("RUNNING", text(binding.tvSheetBadge))
        assertEquals(themedContext.getString(R.string.cancel), text(binding.btnZslPrimary))
        assertEquals(View.GONE, binding.btnZslSecondary.visibility)
        assertEquals(View.GONE, binding.btnZslTertiary.visibility)
        assertEquals(6, binding.containerZslMetrics.childCount)
        assertEquals(themedContext.getString(R.string.zsl_metric_unknown), valueAt(0))
        assertEquals(themedContext.getString(R.string.zsl_metric_unknown), valueAt(5))
    }

    @Test
    fun testBindReportUnsupported_showsReasonAndUnsupportedBadge() {
        sheet.bindReport(
            report(
                verdict = ZslProbeVerdict.UNSUPPORTED,
                failures = listOf(ZslProbeFailure.RAW_FPS_LOW),
                ring = RingConfig.DISABLED
            )
        )

        assertEquals("UNSUPPORTED", text(binding.tvSheetBadge))
        assertEquals("Unsupported · RAW frame rate low", text(binding.txtZslProgress))
    }

    @Test
    fun testDetailsOnlyView_offersCopyAndDoneWithoutRetest() {
        sheet.bindReport(report(), canRetest = false)

        assertEquals(themedContext.getString(R.string.zsl_action_done), text(binding.btnZslPrimary))
        assertEquals(themedContext.getString(R.string.zsl_action_copy_report), text(binding.btnZslSecondary))
        assertEquals(View.GONE, binding.btnZslTertiary.visibility)
        assertEquals(6, binding.containerZslMetrics.childCount)
    }

    @Test
    fun testBindViewsKeepsSixRowsAndNeedsNoHardcodedActionText() {
        sheet.bindViews(binding)
        sheet.bindViews(binding)

        assertEquals(6, binding.containerZslMetrics.childCount)
        // 动作文案一律运行期写入（布局只有 tools:text）
        assertTrue(text(binding.btnZslPrimary).isEmpty())
        assertTrue(text(binding.btnZslTertiary).isEmpty())
    }

    @Test
    fun testBindFinished_failedShowsRetestAndDoneWithoutCopy() {
        sheet.bindFinished(ZslProbeResult.Failed(ZslProbeFailure.RAW_FPS_LOW))

        assertEquals("UNSUPPORTED", text(binding.tvSheetBadge))
        assertEquals(
            themedContext.getString(ZslProbeReasonStrings.resId(ZslProbeFailure.RAW_FPS_LOW)),
            text(binding.txtZslProgress)
        )
        assertEquals(themedContext.getString(R.string.zsl_action_retest), text(binding.btnZslPrimary))
        assertEquals(View.GONE, binding.btnZslSecondary.visibility)
        assertEquals(themedContext.getString(R.string.zsl_action_done), text(binding.btnZslTertiary))
    }

    @Test
    fun testBindFinished_abortedShowsCancelledBadgeAndDoneOnly() {
        sheet.bindFinished(ZslProbeResult.Aborted)

        assertEquals("CANCELLED", text(binding.tvSheetBadge))
        assertEquals(themedContext.getString(R.string.zsl_state_aborted), text(binding.txtZslProgress))
        assertEquals(themedContext.getString(R.string.zsl_action_done), text(binding.btnZslPrimary))
        assertEquals(View.GONE, binding.btnZslSecondary.visibility)
        assertEquals(View.GONE, binding.btnZslTertiary.visibility)
    }

    @Test
    fun testCopyReportWritesExactReportJsonToClipboard() {
        val report = report()
        val clipboard = themedContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        sheet.bindReport(report)

        sheet.copyReport(report, clipboard)

        assertEquals(report.toJson(), clipboard.primaryClip?.getItemAt(0)?.text?.toString())
    }

    @Test
    fun testStageTimelineFollowsThreeMeasuredWindows() {
        assertEquals(R.string.zsl_stage_baseline, zslStageRes(0L))
        assertEquals(R.string.zsl_stage_baseline, zslStageRes(1_499L))
        assertEquals(R.string.zsl_stage_dual, zslStageRes(1_500L))
        assertEquals(R.string.zsl_stage_dual, zslStageRes(5_499L))
        assertEquals(R.string.zsl_stage_recovery, zslStageRes(5_500L))
    }

    @Test
    fun testCloseAction_abortsRunningConsumesFinishedDismissesIdle() {
        assertEquals(ZslProbeAction.ABORT, zslProbeActionFor(ZslProbeState.Running(24)))
        assertEquals(
            ZslProbeAction.CONSUME,
            zslProbeActionFor(ZslProbeState.Finished(ZslProbeResult.Aborted))
        )
        assertEquals(ZslProbeAction.DISMISS, zslProbeActionFor(ZslProbeState.Idle))
        assertEquals(ZslProbeAction.NONE, zslProbeActionFor(null))
    }

    @Test
    fun testConsumeFinishedReturnsRunnerToIdle() {
        val runner = CameraZslProbeRunner(
            FakeZslEnv(app),
            {},
            { false },
            ZslProbeReportStore(SettingsRepository(app))
        )

        runner.consumeFinished()
        assertEquals(ZslProbeState.Idle, runner.state.value)

        assertTrue(runner.start())
        runBlocking {
            withTimeout(5_000) { runner.state.first { it is ZslProbeState.Finished } }
        }
        assertTrue(runner.state.value is ZslProbeState.Finished)

        runner.consumeFinished()
        assertEquals(ZslProbeState.Idle, runner.state.value)
    }
}
