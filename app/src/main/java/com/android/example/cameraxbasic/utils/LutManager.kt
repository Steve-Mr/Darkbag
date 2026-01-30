package com.android.example.cameraxbasic.utils

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

class LutManager(private val context: Context) {

    private val lutDir: File = File(context.filesDir, "luts")

    init {
        if (!lutDir.exists()) {
            lutDir.mkdirs()
        }
    }

    fun getLutList(): List<File> {
        return lutDir.listFiles { _, name -> name.endsWith(".cube", ignoreCase = true) }
            ?.sortedBy { it.name }
            ?: emptyList()
    }

    fun importLut(uri: Uri): Boolean {
        try {
            val contentResolver = context.contentResolver
            val name = getFileName(uri) ?: "imported_lut_${System.currentTimeMillis()}.cube"

            // Ensure .cube extension
            val finalName = if (!name.endsWith(".cube", ignoreCase = true)) "$name.cube" else name

            val destFile = File(lutDir, finalName)

            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    fun deleteLut(file: File): Boolean {
        return if (file.exists() && file.parentFile == lutDir) {
            file.delete()
        } else {
            false
        }
    }

    fun getDisplayName(file: File): String {
        var name = file.name

        // Remove extension
        val dotIndex = name.lastIndexOf('.')
        if (dotIndex > 0) {
            name = name.substring(0, dotIndex)
        }

        // Remove common prefixes/suffixes and underscores
        // Examples: "Pro_Log_Film_Look.cube" -> "Film Look"
        // Replace underscores with spaces
        name = name.replace("_", " ")
        name = name.replace("-", " ")

        // Remove "LUT", "Log", "Pro", "sRGB", "Rec709" (case insensitive)
        val stopWords = listOf("LUT", "Log", "Pro", "sRGB", "Rec709", "Cine", "V", "F", "Arri", "Canon", "Sony")
        // This might be too aggressive if the name IS "Arri Log".
        // Let's stick to just cleaning up formatting for now, maybe removing "LUT"

        name = name.replace("(?i)LUT".toRegex(), "")

        // Trim spaces
        name = name.trim()

        // Collapse multiple spaces
        name = name.replace("\\s+".toRegex(), " ")

        return if (name.isEmpty()) file.nameWithoutExtension else name
    }

    private fun getFileName(uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) {
                        result = cursor.getString(index)
                    }
                }
            } catch (e: Exception) {
                // Ignore
            } finally {
                cursor?.close()
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/')
            if (cut != null && cut != -1) {
                result = result?.substring(cut + 1)
            }
        }
        return result
    }
}
