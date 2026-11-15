package top.maary.darkbag.ui

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.maary.darkbag.R
import top.maary.darkbag.repository.DarkbagPreferences
import top.maary.darkbag.viewmodel.CameraUiTier

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BentoDeckRefactorTest {

    private lateinit var themedContext: Context

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        themedContext = android.view.ContextThemeWrapper(app, R.style.AppTheme)
    }

    @Test
    fun updateRowBAndRowCWeightsToggleBetweenFixedWidthAndWeightedExpansion() {
        val pill = View(themedContext).apply {
            layoutParams = LinearLayout.LayoutParams(50, LinearLayout.LayoutParams.MATCH_PARENT, 0f)
        }
        BentoDeckDimensions.updateRowBWeight(pill, fixedWidthPx = 120, expandFilmPills = true)
        val expandedB = pill.layoutParams as LinearLayout.LayoutParams
        assertEquals(0, expandedB.width)
        assertEquals(1f, expandedB.weight, 0.001f)

        BentoDeckDimensions.updateRowBWeight(pill, fixedWidthPx = 120, expandFilmPills = false)
        val fixedB = pill.layoutParams as LinearLayout.LayoutParams
        assertEquals(120, fixedB.width)
        assertEquals(0f, fixedB.weight, 0.001f)

        val sideBtn = View(themedContext).apply {
            layoutParams = LinearLayout.LayoutParams(104, LinearLayout.LayoutParams.MATCH_PARENT, 0f)
        }
        BentoDeckDimensions.updateRowCWeight(sideBtn, sideBtnWidthPx = 104, expandLensSideButtons = true)
        val expandedC = sideBtn.layoutParams as LinearLayout.LayoutParams
        assertEquals(0, expandedC.width)
        assertEquals(1f, expandedC.weight, 0.001f)

        BentoDeckDimensions.updateRowCWeight(sideBtn, sideBtnWidthPx = 104, expandLensSideButtons = false)
        val fixedC = sideBtn.layoutParams as LinearLayout.LayoutParams
        assertEquals(104, fixedC.width)
        assertEquals(0f, fixedC.weight, 0.001f)
    }

    @Test
    fun resolveVisibilitySpecHonorsPresetsAndCustomPreferences() {
        val prefs = DarkbagPreferences(
            enableManualControls = true,
            showLutSwitcher = false,
            showHdrPlusSwitch = true,
            showHdrUnderexposureButton = false,
            showModeSwitchButton = false,
            showLensControls = true,
            showCameraSwitchButton = true,
            showSettingsButton = false,
            expFocusPeaking = true
        )

        val ps = BentoDeckPreviewBinder.resolveVisibilitySpec(prefs, CameraUiTier.POINT_AND_SHOOT)
        assertFalse(ps.showProModule)
        assertFalse(ps.showFilmRow)
        assertTrue(ps.showLensRow)

        val film = BentoDeckPreviewBinder.resolveVisibilitySpec(prefs, CameraUiTier.FILM)
        assertFalse(film.showProModule)
        assertTrue(film.showLogAndLut)
        assertFalse(film.enableFocusPeaking)

        val pro = BentoDeckPreviewBinder.resolveVisibilitySpec(prefs, CameraUiTier.PRO)
        assertTrue(pro.showProModule)
        assertTrue(pro.enableFocusPeaking)

        val custom = BentoDeckPreviewBinder.resolveVisibilitySpec(prefs, CameraUiTier.CUSTOM)
        assertTrue(custom.showProModule)
        assertFalse(custom.showLogAndLut)
        assertTrue(custom.showHdrPlusPill)
        assertFalse(custom.showUnderexposure)
        assertFalse(custom.showModeSwitch)
        assertFalse(custom.showSettingsButton)
        assertTrue(custom.enableFocusPeaking)
    }

    @Test
    fun disableInteractivityForPreviewClearsFlagsRecursively() {
        val parent = FrameLayout(themedContext).apply {
            isClickable = true
            isLongClickable = true
            isFocusable = true
        }
        val child = View(themedContext).apply {
            isClickable = true
            isLongClickable = true
            isFocusable = true
        }
        parent.addView(child)

        BentoDeckPreviewBinder.disableInteractivityForPreview(parent)

        assertFalse(parent.isClickable)
        assertFalse(parent.isLongClickable)
        assertFalse(parent.isFocusable)
        assertFalse(child.isClickable)
        assertFalse(child.isLongClickable)
        assertFalse(child.isFocusable)
    }

    @Test
    fun formatCompactTierLabelProducesSingleOrTwoLineSpannable() {
        val single = BentoDeckStylingEngine.formatCompactTierLabel(themedContext, "PRO", isActive = true)
        assertEquals("PRO", single.toString())

        val twoLine = BentoDeckStylingEngine.formatCompactTierLabel(themedContext, "FILM", "LUT", isActive = false)
        assertEquals("FILM\nLUT", twoLine.toString())
    }
}
