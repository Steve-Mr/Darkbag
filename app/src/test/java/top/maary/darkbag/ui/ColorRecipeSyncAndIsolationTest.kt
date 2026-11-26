package top.maary.darkbag.ui

import android.content.Context
import android.view.LayoutInflater
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.button.MaterialButton
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
import top.maary.darkbag.R
import top.maary.darkbag.databinding.CameraUiContainerBinding
import top.maary.darkbag.repository.PreferenceKeys
import top.maary.darkbag.repository.SettingsRepository
import top.maary.darkbag.utils.LutManager
import java.io.File
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ColorRecipeSyncAndIsolationTest {

    private lateinit var themedContext: Context
    private lateinit var lutManager: LutManager

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        themedContext = android.view.ContextThemeWrapper(app, R.style.AppTheme)
        lutManager = LutManager(themedContext)
        lutManager.lutDir.listFiles()?.forEach { it.delete() }
        SettingsRepository.resetForTest(themedContext)
        SettingsRepository.getInstance(themedContext).awaitHydratedBlocking()
    }

    @After
    fun tearDown() {
        lutManager.lutDir.listFiles()?.forEach { it.delete() }
        SettingsRepository.resetForTest(themedContext)
    }

    @Test
    fun testBindRecipeRow_InactiveStateReflectsOffAndPorcelainStyling() {
        val root = FrameLayout(themedContext)
        val binding = CameraUiContainerBinding.inflate(LayoutInflater.from(themedContext), root)
        val density = themedContext.resources.displayMetrics.density

        // When both Log and LUT are None or null
        CameraBentoDeckView.bindRecipeRow(binding, "None", null)

        val logBtn = binding.bentoLogButton!!
        val lutBtn = binding.lutSwitcherButton

        assertEquals("Log: Off", logBtn.text.toString())
        assertEquals("LUT: Off", lutBtn.text.toString())

        // Inactive styling: 14dp radius, strokeWidth = 0
        assertEquals((14f * density).roundToInt(), logBtn.cornerRadius)
        assertEquals(0, logBtn.strokeWidth)
        assertEquals((14f * density).roundToInt(), lutBtn.cornerRadius)
        assertEquals(0, lutBtn.strokeWidth)

        // When activeLutName is explicitly string "None"
        CameraBentoDeckView.bindRecipeRow(binding, "None", "None")
        assertEquals("Log: Off", logBtn.text.toString())
        assertEquals("LUT: Off", lutBtn.text.toString())
        assertEquals(0, lutBtn.strokeWidth)
    }

    @Test
    fun testBindRecipeRow_ActiveStateReflectsLabelsAndSquircleMorph() {
        val root = FrameLayout(themedContext)
        val binding = CameraUiContainerBinding.inflate(LayoutInflater.from(themedContext), root)
        val density = themedContext.resources.displayMetrics.density

        // When Log is F-Log2 and LUT is ClassicChrome.cube
        CameraBentoDeckView.bindRecipeRow(binding, "F-Log2", "ClassicChrome.cube")

        val logBtn = binding.bentoLogButton!!
        val lutBtn = binding.lutSwitcherButton

        assertEquals("F-Log2", logBtn.text.toString())
        assertEquals("ClassicChrome", lutBtn.text.toString())

        // Active styling: 12dp squircle morph, 1.5dp strokeWidth
        assertEquals((12f * density).roundToInt(), logBtn.cornerRadius)
        assertEquals((1.5f * density).roundToInt(), logBtn.strokeWidth)
        assertEquals((12f * density).roundToInt(), lutBtn.cornerRadius)
        assertEquals((1.5f * density).roundToInt(), lutBtn.strokeWidth)
    }

    @Test
    fun testColorRecipeBottomSheet_DoesNotPolluteOrReadCameraPrefs() {
        // Set camera global preferences to F-Log2 and Fuji_ProNeg.cube
        val repo = SettingsRepository.getInstance(themedContext)
        repo.edit {
            this[PreferenceKeys.TARGET_LOG] = "F-Log2"
            this[PreferenceKeys.ACTIVE_LUT] = "Fuji_ProNeg.cube"
        }

        // Viewer/Playground opens sheet with initialLog = "None" and initialLut = null
        var selectedLogResult: String? = null
        var selectedLutResult: String? = null

        val dialog = ColorRecipeBottomSheet.show(
            context = themedContext,
            lutManager = lutManager,
            initialLog = "None",
            initialLut = null
        ) { log, lut ->
            selectedLogResult = log
            selectedLutResult = lut
        }

        // Camera preferences must remain completely untouched!
        assertEquals("F-Log2", repo.current().targetLog)
        assertEquals("Fuji_ProNeg.cube", repo.current().activeLut)

        // Dismiss sheet
        dialog.dismiss()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()

        // When dismissed without user modification, callback is NOT invoked, preventing false edit state in viewer
        assertNull(selectedLogResult)
        assertNull(selectedLutResult)

        // Camera preferences are still strictly untouched after dismissal!
        assertEquals("F-Log2", repo.current().targetLog)
        assertEquals("Fuji_ProNeg.cube", repo.current().activeLut)
    }

    @Test
    fun testColorRecipeBottomSheet_SelectingLogNoneResetsIncompatibleLut() {
        // Create an F-Log2 exclusive LUT
        val fLogFile = File(lutManager.lutDir, "Fuji_Classic.cube").apply { writeText("LUT_3D_SIZE 2\n0 0 0\n1 1 1") }
        lutManager.setAssociatedLog(fLogFile.name, "F-Log2")

        var lastLog: String? = "F-Log2"
        var lastLut: String? = "Fuji_Classic.cube"

        val dialog = ColorRecipeBottomSheet.show(
            context = themedContext,
            lutManager = lutManager,
            initialLog = "F-Log2",
            initialLut = "Fuji_Classic.cube"
        ) { log, lut ->
            lastLog = log
            lastLut = lut
        }

        // Find the "None" Log button inside the dialog's content view
        val contentView = dialog.findViewById<android.view.ViewGroup>(android.R.id.content)
            ?: dialog.window?.decorView as android.view.ViewGroup

        val buttons = mutableListOf<MaterialButton>()
        fun findButtons(view: android.view.View) {
            if (view is MaterialButton) buttons.add(view)
            if (view is android.view.ViewGroup) {
                for (i in 0 until view.childCount) {
                    findButtons(view.getChildAt(i))
                }
            }
        }
        findButtons(contentView)

        val noneLogBtn = buttons.firstOrNull { it.tag == "None" || (it.text.toString() == "None" && it.tag != null) }
        org.junit.Assert.assertNotNull("Log 'None' button should exist", noneLogBtn)

        noneLogBtn!!.performClick()

        // Clicking Log -> None MUST reset incompatible LUT to null
        assertEquals("None", lastLog)
        assertNull(lastLut)

        dialog.dismiss()
        assertEquals("None", lastLog)
        assertNull(lastLut)
    }

    @Test
    fun testColorRecipeBottomSheet_ResetButtonResetsAllToNone() {
        var lastLog: String? = "F-Log2"
        var lastLut: String? = "SomeLut.cube"

        val dialog = ColorRecipeBottomSheet.show(
            context = themedContext,
            lutManager = lutManager,
            initialLog = "F-Log2",
            initialLut = "SomeLut.cube"
        ) { log, lut ->
            lastLog = log
            lastLut = lut
        }

        val contentView = dialog.findViewById<android.view.ViewGroup>(android.R.id.content)
            ?: dialog.window?.decorView as android.view.ViewGroup

        val buttons = mutableListOf<MaterialButton>()
        fun findButtons(view: android.view.View) {
            if (view is MaterialButton) buttons.add(view)
            if (view is android.view.ViewGroup) {
                for (i in 0 until view.childCount) {
                    findButtons(view.getChildAt(i))
                }
            }
        }
        findButtons(contentView)

        val resetBtn = buttons.firstOrNull { it.text.toString().equals("Reset", ignoreCase = true) }
        org.junit.Assert.assertNotNull("Reset button should exist", resetBtn)

        resetBtn!!.performClick()

        assertEquals("None", lastLog)
        assertNull(lastLut)

        dialog.dismiss()
        assertEquals("None", lastLog)
        assertNull(lastLut)
    }

    @Test
    fun testColorRecipeBottomSheet_DismissWithoutModificationDoesNotEmitCallback() {
        var callbackInvocationCount = 0

        val dialog = ColorRecipeBottomSheet.show(
            context = themedContext,
            lutManager = lutManager,
            initialLog = "F-Log2",
            initialLut = "SomeLut.cube"
        ) { _, _ ->
            callbackInvocationCount++
        }

        // User does not click anything and simply dismisses the sheet
        dialog.dismiss()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()

        // Callback must NOT be invoked when user only viewed without modifying
        assertEquals(0, callbackInvocationCount)
    }
}
