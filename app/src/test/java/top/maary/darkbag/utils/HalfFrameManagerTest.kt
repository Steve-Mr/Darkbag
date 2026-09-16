package top.maary.darkbag.utils

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.maary.darkbag.fragments.SettingsFragment
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class HalfFrameManagerTest {

    private lateinit var context: Context
    private lateinit var sessionStore: HalfFrameSessionStore
    private lateinit var halfFrameManager: HalfFrameManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val prefs = context.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().apply()

        sessionStore = HalfFrameSessionStore(context)
        halfFrameManager = HalfFrameManager(context)
    }

    @Test
    fun testNormalCapture_WithNullMetadata_NeverHijackedEvenIfGlobalModeIsHalfFrame() {
        // Given: User switched global mode to HALF_FRAME_SBS
        val prefs = context.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(SettingsFragment.KEY_ACTIVE_CAPTURE_MODE, SettingsFragment.MODE_HALF_FRAME_SBS)
            .putBoolean(SettingsFragment.KEY_HALF_FRAME_MODE, true)
            .apply()

        // Verify global isEnabled is true
        assertEquals(true, halfFrameManager.isEnabled)
        assertEquals(HalfFrameSessionStore.PROFILE_HALF_SIDE, sessionStore.currentProfile())

        // Create a dummy image file
        val dummyFile = File(context.cacheDir, "test_normal.jpg")
        dummyFile.writeBytes(byteArrayOf(1, 2, 3))

        // When: A capture with metadata == null (Normal capture from earlier) is saved
        val result = halfFrameManager.handleCapture(
            currentJpgPath = dummyFile.absolutePath,
            baseName = "normal_12345",
            isFastPath = false,
            metadata = null
        )

        // Then: It must return the original path directly and NEVER alter HalfFrame session
        assertEquals(dummyFile.absolutePath, result)
        val session = sessionStore.readSession(profile = HalfFrameSessionStore.PROFILE_HALF_SIDE)
        assertEquals(0, session.step)
        assertNull(session.baseName)
        assertNull(session.tempPath)
    }

    @Test
    fun testNormalCapture_WithExplicitNormalProfile_NeverHijacked() {
        // Given: Global mode is HALF_FRAME_TB
        val prefs = context.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(SettingsFragment.KEY_ACTIVE_CAPTURE_MODE, SettingsFragment.MODE_HALF_FRAME_TB)
            .apply()

        val dummyFile = File(context.cacheDir, "test_normal_explicit.jpg")
        dummyFile.writeBytes(byteArrayOf(1, 2, 3))

        val metadata = HalfFrameManager.Metadata(
            profile = HalfFrameSessionStore.PROFILE_NORMAL,
            dateStamp = false,
            captureTimeMillis = System.currentTimeMillis()
        )

        // When
        val result = halfFrameManager.handleCapture(
            currentJpgPath = dummyFile.absolutePath,
            baseName = "normal_explicit_67890",
            isFastPath = false,
            metadata = metadata
        )

        // Then: Returns original path, session remains step 0
        assertEquals(dummyFile.absolutePath, result)
        val session = sessionStore.readSession(profile = HalfFrameSessionStore.PROFILE_HALF_TOP)
        assertEquals(0, session.step)
        assertNull(session.baseName)
    }

    @Test
    fun testHalfFrameCapture_WithMetadata_CorrectlyRecordsFrame1() {
        val dummyFile = File(context.cacheDir, "test_hf1.jpg")
        dummyFile.writeBytes(byteArrayOf(1, 2, 3))

        val metadata = HalfFrameManager.Metadata(
            profile = HalfFrameSessionStore.PROFILE_HALF_SIDE,
            dateStamp = false,
            captureTimeMillis = System.currentTimeMillis()
        )

        // When: Frame 1 is handled
        val result = halfFrameManager.handleCapture(
            currentJpgPath = dummyFile.absolutePath,
            baseName = "hf_group_001",
            isFastPath = true,
            metadata = metadata
        )

        // Then: Result is null (waiting for frame 2), session is step 1
        assertNull(result)
        val session = sessionStore.readSession(profile = HalfFrameSessionStore.PROFILE_HALF_SIDE)
        assertEquals(1, session.step)
        assertEquals("hf_group_001", session.baseName)
    }
}
