package top.maary.darkbag.viewmodel

import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.maary.darkbag.repository.DarkbagPreferences

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class SettingsViewModelTest {

    private lateinit var app: android.app.Application
    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext<android.app.Application>()
        viewModel = SettingsViewModel(app)
    }

    @Test
    fun testAndroidViewModelFactoryConstructorReflection() {
        // Verify reflection constructor exists with (Application) parameter
        val constructor = SettingsViewModel::class.java.getConstructor(android.app.Application::class.java)
        assertNotNull(constructor)
        val vmFromReflection = constructor.newInstance(app)
        assertNotNull(vmFromReflection)

        // Verify AndroidViewModelFactory.create succeeds without NoSuchMethodException
        val factory = ViewModelProvider.AndroidViewModelFactory.getInstance(app)
        val vmFromFactory = factory.create(SettingsViewModel::class.java)
        assertNotNull(vmFromFactory)
    }

    @Test
    fun testPreferencesFlowAndRepositoryAccess() {
        assertNotNull(viewModel.preferences)
        assertNotNull(viewModel.repository)
        val initialPrefs = viewModel.preferences.value
        assertNotNull(initialPrefs)
    }

    @Test
    fun testReactiveUpdates() {
        viewModel.updateSaveJpg(false)
        assertFalse(viewModel.preferences.value.saveJpg)

        viewModel.updateSaveJpg(true)
        assertTrue(viewModel.preferences.value.saveJpg)

        viewModel.updateSaveRaw(true)
        assertTrue(viewModel.preferences.value.saveRaw)

        viewModel.updateTargetLog("LOG_C")
        assertEquals("LOG_C", viewModel.preferences.value.targetLog)

        viewModel.updateActiveLut("Kodak_Portra_400", "content://lut/portra")
        assertEquals("Kodak_Portra_400", viewModel.preferences.value.activeLut)

        viewModel.updateDngCompressionMode(1)
        assertEquals(1, viewModel.preferences.value.dngCompressionMode)

        viewModel.updateUiTier("PRO")
        assertEquals("PRO", viewModel.preferences.value.uiTier)
    }
}
