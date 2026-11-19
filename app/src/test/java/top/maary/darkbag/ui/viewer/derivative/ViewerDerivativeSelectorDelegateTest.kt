package top.maary.darkbag.ui.viewer.derivative

import android.net.Uri
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.maary.darkbag.models.ImageGroup

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ViewerDerivativeSelectorDelegateTest {

    private lateinit var delegate: ViewerDerivativeSelectorDelegate

    @Before
    fun setUp() {
        delegate = ViewerDerivativeSelectorDelegate()
    }

    @Test
    fun testGetDerivativeUrisCombinesJpgAndMp4DerivativesWithoutDuplicates() {
        val jpg1 = Uri.parse("content://media/1.jpg")
        val jpg2 = Uri.parse("content://media/2.jpg")
        val mp4 = Uri.parse("content://media/1.mp4")
        val mp4Deriv = Uri.parse("content://media/2.mp4")

        val group = ImageGroup(
            baseName = "TEST_GROUP",
            jpgUri = jpg1,
            derivativeJpgUris = listOf(jpg1, jpg2),
            mp4VideoUri = mp4,
            derivativeMp4Uris = listOf(mp4, mp4Deriv)
        )

        val derivatives = delegate.getDerivativeUris(group)
        assertEquals(4, derivatives.size)
        assertEquals(listOf(jpg1, jpg2, mp4, mp4Deriv), derivatives)
    }

    @Test
    fun testCycleDerivativeVersionCyclesThroughAllDerivatives() {
        val jpg1 = Uri.parse("content://media/1.jpg")
        val jpg2 = Uri.parse("content://media/2.jpg")
        val jpg3 = Uri.parse("content://media/3.jpg")

        val group = ImageGroup(
            baseName = "CYCLE_TEST",
            jpgUri = jpg1,
            derivativeJpgUris = listOf(jpg1, jpg2, jpg3)
        )

        assertEquals(0, delegate.getSelectedDerivativeIndex(group.baseName))
        assertEquals(jpg1, delegate.getActiveDerivativeUri(group))

        val step1 = delegate.cycleDerivativeVersion(group)
        assertNotNull(step1)
        assertEquals(1, step1!!.first)
        assertEquals(jpg2, step1.second)
        assertEquals(1, delegate.getSelectedDerivativeIndex(group.baseName))
        assertEquals(jpg2, delegate.getActiveDerivativeUri(group))

        val step2 = delegate.cycleDerivativeVersion(group)
        assertNotNull(step2)
        assertEquals(2, step2!!.first)
        assertEquals(jpg3, step2.second)
        assertEquals(2, delegate.getSelectedDerivativeIndex(group.baseName))

        // Cycles back to 0
        val step3 = delegate.cycleDerivativeVersion(group)
        assertNotNull(step3)
        assertEquals(0, step3!!.first)
        assertEquals(jpg1, step3.second)
        assertEquals(0, delegate.getSelectedDerivativeIndex(group.baseName))
    }

    @Test
    fun testCycleDerivativeVersionReturnsNullIfFewerThanTwoDerivatives() {
        val jpg1 = Uri.parse("content://media/1.jpg")
        val group = ImageGroup(
            baseName = "SINGLE_TEST",
            jpgUri = jpg1
        )
        assertNull(delegate.cycleDerivativeVersion(group))
    }

    @Test
    fun testIsMp4ActiveCorrectlyIdentifiesMp4Derivatives() {
        val jpg1 = Uri.parse("content://media/1.jpg")
        val mp4 = Uri.parse("content://media/1.mp4")

        val group = ImageGroup(
            baseName = "MP4_TEST",
            jpgUri = jpg1,
            derivativeMp4Uris = listOf(mp4)
        )

        assertFalse(delegate.isMp4Active(group, isRawSelected = false))

        delegate.setSelectedDerivativeIndex(group.baseName, 1)
        assertTrue(delegate.isMp4Active(group, isRawSelected = false))

        // If RAW is selected, isMp4Active should be false
        assertFalse(delegate.isMp4Active(group, isRawSelected = true))
    }
}
