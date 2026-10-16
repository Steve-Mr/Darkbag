package top.maary.darkbag.viewmodel

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.maary.darkbag.modes.CaptureMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class CameraViewModelTest {

    private lateinit var viewModel: CameraViewModel

    @Before
    fun setUp() {
        viewModel = CameraViewModel()
    }

    @Test
    fun testDefaultState() {
        val state = viewModel.uiState.value
        assertEquals(CaptureMode.NORMAL, state.currentMode)
        assertEquals("0", state.activeLensId)
        assertEquals(1.0f, state.zoomRatio, 0.001f)
        assertFalse(state.isProcessing)
        assertEquals(0, state.pendingTasksCount)
        assertEquals(0, state.pendingForegroundTasksCount)
        assertEquals(0, state.halfFrameStep)
        assertFalse(state.isBurstActive)
        assertTrue(state.isHdrPlusEnabled)
        assertEquals(0f, state.shutterDotRotation, 0.001f)
        assertEquals(CameraUiTier.FILM, state.uiTier)
        assertEquals("None", state.activeLogName)
        assertEquals("None", state.activeLutName)
        assertEquals("Dynamic (Experimental)", state.underexposureMode)
        assertFalse(state.isFlashEnabled)
        assertFalse(state.isMotionPhotoEnabled)
        assertNull(state.activeManualTab)
        assertFalse(state.isManualFocus)
        assertFalse(state.isManualIso)
        assertFalse(state.isManualShutter)
        assertTrue(state.isProLinked)
        assertFalse(state.showDetailedTelemetry)
        assertTrue(state.showFilmRecipeRow)
    }

    @Test
    fun testProcessIntent_ChangeMode() {
        viewModel.setMode(CaptureMode.HALF_FRAME_SBS)
        assertEquals(CaptureMode.HALF_FRAME_SBS, viewModel.uiState.value.currentMode)
        assertTrue(viewModel.uiState.value.isHalfFrameActive)
        assertFalse(viewModel.uiState.value.isMultiCameraActive)

        viewModel.setMode(CaptureMode.MULTI_CAMERA)
        assertEquals(CaptureMode.MULTI_CAMERA, viewModel.uiState.value.currentMode)
        assertFalse(viewModel.uiState.value.isHalfFrameActive)
        assertTrue(viewModel.uiState.value.isMultiCameraActive)
    }

    @Test
    fun testProcessIntent_TaskCountsAndProcessingState() {
        // Foreground tasks active -> isProcessing should be true
        viewModel.setTaskCounts(totalCount = 3, foregroundCount = 1)
        assertEquals(3, viewModel.uiState.value.pendingTasksCount)
        assertEquals(1, viewModel.uiState.value.pendingForegroundTasksCount)
        assertTrue(viewModel.uiState.value.isProcessing)

        // Foreground tasks complete, background DNG ongoing -> isProcessing false
        viewModel.setTaskCounts(totalCount = 2, foregroundCount = 0)
        assertEquals(2, viewModel.uiState.value.pendingTasksCount)
        assertEquals(0, viewModel.uiState.value.pendingForegroundTasksCount)
        assertFalse(viewModel.uiState.value.isProcessing)
    }

    @Test
    fun testProcessIntent_LensSwitchAndZoom() {
        viewModel.setLensId("2")
        viewModel.setZoomRatio(2.0f)
        viewModel.setSwitchingLens(true)

        val state = viewModel.uiState.value
        assertEquals("2", state.activeLensId)
        assertEquals(2.0f, state.zoomRatio, 0.001f)
        assertTrue(state.isSwitchingLens)
    }

    @Test
    fun testProcessIntent_HalfFrameStepAndRotation() {
        viewModel.setHalfFrameStep(1)
        viewModel.setShutterDotRotation(90f)

        val state = viewModel.uiState.value
        assertEquals(1, state.halfFrameStep)
        assertEquals(90f, state.shutterDotRotation, 0.001f)
    }

    @Test
    fun testEmitEffect_ShutterBlackout() = runBlocking {
        viewModel.emitEffect(CameraEffect.ShutterBlackout)
        val effect = viewModel.effects.first()
        assertTrue(effect is CameraEffect.ShutterBlackout)
    }

    @Test
    fun testEmitEffect_ShowToast() = runBlocking {
        viewModel.emitEffect(CameraEffect.ShowToast("Lens switched"))
        val effect = viewModel.effects.first()
        assertTrue(effect is CameraEffect.ShowToast)
        assertEquals("Lens switched", (effect as CameraEffect.ShowToast).message)
    }

    @Test
    fun testProcessIntent_SetBurstActive_EmitsResetShutterUiWhenFalse() = runBlocking {
        viewModel.setBurstActive(true)
        assertTrue(viewModel.uiState.value.isBurstActive)

        viewModel.setBurstActive(false)
        assertFalse(viewModel.uiState.value.isBurstActive)
        val effect = viewModel.effects.first()
        assertTrue(effect is CameraEffect.ResetShutterUi)
    }

    @Test
    fun testProcessIntent_SetBurstActive_DoesNotEmitResetShutterUiWhenAlreadyFalse() = runBlocking {
        assertFalse(viewModel.uiState.value.isBurstActive)
        viewModel.setBurstActive(false)
        assertFalse(viewModel.uiState.value.isBurstActive)
        // Ensure no effect is emitted when already inactive
        val channelEmpty = kotlinx.coroutines.withTimeoutOrNull(200) {
            viewModel.effects.first()
        } == null
        assertTrue(channelEmpty)
    }

    @Test
    fun testCameraUiTier_NextAndFromKey() {
        assertEquals(CameraUiTier.FILM, CameraUiTier.POINT_AND_SHOOT.next())
        assertEquals(CameraUiTier.PRO, CameraUiTier.FILM.next())
        assertEquals(CameraUiTier.POINT_AND_SHOOT, CameraUiTier.PRO.next())

        assertEquals(CameraUiTier.POINT_AND_SHOOT, CameraUiTier.fromKey("PS"))
        assertEquals(CameraUiTier.POINT_AND_SHOOT, CameraUiTier.fromKey("ps"))
        assertEquals(CameraUiTier.FILM, CameraUiTier.fromKey("FILM"))
        assertEquals(CameraUiTier.FILM, CameraUiTier.fromKey("film"))
        assertEquals(CameraUiTier.PRO, CameraUiTier.fromKey("PRO"))
        assertEquals(CameraUiTier.PRO, CameraUiTier.fromKey("pro"))
        assertEquals(CameraUiTier.FILM, CameraUiTier.fromKey(null))
        assertEquals(CameraUiTier.FILM, CameraUiTier.fromKey("unknown"))
    }

    @Test
    fun testUiTierCycling() {
        assertEquals(CameraUiTier.FILM, viewModel.uiState.value.uiTier)

        viewModel.cycleUiTier()
        assertEquals(CameraUiTier.PRO, viewModel.uiState.value.uiTier)

        viewModel.cycleUiTier()
        assertEquals(CameraUiTier.POINT_AND_SHOOT, viewModel.uiState.value.uiTier)

        viewModel.cycleUiTier()
        assertEquals(CameraUiTier.FILM, viewModel.uiState.value.uiTier)
    }

    @Test
    fun testUiTierComputedProperties() {
        // POINT_AND_SHOOT
        viewModel.setUiTier(CameraUiTier.POINT_AND_SHOOT)
        assertFalse(viewModel.uiState.value.showDetailedTelemetry)
        assertFalse(viewModel.uiState.value.showFilmRecipeRow)

        // FILM
        viewModel.setUiTier(CameraUiTier.FILM)
        assertFalse(viewModel.uiState.value.showDetailedTelemetry)
        assertTrue(viewModel.uiState.value.showFilmRecipeRow)

        // PRO
        viewModel.setUiTier(CameraUiTier.PRO)
        assertTrue(viewModel.uiState.value.showDetailedTelemetry)
        assertTrue(viewModel.uiState.value.showFilmRecipeRow)
    }

    @Test
    fun testSwitchAwayFromProClearsActiveManualTab() {
        viewModel.setUiTier(CameraUiTier.PRO)
        viewModel.setActiveManualTab("focus")
        assertEquals("focus", viewModel.uiState.value.activeManualTab)

        // Cycle away to POINT_AND_SHOOT
        viewModel.cycleUiTier()
        assertEquals(CameraUiTier.POINT_AND_SHOOT, viewModel.uiState.value.uiTier)
        assertNull(viewModel.uiState.value.activeManualTab)

        // Reset to PRO and set active manual tab
        viewModel.setUiTier(CameraUiTier.PRO)
        viewModel.setActiveManualTab("iso")
        assertEquals("iso", viewModel.uiState.value.activeManualTab)

        // Directly setUiTier to FILM
        viewModel.setUiTier(CameraUiTier.FILM)
        assertEquals(CameraUiTier.FILM, viewModel.uiState.value.uiTier)
        assertNull(viewModel.uiState.value.activeManualTab)
    }

    @Test
    fun testUpdateColorRecipe() {
        viewModel.updateColorRecipe("F-Log", "Classic Chrome")
        assertEquals("F-Log", viewModel.uiState.value.activeLogName)
        assertEquals("Classic Chrome", viewModel.uiState.value.activeLutName)
    }

    @Test
    fun testUpdateManualFlags() {
        viewModel.updateManualFlags(
            isManualFocus = true,
            isManualIso = true,
            isManualShutter = false,
            isProLinked = false
        )
        val state = viewModel.uiState.value
        assertTrue(state.isManualFocus)
        assertTrue(state.isManualIso)
        assertFalse(state.isManualShutter)
        assertFalse(state.isProLinked)
    }

    @Test
    fun testAdditionalCameraControls() {
        viewModel.setMotionPhotoEnabled(true)
        assertTrue(viewModel.uiState.value.isMotionPhotoEnabled)

        viewModel.setFlashEnabled(true)
        assertTrue(viewModel.uiState.value.isFlashEnabled)

        viewModel.setUnderexposureMode("Dynamic Daylight")
        assertEquals("Dynamic Daylight", viewModel.uiState.value.underexposureMode)
    }
}
