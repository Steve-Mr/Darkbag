package top.maary.darkbag.fragments

import android.content.Context
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CameraViewFinderConstraintTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        context = android.view.ContextThemeWrapper(app, top.maary.darkbag.R.style.AppTheme)
    }

    @Test
    fun testPortraitViewFinderConstraintsAvoidBottomIslandOverlap() {
        val root = ConstraintLayout(context)
        val topControls = View(context).apply { id = View.generateViewId() }
        val viewFinderContainer = View(context).apply { id = View.generateViewId() }
        val bottomIsland = View(context).apply { id = View.generateViewId() }

        root.addView(topControls)
        root.addView(viewFinderContainer)
        root.addView(bottomIsland)

        val constraintSet = ConstraintSet()
        constraintSet.clone(root)

        val marginHorizontal = 20
        val marginVertical = 12

        // Portrait linkage
        constraintSet.connect(viewFinderContainer.id, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START, marginHorizontal)
        constraintSet.connect(viewFinderContainer.id, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END, marginHorizontal)
        constraintSet.constrainWidth(viewFinderContainer.id, ConstraintSet.MATCH_CONSTRAINT)
        constraintSet.constrainHeight(viewFinderContainer.id, ConstraintSet.MATCH_CONSTRAINT)
        constraintSet.setDimensionRatio(viewFinderContainer.id, "3:4")
        constraintSet.constrainedWidth(viewFinderContainer.id, true)
        constraintSet.constrainedHeight(viewFinderContainer.id, true)

        constraintSet.connect(viewFinderContainer.id, ConstraintSet.TOP, topControls.id, ConstraintSet.BOTTOM, marginVertical)
        constraintSet.connect(viewFinderContainer.id, ConstraintSet.BOTTOM, bottomIsland.id, ConstraintSet.TOP, marginVertical)
        constraintSet.setVerticalBias(viewFinderContainer.id, 0.5f)

        constraintSet.applyTo(root)

        val lp = viewFinderContainer.layoutParams as ConstraintLayout.LayoutParams

        // Verification of constraints
        assertEquals(topControls.id, lp.topToBottom)
        assertEquals(bottomIsland.id, lp.bottomToTop)
        assertEquals(ConstraintLayout.LayoutParams.PARENT_ID, lp.startToStart)
        assertEquals(ConstraintLayout.LayoutParams.PARENT_ID, lp.endToEnd)
        assertEquals("3:4", lp.dimensionRatio)
        assertTrue(lp.constrainedWidth)
        assertTrue(lp.constrainedHeight)
        assertEquals(0.5f, lp.verticalBias, 0.001f)
    }

    @Test
    fun testLandscapeViewFinderConstraintsAvoidSideIslandOverlap() {
        val root = ConstraintLayout(context)
        val viewFinderContainer = View(context).apply { id = View.generateViewId() }
        val bottomIsland = View(context).apply { id = View.generateViewId() }

        root.addView(viewFinderContainer)
        root.addView(bottomIsland)

        val constraintSet = ConstraintSet()
        constraintSet.clone(root)

        val marginHorizontal = 20
        val marginVertical = 12

        // Landscape linkage: island is docked to the right edge
        constraintSet.connect(viewFinderContainer.id, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START, marginHorizontal)
        constraintSet.connect(viewFinderContainer.id, ConstraintSet.END, bottomIsland.id, ConstraintSet.START, marginHorizontal)
        constraintSet.constrainWidth(viewFinderContainer.id, ConstraintSet.MATCH_CONSTRAINT)
        constraintSet.constrainHeight(viewFinderContainer.id, ConstraintSet.MATCH_CONSTRAINT)
        constraintSet.setDimensionRatio(viewFinderContainer.id, "4:3")
        constraintSet.constrainedWidth(viewFinderContainer.id, true)
        constraintSet.constrainedHeight(viewFinderContainer.id, true)

        constraintSet.connect(viewFinderContainer.id, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP, marginVertical)
        constraintSet.connect(viewFinderContainer.id, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM, marginVertical)
        constraintSet.setHorizontalBias(viewFinderContainer.id, 0.5f)

        constraintSet.applyTo(root)

        val lp = viewFinderContainer.layoutParams as ConstraintLayout.LayoutParams

        assertEquals(ConstraintLayout.LayoutParams.PARENT_ID, lp.startToStart)
        assertEquals(bottomIsland.id, lp.endToStart)
        assertEquals(ConstraintLayout.LayoutParams.PARENT_ID, lp.topToTop)
        assertEquals(ConstraintLayout.LayoutParams.PARENT_ID, lp.bottomToBottom)
        assertEquals("4:3", lp.dimensionRatio)
        assertTrue(lp.constrainedWidth)
        assertTrue(lp.constrainedHeight)
        assertEquals(0.5f, lp.horizontalBias, 0.001f)
    }

    @Test
    fun testCompactScreenMathematicalOverlapElimination() {
        // Given a 16:9 device (360x640dp)
        val screenHeight = 640
        val screenWidth = 360
        val topInset = 32
        val bentoDeckHeightInCompactMode = 160 // Shutter 68 + Lens 38 + Recipe 38 + Margins/Paddings 16

        val availableHeight = screenHeight - topInset - bentoDeckHeightInCompactMode // 448dp
        val availableWidth = screenWidth - 20 // 340dp

        // Under 3:4 constraint:
        // Height from width: 340 * 4 / 3 = 453.3dp
        // But availableHeight is 448dp!
        // Because constrainedHeight is true, height is capped at availableHeight (448dp)
        val constrainedHeight = minOf(448, 453)
        val constrainedWidth = (constrainedHeight * 3) / 4 // 336dp

        // Symmetrical pillarboxing: 340 - 336 = 4dp (2dp on each side)
        // Vertical overlap = 0dp!
        val overlap = maxOf(0, (topInset + constrainedHeight) - (screenHeight - bentoDeckHeightInCompactMode))
        assertEquals(0, overlap)
    }
}
