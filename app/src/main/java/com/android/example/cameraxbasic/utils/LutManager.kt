package com.android.example.cameraxbasic.utils

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

class LutManager(private val context: Context) {

    val lutDir: File by lazy {
        File(context.filesDir, "luts").apply { mkdirs() }
    }

    fun getLuts(): List<File> {
        return lutDir.listFiles { _, name -> name.endsWith(".cube", ignoreCase = true) }
            ?.sortedBy { it.name }
            ?.toList() ?: emptyList()
    }

    fun importLut(uri: Uri): Boolean {
        val originalName = getFileName(uri) ?: return false
        // Allow import even if extension is weird, but ensure we save as .cube if it looks like a LUT?
        // No, strict check.
        if (!originalName.endsWith(".cube", ignoreCase = true) && !originalName.endsWith(".CUBE")) return false

        val newName = formatLutName(originalName)
        // Ensure unique name
        var destFile = File(lutDir, "$newName.cube")
        var counter = 1
        while (destFile.exists()) {
            destFile = File(lutDir, "$newName ($counter).cube")
            counter++
        }

        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun deleteLut(file: File): Boolean {
        return file.delete()
    }

    fun renameLut(file: File, newName: String): Boolean {
        val safeName = newName.replace("[^a-zA-Z0-9 _-]".toRegex(), "").trim()
        if (safeName.isEmpty()) return false
        val newFile = File(lutDir, "$safeName.cube")
        if (newFile.exists() && newFile != file) return false
        return file.renameTo(newFile)
    }

    private fun getFileName(uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) {
                        result = cursor.getString(index)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("LutManager", "Failed to get filename from content URI", e)
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

    private fun formatLutName(fileName: String): String {
        val nameWithoutExt = fileName.substringBeforeLast(".")
        // Replace separators with space
        val spaced = nameWithoutExt.replace("[_\\-]".toRegex(), " ")
        // Split
        val words = spaced.split("\\s+".toRegex()).filter { it.isNotEmpty() }

        val commonPrefixes = setOf(
            "fuji", "fujifilm", "sony", "panasonic", "canon", "nikon", "olympus",
            "log", "flog", "slog", "vlog", "clog", "dlog", "nlog", "cine", "film",
            "rec709", "f-log", "s-log", "v-log", "c-log", "d-log", "glog", "arri", "alexa"
        )

        val mainPart = mutableListOf<String>()
        val suffixPart = mutableListOf<String>()

        for (word in words) {
            if (commonPrefixes.contains(word.lowercase(Locale.ROOT))) {
                suffixPart.add(word)
            } else {
                mainPart.add(word)
            }
        }

        val finalWords = mainPart + suffixPart
        return finalWords.joinToString(" ")
    }
}
