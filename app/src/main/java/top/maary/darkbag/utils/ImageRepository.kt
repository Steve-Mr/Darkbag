package top.maary.darkbag.utils

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import androidx.exifinterface.media.ExifInterface
import top.maary.darkbag.fragments.SettingsFragment
import java.io.File

data class DarkbagImage(
    val baseName: String,
    val primaryUri: Uri,
    val type: String, // "JPG", "TIFF", "DNG"
    val allUris: Map<String, Uri>,
    val metadata: DarkbagMetadata? = null,
    val dateAdded: Long
)

class ImageRepository(private val context: Context) {

    private val prefs = context.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)

    suspend fun getImages(): List<DarkbagImage> {
        val imageMap = mutableMapOf<String, MutableMap<String, Uri>>()
        val dateMap = mutableMapOf<String, Long>()

        // 1. Scan MediaStore (Pictures/Darkbag)
        scanMediaStore(imageMap, dateMap)

        // 2. Scan Custom SAF Folders
        scanSafFolder(SettingsFragment.KEY_JPG_STORAGE_URI, "JPG", imageMap, dateMap)
        scanSafFolder(SettingsFragment.KEY_TIFF_STORAGE_URI, "TIFF", imageMap, dateMap)
        scanSafFolder(SettingsFragment.KEY_RAW_STORAGE_URI, "DNG", imageMap, dateMap)

        return imageMap.map { (baseName, uris) ->
            val priorityType = when {
                uris.containsKey("JPG") -> "JPG"
                uris.containsKey("TIFF") -> "TIFF"
                else -> "DNG"
            }
            val primaryUri = uris[priorityType]!!

            // Read metadata if JPG
            var metadata: DarkbagMetadata? = null
            if (priorityType == "JPG") {
                metadata = readMetadata(primaryUri)
            }

            DarkbagImage(
                baseName = baseName,
                primaryUri = primaryUri,
                type = priorityType,
                allUris = uris,
                metadata = metadata,
                dateAdded = dateMap[baseName] ?: 0L
            )
        }.sortedByDescending { it.dateAdded }
    }

    private fun scanMediaStore(imageMap: MutableMap<String, MutableMap<String, Uri>>, dateMap: MutableMap<String, Long>) {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_ADDED
        )
        val selection = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
        } else {
            "${MediaStore.MediaColumns.DATA} LIKE ?"
        }
        val pathFilter = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) "Pictures/Darkbag%" else "%Pictures/Darkbag%"
        val selectionArgs = arrayOf(pathFilter)

        context.contentResolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val name = cursor.getString(nameColumn)
                val mime = cursor.getString(mimeColumn)
                val date = cursor.getLong(dateColumn)
                val uri = android.content.ContentUris.withAppendedId(collection, id)

                var baseName = name.substringBeforeLast(".")
                val type = when {
                    mime.contains("jpeg") || name.endsWith(".jpg", true) -> "JPG"
                    mime.contains("tiff") || name.endsWith(".tiff", true) -> "TIFF"
                    mime.contains("dng") || name.endsWith(".dng", true) -> "DNG"
                    else -> null
                }

                if (type == "DNG") {
                    baseName = baseName.removeSuffix("_linear")
                }

                if (type != null) {
                    imageMap.getOrPut(baseName) { mutableMapOf() }[type] = uri
                    val currentMaxDate = dateMap[baseName] ?: 0L
                    if (date > currentMaxDate) dateMap[baseName] = date
                }
            }
        }
    }

    private fun scanSafFolder(prefsKey: String, type: String, imageMap: MutableMap<String, MutableMap<String, Uri>>, dateMap: MutableMap<String, Long>) {
        val uriStr = prefs.getString(prefsKey, null) ?: return
        try {
            val treeUri = Uri.parse(uriStr)
            val root = DocumentFile.fromTreeUri(context, treeUri)
            root?.listFiles()?.forEach { file ->
                val name = file.name ?: return@forEach
                var baseName = name.substringBeforeLast(".")
                val fileType = when {
                    name.endsWith(".jpg", true) || name.endsWith(".jpeg", true) -> "JPG"
                    name.endsWith(".tiff", true) -> "TIFF"
                    name.endsWith(".dng", true) -> "DNG"
                    else -> null
                }

                if (fileType == "DNG") {
                    baseName = baseName.removeSuffix("_linear")
                }

                if (fileType == type) {
                    imageMap.getOrPut(baseName) { mutableMapOf() }[type] = file.uri
                    val date = file.lastModified() / 1000
                    val currentMaxDate = dateMap[baseName] ?: 0L
                    if (date > currentMaxDate) dateMap[baseName] = date
                }
            }
        } catch (e: Exception) {
            // Log or ignore
        }
    }

    private fun readMetadata(uri: Uri): DarkbagMetadata? {
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                val exif = ExifInterface(pfd.fileDescriptor)
                val comment = exif.getAttribute(ExifInterface.TAG_USER_COMMENT)
                DarkbagMetadata.fromJson(comment)
            }
        } catch (e: Exception) {
            null
        }
    }
}
