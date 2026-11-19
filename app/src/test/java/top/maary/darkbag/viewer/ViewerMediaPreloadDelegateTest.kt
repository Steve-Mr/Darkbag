package top.maary.darkbag.viewer

import android.content.Context
import android.net.Uri
import android.view.LayoutInflater
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.maary.darkbag.R
import top.maary.darkbag.databinding.FragmentImageViewerBinding
import top.maary.darkbag.models.ImageGroup
import top.maary.darkbag.repository.ImageRepository
import top.maary.darkbag.viewer.bento.ViewerBentoDeckCoordinator
import top.maary.darkbag.viewer.chrome.ViewerChromeAndInsetsDelegate
import top.maary.darkbag.viewer.editor.DarkroomEditorViewModel

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ViewerMediaPreloadDelegateTest {

    private lateinit var delegate: ViewerMediaPreloadDelegate

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        context.setTheme(R.style.AppTheme)
        val binding = FragmentImageViewerBinding.inflate(LayoutInflater.from(context))
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val repository = ImageRepository(context)
        val editorViewModel = ViewModelProvider(activity)[DarkroomEditorViewModel::class.java]
        val bento = ViewerBentoDeckCoordinator(
            binding = binding,
            getAdapter = { null },
            getCurrentItemIndex = { 0 },
            onControlsVisibilityRequested = {},
            onMultiCamCollageRequested = {},
            onOpenExportHubRequested = {},
            onFormatToggleRequested = {},
            onDerivativeCycleRequested = {},
            onMotionLiveToggleRequested = {}
        )
        val chrome = ViewerChromeAndInsetsDelegate(
            context = context,
            binding = binding,
            fragmentManager = activity.supportFragmentManager,
            getAdapter = { null },
            isGallerySupported = { true },
            isAdjusted = { false },
            isEditingAdjustments = { false },
            isSaving = { false },
            isLutListVisible = { false },
            onBackNavigate = {},
            onDismissPillPopup = { false },
            onExitEditMode = {},
            onEnterEditMode = {},
            onResetAdjustments = {},
            onSaveEdit = {},
            onShowSaveMenu = {},
            onOpenExportHub = {},
            onPerformShare = {},
            onPerformShareAsTiff = {},
            onShowLutMenu = {},
            onShowDeleteDialog = {},
            onUpdateBackPressedEnabled = {}
        )

        delegate = ViewerMediaPreloadDelegate(
            context = context,
            binding = binding,
            lifecycleScope = scope,
            repository = repository,
            editorViewModel = editorViewModel,
            bentoDeckCoordinator = bento,
            chromeDelegate = chrome,
            getAdapter = { null },
            isBindingAlive = { true },
            isMotionPhotoAutoPlay = { false },
            setMotionPhotoAutoPlay = {},
            getHasAutoPlayedPosition = { -1 },
            setHasAutoPlayedPosition = {},
            isAdjusted = { false },
            getLastCompositeBitmap = { null },
            setLongPressing = {},
            getSourceDngBytes = { Pair(null, null) },
            setSourceDngBytes = { _, _ -> },
            setCurrentCinemaDngFrame = {},
            onPrepareEditConfig = {},
            onUpdateControlsVisibility = {},
            onUpdateFormatBentoRow = {},
            onOpenDarkroomGallery = {}
        )
    }

    @Test
    fun testMatchesTargetAcrossAllGroupUriFields() {
        val group = ImageGroup(
            baseName = "DBAG_20261115_001",
            jpgUri = Uri.parse("content://media/main.jpg"),
            dngUri = Uri.parse("content://media/main.dng"),
            dngUri1 = Uri.parse("content://media/hf1.dng"),
            dngUri2 = Uri.parse("content://media/hf2.dng"),
            rawVideoUri = Uri.parse("content://media/clip.rawvid"),
            mp4VideoUri = Uri.parse("content://media/clip.mp4"),
            derivativeJpgUris = listOf(Uri.parse("content://media/v2.jpg")),
            multiJpgUris = listOf(Uri.parse("content://media/tele.jpg")),
            multiDngUris = listOf(Uri.parse("content://media/tele.dng"))
        )

        assertTrue(delegate.matchesTarget(group, "content://media/other.jpg", "DBAG_20261115_001"))
        assertTrue(delegate.matchesTarget(group, "content://media/main.jpg", null))
        assertTrue(delegate.matchesTarget(group, "content://media/main.dng", null))
        assertTrue(delegate.matchesTarget(group, "content://media/hf1.dng", null))
        assertTrue(delegate.matchesTarget(group, "content://media/hf2.dng", null))
        assertTrue(delegate.matchesTarget(group, "content://media/clip.rawvid", null))
        assertTrue(delegate.matchesTarget(group, "content://media/clip.mp4", null))
        assertTrue(delegate.matchesTarget(group, "content://media/v2.jpg", null))
        assertTrue(delegate.matchesTarget(group, "content://media/tele.jpg", null))
        assertTrue(delegate.matchesTarget(group, "content://media/tele.dng", null))

        assertFalse(delegate.matchesTarget(group, null, "DBAG_20261115_001"))
        assertFalse(delegate.matchesTarget(group, "content://media/unrelated.jpg", "DBAG_OTHER"))
    }
}
