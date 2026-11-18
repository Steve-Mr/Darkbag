package top.maary.darkbag.camera.zsl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ZslLateResultResolverTest {
    @Test
    fun prefersProvidedResultAndFallsBackToLookup() {
        val provided = "with-image"
        assertEquals("with-image", resolveLateResult(provided, 42L) { "late" })
        assertEquals("late", resolveLateResult(null, 42L) { "late" })
        assertNull(resolveLateResult<String>(null, 42L, null))
    }
}
