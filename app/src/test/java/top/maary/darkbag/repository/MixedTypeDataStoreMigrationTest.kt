package top.maary.darkbag.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
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
import top.maary.darkbag.calibration.LensCalibrationStore
import top.maary.darkbag.utils.HalfFrameSessionStore
import top.maary.darkbag.utils.LutManager

/**
 * Verifies atomic migration of legacy `camera_settings` SharedPreferences (including polymorphic
 * mixed-type keys, StringSets, and dynamic profile/lens/LUT keys) into Jetpack DataStore,
 * pre-hydration mutation replay (so early startup edits never clobber disk state), and
 * monotonic concurrent listener ordering.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MixedTypeDataStoreMigrationTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(PreferenceKeys.LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        SettingsRepository.resetForTest(context)
    }

    @Test
    fun testAtomicMigrationOfAllMixedTypeAndDynamicLegacyKeys() = runBlocking {
        val prefs = context.getSharedPreferences(PreferenceKeys.LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
        val profile = HalfFrameSessionStore.PROFILE_HALF_SIDE
        val calibrationLenses = setOf("0", "2")
        val captureTime = System.currentTimeMillis()

        prefs.edit()
            // Legacy mixed-type primitives
            .putString(PreferenceKeys.KEY_FLASH_MODE, "on")
            .putString(PreferenceKeys.KEY_RAW_OUTPUT_TYPE, "Linear DNG RAW (~36MB, Sabre/Merged)")
            .putFloat(PreferenceKeys.KEY_DEFAULT_FOCAL_1X, 28f)
            .putString(PreferenceKeys.KEY_HDR_BURST_COUNT, "7")
            .putString(PreferenceKeys.KEY_ACTIVE_LUT, "Classic_Neg.cube")
            .putString(PreferenceKeys.KEY_TARGET_LOG, "F-Log2")
            // HalfFrameSessionStore dynamic profile keys
            .putInt(PreferenceKeys.KEY_HALF_FRAME_STEP + "_" + profile, 1)
            .putString(PreferenceKeys.KEY_HALF_FRAME_TEMP_PATH + "_" + profile, "/tmp/hf_step1.jpg")
            .putString(PreferenceKeys.KEY_HALF_FRAME_TEMP_DNG_PATH + "_" + profile, "/tmp/hf_step1.dng")
            .putString(PreferenceKeys.KEY_HALF_FRAME_BASE_NAME + "_" + profile, "HF_20261115_001")
            .putLong(PreferenceKeys.KEY_HALF_FRAME_CAPTURE_TIME + "_" + profile, captureTime)
            .putFloat(PreferenceKeys.KEY_HALF_FRAME_DIGITAL_GAIN + "_" + profile, 1.5f)
            .putInt(PreferenceKeys.KEY_HALF_FRAME_FLARE_TYPE + "_" + profile, 2)
            // LensCalibrationStore StringSet + dynamic per-lens keys
            .putStringSet(PreferenceKeys.KEY_CALIBRATED_LENSES, calibrationLenses)
            .putBoolean(PreferenceKeys.lensCalibrationEnabledKey("0").name, true)
            .putFloat(PreferenceKeys.lensCalibrationK1Key("0").name, 0.015f)
            .putFloat(PreferenceKeys.lensCalibrationK2Key("0").name, -0.002f)
            .putFloat(PreferenceKeys.lensCalibrationFocalKey("0").name, 5.5f)
            .putBoolean(PreferenceKeys.lensCalibrationEnabledKey("2").name, false)
            .putFloat(PreferenceKeys.lensCalibrationK1Key("2").name, -0.065f)
            .putFloat(PreferenceKeys.lensCalibrationK2Key("2").name, 0.003f)
            .putFloat(PreferenceKeys.lensCalibrationFocalKey("2").name, 2.1f)
            // LutManager dynamic association keys
            .putString(PreferenceKeys.lutAssocLogKey("Classic_Neg.cube").name, "F-Log2")
            .putString(PreferenceKeys.lutAssocLogKey("Universal_Retro.cube").name, LutManager.LOG_UNIVERSAL)
            .commit()

        val repo = SettingsRepository.getInstance(context)
        repo.awaitHydrated()

        val current = repo.current()
        assertTrue(current.flashEnabled)
        assertEquals("on", current.flashMode)
        assertEquals(1, current.rawOutputFormatIndex)
        assertTrue(current.rawOutputType.contains("Linear"))
        assertEquals("28mm", current.defaultFocal1xName)
        assertEquals(28f, current.defaultFocal1x, 0.01f)
        assertEquals(7, current.hdrBurstCount)
        assertEquals("Classic_Neg.cube", current.activeLut)
        assertEquals("F-Log2", current.targetLog)

        // Verify HalfFrameSessionStore reads migrated dynamic keys
        val hfStore = HalfFrameSessionStore(context)
        val hfSession = hfStore.readSession(profile = profile, strict = false)
        assertEquals(1, hfSession.step)
        assertEquals("/tmp/hf_step1.jpg", hfSession.tempPath)
        assertEquals("/tmp/hf_step1.dng", hfSession.tempDngPath)
        assertEquals("HF_20261115_001", hfSession.baseName)
        assertEquals(captureTime, hfSession.captureTimeMillis)
        assertEquals(1.5f, hfSession.digitalGain, 0.001f)
        assertEquals(2, hfSession.flareType)

        // Verify LensCalibrationStore reads migrated StringSet + dynamic calibration keys
        val calibStore = LensCalibrationStore(context)
        val allCalibs = calibStore.getAllCalibrations()
        assertEquals(2, allCalibs.size)
        val lens0 = calibStore.getCalibration("0")
        assertNotNull(lens0)
        assertTrue(lens0!!.isEnabled)
        assertEquals(0.015f, lens0.k1, 0.0001f)
        assertEquals(-0.002f, lens0.k2, 0.0001f)
        assertEquals(5.5f, lens0.focalLength, 0.001f)
        val lens2 = calibStore.getCalibration("2")
        assertNotNull(lens2)
        assertFalse(lens2!!.isEnabled)
        assertEquals(-0.065f, lens2.k1, 0.0001f)

        // Verify LutManager reads migrated dynamic LUT-log bindings
        val lutManager = LutManager(context)
        assertEquals("F-Log2", lutManager.getAssociatedLog("Classic_Neg.cube"))
        assertEquals(LutManager.LOG_UNIVERSAL, lutManager.getAssociatedLog("Universal_Retro.cube"))
    }

    @Test
    fun testPreHydrationClearSessionLensStateDoesNotClobberPersistedSettings() = runBlocking {
        val prefs = context.getSharedPreferences(PreferenceKeys.LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(PreferenceKeys.KEY_ONBOARDING_COMPLETED, true)
            .putString(PreferenceKeys.KEY_TARGET_LOG, "S-Log3")
            .putString(PreferenceKeys.KEY_SELECTED_LENS_SENSOR_ID, "0_24mm")
            .putInt(PreferenceKeys.KEY_LENS_FACING, 0)
            .commit()

        // Simulate MainApplication.onCreate() calling clearSessionLensState() immediately on startup
        // before async DataStore hydration completes
        val repo = SettingsRepository.getInstance(context)
        repo.clearSessionLensState()

        repo.awaitHydrated()
        repo.awaitPersisted()

        // Persisted settings survive AND session lens state is cleared
        assertTrue(repo.current().onboardingCompleted)
        assertEquals("S-Log3", repo.current().targetLog)
        assertNull(repo.current().selectedLensSensorId)
        assertNull(repo.current().lensFacing)

        // Re-verify from disk by closing store without deleting .preferences_pb and reopening
        DarkbagDataStoreProvider.resetForTest(context, deleteDataStoreFile = false)
        val reopened = SettingsRepository.getInstance(context).apply { awaitHydrated() }
        assertTrue(reopened.current().onboardingCompleted)
        assertEquals("S-Log3", reopened.current().targetLog)
        assertNull(reopened.current().selectedLensSensorId)
        assertNull(reopened.current().lensFacing)
        assertFalse(reopened.hasExplicitKey(PreferenceKeys.SELECTED_LENS_SENSOR_ID))
    }

    @Test
    fun testConcurrentWritersKeepRepositoryFlowConsistentWithStoreSnapshot() = runBlocking {
        val repo = SettingsRepository.getInstance(context)
        repo.awaitHydrated()

        val jobs = (1..25).map { idx ->
            launch(Dispatchers.Default) {
                repo.setHdrBurstCount((idx % 6) + 3)
                repo.setFlashEnabled(idx % 2 == 0)
            }
        }
        jobs.joinAll()
        repo.awaitPersisted()

        // After all concurrent writers settle, SettingsRepository.current() MUST match persisted DataStore state
        val rawBurst = repo.rawSnapshot()[PreferenceKeys.HDR_BURST_COUNT]
        val rawFlash = repo.rawSnapshot()[PreferenceKeys.FLASH_MODE]
        assertEquals(rawBurst, repo.current().hdrBurstCount)
        assertEquals(rawFlash, repo.current().flashEnabled)
    }

    @Test
    fun testAtomicMigrationOfCanonicalTypedLegacyKeys() = runBlocking {
        val prefs = context.getSharedPreferences(PreferenceKeys.LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(PreferenceKeys.KEY_FLASH_MODE, true)
            .putInt(PreferenceKeys.KEY_RAW_OUTPUT_TYPE, 1)
            .putString(PreferenceKeys.KEY_DEFAULT_FOCAL_1X, "35mm")
            .putInt(PreferenceKeys.KEY_HDR_BURST_COUNT, 6)
            .commit()

        val repo = SettingsRepository.getInstance(context).apply { awaitHydrated() }
        assertTrue(repo.current().flashEnabled)
        assertEquals("on", repo.current().flashMode)
        assertEquals(1, repo.current().rawOutputFormatIndex)
        assertEquals("35mm", repo.current().defaultFocal1xName)
        assertEquals(35f, repo.current().defaultFocal1x, 0.01f)
        assertEquals(6, repo.current().hdrBurstCount)
    }
}
