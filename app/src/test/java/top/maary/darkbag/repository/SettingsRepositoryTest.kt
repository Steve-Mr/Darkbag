package top.maary.darkbag.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.maary.darkbag.fragments.SettingsFragment

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SettingsRepositoryTest {

    private lateinit var context: Context
    private lateinit var repository: SettingsRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val prefs = context.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        repository = SettingsRepository(context)
    }

    @Test
    fun testDefaultPreferences() {
        val prefs = repository.current()
        assertEquals("None", prefs.targetLog)
        assertEquals("None", prefs.activeLut)
        assertTrue(prefs.saveJpg)
        assertFalse(prefs.saveRaw)
        assertEquals(5, prefs.hdrBurstCount)
        assertEquals(SettingsFragment.HDR_FUSION_AUTO, prefs.hdrFusionMode)
    }

    @Test
    fun testReactiveUpdates() {
        assertEquals("None", repository.preferences.value.targetLog)

        repository.setTargetLog("Apple Log")
        assertEquals("Apple Log", repository.preferences.value.targetLog)

        repository.setActiveLut("Kodak_Portra_400.cube", "content://lut/1")
        assertEquals("Kodak_Portra_400.cube", repository.preferences.value.activeLut)
        assertEquals("content://lut/1", repository.preferences.value.lutUri)

        repository.setSaveRaw(true)
        assertTrue(repository.preferences.value.saveRaw)

        repository.setHdrBurstCount(8)
        assertEquals(8, repository.preferences.value.hdrBurstCount)
    }

    @Test
    fun testSingletonInstance() {
        val inst1 = SettingsRepository.getInstance(context)
        val inst2 = SettingsRepository.getInstance(context)
        assertSame(inst1, inst2)
    }
}
