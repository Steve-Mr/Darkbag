package top.maary.darkbag.fragments

import android.content.Context
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import androidx.fragment.app.FragmentActivity
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.maary.darkbag.R
import top.maary.darkbag.databinding.FragmentImageViewerBinding
import top.maary.darkbag.models.EditConfig
import top.maary.darkbag.models.ImageGroup
import top.maary.darkbag.viewer.bento.ViewerBentoDeckCoordinator
import top.maary.darkbag.viewer.chrome.ViewerChromeAndInsetsDelegate
import top.maary.darkbag.viewer.editor.DarkroomEditorPanelDelegate
import top.maary.darkbag.viewer.editor.DarkroomEditorViewModel

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ImageViewerControlsVisibilityTest {

    private class TestableImageViewerFragment : ImageViewerFragment() {
        fun attachTestDependencies(
            testBinding: FragmentImageViewerBinding,
            testAdapter: ImageViewerAdapter,
            testChromeDelegate: ViewerChromeAndInsetsDelegate,
            testBentoDeckCoordinator: ViewerBentoDeckCoordinator,
            testEditorPanelDelegate: DarkroomEditorPanelDelegate
        ) {
            _binding = testBinding
            adapter = testAdapter
            chromeDelegate = testChromeDelegate
            bentoDeckCoordinator = testBentoDeckCoordinator
            editorPanelDelegate = testEditorPanelDelegate
        }

        fun invokeUpdateControlsVisibility() = updateControlsVisibility()

        fun setEditingState(editing: Boolean, adjusted: Boolean, editConfig: EditConfig? = EditConfig()) {
            isEditingAdjustments = editing
            isAdjusted = adjusted
            currentEditConfig = editConfig
        }
    }

    private lateinit var context: Context
    private lateinit var binding: FragmentImageViewerBinding
    private lateinit var fragment: TestableImageViewerFragment
    private lateinit var adapter: ImageViewerAdapter

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.setTheme(R.style.AppTheme)
        val inflater = LayoutInflater.from(context)
        binding = FragmentImageViewerBinding.inflate(inflater)
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        fragment = TestableImageViewerFragment()

        val normalDng = ImageGroup(
            baseName = "IMG_DNG",
            jpgUri = Uri.parse("content://media/1.jpg"),
            dngUri = Uri.parse("content://media/1.dng")
        )
        val normalJpgOnly = ImageGroup(
            baseName = "IMG_JPG",
            jpgUri = Uri.parse("content://media/2.jpg")
        )
        val halfFrameDng = ImageGroup(
            baseName = "HF_DNG",
            jpgUri = Uri.parse("content://media/3.jpg"),
            dngUri1 = Uri.parse("content://media/3_1.dng"),
            dngUri2 = Uri.parse("content://media/3_2.dng"),
            hfLayout = "SBS",
            editConfig = EditConfig(hfLayout = "SBS")
        )

        adapter = ImageViewerAdapter(
            listOf(normalDng, normalJpgOnly, halfFrameDng),
            CoroutineScope(Dispatchers.Unconfined),
            context
        )
        binding.imagePager.adapter = adapter

        val viewModel = DarkroomEditorViewModel(ApplicationProvider.getApplicationContext())
        val bento = ViewerBentoDeckCoordinator(
            binding = binding,
            getAdapter = { adapter },
            getCurrentItemIndex = { binding.imagePager.currentItem },
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
            getAdapter = { adapter },
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
        val panel = DarkroomEditorPanelDelegate(
            binding = binding,
            viewModel = viewModel,
            layoutInflater = inflater,
            lifecycleScope = CoroutineScope(Dispatchers.Unconfined),
            ensureDngBytesLoaded = {},
            onMarkAdjusted = {},
            onViewportPaddingRequested = {},
            onEditStateChanged = { _, _ -> },
            onBackPressedCallbackStateChanged = {}
        )
        fragment.attachTestDependencies(binding, adapter, chrome, bento, panel)
    }

    @Test
    fun updateControlsVisibility_showsAdjustForNormalDng_disablesForJpgOnly_andShowsHalfFrameExtras() {
        // 1. Normal DNG: btnAdjustTofu visible & enabled, hfExtraControls GONE
        binding.imagePager.setCurrentItem(0, false)
        fragment.setEditingState(editing = false, adjusted = false)
        fragment.invokeUpdateControlsVisibility()
        assertEquals(View.VISIBLE, binding.btnAdjustTofu.visibility)
        assertTrue(binding.btnAdjustTofu.isEnabled)
        assertEquals(1.0f, binding.btnAdjustTofu.alpha, 0.001f)
        assertEquals(View.GONE, binding.hfExtraControls.visibility)

        // 2. Normal JPG-only: btnAdjustTofu visible but disabled (0.65f)
        binding.imagePager.setCurrentItem(1, false)
        fragment.setEditingState(editing = false, adjusted = false)
        fragment.invokeUpdateControlsVisibility()
        assertEquals(View.VISIBLE, binding.btnAdjustTofu.visibility)
        assertFalse(binding.btnAdjustTofu.isEnabled)
        assertEquals(0.65f, binding.btnAdjustTofu.alpha, 0.001f)
        assertEquals(View.GONE, binding.hfExtraControls.visibility)

        // 3. Half-Frame DNG (even when isAdjusted = true): btnAdjustTofu & hfExtraControls remain VISIBLE
        binding.imagePager.setCurrentItem(2, false)
        fragment.setEditingState(editing = false, adjusted = true, editConfig = EditConfig(hfLayout = "SBS"))
        fragment.invokeUpdateControlsVisibility()
        assertEquals(View.VISIBLE, binding.btnAdjustTofu.visibility)
        assertTrue(binding.btnAdjustTofu.isEnabled)
        assertEquals(View.VISIBLE, binding.hfExtraControls.visibility)

        // 4. Editing mode hides viewerBentoDeck; exiting restores it
        fragment.setEditingState(editing = true, adjusted = false, editConfig = EditConfig(hfLayout = "SBS"))
        fragment.invokeUpdateControlsVisibility()
        assertEquals(View.GONE, binding.viewerBentoDeck.visibility)

        fragment.setEditingState(editing = false, adjusted = false, editConfig = EditConfig(hfLayout = "SBS"))
        fragment.invokeUpdateControlsVisibility()
        assertEquals(View.VISIBLE, binding.viewerBentoDeck.visibility)
    }
}
