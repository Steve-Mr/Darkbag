package top.maary.darkbag

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import top.maary.darkbag.repository.SettingsRepository

class MainApplication : Application() {
    // Global scope for background processing that should survive UI destruction
    val applicationScope = CoroutineScope(SupervisorJob())

    // Global event flow to replace LocalBroadcastManager
    val keyEventFlow = MutableSharedFlow<android.view.KeyEvent>(extraBufferCapacity = 1)

    override fun onCreate() {
        super.onCreate()
        // Clear session-based camera settings on app startup
        SettingsRepository.getInstance(this).clearSessionLensState()

        top.maary.darkbag.processor.ColorProcessor.detectAndApplyMemoryTier(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level == android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            top.maary.darkbag.processor.ColorProcessor.clearSabreTexturePoolAsync()
            top.maary.darkbag.camera.zsl.ZslRuntimeHooks.onTrimMemory()
            top.maary.darkbag.repository.GalleryCacheStore.getInstance().onTrimMemory(level)
        }
    }
}
