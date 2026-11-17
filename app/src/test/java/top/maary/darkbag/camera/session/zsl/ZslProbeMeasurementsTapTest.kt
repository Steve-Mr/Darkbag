package top.maary.darkbag.camera.session.zsl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 探测测量缺陷修复的纯逻辑测试（S1 补丁）：
 * - W0 基线计数必须排除 `captureResultFlow` 的 replay 历史，并在双流期间让位给回调；
 * - 元数据绑定必须是"窗口后回查"，不能沿用回调瞬时判定。
 */
class ZslProbeMeasurementsTapTest {

    @Test
    fun testShouldCountBaselinePreview_excludesReplayHistory() {
        assertFalse(shouldCountBaselinePreview(timestampNs = 100L, cutNs = 100L, dualStreamActive = false))
        assertFalse(shouldCountBaselinePreview(timestampNs = 99L, cutNs = 100L, dualStreamActive = false))
        assertTrue(shouldCountBaselinePreview(timestampNs = 101L, cutNs = 100L, dualStreamActive = false))
    }

    @Test
    fun testShouldCountBaselinePreview_yieldsToDualStreamCallback() {
        // W1 由适配器回调直接上报，flow 计数必须停用以避免同一帧被数两次
        assertFalse(shouldCountBaselinePreview(timestampNs = 1_000L, cutNs = 0L, dualStreamActive = true))
    }

    @Test
    fun testMetadataBinder_countsBoundFramesAfterWindow() {
        val timestamps = listOf(10L, 20L, 30L, 40L)
        val allBound = ZslProbeMetadataBinder.bind(timestamps) { true }
        assertEquals(4, allBound.bound)
        assertEquals(4, allBound.total)

        val partial = ZslProbeMetadataBinder.bind(timestamps) { it != 30L }
        assertEquals(3, partial.bound)
        assertEquals(4, partial.total)

        val noneBound = ZslProbeMetadataBinder.bind(timestamps) { false }
        assertEquals(0, noneBound.bound)
        assertEquals(4, noneBound.total)
    }

    @Test
    fun testMetadataBinder_emptyInputIsZero() {
        val empty = ZslProbeMetadataBinder.bind(emptyList()) { true }
        assertEquals(0, empty.bound)
        assertEquals(0, empty.total)
    }
}
