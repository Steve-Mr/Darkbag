package top.maary.darkbag.camera.zsl

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class ZslRuntimeHooksTest {
    @After
    fun tearDown() {
        ZslRuntimeHooks.unregisterTrimListener(listener)
    }

    private val listener: () -> Unit = {}

    @Test
    fun trimListenerIsRegisteredAndRemoved() {
        var calls = 0
        val active: () -> Unit = { calls++ }
        ZslRuntimeHooks.registerTrimListener(active)
        ZslRuntimeHooks.onTrimMemory()
        assertEquals(1, calls)
        ZslRuntimeHooks.unregisterTrimListener(active)
        ZslRuntimeHooks.onTrimMemory()
        assertEquals(1, calls)
    }
}
