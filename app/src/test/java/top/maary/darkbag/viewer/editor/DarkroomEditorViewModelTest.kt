package top.maary.darkbag.viewer.editor

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.maary.darkbag.models.BasicAdjustments
import top.maary.darkbag.models.EditConfig
import top.maary.darkbag.models.ImageGroup

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class DarkroomEditorViewModelTest {

    private lateinit var context: Application
    private lateinit var viewModel: DarkroomEditorViewModel

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        viewModel = DarkroomEditorViewModel(context)
    }

    @Test
    fun testDefaultState() {
        val state = viewModel.uiState.value
        assertFalse(state.isEditing)
        assertFalse(state.isAdjusted)
        assertEquals(0, state.selectedTab)
        assertEquals(0, state.selectedHalfFrameIndex)
        assertFalse(state.isHalfFrame)
        assertEquals("Exposure", state.slider1Label)
        assertEquals(0f, state.slider1Value, 0.001f)
        assertEquals("Contrast", state.slider2Label)
        assertEquals(0f, state.slider2Value, 0.001f)
        assertTrue(state.isSlider2Visible)
    }

    @Test
    fun testSetActiveGroup_singleImage() {
        val config = EditConfig(
            exposure = 1.25f,
            contrast = 0.35f,
            lut = "Kodak_Gold.cube",
            log = "D-Log"
        )
        val group = ImageGroup(
            baseName = "DARK_SINGLE_001",
            editConfig = config
        )

        viewModel.processIntent(DarkroomEditorUserIntent.SetActiveGroup(group))

        val state = viewModel.uiState.value
        assertFalse(state.isHalfFrame)
        assertEquals(1.25f, state.slider1Value, 0.001f)
        assertEquals(0.35f, state.slider2Value, 0.001f)
        assertEquals("Kodak_Gold", state.currentLutName)
        assertEquals("D-Log", state.currentLogName)
        assertEquals("1.25 EV", state.slider1DisplayValue)
        assertEquals("0.35", state.slider2DisplayValue)
    }

    @Test
    fun testEnterAndExitEditMode_applyFalse_restoresOriginalConfig() {
        val group = ImageGroup(baseName = "IMG_001")
        viewModel.processIntent(DarkroomEditorUserIntent.SetActiveGroup(group))

        viewModel.processIntent(DarkroomEditorUserIntent.EnterEditMode(group))
        assertTrue(viewModel.uiState.value.isEditing)

        viewModel.processIntent(DarkroomEditorUserIntent.UpdateSlider1(2.0f))
        assertTrue(viewModel.uiState.value.isAdjusted)
        assertEquals(2.0f, viewModel.uiState.value.slider1Value, 0.001f)

        viewModel.processIntent(DarkroomEditorUserIntent.ExitEditMode(apply = false))
        val stateAfterExit = viewModel.uiState.value
        assertFalse(stateAfterExit.isEditing)
        assertFalse(stateAfterExit.isAdjusted)
        assertEquals(0f, stateAfterExit.slider1Value, 0.001f)
    }

    @Test
    fun testEnterAndExitEditMode_applyTrue_preservesAdjustments() {
        val group = ImageGroup(baseName = "IMG_001")
        viewModel.processIntent(DarkroomEditorUserIntent.SetActiveGroup(group))

        viewModel.processIntent(DarkroomEditorUserIntent.EnterEditMode(group))
        viewModel.processIntent(DarkroomEditorUserIntent.UpdateSlider1(1.5f))
        viewModel.processIntent(DarkroomEditorUserIntent.ExitEditMode(apply = true))

        val state = viewModel.uiState.value
        assertFalse(state.isEditing)
        assertTrue(state.isAdjusted)
        assertEquals(1.5f, state.slider1Value, 0.001f)
    }

    @Test
    fun testSelectTab_updatesLabelsAndBounds() {
        // Tab 0: Light
        viewModel.processIntent(DarkroomEditorUserIntent.SelectTab(0))
        var state = viewModel.uiState.value
        assertEquals("Exposure", state.slider1Label)
        assertEquals(DarkroomEditorViewModel.EXPOSURE_MIN, state.slider1Min, 0.001f)
        assertEquals(DarkroomEditorViewModel.EXPOSURE_MAX, state.slider1Max, 0.001f)
        assertEquals("Contrast", state.slider2Label)
        assertTrue(state.isSlider2Visible)

        // Tab 1: Range
        viewModel.processIntent(DarkroomEditorUserIntent.SelectTab(1))
        state = viewModel.uiState.value
        assertEquals("Highlights", state.slider1Label)
        assertEquals("Shadows", state.slider2Label)
        assertTrue(state.isSlider2Visible)

        // Tab 2: Tone
        viewModel.processIntent(DarkroomEditorUserIntent.SelectTab(2))
        state = viewModel.uiState.value
        assertEquals("Whites", state.slider1Label)
        assertEquals("Blacks", state.slider2Label)
        assertTrue(state.isSlider2Visible)

        // Tab 3: Color
        viewModel.processIntent(DarkroomEditorUserIntent.SelectTab(3))
        state = viewModel.uiState.value
        assertEquals("Saturation", state.slider1Label)
        assertFalse(state.isSlider2Visible)
    }

    @Test
    fun testHalfFrameAdjustmentIsolation() {
        val hfGroup = ImageGroup(
            baseName = "HF_001",
            dngUri1 = android.net.Uri.parse("file:///dummy1.dng"),
            dngUri2 = android.net.Uri.parse("file:///dummy2.dng"),
            hfLayout = "SBS",
            editConfig = EditConfig(
                adjustments = listOf(
                    BasicAdjustments(exposure = 0.5f, contrast = 0.1f),
                    BasicAdjustments(exposure = -0.5f, contrast = -0.1f)
                )
            )
        )

        viewModel.processIntent(DarkroomEditorUserIntent.SetActiveGroup(hfGroup))
        assertTrue(viewModel.uiState.value.isHalfFrame)

        // Select Frame 1 (Index 0)
        viewModel.processIntent(DarkroomEditorUserIntent.SelectHalfFrameIndex(0))
        assertEquals(0.5f, viewModel.uiState.value.slider1Value, 0.001f)
        assertEquals(0.1f, viewModel.uiState.value.slider2Value, 0.001f)

        // Select Frame 2 (Index 1)
        viewModel.processIntent(DarkroomEditorUserIntent.SelectHalfFrameIndex(1))
        assertEquals(-0.5f, viewModel.uiState.value.slider1Value, 0.001f)
        assertEquals(-0.1f, viewModel.uiState.value.slider2Value, 0.001f)

        // Update Frame 2 exposure
        viewModel.processIntent(DarkroomEditorUserIntent.UpdateSlider1(-1.5f))
        assertEquals(-1.5f, viewModel.uiState.value.slider1Value, 0.001f)

        // Switch back to Frame 1 and ensure it remained 0.5f
        viewModel.processIntent(DarkroomEditorUserIntent.SelectHalfFrameIndex(0))
        assertEquals(0.5f, viewModel.uiState.value.slider1Value, 0.001f)

        val activeConfig = viewModel.uiState.value.activeConfig
        assertNotNull(activeConfig)
        assertEquals(0.5f, activeConfig?.adjustments?.get(0)?.exposure ?: 0f, 0.001f)
        assertEquals(-1.5f, activeConfig?.adjustments?.get(1)?.exposure ?: 0f, 0.001f)
    }

    @Test
    fun testToggleSwap_halfFrame() {
        val hfGroup = ImageGroup(
            baseName = "HF_001",
            dngUri1 = android.net.Uri.parse("file:///dummy1.dng"),
            dngUri2 = android.net.Uri.parse("file:///dummy2.dng"),
            editConfig = EditConfig(
                adjustments = listOf(
                    BasicAdjustments(exposure = 1.0f),
                    BasicAdjustments(exposure = -1.0f)
                )
            )
        )
        viewModel.processIntent(DarkroomEditorUserIntent.SetActiveGroup(hfGroup))

        assertFalse(viewModel.uiState.value.isSwapped)
        viewModel.processIntent(DarkroomEditorUserIntent.ToggleSwap)
        assertTrue(viewModel.uiState.value.isSwapped)
        assertTrue(viewModel.uiState.value.activeConfig?.isSwapped == true)
    }

    @Test
    fun testToggleTimestampAndSetFlare() {
        val group = ImageGroup(baseName = "IMG_001")
        viewModel.processIntent(DarkroomEditorUserIntent.SetActiveGroup(group))

        assertFalse(viewModel.uiState.value.showTimestamp)
        viewModel.processIntent(DarkroomEditorUserIntent.ToggleTimestamp)
        assertTrue(viewModel.uiState.value.showTimestamp)
        assertTrue(viewModel.uiState.value.activeConfig?.showTimestamp == true)

        assertEquals(-1, viewModel.uiState.value.flareType)
        viewModel.processIntent(DarkroomEditorUserIntent.SetFlareType(2))
        assertEquals(2, viewModel.uiState.value.flareType)
        assertEquals(2, viewModel.uiState.value.activeConfig?.flareType)
    }

    @Test
    fun testResetAdjustments() {
        val initialConfig = EditConfig(exposure = 0f, contrast = 0f)
        val group = ImageGroup(baseName = "IMG_001", editConfig = initialConfig)
        viewModel.processIntent(DarkroomEditorUserIntent.SetActiveGroup(group))

        viewModel.processIntent(DarkroomEditorUserIntent.UpdateSlider1(2.5f))
        viewModel.processIntent(DarkroomEditorUserIntent.SetLut("Custom.cube"))
        assertTrue(viewModel.uiState.value.isAdjusted)

        viewModel.processIntent(DarkroomEditorUserIntent.ResetAdjustments)
        val state = viewModel.uiState.value
        assertFalse(state.isAdjusted)
        assertFalse(state.isEditing)
        assertEquals(0f, state.slider1Value, 0.001f)
        assertEquals("None", state.currentLutName)
    }
}
