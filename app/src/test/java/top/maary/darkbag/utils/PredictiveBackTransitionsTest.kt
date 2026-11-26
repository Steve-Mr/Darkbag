package top.maary.darkbag.utils

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.View
import androidx.activity.BackEventCompat
import androidx.fragment.app.Fragment
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.transition.MaterialSharedAxis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.maary.darkbag.R

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class PredictiveBackTransitionsTest {

    private lateinit var themedContext: Context

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        themedContext = ContextThemeWrapper(app, R.style.AppTheme)
    }

    @Test
    fun testApply_configuresSeekableMaterialSharedAxisTransitions() {
        val fragment = Fragment()
        PredictiveBackTransitions.apply(fragment)

        assertTrue(fragment.enterTransition is MaterialSharedAxis)
        assertTrue(fragment.exitTransition is MaterialSharedAxis)
        assertTrue(fragment.returnTransition is MaterialSharedAxis)
        assertTrue(fragment.reenterTransition is MaterialSharedAxis)

        assertTrue((fragment.enterTransition as MaterialSharedAxis).isSeekingSupported)
        assertTrue((fragment.returnTransition as MaterialSharedAxis).isSeekingSupported)
    }

    @Test
    fun testComputeScrubTransform_clampsAndRespectsSwipeEdgeDirection() {
        val leftSwipe = PredictiveBackTransitions.computeScrubTransform(
            progress = 0.5f,
            swipeEdge = BackEventCompat.EDGE_LEFT,
            density = 2f,
            maxTranslationYDp = 20f,
            maxTranslationXDp = 10f,
            minScale = 0.96f,
            minAlpha = 0.6f
        )
        assertEquals(10f, leftSwipe.translationX, 0.001f)
        assertEquals(20f, leftSwipe.translationY, 0.001f)
        assertEquals(0.98f, leftSwipe.scale, 0.001f)
        assertEquals(0.8f, leftSwipe.alpha, 0.001f)

        val rightSwipe = PredictiveBackTransitions.computeScrubTransform(
            progress = 1.5f, // clamped to 1.0f
            swipeEdge = BackEventCompat.EDGE_RIGHT,
            density = 2f,
            maxTranslationYDp = 20f,
            maxTranslationXDp = 10f,
            minScale = 0.96f,
            minAlpha = 0.6f
        )
        assertEquals(-20f, rightSwipe.translationX, 0.001f)
        assertEquals(40f, rightSwipe.translationY, 0.001f)
        assertEquals(0.96f, rightSwipe.scale, 0.001f)
        assertEquals(0.6f, rightSwipe.alpha, 0.001f)
    }

    @Test
    fun testApplyScrubProgressAndResetImmediate_updatesAndRestoresVisibleViews() {
        val panel = View(themedContext).apply { visibility = View.VISIBLE }
        val backdrop = View(themedContext).apply {
            visibility = View.VISIBLE
            alpha = 1f
        }
        val targets = listOf(
            PredictiveBackTransitions.ScrubTarget(
                view = panel,
                maxTranslationYDp = 20f,
                maxTranslationXDp = 0f,
                minScale = 0.96f,
                minAlpha = 0.5f
            )
        )

        val event = BackEventCompat(0f, 0f, 0.5f, BackEventCompat.EDGE_LEFT)
        PredictiveBackTransitions.applyScrubProgress(targets, event, backdropView = backdrop)

        assertTrue(panel.translationY > 0f)
        assertEquals(0.98f, panel.scaleX, 0.001f)
        assertEquals(0.75f, panel.alpha, 0.001f)
        assertEquals(0.75f, backdrop.alpha, 0.001f)

        PredictiveBackTransitions.resetScrubImmediate(targets, backdropView = backdrop)
        assertEquals(0f, panel.translationY, 0.001f)
        assertEquals(1f, panel.scaleX, 0.001f)
        assertEquals(1f, panel.alpha, 0.001f)
        assertEquals(1f, backdrop.alpha, 0.001f)
    }
}
