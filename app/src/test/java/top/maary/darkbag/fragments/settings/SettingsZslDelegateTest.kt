package top.maary.darkbag.fragments.settings

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.maary.darkbag.R
import top.maary.darkbag.camera.zsl.RingConfig
import top.maary.darkbag.camera.zsl.probe.ZslProbeFailure
import top.maary.darkbag.camera.zsl.probe.ZslProbeReport
import top.maary.darkbag.camera.zsl.probe.ZslProbeReportStore
import top.maary.darkbag.camera.zsl.probe.ZslProbeSample
import top.maary.darkbag.camera.zsl.probe.ZslProbeVerdict
import top.maary.darkbag.databinding.FragmentSettingsBinding
import top.maary.darkbag.repository.DarkbagPreferences
import top.maary.darkbag.repository.PreferenceKeys
import top.maary.darkbag.repository.SettingsRepository

/**
 * 设置页托盘渲染（S1-8）：LayoutInflater 真实 inflate `fragment_settings.xml` 后断言
 * 三态文案、开关可用性/勾选、按钮文案、无报告时点击开关的回弹与探测请求，
 * 以及三个关键字符串在默认 locale 下确实可解析（非 key 名）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SettingsZslDelegateTest {

    private lateinit var themedContext: ContextThemeWrapper
    private lateinit var binding: FragmentSettingsBinding
    private lateinit var repository: SettingsRepository
    private lateinit var store: ZslProbeReportStore
    private lateinit var delegate: SettingsZslDelegate

    private var probeRequests = 0
    private var detailRequests = 0
    private var confirmRequests = 0
    private var cancelRequests = 0
    private var pendingConfirm: (() -> Unit)? = null
    private val toggleCalls = ArrayList<Boolean>()

    @Before
    fun setup() {
        val app: Context = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences(PreferenceKeys.LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        SettingsRepository.resetForTest(app)
        repository = SettingsRepository(app).apply { awaitHydratedBlocking() }
        store = ZslProbeReportStore(repository)

        themedContext = ContextThemeWrapper(app, R.style.AppTheme)
        binding = FragmentSettingsBinding.inflate(LayoutInflater.from(themedContext))

        delegate = SettingsZslDelegate(themedContext, store)
        delegate.onRequestProbe = { probeRequests++ }
        delegate.onShowDetails = { detailRequests++ }
        delegate.onToggle = { toggleCalls.add(it) }
        delegate.onConfirmProbe = { confirmed ->
            confirmRequests++
            pendingConfirm = confirmed
        }
        delegate.onCancelProbe = { cancelRequests++ }
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

    private fun save(report: ZslProbeReport?) = runBlocking { store.save(report) }

    private fun render(prefs: DarkbagPreferences = DarkbagPreferences()) =
        delegate.render(binding, prefs)

    @Test
    fun testNoReport_rendersUntestedWithEnabledButUncheckedSwitch() {
        render()

        assertEquals("Not tested", binding.txtZslStateValue.text.toString())
        assertEquals("PROBE", binding.txtZslStateLabel.text.toString())
        assertEquals("Run test", binding.btnZslRun.text.toString())
        assertEquals("Details", binding.btnZslDetails.text.toString())
        assertTrue(binding.switchZslEnabled.isEnabled)
        assertFalse(binding.switchZslEnabled.isChecked)
        assertFalse(binding.btnZslDetails.isEnabled)
        assertFalse(delegate.isRendering)
    }

    @Test
    fun testRecommendedReport_rendersVerdictAndReflectsPreference() {
        save(report())
        render(DarkbagPreferences(zslExperimentalEnabled = true))

        assertEquals("Supported · 24 FPS · 3 frames", binding.txtZslStateValue.text.toString())
        assertEquals("Retest", binding.btnZslRun.text.toString())
        assertTrue(binding.switchZslEnabled.isEnabled)
        assertTrue(binding.switchZslEnabled.isChecked)
        assertTrue(binding.btnZslDetails.isEnabled)
    }

    @Test
    fun testUnsupportedReport_disablesSwitchAndShowsReason() {
        save(report(verdict = ZslProbeVerdict.UNSUPPORTED, failures = listOf(ZslProbeFailure.RAW_FPS_LOW), ring = RingConfig.DISABLED))
        render(DarkbagPreferences(zslExperimentalEnabled = true))

        assertEquals("Unsupported · RAW frame rate low", binding.txtZslStateValue.text.toString())
        assertFalse(binding.switchZslEnabled.isEnabled)
        assertFalse(binding.switchZslEnabled.isChecked)
        assertEquals("Retest", binding.btnZslRun.text.toString())
    }

    @Test
    fun testPendingPreference_locksSwitchAndCancelsProbe() {
        render(DarkbagPreferences(zslProbePending = true))

        assertEquals("Waiting for viewfinder", binding.txtZslStateValue.text.toString())
        assertFalse(binding.switchZslEnabled.isEnabled)
        assertFalse(binding.switchZslEnabled.isChecked)
        assertEquals("Cancel", binding.btnZslRun.text.toString())

        binding.btnZslRun.performClick()

        assertEquals(1, cancelRequests)
        assertEquals(0, probeRequests)
    }

    @Test
    fun testSwitchTapWithoutReport_asksConfirmationBeforeProbe() {
        render()

        binding.switchZslEnabled.performClick()

        // 回弹 + 只弹确认，未确认前绝不写 pending / 请求探测（F3）
        assertFalse(binding.switchZslEnabled.isChecked)
        assertEquals(1, confirmRequests)
        assertEquals(0, probeRequests)
        assertTrue(toggleCalls.isEmpty())

        pendingConfirm!!.invoke()

        assertEquals(1, probeRequests)
    }

    @Test
    fun testPendingSwitchTap_neverRequestsProbeNorConfirmation() {
        render(DarkbagPreferences(zslProbePending = true))

        // 绕过禁用态直接翻转，验证 F1 的第二层守卫（回弹且不触发任何请求）
        binding.switchZslEnabled.isChecked = true

        assertFalse(binding.switchZslEnabled.isChecked)
        assertEquals(0, confirmRequests)
        assertEquals(0, probeRequests)
    }

    @Test
    fun testSwitchTapWithValidReport_persistsThroughToggleCallback() {
        save(report())
        render(DarkbagPreferences(zslExperimentalEnabled = true))

        binding.switchZslEnabled.performClick()

        assertEquals(listOf(false), toggleCalls)
        assertEquals(0, probeRequests)
    }

    @Test
    fun testButtonsAndDetailsDispatchCallbacks() {
        save(report())
        render()

        binding.btnZslRun.performClick()
        binding.btnZslDetails.performClick()

        assertEquals(1, probeRequests)
        assertEquals(1, detailRequests)
    }

    @Test
    fun testZslStringsResolveInDefaultLocale() {
        listOf(
            R.string.settings_zsl_title,
            R.string.settings_zsl_sub,
            R.string.pref_zsl_enable
        ).forEach { res ->
            val value = themedContext.getString(res)
            val key = themedContext.resources.getResourceEntryName(res)
            assertTrue("empty string resource $key", value.isNotBlank())
            assertNotEquals("unresolved resource name for $key", key, value)
        }
    }
}
