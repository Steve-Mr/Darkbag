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

    @Test
    fun testStyleBentoProPill() {
        val button = MaterialButton(themedContext)
        val density = themedContext.resources.displayMetrics.density

        // Inactive (Auto parameter, dial closed): 14dp radius, 1dp stroke
        CameraBentoDeckView.styleBentoProPill(button, isTabActive = false, isValueManual = false)
        assertEquals((14f * density).roundToInt(), button.cornerRadius)
        assertEquals((1f * density).roundToInt(), button.strokeWidth)

        // Active / Tab Selected (dial open): 10dp squircle radius, 1.5dp stroke
        CameraBentoDeckView.styleBentoProPill(button, isTabActive = true, isValueManual = false)
        assertEquals((10f * density).roundToInt(), button.cornerRadius)
        assertEquals((1.5f * density).roundToInt(), button.strokeWidth)

        // Active takes precedence even if isValueManual = true
        CameraBentoDeckView.styleBentoProPill(button, isTabActive = true, isValueManual = true)
        assertEquals((10f * density).roundToInt(), button.cornerRadius)
        assertEquals((1.5f * density).roundToInt(), button.strokeWidth)

        // Manual Override (locked to manual, dial closed): 12dp radius, 1.5dp stroke
        CameraBentoDeckView.styleBentoProPill(button, isTabActive = false, isValueManual = true)
        assertEquals((12f * density).roundToInt(), button.cornerRadius)
        assertEquals((1.5f * density).roundToInt(), button.strokeWidth)
    }

    @Test
    fun testStyleBentoProLink() {
        val button = MaterialButton(themedContext)
        val density = themedContext.resources.displayMetrics.density

        // Unlinked: 14dp radius, 1dp stroke
        CameraBentoDeckView.styleBentoProLink(button, isLinked = false)
        assertEquals((14f * density).roundToInt(), button.cornerRadius)
        assertEquals((1f * density).roundToInt(), button.strokeWidth)

        // Linked: 14dp radius, 1.5dp stroke
        CameraBentoDeckView.styleBentoProLink(button, isLinked = true)
        assertEquals((14f * density).roundToInt(), button.cornerRadius)
        assertEquals((1.5f * density).roundToInt(), button.strokeWidth)
    }

    @Test
    fun testFormatProTelemetry() {
        val telemetry = CameraBentoDeckView.formatProTelemetry("ISO", "100", isTabActive = false, isValueManual = false, context = themedContext)
        assertEquals("ISO\n100", telemetry.toString())

        val spanned = telemetry as android.text.Spanned
        val spans = spanned.getSpans(0, spanned.length, Any::class.java)
        org.junit.Assert.assertTrue(spans.isNotEmpty())

        val sizeSpans = spanned.getSpans(0, spanned.length, android.text.style.AbsoluteSizeSpan::class.java)
        assertEquals(2, sizeSpans.size)

        val styleSpans = spanned.getSpans(0, spanned.length, android.text.style.StyleSpan::class.java)
        assertEquals(2, styleSpans.size)
    }
}
