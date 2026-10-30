package top.maary.darkbag.utils

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import top.maary.darkbag.fragments.SettingsFragment
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

class LutManager(private val context: Context) {

    data class LutItem(
        val file: File,
        val name: String,
        val associatedLog: String?
    ) {
        val isUniversal: Boolean
            get() = associatedLog == null ||
                    associatedLog.equals(LOG_UNIVERSAL, ignoreCase = true) ||
                    associatedLog.equals(LOG_NONE, ignoreCase = true)

        val isRec709: Boolean
            get() = associatedLog.equals(LOG_REC709, ignoreCase = true)
    }

    val lutDir: File by lazy {
        File(context.filesDir, "luts").apply { mkdirs() }
    }

    fun getLuts(): List<File> {
        return lutDir.listFiles { _, name -> name.endsWith(".cube", ignoreCase = true) }
            ?.sortedBy { it.name }
            ?.toList() ?: emptyList()
    }

    fun getAllLutItems(): List<LutItem> {
        return getLuts().map { file ->
            LutItem(
                file = file,
                name = file.name,
                associatedLog = getAssociatedLog(file.name)
            )
        }
    }

    /**
     * Filters available LUT items for a given target Log curve according to Strategy A (Continuous Stream):
     * 1. If targetLog is None, Rec.709, or null: Rec.709 items are displayed first, followed continuously by Universal items.
     * 2. If targetLog is a specific Log (e.g. F-Log2, S-Log3): Log-specific LUTs are displayed first,
     *    followed continuously by Universal LUTs. LUTs exclusively bound to other Logs are strictly filtered out.
     */
    fun getLutsForLog(targetLog: String?): List<LutItem> {
        val allItems = getAllLutItems()
        if (targetLog == null || targetLog.equals(LOG_NONE, ignoreCase = true) || targetLog.equals(LOG_REC709, ignoreCase = true)) {
            val rec709Items = allItems.filter { it.isRec709 }
            val universalItems = allItems.filter { it.isUniversal && !it.isRec709 }
            return rec709Items + universalItems
        }

        val specificItems = allItems.filter {
            !it.isUniversal && !it.isRec709 && it.associatedLog.equals(targetLog, ignoreCase = true)
        }
        val universalItems = allItems.filter { it.isUniversal }
        return specificItems + universalItems
    }

    /**
     * Maps an associated Log curve to a valid KEY_TARGET_LOG value for SettingsFragment.LOG_CURVES.
     * Specifically, Rec.709 and Universal curves map to standard capture ("None" at index 0).
     */
    fun toTargetLogPreference(associatedLog: String?): String {
        if (associatedLog == null ||
            associatedLog.equals(LOG_UNIVERSAL, ignoreCase = true) ||
            associatedLog.equals(LOG_NONE, ignoreCase = true) ||
            associatedLog.equals(LOG_REC709, ignoreCase = true)) {
            return LOG_NONE
        }
        return associatedLog
    }

    /**
     * Validates whether an active LUT is compatible with the newly selected target Log.
     */
    fun isLutCompatibleWithLog(lutFileName: String?, targetLog: String?): Boolean {
        if (lutFileName.isNullOrBlank() || lutFileName.equals(LOG_NONE, ignoreCase = true)) return true
        val assocLog = getAssociatedLog(lutFileName)
        val isUniversal = assocLog == null ||
                assocLog.equals(LOG_UNIVERSAL, ignoreCase = true) ||
                assocLog.equals(LOG_NONE, ignoreCase = true)
        if (isUniversal) return true

        if (targetLog == null || targetLog.equals(LOG_NONE, ignoreCase = true)) {
            return assocLog.equals(LOG_REC709, ignoreCase = true)
        }
        return assocLog.equals(targetLog, ignoreCase = true)
    }

    fun getAssociatedLog(lutFileName: String): String? {
        val prefs = context.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(PREF_PREFIX_LUT_LOG + lutFileName, null)
    }

    fun setAssociatedLog(lutFileName: String, logCurve: String?) {
        val prefs = context.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        if (logCurve != null && logCurve != LOG_NONE) {
            editor.putString(PREF_PREFIX_LUT_LOG + lutFileName, logCurve)
        } else {
            editor.remove(PREF_PREFIX_LUT_LOG + lutFileName)
        }
        editor.apply()
    }

    fun importLut(uri: Uri, associatedLog: String? = null): String? {
        val originalName = getFileName(uri) ?: return null
        // Allow import even if extension is weird, but ensure we save as .cube if it looks like a LUT
        if (!originalName.endsWith(".cube", ignoreCase = true) && !originalName.endsWith(".CUBE")) return null

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
            if (associatedLog != null) {
                setAssociatedLog(destFile.name, associatedLog)
            }
            destFile.name
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun importLut(uri: Uri): Boolean {
        return importLut(uri, null) != null
    }

    fun deleteLut(file: File): Boolean {
        val oldName = file.name
        val deleted = file.delete()
        if (deleted) {
            setAssociatedLog(oldName, null)
        }
        return deleted
    }

    fun renameLut(file: File, newName: String): Boolean {
        val safeName = newName.replace("[^a-zA-Z0-9 _-]".toRegex(), "").trim()
        if (safeName.isEmpty()) return false
        val newFile = File(lutDir, "$safeName.cube")
        if (newFile.exists() && newFile != file) return false
        val oldName = file.name
        val renamed = file.renameTo(newFile)
        if (renamed) {
            val assoc = getAssociatedLog(oldName)
            if (assoc != null) {
                setAssociatedLog(oldName, null)
                setAssociatedLog(newFile.name, assoc)
            }
        }
        return renamed
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

    companion object {
        const val PREF_PREFIX_LUT_LOG = "lut_assoc_log_"
        const val LOG_UNIVERSAL = "Universal"
        const val LOG_REC709 = "Rec.709"
        const val LOG_NONE = "None"
    }
}
