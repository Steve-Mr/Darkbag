package top.maary.darkbag.zsl.probe

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
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
 * 报告持久化的**原子性**（S1-B2 R11）：`zsl_probe_report` 与 `zsl_probe_version` 必须由
 * 同一次 DataStore `edit` 写入，且写进去的版本号来自"解码后的报告"，因此：
 * - 保存成功后两键在**落盘快照**里同时存在且互相一致（不存在半更新窗口）；
 * - 非法 JSON **视为清空**（两键一起归零），绝不把无法解码的字符串留在存储里；
 * - 版本键 === 报告解码出的 [ZslProbeReport.version]。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ZslReportStoreAtomicTest {

    private lateinit var repository: SettingsRepository
    private lateinit var store: ZslProbeReportStore

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(PreferenceKeys.LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        SettingsRepository.resetForTest(context)
        repository = SettingsRepository(context).apply { awaitHydratedBlocking() }
        store = ZslProbeReportStore(repository)
    }

    private fun sample() = ZslProbeSample(
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
        warmupMs = 120L,
        thermalStatusBefore = 0,
        thermalStatusAfter = 1,
        memoryDeltaBytes = -1_234_567L,
        captureFailures = 0
    )

    private fun report(
        verdict: ZslProbeVerdict = ZslProbeVerdict.SUPPORTED,
        failures: List<ZslProbeFailure> = emptyList()
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
        ringConfig = RingConfig(3, 24, 2)
    )

    /** 落盘后读取原始键（不经解码器），用于验证"两个键在同一次事务里一致"。 */
    private fun persistedRaw(): Pair<String?, Int> = runBlocking {
        repository.awaitPersisted()
        val snapshot = repository.rawSnapshot()
        snapshot[PreferenceKeys.ZSL_PROBE_REPORT] to
            (snapshot[PreferenceKeys.ZSL_PROBE_VERSION] ?: 0)
    }

    @Test
    fun testBundleSaveWritesBothKeysConsistentlyInOnePersistedTransaction() {
        val original = report()

        val returnedVersion = runBlocking {
            val persisted = store.save(original)
            repository.awaitPersisted()
            persisted
        }
        val policySnapshot = repository.preferences.value

        assertEquals(ZslProbeReport.SCHEMA_VERSION, returnedVersion)
        // 内存快照：两键同时就位
        assertEquals(original.toJson(), policySnapshot.zslProbeReportJson)
        assertEquals(ZslProbeReport.SCHEMA_VERSION, policySnapshot.zslProbeVersion)
        // 落盘快照：两键仍然同时存在，且 JSON 解码出的版本与版本键一致
        val (json, version) = persistedRaw()
        assertNotNull(json)
        assertEquals(ZslProbeReport.SCHEMA_VERSION, version)
        val decoded = ZslProbeReport.fromJson(json)
        assertNotNull(decoded)
        assertEquals(version, decoded!!.version)
        assertEquals(original, decoded)
    }

    @Test
    fun testIllegalJsonClearsBothKeysInsteadOfPersistingGarbage() {
        runBlocking {
            store.save(report())
            repository.awaitPersisted()
        }
        assertEquals(ZslProbeReport.SCHEMA_VERSION, repository.current().zslProbeVersion)

        val returnedVersion = runBlocking { store.save("{not-json") }

        assertEquals(0, returnedVersion)
        assertNull(repository.current().zslProbeReportJson)
        assertEquals(0, repository.current().zslProbeVersion)
        val (json, version) = persistedRaw()
        assertNull(json)
        assertEquals(0, version)
        assertNull(store.load())
    }

    @Test
    fun testIllegalJsonThatDecodesToNullVerdictIsTreatedAsClearToo() {
        // 缺 verdict 的合法 JSON 同样不可采信（fromJson → null），必须清空而不是保存
        val returnedVersion = runBlocking {
            store.save(JSONObject().put("version", 2).put("sample", JSONObject()).toString())
        }

        assertEquals(0, returnedVersion)
        assertNull(store.load())
        val (json, version) = persistedRaw()
        assertNull(json)
        assertEquals(0, version)
    }

    @Test
    fun testPersistedVersionAlwaysEqualsDecodedReportVersion() {
        // 先存一份合法报告，再存"无报告"，版本键必须跟着报告体一起走，绝不出现 2/0 混搭
        runBlocking { store.save(report(verdict = ZslProbeVerdict.RECOMMENDED, failures = emptyList())) }
        var (json, version) = persistedRaw()
        assertNotNull(json)
        assertEquals(ZslProbeReport.fromJson(json)!!.version, version)

        runBlocking { store.save(report(verdict = ZslProbeVerdict.UNSUPPORTED, failures = listOf(ZslProbeFailure.RAW_UNSUPPORTED))) }
        val second = persistedRaw()
        json = second.first
        version = second.second
        val decoded = ZslProbeReport.fromJson(json)
        assertNotNull(decoded)
        assertEquals(ZslProbeVerdict.UNSUPPORTED, decoded!!.verdict)
        assertEquals(decoded.version, version)

        runBlocking { store.clear() }
        val cleared = persistedRaw()
        assertNull(cleared.first)
        assertEquals(0, cleared.second)
        assertFalse(cleared.second == ZslProbeReport.SCHEMA_VERSION)
        assertTrue(store.load() == null)
    }
}
