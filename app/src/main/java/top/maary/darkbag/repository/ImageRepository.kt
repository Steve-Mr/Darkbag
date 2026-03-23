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
                .filter { it.hasAny() }
                .sortedByDescending { it.captureTime }

            cachedGroups = result
            result
        }
    }

    fun invalidateCache() {
        cachedGroups = null
    }

    private fun scanSafFolder(folderUri: String, groups: MutableMap<String, ImageGroupBuilder>) {
        try {
            val treeUri = Uri.parse(folderUri)
            val root = DocumentFile.fromTreeUri(context, treeUri)
            root?.listFiles()?.forEach { file ->
                val name = file.name ?: return@forEach
                if (!name.startsWith(DarkbagIdentity.FILE_PREFIX, ignoreCase = true)) return@forEach
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
                if (!name.startsWith(DarkbagIdentity.FILE_PREFIX, ignoreCase = true)) continue
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
        if (comment?.startsWith("HF_LAYOUT:") == true) {
            builder.hfLayout = comment.substringAfter("HF_LAYOUT:")
        } else if (comment?.startsWith("{") == true) {
            val config = parseEditConfig(comment)
            builder.editConfig = config
            if (config?.hfLayout != null) {
                builder.hfLayout = config.hfLayout
            }
        }
    }

    private fun parseEditConfig(jsonStr: String): EditConfig? {
        return try {
            val json = JSONObject(jsonStr)
            val adjustmentsArray = json.optJSONArray("adjustments")
            val adjustments = if (adjustmentsArray != null) {
                List(adjustmentsArray.length()) { i ->
                    val adjJson = adjustmentsArray.getJSONObject(i)
                    top.maary.darkbag.models.BasicAdjustments(
                        exposure = adjJson.optDouble("exposure", 0.0).toFloat(),
                        contrast = adjJson.optDouble("contrast", 0.0).toFloat(),
                        saturation = adjJson.optDouble("saturation", 0.0).toFloat(),
                        highlights = adjJson.optDouble("highlights", 0.0).toFloat(),
                        shadows = adjJson.optDouble("shadows", 0.0).toFloat(),
                        whites = adjJson.optDouble("whites", 0.0).toFloat(),
                        blacks = adjJson.optDouble("blacks", 0.0).toFloat(),
                        digitalGain = adjJson.optDouble("digital_gain", 1.0).toFloat()
                    )
                }
            } else null

            EditConfig(
                log = json.optString("log", "None"),
                lut = json.optString("lut", "None"),
                exposure = json.optDouble("exposure", 0.0).toFloat(),
                contrast = json.optDouble("contrast", 0.0).toFloat(),
                saturation = json.optDouble("saturation", 0.0).toFloat(),
                highlights = json.optDouble("highlights", 0.0).toFloat(),
                shadows = json.optDouble("shadows", 0.0).toFloat(),
                whites = json.optDouble("whites", 0.0).toFloat(),
                blacks = json.optDouble("blacks", 0.0).toFloat(),
                digitalGain = json.optDouble("digital_gain", 1.0).toFloat(),
                adjustments = adjustments,
                showTimestamp = json.optBoolean("show_timestamp", false),
                flareType = json.optInt("flare_type", -1),
                hfLayout = json.optString("hf_layout", null),
                zoomFactor = json.optDouble("zoom_factor", 1.0).toFloat()
            )
        } catch (e: Exception) {
            null
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
            val finalTime1 = if (dngUri1 != null) top.maary.darkbag.utils.ImageUtils.getCaptureTime(context, dngUri1!!).takeIf { it > 0 } ?: dngUri1Time else captureTime
            val finalTime2 = if (dngUri2 != null) top.maary.darkbag.utils.ImageUtils.getCaptureTime(context, dngUri2!!).takeIf { it > 0 } ?: dngUri2Time else captureTime
            val finalTime = if (dngUri != null) top.maary.darkbag.utils.ImageUtils.getCaptureTime(context, dngUri!!).takeIf { it > 0 } ?: captureTime else captureTime

            return ImageGroup(
                baseName,
                jpgUri,
                dngUri,
                dngUri1,
                dngUri2,
                hfLayout,
                width,
                height,
                finalTime,
                finalTime1,
                finalTime2,
                maxOf(jpgTime, dngTime, dngUri1Time, dngUri2Time),
                editConfig
            )
        }
    }
}
