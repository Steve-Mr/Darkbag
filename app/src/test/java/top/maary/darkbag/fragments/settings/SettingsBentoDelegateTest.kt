package top.maary.darkbag.fragments.settings

import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.maary.darkbag.R
import top.maary.darkbag.databinding.FragmentSettingsBinding
import top.maary.darkbag.repository.DarkbagPreferences
import top.maary.darkbag.repository.PreferenceKeys
import top.maary.darkbag.ui.bento.BentoContainerRole
import top.maary.darkbag.utils.CameraRepository

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SettingsBentoDelegateTest {

    private lateinit var themedContext: ContextThemeWrapper
    private lateinit var binding: FragmentSettingsBinding
    private lateinit var cameraRepository: CameraRepository
    private lateinit var delegate: SettingsBentoDelegate

    @Before
    fun setup() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        themedContext = ContextThemeWrapper(app, R.style.AppTheme)
        val inflater = LayoutInflater.from(themedContext)
        binding = FragmentSettingsBinding.inflate(inflater)
        cameraRepository = CameraRepository(themedContext)
        delegate = SettingsBentoDelegate(themedContext, cameraRepository)
    }

    @Test
    fun testRenderPreferences_UpdatesTelemetryAndSwitches() {
        val testPrefs = DarkbagPreferences(
            targetLog = "Arri LogC3",
            activeLut = "TestLook.cube",
            saveJpg = true,
            saveRaw = true,
            enableLutPreview = true,
            hdrBurstCount = 6,
            hdrFusionMode = PreferenceKeys.HDR_FUSION_SABRE,
            burstProcessingStrategy = PreferenceKeys.BURST_STRATEGY_AGGRESSIVE,
            colorEngineMode = 2,
            motionPhotoEnabled = true,
            motionPhotoAudioEnabled = true,
            halfFrameMode = true,
            multiCameraModeEnabled = true,
            dngCompressionMode = 0
        )

        delegate.renderPreferences(binding, testPrefs)

        assertFalse(delegate.isRendering)
        assertTrue(binding.btnMenuTargetLog.text.contains("Arri LogC3"))
        assertTrue(binding.btnManageLuts.text.contains("TestLook"))
        assertTrue(binding.btnMenuHdrBurstMode.text.contains("6"))
        assertTrue(binding.cbSaveJpg.isChecked)
        assertTrue(binding.cbSaveRaw.isChecked)
        assertTrue(binding.switchMotionPhoto.isChecked)
        assertTrue(binding.switchMotionPhotoAudio.isChecked)
        assertTrue(binding.switchHalfFrameMode.isChecked)
        assertTrue(binding.switchMultiCameraMode.isChecked)
    }

    @Test
    fun testUpdateSaveFormatCardStyling() {
        delegate.updateSaveFormatCardStyling(binding, isJpg = true, isRaw = false)
        assertTrue(binding.tileFormatJpg.strokeWidth > 0)

        delegate.updateSaveFormatCardStyling(binding, isJpg = false, isRaw = true)
        assertTrue(binding.tileFormatRaw.strokeWidth > 0)
    }

    @Test
    fun testUpdateStartupCards() {
        delegate.updateStartupCards(binding, PreferenceKeys.STARTUP_CAMERA, enableCamera = true, enablePlayground = true)
        assertEquals(true, binding.switchEnableCamera.isChecked)
        assertEquals(true, binding.switchEnablePlayground.isChecked)

        delegate.updateStartupCards(binding, PreferenceKeys.STARTUP_PLAYGROUND, enableCamera = true, enablePlayground = true)
        assertEquals(true, binding.switchEnableCamera.isChecked)
    }

    @Test
    fun testZslBentoSubTray_WhenDisabled() {
        val prefs = DarkbagPreferences(
            debugEnabled = true,
            zslEnabled = false
        )
        delegate.renderPreferences(binding, prefs)

        assertEquals(themedContext.getString(R.string.badge_zsl_off), binding.badgeZslStatus.text)
        assertEquals(BentoContainerRole.SURFACE_CONTAINER_HIGH, binding.cardTrayExpZsl.containerRole)
        assertEquals(android.view.View.GONE, binding.switchZslAdaptive.visibility)
        assertEquals(android.view.View.GONE, binding.switchZslLazyRing.visibility)
        assertEquals(android.view.View.GONE, binding.cardZslDebug.visibility)
    }

    @Test
    fun testZslBentoSubTray_WhenArmed() {
        val prefs = DarkbagPreferences(
            debugEnabled = true,
            zslEnabled = true,
            zslRoute = "raw_ring",
            hdrPlusEnabled = true,
            hdrBurstCount = 5,
            hdrUnderexposureMode = "Dynamic (Experimental)"
        )
        delegate.renderPreferences(binding, prefs)

        assertEquals(themedContext.getString(R.string.badge_zsl_armed), binding.badgeZslStatus.text)
        assertEquals(BentoContainerRole.TERTIARY_CONTAINER, binding.cardTrayExpZsl.containerRole)
        assertEquals(0, binding.cardTrayExpZsl.strokeWidth)
        assertEquals(android.view.View.VISIBLE, binding.switchZslAdaptive.visibility)
        assertEquals(android.view.View.VISIBLE, binding.switchZslLazyRing.visibility)
        assertEquals(android.view.View.VISIBLE, binding.cardZslDebug.visibility)
        assertTrue(binding.switchZslAdaptive.isEnabled)
        assertTrue(binding.switchZslLazyRing.isEnabled)
        assertTrue(binding.tvZslStatus.text.contains("5/5"))
        assertTrue(binding.tvZslStatus.text.contains("-1.0 EV"))
    }

    @Test
    fun testZslBentoSubTray_WhenConflict_Force60Fps() {
        val prefs = DarkbagPreferences(
            debugEnabled = true,
            zslEnabled = true,
            force60Fps = true,
            zslRoute = "raw_ring"
        )
        delegate.renderPreferences(binding, prefs)

        assertEquals(themedContext.getString(R.string.badge_zsl_conflict), binding.badgeZslStatus.text)
        assertEquals(BentoContainerRole.SURFACE_CONTAINER_HIGH, binding.cardTrayExpZsl.containerRole)
        assertTrue(binding.cardTrayExpZsl.strokeWidth > 0)
        assertFalse(binding.switchZslAdaptive.isEnabled)
        assertFalse(binding.switchZslLazyRing.isEnabled)
        assertTrue(binding.tvZslStatus.text.contains(themedContext.getString(R.string.zsl_status_inactive_60fps)))
    }

    @Test
    fun testZslBentoSubTray_HalfFrameIsCompatibleAndDoesNotConflict() {
        val hfPrefs = DarkbagPreferences(
            debugEnabled = true,
            zslEnabled = true,
            halfFrameMode = true,
            activeCaptureMode = "half_frame_sbs",
            zslRoute = "raw_ring"
        )
        delegate.renderPreferences(binding, hfPrefs)
        assertEquals(themedContext.getString(R.string.badge_zsl_armed), binding.badgeZslStatus.text)
        assertEquals(BentoContainerRole.TERTIARY_CONTAINER, binding.cardTrayExpZsl.containerRole)
        assertEquals(0, binding.cardTrayExpZsl.strokeWidth)
        assertTrue(binding.switchZslAdaptive.isEnabled)
    }

    @Test
    fun testZslBentoSubTray_WhenConflict_MultiCameraActive() {
        val mcPrefs = DarkbagPreferences(
            debugEnabled = true,
            zslEnabled = true,
            multiCameraModeEnabled = true,
            activeCaptureMode = "multi_camera",
            zslRoute = "raw_ring"
        )
        delegate.renderPreferences(binding, mcPrefs)
        assertEquals(themedContext.getString(R.string.badge_zsl_conflict), binding.badgeZslStatus.text)
        assertEquals(BentoContainerRole.SURFACE_CONTAINER_HIGH, binding.cardTrayExpZsl.containerRole)
        assertTrue(binding.cardTrayExpZsl.strokeWidth > 0)
        assertTrue(binding.tvZslStatus.text.contains(themedContext.getString(R.string.zsl_status_inactive_multi_camera)))
    }

    @Test
    fun testMultiCameraBentoStylingAndOptionsVisibility() {
        // Multi-Camera enabled
        val mcEnabledPrefs = DarkbagPreferences(
            multiCameraModeEnabled = true
        )
        delegate.renderPreferences(binding, mcEnabledPrefs)
        assertEquals(android.view.View.VISIBLE, binding.layoutMultiCameraOptions.visibility)
        assertEquals(BentoContainerRole.SECONDARY_CONTAINER, binding.cardTrayAdvMulticam.containerRole)
        // On non-native multi-camera devices/environment, bypass switch stays visible
        assertEquals(android.view.View.VISIBLE, binding.switchMultiCameraForceEnable.visibility)

        // Multi-Camera disabled
        val mcDisabledPrefs = DarkbagPreferences(
            multiCameraModeEnabled = false
        )
        delegate.renderPreferences(binding, mcDisabledPrefs)
        assertEquals(android.view.View.GONE, binding.layoutMultiCameraOptions.visibility)
        assertEquals(BentoContainerRole.SURFACE_CONTAINER_HIGH, binding.cardTrayAdvMulticam.containerRole)
        // Force enable switch stays visible even when multiCameraModeEnabled is false, so user can unlock it
        assertEquals(android.view.View.VISIBLE, binding.switchMultiCameraForceEnable.visibility)
    }

    @Test
    fun testZslBentoSubTray_WhenMultiCameraDisabled_DoesNotConflictEvenIfActiveModeIsStale() {
        val stalePrefs = DarkbagPreferences(
            debugEnabled = true,
            zslEnabled = true,
            multiCameraModeEnabled = false,
            activeCaptureMode = "multi_camera",
            zslRoute = "raw_ring"
        )
        delegate.renderPreferences(binding, stalePrefs)
        assertEquals(themedContext.getString(R.string.badge_zsl_armed), binding.badgeZslStatus.text)
        assertEquals(BentoContainerRole.TERTIARY_CONTAINER, binding.cardTrayExpZsl.containerRole)
        assertEquals(0, binding.cardTrayExpZsl.strokeWidth)
        assertTrue(binding.switchZslAdaptive.isEnabled)
    }

    @Test
    fun testZslBentoSubTray_WhenHdrUnsupported() {
        val prefs = DarkbagPreferences(
            debugEnabled = true,
            zslEnabled = true,
            zslRoute = "raw_ring",
            hdrPlusEnabled = true,
            zslReason = "hdr_unsupported"
        )
        delegate.renderPreferences(binding, prefs)

        assertEquals(themedContext.getString(R.string.badge_zsl_hdr_incompat), binding.badgeZslStatus.text)
        assertEquals(BentoContainerRole.SURFACE_CONTAINER_HIGH, binding.cardTrayExpZsl.containerRole)
        assertTrue(binding.cardTrayExpZsl.strokeWidth > 0)
        assertFalse(binding.switchZslAdaptive.isEnabled)
        assertFalse(binding.switchZslLazyRing.isEnabled)
        assertTrue(binding.tvZslStatus.text.contains(themedContext.getString(R.string.zsl_status_inactive_hdr)))
    }

    @Test
    fun testZslBentoSubTray_WhenRouteUnavailable() {
        val prefs = DarkbagPreferences(
            debugEnabled = true,
            zslEnabled = true,
            zslRoute = "none"
        )
        delegate.renderPreferences(binding, prefs)

        assertEquals(themedContext.getString(R.string.badge_zsl_unavailable), binding.badgeZslStatus.text)
        assertEquals(BentoContainerRole.SURFACE_CONTAINER_HIGH, binding.cardTrayExpZsl.containerRole)
        assertEquals(0, binding.cardTrayExpZsl.strokeWidth)
        assertFalse(binding.switchZslAdaptive.isEnabled)
        assertFalse(binding.switchZslLazyRing.isEnabled)
        assertTrue(binding.tvZslStatus.text.contains(themedContext.getString(R.string.zsl_status_unavailable)))
    }
}
