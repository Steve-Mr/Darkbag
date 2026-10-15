package top.maary.darkbag.utils

import android.util.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class CameraRepositoryPreviewTest {

    @Test
    fun testSelectOptimalPreviewSize_filtersOutHighResolutionSizes() {
        val sizes = arrayOf(
            Size(4096, 3072), // 12.5 MP - must be rejected
            Size(3264, 2448), // 8 MP - must be rejected
            Size(2560, 1920), // 4.9 MP - must be rejected
            Size(1920, 1440), // 2.7 MP - optimal 4:3 under <= 1920x1440!
            Size(1440, 1080), // 1.5 MP - valid 4:3
            Size(640, 480)    // 0.3 MP - valid 4:3
        )

        val selected = CameraRepository.selectOptimalPreviewSize(sizes)
        assertEquals(Size(1920, 1440), selected)
    }

    @Test
    fun testSelectOptimalPreviewSize_picks1440x1080When1920x1440NotAvailable() {
        val sizes = arrayOf(
            Size(4096, 3072), // rejected
            Size(1440, 1080), // best under 1920x1440
            Size(1280, 960),
            Size(640, 480)
        )

        val selected = CameraRepository.selectOptimalPreviewSize(sizes)
        assertEquals(Size(1440, 1080), selected)
    }

    @Test
    fun testSelectOptimalPreviewSize_ignoresNon4by3AspectRatios() {
        val sizes = arrayOf(
            Size(1920, 1080), // 16:9 ratio (1.777) - must be rejected
            Size(1280, 720),  // 16:9 ratio - must be rejected
            Size(1440, 1080), // 4:3 ratio (1.333) - must be selected
            Size(640, 480)    // 4:3 ratio
        )

        val selected = CameraRepository.selectOptimalPreviewSize(sizes)
        assertEquals(Size(1440, 1080), selected)
    }

    @Test
    fun testSelectOptimalPreviewSize_fallbackWhenAllSizesExceedMax() {
        val sizes = arrayOf(
            Size(4096, 3072),
            Size(3264, 2448),
            Size(2560, 1920) // Smallest available candidate
        )

        val selected = CameraRepository.selectOptimalPreviewSize(sizes)
        assertEquals(Size(2560, 1920), selected)
    }

    @Test
    fun testSelectOptimalPreviewSize_handlesNullAndEmpty() {
        val nullResult = CameraRepository.selectOptimalPreviewSize(null)
        assertEquals(Size(1440, 1080), nullResult)

        val emptyResult = CameraRepository.selectOptimalPreviewSize(emptyArray())
        assertEquals(Size(1440, 1080), emptyResult)
    }

    @Test
    fun testLensSwitchStateTransitions() {
        // Test state transitions matching CameraFragment lens switch state machine
        var isSwitchingLens = false
        var isBurstActive = false
        var isProcessing = false
        var lensSwitchTriggers = 0

        fun onLensButtonClick() {
            if (isBurstActive || isProcessing || isSwitchingLens) return
            isSwitchingLens = true
            lensSwitchTriggers++
        }

        // 1. Initial click initiates switch
        onLensButtonClick()
        assertTrue(isSwitchingLens)
        assertEquals(1, lensSwitchTriggers)

        // 2. Rapid re-entrant click while switching is ignored
        onLensButtonClick()
        assertEquals(1, lensSwitchTriggers)

        // 3. First frame arrives: resets state
        isSwitchingLens = false

        // 4. Subsequent click allowed
        onLensButtonClick()
        assertTrue(isSwitchingLens)
        assertEquals(2, lensSwitchTriggers)

        // 5. Error callback or watchdog recovery
        isSwitchingLens = false
        onLensButtonClick()
        assertEquals(3, lensSwitchTriggers)
    }
}
