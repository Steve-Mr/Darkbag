package top.maary.darkbag.fragments

import android.util.Size
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class SettingsFragmentTest {

    @Test
    fun testRawVideoResolutionOptionsAndDefault() {
        assertEquals(listOf("Max Native", "4K UHD", "1080p"), SettingsFragment.RAW_VIDEO_RESOLUTION_OPTIONS)
        assertEquals("Max Native", SettingsFragment.DEFAULT_RAW_VIDEO_RESOLUTION)
    }

    @Test
    fun testSelectRawVideoSize_MaxNative() {
        val sizes = arrayOf(
            Size(1920, 1080),
            Size(3840, 2160),
            Size(4000, 3000),
            Size(1280, 720)
        )
        val selected = SettingsFragment.selectRawVideoSize("Max Native", sizes)
        assertEquals(Size(4000, 3000), selected)

        val emptySelected = SettingsFragment.selectRawVideoSize("Max Native", emptyArray())
        assertEquals(Size(4000, 3000), emptySelected)
    }

    @Test
    fun testSelectRawVideoSize_4KUHD_Exact() {
        val sizes = arrayOf(
            Size(1920, 1080),
            Size(3840, 2160),
            Size(4000, 3000)
        )
        val selected = SettingsFragment.selectRawVideoSize("4K UHD", sizes)
        assertEquals(Size(3840, 2160), selected)
    }

    @Test
    fun testSelectRawVideoSize_4KUHD_Closest16x9() {
        val sizes = arrayOf(
            Size(1920, 1080),
            Size(4096, 2304), // 16:9 near 4K
            Size(4000, 3000)
        )
        val selected = SettingsFragment.selectRawVideoSize("4K UHD", sizes)
        assertEquals(Size(4096, 2304), selected)
    }

    @Test
    fun testSelectRawVideoSize_4KUHD_FallbackClosestArea() {
        // No 16:9 aspect sizes available (all 4:3)
        val sizes = arrayOf(
            Size(2000, 1500), // 3,000,000 px
            Size(3200, 2400), // 7,680,000 px (closest to 3840*2160 = 8,294,400)
            Size(4000, 3000)  // 12,000,000 px
        )
        val selected = SettingsFragment.selectRawVideoSize("4K UHD", sizes)
        assertEquals(Size(3200, 2400), selected)
    }

    @Test
    fun testSelectRawVideoSize_1080p_Exact() {
        val sizes = arrayOf(
            Size(1920, 1080),
            Size(3840, 2160),
            Size(4000, 3000)
        )
        val selected = SettingsFragment.selectRawVideoSize("1080p", sizes)
        assertEquals(Size(1920, 1080), selected)
    }

    @Test
    fun testSelectRawVideoSize_1080p_Closest16x9UnderWidth() {
        val sizes = arrayOf(
            Size(1280, 720), // 16:9 width <= 1920
            Size(3840, 2160),
            Size(4000, 3000)
        )
        val selected = SettingsFragment.selectRawVideoSize("1080p", sizes)
        assertEquals(Size(1280, 720), selected)
    }

    @Test
    fun testSelectRawVideoSize_1080p_FallbackClosestArea() {
        // No 16:9 aspect sizes available (all 4:3)
        val sizes = arrayOf(
            Size(1600, 1200), // 1,920,000 px (closest to 1920*1080 = 2,073,600)
            Size(3200, 2400),
            Size(4000, 3000)
        )
        val selected = SettingsFragment.selectRawVideoSize("1080p", sizes)
        assertEquals(Size(1600, 1200), selected)
    }

    @Test
    fun testSelectRawVideoSize_UnknownPreferenceFallsBackToMaxNative() {
        val sizes = arrayOf(
            Size(1920, 1080),
            Size(4000, 3000)
        )
        val selected = SettingsFragment.selectRawVideoSize("Unknown", sizes)
        assertEquals(Size(4000, 3000), selected)
    }
}
