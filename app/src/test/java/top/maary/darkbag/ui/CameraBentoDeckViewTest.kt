package top.maary.darkbag.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.button.MaterialButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CameraBentoDeckViewTest {

    private lateinit var themedContext: Context

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        themedContext = android.view.ContextThemeWrapper(app, top.maary.darkbag.R.style.AppTheme)
    }

    @Test
    fun testStyleModeSwitchButtonRestingAndActive() {
        val button = MaterialButton(themedContext)
        val density = themedContext.resources.displayMetrics.density

        // Normal mode (resting): cornerRadius 18dp (concentric outer corner block)
        CameraBentoDeckView.styleModeSwitchButton(button, isSpecialMode = false)
        assertEquals((18f * density).roundToInt(), button.cornerRadius)
        assertEquals(0, button.strokeWidth)

        // Special mode: cornerRadius 12dp (squircle morph with 1.5dp stroke)
        CameraBentoDeckView.styleModeSwitchButton(button, isSpecialMode = true)
        assertEquals((12f * density).roundToInt(), button.cornerRadius)
        assertEquals((1.5f * density).roundToInt(), button.strokeWidth)
    }

    @Test
    fun testStyleBentoLensButton() {
        val button = MaterialButton(themedContext)
        val density = themedContext.resources.displayMetrics.density

        // Inactive: cornerRadius 14dp resting
        CameraBentoDeckView.styleBentoLensButton(button, isActive = false, isDigitalCrop = false)
        assertEquals((14f * density).roundToInt(), button.cornerRadius)
        assertEquals(0, button.strokeWidth)

        // Active physical: cornerRadius 12dp squircle morph
        CameraBentoDeckView.styleBentoLensButton(button, isActive = true, isDigitalCrop = false)
        assertEquals((12f * density).roundToInt(), button.cornerRadius)
        assertEquals((1.5f * density).roundToInt(), button.strokeWidth)

        // Active digital crop: cornerRadius 12dp squircle morph
        CameraBentoDeckView.styleBentoLensButton(button, isActive = true, isDigitalCrop = true)
        assertEquals((12f * density).roundToInt(), button.cornerRadius)
        assertEquals((1.5f * density).roundToInt(), button.strokeWidth)
    }

    @Test
    fun testStyleBentoToggleTile() {
        val button = MaterialButton(themedContext)
        val density = themedContext.resources.displayMetrics.density

        // Inactive: cornerRadius 14dp resting
        CameraBentoDeckView.styleBentoToggleTile(button, isActive = false)
        assertEquals((14f * density).roundToInt(), button.cornerRadius)
        assertEquals(0, button.strokeWidth)

        // Active: cornerRadius 12dp squircle morph
        CameraBentoDeckView.styleBentoToggleTile(button, isActive = true)
        assertEquals((12f * density).roundToInt(), button.cornerRadius)
    }

    @Test
    fun testFormatRecipeLabel() {
        assertEquals("ClassicChrome", CameraBentoDeckView.formatRecipeLabel("ClassicChrome.cube"))
        assertEquals("KodakPortra", CameraBentoDeckView.formatRecipeLabel("KodakPortra"))
        assertEquals("None", CameraBentoDeckView.formatRecipeLabel(null))
        assertEquals("None", CameraBentoDeckView.formatRecipeLabel("None"))
    }
}
