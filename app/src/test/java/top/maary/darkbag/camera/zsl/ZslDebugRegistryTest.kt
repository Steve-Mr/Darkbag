package top.maary.darkbag.camera.zsl

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ZslDebugRegistryTest {
    @After
    fun tearDown() {
        ZslDebugRegistry.reset()
    }

    private fun labels() = ZslDebugLabels(
        armed = "ARMED",
        off = "OFF",
        stateFormat = "ZSL %1\$s · %2\$s",
        reasonFormat = "reason: %1\$s",
        routeFormat = "route: %1\$s",
        ringFormat = "ring: %1\$d/%2\$d",
        ringFpsFormat = "ring: %1\$d/%2\$d · %3\$.1f fps",
        lastFormat = "last: %1\$s",
        waiting = "waiting",
        profileHdrPlus = "HDR+",
        profileSingle = "SINGLE",
        profileOff = "OFF"
    )

    @Test
    fun repeatedDisarmPreservesLastArmedSnapshot() {
        val armed = ZslDebugState(
            enabled = true,
            armed = true,
            profile = "hdr_plus",
            depth = 5,
            frames = 5,
            fps = 29.8f
        )
        val closed = armed.afterDisarm("session_closed")
        assertEquals("hdr_plus", closed.profile)
        assertEquals(5, closed.depth)
        assertEquals(5, closed.frames)
        assertTrue(!closed.armed)

        val repeated = closed.afterDisarm("stopped")
        assertEquals("hdr_plus", repeated.profile)
        assertEquals(5, repeated.depth)
        assertEquals(5, repeated.frames)
        assertEquals("stopped", repeated.reason)
    }

    @Test
    fun formatterShowsOccupancyAndFps() {
        val text = ZslDebugFormatter.format(
            ZslDebugState(
                enabled = true,
                armed = true,
                profile = "hdr_plus",
                route = "raw_ring",
                rawSize = "4096x3072",
                depth = 5,
                frames = 5,
                fps = 29.8f
            ),
            labels()
        )
        assertTrue(text.contains("ZSL ARMED · HDR+"))
        assertTrue(text.contains("ring: 5/5 · 29.8 fps"))
    }

    @Test
    fun formatterPreservesLastArmedStatsAfterDisarm() {
        val text = ZslDebugFormatter.format(
            ZslDebugState(
                enabled = true,
                armed = false,
                profile = "hdr_plus",
                reason = "session_closed",
                route = "raw_ring",
                depth = 5,
                frames = 5,
                fps = 29.8f
            ),
            labels()
        )
        assertTrue(text.contains("ZSL OFF · HDR+"))
        assertTrue(text.contains("reason: session_closed"))
        assertTrue(text.contains("ring: 5/5 · 29.8 fps"))
    }

    @Test
    fun formatterWaitsForCameraAndShowsLastCapture() {
        assertEquals("waiting", ZslDebugFormatter.format(ZslDebugState(), labels()))
        val last = ZslDebugFormatter.format(
            ZslDebugState(enabled = true, lastCapture = "single · offset 12ms"),
            labels()
        )
        assertTrue(last.contains("last: single · offset 12ms"))
    }

    @Test
    fun updatesAndResetsLastKnownState() {
        ZslDebugRegistry.update {
            it.copy(enabled = true, armed = true, reason = "", depth = 5, frames = 5, fps = 29.8f)
        }
        val armed = ZslDebugRegistry.state.value
        assertTrue(armed.enabled)
        assertTrue(armed.armed)
        assertEquals(5, armed.depth)
        assertEquals(5, armed.frames)
        assertEquals(29.8f, armed.fps ?: 0f, 0.01f)

        ZslDebugRegistry.update { it.copy(armed = false, reason = "motion_photo") }
        assertEquals("motion_photo", ZslDebugRegistry.state.value.reason)

        ZslDebugRegistry.reset()
        assertEquals(ZslDebugState(), ZslDebugRegistry.state.value)
    }
}
