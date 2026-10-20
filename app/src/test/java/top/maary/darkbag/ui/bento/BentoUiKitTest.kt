package top.maary.darkbag.ui.bento

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.maary.darkbag.R
import top.maary.darkbag.modes.CaptureMode
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BentoUiKitTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.setTheme(R.style.AppTheme)
    }

    @Test
    fun testBentoContainerRoleMapping() {
        assertEquals(BentoContainerRole.TERTIARY_CONTAINER, BentoContainerRole.fromCaptureMode(CaptureMode.HALF_FRAME_SBS))
        assertEquals(BentoContainerRole.TERTIARY_CONTAINER, BentoContainerRole.fromCaptureMode(CaptureMode.HALF_FRAME_TB))
        assertEquals(BentoContainerRole.SECONDARY_CONTAINER, BentoContainerRole.fromCaptureMode(CaptureMode.MULTI_CAMERA))
        assertEquals(BentoContainerRole.SURFACE_CONTAINER, BentoContainerRole.fromCaptureMode(CaptureMode.NORMAL))

        val resolved = BentoContainerRole.TERTIARY_CONTAINER.resolveColor(context)
        assertNotEquals(0, resolved)
    }

    @Test
    fun testBentoTrayCardDefaultsAndModeReactivity() {
        val card = BentoTrayCard(context)
        val density = context.resources.displayMetrics.density

        // Default constraints: strokeWidth = 0dp, radius = 26dp, padding = 10dp
        assertEquals(0, card.strokeWidth)
        assertEquals((26f * density).roundToInt(), card.radius.roundToInt())
        assertEquals((10f * density).toInt(), card.contentPaddingLeft)
        assertEquals((10f * density).toInt(), card.contentPaddingTop)

        // Mode reactivity: half-frame mode updates background color and maintains 0dp stroke
        card.setCaptureMode(CaptureMode.HALF_FRAME_SBS, animated = false)
        assertEquals(0, card.strokeWidth)
        assertNotNull(card.cardBackgroundColor)

        // Multi-cam mode
        card.setCaptureMode(CaptureMode.MULTI_CAMERA, animated = false)
        assertEquals(0, card.strokeWidth)

        // Container role update
        card.setContainerRole(BentoContainerRole.ERROR_CONTAINER, animated = false)
        assertEquals(0, card.strokeWidth)
    }

    @Test
    fun testBentoTileButtonSquircleMorphing() {
        val button = BentoTileButton(context)
        val density = context.resources.displayMetrics.density

        // Default: 14dp radius
        assertEquals((14 * density).toInt(), button.cornerRadius)

        // Active squircle morphing: 10dp radius
        button.setActiveSquircle(true)
        assertEquals((10 * density).toInt(), button.cornerRadius)

        // Return to inactive: 14dp radius
        button.setActiveSquircle(false)
        assertEquals((14 * density).toInt(), button.cornerRadius)

        // Telemetry formatting
        button.setTelemetry("TEST", "Active")
        assertTrue(button.text.contains("TEST"))
    }

    @Test
    fun testBentoSwitchTile() {
        val switchTile = BentoSwitchTile(context)
        val density = context.resources.displayMetrics.density

        assertEquals((14f * density).roundToInt(), switchTile.radius.roundToInt())
        assertFalse(switchTile.isChecked)

        switchTile.isChecked = true
        assertTrue(switchTile.isChecked)
        assertTrue(switchTile.switchView.isChecked)

        switchTile.setTitle("Optical Stabilization")
        switchTile.setBadge("OIS", BentoSwitchTile.BentoBadgeStyle.PRIMARY)
    }

    @Test
    fun testBentoRowLayoutGutterBalancing() {
        val row = BentoRowLayout(context)
        val density = context.resources.displayMetrics.density
        val halfGutterPx = (4f * density).toInt()

        val child1 = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, (48 * density).toInt(), 1f)
        }
        val child2 = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, (48 * density).toInt(), 1f)
        }

        row.addView(child1)
        row.addView(child2)

        val lp1 = child1.layoutParams as LinearLayout.LayoutParams
        val lp2 = child2.layoutParams as LinearLayout.LayoutParams

        assertEquals(0, lp1.marginStart)
        assertEquals(halfGutterPx, lp1.marginEnd)

        assertEquals(halfGutterPx, lp2.marginStart)
        assertEquals(0, lp2.marginEnd)

        // Adaptive height test
        row.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0)
        row.applyAdaptiveHeight(BentoRowLayout.RowScreenTier.COMPACT)
        assertEquals((46 * density).toInt(), row.layoutParams.height)

        row.applyAdaptiveHeight(BentoRowLayout.RowScreenTier.STANDARD)
        assertEquals((54 * density).toInt(), row.layoutParams.height)
    }
}
