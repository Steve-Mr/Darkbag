package top.maary.darkbag.viewer.deletion

import android.app.Activity
import android.content.Context
import android.net.Uri
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
import top.maary.darkbag.repository.ImageRepository
import top.maary.darkbag.viewer.strategy.MediaStoreViewerStrategy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ViewerDeletionControllerTest {

    private lateinit var context: Context
    private lateinit var controller: ViewerDeletionController
    private var reloadedTargetUri: String? = null
    private var navigatedUp = false

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.setTheme(top.maary.darkbag.R.style.AppTheme)
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val repository = ImageRepository(context)
        val strategy = MediaStoreViewerStrategy()

        controller = ViewerDeletionController(
            context = context,
            scope = scope,
            repository = repository,
            mediaStrategy = strategy,
            launchDeleteRequest = {},
            onNavigateUp = { navigatedUp = true },
            onReloadImages = { uri -> reloadedTargetUri = uri },
            onUpdateAdapterGroups = {},
            getAdapterItemCount = { 1 },
            getAdapterGroupAt = { null },
            getCurrentItemIndex = { 0 }
        )
    }

    @Test
    fun testHandleDeleteResultTriggersReload() {
        controller.pendingDeleteNextTargetUri = "content://media/100"
        controller.handleDeleteResult(Activity.RESULT_OK)

        assertEquals("content://media/100", reloadedTargetUri)
        assertNull(controller.pendingDeleteNextTargetUri)
    }

    @Test
    fun testHandleDeleteResultCancelledStillClearsPendingUri() {
        controller.pendingDeleteNextTargetUri = "content://media/200"
        controller.handleDeleteResult(Activity.RESULT_CANCELED)

        assertEquals("content://media/200", reloadedTargetUri)
        assertNull(controller.pendingDeleteNextTargetUri)
    }
}
