package top.maary.darkbag.fragments.settings

import androidx.fragment.app.FragmentActivity
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.maary.darkbag.R
import top.maary.darkbag.utils.CameraRepository
import top.maary.darkbag.viewmodel.SettingsViewModel

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SettingsSheetDispatcherTest {

    private lateinit var activity: FragmentActivity
    private lateinit var cameraRepository: CameraRepository
    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setup() {
        activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        activity.setTheme(R.style.AppTheme)
        cameraRepository = CameraRepository(activity)
        viewModel = SettingsViewModel(activity.application)
    }

    @Test
    fun testDispatcherInstantiationAndPreferencesAccess() {
        val dispatcher = SettingsSheetDispatcher(
            fragmentManager = activity.supportFragmentManager,
            context = activity,
            cameraRepository = cameraRepository,
            viewModel = viewModel
        )
        assertNotNull(dispatcher)
    }
}
