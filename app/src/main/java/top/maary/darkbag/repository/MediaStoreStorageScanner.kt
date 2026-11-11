package top.maary.darkbag.repository

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import top.maary.darkbag.utils.DarkbagIdentity
import top.maary.darkbag.utils.ImageUtils

/**
 * Encapsulates MediaStore image/video/audio queries, deduplication, and parsing into ImageGroupBuilders.
 */
object MediaStoreStorageScanner {

    private val CINEMADNG_FRAME_REGEX = Regex("^(?:DBAG_)?((?:RAWVID|CDNG)_[0-9]{8}_[0-9]{6}(?:_[0-9]+)?)_([0-9]{6})\\.dng$", RegexOption.IGNORE_CASE)
    private val CINEMADNG_AUDIO_REGEX = Regex("^(?:DBAG_)?((?:RAWVID|CDNG)_[0-9]{8}_[0-9]{6}(?:_[0-9]+)?)\\.wav$", RegexOption.IGNORE_CASE)

    fun scanMediaStore(
        context: Context,
        groups: MutableMap<String, ImageGroupBuilder>,
        fast: Boolean = false,
        readExif: (Uri, ImageGroupBuilder) -> Unit
    ) {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.SIZE
        )

        val collections = mutableListOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            collections.add(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL))
        } else {
            collections.add(MediaStore.Files.getContentUri("external"))
        }

        val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? OR ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? OR ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? OR ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? OR ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
        } else {
            "${MediaStore.MediaColumns.DATA} LIKE ? OR ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? OR ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? OR ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? OR ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
        }
        val selectionArgs = arrayOf("%Darkbag%", "${DarkbagIdentity.FILE_PREFIX}%", "Darkbag_%", "RAWVID_%", "CDNG_%")

        val processedMediaKeys = mutableSetOf<String>()

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
                    val sizeColumn = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)

                    while (cursor.moveToNext()) {
                        try {
                            val id = cursor.getLong(idColumn)
                            val name = cursor.getString(nameColumn) ?: continue
                            if (!ImageRepository.isDarkbagFileName(name)) continue
                            val date = cursor.getLong(dateColumn) * 1000 // Convert to ms
                            val modified = cursor.getLong(modifiedColumn) * 1000 // Convert to ms
                            val mime = cursor.getString(mimeColumn)
                            val size = if (sizeColumn != -1) cursor.getLong(sizeColumn) else 0L

                            val dedupeKey = "$name-$modified"
                            if (!processedMediaKeys.add(dedupeKey)) continue

                            val uri = ContentUris.withAppendedId(collection, id)

                            val cdngFrameMatch = CINEMADNG_FRAME_REGEX.find(name)
                            val cdngAudioMatch = CINEMADNG_AUDIO_REGEX.find(name)

                            val matchedBuilder = when {
                                cdngFrameMatch != null -> {
                                    val clipBaseName = cdngFrameMatch.groupValues[1]
                                    val frameIndex = cdngFrameMatch.groupValues[2].toIntOrNull() ?: 0
                                    val builder = groups.getOrPut(clipBaseName) { ImageGroupBuilder(clipBaseName, context) }
                                    builder.addCinemaDngFrame(uri, frameIndex, date, modified)
                                    if (widthColumn != -1 && heightColumn != -1 && builder.width == 0 && builder.height == 0) {
                                        val w = cursor.getInt(widthColumn)
                                        val h = cursor.getInt(heightColumn)
                                        if (w > 0 && h > 0) {
                                            builder.width = w
                                            builder.height = h
                                        }
                                    }
                                    if (!fast && frameIndex == 0 && (builder.width == 0 || builder.height == 0)) {
                                        readExif(uri, builder)
                                    }
                                    builder
                                }
                                cdngAudioMatch != null -> {
                                    val clipBaseName = cdngAudioMatch.groupValues[1]
                                    val builder = groups.getOrPut(clipBaseName) { ImageGroupBuilder(clipBaseName, context) }
                                    builder.setCinemaDngAudio(uri, date, modified)
                                    builder
                                }
                                name.contains("_MULTI_") && mime == "image/jpeg" -> {
                                    val baseName = ImageUtils.getBaseName(name)
                                    val builder = groups.getOrPut(baseName) { ImageGroupBuilder(baseName, context) }
                                    if (widthColumn != -1 && heightColumn != -1 && builder.width == 0 && builder.height == 0) {
                                        val w = cursor.getInt(widthColumn)
                                        val h = cursor.getInt(heightColumn)
                                        if (w > 0 && h > 0) {
                                            builder.width = w
                                            builder.height = h
                                        }
                                    }
                                    builder.addMultiJpg(uri, name, date, modified)
                                    if (!fast) {
                                        readExif(uri, builder)
                                    }
                                    builder
                                }
                                name.contains("_MULTI_") && (mime == "image/x-adobe-dng" || name.endsWith(".dng", ignoreCase = true)) -> {
                                    val baseName = ImageUtils.getBaseName(name)
                                    val builder = groups.getOrPut(baseName) { ImageGroupBuilder(baseName, context) }
                                    builder.addMultiDng(uri, name, date, modified)
                                    builder
                                }
                                mime == "image/jpeg" || name.endsWith(".jpg", ignoreCase = true) || name.endsWith(".jpeg", ignoreCase = true) -> {
                                    val baseName = ImageUtils.getBaseName(name)
                                    val builder = groups.getOrPut(baseName) { ImageGroupBuilder(baseName, context) }
                                    if (widthColumn != -1 && heightColumn != -1 && builder.width == 0 && builder.height == 0) {
                                        val w = cursor.getInt(widthColumn)
                                        val h = cursor.getInt(heightColumn)
                                        if (w > 0 && h > 0) {
                                            builder.width = w
                                            builder.height = h
                                        }
                                    }
                                    builder.setJpg(uri, date, modified)
                                    if (!fast) {
                                        readExif(uri, builder)
                                    }
                                    builder
                                }
                                name.contains("_HF2") && (mime == "image/x-adobe-dng" || name.endsWith(".dng", ignoreCase = true)) -> {
                                    val baseName = ImageUtils.getBaseName(name)
                                    val builder = groups.getOrPut(baseName) { ImageGroupBuilder(baseName, context) }
                                    builder.setDng2(uri, date, modified)
                                    builder
                                }
                                name.contains("_HF1") && (mime == "image/x-adobe-dng" || name.endsWith(".dng", ignoreCase = true)) -> {
                                    val baseName = ImageUtils.getBaseName(name)
                                    val builder = groups.getOrPut(baseName) { ImageGroupBuilder(baseName, context) }
                                    builder.setDng1(uri, date, modified)
                                    builder
                                }
                                mime == "image/x-adobe-dng" || name.endsWith(".dng", ignoreCase = true) -> {
                                    val baseName = ImageUtils.getBaseName(name)
                                    val builder = groups.getOrPut(baseName) { ImageGroupBuilder(baseName, context) }
                                    builder.setDng(uri, date, modified)
                                    builder
                                }
                                name.endsWith(".rawvid", ignoreCase = true) -> {
                                    val baseName = ImageUtils.getBaseName(name)
                                    val builder = groups.getOrPut(baseName) { ImageGroupBuilder(baseName, context) }
                                    builder.setRawVideo(uri, date, modified)
                                    builder
                                }
                                mime == "video/mp4" || name.endsWith(".mp4", ignoreCase = true) -> {
                                    val baseName = ImageUtils.getBaseName(name)
                                    val builder = groups.getOrPut(baseName) { ImageGroupBuilder(baseName, context) }
                                    builder.setMp4Video(uri, date, modified)
                                    builder
                                }
                                else -> null
                            }
                            matchedBuilder?.addSize(size)
                        } catch (e: Throwable) {
                            android.util.Log.w("MediaStoreStorageScanner", "Error processing MediaStore entry", e)
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("MediaStoreStorageScanner", "Error querying collection $collection", e)
            }
        }
    }
}
