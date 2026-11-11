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
import top.maary.darkbag.fragments.SettingsFragment
import top.maary.darkbag.repository.DarkbagPreferences
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
            hdrFusionMode = SettingsFragment.HDR_FUSION_SABRE,
            burstProcessingStrategy = SettingsFragment.BURST_STRATEGY_AGGRESSIVE,
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
        delegate.updateStartupCards(binding, SettingsFragment.STARTUP_CAMERA, enableCamera = true, enablePlayground = true)
        assertEquals(true, binding.switchEnableCamera.isChecked)
        assertEquals(true, binding.switchEnablePlayground.isChecked)

        delegate.updateStartupCards(binding, SettingsFragment.STARTUP_PLAYGROUND, enableCamera = true, enablePlayground = true)
        assertEquals(true, binding.switchEnableCamera.isChecked)
    }
}
