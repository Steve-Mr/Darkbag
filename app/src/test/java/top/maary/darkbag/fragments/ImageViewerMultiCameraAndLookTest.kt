package top.maary.darkbag.fragments

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
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
import top.maary.darkbag.models.MultiCameraLensItem
import top.maary.darkbag.ui.CameraBentoDeckView

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ImageViewerMultiCameraAndLookTest {

    private lateinit var context: Context
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun testMultiCameraLensesAndFocalLengthSwitching() {
        val ultraJpg = Uri.parse("content://media/101_MULTI_0.6x.jpg")
        val ultraDng = Uri.parse("content://saf/101_MULTI_0.6x.dng")
        val wideJpg = Uri.parse("content://media/102_MULTI_1.0x.jpg")
        val wideDng = Uri.parse("content://saf/102_MULTI_1.0x.dng")
        val teleJpg = Uri.parse("content://media/103_MULTI_3.0x.jpg")
        val teleDng = Uri.parse("content://saf/103_MULTI_3.0x.dng")

        val lens0 = MultiCameraLensItem(lensTag = "0.6x", multiplier = 0.6f, jpgUri = ultraJpg, dngUri = ultraDng)
        val lens1 = MultiCameraLensItem(lensTag = "1.0x", multiplier = 1.0f, jpgUri = wideJpg, dngUri = wideDng)
        val lens2 = MultiCameraLensItem(lensTag = "3.0x", multiplier = 3.0f, jpgUri = teleJpg, dngUri = teleDng)

        val group = ImageGroup(
            baseName = "DBAG_20261017_120000",
            jpgUri = wideJpg,
            isMultiCamera = true,
            multiCameraLenses = listOf(lens0, lens1, lens2),
            multiJpgUris = listOf(ultraJpg, wideJpg, teleJpg),
            multiDngUris = listOf(ultraDng, wideDng, teleDng)
        )

        val adapter = ImageViewerAdapter(listOf(group), scope, context)
        val lenses = adapter.getMultiCameraLenses(group)
        assertEquals(3, lenses.size)
        assertEquals("0.6x", lenses[0].lensTag)
        assertEquals("1.0x", lenses[1].lensTag)
        assertEquals("3.0x", lenses[2].lensTag)

        // Default lens index is 0
        assertEquals(0, adapter.getSelectedLensIndex(0))

        var notifiedPos = -1
        var notifiedLensIdx = -1
        adapter.onMultiCameraLensChanged = { pos, idx ->
            notifiedPos = pos
            notifiedLensIdx = idx
        }

        // Switch to Tele lens (index 2)
        adapter.setSelectedLensIndex(0, 2)
        assertEquals(2, adapter.getSelectedLensIndex(0))
        assertEquals(0, notifiedPos)
        assertEquals(2, notifiedLensIdx)

        // Current URI should correspond to the Tele lens (JPG)
        val currentUri = adapter.getCurrentUri(0)
        assertEquals(teleJpg, currentUri)

        // Toggle format to DNG for the Tele lens
        adapter.cycleOrToggleFormat(0)
        assertEquals(ImageViewerAdapter.FORMAT_DNG, adapter.getSelectedFormat(group))
        assertEquals(teleDng, adapter.getCurrentUri(0))
    }

    @Test
    fun testMultiLookDerivativeCycleTelemetryAndWrapping() {
        val jpg1 = Uri.parse("content://media/300.jpg")
        val jpg2 = Uri.parse("content://media/301.jpg")
        val jpg3 = Uri.parse("content://media/302.jpg")

        val group = ImageGroup(
            baseName = "DBAG_20261017_150000",
            jpgUri = jpg1,
            derivativeJpgUris = listOf(jpg1, jpg2, jpg3)
        )

        val adapter = ImageViewerAdapter(listOf(group), scope, context)
        val derivatives = adapter.getDerivativeUris(group)
        assertEquals(3, derivatives.size)

        var lastDerivUri: Uri? = null
        adapter.onDerivativeVersionChanged = { _, uri -> lastDerivUri = uri }

        assertEquals(0, adapter.getSelectedDerivativeIndex(group.baseName))

        // Cycle 1: V1 -> V2
        adapter.cycleDerivativeVersion(0)
        assertEquals(1, adapter.getSelectedDerivativeIndex(group.baseName))
        assertEquals(jpg2, lastDerivUri)

        // Cycle 2: V2 -> V3
        adapter.cycleDerivativeVersion(0)
        assertEquals(2, adapter.getSelectedDerivativeIndex(group.baseName))
        assertEquals(jpg3, lastDerivUri)

        // Cycle 3: V3 -> V1 (wraparound)
        adapter.cycleDerivativeVersion(0)
        assertEquals(0, adapter.getSelectedDerivativeIndex(group.baseName))
        assertEquals(jpg1, lastDerivUri)
    }

    @Test
    fun testLookDotColorBadgesAndSpans() {
        val dotColors = ImageViewerFragment.LOOK_DOT_COLORS
        assertTrue("Look dot colors must contain at least 6 distinct colors", dotColors.size >= 6)

        val activeDerivIdx = 2
        val totalDerivatives = 3
        val dotColor = dotColors[activeDerivIdx % dotColors.size]

        val line1 = "LOOK"
        val line2 = "V${activeDerivIdx + 1} / V${totalDerivatives} ●"
        val formatted = CameraBentoDeckView.formatProTelemetry(
            label = line1,
            value = line2,
            isTabActive = false,
            isValueManual = true,
            context = context
        )

        val textStr = formatted.toString()
        assertTrue(textStr.contains("LOOK"))
        assertTrue(textStr.contains("V3 / V3 ●"))

        val dotIdx = textStr.indexOf("●")
        assertTrue(dotIdx >= 0)

        val spannable = formatted as SpannableStringBuilder
        spannable.setSpan(
            ForegroundColorSpan(dotColor),
            dotIdx,
            dotIdx + 1,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        val spans = spannable.getSpans(dotIdx, dotIdx + 1, ForegroundColorSpan::class.java)
        assertTrue("Must contain ForegroundColorSpan for the Look dot badge", spans.isNotEmpty())
        assertEquals(dotColor, spans.last().foregroundColor)
    }

    @Test
    fun testMultiCameraFormatSwitcherTelemetry() {
        val activeLensTag = "1.0x"
        val isRawSelected = false
        val line1 = "$activeLensTag · ${if (isRawSelected) "DNG" else "JPG"}"
        val line2 = "JPG ⇄ DNG"

        val formatted = CameraBentoDeckView.formatProTelemetry(
            label = line1,
            value = line2,
            isTabActive = isRawSelected,
            isValueManual = false,
            context = context
        )

        val text = formatted.toString()
        assertEquals("1.0x · JPG\nJPG ⇄ DNG", text)

        val collageText = CameraBentoDeckView.formatProTelemetry(
            label = "⊞ COLLAGE",
            value = "多摄拼图",
            isTabActive = true,
            isValueManual = false,
            context = context
        ).toString()
        assertEquals("⊞ COLLAGE\n多摄拼图", collageText)
    }

    @Test
    fun testAdaptiveBentoSlotRoutingCondition() {
        val standardGroup = ImageGroup(
            baseName = "DBAG_20261017_000001",
            jpgUri = Uri.parse("content://media/1.jpg"),
            dngUri = Uri.parse("content://saf/1.dng"),
            isMotionPhoto = false,
            isMultiCamera = false,
            derivativeJpgUris = emptyList()
        )

        // Case 1: Standard photo has no contextual addons -> compact single row slot!
        val stdShowMotionLive = !standardGroup.isMultiCamera && standardGroup.isMotionPhoto
        val stdHasContextualAddons = stdShowMotionLive
        assertFalse("Standard photo must not expand upper format row", stdHasContextualAddons)

        // Case 2: Multi-look photo alone uses compact Look button in Primary Action Row without expanding upper format row
        val multiLookGroup = standardGroup.copy(
            derivativeJpgUris = listOf(
                Uri.parse("content://media/1.jpg"),
                Uri.parse("content://media/2.jpg")
            )
        )
        val lookDerivatives = multiLookGroup.derivativeJpgUris
        val lookShowDerivCycle = !multiLookGroup.isMultiCamera && lookDerivatives.size >= 2
        val lookHasContextualAddons = !multiLookGroup.isMultiCamera && multiLookGroup.isMotionPhoto
        assertFalse("Multi-look alone uses compact mode in primary action row", lookHasContextualAddons)
        assertTrue("Multi-look displays compact cycle button", lookShowDerivCycle)

        // Case 3: Motion Photo expands upper format row!
        val motionGroup = standardGroup.copy(isMotionPhoto = true)
        val motionShowMotionLive = !motionGroup.isMultiCamera && motionGroup.isMotionPhoto
        val motionHasContextualAddons = motionShowMotionLive
        assertTrue("Motion photo must trigger upper format row addons", motionHasContextualAddons)

        // Case 4: Coexistence of Motion Photo and Multi-Look -> Both displayed simultaneously in upper row!
        val motionPlusLookGroup = multiLookGroup.copy(isMotionPhoto = true)
        val coexistenceMotionLive = !motionPlusLookGroup.isMultiCamera && motionPlusLookGroup.isMotionPhoto
        val coexistenceDerivCycle = !motionPlusLookGroup.isMultiCamera && motionPlusLookGroup.derivativeJpgUris.size >= 2
        val coexistenceHasUpperRow = coexistenceMotionLive
        assertTrue("Motion Photo + Looks expands upper format row", coexistenceHasUpperRow)
        assertTrue("Motion Photo button is active", coexistenceMotionLive)
        assertTrue("Look cycle button is active simultaneously in upper row", coexistenceDerivCycle)
    }

    @Test
    fun testLivePhotoBentoTelemetry() {
        val liveOnText = CameraBentoDeckView.formatProTelemetry(
            label = "LIVE PHOTO",
            value = "AUTO ON",
            isTabActive = true,
            isValueManual = true,
            context = context
        ).toString()
        assertEquals("LIVE PHOTO\nAUTO ON", liveOnText)

        val livePausedText = CameraBentoDeckView.formatProTelemetry(
            label = "LIVE PHOTO",
            value = "PAUSED",
            isTabActive = false,
            isValueManual = false,
            context = context
        ).toString()
        assertEquals("LIVE PHOTO\nPAUSED", livePausedText)
    }
}
