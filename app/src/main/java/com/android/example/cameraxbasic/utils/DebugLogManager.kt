package com.android.example.cameraxbasic.utils

import java.text.SimpleDateFormat
import java.util.LinkedList
import java.util.Locale

object DebugLogManager {
    private const val MAX_LOGS = 5
    private val logs = LinkedList<String>()

    fun addLog(log: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(System.currentTimeMillis())
        val entry = "[$timestamp] $log"
        synchronized(logs) {
            logs.addFirst(entry)
            if (logs.size > MAX_LOGS) {
                logs.removeLast()
            }
        }
    }

    fun getLogs(): String {
        synchronized(logs) {
            return logs.joinToString("\n\n")
        }
    }
}
