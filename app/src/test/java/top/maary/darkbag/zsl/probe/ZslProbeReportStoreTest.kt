package top.maary.darkbag.zsl.probe

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.maary.darkbag.camera.zsl.RingConfig
import top.maary.darkbag.camera.zsl.probe.ZslProbeFailure
import top.maary.darkbag.camera.zsl.probe.ZslProbeReport
import top.maary.darkbag.camera.zsl.probe.ZslProbeReportStore
import top.maary.darkbag.camera.zsl.probe.ZslProbeSample
import top.maary.darkbag.camera.zsl.probe.ZslProbeVerdict
import top.maary.darkbag.repository.PreferenceKeys
import top.maary.darkbag.repository.SettingsRepository

/**
 * 报告持久化端口（S1-B1 F7 + S1-B2 R11）：4 个 DataStore 键的默认值、保存（含 schema 版本）、
 * 读回一致、以及 `save("")` 的彻底清空语义。
 *
 * `save` 自 S1-B2 R11 起是 `suspend`（单次 `editAndAwait` 原子写两键），测试统一用 [runBlocking] 驱动。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ZslProbeReportStoreTest {

    private lateinit var repository: SettingsRepository
    private lateinit var store: ZslProbeReportStore

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val legacy = context.getSharedPreferences(PreferenceKeys.LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
        legacy.edit().clear().commit()
        SettingsRepository.resetForTest(context)
        repository = SettingsRepository(context).apply { awaitHydratedBlocking() }
        store = ZslProbeReportStore(repository)
    }

    private fun sample(warmupMs: Long? = 120L) = ZslProbeSample(
        rawFrameCount = 120,
        rawFps = 29.5f,
        gapP50Ms = 33.5f,
        gapP95Ms = 45.25f,
        gapMaxMs = 80f,
        ageP50Ms = 40.5f,
        ageP95Ms = 60.25f,
        copyP50Ms = 2.5f,
        copyP95Ms = 4.75f,
        previewFpsBaseline = 30f,
        previewFpsDual = 27.5f,
        previewDropRatio = 0.083f,
        metadataHitRate = 0.995f,
        warmupMs = warmupMs,
        thermalStatusBefore = 0,
        thermalStatusAfter = 1,
        memoryDeltaBytes = -1_234_567L,
        captureFailures = 1
    )

    private fun report(verdict: ZslProbeVerdict = ZslProbeVerdict.SUPPORTED) = ZslProbeReport(
        version = ZslProbeReport.SCHEMA_VERSION,
        timestampMs = 1_700_000_000_123L,
        deviceFingerprint = "Google/Pixel 8/14",
        lensId = "0",
        rawWidth = 4000,
        rawHeight = 3000,
        verdict = verdict,
        failures = listOf(ZslProbeFailure.RAW_JITTER),
        sample = sample(),
        ringConfig = RingConfig(3, 24, 2)
    )

    private fun save(report: ZslProbeReport?) = runBlocking { store.save(report) }

    private fun save(json: String?) = runBlocking { store.save(json) }

    @Test
    fun testKeysDefaultToEmptyStateBeforeAnySave() {
        val prefs = repository.current()

        assertNull(prefs.zslProbeReportJson)
        assertEquals(0, prefs.zslProbeVersion)
        assertFalse(prefs.zslProbePending)
        assertFalse(prefs.zslExperimentalEnabled)
        assertNull(store.load())
    }

    @Test
    fun testSavePersistsReportJsonAndSchemaVersionThenLoadsBack() {
        val original = report()

        save(original)

        val prefs = repository.current()
        assertEquals(original.toJson(), prefs.zslProbeReportJson)
        assertEquals(ZslProbeReport.SCHEMA_VERSION, prefs.zslProbeVersion)
        val loaded = store.load()
        assertNotNull(loaded)
        assertEquals(original, loaded)
    }

    @Test
    fun testSaveBlankStringClearsReportAndResetsVersion() {
        save(report())
        assertEquals(ZslProbeReport.SCHEMA_VERSION, repository.current().zslProbeVersion)

        save("")

        assertNull(repository.current().zslProbeReportJson)
        assertEquals(0, repository.current().zslProbeVersion)
        assertNull(store.load())
    }

    @Test
    fun testSaveNullReportClearsPreviouslyStoredReport() {
        save(report())

        save(null as ZslProbeReport?)

        assertNull(store.load())
        assertEquals(0, repository.current().zslProbeVersion)
    }

    @Test
    fun testLoadReturnsNullForCorruptedStoredJsonInsteadOfThrowing() {
        repository.setZslProbeReportJson("{not-json")
        repository.setZslProbeVersion(ZslProbeReport.SCHEMA_VERSION)

        assertNull(store.load())
        assertTrue(repository.current().zslProbeReportJson!!.contains("not-json"))
    }

    @Test
    fun testSaveWritesSingleLineJsonWithoutNewlines() {
        save(report())

        val raw = repository.current().zslProbeReportJson!!
        assertFalse(raw.contains("\n"))
        assertTrue(raw.contains("\"version\":${ZslProbeReport.SCHEMA_VERSION}"))
    }
}
