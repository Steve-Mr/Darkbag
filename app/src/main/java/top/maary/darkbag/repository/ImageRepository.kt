package top.maary.darkbag.repository

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.annotation.VisibleForTesting
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.json.JSONArray
import org.json.JSONObject
import top.maary.darkbag.fragments.SettingsFragment
import top.maary.darkbag.models.EditConfig
import top.maary.darkbag.models.ImageGroup
import top.maary.darkbag.utils.DarkbagIdentity
import java.io.File

class ImageRepository(
    private val context: Context,
    private val cacheStore: GalleryCacheStore = GalleryCacheStore.getInstance()
) {

    companion object {
        @Deprecated(
            "Use GalleryCacheStore directly or via ImageRepository instance to prevent static leaks",
            ReplaceWith("GalleryCacheStore.getInstance().cachedGroups")
        )
        @VisibleForTesting
        internal var cachedGroups: List<ImageGroup>?
            get() = GalleryCacheStore.getInstance().cachedGroups
            set(value) {
                GalleryCacheStore.getInstance().cachedGroups = value
            }

        @Deprecated(
            "Use GalleryCacheStore directly or via ImageRepository instance to prevent static leaks",
            ReplaceWith("GalleryCacheStore.getInstance().staleCachedGroups")
        )
        @VisibleForTesting
        internal var staleCachedGroups: List<ImageGroup>?
            get() = GalleryCacheStore.getInstance().staleCachedGroups
            set(value) {
                GalleryCacheStore.getInstance().staleCachedGroups = value
            }

        private val CINEMADNG_FRAME_REGEX = Regex("^(?:DBAG_)?((?:RAWVID|CDNG)_[0-9]{8}_[0-9]{6}(?:_[0-9]+)?)_([0-9]{6})\\.dng$", RegexOption.IGNORE_CASE)
        private val CINEMADNG_AUDIO_REGEX = Regex("^(?:DBAG_)?((?:RAWVID|CDNG)_[0-9]{8}_[0-9]{6}(?:_[0-9]+)?)\\.wav$", RegexOption.IGNORE_CASE)

        private fun isCinemaDngDirectoryName(name: String): Boolean {
            return isDarkbagFileName(name) &&
                    (name.contains("RAWVID", ignoreCase = true) || name.contains("CDNG", ignoreCase = true))
        }

        internal fun isDarkbagFileName(name: String): Boolean {
            return name.startsWith(DarkbagIdentity.FILE_PREFIX, ignoreCase = true) ||
                    name.startsWith("Darkbag_", ignoreCase = true) ||
                    name.startsWith("RAWVID_", ignoreCase = true) ||
                    name.startsWith("CDNG_", ignoreCase = true)
        }
    }

    suspend fun getGroupedImages(forceRefresh: Boolean = false): List<ImageGroup> {
        if (!forceRefresh) {
            cacheStore.cachedGroups?.let { return it }
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

            cacheStore.cachedGroups = result
            result
        }
    }

    fun getGroupedImagesFlow(initialUri: String? = null): Flow<List<ImageGroup>> = flow {
        val prefs = context.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        val groups = mutableMapOf<String, ImageGroupBuilder>()

        // Pre-populate with cached metadata if available (Stale-While-Revalidate)
        val initialCache = cacheStore.cachedGroups ?: cacheStore.staleCachedGroups
        initialCache?.forEach { cached ->
            groups[cached.baseName] = ImageGroupBuilder(cached.baseName).applyFrom(cached)
        }

        suspend fun emitCurrent() {
            val sorted = groups.values
                .map { it.build() }
                .filter { it.hasAny() && !it.isInProgress }
                .sortedByDescending { it.captureTime }
            cacheStore.cachedGroups = sorted
            cacheStore.staleCachedGroups = sorted
            emit(sorted)
        }

        // Stage 0: Stale-While-Revalidate instant T2 delivery (0ms)
        if (groups.isNotEmpty()) {
            emitCurrent()
        }

        val safJpgUri = prefs.getString(SettingsFragment.KEY_JPG_STORAGE_URI, null)
        val safRawUri = prefs.getString(SettingsFragment.KEY_RAW_STORAGE_URI, null)

        // Stage 1: Fast concurrent scan of MediaStore + SAF Folders in parallel
        coroutineScope {
            val jobMediaStore = async(Dispatchers.IO) {
                val msGroups = mutableMapOf<String, ImageGroupBuilder>()
                try {
                    scanMediaStore(msGroups, fast = true)
                } catch (e: Throwable) {
                    android.util.Log.e("ImageRepository", "Error scanning MediaStore", e)
                }
                msGroups
            }
            val jobSafJpg = async(Dispatchers.IO) {
                val jpgGroups = mutableMapOf<String, ImageGroupBuilder>()
                if (safJpgUri != null) {
                    try {
                        scanSafFolder(safJpgUri, jpgGroups, fast = true)
                    } catch (e: Throwable) {
                        android.util.Log.e("ImageRepository", "Error scanning SAF JPG folder: $safJpgUri", e)
                    }
                }
                jpgGroups
            }
            val jobSafRaw = async(Dispatchers.IO) {
                val rawGroups = mutableMapOf<String, ImageGroupBuilder>()
                if (safRawUri != null && safRawUri != safJpgUri) {
                    try {
                        scanSafFolder(safRawUri, rawGroups, fast = true)
                    } catch (e: Throwable) {
                        android.util.Log.e("ImageRepository", "Error scanning SAF RAW folder: $safRawUri", e)
                    }
                }
                rawGroups
            }

            val msResults = try { jobMediaStore.await() } catch (e: Throwable) { emptyMap() }
            mergeGroupMaps(groups, msResults)

            val jpgResults = try {
                kotlinx.coroutines.withTimeoutOrNull(3000L) { jobSafJpg.await() } ?: emptyMap()
            } catch (e: Throwable) { emptyMap() }

            val rawResults = try {
                kotlinx.coroutines.withTimeoutOrNull(3000L) { jobSafRaw.await() } ?: emptyMap()
            } catch (e: Throwable) { emptyMap() }

            mergeGroupMaps(groups, jpgResults)
            mergeGroupMaps(groups, rawResults)

            val validBaseNames = msResults.keys + jpgResults.keys + rawResults.keys
            groups.keys.retainAll { baseName ->
                baseName in validBaseNames || groups[baseName]?.isInProgress == true
            }

            emitCurrent()

            withContext(Dispatchers.IO) {
                preloadInitialMetadata(groups, initialUri)
            }
        }
    }

    private fun mergeGroupMaps(target: MutableMap<String, ImageGroupBuilder>, source: Map<String, ImageGroupBuilder>) {
        for ((baseName, builder) in source) {
            val existing = target.getOrPut(baseName) { ImageGroupBuilder(baseName) }
            existing.mergeFrom(builder)
        }
    }

    private fun preloadInitialMetadata(groups: Map<String, ImageGroupBuilder>, initialUri: String?) {
        if (initialUri == null) return
        val targetBaseName = getBaseName(initialUri)
        val targetBuilder = groups[targetBaseName] ?: groups.values.firstOrNull { builder ->
            builder.baseName == targetBaseName ||
                    builder.jpgUri?.toString() == initialUri ||
                    builder.dngUri?.toString() == initialUri ||
                    builder.dngUri1?.toString() == initialUri ||
                    builder.dngUri2?.toString() == initialUri ||
                    builder.rawVideoUri?.toString() == initialUri ||
                    builder.mp4VideoUri?.toString() == initialUri ||
                    builder.cinemaDngFolderUri?.toString() == initialUri ||
                    builder.cinemaDngFirstFrameUri?.toString() == initialUri ||
                    builder.cinemaDngFrameUris.any { it.toString() == initialUri } ||
                    builder.derivativeJpgUris.any { it.toString() == initialUri } ||
                    builder.derivativeMp4Uris.any { it.toString() == initialUri }
        }
        if (targetBuilder != null && (!targetBuilder.metadataLoaded || (targetBuilder.editConfig == null && (targetBuilder.rawVideoUri != null || targetBuilder.isCinemaDng)))) {
            targetBuilder.metadataLoaded = false
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
                        try {
                            val header = top.maary.darkbag.rawvideo.RawVideoNative.readHeader(handle)
                            if (header != null) {
                                builder.width = header.width
                                builder.height = header.height
                                builder.orientation = header.orientation
                                builder.rawVideoFps = header.fps
                                builder.rawVideoFrameCount = header.frameCount
                                builder.rawVideoDurationMs = if (header.fps > 0) (header.frameCount * 1000L / header.fps).toLong() else 0L
                                val activeLut = header.activeLutName.takeIf { it.isNotBlank() }
                                val activeLog = header.activeLogName.takeIf { it.isNotBlank() }
                                builder.editConfig = EditConfig(
                                    log = activeLog ?: "None",
                                    lut = activeLut ?: "None",
                                    exposure = header.exposure,
                                    contrast = header.contrast,
                                    saturation = header.saturation
                                )
                            }
                        } finally {
                            top.maary.darkbag.rawvideo.RawVideoNative.nativeCloseReader(handle)
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("ImageRepository", "Failed to read raw video metadata for $rawVideoUri", e)
            }
            builder.metadataLoaded = true
            return
        }

        if (builder.isCinemaDng) {
            val firstFrame = builder.cinemaDngFirstFrameUri ?: builder.cinemaDngFrameUris.firstOrNull()
            if (firstFrame != null) {
                readExifForScanning(firstFrame, builder)
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

        if (!builder.isMotionPhoto && builder.derivativeJpgUris.isNotEmpty()) {
            for (derivUri in builder.derivativeJpgUris) {
                if (builder.isMotionPhoto) break
                try {
                    context.contentResolver.openFileDescriptor(derivUri, "r")?.use { pfd ->
                        var motionInfoParsed = false
                        try {
                            val exif = androidx.exifinterface.media.ExifInterface(pfd.fileDescriptor)
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
                            android.util.Log.w("ImageRepository", "Failed to read EXIF from $derivUri", e)
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
                                android.util.Log.w("ImageRepository", "Failed to read Motion Photo from $derivUri", e)
                            }
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.w("ImageRepository", "Failed to open $derivUri", e)
                }
            }
        }
        builder.metadataLoaded = true
    }

    suspend fun loadMetadata(group: ImageGroup): ImageGroup = withContext(Dispatchers.IO) {
        if (group.metadataLoaded) return@withContext group

        val builder = ImageGroupBuilder(group.baseName).applyFrom(group)
        populateMetadata(builder)

        val updated = builder.build()
        val isSandboxGroup = listOfNotNull(
            updated.jpgUri,
            updated.dngUri,
            updated.dngUri1,
            updated.dngUri2,
            updated.rawVideoUri,
            updated.mp4VideoUri
        ).any { it.scheme == "file" }

        val currentCache = cacheStore.cachedGroups
        if (!isSandboxGroup && currentCache != null && currentCache.any { it.baseName == updated.baseName }) {
            cacheStore.updateCachedGroup(updated)
        }
        updated
    }

    fun invalidateCache(clearStale: Boolean = false) {
        cacheStore.invalidate(clearStale)
    }

    private fun scanSafFolder(
        folderUri: String,
        groups: MutableMap<String, ImageGroupBuilder>,
        fast: Boolean = false
    ) {
        SafStorageScanner.scanSafFolder(context, folderUri, groups, fast) { uri, builder ->
            readExifForScanning(uri, builder)
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
        MediaStoreStorageScanner.scanMediaStore(context, groups, fast) { uri, builder ->
            readExifForScanning(uri, builder)
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
                zoomFactor = json.optDouble("zoom_factor", 1.0).toFloat(),
                colorEngineMode = json.optInt("color_engine_mode", 0)
            )
        } catch (e: Exception) {
            null
        }
    }

    fun readDngBaselineExposure(uri: Uri, isHalfFrame: Boolean, index: Int = 0): EditConfig? {
        return DngMetadataSeekParser.readDngBaselineExposure(context, uri, isHalfFrame, index)
    }

    fun saveEditConfig(uri: Uri, editConfig: EditConfig) {
        top.maary.darkbag.utils.ImageSaver.writeMetadataToExif(context, uri, editConfig, null)
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


}
