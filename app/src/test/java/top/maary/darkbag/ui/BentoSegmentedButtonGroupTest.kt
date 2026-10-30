package top.maary.darkbag.ui

import android.content.Context
import android.graphics.RectF
import android.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.button.MaterialButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.maary.darkbag.R

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class BentoSegmentedButtonGroupTest {

    private lateinit var context: Context
    private lateinit var group: BentoSegmentedButtonGroup
    private lateinit var btn1: MaterialButton
    private lateinit var btn2: MaterialButton
    private lateinit var btn3: MaterialButton

    @Before
    fun setUp() {
        val appCtx = ApplicationProvider.getApplicationContext<Context>()
        context = ContextThemeWrapper(appCtx, R.style.AppTheme)

        group = BentoSegmentedButtonGroup(context)
        btn1 = MaterialButton(context).apply { id = 101; text = "P&S" }
        btn2 = MaterialButton(context).apply { id = 102; text = "FILM" }
        btn3 = MaterialButton(context).apply { id = 103; text = "PRO" }

        group.addView(btn1)
        group.addView(btn2)
        group.addView(btn3)
    }

    @Test
    fun testConcentricShapes_noZeroCorners() {
        val testBounds = RectF(0f, 0f, 100f, 100f)
        val density = context.resources.displayMetrics.density
        val activeExpected = 10f * density
        val inactiveExpected = 14f * density

        group.check(btn2.id)
        group.enforceConcentricShapes()

        // btn2 is active -> 10dp on all 4 corners
        assertEquals(activeExpected, btn2.shapeAppearanceModel.topLeftCornerSize.getCornerSize(testBounds), 0.1f)
        assertEquals(activeExpected, btn2.shapeAppearanceModel.topRightCornerSize.getCornerSize(testBounds), 0.1f)
        assertEquals(activeExpected, btn2.shapeAppearanceModel.bottomLeftCornerSize.getCornerSize(testBounds), 0.1f)
        assertEquals(activeExpected, btn2.shapeAppearanceModel.bottomRightCornerSize.getCornerSize(testBounds), 0.1f)

        // btn1 and btn3 are inactive -> 14dp on all 4 corners (never 0dp right angles!)
        assertEquals(inactiveExpected, btn1.shapeAppearanceModel.topLeftCornerSize.getCornerSize(testBounds), 0.1f)
        assertEquals(inactiveExpected, btn1.shapeAppearanceModel.topRightCornerSize.getCornerSize(testBounds), 0.1f)
        assertNotEquals(0f, btn1.shapeAppearanceModel.topRightCornerSize.getCornerSize(testBounds))

        assertEquals(inactiveExpected, btn3.shapeAppearanceModel.topLeftCornerSize.getCornerSize(testBounds), 0.1f)
        assertEquals(inactiveExpected, btn3.shapeAppearanceModel.topRightCornerSize.getCornerSize(testBounds), 0.1f)
        assertNotEquals(0f, btn3.shapeAppearanceModel.topLeftCornerSize.getCornerSize(testBounds))
    }

    @Test
    fun testSelectionChange_morphsCornersDynamically() {
        val testBounds = RectF(0f, 0f, 100f, 100f)
        val density = context.resources.displayMetrics.density
        val activeExpected = 10f * density
        val inactiveExpected = 14f * density

        group.check(btn3.id)
        group.enforceConcentricShapes()

        // btn3 becomes active
        assertEquals(activeExpected, btn3.shapeAppearanceModel.topLeftCornerSize.getCornerSize(testBounds), 0.1f)
        // btn2 returns to inactive
        assertEquals(inactiveExpected, btn2.shapeAppearanceModel.topLeftCornerSize.getCornerSize(testBounds), 0.1f)
    }
}
