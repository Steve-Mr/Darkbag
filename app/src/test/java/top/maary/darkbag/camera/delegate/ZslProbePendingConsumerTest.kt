package top.maary.darkbag.camera.delegate

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.maary.darkbag.repository.PreferenceKeys
import top.maary.darkbag.repository.SettingsRepository

/**
 * `zsl_probe_pending` 消费点（S1-B2 R10）：
 *
 * - DataStore 未 hydrate 时 `current()` 返回默认快照（pending=false），此时**不得消费**，
 *   否则会把用户刚点下的探测请求静默丢掉；状态必须原样保留，等 `onSettingsHydrated()` 再试。
 * - hydrate 之后恰好消费一次：清 pending + 启动一次探测；重复调用不得二次启动。
 *
 * 覆盖层级（S1-B2 复审 F4）：`hydrated = false` 是**注入的谓词**，钉的是"守卫分支"本身；
 * 真实冷启动的"store 尚未 hydrate"时序由 `SettingsRepository.isHydrated`（`DarkbagPreferencesStore`
 * 的 `hydratedDeferred` 完成前恒为 false）+ Hub 的两次调用点保证，不在本测试重复伪造，
 * 也不与后台 hydration 竞速（那会让测试变成 flaky 的时序断言）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ZslProbePendingConsumerTest {

    private lateinit var repository: SettingsRepository

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(PreferenceKeys.LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        SettingsRepository.resetForTest(context)
        repository = SettingsRepository(context).apply { awaitHydratedBlocking() }
    }

    private fun newConsumer(
        hydrated: Boolean,
        starts: MutableList<Long> = mutableListOf(),
        startResult: Boolean = true
    ) = ZslProbePendingConsumer(
        isHydrated = { hydrated },
        settingsRepository = repository,
        startProbe = {
            starts += System.nanoTime()
            startResult
        }
    ) to starts

    @Test
    fun testNotHydratedDoesNotConsumePendingRequest() {
        repository.setZslProbePending(true)
        val (consumer, starts) = newConsumer(hydrated = false)

        assertFalse("未 hydrate 时不得消费", consumer.consume())

        assertTrue("pending 必须原样保留，等 hydration 完成后再消费", repository.current().zslProbePending)
        assertEquals(0, starts.size)
    }

    @Test
    fun testHydratedConsumesPendingExactlyOnceAndClearsFlag() {
        repository.setZslProbePending(true)
        val (consumer, starts) = newConsumer(hydrated = true)

        assertTrue(consumer.consume())
        assertFalse("pending 必须被清零", repository.current().zslProbePending)
        assertEquals(1, starts.size)

        // 第二次调用（例如 onSessionConfigured 与 onSettingsHydrated 都触发）：不得再启动
        assertFalse(consumer.consume())
        assertEquals(1, starts.size)
    }

    @Test
    fun testHydratedWithoutPendingNeverStartsProbe() {
        val (consumer, starts) = newConsumer(hydrated = true)

        assertFalse(consumer.consume())

        assertEquals(0, starts.size)
        assertFalse(repository.current().zslProbePending)
    }

    @Test
    fun testConsumeReportsDeliveryEvenIfRunnerRejectsStart() {
        // Runner.start() 返回 false（已在运行）时也算"已消费"：pending 不能被重新排上
        repository.setZslProbePending(true)
        val (consumer, starts) = newConsumer(hydrated = true, startResult = false)

        assertTrue(consumer.consume())

        assertEquals(1, starts.size)
        assertFalse(repository.current().zslProbePending)
    }
}
