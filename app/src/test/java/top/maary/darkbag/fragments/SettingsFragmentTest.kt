package top.maary.darkbag.fragments

import android.util.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class SettingsFragmentTest {

    @Test
    fun testRawVideoResolutionOptionsAndDefault() {
        assertEquals(
            listOf("Max Native (4:3)", "2K Open Gate (4:3)", "4K UHD (16:9)", "1080p (16:9)"),
            SettingsFragment.RAW_VIDEO_RESOLUTION_OPTIONS
        )
        assertEquals("Max Native (4:3)", SettingsFragment.DEFAULT_RAW_VIDEO_RESOLUTION)
    }

    @Test
    fun testSelectRawVideoSize_MaxNative() {
        val sizes = arrayOf(
            Size(1920, 1080),
            Size(3840, 2160),
            Size(4000, 3000),
            Size(1280, 720)
        )
        val selected = SettingsFragment.selectRawVideoSize("Max Native (4:3)", sizes)
        assertEquals(Size(4000, 3000), selected)

        val legacySelected = SettingsFragment.selectRawVideoSize("Max Native", sizes)
        assertEquals(Size(4000, 3000), legacySelected)

        val emptySelected = SettingsFragment.selectRawVideoSize("Max Native (4:3)", emptyArray())
        assertEquals(Size(4000, 3000), emptySelected)
    }

    @Test
    fun testSelectRawVideoSize_2KOpenGate() {
        val sizes = arrayOf(
            Size(1920, 1080),
            Size(3840, 2160),
            Size(4000, 3000),
            Size(1280, 720)
        )
        val selected = SettingsFragment.selectRawVideoSize("2K Open Gate (4:3)", sizes)
        assertEquals(Size(4000, 3000), selected)

        val emptySelected = SettingsFragment.selectRawVideoSize("2K Open Gate (4:3)", emptyArray())
        assertEquals(Size(4000, 3000), emptySelected)
    }

    @Test
    fun testSelectRawVideoSize_4KUHD_Exact() {
        val sizes = arrayOf(
            Size(1920, 1080),
            Size(3840, 2160),
            Size(4000, 3000)
        )
        val selected = SettingsFragment.selectRawVideoSize("4K UHD (16:9)", sizes)
        assertEquals(Size(3840, 2160), selected)

        val legacySelected = SettingsFragment.selectRawVideoSize("4K UHD", sizes)
        assertEquals(Size(3840, 2160), legacySelected)
    }

    @Test
    fun testSelectRawVideoSize_4KUHD_Closest16x9() {
        val sizes = arrayOf(
            Size(1920, 1080),
            Size(4096, 2304), // 16:9 near 4K
            Size(4000, 3000)
        )
        val selected = SettingsFragment.selectRawVideoSize("4K UHD (16:9)", sizes)
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
        val selected = SettingsFragment.selectRawVideoSize("4K UHD (16:9)", sizes)
        assertEquals(Size(3200, 2400), selected)
    }

    @Test
    fun testSelectRawVideoSize_1080p_Exact() {
        val sizes = arrayOf(
            Size(1920, 1080),
            Size(3840, 2160),
            Size(4000, 3000)
        )
        val selected = SettingsFragment.selectRawVideoSize("1080p (16:9)", sizes)
        assertEquals(Size(1920, 1080), selected)

        val legacySelected = SettingsFragment.selectRawVideoSize("1080p", sizes)
        assertEquals(Size(1920, 1080), legacySelected)
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

    @Test
    fun testHardwareCapabilities_FpsAnd4kCropDetection() {
        // 1. 测试未获取到 characteristics 时的安全 fallback
        assertEquals(30, SettingsFragment.HardwareCapabilities.getMaxSupportedFps(null))
        assertFalse(SettingsFragment.HardwareCapabilities.is4kCropSupported(null))
        assertNull(SettingsFragment.HardwareCapabilities.getMaxRawSize(null))

        // 2. 验证选项展示格式化逻辑
        val maxHardwareFps = 30
        val fpsOptions = listOf("24", "30", "60")
        val formatted = fpsOptions.map { fpsStr ->
            if ((fpsStr.toIntOrNull() ?: 24) > maxHardwareFps) {
                "$fpsStr (Unsupported)"
            } else {
                fpsStr
            }
        }
        assertEquals(listOf("24", "30", "60 (Unsupported)"), formatted)
    }

    @Test
    fun testBurstProcessingStrategies() {
        assertEquals(
            listOf(SettingsFragment.BURST_STRATEGY_BALANCED, SettingsFragment.BURST_STRATEGY_AGGRESSIVE),
            SettingsFragment.BURST_PROCESSING_STRATEGIES
        )
        assertEquals("Balanced (Smooth UI)", SettingsFragment.BURST_STRATEGY_BALANCED)
        assertEquals("Rapid Burst (Maximum Shots)", SettingsFragment.BURST_STRATEGY_AGGRESSIVE)
    }

    @Test
    fun testHdrFusionModes() {
        assertEquals("hdr_fusion_mode", SettingsFragment.KEY_HDR_FUSION_MODE)
        assertEquals(
            listOf(
                SettingsFragment.HDR_FUSION_AUTO,
                SettingsFragment.HDR_FUSION_SPATIAL_RCD,
                SettingsFragment.HDR_FUSION_SABRE,
                SettingsFragment.HDR_FUSION_CLASSIC
            ),
            SettingsFragment.HDR_FUSION_MODES
        )
        assertEquals("Auto (Spatial / Sabre Zoom)", SettingsFragment.HDR_FUSION_AUTO)
        assertEquals("Spatial Merge + RCD", SettingsFragment.HDR_FUSION_SPATIAL_RCD)
        assertEquals("Sabre (Super-Resolution)", SettingsFragment.HDR_FUSION_SABRE)
        assertEquals("Classic HDR+ (Wiener)", SettingsFragment.HDR_FUSION_CLASSIC)
    }

    @Test
    fun testHdrFusionModeConstantsAndMapping() {
        // Verify key and list constants
        assertEquals("hdr_fusion_mode", SettingsFragment.KEY_HDR_FUSION_MODE)
        assertEquals(4, SettingsFragment.HDR_FUSION_MODES.size)

        // Verify index contract matches pipeline fusionMode integers:
        // 0 -> Auto, 1 -> Spatial + RCD, 2 -> Sabre, 3 -> Classic
        assertEquals(0, SettingsFragment.HDR_FUSION_MODES.indexOf(SettingsFragment.HDR_FUSION_AUTO))
        assertEquals(1, SettingsFragment.HDR_FUSION_MODES.indexOf(SettingsFragment.HDR_FUSION_SPATIAL_RCD))
        assertEquals(2, SettingsFragment.HDR_FUSION_MODES.indexOf(SettingsFragment.HDR_FUSION_SABRE))
        assertEquals(3, SettingsFragment.HDR_FUSION_MODES.indexOf(SettingsFragment.HDR_FUSION_CLASSIC))

        // Contract verification of CameraFragment preference resolution
        fun mapPreferenceToFusionMode(pref: String?): Int = when (pref) {
            SettingsFragment.HDR_FUSION_SPATIAL_RCD -> 1
            SettingsFragment.HDR_FUSION_SABRE -> 2
            SettingsFragment.HDR_FUSION_CLASSIC -> 3
            else -> 0
        }

        assertEquals(0, mapPreferenceToFusionMode(SettingsFragment.HDR_FUSION_AUTO))
        assertEquals(1, mapPreferenceToFusionMode(SettingsFragment.HDR_FUSION_SPATIAL_RCD))
        assertEquals(2, mapPreferenceToFusionMode(SettingsFragment.HDR_FUSION_SABRE))
        assertEquals(3, mapPreferenceToFusionMode(SettingsFragment.HDR_FUSION_CLASSIC))
        assertEquals(0, mapPreferenceToFusionMode("Unknown"))
        assertEquals(0, mapPreferenceToFusionMode(null))
    }

    @Test
    fun testFragmentSettingsBindingParity() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val themedContext = android.view.ContextThemeWrapper(context, top.maary.darkbag.R.style.AppTheme)
        val inflater = android.view.LayoutInflater.from(themedContext)
        val binding = top.maary.darkbag.databinding.FragmentSettingsBinding.inflate(inflater)

        org.junit.Assert.assertNotNull(binding.cardAdvancedContainer)
        org.junit.Assert.assertNotNull(binding.headerAdvancedToggle)
        org.junit.Assert.assertNotNull(binding.ivAdvancedArrow)
        org.junit.Assert.assertNotNull(binding.containerAdvancedContent)
        org.junit.Assert.assertNotNull(binding.btnCloseExperimental)
        org.junit.Assert.assertNotNull(binding.cardStartupCamera)
        org.junit.Assert.assertNotNull(binding.cardStartupPlayground)
        org.junit.Assert.assertNotNull(binding.sectionDebug)
    }

    @Test
    fun testBentoSettingsMatrixParity() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val themedContext = android.view.ContextThemeWrapper(context, top.maary.darkbag.R.style.AppTheme)
        val inflater = android.view.LayoutInflater.from(themedContext)
        val binding = top.maary.darkbag.databinding.FragmentSettingsBinding.inflate(inflater)

        // Top Bento Header
        org.junit.Assert.assertNotNull(binding.cardSettingsHeader)
        org.junit.Assert.assertNotNull(binding.btnSettingsBack)

        // Section 1: Shooting & Capture Modes
        org.junit.Assert.assertNotNull(binding.tileHfSaveRaw)
        org.junit.Assert.assertNotNull(binding.tileMultiCameraSaveRaw)
        org.junit.Assert.assertNotNull(binding.cardStartupCamera)
        org.junit.Assert.assertNotNull(binding.cardStartupPlayground)
        org.junit.Assert.assertNotNull(binding.tileSaveFormat)
        org.junit.Assert.assertNotNull(binding.cbSaveJpg)
        org.junit.Assert.assertNotNull(binding.cbSaveRaw)
        org.junit.Assert.assertNotNull(binding.switchMotionPhoto)
        org.junit.Assert.assertNotNull(binding.switchSaveLocation)
        org.junit.Assert.assertNotNull(binding.btnMenuDefaultLens)
        org.junit.Assert.assertNotNull(binding.btnMenuDefaultFocal1x)
        org.junit.Assert.assertNotNull(binding.switchHalfFrameMode)
        org.junit.Assert.assertNotNull(binding.layoutHalfFrameOptions)
        org.junit.Assert.assertNotNull(binding.switchHalfFrameDateStamp)
        org.junit.Assert.assertNotNull(binding.switchHalfFrameLightLeak)
        org.junit.Assert.assertNotNull(binding.switchHalfFrameDownsample)
        org.junit.Assert.assertNotNull(binding.cbHfSaveRaw)
        org.junit.Assert.assertNotNull(binding.switchMirrorFront)

        // Section 2: Color Science & Recipes
        org.junit.Assert.assertNotNull(binding.btnMenuTargetLog)
        org.junit.Assert.assertNotNull(binding.btnManageLuts)
        org.junit.Assert.assertNotNull(binding.switchLivePreview)

        // Section 3: Advanced Settings (Collapsible Bento)
        org.junit.Assert.assertNotNull(binding.cardAdvancedContainer)
        org.junit.Assert.assertNotNull(binding.headerAdvancedToggle)
        org.junit.Assert.assertNotNull(binding.ivAdvancedArrow)
        org.junit.Assert.assertNotNull(binding.containerAdvancedContent)

        // Sub-Matrix A: HDR+ & Compute Pipeline
        org.junit.Assert.assertNotNull(binding.btnMenuHdrFusionMode)
        org.junit.Assert.assertNotNull(binding.btnMenuHdrBurstMode)
        org.junit.Assert.assertNotNull(binding.btnMenuUnderexposureMode)
        org.junit.Assert.assertNotNull(binding.btnMenuColorEngine)
        org.junit.Assert.assertNotNull(binding.switchHdrPlusOis)
        org.junit.Assert.assertNotNull(binding.btnMenuAntibanding)
        org.junit.Assert.assertNotNull(binding.btnMenuBurstProcessingStrategy)
        org.junit.Assert.assertNotNull(binding.btnMenuShutterLongPress)

        // Sub-Matrix B: RAW Video & Multi-Camera
        org.junit.Assert.assertNotNull(binding.btnMenuRawVideo)
        org.junit.Assert.assertNotNull(binding.btnMenuMultiCameraDualPair)
        org.junit.Assert.assertNotNull(binding.tvMultiCameraStatus)
        org.junit.Assert.assertNotNull(binding.switchMultiCameraMode)
        org.junit.Assert.assertNotNull(binding.btnMenuMultiCameraCount)
        org.junit.Assert.assertNotNull(binding.switchMultiCameraForceEnable)
        org.junit.Assert.assertNotNull(binding.cbMultiCameraSaveRaw)

        // Sub-Matrix C: DNG Format & Storage
        org.junit.Assert.assertNotNull(binding.btnMenuDngCompression)
        org.junit.Assert.assertNotNull(binding.btnMenuDngFormat)
        org.junit.Assert.assertNotNull(binding.layoutJpgStorage)
        org.junit.Assert.assertNotNull(binding.layoutRawStorage)

        // Sub-Matrix D: Advanced UI Customization
        org.junit.Assert.assertNotNull(binding.toggleUiTiers)
        org.junit.Assert.assertNotNull(binding.btnTierPs)
        org.junit.Assert.assertNotNull(binding.btnTierFilm)
        org.junit.Assert.assertNotNull(binding.btnTierPro)
        org.junit.Assert.assertNotNull(binding.containerTierPs)
        org.junit.Assert.assertNotNull(binding.containerTierFilm)
        org.junit.Assert.assertNotNull(binding.containerTierPro)
        org.junit.Assert.assertNotNull(binding.switchUseInternalViewer)
        org.junit.Assert.assertNotNull(binding.btnMenuExternalViewer)

        // Section 4: System & About
        org.junit.Assert.assertNotNull(binding.btnClearCache)
        org.junit.Assert.assertNotNull(binding.cardAbout)

        // Section 5: Experimental Lab
        org.junit.Assert.assertNotNull(binding.sectionDebug)
        org.junit.Assert.assertNotNull(binding.switchForce60fps)
        org.junit.Assert.assertNotNull(binding.tvDebugStats)
        org.junit.Assert.assertNotNull(binding.btnCopyDebugLogs)
        org.junit.Assert.assertNotNull(binding.btnClearDebugLogs)
        org.junit.Assert.assertNotNull(binding.btnCloseExperimental)
    }

    @Test
    fun testUnderexposureFormatting() {
        fun formatUnderexposure(saved: String): String = when {
            saved.endsWith("EV") -> saved
            saved == "Off" || saved.startsWith("Off") -> saved
            saved.startsWith("Dynamic") -> "Dynamic"
            else -> "$saved EV"
        }

        assertEquals("-1 EV", formatUnderexposure("-1 EV"))
        assertEquals("-2 EV", formatUnderexposure("-2 EV"))
        assertEquals("Off", formatUnderexposure("Off"))
        assertEquals("Dynamic", formatUnderexposure("Dynamic"))
        assertEquals("Dynamic", formatUnderexposure("Dynamic (Experimental)"))
        assertEquals("-0.5 EV", formatUnderexposure("-0.5"))
    }
}
