package top.maary.darkbag.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DebugLogManagerTest {

    @Before
    fun setUp() {
        DebugLogManager.clearLogs()
    }

    @Test
    fun testSegregationOfTimingAndDiagnosticLogs() {
        DebugLogManager.addDiagnosticLog("Sensor CFA: RGGB, BlackLevel=[64, 64, 64, 64]")
        DebugLogManager.addTimingReport("========================================\n[HDR+ Burst (5 frames)]\n⚡ 核心延迟指标:\n  • T1 快门就绪延迟: 120ms\n========================================")

        val timing = DebugLogManager.getTimingLogs()
        val diagnostic = DebugLogManager.getDiagnosticLogs()

        assertTrue(timing.contains("HDR+ Burst"))
        assertFalse(timing.contains("Sensor CFA"))

        assertTrue(diagnostic.contains("Sensor CFA"))
        assertFalse(diagnostic.contains("HDR+ Burst"))

        // getLogs() should prioritize timing logs if available
        val allLogs = DebugLogManager.getLogs()
        assertTrue(allLogs.contains("HDR+ Burst"))
    }

    @Test
    fun testAddLogAutoRouting() {
        DebugLogManager.addLog("[A1 Hardware Baseline Diagnostic] CFA=RGGB")
        DebugLogManager.addLog("========================================\n[Single RAW]\n⚡ 核心延迟指标:\n  • T1 快门就绪延迟: 90ms\n========================================")

        val timing = DebugLogManager.getTimingLogs()
        val diagnostic = DebugLogManager.getDiagnosticLogs()

        assertTrue(timing.contains("Single RAW"))
        assertTrue(diagnostic.contains("Hardware Baseline"))
    }

    @Test
    fun testClearLogs() {
        DebugLogManager.addLog("Some baseline log")
        DebugLogManager.addTimingReport("Some timing report")

        DebugLogManager.clearLogs()

        assertEquals("", DebugLogManager.getTimingLogs())
        assertEquals("", DebugLogManager.getDiagnosticLogs())
        assertEquals("", DebugLogManager.getLogs())
    }

    @Test
    fun testFifoEviction() {
        for (i in 1..25) {
            DebugLogManager.addTimingReport("Report #$i")
        }
        val timing = DebugLogManager.getTimingLogs()
        assertTrue(timing.contains("Report #25"))
        assertTrue(timing.contains("Report #6"))
        assertFalse(timing.contains("Report #5")) // Evicted (max 20)

        for (i in 1..15) {
            DebugLogManager.addDiagnosticLog("Diagnostic #$i")
        }
        val diagnostic = DebugLogManager.getDiagnosticLogs()
        assertTrue(diagnostic.contains("Diagnostic #15"))
        assertTrue(diagnostic.contains("Diagnostic #6"))
        assertFalse(diagnostic.contains("Diagnostic #5")) // Evicted (max 10)
    }

    @Test
    fun testEmptyStringIgnored() {
        DebugLogManager.addLog("   ")
        DebugLogManager.addTimingReport("")
        DebugLogManager.addDiagnosticLog("  \n\t  ")

        assertEquals("", DebugLogManager.getTimingLogs())
        assertEquals("", DebugLogManager.getDiagnosticLogs())
    }
}
