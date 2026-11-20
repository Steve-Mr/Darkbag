package top.maary.darkbag.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SettingsRepositoryTest {

    private lateinit var context: Context
    private lateinit var repository: SettingsRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val prefs = context.getSharedPreferences(PreferenceKeys.LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        SettingsRepository.resetForTest(context)
        repository = SettingsRepository(context).apply { awaitHydratedBlocking() }
    }

    @Test
    fun testDefaultPreferences() {
        val prefs = repository.current()
        assertEquals("None", prefs.targetLog)
        assertEquals("None", prefs.activeLut)
        assertTrue(prefs.saveJpg)
        assertFalse(prefs.saveRaw)
        assertEquals(5, prefs.hdrBurstCount)
        assertEquals(PreferenceKeys.HDR_FUSION_AUTO, prefs.hdrFusionMode)
        assertEquals(PreferenceKeys.WB_STRATEGY_AMBIENCE, prefs.wbStrategy)
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

        repository.setWbStrategy(PreferenceKeys.WB_STRATEGY_NEUTRAL)
        assertEquals(PreferenceKeys.WB_STRATEGY_NEUTRAL, repository.preferences.value.wbStrategy)
    }

    @Test
    fun testSingletonInstance() {
        val inst1 = SettingsRepository.getInstance(context)
        val inst2 = SettingsRepository.getInstance(context)
        assertSame(inst1, inst2)
    }

    @Test
    fun testLegacyBooleanFlashModeCompatibility() = runBlocking {
        SettingsRepository.resetForTest(context)
        val prefs = context.getSharedPreferences(PreferenceKeys.LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(PreferenceKeys.KEY_FLASH_MODE, true).commit()

        val repo = SettingsRepository(context).apply { awaitHydrated() }
        assertTrue(repo.current().flashEnabled)
        assertEquals("on", repo.current().flashMode)

        repo.setFlashEnabled(false)
        assertFalse(repo.current().flashEnabled)
        assertEquals("off", repo.current().flashMode)

        repo.setFlashMode("on")
        assertTrue(repo.current().flashEnabled)
        assertEquals("on", repo.current().flashMode)
        repo.awaitPersisted()

        val reopened = SettingsRepository(context).apply { awaitHydrated() }
        assertTrue(reopened.current().flashEnabled)
    }

    @Test
    fun testLegacyStringFlashModeCompatibility() = runBlocking {
        SettingsRepository.resetForTest(context)
        val prefs = context.getSharedPreferences(PreferenceKeys.LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(PreferenceKeys.KEY_FLASH_MODE, "on").commit()

        val repo = SettingsRepository(context).apply { awaitHydrated() }
        assertTrue(repo.current().flashEnabled)
        assertEquals("on", repo.current().flashMode)
    }

    @Test
    fun testRawOutputTypeCompatibility() = runBlocking {
        SettingsRepository.resetForTest(context)
        val prefs = context.getSharedPreferences(PreferenceKeys.LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
        // Store as Int = 1 (Linear DNG)
        prefs.edit().putInt(PreferenceKeys.KEY_RAW_OUTPUT_TYPE, 1).commit()

        val repo = SettingsRepository(context).apply { awaitHydrated() }
        assertEquals(1, repo.current().rawOutputFormatIndex)
        assertTrue(repo.current().rawOutputType.contains("Linear"))

        repo.setRawOutputFormatIndex(0)
        assertEquals(0, repo.current().rawOutputFormatIndex)
        assertTrue(repo.current().rawOutputType.contains("Bayer"))
        repo.awaitPersisted()

        // Also test legacy string format
        SettingsRepository.resetForTest(context)
        prefs.edit().putString(PreferenceKeys.KEY_RAW_OUTPUT_TYPE, "Linear DNG RAW (~36MB, Sabre/Merged)").commit()
        val repoLegacy = SettingsRepository(context).apply { awaitHydrated() }
        assertEquals(1, repoLegacy.current().rawOutputFormatIndex)
    }

    @Test
    fun testHdrBurstCountIntAndStringCompatibility() = runBlocking {
        SettingsRepository.resetForTest(context)
        val prefs = context.getSharedPreferences(PreferenceKeys.LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
        // Stored as String
        prefs.edit().putString(PreferenceKeys.KEY_HDR_BURST_COUNT, "7").commit()
        val repoString = SettingsRepository(context).apply { awaitHydrated() }
        assertEquals(7, repoString.current().hdrBurstCount)

        // Stored as Int
        SettingsRepository.resetForTest(context)
        prefs.edit().putInt(PreferenceKeys.KEY_HDR_BURST_COUNT, 8).commit()
        val repoInt = SettingsRepository(context).apply { awaitHydrated() }
        assertEquals(8, repoInt.current().hdrBurstCount)
    }

    @Test
    fun testDefaultFocal1xCompatibility() = runBlocking {
        SettingsRepository.resetForTest(context)
        val prefs = context.getSharedPreferences(PreferenceKeys.LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(PreferenceKeys.KEY_DEFAULT_FOCAL_1X, "28mm").commit()

        val repo = SettingsRepository(context).apply { awaitHydrated() }
        assertEquals("28mm", repo.current().defaultFocal1xName)
        assertEquals(28f, repo.current().defaultFocal1x, 0.01f)

        // Float stored in preferences
        SettingsRepository.resetForTest(context)
        prefs.edit().putFloat(PreferenceKeys.KEY_DEFAULT_FOCAL_1X, 35f).commit()
        val repoFloat = SettingsRepository(context).apply { awaitHydrated() }
        assertEquals("35mm", repoFloat.current().defaultFocal1xName)
        assertEquals(35f, repoFloat.current().defaultFocal1x, 0.01f)
    }

    @Test
    fun testLegacyActiveLutFilenameMigrationAndSessionReset() = runBlocking {
        SettingsRepository.resetForTest(context)
        val prefs = context.getSharedPreferences(PreferenceKeys.LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(PreferenceKeys.KEY_ACTIVE_LUT, "Portra_400.cube")
            .putString(PreferenceKeys.KEY_SELECTED_LENS_SENSOR_ID, "0_24mm")
            .putInt(PreferenceKeys.KEY_LENS_FACING, 0)
            .commit()

        val repo = SettingsRepository(context).apply { awaitHydrated() }
        assertEquals("Portra_400.cube", repo.current().activeLut)
        assertTrue(repo.hasExplicitKey(PreferenceKeys.ACTIVE_LUT))
        assertEquals("0_24mm", repo.current().selectedLensSensorId)
        assertEquals(0, repo.current().lensFacing)

        repo.clearSessionLensState()
        assertNull(repo.current().selectedLensSensorId)
        assertNull(repo.current().lensFacing)
        repo.awaitPersisted()

        val reopened = SettingsRepository(context).apply { awaitHydrated() }
        assertNull(reopened.current().selectedLensSensorId)
        assertNull(reopened.current().lensFacing)
        assertEquals("Portra_400.cube", reopened.current().activeLut)
    }

    @Test
    fun testExifEditConfigSerialization_BackwardCompatibility() {
        // 1. Old JSON without wb_mode
        val oldJson = """
            {
                "log": "Apple Log",
                "lut": "Classic Neg",
                "exposure": 0.5,
                "contrast": 0.1
            }
        """.trimIndent()
        val json = org.json.JSONObject(oldJson)
        val wbMode = json.optInt("wb_mode", 0)
        val wbTempOffset = json.optDouble("wb_temp_offset", 0.0).toFloat()
        val wbTintOffset = json.optDouble("wb_tint_offset", 0.0).toFloat()
        val wbManualTempK = json.optDouble("wb_manual_temp_k", 5500.0).toFloat()

        assertEquals("Old JSON must default to NEUTRAL (0)", 0, wbMode)
        assertEquals(0f, wbTempOffset, 1e-5f)
        assertEquals(0f, wbTintOffset, 1e-5f)
        assertEquals(5500f, wbManualTempK, 1e-5f)

        // 2. Full serialization roundtrip
        val cfg = top.maary.darkbag.models.EditConfig(
            log = "Apple Log",
            lut = "None",
            wbMode = 1,
            wbTempOffset = 15.0f,
            wbTintOffset = -8.0f,
            wbManualTempK = 4800.0f
        )
        val serialized = top.maary.darkbag.utils.ExifMetadataApplier.buildEditConfigJson(cfg)
        assertNotNull(serialized)
        assertEquals(1, serialized!!.getInt("wb_mode"))
        assertEquals(15.0, serialized.getDouble("wb_temp_offset"), 1e-4)
        assertEquals(-8.0, serialized.getDouble("wb_tint_offset"), 1e-4)
        assertEquals(4800.0, serialized.getDouble("wb_manual_temp_k"), 1e-4)
    }
}
