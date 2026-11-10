package top.maary.darkbag.viewmodel

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
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
import org.robolectric.shadows.ShadowLooper
import top.maary.darkbag.R
import top.maary.darkbag.models.PlaygroundItem
import top.maary.darkbag.repository.PlaygroundRepository
import top.maary.darkbag.ui.bento.BentoContainerRole
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class PlaygroundGalleryViewModelTest {

    private lateinit var viewModel: PlaygroundGalleryViewModel

    private fun pumpMainLooper(timeoutMs: Long = 2000, condition: () -> Boolean) {
        val start = System.currentTimeMillis()
        while (!condition() && System.currentTimeMillis() - start < timeoutMs) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(20)
        }
        ShadowLooper.idleMainLooper()
    }

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        viewModel = PlaygroundGalleryViewModel(app)
        ShadowLooper.idleMainLooper()
    }

    @Test
    fun testAndroidViewModelFactoryConstructorReflection() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val constructor = PlaygroundGalleryViewModel::class.java.getConstructor(android.app.Application::class.java)
        org.junit.Assert.assertNotNull(constructor)
        val vm = constructor.newInstance(app)
        org.junit.Assert.assertNotNull(vm)
    }

    @Test
    fun testDefaultState() {
        val state = viewModel.uiState.value
        assertFalse(state.isSelectionMode)
        assertTrue(state.selectedFiles.isEmpty())
        assertTrue(state.expandedGroupPaths.isEmpty())
        assertEquals(2, state.columnCount)
        assertTrue(state.isWaterfallMode)
        assertEquals(BentoContainerRole.SURFACE_CONTAINER, state.containerRole)
        assertFalse(state.isDeleting)
        assertFalse(state.isExporting)
    }

    @Test
    fun testToggleSelection_SingleFile() {
        val fileA = File("/mock/test_a.dng")
        viewModel.processIntent(PlaygroundUserIntent.ToggleSelection(fileA))

        val stateSelected = viewModel.uiState.value
        assertTrue(stateSelected.isSelectionMode)
        assertEquals(1, stateSelected.selectedFiles.size)
        assertTrue(stateSelected.selectedFiles.contains(fileA))
        assertEquals(BentoContainerRole.PRIMARY_CONTAINER, stateSelected.containerRole)

        // Toggle again to deselect
        viewModel.processIntent(PlaygroundUserIntent.ToggleSelection(fileA))
        val stateDeselected = viewModel.uiState.value
        assertFalse(stateDeselected.isSelectionMode)
        assertTrue(stateDeselected.selectedFiles.isEmpty())
        assertEquals(BentoContainerRole.SURFACE_CONTAINER, stateDeselected.containerRole)
    }

    @Test
    fun testClearSelection() {
        val fileA = File("/mock/test_a.dng")
        val fileB = File("/mock/test_b.dng")
        viewModel.processIntent(PlaygroundUserIntent.ToggleSelection(fileA))
        viewModel.processIntent(PlaygroundUserIntent.ToggleSelection(fileB))

        assertTrue(viewModel.uiState.value.isSelectionMode)
        assertEquals(2, viewModel.uiState.value.selectedFiles.size)

        viewModel.processIntent(PlaygroundUserIntent.ClearSelection)
        val state = viewModel.uiState.value
        assertFalse(state.isSelectionMode)
        assertTrue(state.selectedFiles.isEmpty())
        assertTrue(state.expandedGroupPaths.isEmpty())
        assertEquals(BentoContainerRole.SURFACE_CONTAINER, state.containerRole)
    }

    @Test
    fun testToggleLayout() {
        assertEquals(2, viewModel.uiState.value.columnCount)

        viewModel.processIntent(PlaygroundUserIntent.ToggleLayout)
        assertEquals(3, viewModel.uiState.value.columnCount)

        viewModel.processIntent(PlaygroundUserIntent.ToggleLayout)
        assertEquals(2, viewModel.uiState.value.columnCount)
    }

    @Test
    fun testGroupExpansionToggle() {
        val jpg = File("/mock/group.jpg")
        val dng1 = File("/mock/group_1.dng")
        val dng2 = File("/mock/group_2.dng")
        val group = PlaygroundItem.Group(jpgFile = jpg, dng1 = dng1, dng2 = dng2, isExpanded = false)

        viewModel.processIntent(PlaygroundUserIntent.ToggleGroupExpansion(group))
        assertTrue(viewModel.uiState.value.expandedGroupPaths.contains(jpg.absolutePath))

        viewModel.processIntent(PlaygroundUserIntent.ToggleGroupExpansion(group))
        assertFalse(viewModel.uiState.value.expandedGroupPaths.contains(jpg.absolutePath))
    }

    @Test
    fun testToggleSelectAll() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val repo = PlaygroundRepository(app)
        val pDir = repo.playgroundDir
        val file1 = File(pDir, "test1.dng").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val file2 = File(pDir, "test2.dng").apply { writeBytes(byteArrayOf(4, 5, 6)) }

        val vm = PlaygroundGalleryViewModel(app, repo)
        pumpMainLooper { vm.uiState.value.items.isNotEmpty() }

        assertTrue(vm.uiState.value.items.isNotEmpty())

        // Toggle select all -> all selected
        vm.processIntent(PlaygroundUserIntent.ToggleSelectAll)
        assertTrue(vm.uiState.value.isSelectionMode)
        assertEquals(vm.uiState.value.items.size, vm.uiState.value.selectedFiles.size)
        assertEquals(BentoContainerRole.PRIMARY_CONTAINER, vm.uiState.value.containerRole)

        // Toggle select all again -> deselected
        vm.processIntent(PlaygroundUserIntent.ToggleSelectAll)
        assertFalse(vm.uiState.value.isSelectionMode)
        assertTrue(vm.uiState.value.selectedFiles.isEmpty())
        assertEquals(BentoContainerRole.SURFACE_CONTAINER, vm.uiState.value.containerRole)

        file1.delete()
        file2.delete()
    }

    @Test
    fun testExportSelected_EmptySelection_EmitsHintEffect() = runBlocking {
        viewModel.processIntent(PlaygroundUserIntent.ClearSelection)
        val job = async {
            viewModel.effects.first()
        }
        viewModel.processIntent(PlaygroundUserIntent.ExportSelected)
        val effect = job.await()
        assertTrue(effect is PlaygroundEffect.ShowToastRes)
        assertEquals(R.string.playground_export_hint, (effect as PlaygroundEffect.ShowToastRes).resId)
    }

    @Test
    fun testDeleteSelected() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val repo = PlaygroundRepository(app)
        val pDir = repo.playgroundDir
        val file1 = File(pDir, "to_delete.dng").apply { writeBytes(byteArrayOf(1, 2, 3)) }

        val vm = PlaygroundGalleryViewModel(app, repo)
        pumpMainLooper { vm.uiState.value.items.isNotEmpty() }

        vm.processIntent(PlaygroundUserIntent.ToggleSelection(file1))
        assertTrue(vm.uiState.value.selectedFiles.contains(file1))

        vm.processIntent(PlaygroundUserIntent.DeleteSelected)
        pumpMainLooper(timeoutMs = 5000) { !file1.exists() && !vm.uiState.value.isSelectionMode && !vm.uiState.value.isDeleting }

        assertFalse(vm.uiState.value.isSelectionMode)
        assertTrue(vm.uiState.value.selectedFiles.isEmpty())
        assertFalse(file1.exists())
    }

    @Test
    fun testDisbandSelected() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val repo = PlaygroundRepository(app)
        val pDir = repo.playgroundDir
        val base = "test_group_${System.currentTimeMillis()}"
        val jpg = File(pDir, "$base.jpg").apply { writeBytes(byteArrayOf(1, 2)) }
        val dng1 = File(pDir, "${base}_1.dng").apply { writeBytes(byteArrayOf(3, 4)) }
        val dng2 = File(pDir, "${base}_2.dng").apply { writeBytes(byteArrayOf(5, 6)) }

        val vm = PlaygroundGalleryViewModel(app, repo)
        pumpMainLooper(timeoutMs = 5000) { vm.uiState.value.items.any { it is PlaygroundItem.Group && it.jpgFile == jpg } }

        vm.processIntent(PlaygroundUserIntent.ToggleSelection(jpg))
        assertTrue(vm.uiState.value.selectedFiles.contains(jpg))

        vm.processIntent(PlaygroundUserIntent.DisbandSelected)
        pumpMainLooper(timeoutMs = 5000) { !jpg.exists() && !vm.uiState.value.isSelectionMode }

        assertFalse(vm.uiState.value.isSelectionMode)
        assertTrue(vm.uiState.value.selectedFiles.isEmpty())
        assertFalse(jpg.exists())
        assertTrue(dng1.exists())
        assertTrue(dng2.exists())

        dng1.delete()
        dng2.delete()
    }

    @Test
    fun testEmitEffect() {
        runBlocking {
            val job = async {
                viewModel.effects.first()
            }
            viewModel.emitEffect(PlaygroundEffect.OpenViewer(listOf("/path/1.dng"), "SBS"))
            val effect = job.await()
            assertTrue(effect is PlaygroundEffect.OpenViewer)
            assertEquals(listOf("/path/1.dng"), (effect as PlaygroundEffect.OpenViewer).paths)
            assertEquals("SBS", effect.hfLayout)
        }
    }

    @Test
    fun testPlaygroundColumnPersistence() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val vm1 = PlaygroundGalleryViewModel(app)
        // Default is 2 columns
        assertEquals(2, vm1.uiState.value.columnCount)

        // Toggle layout: 2 -> 3
        vm1.processIntent(PlaygroundUserIntent.ToggleLayout)
        assertEquals(3, vm1.uiState.value.columnCount)

        // New ViewModel restores 3
        val vm2 = PlaygroundGalleryViewModel(app)
        assertEquals(3, vm2.uiState.value.columnCount)
    }
}
