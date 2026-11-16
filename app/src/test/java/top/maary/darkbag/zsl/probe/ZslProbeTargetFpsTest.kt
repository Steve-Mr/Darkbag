package top.maary.darkbag.zsl.probe

import org.junit.Assert.assertEquals
import org.junit.Test
import top.maary.darkbag.camera.zsl.probe.ZslProbeTargetFps

/**
 * 目标帧率选择（S1-B1 F6）：四档 + 档位边界 + 未知能力兜底。
 *
 * 该值决定探测的"标称帧间隔"（1000 / targetFps），进而决定 RAW_JITTER / RAW_STALL 阈值，
 * 因此边界必须逐条钉死：29.9 不得落到 30，14.9 不得落到 15。
 */
class ZslProbeTargetFpsTest {

    @Test
    fun testThirtyAndAboveChoosesThirty() {
        assertEquals(30, ZslProbeTargetFps.choose(30f))
        assertEquals(30, ZslProbeTargetFps.choose(60f))
        assertEquals(30, ZslProbeTargetFps.choose(240f))
    }

    @Test
    fun testTwentyFourTierCoversTwentyFourToJustBelowThirty() {
        assertEquals(24, ZslProbeTargetFps.choose(24f))
        assertEquals(24, ZslProbeTargetFps.choose(27f))
        assertEquals(24, ZslProbeTargetFps.choose(29.9f))
    }

    @Test
    fun testFifteenTierCoversFifteenToJustBelowTwentyFour() {
        assertEquals(15, ZslProbeTargetFps.choose(15f))
        assertEquals(15, ZslProbeTargetFps.choose(20f))
        assertEquals(15, ZslProbeTargetFps.choose(23.9f))
    }

    @Test
    fun testBelowFifteenAndUnknownFallBackToFifteen() {
        assertEquals(15, ZslProbeTargetFps.choose(14.9f))
        assertEquals(15, ZslProbeTargetFps.choose(1f))
        assertEquals(15, ZslProbeTargetFps.choose(0f))
        assertEquals(15, ZslProbeTargetFps.choose(-5f))
        assertEquals(15, ZslProbeTargetFps.choose(null))
    }

    @Test
    fun testJustBelowThirtyDoesNotRoundUpToThirty() {
        assertEquals(24, ZslProbeTargetFps.choose(29.999f))
        assertEquals(30, ZslProbeTargetFps.choose(30.001f))
    }
}
