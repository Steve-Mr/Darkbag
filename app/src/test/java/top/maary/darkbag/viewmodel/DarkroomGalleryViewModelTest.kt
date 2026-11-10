package top.maary.darkbag.viewmodel

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
import top.maary.darkbag.models.ImageGroup
import top.maary.darkbag.models.MultiCameraLensItem
import top.maary.darkbag.ui.bento.BentoContainerRole

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class DarkroomGalleryViewModelTest {

    private lateinit var viewModel: DarkroomGalleryViewModel

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        viewModel = DarkroomGalleryViewModel(app)
    }

    @Test
    fun testAndroidViewModelFactoryConstructorReflection() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val constructor = DarkroomGalleryViewModel::class.java.getConstructor(android.app.Application::class.java)
        assertNotNull(constructor)
        val vm = constructor.newInstance(app)
        assertNotNull(vm)
    }

    @Test
    fun testDefaultState() {
        val state = viewModel.uiState.value
        assertFalse(state.isSelectionMode)
        assertTrue(state.selectedKeys.isEmpty())
        assertEquals(0, state.selectedCount)
        assertEquals(3, state.columnCount)
        assertFalse(state.isWaterfallMode)
        assertEquals(BentoContainerRole.SURFACE_CONTAINER, state.containerRole)
        assertFalse(state.isLoading)
        assertFalse(state.isDeleting)
        assertEquals(0, state.deletingCount)
    }

    @Test
    fun testToggleSingleSelection() {
        val groupA = ImageGroup(baseName = "DARK_20261109_001")
        viewModel.processIntent(DarkroomGalleryUserIntent.ToggleSingleSelection(groupA))

        val stateSelected = viewModel.uiState.value
        assertTrue(stateSelected.isSelectionMode)
        assertEquals(1, stateSelected.selectedCount)
        assertTrue(stateSelected.selectedKeys.contains("DARK_20261109_001"))
        assertEquals(BentoContainerRole.PRIMARY_CONTAINER, stateSelected.containerRole)

        // Toggle again to deselect
        viewModel.processIntent(DarkroomGalleryUserIntent.ToggleSingleSelection(groupA))
        val stateDeselected = viewModel.uiState.value
        assertFalse(stateDeselected.isSelectionMode)
        assertTrue(stateDeselected.selectedKeys.isEmpty())
        assertEquals(BentoContainerRole.SURFACE_CONTAINER, stateDeselected.containerRole)
    }

    @Test
    fun testToggleLensSelection() {
        val groupMulti = ImageGroup(
            baseName = "DARK_20261109_MULTI",
            isMultiCamera = true,
            multiCameraLenses = listOf(
                MultiCameraLensItem(lensTag = "WIDE", multiplier = 1.0f),
                MultiCameraLensItem(lensTag = "ULTRA", multiplier = 0.6f)
            )
        )
        val lensWide = groupMulti.multiCameraLenses[0]

        viewModel.processIntent(DarkroomGalleryUserIntent.ToggleLensSelection(groupMulti, lensWide))

        val stateSelected = viewModel.uiState.value
        assertTrue(stateSelected.isSelectionMode)
        assertEquals(1, stateSelected.selectedCount)
        assertTrue(stateSelected.selectedKeys.contains("DARK_20261109_MULTI#WIDE"))
        assertEquals(BentoContainerRole.PRIMARY_CONTAINER, stateSelected.containerRole)

        // Toggle again to deselect
        viewModel.processIntent(DarkroomGalleryUserIntent.ToggleLensSelection(groupMulti, lensWide))
        val stateDeselected = viewModel.uiState.value
        assertFalse(stateDeselected.isSelectionMode)
        assertTrue(stateDeselected.selectedKeys.isEmpty())
        assertEquals(BentoContainerRole.SURFACE_CONTAINER, stateDeselected.containerRole)
    }

    @Test
    fun testClearSelection() {
        val groupA = ImageGroup(baseName = "DARK_20261109_001")
        val groupB = ImageGroup(baseName = "DARK_20261109_002")
        viewModel.processIntent(DarkroomGalleryUserIntent.ToggleSingleSelection(groupA))
        viewModel.processIntent(DarkroomGalleryUserIntent.ToggleSingleSelection(groupB))

        assertTrue(viewModel.uiState.value.isSelectionMode)
        assertEquals(2, viewModel.uiState.value.selectedCount)

        viewModel.processIntent(DarkroomGalleryUserIntent.ClearSelection)
        val state = viewModel.uiState.value
        assertFalse(state.isSelectionMode)
        assertTrue(state.selectedKeys.isEmpty())
        assertEquals(BentoContainerRole.SURFACE_CONTAINER, state.containerRole)
    }

    @Test
    fun testCycleLayout_Portrait() {
        assertEquals(3, viewModel.uiState.value.columnCount)
        assertFalse(viewModel.uiState.value.isWaterfallMode)

        // 3 -> 2
        viewModel.processIntent(DarkroomGalleryUserIntent.CycleLayout(isLandscape = false))
        assertEquals(2, viewModel.uiState.value.columnCount)
        assertFalse(viewModel.uiState.value.isWaterfallMode)

        // 2 -> 1
        viewModel.processIntent(DarkroomGalleryUserIntent.CycleLayout(isLandscape = false))
        assertEquals(1, viewModel.uiState.value.columnCount)
        assertFalse(viewModel.uiState.value.isWaterfallMode)

        // 1 -> Waterfall (2 cols)
        viewModel.processIntent(DarkroomGalleryUserIntent.CycleLayout(isLandscape = false))
        assertEquals(2, viewModel.uiState.value.columnCount)
        assertTrue(viewModel.uiState.value.isWaterfallMode)

        // Waterfall -> 3 (Square)
        viewModel.processIntent(DarkroomGalleryUserIntent.CycleLayout(isLandscape = false))
        assertEquals(3, viewModel.uiState.value.columnCount)
        assertFalse(viewModel.uiState.value.isWaterfallMode)
    }

    @Test
    fun testCycleLayout_Landscape() {
        // Landscape starts or cycles through 4 columns
        viewModel.processIntent(DarkroomGalleryUserIntent.CycleLayout(isLandscape = true))
        // from 3 -> 2
        assertEquals(2, viewModel.uiState.value.columnCount)

        // 2 -> 1
        viewModel.processIntent(DarkroomGalleryUserIntent.CycleLayout(isLandscape = true))
        assertEquals(1, viewModel.uiState.value.columnCount)

        // 1 -> Waterfall (3 cols in landscape)
        viewModel.processIntent(DarkroomGalleryUserIntent.CycleLayout(isLandscape = true))
        assertEquals(3, viewModel.uiState.value.columnCount)
        assertTrue(viewModel.uiState.value.isWaterfallMode)

        // Waterfall -> 4 (Square in landscape)
        viewModel.processIntent(DarkroomGalleryUserIntent.CycleLayout(isLandscape = true))
        assertEquals(4, viewModel.uiState.value.columnCount)
        assertFalse(viewModel.uiState.value.isWaterfallMode)
    }

    @Test
    fun testSetDeletingAndLoading() {
        viewModel.processIntent(DarkroomGalleryUserIntent.SetDeleting(true, count = 5))
        assertTrue(viewModel.uiState.value.isDeleting)
        assertEquals(5, viewModel.uiState.value.deletingCount)

        viewModel.processIntent(DarkroomGalleryUserIntent.SetDeleting(false, count = 0))
        assertFalse(viewModel.uiState.value.isDeleting)
        assertEquals(0, viewModel.uiState.value.deletingCount)

        viewModel.processIntent(DarkroomGalleryUserIntent.SetLoading(true))
        assertTrue(viewModel.uiState.value.isLoading)

        viewModel.processIntent(DarkroomGalleryUserIntent.SetLoading(false))
        assertFalse(viewModel.uiState.value.isLoading)
    }
}
