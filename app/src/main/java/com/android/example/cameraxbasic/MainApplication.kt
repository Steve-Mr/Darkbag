package com.android.example.cameraxbasic

import android.app.Application
import android.util.Log
import androidx.camera.camera2.Camera2Config
import androidx.camera.core.CameraXConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

/**
 * Set CameraX logging level to Log.ERROR to avoid excessive logcat messages.
 * Refer to https://developer.android.com/reference/androidx/camera/core/CameraXConfig.Builder#setMinimumLoggingLevel(int)
 * for details.
 */
class MainApplication : Application(), CameraXConfig.Provider {
    // Global scope for background processing that should survive UI destruction
    val applicationScope = CoroutineScope(SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        // Clear session-based camera settings on app startup
        val prefs = getSharedPreferences("camera_settings", android.content.Context.MODE_PRIVATE)
        prefs.edit()
            .remove("selected_lens_sensor_id")
            .apply()
    }

    override fun getCameraXConfig(): CameraXConfig {
        return CameraXConfig.Builder.fromConfig(Camera2Config.defaultConfig())
            .setMinimumLoggingLevel(Log.ERROR).build()
    }
}
