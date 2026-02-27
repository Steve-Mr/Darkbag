package top.maary.darkbag.repository

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.maary.darkbag.fragments.SettingsFragment
import top.maary.darkbag.models.ImageGroup
import java.io.File

class ImageRepository(private val context: Context) {

    suspend fun getGroupedImages(): List<ImageGroup> = withContext(Dispatchers.IO) {
        val groups = mutableMapOf<String, ImageGroupBuilder>()
        val prefs = context.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)

        // 1. Scan prioritized SAF folders
        val safFolders = listOf(
            prefs.getString(SettingsFragment.KEY_JPG_STORAGE_URI, null) to "jpg",
            prefs.getString(SettingsFragment.KEY_RAW_STORAGE_URI, null) to "dng",
            prefs.getString(SettingsFragment.KEY_TIFF_STORAGE_URI, null) to "tiff"
        )

        for ((folderUri, type) in safFolders) {
            if (folderUri != null) {
                scanSafFolder(folderUri, type, groups)
            }
        }

        // 2. Scan MediaStore for Darkbag folder
        scanMediaStore(groups)

        groups.values
            .map { it.build() }
            .filter { it.hasAny() }
            .sortedByDescending { it.captureTime }
    }

    private fun scanSafFolder(folderUri: String, type: String, groups: MutableMap<String, ImageGroupBuilder>) {
        try {
            val treeUri = Uri.parse(folderUri)
            val root = DocumentFile.fromTreeUri(context, treeUri)
            root?.listFiles()?.forEach { file ->
                val name = file.name ?: return@forEach
                val baseName = getBaseName(name)
                val builder = groups.getOrPut(baseName) { ImageGroupBuilder(baseName) }

                when {
                    name.endsWith(".jpg", ignoreCase = true) || name.endsWith(".jpeg", ignoreCase = true) -> {
                        builder.jpgUri = file.uri
                        builder.updateTime(file.lastModified())
                    }
                    name.endsWith(".dng", ignoreCase = true) -> {
                        builder.dngUri = file.uri
                        builder.updateTime(file.lastModified())
                    }
                    name.endsWith(".tiff", ignoreCase = true) || name.endsWith(".tif", ignoreCase = true) -> {
                        builder.tiffUri = file.uri
                        builder.updateTime(file.lastModified())
                    }
                }
            }
        } catch (e: Exception) {
            // Log error
        }
    }

    private fun scanMediaStore(groups: MutableMap<String, ImageGroupBuilder>) {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.MIME_TYPE
        )

        val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
        } else {
            "${MediaStore.MediaColumns.DATA} LIKE ?"
        }
        val pathFilter = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "Pictures/Darkbag%" else "%Pictures/Darkbag%"
        val selectionArgs = arrayOf(pathFilter)

        context.contentResolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val name = cursor.getString(nameColumn)
                val date = cursor.getLong(dateColumn) * 1000 // Convert to ms
                val mime = cursor.getString(mimeColumn)
                val uri = ContentUris.withAppendedId(collection, id)

                val baseName = getBaseName(name)
                val builder = groups.getOrPut(baseName) { ImageGroupBuilder(baseName) }

                when {
                    mime == "image/jpeg" -> builder.jpgUri = uri
                    mime == "image/x-adobe-dng" || name.endsWith(".dng", ignoreCase = true) -> builder.dngUri = uri
                    mime == "image/tiff" -> builder.tiffUri = uri
                }
                builder.updateTime(date)
            }
        }
    }

    private fun getBaseName(fileName: String): String {
        return fileName.substringBeforeLast(".")
            .replace("_linear", "")
            .replace("_bayer", "")
            .replace("_HDRPLUS", "")
            .replace("_full", "")
    }

    private class ImageGroupBuilder(val baseName: String) {
        var jpgUri: Uri? = null
        var tiffUri: Uri? = null
        var dngUri: Uri? = null
        var captureTime: Long = 0L

        fun updateTime(time: Long) {
            if (time > captureTime) captureTime = time
        }

        fun build() = ImageGroup(baseName, jpgUri, tiffUri, dngUri, captureTime)
    }
}
