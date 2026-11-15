package top.maary.darkbag.fragments.onboarding

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.maary.darkbag.R
import top.maary.darkbag.databinding.FragmentPermissionsBinding
import top.maary.darkbag.repository.PreferenceKeys
import top.maary.darkbag.repository.SettingsRepository

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class OnboardingStepBinderTest {

    private lateinit var themedContext: Context
    private lateinit var binding: FragmentPermissionsBinding
    private lateinit var repo: SettingsRepository
    private var finishedOnboarding = false
    private var lastLutPickerLog: String? = null
    private var locationPermissionRequestCount = 0

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        SettingsRepository.resetForTest(app)
        repo = SettingsRepository.getInstance(app).apply { awaitHydratedBlocking() }
        themedContext = android.view.ContextThemeWrapper(app, R.style.AppTheme)
        binding = FragmentPermissionsBinding.inflate(LayoutInflater.from(themedContext))
        finishedOnboarding = false
        lastLutPickerLog = null
        locationPermissionRequestCount = 0
    }

    private fun createBinder(): OnboardingStepBinder {
        return OnboardingStepBinder(
            binding = binding,
            settingsRepository = repo,
            callbacks = object : OnboardingStepBinder.Callbacks {
                override fun requestCameraPermission() {}
                override fun requestNotificationPermission() {}
                override fun requestAudioPermission() {}
                override fun requestLocationPermissions(permissions: Array<String>) {
                    locationPermissionRequestCount++
                }
                override fun requestBatchStartupPermissions(permissions: Array<String>) {}
                override fun launchLutPicker(selectedLog: String) {
                    lastLutPickerLog = selectedLog
                }
                override fun onFinishOnboarding() {
                    finishedOnboarding = true
                }
            }
        )
    }

    @Test
    fun stepNavigationUpdatesVisibilityBackButtonAndCompletion() {
        val binder = createBinder()
        binder.setupAllSteps()
        binder.goToStep(1)

        assertEquals(1, binder.currentStep)
        assertEquals(View.VISIBLE, binding.containerStep1.visibility)
        assertEquals(View.GONE, binding.containerStep2.visibility)
        assertEquals(View.INVISIBLE, binding.btnHeaderBack.visibility)

        binder.goToStep(2)
        assertEquals(2, binder.currentStep)
        assertEquals(View.GONE, binding.containerStep1.visibility)
        assertEquals(View.VISIBLE, binding.containerStep2.visibility)
        assertEquals(View.VISIBLE, binding.btnHeaderBack.visibility)

        binding.btnHeaderBack.performClick()
        assertEquals(1, binder.currentStep)

        binder.goToStep(4)
        binding.btnPrimaryCta.performClick()
        assertTrue(finishedOnboarding)
    }

    @Test
    fun startupOpticsTierAndShutterHoldActionsUpdateRepository() {
        val binder = createBinder()
        binder.setupAllSteps()
        binder.goToStep(1)

        binding.cardStartupPlayground.performClick()
        assertEquals(PreferenceKeys.STARTUP_PLAYGROUND, repo.current().defaultStartupPage)

        binding.tileFocal28.performClick()
        assertEquals("28mm", repo.current().defaultFocal1xName)

        binding.btnLogSlog3.performClick()
        binding.btnImportLut.performClick()
        assertEquals("S-Log3", lastLutPickerLog)

        binding.tileTierPro.performClick()
        assertEquals("PRO", repo.current().uiTier)
        assertTrue(repo.current().enableManualControls)
        assertTrue(repo.current().expFocusPeaking)

        binding.tileTierPs.performClick()
        assertEquals("PS", repo.current().uiTier)
        assertFalse(repo.current().enableManualControls)
        assertFalse(repo.current().showLutSwitcher)

        binding.tileShutterHoldAction.performClick()
        assertEquals(PreferenceKeys.SHUTTER_LONG_PRESS_RAW_VIDEO, repo.current().shutterLongPressAction)
    }

    @Test
    fun hydrationAndRefreshPreservePersistedProTierAndSaveRawFalseWithoutSpuriousWrites() = runBlocking {
        repo.edit {
            this[PreferenceKeys.UI_TIER] = "PRO"
            this[PreferenceKeys.MANUAL_CONTROLS] = true
            this[PreferenceKeys.EXP_FOCUS_PEAKING] = true
            this[PreferenceKeys.SAVE_JPG] = true
            this[PreferenceKeys.SAVE_RAW] = false
            this[PreferenceKeys.MOTION_PHOTO] = true
            this[PreferenceKeys.ENABLE_LUT_PREVIEW] = false
            this[PreferenceKeys.SAVE_LOCATION] = true
        }
        repo.awaitPersisted()

        var writeEmissions = 0
        val collectJob = launch(Dispatchers.Unconfined) {
            repo.preferences.drop(1).collect {
                writeEmissions++
            }
        }

        val binder = createBinder()
        binder.setupAllSteps()
        binder.refreshStepsAfterHydration()
        binder.setSaveLocationChecked(true)
        binder.setSaveLocationChecked(false)
        repo.awaitPersisted()

        assertEquals("PRO", binder.selectedUiTier)
        assertEquals("PRO", repo.current().uiTier)
        assertTrue(repo.current().enableManualControls)
        assertTrue(repo.current().expFocusPeaking)
        assertTrue(repo.current().saveJpg)
        assertFalse(repo.current().saveRaw)
        assertTrue(binding.cbSaveJpg.isChecked)
        assertFalse(binding.cbSaveRaw.isChecked)
        assertTrue(binding.switchMotionPhoto.isChecked)
        assertFalse(binding.switchLivePreview.isChecked)
        assertEquals(0, locationPermissionRequestCount)
        assertEquals(0, writeEmissions)

        collectJob.cancel()
    }
}
