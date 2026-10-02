package top.maary.darkbag.utils

import java.text.SimpleDateFormat
import java.util.LinkedList
import java.util.Locale

object DebugLogManager {
    private const val MAX_TIMING_LOGS = 20
    private const val MAX_DIAGNOSTIC_LOGS = 10

    private val timingLogs = LinkedList<String>()
    private val diagnosticLogs = LinkedList<String>()

    fun addTimingReport(report: String) {
        val trimmed = report.trim()
        if (trimmed.isEmpty()) return
        synchronized(timingLogs) {
            timingLogs.addFirst(trimmed)
            if (timingLogs.size > MAX_TIMING_LOGS) {
                timingLogs.removeLast()
            }
        }
    }

    fun addDiagnosticLog(log: String) {
        val trimmed = log.trim()
        if (trimmed.isEmpty()) return
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(System.currentTimeMillis())
        val entry = "[$timestamp] $trimmed"
        synchronized(diagnosticLogs) {
            diagnosticLogs.addFirst(entry)
            if (diagnosticLogs.size > MAX_DIAGNOSTIC_LOGS) {
                diagnosticLogs.removeLast()
            }
        }
    }

    fun addLog(log: String) {
        val trimmed = log.trim()
        if (trimmed.isEmpty()) return
        if (trimmed.contains("A1 Hardware Baseline") || trimmed.contains("Sensor CFA:")) {
            addDiagnosticLog(trimmed)
        } else if (trimmed.contains("核心延迟指标") ||
            trimmed.contains("Lifecycle Timing Report") ||
            trimmed.startsWith("========================================")
        ) {
            addTimingReport(trimmed)
        } else {
            addDiagnosticLog(trimmed)
        }
    }

    fun getTimingLogs(): String {
        synchronized(timingLogs) {
            return timingLogs.joinToString("\n\n")
        }
    }

    fun getDiagnosticLogs(): String {
        synchronized(diagnosticLogs) {
            return diagnosticLogs.joinToString("\n\n")
        }
    }

    fun getLogs(): String {
        val timings = getTimingLogs()
        if (timings.isNotEmpty()) {
            return timings
        }
        return getDiagnosticLogs()
    }

    fun clearLogs() {
        synchronized(timingLogs) {
            timingLogs.clear()
        }
        synchronized(diagnosticLogs) {
            diagnosticLogs.clear()
        }
    }
}

