package top.maary.darkbag

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow

class MainApplication : Application() {
    // Global scope for background processing that should survive UI destruction
    val applicationScope = CoroutineScope(SupervisorJob())

    // Global event flow to replace LocalBroadcastManager
    val keyEventFlow = MutableSharedFlow<android.view.KeyEvent>(extraBufferCapacity = 1)

    override fun onCreate() {
        super.onCreate()
        // Clear session-based camera settings on app startup
        val prefs = getSharedPreferences("camera_settings", android.content.Context.MODE_PRIVATE)
        prefs.edit()
            .remove("selected_lens_sensor_id")
            .remove("lens_facing")
            .apply()
    }
}
