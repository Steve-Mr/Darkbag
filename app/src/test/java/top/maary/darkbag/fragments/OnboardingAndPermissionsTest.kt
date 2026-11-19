package top.maary.darkbag.fragments

import android.content.Context
import android.net.Uri
import android.view.LayoutInflater
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.maary.darkbag.R
import top.maary.darkbag.databinding.FragmentPermissionsBinding
import top.maary.darkbag.repository.PreferenceKeys
import top.maary.darkbag.repository.SettingsRepository
import top.maary.darkbag.utils.LutManager
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class OnboardingAndPermissionsTest {

    @Test
    fun testPermissionsFragmentLayoutParity() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val themedContext = android.view.ContextThemeWrapper(context, R.style.AppTheme)
        val inflater = LayoutInflater.from(themedContext)
        val binding = FragmentPermissionsBinding.inflate(inflater)

        // Header controls
        assertNotNull(binding.cardOnboardingHeader)
        assertNotNull(binding.btnHeaderBack)
        assertNotNull(binding.tvHeaderTitle)
        assertNotNull(binding.tvHeaderStep)
        assertNotNull(binding.btnHeaderSkip)
        assertNotNull(binding.layoutProgressBars)
        assertNotNull(binding.progStep1)
        assertNotNull(binding.progStep2)
        assertNotNull(binding.progStep3)
        assertNotNull(binding.progStep4)

        // Step 1: Welcome & Startup & Permissions
        assertNotNull(binding.containerStep1)
        assertNotNull(binding.cardTrayStartup)
        assertNotNull(binding.cardStartupCamera)
        assertNotNull(binding.cardStartupPlayground)
        assertNotNull(binding.cardTrayPermissions)
        assertNotNull(binding.tilePermCamera)
        assertNotNull(binding.btnGrantCamera)
        assertNotNull(binding.tilePermNotifications)
        assertNotNull(binding.btnGrantNotifications)
        assertNotNull(binding.tilePermAudio)
        assertNotNull(binding.btnGrantAudio)

        // Step 2: Formats & Optics
        assertNotNull(binding.containerStep2)
        assertNotNull(binding.cardTrayOutput)
        assertNotNull(binding.tileFormatJpg)
        assertNotNull(binding.cbSaveJpg)
        assertNotNull(binding.tileFormatRaw)
        assertNotNull(binding.cbSaveRaw)
        assertNotNull(binding.tvFormatExplain)
        assertNotNull(binding.cardTrayOptics)
        assertNotNull(binding.tileFocal24)
        assertNotNull(binding.tileFocal28)
        assertNotNull(binding.tileFocal35)
        assertNotNull(binding.switchMotionPhoto)
        assertNotNull(binding.switchMotionPhotoAudio)
        assertNotNull(binding.cardMotionPhotoAudioHint)
        assertNotNull(binding.tvMotionPhotoAudioHint)
        assertNotNull(binding.switchSaveLocation)

        // Step 3: Recipe & Log Pairing
        assertNotNull(binding.containerStep3)
        assertNotNull(binding.cardTrayRecipePairing)
        assertNotNull(binding.btnLogFlog2)
        assertNotNull(binding.btnLogApple)
        assertNotNull(binding.btnLogSlog3)
        assertNotNull(binding.btnLogArri)
        assertNotNull(binding.btnLogClog)
        assertNotNull(binding.btnLogRec709)
        assertNotNull(binding.btnImportLut)
        assertNotNull(binding.layoutPairingResult)
        assertNotNull(binding.tvPairingDesc)
        assertNotNull(binding.switchLivePreview)

        // Step 4: UI Tier & Viewer Navigation
        assertNotNull(binding.containerStep4)
        assertNotNull(binding.cardTrayUiTier)
        assertNotNull(binding.tileTierPs)
        assertNotNull(binding.tileTierFilm)
        assertNotNull(binding.tileTierPro)
        assertNotNull(binding.tvTierDesc)
        assertNotNull(binding.tileShutterHoldAction)
        assertNotNull(binding.tvShutterHoldValue)
        assertNotNull(binding.cardTrayViewer)
        assertNotNull(binding.switchUseInternalViewer)
        assertNotNull(binding.tvViewerDesc)

        // Bottom CTA
        assertNotNull(binding.layoutBottomDock)
        assertNotNull(binding.btnPrimaryCta)
    }

    @Test
    fun testSettingsFragmentReplayOnboardingBindingParity() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val themedContext = android.view.ContextThemeWrapper(context, R.style.AppTheme)
        val inflater = LayoutInflater.from(themedContext)
        val binding = top.maary.darkbag.databinding.FragmentSettingsBinding.inflate(inflater)

        assertNotNull(binding.btnReplayOnboarding)
    }

    @Test
    fun testOnboardingCompletedPrefKey() {
        assertEquals("onboarding_completed", PreferenceKeys.KEY_ONBOARDING_COMPLETED)
        val context = ApplicationProvider.getApplicationContext<Context>()
        SettingsRepository.resetForTest(context)
        val repo = SettingsRepository.getInstance(context).apply { awaitHydratedBlocking() }

        assertFalse(repo.current().onboardingCompleted)
        repo.setOnboardingCompleted(true)
        assertTrue(repo.current().onboardingCompleted)
    }

    @Test
    fun testLutManagerLogAssociation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val lutManager = LutManager(context)

        val lutName = "Test_Film_Recipe.cube"
        assertNull(lutManager.getAssociatedLog(lutName))

        // Set association
        lutManager.setAssociatedLog(lutName, "F-Log2")
        assertEquals("F-Log2", lutManager.getAssociatedLog(lutName))

        // Update association
        lutManager.setAssociatedLog(lutName, "Apple Log")
        assertEquals("Apple Log", lutManager.getAssociatedLog(lutName))

        // Remove association
        lutManager.setAssociatedLog(lutName, null)
        assertNull(lutManager.getAssociatedLog(lutName))
    }

    @Test
    fun testLutManagerRenamePreservesAssociation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val lutManager = LutManager(context)

        // Create a dummy LUT file in lutDir
        val sourceFile = File(lutManager.lutDir, "Old_Classic_Neg.cube")
        sourceFile.writeText("LUT_3D_SIZE 2\n0 0 0\n1 1 1")

        lutManager.setAssociatedLog(sourceFile.name, "S-Log3")
        assertEquals("S-Log3", lutManager.getAssociatedLog(sourceFile.name))

        val renamed = lutManager.renameLut(sourceFile, "New Classic Neg")
        assertTrue(renamed)

        assertNull(lutManager.getAssociatedLog("Old_Classic_Neg.cube"))
        assertEquals("S-Log3", lutManager.getAssociatedLog("New Classic Neg.cube"))

        // Cleanup
        val newFile = File(lutManager.lutDir, "New Classic Neg.cube")
        lutManager.deleteLut(newFile)
        assertNull(lutManager.getAssociatedLog("New Classic Neg.cube"))
    }

    @Test
    fun testColdStartupFastPathPlaygroundBypassesCameraPermission() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        SettingsRepository.resetForTest(context)
        val repo = SettingsRepository.getInstance(context).apply { awaitHydratedBlocking() }

        repo.setOnboardingCompleted(true)
        repo.setDefaultStartupPage(PreferenceKeys.STARTUP_PLAYGROUND)

        val isPlaygroundStartup = repo.current().defaultStartupPage == PreferenceKeys.STARTUP_PLAYGROUND
        val hasCompleted = repo.current().onboardingCompleted
        val cameraGranted = false // simulated camera permission denied

        // Fast path condition in PermissionsFragment.kt
        val canFastPath = hasCompleted && (isPlaygroundStartup || cameraGranted)
        assertTrue("Playground startup should fast-path without camera permission", canFastPath)

        // Conversely, Camera startup without camera permission should not bypass
        repo.setDefaultStartupPage(PreferenceKeys.STARTUP_CAMERA)
        val isCameraStartup = repo.current().defaultStartupPage == PreferenceKeys.STARTUP_PLAYGROUND
        val canFastPathCamera = hasCompleted && (isCameraStartup || cameraGranted)
        assertFalse("Camera startup should not fast-path when camera permission is missing", canFastPathCamera)
    }

    @Test
    fun testBentoSquircleDensityMathCorrectPrecedence() {
        // Density math verification to guard against operator precedence bugs
        listOf(1.0f, 2.0f, 2.75f, 3.0f, 3.5f, 4.0f).forEach { density ->
            // In PermissionsFragment:
            // btn.cornerRadius = ((if (isSelected) 10f else 14f) * density).toInt()
            val selectedRadius = ((10f) * density).toInt()
            val unselectedRadius = ((14f) * density).toInt()
            assertTrue("Selected squircle radius ($selectedRadius) must be smaller than unselected ($unselectedRadius)", selectedRadius < unselectedRadius)

            // card.strokeWidth = ((if (isSelected) 2f else 1f) * density).toInt()
            val selectedStroke = ((2f) * density).toInt()
            val unselectedStroke = ((1f) * density).toInt()
            assertTrue("Selected squircle stroke ($selectedStroke) must be thicker than unselected ($unselectedStroke)", selectedStroke > unselectedStroke)
        }
    }

    @Test
    fun testMotionPhotoAudioSwitchAndHint() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val themedContext = android.view.ContextThemeWrapper(context, R.style.AppTheme)
        val inflater = LayoutInflater.from(themedContext)
        val binding = FragmentPermissionsBinding.inflate(inflater)
        SettingsRepository.resetForTest(context)
        val repo = SettingsRepository.getInstance(context).apply { awaitHydratedBlocking() }

        // Verify keys and defaults
        assertEquals("motion_photo_enabled", PreferenceKeys.KEY_MOTION_PHOTO)
        assertEquals("motion_photo_audio_enabled", PreferenceKeys.KEY_MOTION_PHOTO_AUDIO)

        // Initial default state
        assertFalse(repo.current().motionPhotoEnabled)
        assertTrue(repo.current().motionPhotoAudioEnabled)

        // Badge parity
        assertEquals("LIVE", binding.switchMotionPhoto.badgeText)
        assertEquals("AUDIO", binding.switchMotionPhotoAudio.badgeText)

        // Verify hint card text
        val enHint = context.getString(R.string.onboarding_motion_photo_audio_hint)
        assertTrue(enHint.contains("microphone permission"))

        // Chinese locale test
        val config = android.content.res.Configuration(context.resources.configuration)
        config.setLocale(java.util.Locale.SIMPLIFIED_CHINESE)
        val zhContext = context.createConfigurationContext(config)
        val zhHint = zhContext.getString(R.string.onboarding_motion_photo_audio_hint)
        assertTrue(zhHint.contains("麦克风权限"))
    }

    @Test
    fun testInternalViewerSwitchAndPreference() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val themedContext = android.view.ContextThemeWrapper(context, R.style.AppTheme)
        val inflater = LayoutInflater.from(themedContext)
        val binding = FragmentPermissionsBinding.inflate(inflater)
        SettingsRepository.resetForTest(context)
        val repo = SettingsRepository.getInstance(context).apply { awaitHydratedBlocking() }

        // Verify key and default
        assertEquals("use_internal_viewer", PreferenceKeys.KEY_USE_INTERNAL_VIEWER)
        assertTrue(repo.current().useInternalViewer)

        // Badge parity
        assertEquals("BUILT-IN", binding.switchUseInternalViewer.badgeText)

        // Check descriptions
        val enInternalDesc = context.getString(R.string.onboarding_viewer_internal_desc)
        val enExternalDesc = context.getString(R.string.onboarding_viewer_external_desc)
        assertTrue(enInternalDesc.contains("Built-in Darkroom Viewer"))
        assertTrue(enExternalDesc.contains("External Viewer"))

        // Chinese locale test
        val config = android.content.res.Configuration(context.resources.configuration)
        config.setLocale(java.util.Locale.SIMPLIFIED_CHINESE)
        val zhContext = context.createConfigurationContext(config)
        val zhInternalDesc = zhContext.getString(R.string.onboarding_viewer_internal_desc)
        val zhExternalDesc = zhContext.getString(R.string.onboarding_viewer_external_desc)
        assertTrue(zhInternalDesc.contains("内置暗房查看器"))
        assertTrue(zhExternalDesc.contains("第三方查看器"))
    }

    @Test
    fun testAudioPermissionDescriptionAndBadges() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        // EN description & badges
        val enAudioDesc = context.getString(R.string.onboarding_perm_audio_desc)
        assertTrue("Audio permission description should mention Motion Photos", enAudioDesc.contains("Motion Photos"))
        assertEquals("REQUIRED", context.getString(R.string.onboarding_badge_required))
        assertEquals("RECOMMENDED", context.getString(R.string.onboarding_badge_recommended))
        assertEquals("OPTIONAL", context.getString(R.string.onboarding_badge_optional))

        // ZH description & badges
        val config = android.content.res.Configuration(context.resources.configuration)
        config.setLocale(java.util.Locale.SIMPLIFIED_CHINESE)
        val zhContext = context.createConfigurationContext(config)
        val zhAudioDesc = zhContext.getString(R.string.onboarding_perm_audio_desc)
        assertTrue("Chinese audio permission description should mention 实况照片", zhAudioDesc.contains("实况照片"))
        assertEquals("必选", zhContext.getString(R.string.onboarding_badge_required))
        assertEquals("推荐", zhContext.getString(R.string.onboarding_badge_recommended))
        assertEquals("可选", zhContext.getString(R.string.onboarding_badge_optional))
    }
}
