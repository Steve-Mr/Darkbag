package top.maary.darkbag.ui.viewer.lens

import android.net.Uri
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.maary.darkbag.models.ImageGroup
import top.maary.darkbag.models.MultiCameraLensItem

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ViewerLensSelectorDelegateTest {

    private lateinit var delegate: ViewerLensSelectorDelegate

    @Before
    fun setUp() {
        delegate = ViewerLensSelectorDelegate()
    }

    @Test
    fun testGetMultiCameraLensesUsesExplicitLensesIfProvided() {
        val lens0 = MultiCameraLensItem("0.5x", 0.5f, Uri.parse("content://0.5.jpg"), Uri.parse("content://0.5.dng"))
        val lens1 = MultiCameraLensItem("1.0x", 1.0f, Uri.parse("content://1.0.jpg"), Uri.parse("content://1.0.dng"))

        val group = ImageGroup(
            baseName = "MULTI_TEST",
            isMultiCamera = true,
            multiCameraLenses = listOf(lens0, lens1)
        )

        val lenses = delegate.getMultiCameraLenses(group)
        assertEquals(2, lenses.size)
        assertEquals(lens0, lenses[0])
        assertEquals(lens1, lenses[1])
    }

    @Test
    fun testGetMultiCameraLensesParsesFromUrisFallback() {
        val jpg0 = Uri.parse("content://media/101_MULTI_0.6x.jpg")
        val dng0 = Uri.parse("content://saf/101_MULTI_0.6x.dng")
        val jpg1 = Uri.parse("content://media/102_MULTI_2.0x.jpg")
        val dng1 = Uri.parse("content://saf/102_MULTI_2.0x.dng")

        val group = ImageGroup(
            baseName = "MULTI_FALLBACK",
            isMultiCamera = true,
            multiJpgUris = listOf(jpg0, jpg1),
            multiDngUris = listOf(dng0, dng1)
        )

        val lenses = delegate.getMultiCameraLenses(group)
        assertEquals(2, lenses.size)
        assertEquals(0.6f, lenses[0].multiplier, 0.01f)
        assertEquals(2.0f, lenses[1].multiplier, 0.01f)
    }

    @Test
    fun testLensSelectionMapAndActiveLens() {
        val lens0 = MultiCameraLensItem("1x", 1.0f, Uri.parse("content://1.jpg"), null)
        val lens1 = MultiCameraLensItem("3x", 3.0f, Uri.parse("content://3.jpg"), null)

        val group = ImageGroup(
            baseName = "LENS_SELECTION",
            isMultiCamera = true,
            multiCameraLenses = listOf(lens0, lens1)
        )

        assertEquals(0, delegate.getSelectedLensIndex(group.baseName))
        assertEquals(lens0, delegate.getActiveLens(group))

        delegate.setSelectedLensIndex(group.baseName, 1)
        assertEquals(1, delegate.getSelectedLensIndex(group.baseName))
        assertEquals(lens1, delegate.getActiveLens(group))
    }

    @Test
    fun testGetMultiCameraLensesReturnsEmptyForSingleCamera() {
        val group = ImageGroup(
            baseName = "SINGLE_CAMERA",
            isMultiCamera = false,
            jpgUri = Uri.parse("content://test.jpg")
        )
        assertTrue(delegate.getMultiCameraLenses(group).isEmpty())
        assertNull(delegate.getActiveLens(group))
    }
}
