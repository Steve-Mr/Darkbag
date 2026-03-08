package com.android.example.cameraxbasic.utils

import android.content.Context
import java.text.SimpleDateFormat
import java.util.LinkedList
import java.util.Locale

object DebugLogManager {
    private const val MAX_LOGS = 40
    private const val PREFS_NAME = "debug_logs_prefs"
    private const val KEY_LOGS = "debug_logs"

    private val logs = LinkedList<String>()
    @Volatile private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
        synchronized(logs) {
            if (logs.isNotEmpty()) return
            val saved = appContext
                ?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                ?.getString(KEY_LOGS, null)
            if (!saved.isNullOrBlank()) {
                saved.split("\n---\n").filter { it.isNotBlank() }.forEach { logs.add(it) }
            }
        }
    }

    fun addLog(log: String, captureId: String? = null) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(System.currentTimeMillis())
        val capture = if (!captureId.isNullOrBlank()) " [CID:$captureId]" else ""
        val entry = "[$timestamp]$capture $log"
        synchronized(logs) {
            logs.addFirst(entry)
            while (logs.size > MAX_LOGS) {
                logs.removeLast()
            }
            persistLocked()
        }
    }

    fun clearLogs() {
        synchronized(logs) {
            logs.clear()
            persistLocked()
        }
    }

    fun getLogs(): String {
        synchronized(logs) {
            return logs.joinToString("\n\n")
        }
    }

    private fun persistLocked() {
        appContext
            ?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit()
            ?.putString(KEY_LOGS, logs.joinToString("\n---\n"))
            ?.apply()
    }
}
