package top.maary.darkbag.viewer.editor

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.maary.darkbag.R
import top.maary.darkbag.databinding.FragmentImageViewerBinding
import top.maary.darkbag.models.EditConfig

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DarkroomEditorPanelDelegateTest {

    private lateinit var context: Context
    private lateinit var binding: FragmentImageViewerBinding
    private lateinit var viewModel: DarkroomEditorViewModel
    private lateinit var delegate: DarkroomEditorPanelDelegate

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.setTheme(R.style.AppTheme)
        val inflater = LayoutInflater.from(context)
        binding = FragmentImageViewerBinding.inflate(inflater)
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        viewModel = DarkroomEditorViewModel(app)
        val scope = CoroutineScope(Dispatchers.Unconfined)

        delegate = DarkroomEditorPanelDelegate(
            binding = binding,
            viewModel = viewModel,
            layoutInflater = inflater,
            lifecycleScope = scope,
            ensureDngBytesLoaded = {},
            onMarkAdjusted = {},
            onViewportPaddingRequested = {},
            onEditStateChanged = { _, _ -> },
            onBackPressedCallbackStateChanged = {}
        )
    }

    @Test
    fun testDismissPillPopupWhenHiddenReturnsFalse() {
        binding.lutListContainer.visibility = View.GONE
        assertFalse(delegate.dismissPillPopup())
    }

    @Test
    fun testDismissPillPopupWhenVisibleHidesAndReturnsTrue() {
        binding.lutListContainer.visibility = View.VISIBLE
        binding.touchOverlay.visibility = View.VISIBLE

        assertTrue(delegate.dismissPillPopup())
        assertEquals(View.GONE, binding.lutListContainer.visibility)
        assertEquals(View.GONE, binding.touchOverlay.visibility)
    }

    @Test
    fun testUpdateEffectsButtons() {
        val config = EditConfig(
            showTimestamp = true,
            flareType = 1,
            isSwapped = true
        )

        delegate.updateEffectsButtons(config, isTB = true)

        assertTrue(binding.btnTimestamp.text.contains("DATE"))
        assertTrue(binding.btnFlare.text.contains("FLARE"))
        assertTrue(binding.btnSwap.text.contains("SWAP"))
    }

    @Test
    fun testRenderKeepsLegacyFullScreenOverlaysGoneAndForwardsHalfFrameOverlayState() {
        var overlayArgs: Triple<Boolean, Boolean, Int>? = null
        var recipeClicked = false
        val wiredDelegate = DarkroomEditorPanelDelegate(
            binding = binding,
            viewModel = viewModel,
            layoutInflater = LayoutInflater.from(context),
            lifecycleScope = CoroutineScope(Dispatchers.Unconfined),
            ensureDngBytesLoaded = {},
            onMarkAdjusted = {},
            onViewportPaddingRequested = {},
            onEditStateChanged = { _, _ -> },
            onBackPressedCallbackStateChanged = {},
            onShowRecipeMenu = { recipeClicked = true },
            onHalfFrameOverlayChanged = { active, isTb, idx ->
                overlayArgs = Triple(active, isTb, idx)
            }
        )
        wiredDelegate.setup()

        wiredDelegate.render(
            DarkroomEditorUiState(
                isEditing = true,
                isHalfFrame = true,
                selectedHalfFrameIndex = 1,
                activeConfig = EditConfig(hfLayout = "TB")
            )
        )

        assertEquals(View.GONE, binding.hfSelection1.visibility)
        assertEquals(View.GONE, binding.hfSelection2.visibility)
        assertEquals(Triple(true, true, 1), overlayArgs)

        binding.btnEditRecipe.performClick()
        assertTrue(recipeClicked)
    }
}
