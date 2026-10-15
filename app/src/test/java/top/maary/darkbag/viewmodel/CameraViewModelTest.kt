package top.maary.darkbag.viewmodel

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}

