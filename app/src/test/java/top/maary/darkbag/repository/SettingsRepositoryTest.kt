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

    @Test
    fun testLegacyBooleanFlashModeCompatibility() {
        val prefs = context.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(SettingsFragment.KEY_FLASH_MODE, true).commit()

        val repo = SettingsRepository(context)
        assertTrue(repo.current().flashEnabled)
        assertEquals("on", repo.current().flashMode)

        repo.setFlashEnabled(false)
        assertFalse(repo.current().flashEnabled)
        assertEquals("off", repo.current().flashMode)
        assertFalse(prefs.getBoolean(SettingsFragment.KEY_FLASH_MODE, true))

        repo.setFlashMode("on")
        assertTrue(repo.current().flashEnabled)
        assertEquals("on", repo.current().flashMode)
        assertTrue(prefs.getBoolean(SettingsFragment.KEY_FLASH_MODE, false))
    }

    @Test
    fun testLegacyStringFlashModeCompatibility() {
        val prefs = context.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(SettingsFragment.KEY_FLASH_MODE, "on").commit()

        val repo = SettingsRepository(context)
        assertTrue(repo.current().flashEnabled)
        assertEquals("on", repo.current().flashMode)
    }

    @Test
    fun testRawOutputTypeCompatibility() {
        val prefs = context.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        // Store as Int = 1 (Linear DNG)
        prefs.edit().putInt(SettingsFragment.KEY_RAW_OUTPUT_TYPE, 1).commit()

        val repo = SettingsRepository(context)
        assertEquals(1, repo.current().rawOutputFormatIndex)
        assertTrue(repo.current().rawOutputType.contains("Linear"))

        repo.setRawOutputFormatIndex(0)
        assertEquals(0, repo.current().rawOutputFormatIndex)
        assertTrue(repo.current().rawOutputType.contains("Bayer"))
        assertEquals(0, prefs.getInt(SettingsFragment.KEY_RAW_OUTPUT_TYPE, -1))

        // Also test legacy string format
        prefs.edit().putString(SettingsFragment.KEY_RAW_OUTPUT_TYPE, "Linear DNG RAW (~36MB, Sabre/Merged)").commit()
        val repoLegacy = SettingsRepository(context)
        assertEquals(1, repoLegacy.current().rawOutputFormatIndex)
    }

    @Test
    fun testHdrBurstCountIntAndStringCompatibility() {
        val prefs = context.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        // Stored as String
        prefs.edit().putString(SettingsFragment.KEY_HDR_BURST_COUNT, "7").commit()
        val repoString = SettingsRepository(context)
        assertEquals(7, repoString.current().hdrBurstCount)

        // Stored as Int
        prefs.edit().putInt(SettingsFragment.KEY_HDR_BURST_COUNT, 8).commit()
        val repoInt = SettingsRepository(context)
        assertEquals(8, repoInt.current().hdrBurstCount)
    }

    @Test
    fun testDefaultFocal1xCompatibility() {
        val prefs = context.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(SettingsFragment.KEY_DEFAULT_FOCAL_1X, "28mm").commit()

        val repo = SettingsRepository(context)
        assertEquals("28mm", repo.current().defaultFocal1xName)
        assertEquals(28f, repo.current().defaultFocal1x, 0.01f)

        // Float stored in preferences
        prefs.edit().putFloat(SettingsFragment.KEY_DEFAULT_FOCAL_1X, 35f).commit()
        val repoFloat = SettingsRepository(context)
        assertEquals("35mm", repoFloat.current().defaultFocal1xName)
        assertEquals(35f, repoFloat.current().defaultFocal1x, 0.01f)
    }
}
