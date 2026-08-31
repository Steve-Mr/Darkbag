package top.maary.darkbag.repository

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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
            val prefs =
                context.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)

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

            // 2. Scan MediaStore for Darkbag folder
            scanMediaStore(groups)

            val result = groups.values
                .map { it.build() }
                .filter { it.hasAny() && !it.isInProgress }
                .sortedByDescending { it.captureTime }

            cachedGroups = result
            result
        }
    }

    fun getGroupedImagesFlow(initialUri: String? = null): Flow<List<ImageGroup>> = flow {
        val prefs = context.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        val groups = mutableMapOf<String, ImageGroupBuilder>()

        // Pre-populate with cached metadata if available
        cachedGroups?.forEach { cached ->
            groups[cached.baseName] = ImageGroupBuilder(cached.baseName).applyFrom(cached)
        }

        suspend fun emitCurrent() {
            val sorted = groups.values
                .map { it.build() }
                .filter { it.hasAny() && !it.isInProgress }
                .sortedByDescending { it.captureTime }
            cachedGroups = sorted
            emit(sorted)
        }

        // Stage 1: Fast scan MediaStore (usually fastest as it's an indexed database)
        withContext(Dispatchers.IO) {
            scanMediaStore(groups, fast = true)
            preloadInitialMetadata(groups, initialUri)
        }
        emitCurrent()

        // Stage 2: Scan prioritized SAF folders
        val safFolders = listOf(
            prefs.getString(SettingsFragment.KEY_JPG_STORAGE_URI, null),
            prefs.getString(SettingsFragment.KEY_RAW_STORAGE_URI, null)
        )

        for (folderUri in safFolders) {
            if (folderUri != null) {
                withContext(Dispatchers.IO) {
                    scanSafFolder(folderUri, groups, fast = true)
                    preloadInitialMetadata(groups, initialUri)
                }
                emitCurrent()
            }
        }
    }

    private fun preloadInitialMetadata(groups: Map<String, ImageGroupBuilder>, initialUri: String?) {
        if (initialUri == null) return
        val targetBuilder = groups.values.firstOrNull { builder ->
            builder.jpgUri?.toString() == initialUri ||
                    builder.dngUri?.toString() == initialUri ||
                    builder.dngUri1?.toString() == initialUri ||
                    builder.dngUri2?.toString() == initialUri
        }
        if (targetBuilder != null && !targetBuilder.metadataLoaded) {
            populateMetadata(targetBuilder)
        }
    }

    private fun populateMetadata(builder: ImageGroupBuilder) {
        if (builder.metadataLoaded) return

        val rawVideoUri = builder.rawVideoUri
        if (rawVideoUri != null) {
            try {
                context.contentResolver.openFileDescriptor(rawVideoUri, "r")?.use { pfd ->
                    val fd = pfd.fd
                    val handle = top.maary.darkbag.rawvideo.RawVideoNative.nativeOpenReaderFd(fd)
                    if (handle != 0L) {
                        val header = top.maary.darkbag.rawvideo.RawVideoNative.readHeader(handle)
                        if (header != null) {
                            builder.width = header.width
                            builder.height = header.height
                            builder.rawVideoFps = header.fps
                            builder.rawVideoFrameCount = header.frameCount
                            builder.rawVideoDurationMs = if (header.fps > 0) (header.frameCount * 1000L / header.fps).toLong() else 0L
                            val activeLut = header.activeLutName.takeIf { it.isNotBlank() }
                            val activeLog = header.activeLogName.takeIf { it.isNotBlank() }
                            if (activeLut != null || activeLog != null) {
                                builder.editConfig = EditConfig(log = activeLog ?: "None", lut = activeLut ?: "None")
                            }
                        }
                        top.maary.darkbag.rawvideo.RawVideoNative.nativeCloseReader(handle)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("ImageRepository", "Failed to read raw video metadata for $rawVideoUri", e)
            }
            builder.metadataLoaded = true
            return
        }

        val jpgUri = builder.jpgUri
        if (jpgUri == null) {
            builder.metadataLoaded = true
            return
        }

        try {
            context.contentResolver.openFileDescriptor(jpgUri, "r")?.use { pfd ->
                var motionInfoParsed = false
                try {
                    val exif = androidx.exifinterface.media.ExifInterface(pfd.fileDescriptor)
                    val comment =
                        exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_USER_COMMENT)
                    parseUserComment(comment, builder)

                    val orientation = exif.getAttributeInt(
                        androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                        androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
                    )
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

                    // Try reading XMP payload directly from ExifInterface to avoid re-scanning APP segments
                    val xmpBytes = exif.getAttributeBytes(androidx.exifinterface.media.ExifInterface.TAG_XMP)
                    if (xmpBytes != null && xmpBytes.isNotEmpty()) {
                        val xmpStr = String(xmpBytes, java.nio.charset.StandardCharsets.UTF_8)
                        val motionInfo = top.maary.darkbag.motionphoto.MotionPhotoReader.parseXmpPayload(xmpStr, pfd.statSize)
                        if (motionInfo != null) {
                            builder.isMotionPhoto = true
                            builder.motionPhotoPtsUs = motionInfo.presentationTimestampUs
                            builder.motionPhotoVideoLength = motionInfo.videoLength
                            motionInfoParsed = true
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.w("ImageRepository", "Failed to read EXIF from $jpgUri", e)
                }

                if (!motionInfoParsed) {
                    try {
                        android.system.Os.lseek(pfd.fileDescriptor, 0L, android.system.OsConstants.SEEK_SET)
                        val motionInfo = top.maary.darkbag.motionphoto.MotionPhotoReader.parseMotionPhotoInfo(pfd)
                        if (motionInfo != null) {
                            builder.isMotionPhoto = true
                            builder.motionPhotoPtsUs = motionInfo.presentationTimestampUs
                            builder.motionPhotoVideoLength = motionInfo.videoLength
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("ImageRepository", "Failed to read Motion Photo from $jpgUri", e)
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("ImageRepository", "Failed to open $jpgUri", e)
        }
        builder.metadataLoaded = true
    }

    suspend fun loadMetadata(group: ImageGroup): ImageGroup = withContext(Dispatchers.IO) {
        if (group.metadataLoaded) return@withContext group

        val builder = ImageGroupBuilder(group.baseName).applyFrom(group)
        populateMetadata(builder)

        val updated = builder.build()
        val currentCache = cachedGroups
        cachedGroups = if (currentCache != null) {
            if (currentCache.any { it.baseName == updated.baseName }) {
                currentCache.map { if (it.baseName == updated.baseName) updated else it }
            } else {
                currentCache + updated
            }
        } else {
            listOf(updated)
        }
        updated
    }

    fun invalidateCache() {
        cachedGroups = null
    }

    private fun scanSafFolder(
        folderUri: String,
        groups: MutableMap<String, ImageGroupBuilder>,
        fast: Boolean = false
    ) {
        try {
            val treeUri = Uri.parse(folderUri)
            val docId = if (DocumentsContract.isTreeUri(treeUri)) {
                DocumentsContract.getTreeDocumentId(treeUri)
            } else {
                DocumentsContract.getDocumentId(treeUri)
            }
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
            )

            var scannedWithDirectQuery = false
            try {
                context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                    scannedWithDirectQuery = true
                    val idColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val nameColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val modifiedColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

                    while (cursor.moveToNext()) {
                        if (idColumn == -1 || nameColumn == -1) continue
                        val childDocId = cursor.getString(idColumn) ?: continue
                        val name = cursor.getString(nameColumn) ?: continue
                        if (!name.startsWith(DarkbagIdentity.FILE_PREFIX, ignoreCase = true)) continue
                        val baseName = getBaseName(name)
                        val builder = groups.getOrPut(baseName) { ImageGroupBuilder(baseName) }
                        val lastModified = if (modifiedColumn != -1) cursor.getLong(modifiedColumn) else 0L
                        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childDocId)

                        when {
                            name.contains("_MULTI_") && (name.endsWith(".jpg", ignoreCase = true) || name.endsWith(".jpeg", ignoreCase = true)) -> {
                                builder.addMultiJpg(docUri, name, lastModified)
                                if (!fast) {
                                    readExifForScanning(docUri, builder)
                                }
                            }
                            name.contains("_MULTI_") && name.endsWith(".dng", ignoreCase = true) -> {
                                builder.addMultiDng(docUri, name, lastModified)
                            }
                            name.endsWith(".jpg", ignoreCase = true) || name.endsWith(".jpeg", ignoreCase = true) -> {
                                builder.setJpg(docUri, lastModified)
                                if (!fast) {
                                    readExifForScanning(docUri, builder)
                                }
                            }
                            name.contains("_HF2") && name.endsWith(".dng", ignoreCase = true) -> {
                                builder.setDng2(docUri, lastModified)
                            }
                            name.contains("_HF1") && name.endsWith(".dng", ignoreCase = true) -> {
                                builder.setDng1(docUri, lastModified)
                            }
                            name.endsWith(".dng", ignoreCase = true) -> {
                                builder.setDng(docUri, lastModified)
                            }
                            name.endsWith(".rawvid", ignoreCase = true) -> {
                                builder.setRawVideo(docUri, lastModified)
                            }
                            name.endsWith(".mp4", ignoreCase = true) -> {
                                builder.setMp4Video(docUri, lastModified)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("ImageRepository", "Direct SAF children query failed for $folderUri, falling back", e)
            }

            // Fallback for providers that don't support buildChildDocumentsUriUsingTree
            if (!scannedWithDirectQuery) {
                val root = DocumentFile.fromTreeUri(context, treeUri)
                root?.listFiles()?.forEach { file ->
                    val name = file.name ?: return@forEach
                    if (!name.startsWith(DarkbagIdentity.FILE_PREFIX, ignoreCase = true)) return@forEach
                    val baseName = getBaseName(name)
                    val builder = groups.getOrPut(baseName) { ImageGroupBuilder(baseName) }
                    val lastModified = file.lastModified()

                    when {
                        name.contains("_MULTI_") && (name.endsWith(".jpg", ignoreCase = true) || name.endsWith(".jpeg", ignoreCase = true)) -> {
                            builder.addMultiJpg(file.uri, name, lastModified)
                            if (!fast) {
                                readExifForScanning(file.uri, builder)
                            }
                        }
                        name.contains("_MULTI_") && name.endsWith(".dng", ignoreCase = true) -> {
                            builder.addMultiDng(file.uri, name, lastModified)
                        }
                        name.endsWith(".jpg", ignoreCase = true) || name.endsWith(".jpeg", ignoreCase = true) -> {
                            builder.setJpg(file.uri, lastModified)
                            if (!fast) {
                                readExifForScanning(file.uri, builder)
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
                        name.endsWith(".rawvid", ignoreCase = true) -> {
                            builder.setRawVideo(file.uri, lastModified)
                        }
                        name.endsWith(".mp4", ignoreCase = true) -> {
                            builder.setMp4Video(file.uri, lastModified)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("ImageRepository", "Failed to scan SAF folder: $folderUri", e)
        }
    }

    private fun readExifForScanning(uri: Uri, builder: ImageGroupBuilder) {
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                val exif = androidx.exifinterface.media.ExifInterface(pfd.fileDescriptor)
                val comment = exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_USER_COMMENT)
                parseUserComment(comment, builder)

                val orientation = exif.getAttributeInt(
                    androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                    androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
                )
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
            }
        } catch (e: Exception) {
            android.util.Log.w("ImageRepository", "Failed to read EXIF from $uri", e)
        }
    }

    private fun scanMediaStore(groups: MutableMap<String, ImageGroupBuilder>, fast: Boolean = false) {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT
        )

        val collections = mutableListOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            collections.add(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL))
        } else {
            collections.add(MediaStore.Files.getContentUri("external"))
        }

        val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? OR ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
        } else {
            "${MediaStore.MediaColumns.DATA} LIKE ? OR ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
        }
        val selectionArgs = arrayOf("%Darkbag%", "${DarkbagIdentity.FILE_PREFIX}%")

        for (collection in collections) {
            try {
                context.contentResolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
                    val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                    val modifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                    val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                    val widthColumn = cursor.getColumnIndex(MediaStore.MediaColumns.WIDTH)
                    val heightColumn = cursor.getColumnIndex(MediaStore.MediaColumns.HEIGHT)

                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idColumn)
                        val name = cursor.getString(nameColumn) ?: continue
                        if (!name.startsWith(DarkbagIdentity.FILE_PREFIX, ignoreCase = true)) continue
                        val date = cursor.getLong(dateColumn) * 1000 // Convert to ms
                        val modified = cursor.getLong(modifiedColumn) * 1000 // Convert to ms
                        val mime = cursor.getString(mimeColumn)
                        val uri = ContentUris.withAppendedId(collection, id)

                        val baseName = getBaseName(name)
                        val builder = groups.getOrPut(baseName) { ImageGroupBuilder(baseName) }

                        if (widthColumn != -1 && heightColumn != -1 && builder.width == 0 && builder.height == 0) {
                            val w = cursor.getInt(widthColumn)
                            val h = cursor.getInt(heightColumn)
                            if (w > 0 && h > 0) {
                                builder.width = w
                                builder.height = h
                            }
                        }

                        when {
                            name.contains("_MULTI_") && mime == "image/jpeg" -> {
                                builder.addMultiJpg(uri, name, date, modified)
                                if (!fast) {
                                    readExifForScanning(uri, builder)
                                }
                            }
                            name.contains("_MULTI_") && (mime == "image/x-adobe-dng" || name.endsWith(".dng", ignoreCase = true)) -> {
                                builder.addMultiDng(uri, name, date, modified)
                            }
                            mime == "image/jpeg" || name.endsWith(".jpg", ignoreCase = true) || name.endsWith(".jpeg", ignoreCase = true) -> {
                                builder.setJpg(uri, date, modified)
                                if (!fast) {
                                    readExifForScanning(uri, builder)
                                }
                            }
                            name.contains("_HF2") && (mime == "image/x-adobe-dng" || name.endsWith(".dng", ignoreCase = true)) -> {
                                builder.setDng2(uri, date, modified)
                            }
                            name.contains("_HF1") && (mime == "image/x-adobe-dng" || name.endsWith(".dng", ignoreCase = true)) -> {
                                builder.setDng1(uri, date, modified)
                            }
                            mime == "image/x-adobe-dng" || name.endsWith(".dng", ignoreCase = true) -> {
                                builder.setDng(uri, date, modified)
                            }
                            name.endsWith(".rawvid", ignoreCase = true) -> {
                                builder.setRawVideo(uri, date, modified)
                            }
                            mime == "video/mp4" || name.endsWith(".mp4", ignoreCase = true) -> {
                                builder.setMp4Video(uri, date, modified)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("ImageRepository", "Error querying collection $collection", e)
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
                hfLayout = if (json.has("hf_layout")) json.optString("hf_layout") else null,
                isSwapped = json.optBoolean("is_swapped", false),
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

    fun getCaptureMetadata(uri: Uri): top.maary.darkbag.models.CaptureMetadata? {
        if (uri == Uri.EMPTY) return null
        return try {
            val hasMediaLocPerm = top.maary.darkbag.utils.LocationHelper.hasMediaLocationPermission(context)
            val targetUri = if (hasMediaLocPerm && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && uri.scheme == "content" && uri.authority == "media") {
                try {
                    MediaStore.setRequireOriginal(uri)
                } catch (e: Exception) {
                    uri
                }
            } else {
                uri
            }

            val pfd = try {
                context.contentResolver.openFileDescriptor(targetUri, "r")
            } catch (e: SecurityException) {
                if (targetUri != uri) {
                    // Fallback to standard Uri if setRequireOriginal failed due to missing permission
                    context.contentResolver.openFileDescriptor(uri, "r")
                } else {
                    throw e
                }
            }

            pfd?.use { fd ->
                val exif = androidx.exifinterface.media.ExifInterface(fd.fileDescriptor)
                val iso = exif.getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ISO_SPEED_RATINGS, 0).takeIf { it > 0 }
                val exposureTime = (exif.getAttributeDouble(androidx.exifinterface.media.ExifInterface.TAG_EXPOSURE_TIME, 0.0) * 1_000_000_000).toLong().takeIf { it > 0 }
                val fNumber = exif.getAttributeDouble(androidx.exifinterface.media.ExifInterface.TAG_F_NUMBER, 0.0).toFloat().takeIf { it > 0 }
                val focalLength = exif.getAttributeDouble(androidx.exifinterface.media.ExifInterface.TAG_FOCAL_LENGTH, 0.0).toFloat().takeIf { it > 0 }
                val dateTimeStr = exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_DATETIME_ORIGINAL)
                val dateTimeDigitizedStr = exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_DATETIME_DIGITIZED)
                val sdf = java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.US)
                val dateTime = dateTimeStr?.let {
                    sdf.parse(it)?.time
                }
                val dateTimeDigitized = dateTimeDigitizedStr?.let {
                    sdf.parse(it)?.time
                }
                val offsetTime = exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_OFFSET_TIME)
                val offsetTimeOriginal = exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_OFFSET_TIME_ORIGINAL)
                val offsetTimeDigitized = exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_OFFSET_TIME_DIGITIZED)

                val make = exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_MAKE)
                val model = exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_MODEL)
                val lensModel = exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_LENS_MODEL)
                val focalLengthIn35mm = exif.getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, 0).takeIf { it > 0 }

                val latLong = exif.latLong
                val location = if (latLong != null) {
                    android.location.Location("exif").apply {
                        latitude = latLong[0]
                        longitude = latLong[1]
                        val alt = exif.getAltitude(Double.NaN)
                        if (!alt.isNaN()) {
                            altitude = alt
                        }
                    }
                } else null

                top.maary.darkbag.models.CaptureMetadata(
                    iso = iso,
                    exposureTime = exposureTime,
                    fNumber = fNumber,
                    focalLength = focalLength,
                    focalLengthIn35mmFilm = focalLengthIn35mm,
                    dateTimeOriginal = dateTime,
                    dateTimeDigitized = dateTimeDigitized,
                    offsetTime = offsetTime,
                    offsetTimeOriginal = offsetTimeOriginal,
                    offsetTimeDigitized = offsetTimeDigitized,
                    make = make,
                    model = model,
                    lensModel = lensModel,
                    location = location
                )
            }
        } catch (e: Exception) {
            android.util.Log.e("ImageRepository", "Failed to get capture metadata from $uri", e)
            null
        }
    }

    private fun getBaseName(fileName: String): String {
        return top.maary.darkbag.utils.ImageUtils.getBaseName(fileName)
    }

    private class MultiLensItemBuilder(
        val lensTag: String,
        val multiplier: Float,
        var jpgUri: Uri? = null,
        var dngUri: Uri? = null
    ) {
        fun build(): top.maary.darkbag.models.MultiCameraLensItem = top.maary.darkbag.models.MultiCameraLensItem(
            lensTag = lensTag,
            multiplier = multiplier,
            jpgUri = jpgUri,
            dngUri = dngUri
        )
    }

    private inner class ImageGroupBuilder(val baseName: String) {
        var jpgUri: Uri? = null
        var jpgTime: Long = 0L
        var dngUri: Uri? = null
        var dngTime: Long = 0L
        var dngUri1: Uri? = null
        var dngUri1Time: Long = 0L
        var dngUri2: Uri? = null
        var dngUri2Time: Long = 0L
        var rawVideoUri: Uri? = null
        var rawVideoTime: Long = 0L
        var isRawVideo: Boolean = false
        var rawVideoFps: Float = 24.0f
        var rawVideoFrameCount: Int = 0
        var rawVideoDurationMs: Long = 0L
        var mp4VideoUri: Uri? = null
        var mp4VideoTime: Long = 0L
        var isMp4Video: Boolean = false
        val multiLensBuilders = mutableMapOf<String, MultiLensItemBuilder>()
        var isMultiCamera: Boolean = false
        var hfLayout: String? = null
        var width: Int = 0
        var height: Int = 0
        var captureTime: Long = 0L
        var lastModified: Long = 0L
        var editConfig: EditConfig? = null
        var isMotionPhoto: Boolean = false
        var motionPhotoPtsUs: Long = 0L
        var motionPhotoVideoLength: Long = 0L
        var metadataLoaded: Boolean = false

        fun applyFrom(group: ImageGroup): ImageGroupBuilder {
            jpgUri = group.jpgUri
            dngUri = group.dngUri
            dngUri1 = group.dngUri1
            dngUri2 = group.dngUri2
            rawVideoUri = group.rawVideoUri
            isRawVideo = group.isRawVideo
            rawVideoFps = group.rawVideoFps
            rawVideoFrameCount = group.rawVideoFrameCount
            rawVideoDurationMs = group.rawVideoDurationMs
            mp4VideoUri = group.mp4VideoUri
            isMp4Video = group.isMp4Video
            isMultiCamera = group.isMultiCamera
            multiLensBuilders.clear()
            for (lens in group.multiCameraLenses) {
                multiLensBuilders[lens.lensTag] = MultiLensItemBuilder(
                    lensTag = lens.lensTag,
                    multiplier = lens.multiplier,
                    jpgUri = lens.jpgUri,
                    dngUri = lens.dngUri
                )
            }
            hfLayout = group.hfLayout
            width = group.width
            height = group.height
            captureTime = group.captureTime
            lastModified = group.lastModified
            editConfig = group.editConfig
            isMotionPhoto = group.isMotionPhoto
            motionPhotoPtsUs = group.motionPhotoPtsUs
            motionPhotoVideoLength = group.motionPhotoVideoLength
            metadataLoaded = group.metadataLoaded
            return this
        }

        fun addMultiJpg(uri: Uri, fileName: String, time: Long, modifiedTime: Long = time) {
            isMultiCamera = true
            val tag = top.maary.darkbag.utils.ImageUtils.extractMultiCameraLensTag(fileName)
            val mult = top.maary.darkbag.utils.ImageUtils.extractMultiCameraMultiplier(fileName)
            val effectiveTag = if (tag.isNotEmpty()) tag else String.format(java.util.Locale.US, "%.1fx", mult)
            val item = multiLensBuilders.getOrPut(effectiveTag) { MultiLensItemBuilder(effectiveTag, mult) }
            item.jpgUri = uri
            updateTime(time, modifiedTime)
        }

        fun addMultiDng(uri: Uri, fileName: String, time: Long, modifiedTime: Long = time) {
            isMultiCamera = true
            val tag = top.maary.darkbag.utils.ImageUtils.extractMultiCameraLensTag(fileName)
            val mult = top.maary.darkbag.utils.ImageUtils.extractMultiCameraMultiplier(fileName)
            val effectiveTag = if (tag.isNotEmpty()) tag else String.format(java.util.Locale.US, "%.1fx", mult)
            val item = multiLensBuilders.getOrPut(effectiveTag) { MultiLensItemBuilder(effectiveTag, mult) }
            item.dngUri = uri
            updateTime(time, modifiedTime)
        }

        fun setRawVideo(uri: Uri, time: Long, modifiedTime: Long = time) {
            if (rawVideoUri == null || (time > rawVideoTime + 2000)) {
                rawVideoUri = uri
                rawVideoTime = time
                isRawVideo = true
            }
            updateTime(time, modifiedTime)
        }

        fun setMp4Video(uri: Uri, time: Long, modifiedTime: Long = time) {
            if (mp4VideoUri == null || (time > mp4VideoTime + 2000)) {
                mp4VideoUri = uri
                mp4VideoTime = time
                isMp4Video = true
            }
            updateTime(time, modifiedTime)
        }

        fun setJpg(uri: Uri, time: Long, modifiedTime: Long = time) {
            // Avoid flipping URI if we already have one for the same file (within 2s)
            // unless the new one is significantly newer (indicating a true update/edit)
            if (jpgUri == null || (time > jpgTime + 2000)) {
                jpgUri = uri
                jpgTime = time
            }
            updateTime(time, modifiedTime)
        }

        fun setDng(uri: Uri, time: Long, modifiedTime: Long = time) {
            if (dngUri == null || (time > dngTime + 2000)) {
                dngUri = uri
                dngTime = time
            }
            updateTime(time, modifiedTime)
        }

        fun setDng1(uri: Uri, time: Long, modifiedTime: Long = time) {
            if (dngUri1 == null || (time > dngUri1Time + 2000)) {
                dngUri1 = uri
                dngUri1Time = time
            }
            updateTime(time, modifiedTime)
        }

        fun setDng2(uri: Uri, time: Long, modifiedTime: Long = time) {
            if (dngUri2 == null || (time > dngUri2Time + 2000)) {
                dngUri2 = uri
                dngUri2Time = time
            }
            updateTime(time, modifiedTime)
        }

        fun updateTime(time: Long, modifiedTime: Long) {
            if (time > captureTime) captureTime = time
            if (modifiedTime > lastModified) lastModified = modifiedTime
        }

        fun build(): ImageGroup {
            var isInProgress = false
            var isPartial = false

            // Check for in-progress half-frame capture
            if (dngUri1 != null && dngUri2 == null && jpgUri == null) {
                val sideFile = top.maary.darkbag.utils.HalfFrameSessionStore(context).tempFileForProfile(top.maary.darkbag.utils.HalfFrameSessionStore.PROFILE_HALF_SIDE)
                val topFile = top.maary.darkbag.utils.HalfFrameSessionStore(context).tempFileForProfile(top.maary.darkbag.utils.HalfFrameSessionStore.PROFILE_HALF_TOP)

                val sideMatch = sideFile.exists() && Math.abs(sideFile.lastModified() - dngUri1Time) < 60000
                val topMatch = topFile.exists() && Math.abs(topFile.lastModified() - dngUri1Time) < 60000

                if (sideMatch || topMatch) {
                    isInProgress = true
                }
            }

            // Check for partial half-frame (JPG exists but exactly one DNG is missing)
            if (jpgUri != null && (hfLayout == "SBS" || hfLayout == "TB" || hfLayout == "Side-by-side" || hfLayout == "Top-bottom")) {
                if ((dngUri1 == null) xor (dngUri2 == null)) {
                    isPartial = true
                }
            }

            // If it's a half-frame DNG group but no layout is specified, default to SBS
            var finalLayout = hfLayout
            if (finalLayout == null && dngUri1 != null && dngUri2 != null && jpgUri == null) {
                finalLayout = "SBS"
            }

            val lenses = multiLensBuilders.values
                .map { it.build() }
                .sortedWith(compareBy<top.maary.darkbag.models.MultiCameraLensItem> { it.multiplier }.thenBy { it.lensTag })

            val sortedJpgs = lenses.mapNotNull { it.jpgUri }
            val sortedDngs = lenses.mapNotNull { it.dngUri }

            val finalJpgUri = if (isMultiCamera && sortedJpgs.isNotEmpty()) {
                lenses.find { it.lensTag == "1.0x" || it.lensTag == "1x" }?.jpgUri
                    ?: sortedJpgs.firstOrNull()
                    ?: jpgUri
            } else jpgUri

            return ImageGroup(
                baseName = baseName,
                jpgUri = finalJpgUri,
                dngUri = dngUri,
                dngUri1 = dngUri1,
                dngUri2 = dngUri2,
                hfLayout = finalLayout,
                width = width,
                height = height,
                captureTime = captureTime,
                lastModified = if (lastModified > 0) lastModified else maxOf(jpgTime, dngTime, dngUri1Time, dngUri2Time, rawVideoTime, mp4VideoTime),
                editConfig = editConfig,
                metadataLoaded = metadataLoaded,
                isInProgress = isInProgress,
                isPartial = isPartial,
                isMotionPhoto = isMotionPhoto,
                motionPhotoPtsUs = motionPhotoPtsUs,
                motionPhotoVideoLength = motionPhotoVideoLength,
                isMultiCamera = isMultiCamera || lenses.isNotEmpty(),
                multiJpgUris = sortedJpgs,
                multiDngUris = sortedDngs,
                multiCameraLenses = lenses,
                rawVideoUri = rawVideoUri,
                isRawVideo = isRawVideo,
                rawVideoFps = rawVideoFps,
                rawVideoFrameCount = rawVideoFrameCount,
                rawVideoDurationMs = rawVideoDurationMs,
                mp4VideoUri = mp4VideoUri,
                isMp4Video = isMp4Video
            )
        }
    }
}
