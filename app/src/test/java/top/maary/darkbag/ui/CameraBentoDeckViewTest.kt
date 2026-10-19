package top.maary.darkbag.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.button.MaterialButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import top.maary.darkbag.R
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CameraBentoDeckViewTest {

    private lateinit var themedContext: Context

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        themedContext = android.view.ContextThemeWrapper(app, top.maary.darkbag.R.style.AppTheme)
    }

    @Test
    fun testStyleModeSwitchButtonRestingAndActive() {
        val button = MaterialButton(themedContext)
        val density = themedContext.resources.displayMetrics.density

        // Normal mode (resting): cornerRadius 18dp (concentric outer corner block)
        CameraBentoDeckView.styleModeSwitchButton(button, isSpecialMode = false)
        assertEquals((18f * density).roundToInt(), button.cornerRadius)
        assertEquals(0, button.strokeWidth)

        // Special mode: cornerRadius 12dp (squircle morph with 1.5dp stroke)
        CameraBentoDeckView.styleModeSwitchButton(button, isSpecialMode = true)
        assertEquals((12f * density).roundToInt(), button.cornerRadius)
        assertEquals((1.5f * density).roundToInt(), button.strokeWidth)
    }

    @Test
    fun testStyleBentoLensButton() {
        val button = MaterialButton(themedContext)
        val density = themedContext.resources.displayMetrics.density

        // Inactive: cornerRadius 14dp resting
        CameraBentoDeckView.styleBentoLensButton(button, isActive = false, isDigitalCrop = false)
        assertEquals((14f * density).roundToInt(), button.cornerRadius)
        assertEquals(0, button.strokeWidth)

        // Active physical: cornerRadius 12dp squircle morph
        CameraBentoDeckView.styleBentoLensButton(button, isActive = true, isDigitalCrop = false)
        assertEquals((12f * density).roundToInt(), button.cornerRadius)
        assertEquals((1.5f * density).roundToInt(), button.strokeWidth)

        // Active digital crop: cornerRadius 12dp squircle morph
        CameraBentoDeckView.styleBentoLensButton(button, isActive = true, isDigitalCrop = true)
        assertEquals((12f * density).roundToInt(), button.cornerRadius)
        assertEquals((1.5f * density).roundToInt(), button.strokeWidth)
    }

    @Test
    fun testStyleBentoToggleTile() {
        val button = MaterialButton(themedContext)
        val density = themedContext.resources.displayMetrics.density

        // Inactive: cornerRadius 14dp resting
        CameraBentoDeckView.styleBentoToggleTile(button, isActive = false)
        assertEquals((14f * density).roundToInt(), button.cornerRadius)
        assertEquals(0, button.strokeWidth)

        // Active: cornerRadius 12dp squircle morph
        CameraBentoDeckView.styleBentoToggleTile(button, isActive = true)
        assertEquals((12f * density).roundToInt(), button.cornerRadius)
    }

    @Test
    fun testFormatRecipeLabel() {
        assertEquals("ClassicChrome", CameraBentoDeckView.formatRecipeLabel("ClassicChrome.cube"))
        assertEquals("KodakPortra", CameraBentoDeckView.formatRecipeLabel("KodakPortra"))
        assertEquals("None", CameraBentoDeckView.formatRecipeLabel(null))
        assertEquals("None", CameraBentoDeckView.formatRecipeLabel("None"))
    }

    @Test
    fun testStyleBentoProPill() {
        val button = MaterialButton(themedContext)
        val density = themedContext.resources.displayMetrics.density

        // Inactive (Auto parameter, dial closed): 14dp radius, 1dp stroke
        CameraBentoDeckView.styleBentoProPill(button, isTabActive = false, isValueManual = false)
        assertEquals((14f * density).roundToInt(), button.cornerRadius)
        assertEquals((1f * density).roundToInt(), button.strokeWidth)

        // Active / Tab Selected (dial open): 10dp squircle radius, 1.5dp stroke
        CameraBentoDeckView.styleBentoProPill(button, isTabActive = true, isValueManual = false)
        assertEquals((10f * density).roundToInt(), button.cornerRadius)
        assertEquals((1.5f * density).roundToInt(), button.strokeWidth)

        // Active takes precedence even if isValueManual = true
        CameraBentoDeckView.styleBentoProPill(button, isTabActive = true, isValueManual = true)
        assertEquals((10f * density).roundToInt(), button.cornerRadius)
        assertEquals((1.5f * density).roundToInt(), button.strokeWidth)

        // Manual Override (locked to manual, dial closed): 12dp radius, 1.5dp stroke
        CameraBentoDeckView.styleBentoProPill(button, isTabActive = false, isValueManual = true)
        assertEquals((12f * density).roundToInt(), button.cornerRadius)
        assertEquals((1.5f * density).roundToInt(), button.strokeWidth)
    }

    @Test
    fun testStyleBentoProLink() {
        val button = MaterialButton(themedContext)
        val density = themedContext.resources.displayMetrics.density

        // Unlinked: 14dp radius, 1dp stroke
        CameraBentoDeckView.styleBentoProLink(button, isLinked = false)
        assertEquals((14f * density).roundToInt(), button.cornerRadius)
        assertEquals((1f * density).roundToInt(), button.strokeWidth)

        // Linked: 14dp radius, 1.5dp stroke
        CameraBentoDeckView.styleBentoProLink(button, isLinked = true)
        assertEquals((14f * density).roundToInt(), button.cornerRadius)
        assertEquals((1.5f * density).roundToInt(), button.strokeWidth)
    }

    @Test
    fun testFormatProTelemetry() {
        val telemetry = CameraBentoDeckView.formatProTelemetry("ISO", "100", isTabActive = false, isValueManual = false, context = themedContext)
        assertEquals("ISO\n100", telemetry.toString())

        val spanned = telemetry as android.text.Spanned
        val spans = spanned.getSpans(0, spanned.length, Any::class.java)
        org.junit.Assert.assertTrue(spans.isNotEmpty())

        val sizeSpans = spanned.getSpans(0, spanned.length, android.text.style.AbsoluteSizeSpan::class.java)
        assertEquals(2, sizeSpans.size)

        val styleSpans = spanned.getSpans(0, spanned.length, android.text.style.StyleSpan::class.java)
        assertEquals(2, styleSpans.size)
    }

    @Test
    fun testResolveScreenTier() {
        // 16:9 aspect ratio (compact)
        assertEquals(
            CameraBentoDeckView.BentoScreenTier.COMPACT,
            CameraBentoDeckView.BentoScreenTier.resolve(1.77f, 640)
        )
        // Short screen (< 720dp) even if ratio is 2.0 (compact)
        assertEquals(
            CameraBentoDeckView.BentoScreenTier.COMPACT,
            CameraBentoDeckView.BentoScreenTier.resolve(2.0f, 700)
        )
        // 18:9 aspect ratio standard (ratio 2.0f, maxDp 740)
        assertEquals(
            CameraBentoDeckView.BentoScreenTier.STANDARD,
            CameraBentoDeckView.BentoScreenTier.resolve(2.0f, 740)
        )
        // 19.5:9 aspect ratio standard (ratio 2.16f, maxDp 800)
        assertEquals(
            CameraBentoDeckView.BentoScreenTier.STANDARD,
            CameraBentoDeckView.BentoScreenTier.resolve(2.16f, 800)
        )
        // Tall flagship screen (ratio 2.22f, maxDp 880)
        assertEquals(
            CameraBentoDeckView.BentoScreenTier.TALL,
            CameraBentoDeckView.BentoScreenTier.resolve(2.22f, 880)
        )
    }

    @Test
    fun testBentoDimensionsValues() {
        val compactDims = CameraBentoDeckView.BentoDimensions(
            shutterRowDp = 68,
            lensRowDp = 38,
            recipeRowDp = 38,
            proBarDp = 38,
            dialWheelDp = 38,
            cardPaddingDp = 5,
            rowMarginDp = 3
        )
        assertEquals(68, compactDims.shutterRowDp)
        assertEquals(38, compactDims.lensRowDp)
        assertEquals(38, compactDims.recipeRowDp)
        assertEquals(38, compactDims.proBarDp)
        assertEquals(38, compactDims.dialWheelDp)
        assertEquals(5, compactDims.cardPaddingDp)
        assertEquals(3, compactDims.rowMarginDp)

        val standardDims = CameraBentoDeckView.BentoDimensions(
            shutterRowDp = 78,
            lensRowDp = 48,
            recipeRowDp = 48,
            proBarDp = 44,
            dialWheelDp = 44,
            cardPaddingDp = 8,
            rowMarginDp = 5
        )
        assertEquals(78, standardDims.shutterRowDp)
        assertEquals(48, standardDims.lensRowDp)
        assertEquals(48, standardDims.recipeRowDp)
        assertEquals(44, standardDims.proBarDp)
        assertEquals(44, standardDims.dialWheelDp)
        assertEquals(8, standardDims.cardPaddingDp)
        assertEquals(5, standardDims.rowMarginDp)

        val tallDims = CameraBentoDeckView.BentoDimensions(
            shutterRowDp = 88,
            lensRowDp = 54,
            recipeRowDp = 54,
            proBarDp = 48,
            dialWheelDp = 46,
            cardPaddingDp = 10,
            rowMarginDp = 6
        )
        assertEquals(88, tallDims.shutterRowDp)
        assertEquals(54, tallDims.lensRowDp)
        assertEquals(54, tallDims.recipeRowDp)
        assertEquals(48, tallDims.proBarDp)
        assertEquals(46, tallDims.dialWheelDp)
        assertEquals(10, tallDims.cardPaddingDp)
        assertEquals(6, tallDims.rowMarginDp)
    }

    @Test
    fun testApplyDynamicRowHeightsOnLayoutViews() {
        val root = com.google.android.material.card.MaterialCardView(themedContext).apply {
            id = R.id.bottom_island_card
        }
        val proBar = android.widget.LinearLayout(themedContext).apply {
            id = R.id.pro_info_bar_card
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0
            )
        }
        val filmRow = android.widget.LinearLayout(themedContext).apply {
            id = R.id.bento_film_row
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0
            )
        }
        val lensRow = android.widget.LinearLayout(themedContext).apply {
            id = R.id.lens_control_row
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0
            )
        }
        val shutterRow = android.widget.LinearLayout(themedContext).apply {
            id = R.id.bento_shutter_row
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0
            )
        }
        val dialWheel = android.view.View(themedContext).apply {
            id = R.id.dial_wheel_view
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0
            )
        }
        val photoCard = android.view.View(themedContext).apply {
            id = R.id.photo_view_card
            layoutParams = android.widget.LinearLayout.LayoutParams(100, 100)
        }
        val rightStack = android.view.View(themedContext).apply {
            id = R.id.bento_right_stack
            layoutParams = android.widget.LinearLayout.LayoutParams(100, 100)
        }
        val captureBtn = android.view.View(themedContext).apply {
            id = R.id.camera_capture_button
            layoutParams = android.widget.LinearLayout.LayoutParams(100, 100)
        }
        root.addView(proBar)
        root.addView(dialWheel)
        root.addView(filmRow)
        root.addView(lensRow)
        root.addView(shutterRow)
        root.addView(photoCard)
        root.addView(rightStack)
        root.addView(captureBtn)

        CameraBentoDeckView.applyDynamicRowHeights(root)

        val density = themedContext.resources.displayMetrics.density
        val dims = CameraBentoDeckView.getBentoDimensions(themedContext.resources)
        val tier = CameraBentoDeckView.resolveScreenTier(themedContext.resources)

        assertEquals((dims.proBarDp * density).roundToInt(), proBar.layoutParams.height)
        assertEquals((dims.recipeRowDp * density).roundToInt(), filmRow.layoutParams.height)
        assertEquals((dims.lensRowDp * density).roundToInt(), lensRow.layoutParams.height)
        assertEquals((dims.shutterRowDp * density).roundToInt(), shutterRow.layoutParams.height)
        assertEquals((dims.dialWheelDp * density).roundToInt(), dialWheel.layoutParams.height)

        val expectedPhotoWidthDp = when (tier) {
            CameraBentoDeckView.BentoScreenTier.COMPACT -> 66
            CameraBentoDeckView.BentoScreenTier.STANDARD -> 72
            CameraBentoDeckView.BentoScreenTier.TALL -> 76
        }
        assertEquals((expectedPhotoWidthDp * density).roundToInt(), photoCard.layoutParams.width)

        val expectedStackWidthDp = when (tier) {
            CameraBentoDeckView.BentoScreenTier.COMPACT -> 92
            CameraBentoDeckView.BentoScreenTier.STANDARD -> 98
            CameraBentoDeckView.BentoScreenTier.TALL -> 102
        }
        assertEquals((expectedStackWidthDp * density).roundToInt(), rightStack.layoutParams.width)

        // 8. Bento Tofu Block: shutter button retains MATCH_PARENT x MATCH_PARENT instead of being shrunk to a small square
        assertEquals(android.view.ViewGroup.LayoutParams.MATCH_PARENT, captureBtn.layoutParams.width)
        assertEquals(android.view.ViewGroup.LayoutParams.MATCH_PARENT, captureBtn.layoutParams.height)

        val expectedPadding = (dims.cardPaddingDp * density).roundToInt()
        assertEquals(expectedPadding, root.contentPaddingLeft)
        assertEquals(expectedPadding, root.contentPaddingTop)
        assertEquals(expectedPadding, root.contentPaddingRight)
        assertEquals(expectedPadding, root.contentPaddingBottom)
    }

    @Test
    fun testBentoShutterTofuBlockMaintainsMatchParentAndStretchingContainer() {
        val root = com.google.android.material.card.MaterialCardView(themedContext).apply {
            id = R.id.bottom_island_card
        }
        val shutterRow = android.widget.LinearLayout(themedContext).apply {
            id = R.id.bento_shutter_row
            orientation = android.widget.LinearLayout.HORIZONTAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0
            )
        }
        val photoCard = android.view.View(themedContext).apply {
            id = R.id.photo_view_card
            layoutParams = android.widget.LinearLayout.LayoutParams(100, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val shutterContainer = android.widget.FrameLayout(themedContext).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                0,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                1.0f
            )
        }
        val captureBtn = ExpressiveShutterButton(themedContext).apply {
            id = R.id.camera_capture_button
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        shutterContainer.addView(captureBtn)

        val rightStack = android.view.View(themedContext).apply {
            id = R.id.bento_right_stack
            layoutParams = android.widget.LinearLayout.LayoutParams(100, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
        }

        shutterRow.addView(photoCard)
        shutterRow.addView(shutterContainer)
        shutterRow.addView(rightStack)
        root.addView(shutterRow)

        CameraBentoDeckView.applyDynamicRowHeights(root)

        val density = themedContext.resources.displayMetrics.density
        val dims = CameraBentoDeckView.getBentoDimensions(themedContext.resources)

        // Shutter row height set according to tier
        assertEquals((dims.shutterRowDp * density).roundToInt(), shutterRow.layoutParams.height)

        // FrameLayout container retains full stretch (height = MATCH_PARENT, weight = 1f)
        val containerLp = shutterContainer.layoutParams as android.widget.LinearLayout.LayoutParams
        assertEquals(android.view.ViewGroup.LayoutParams.MATCH_PARENT, containerLp.height)
        assertEquals(1.0f, containerLp.weight, 0.001f)

        // Bento Tofu Block: capture button fills match_parent x match_parent
        assertEquals(android.view.ViewGroup.LayoutParams.MATCH_PARENT, captureBtn.layoutParams.width)
        assertEquals(android.view.ViewGroup.LayoutParams.MATCH_PARENT, captureBtn.layoutParams.height)

        // Verify button is NOT forced into a small square (e.g. 60dp, 68dp, 72dp)
        assertNotEquals((60 * density).roundToInt(), captureBtn.layoutParams.width)
        assertNotEquals((68 * density).roundToInt(), captureBtn.layoutParams.width)
        assertNotEquals((72 * density).roundToInt(), captureBtn.layoutParams.width)
    }

    @Test
    fun testApplyDynamicRowHeightsInLandscapePreservesCircularShutterButton() {
        // In landscape layout, bottom_island_card does NOT contain bento_shutter_row
        val root = com.google.android.material.card.MaterialCardView(themedContext).apply {
            id = R.id.bottom_island_card
        }
        val circularCaptureBtn = ExpressiveShutterButton(themedContext).apply {
            id = R.id.camera_capture_button
            layoutParams = android.view.ViewGroup.LayoutParams(200, 200)
        }
        root.addView(circularCaptureBtn)

        CameraBentoDeckView.applyDynamicRowHeights(root)

        // Capture button fixed size must be preserved and not overwritten to MATCH_PARENT
        assertEquals(200, circularCaptureBtn.layoutParams.width)
        assertEquals(200, circularCaptureBtn.layoutParams.height)
    }
}
