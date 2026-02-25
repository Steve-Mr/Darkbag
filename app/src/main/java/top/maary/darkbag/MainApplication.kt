package top.maary.darkbag

import android.app.Application
import android.util.Log
import androidx.camera.camera2.Camera2Config
import androidx.camera.core.CameraXConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import top.maary.darkbag.persistence.AppDatabase
import top.maary.darkbag.persistence.ImageRepository

/**
 * Set CameraX logging level to Log.ERROR to avoid excessive logcat messages.
 * Refer to https://developer.android.com/reference/androidx/camera/core/CameraXConfig.Builder#setMinimumLoggingLevel(int)
 * for details.
 */
class MainApplication : Application(), CameraXConfig.Provider {
    // Global scope for background processing that should survive UI destruction
    val applicationScope = CoroutineScope(SupervisorJob())

    val database by lazy { AppDatabase.getDatabase(this) }
    val imageRepository by lazy { ImageRepository(database.imageDao()) }

    override fun onCreate() {
        super.onCreate()
        INSTANCE = this
        // Clear session-based camera settings on app startup
        val prefs = getSharedPreferences("camera_settings", android.content.Context.MODE_PRIVATE)
        prefs.edit()
            .remove("selected_lens_sensor_id")
            .remove("lens_facing")
            .apply()
    }

    override fun getCameraXConfig(): CameraXConfig {
        return CameraXConfig.Builder.fromConfig(Camera2Config.defaultConfig())
            .setMinimumLoggingLevel(Log.ERROR).build()
    }

    companion object {
        lateinit var INSTANCE: MainApplication
    }
}
