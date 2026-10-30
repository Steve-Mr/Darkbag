package top.maary.darkbag.utils

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.maary.darkbag.fragments.SettingsFragment
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class LutManagerLogBindingTest {

    private lateinit var context: Context
    private lateinit var lutManager: LutManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        lutManager = LutManager(context)
        lutManager.lutDir.listFiles()?.forEach { it.delete() }
        val prefs = context.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    @After
    fun tearDown() {
        lutManager.lutDir.listFiles()?.forEach { it.delete() }
        val prefs = context.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    @Test
    fun testLutFilteringForSpecificLogWithStrategyA() {
        // Create 4 dummy LUT files
        val fLogFile = File(lutManager.lutDir, "Fuji_Classic.cube").apply { writeText("LUT_3D_SIZE 2\n0 0 0\n1 1 1") }
        val sLogFile = File(lutManager.lutDir, "Sony_Cinema.cube").apply { writeText("LUT_3D_SIZE 2\n0 0 0\n1 1 1") }
        val universalFile = File(lutManager.lutDir, "Universal_Retro.cube").apply { writeText("LUT_3D_SIZE 2\n0 0 0\n1 1 1") }
        val rec709File = File(lutManager.lutDir, "Rec709_Warm.cube").apply { writeText("LUT_3D_SIZE 2\n0 0 0\n1 1 1") }

        lutManager.setAssociatedLog(fLogFile.name, "F-Log2")
        lutManager.setAssociatedLog(sLogFile.name, "S-Log3")
        lutManager.setAssociatedLog(universalFile.name, LutManager.LOG_UNIVERSAL)
        lutManager.setAssociatedLog(rec709File.name, LutManager.LOG_REC709)

        // When user selects F-Log2:
        // Strategy A: Specific (Fuji_Classic) first, followed by Universal (Universal_Retro).
        // S-Log3 and Rec.709 should NOT be in the list!
        val fLogItems = lutManager.getLutsForLog("F-Log2")
        assertEquals(2, fLogItems.size)
        assertEquals("Fuji_Classic.cube", fLogItems[0].name)
        assertFalse(fLogItems[0].isUniversal)
        assertEquals("Universal_Retro.cube", fLogItems[1].name)
        assertTrue(fLogItems[1].isUniversal)

        // When user selects S-Log3:
        val sLogItems = lutManager.getLutsForLog("S-Log3")
        assertEquals(2, sLogItems.size)
        assertEquals("Sony_Cinema.cube", sLogItems[0].name)
        assertEquals("Universal_Retro.cube", sLogItems[1].name)

        // When user selects None (Standard capture):
        // Strategy A: Rec.709 first, followed continuously by Universal
        val noneLogItems = lutManager.getLutsForLog("None")
        assertEquals(2, noneLogItems.size)
        assertEquals("Rec709_Warm.cube", noneLogItems[0].name)
        assertTrue(noneLogItems[0].isRec709)
        assertEquals("Universal_Retro.cube", noneLogItems[1].name)
        assertTrue(noneLogItems[1].isUniversal)
    }

    @Test
    fun testToTargetLogPreference() {
        assertEquals("None", lutManager.toTargetLogPreference("Rec.709"))
        assertEquals("None", lutManager.toTargetLogPreference(LutManager.LOG_REC709))
        assertEquals("None", lutManager.toTargetLogPreference(LutManager.LOG_UNIVERSAL))
        assertEquals("None", lutManager.toTargetLogPreference(null))
        assertEquals("None", lutManager.toTargetLogPreference("None"))
        assertEquals("F-Log2", lutManager.toTargetLogPreference("F-Log2"))
        assertEquals("S-Log3", lutManager.toTargetLogPreference("S-Log3"))
        assertEquals("Arri LogC3", lutManager.toTargetLogPreference("Arri LogC3"))
    }

    @Test
    fun testCompatibilityCheck() {
        val fLogFile = "Fuji_ProNeg.cube"
        val universalFile = "Universal_Bw.cube"
        val rec709File = "Rec709_Clean.cube"

        lutManager.setAssociatedLog(fLogFile, "F-Log2")
        lutManager.setAssociatedLog(universalFile, LutManager.LOG_UNIVERSAL)
        lutManager.setAssociatedLog(rec709File, LutManager.LOG_REC709)

        // None LUT is always compatible
        assertTrue(lutManager.isLutCompatibleWithLog(null, "F-Log2"))
        assertTrue(lutManager.isLutCompatibleWithLog("None", "F-Log2"))

        // Universal LUT is compatible everywhere
        assertTrue(lutManager.isLutCompatibleWithLog(universalFile, "F-Log2"))
        assertTrue(lutManager.isLutCompatibleWithLog(universalFile, "Apple Log"))
        assertTrue(lutManager.isLutCompatibleWithLog(universalFile, "None"))

        // F-Log2 exclusive LUT is only compatible with F-Log2
        assertTrue(lutManager.isLutCompatibleWithLog(fLogFile, "F-Log2"))
        assertFalse(lutManager.isLutCompatibleWithLog(fLogFile, "S-Log3"))
        assertFalse(lutManager.isLutCompatibleWithLog(fLogFile, "None"))

        // Rec709 LUT is compatible with None and Rec.709
        assertTrue(lutManager.isLutCompatibleWithLog(rec709File, "None"))
        assertTrue(lutManager.isLutCompatibleWithLog(rec709File, "Rec.709"))
        assertFalse(lutManager.isLutCompatibleWithLog(rec709File, "F-Log2"))
    }

    @Test
    fun testLegacyUnassignedLutDefaultsToUniversal() {
        val legacyFile = File(lutManager.lutDir, "Legacy_Filter.cube").apply { writeText("LUT_3D_SIZE 2\n0 0 0\n1 1 1") }
        // No association set
        assertNull(lutManager.getAssociatedLog(legacyFile.name))

        val allItems = lutManager.getAllLutItems()
        val legacyItem = allItems.find { it.name == "Legacy_Filter.cube" }!!
        assertTrue(legacyItem.isUniversal)

        // Should appear in any Log's available list as universal fallback
        val fLogList = lutManager.getLutsForLog("F-Log2")
        assertTrue(fLogList.any { it.name == "Legacy_Filter.cube" && it.isUniversal })

        val noneLogList = lutManager.getLutsForLog("None")
        assertTrue(noneLogList.any { it.name == "Legacy_Filter.cube" && it.isUniversal })
    }
}
