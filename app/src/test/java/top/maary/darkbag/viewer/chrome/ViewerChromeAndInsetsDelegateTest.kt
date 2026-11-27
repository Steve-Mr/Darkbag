package top.maary.darkbag.viewer.chrome

import android.content.Context
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import androidx.fragment.app.FragmentActivity
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
import top.maary.darkbag.fragments.ImageViewerAdapter
import top.maary.darkbag.models.ImageGroup

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ViewerChromeAndInsetsDelegateTest {

    private lateinit var context: Context
    private lateinit var binding: FragmentImageViewerBinding
    private lateinit var adapter: ImageViewerAdapter
    private var adjusted = false
    private var editing = false
    private var gallerySupported = true
    private var backEnabled = false
    private var backNavigateCount = 0
    private var resetCount = 0
    private var exitEditCount = 0
    private var navigateBackOnDiscard = false

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.setTheme(R.style.AppTheme)
        binding = FragmentImageViewerBinding.inflate(LayoutInflater.from(context))
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val groupJpg = ImageGroup(baseName = "IMG_1", jpgUri = Uri.parse("content://media/1.jpg"))
        val groupRawVid = ImageGroup(baseName = "VID_1", isRawVideo = true, rawVideoUri = Uri.parse("content://media/1.rawvid"))
        adapter = ImageViewerAdapter(listOf(groupJpg, groupRawVid), CoroutineScope(Dispatchers.Unconfined), context)
        binding.imagePager.adapter = adapter
    }

    private fun createDelegate(
        fragmentManager: androidx.fragment.app.FragmentManager,
        delegateContext: Context = context
    ): ViewerChromeAndInsetsDelegate {
        return ViewerChromeAndInsetsDelegate(
            context = delegateContext,
            binding = binding,
            fragmentManager = fragmentManager,
            getAdapter = { adapter },
            isGallerySupported = { gallerySupported },
            isAdjusted = { adjusted },
            isEditingAdjustments = { editing },
            isSaving = { false },
            isLutListVisible = { binding.lutListContainer.visibility == View.VISIBLE },
            onBackNavigate = { backNavigateCount++ },
            onDismissPillPopup = { false },
            onExitEditMode = { exitEditCount++ },
            onEnterEditMode = {},
            onResetAdjustments = { resetCount++ },
            onSaveEdit = {},
            onShowSaveMenu = {},
            onOpenExportHub = {},
            onPerformShare = {},
            onPerformShareAsTiff = {},
            onShowLutMenu = {},
            onShowDeleteDialog = {},
            onUpdateBackPressedEnabled = { backEnabled = it },
            shouldNavigateBackOnDiscard = { navigateBackOnDiscard }
        )
    }

    @Test
    fun testGalleryPillAndSplitButtonsStates() {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val delegate = createDelegate(activity.supportFragmentManager)

        gallerySupported = false
        delegate.updateGalleryPill(0, 5)
        assertEquals(View.GONE, binding.btnGalleryPill.visibility)

        gallerySupported = true
        delegate.updateGalleryPill(1, 5)
        assertEquals(View.VISIBLE, binding.btnGalleryPill.visibility)
        assertEquals("2 / 5", binding.btnGalleryPill.text.toString())

        // Standard rendered item shows SHARE
        binding.imagePager.setCurrentItem(0, false)
        delegate.updateSplitButtons()
        assertEquals("SHARE", binding.btnActionMain.text?.toString())

        // Unrendered RAW video shows EXPORT
        binding.imagePager.setCurrentItem(1, false)
        delegate.updateSplitButtons()
        assertEquals("EXPORT", binding.btnActionMain.text?.toString())

        // Editing state shows SAVE
        editing = true
        delegate.updateSplitButtons()
        assertEquals(context.getString(R.string.save).uppercase(), binding.btnActionMain.text?.toString())

        // Back callback enabled when editing or adjusted or LUT list visible
        delegate.updateBackPressedCallbackState()
        assertTrue(backEnabled)

        editing = false
        adjusted = false
        binding.lutListContainer.visibility = View.GONE
        delegate.updateBackPressedCallbackState()
        assertFalse(backEnabled)
    }

    @Test
    fun testToggleUiUpdatesVisibilityFlag() {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val delegate = createDelegate(activity.supportFragmentManager)
        assertTrue(delegate.isUiVisible)

        delegate.toggleUi()
        assertFalse(delegate.isUiVisible)

        delegate.toggleUi()
        assertTrue(delegate.isUiVisible)

        // While editing adjustments, showUi() must not unhide viewerBentoDeck over the adjustment panel
        delegate.toggleUi() // hide
        editing = true
        binding.viewerBentoDeck.visibility = View.GONE
        delegate.showUi()
        assertTrue(delegate.isUiVisible)
        assertEquals(View.GONE, binding.viewerBentoDeck.visibility)
    }

    @Test
    fun testDiscardDialogStaysInViewerByDefaultAndNavigatesOnlyWhenRequested() {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        activity.setTheme(R.style.AppTheme)
        val delegate = createDelegate(activity.supportFragmentManager, activity)
        delegate.setupToolbar()

        // When editing & adjusted, btnBack opens discard dialog instead of exiting edit mode silently
        editing = true
        adjusted = true
        navigateBackOnDiscard = false
        binding.btnBack.performClick()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertEquals(0, exitEditCount)

        val dialog1 = org.robolectric.shadows.ShadowAlertDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        assertNotNull(dialog1)
        dialog1.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).performClick()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertEquals(1, resetCount)
        assertEquals(0, backNavigateCount)

        // When shouldNavigateBackOnDiscard is true (e.g. unsaved sandbox pair), confirming discard navigates back
        navigateBackOnDiscard = true
        delegate.showDiscardChangesDialog()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        val dialog2 = org.robolectric.shadows.ShadowAlertDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        assertNotNull(dialog2)
        dialog2.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).performClick()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertEquals(2, resetCount)
        assertEquals(1, backNavigateCount)
    }

    @Test
    fun testSplitActionMenuOpensBentoTrayAndResetsCheckedOnDismiss() {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        activity.setTheme(R.style.AppTheme)
        val themedBinding = FragmentImageViewerBinding.inflate(LayoutInflater.from(activity))
        themedBinding.imagePager.adapter = adapter
        var detailsClicked = false
        val delegate = ViewerChromeAndInsetsDelegate(
            context = activity,
            binding = themedBinding,
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
            onOpenExportHub = { detailsClicked = true },
            onPerformShare = {},
            onPerformShareAsTiff = {},
            onShowLutMenu = {},
            onShowDeleteDialog = {},
            onUpdateBackPressedEnabled = {}
        )
        delegate.setupActionButtons()
        themedBinding.imagePager.setCurrentItem(0, false)

        themedBinding.btnActionMenu.performClick()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertTrue(themedBinding.btnActionMenu.isChecked)

        val popup = org.robolectric.Shadows.shadowOf(activity.application).latestPopupWindow
        assertNotNull(popup)
        assertTrue(popup.isShowing)

        popup.dismiss()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertFalse(themedBinding.btnActionMenu.isChecked)
        assertFalse(detailsClicked)
    }
}
