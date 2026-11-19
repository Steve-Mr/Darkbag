package top.maary.darkbag.camera.zsl

/**
 * Process-level hook for low-memory events. The ZSL controller can live behind the camera
 * fragment; MainApplication must not retain it. Keep the callback volatile and idempotent.
 */
object ZslRuntimeHooks {
    @Volatile
    private var trimListener: (() -> Unit)? = null

    fun registerTrimListener(listener: () -> Unit) {
        trimListener = listener
    }

    fun unregisterTrimListener(listener: () -> Unit) {
        if (trimListener === listener) trimListener = null
    }

    fun onTrimMemory() {
        trimListener?.invoke()
    }
}
