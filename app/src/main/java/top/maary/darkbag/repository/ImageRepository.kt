package top.maary.darkbag.repository

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import top.maary.darkbag.fragments.SettingsFragment
import top.maary.darkbag.models.EditConfig
import top.maary.darkbag.models.ImageGroup
import top.maary.darkbag.utils.DarkbagIdentity
import java.io.File

class ImageRepository(private val context: Context) {

    companion object {
        @Volatile
        private var cachedGroups: List<ImageGroup>? = null
        @Volatile
        private var cachedStudioGroups: List<ImageGroup>? = null

        const val STUDIO_DIR = "studio_assets"
    }

    private val studioFolder: File by lazy {
        File(context.filesDir, STUDIO_DIR).apply { if (!exists()) mkdirs() }
    }

    suspend fun getGroupedImages(forceRefresh: Boolean = false): List<ImageGroup> {
        if (!forceRefresh) {
            cachedGroups?.let { return it }
        }

        return withContext(Dispatchers.IO) {
            val groups = mutableMapOf<String, ImageGroupBuilder>()
            val prefs = context.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)

            // 1. Scan prioritized SAF folders
            val safFolders = listOf(
                prefs.getString(SettingsFragment.KEY_JPG_STORAGE_URI, null) to "jpg",
                prefs.getString(SettingsFragment.KEY_RAW_STORAGE_URI, null) to "dng"
            )

            for ((folderUri, _) in safFolders) {
                if (folderUri != null) {
                    scanSafFolder(folderUri, groups)
                }
            }

            // 2. Scan MediaStore for ALL DNG files
            scanAllDngs(groups)

            // 3. Scan MediaStore for Darkbag folder
            scanMediaStore(groups)

            val result = groups.values
                .map { it.build() }
                .filter { it.hasAny() && (it.dngUri != null || it.dngUri1 != null || it.dngUri2 != null) }
                .sortedByDescending { it.captureTime }

            cachedGroups = result
            result
        }
    }

    fun invalidateCache() {
        cachedGroups = null
        cachedStudioGroups = null
    }

    fun resolveFilename(uri: Uri): String? {
        if (uri.scheme == "file") return File(uri.path ?: "").name
        return try {
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (e: Exception) {
            null
        } ?: try {
            DocumentFile.fromSingleUri(context, uri)?.name
        } catch (e: Exception) {
            null
        }
    }

    fun findSiblingsForUri(uri: Uri, baseName: String): Pair<Uri?, Uri?> {
        var hf1: Uri? = null
        var hf2: Uri? = null

        // 1. Try MediaStore in the same folder
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME)

        val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
        } else {
            "${MediaStore.MediaColumns.DATA} LIKE ?"
        }

        val relativePath = try {
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (e: Exception) { null }

        val selectionArgs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && relativePath != null) {
            arrayOf(relativePath, "$baseName%")
        } else {
            arrayOf("%$baseName%")
        }

        context.contentResolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameCol)
                val id = cursor.getLong(idCol)
                val siblingUri = ContentUris.withAppendedId(collection, id)
                if (name.contains("_HF1") && name.endsWith(".dng", ignoreCase = true)) hf1 = siblingUri
                if (name.contains("_HF2") && name.endsWith(".dng", ignoreCase = true)) hf2 = siblingUri
            }
        }

        // 2. Try configured folders and fallback to SAF
        if (hf1 == null || hf2 == null) {
            val prefs = context.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
            val folders = mutableListOf<String>()
            prefs.getString(SettingsFragment.KEY_RAW_STORAGE_URI, null)?.let { folders.add(it) }
            prefs.getString(SettingsFragment.KEY_JPG_STORAGE_URI, null)?.let { folders.add(it) }

            for (folderUri in folders) {
                try {
                    val root = DocumentFile.fromTreeUri(context, Uri.parse(folderUri))
                    root?.listFiles()?.forEach { file ->
                        val name = file.name ?: return@forEach
                        if (name.startsWith(baseName)) {
                            if (name.contains("_HF1") && name.endsWith(".dng", ignoreCase = true)) hf1 = file.uri
                            if (name.contains("_HF2") && name.endsWith(".dng", ignoreCase = true)) hf2 = file.uri
                        }
                    }
                } catch (e: Exception) {}
                if (hf1 != null && hf2 != null) break
            }

            if (hf1 == null || hf2 == null) {
                try {
                    val document = DocumentFile.fromSingleUri(context, uri)
                    val parent = document?.parentFile
                    parent?.listFiles()?.forEach { file ->
                        val name = file.name ?: return@forEach
                        if (name.startsWith(baseName)) {
                            if (name.contains("_HF1") && name.endsWith(".dng", ignoreCase = true)) hf1 = file.uri
                            if (name.contains("_HF2") && name.endsWith(".dng", ignoreCase = true)) hf2 = file.uri
                        }
                    }
                } catch (e: Exception) {}
            }
        }

        return hf1 to hf2
    }

    private fun scanSafFolder(folderUri: String, groups: MutableMap<String, ImageGroupBuilder>) {
        try {
            val treeUri = Uri.parse(folderUri)
            val root = DocumentFile.fromTreeUri(context, treeUri)
            root?.listFiles()?.forEach { file ->
                val name = file.name ?: return@forEach
                val baseName = getBaseName(name)
                val builder = groups.getOrPut(baseName) { ImageGroupBuilder(baseName) }
                val lastModified = file.lastModified()

                when {
                    name.endsWith(".jpg", ignoreCase = true) || name.endsWith(".jpeg", ignoreCase = true) -> {
                        builder.setJpg(file.uri, lastModified)
                        // Try reading EXIF for layout and dimensions
                        try {
                            context.contentResolver.openFileDescriptor(file.uri, "r")?.use { pfd ->
                                val exif = androidx.exifinterface.media.ExifInterface(pfd.fileDescriptor)
                                val comment = exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_USER_COMMENT)
                                parseUserComment(comment, builder)

                                val orientation = exif.getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL)
                                val w = exif.getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_IMAGE_WIDTH, 0)
                                val h = exif.getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_IMAGE_LENGTH, 0)

                                if (orientation == androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 || orientation == androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270) {
                                    builder.width = h
                                    builder.height = w
                                } else {
                                    builder.width = w
                                    builder.height = h
                                }
                            }
                        } catch (e: Exception) {
                            android.util.Log.w("ImageRepository", "Failed to read EXIF from ${file.uri}", e)
                        }
                    }
                    name.contains("_HF2") && name.endsWith(".dng", ignoreCase = true) -> {
                        builder.setDng2(file.uri, lastModified)
                    }
                    name.contains("_HF1") && name.endsWith(".dng", ignoreCase = true) -> {
                        builder.setDng1(file.uri, lastModified)
                    }
                    name.endsWith(".dng", ignoreCase = true) -> {
                        builder.setDng(file.uri, lastModified)
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("ImageRepository", "Failed to scan SAF folder: $folderUri", e)
        }
    }

    private fun scanAllDngs(groups: MutableMap<String, ImageGroupBuilder>) {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.MIME_TYPE
        )

        val selection = "${MediaStore.MediaColumns.MIME_TYPE} = ?"
        val selectionArgs = arrayOf("image/x-adobe-dng")

        context.contentResolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            val uriColumn = MediaStore.MediaColumns._ID

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val name = cursor.getString(nameColumn)
                val date = cursor.getLong(dateColumn) * 1000
                val uri = ContentUris.withAppendedId(collection, id)

                val baseName = getBaseName(name)
                val builder = groups.getOrPut(baseName) { ImageGroupBuilder(baseName) }

                if (name.contains("_HF2")) {
                    builder.setDng2(uri, date)
                } else if (name.contains("_HF1")) {
                    builder.setDng1(uri, date)
                } else {
                    builder.setDng(uri, date)
                }
            }
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
                    mime == "image/jpeg" -> {
                        builder.setJpg(uri, date)
                        try {
                            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                                val exif = androidx.exifinterface.media.ExifInterface(pfd.fileDescriptor)
                                val comment = exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_USER_COMMENT)
                                parseUserComment(comment, builder)

                                val orientation = exif.getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL)
                                val w = exif.getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_IMAGE_WIDTH, 0)
                                val h = exif.getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_IMAGE_LENGTH, 0)

                                if (orientation == androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 || orientation == androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270) {
                                    builder.width = h
                                    builder.height = w
                                } else {
                                    builder.width = w
                                    builder.height = h
                                }
                            }
                        } catch (e: Exception) {
                            android.util.Log.w("ImageRepository", "Failed to read EXIF from $uri", e)
                        }
                    }
                    name.contains("_HF2") && (mime == "image/x-adobe-dng" || name.endsWith(".dng", ignoreCase = true)) -> {
                        builder.setDng2(uri, date)
                    }
                    name.contains("_HF1") && (mime == "image/x-adobe-dng" || name.endsWith(".dng", ignoreCase = true)) -> {
                        builder.setDng1(uri, date)
                    }
                    mime == "image/x-adobe-dng" || name.endsWith(".dng", ignoreCase = true) -> {
                        builder.setDng(uri, date)
                    }
                }
            }
        }
    }

    private fun parseUserComment(comment: String?, builder: ImageGroupBuilder) {
        val config = top.maary.darkbag.utils.ImageUtils.parseUserComment(comment)
        if (config != null) {
            builder.editConfig = config
            if (config.hfLayout != null) {
                builder.hfLayout = config.hfLayout
            }
        }
    }

    fun readDngBaselineExposure(uri: Uri, isHalfFrame: Boolean, index: Int = 0): EditConfig? {
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                val exif = androidx.exifinterface.media.ExifInterface(pfd.fileDescriptor)
                // TAG_BASELINE_EXPOSURE is 50730
                val baselineExposure = exif.getAttributeDouble("BaselineExposure", 0.0).toFloat()
                val digitalGain = Math.pow(2.0, baselineExposure.toDouble()).toFloat()

                if (isHalfFrame) {
                    val adjs = mutableListOf(top.maary.darkbag.models.BasicAdjustments(), top.maary.darkbag.models.BasicAdjustments())
                    adjs[index] = top.maary.darkbag.models.BasicAdjustments(exposure = baselineExposure, digitalGain = digitalGain)
                    EditConfig(adjustments = adjs)
                } else {
                    EditConfig(exposure = baselineExposure, digitalGain = digitalGain)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("ImageRepository", "Failed to read baseline exposure from $uri", e)
            null
        }
    }

    private fun getBaseName(fileName: String): String {
        return top.maary.darkbag.utils.ImageUtils.getBaseName(fileName)
    }

    suspend fun getStudioGroups(forceRefresh: Boolean = false): List<ImageGroup> {
        if (!forceRefresh) {
            cachedStudioGroups?.let { return it }
        }

        return withContext(Dispatchers.IO) {
            val studioGroups = mutableMapOf<String, ImageGroupBuilder>()
            val prefs = context.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)

            // 1. Scan internal studio folder
            scanStudioFolder(studioGroups)

            // 2. Scan external JPG storage and Darkbag folder to find matching JPGs for the internal DNGs
            val jpgFolderUri = prefs.getString(SettingsFragment.KEY_JPG_STORAGE_URI, null)
            val exportFolderUri = prefs.getString(SettingsFragment.KEY_EXPORT_STORAGE_URI, null)

            val externalFolders = mutableSetOf<String>()
            jpgFolderUri?.let { externalFolders.add(it) }
            exportFolderUri?.let { externalFolders.add(it) }

            // Only search for JPGs that match existing internal DNG base names to maintain sandbox isolation
            val internalBaseNames = studioGroups.keys.toSet()

            for (folderUri in externalFolders) {
                scanSafFolderForMatchingJpgs(folderUri, studioGroups, internalBaseNames)
            }

            // Also scan MediaStore Darkbag folder for matching JPGs
            scanMediaStoreForMatchingJpgs(studioGroups, internalBaseNames)

            val result = studioGroups.values
                .map { it.build() }
                .filter { it.hasAny() }
                .sortedByDescending { it.captureTime }

            cachedStudioGroups = result
            result
        }
    }

    private fun scanStudioFolder(groups: MutableMap<String, ImageGroupBuilder>) {
        val files = studioFolder.listFiles() ?: return
        for (file in files) {
            val name = file.name
            if (!name.endsWith(".dng", ignoreCase = true)) continue

            val baseName = getBaseName(name)
            val builder = groups.getOrPut(baseName) { ImageGroupBuilder(baseName) }
            val lastModified = file.lastModified()
            val uri = Uri.fromFile(file)

            when {
                name.contains("_HF2") -> builder.setDng2(uri, lastModified)
                name.contains("_HF1") -> builder.setDng1(uri, lastModified)
                else -> builder.setDng(uri, lastModified)
            }

            // Extract dimensions for waterfall layout stability
            try {
                val exif = androidx.exifinterface.media.ExifInterface(file.absolutePath)
                val orientation = exif.getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL)
                val w = exif.getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_IMAGE_WIDTH, 0)
                val h = exif.getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_IMAGE_LENGTH, 0)
                if (w > 0 && h > 0) {
                    if (orientation == androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 || orientation == androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270) {
                        builder.width = h
                        builder.height = w
                    } else {
                        builder.width = w
                        builder.height = h
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("ImageRepository", "Failed to read dimensions for $name", e)
            }
        }
    }

    private fun scanSafFolderForMatchingJpgs(folderUri: String, groups: MutableMap<String, ImageGroupBuilder>, filter: Set<String>) {
        try {
            val treeUri = Uri.parse(folderUri)
            val root = DocumentFile.fromTreeUri(context, treeUri)
            root?.listFiles()?.forEach { file ->
                val name = file.name ?: return@forEach
                if (!name.endsWith(".jpg", ignoreCase = true) && !name.endsWith(".jpeg", ignoreCase = true)) return@forEach

                val baseName = getBaseName(name)
                if (!filter.contains(baseName)) return@forEach

                val builder = groups[baseName] ?: return@forEach
                builder.setJpg(file.uri, file.lastModified())

                try {
                    context.contentResolver.openFileDescriptor(file.uri, "r")?.use { pfd ->
                        val exif = androidx.exifinterface.media.ExifInterface(pfd.fileDescriptor)
                        val comment = exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_USER_COMMENT)
                        parseUserComment(comment, builder)
                    }
                } catch (e: Exception) {}
            }
        } catch (e: Exception) {
            android.util.Log.e("ImageRepository", "Failed to scan SAF for matching JPGs", e)
        }
    }

    private fun scanMediaStoreForMatchingJpgs(groups: MutableMap<String, ImageGroupBuilder>, filter: Set<String>) {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.MIME_TYPE
        )

        val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.MIME_TYPE} = ?"
        } else {
            "${MediaStore.MediaColumns.DATA} LIKE ? AND ${MediaStore.MediaColumns.MIME_TYPE} = ?"
        }
        val pathFilter = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "Pictures/Darkbag%" else "%Pictures/Darkbag%"
        val selectionArgs = arrayOf(pathFilter, "image/jpeg")

        context.contentResolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)

            while (cursor.moveToNext()) {
                val name = cursor.getString(nameColumn)
                val baseName = getBaseName(name)
                if (!filter.contains(baseName)) continue

                val id = cursor.getLong(idColumn)
                val uri = ContentUris.withAppendedId(collection, id)
                val date = cursor.getLong(dateColumn) * 1000

                val builder = groups[baseName] ?: continue
                builder.setJpg(uri, date)

                try {
                    context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                        val exif = androidx.exifinterface.media.ExifInterface(pfd.fileDescriptor)
                        val comment = exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_USER_COMMENT)
                        parseUserComment(comment, builder)
                    }
                } catch (e: Exception) {}
            }
        }
    }

    suspend fun importToStudio(uri: Uri, targetBaseName: String? = null, suffix: String? = null): Uri? = withContext(Dispatchers.IO) {
        try {
            val name = resolveFilename(uri) ?: "imported_${System.currentTimeMillis()}.dng"
            val finalBaseName = targetBaseName ?: getBaseName(name)
            var finalName = if (suffix != null) "${finalBaseName}${suffix}.dng" else "${finalBaseName}.dng"

            // Avoid overwriting if possible
            var destFile = File(studioFolder, finalName)
            if (destFile.exists()) {
                finalName = "${finalBaseName}_${System.currentTimeMillis()}${suffix ?: ""}.dng"
                destFile = File(studioFolder, finalName)
            }

            context.contentResolver.openInputStream(uri)?.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            Uri.fromFile(destFile)
        } catch (e: Exception) {
            android.util.Log.e("ImageRepository", "Failed to import file to studio", e)
            null
        }
    }

    suspend fun promoteToGroup(uri1: Uri, uri2: Uri, newBaseName: String): Pair<Uri, Uri>? = withContext(Dispatchers.IO) {
        try {
            val file1 = if (uri1.scheme == "file") File(uri1.path!!) else null
            val file2 = if (uri2.scheme == "file") File(uri2.path!!) else null

            if (file1 == null || file2 == null || !file1.exists() || !file2.exists()) return@withContext null

            val dest1 = File(studioFolder, "${newBaseName}_HF1.dng")
            val dest2 = File(studioFolder, "${newBaseName}_HF2.dng")

            // If it's the same file (already promoted), skip
            if (file1.absolutePath == dest1.absolutePath && file2.absolutePath == dest2.absolutePath) {
                return@withContext uri1 to uri2
            }

            if (file1.renameTo(dest1) && file2.renameTo(dest2)) {
                invalidateCache()
                Uri.fromFile(dest1) to Uri.fromFile(dest2)
            } else null
        } catch (e: Exception) {
            android.util.Log.e("ImageRepository", "Failed to promote to group", e)
            null
        }
    }

    fun deleteStudioGroup(group: ImageGroup) {
        group.dngUri?.let { if (it.scheme == "file") File(it.path!!).delete() }
        group.dngUri1?.let { if (it.scheme == "file") File(it.path!!).delete() }
        group.dngUri2?.let { if (it.scheme == "file") File(it.path!!).delete() }
        // We do NOT delete the JPG as it is in external storage and might be valuable to the user
        invalidateCache()
    }

    private inner class ImageGroupBuilder(val baseName: String) {
        var jpgUri: Uri? = null
        private var jpgTime: Long = 0L
        var dngUri: Uri? = null
        private var dngTime: Long = 0L
        var dngUri1: Uri? = null
        private var dngUri1Time: Long = 0L
        var dngUri2: Uri? = null
        private var dngUri2Time: Long = 0L
        var hfLayout: String? = null
        var width: Int = 0
        var height: Int = 0
        var captureTime: Long = 0L
        var editConfig: EditConfig? = null

        fun setJpg(uri: Uri, time: Long) {
            // Prefer _stitched or simply newer JPG if both exist
            if (jpgUri == null || time > jpgTime) {
                jpgUri = uri
                jpgTime = time
            }
            updateTime(time)
        }

        fun setDng(uri: Uri, time: Long) {
            if (dngUri == null || time > dngTime) {
                dngUri = uri
                dngTime = time
            }
            updateTime(time)
        }

        fun setDng1(uri: Uri, time: Long) {
            // For HF1, prefer the one with earlier time in case of conflict
            if (dngUri1 == null || time < dngUri1Time) {
                dngUri1 = uri
                dngUri1Time = time
            }
            updateTime(time)
        }

        fun setDng2(uri: Uri, time: Long) {
            // For HF2, prefer the one with later time in case of conflict
            if (dngUri2 == null || time > dngUri2Time) {
                dngUri2 = uri
                dngUri2Time = time
            }
            updateTime(time)
        }

        fun updateTime(time: Long) {
            if (time > captureTime) captureTime = time
        }

        fun build(): ImageGroup {
            return ImageGroup(
                baseName,
                jpgUri,
                dngUri,
                dngUri1,
                dngUri2,
                hfLayout,
                width,
                height,
                captureTime,
                dngUri1Time,
                dngUri2Time,
                maxOf(jpgTime, dngTime, dngUri1Time, dngUri2Time),
                editConfig
            )
        }
    }
}
