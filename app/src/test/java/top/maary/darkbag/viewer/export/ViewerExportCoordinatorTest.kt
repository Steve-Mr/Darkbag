package top.maary.darkbag.viewer.export

import android.content.Context
import android.view.LayoutInflater
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.maary.darkbag.models.ImageGroup
import top.maary.darkbag.rawvideo.RawVideoExporter
import top.maary.darkbag.repository.ImageRepository

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ViewerExportCoordinatorTest {

    private lateinit var context: Context
    private lateinit var coordinator: ViewerExportCoordinator

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.setTheme(top.maary.darkbag.R.style.AppTheme)
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val inflater = LayoutInflater.from(context)
        coordinator = ViewerExportCoordinator(context, scope, inflater)
    }

    @Test
    fun testShowExportProgressDialogAndDismiss() {
        var cancelled = false
        val (dialog, updateProgress) = coordinator.showExportProgressDialog("Test Export") {
            cancelled = true
        }

        assertNotNull(dialog)
        assertTrue(dialog.isShowing)
        assertEquals(dialog, coordinator.activeExportDialog)

        // Progress callback invocations without crash
        updateProgress(50, 100)

        coordinator.dismissActiveDialog()
        assertFalse(dialog.isShowing)
        assertNull(coordinator.activeExportDialog)
    }

    @Test
    fun testExportCinemaDngFrameHandlesNullUriGracefully() {
        var finished = false
        val group = ImageGroup(baseName = "TEST_CDNG")
        val repository = ImageRepository(context)

        coordinator.exportCinemaDngFrame(
            group = group,
            frameUri = null,
            frameIndex = 0,
            exportType = RawVideoExporter.FrameExportType.RAW_DNG_ONLY,
            repository = repository,
            onStart = { fail("Should not start if URI is null") },
            onFinished = { finished = true }
        )

        assertFalse(finished)
    }
}
