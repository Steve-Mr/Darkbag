package top.maary.darkbag.calibration

import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
import top.maary.darkbag.fragments.SettingsFragment

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class LensCalibrationStoreTest {

    private lateinit var context: Context
    private lateinit var store: LensCalibrationStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val prefs = context.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
        store = LensCalibrationStore(context)
    }

    @Test
    fun testSaveAndGetCalibration() {
        assertNull(store.getCalibration("2"))

        val calib = CustomLensCalibration(
            lensId = "2",
            focalLength = 2.1f,
            k1 = -0.065f,
            k2 = 0.001f,
            isEnabled = true
        )
        store.saveCalibration(calib)

        val retrieved = store.getCalibration("2")
        assertNotNull(retrieved)
        assertEquals("2", retrieved?.lensId)
        assertEquals(2.1f, retrieved?.focalLength ?: 0f, 0.001f)
        assertEquals(-0.065f, retrieved?.k1 ?: 0f, 0.0001f)
        assertEquals(0.001f, retrieved?.k2 ?: 0f, 0.0001f)
        assertTrue(retrieved?.isEnabled == true)
    }

    @Test
    fun testSetEnabledAndRemove() {
        val calib = CustomLensCalibration(
            lensId = "0",
            focalLength = 5.5f,
            k1 = 0.012f,
            isEnabled = true
        )
        store.saveCalibration(calib)

        store.setEnabled("0", false)
        assertFalse(store.getCalibration("0")?.isEnabled == true)

        store.removeCalibration("0")
        assertNull(store.getCalibration("0"))
    }

    @Test
    fun testGetAllCalibrations() {
        store.saveCalibration(CustomLensCalibration(lensId = "0", k1 = 0.01f))
        store.saveCalibration(CustomLensCalibration(lensId = "2", k1 = -0.05f))

        val all = store.getAllCalibrations()
        assertEquals(2, all.size)
        assertTrue(all.any { it.lensId == "0" })
        assertTrue(all.any { it.lensId == "2" })
    }
}
