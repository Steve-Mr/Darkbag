package top.maary.darkbag.viewer.motion

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.maary.darkbag.models.ImageGroup
import top.maary.darkbag.motionphoto.MotionPhotoInfo
import top.maary.darkbag.motionphoto.MotionPhotoReader
import top.maary.darkbag.utils.MediaStoreOutputWriter
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlin.math.abs

/**
 * Handles extraction, frame picking, EXIF-preserving JPEG saving/sharing,
 * and embedded MP4 saving/sharing for Motion Photos.
 */
object MotionPhotoExportHelper {

    data class PreparedMotionVideo(
        val videoFile: File,
        val sourceJpgUri: Uri,
        val durationUs: Long,
        val stillPtsUs: Long,
        val width: Int,
        val height: Int,
        val rotation: Int
    )

    private val COPIED_EXIF_TAGS = arrayOf(
        ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_MAKE,
        ExifInterface.TAG_MODEL,
        ExifInterface.TAG_EXPOSURE_TIME,
        ExifInterface.TAG_F_NUMBER,
        ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
        ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
        ExifInterface.TAG_GPS_LATITUDE,
        ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE,
        ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_DATESTAMP,
        ExifInterface.TAG_GPS_TIMESTAMP
    )

    fun formatRelativeTime(currentUs: Long, stillPtsUs: Long): String {
        val diffSec = (currentUs - stillPtsUs) / 1_000_000.0
        return if (abs(diffSec) < 0.005) {
            "0.00s"
        } else {
            val sign = if (diffSec > 0) "+" else ""
            String.format(Locale.US, "%s%.2fs", sign, diffSec)
        }
    }

    fun buildFrameFileName(baseName: String, timeMs: Long): String {
        val cleanBase = baseName.ifBlank { "Darkbag" }
        return "${cleanBase}_frame_${timeMs.coerceAtLeast(0L)}ms.jpg"
    }

    fun buildVideoFileName(baseName: String): String {
        val cleanBase = baseName.ifBlank { "Darkbag" }
        return "${cleanBase}_motion.mp4"
    }

    suspend fun prepareMotionVideo(context: Context, group: ImageGroup): PreparedMotionVideo? =
        withContext(Dispatchers.IO) {
            val candidateUris = listOfNotNull(group.jpgUri) + group.derivativeJpgUris
            val motionUri = candidateUris.firstOrNull { MotionPhotoReader.isMotionPhoto(context, it) }
                ?: group.jpgUri ?: return@withContext null

            val parsedInfo = MotionPhotoReader.parseMotionPhotoInfo(context, motionUri)
            val info = when {
                group.motionPhotoVideoLength > 0 -> MotionPhotoInfo(
                    videoLength = group.motionPhotoVideoLength,
                    presentationTimestampUs = if (group.motionPhotoPtsUs > 0) {
                        group.motionPhotoPtsUs
                    } else {
                        parsedInfo?.presentationTimestampUs ?: 0L
                    }
                )
                else -> parsedInfo
            }

            val videoFile = MotionPhotoReader.extractVideoToCache(
                context = context,
                uri = motionUri,
                baseName = group.baseName,
                info = info
            ) ?: return@withContext null
            if (!videoFile.exists() || videoFile.length() <= 0L) return@withContext null

            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(videoFile.absolutePath)
                val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                val durationUs = (durationMs * 1000L).coerceAtLeast(1_000L)
                val stillPtsUs = (info?.presentationTimestampUs ?: (durationUs / 2L)).coerceIn(0L, durationUs)
                PreparedMotionVideo(
                    videoFile = videoFile,
                    sourceJpgUri = motionUri,
                    durationUs = durationUs,
                    stillPtsUs = stillPtsUs,
                    width = width,
                    height = height,
                    rotation = rotation
                )
            } catch (_: Exception) {
                null
            } finally {
                runCatching { retriever.release() }
            }
        }

    suspend fun extractFilmstripThumbnails(
        videoFile: File,
        durationUs: Long,
        count: Int = 12
    ): List<Bitmap> = withContext(Dispatchers.IO) {
        if (!videoFile.exists() || durationUs <= 0L || count <= 0) return@withContext emptyList()
        val retriever = MediaMetadataRetriever()
        val thumbs = mutableListOf<Bitmap>()
        try {
            retriever.setDataSource(videoFile.absolutePath)
            for (i in 0 until count) {
                val fraction = if (count == 1) 0.5f else i.toFloat() / (count - 1).toFloat()
                val timeUs = (durationUs * fraction).toLong().coerceIn(0L, durationUs)
                val raw = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                if (raw != null) {
                    val targetH = 96
                    val targetW = ((raw.width.toFloat() / raw.height.coerceAtLeast(1)) * targetH).toInt().coerceIn(48, 160)
                    val scaled = Bitmap.createScaledBitmap(raw, targetW, targetH, true)
                    if (scaled !== raw) raw.recycle()
                    thumbs.add(scaled)
                }
            }
        } catch (_: Exception) {
        } finally {
            runCatching { retriever.release() }
        }
        thumbs
    }

    suspend fun extractFrameAt(videoFile: File, timeUs: Long): Bitmap? = withContext(Dispatchers.IO) {
        if (!videoFile.exists()) return@withContext null
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(videoFile.absolutePath)
            retriever.getFrameAtTime(timeUs.coerceAtLeast(0L), MediaMetadataRetriever.OPTION_CLOSEST)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    suspend fun saveFrameToGallery(
        context: Context,
        bitmap: Bitmap,
        sourceJpgUri: Uri,
        baseName: String,
        timeMs: Long
    ): Uri? = withContext(Dispatchers.IO) {
        val tempFile = writeTempJpegWithExif(context, bitmap, sourceJpgUri, "motion_save_${System.currentTimeMillis()}.jpg")
            ?: return@withContext null
        try {
            val displayName = buildFrameFileName(baseName, timeMs)
            MediaStoreOutputWriter.saveFileToMediaStore(
                context = context,
                sourceFile = tempFile,
                displayName = displayName,
                mimeType = "image/jpeg",
                collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            )
        } finally {
            tempFile.delete()
        }
    }

    suspend fun createShareableFrameUri(
        context: Context,
        bitmap: Bitmap,
        sourceJpgUri: Uri,
        baseName: String,
        timeMs: Long
    ): Uri? = withContext(Dispatchers.IO) {
        val shareDir = File(context.cacheDir, "shared_motion").apply { mkdirs() }
        val fileName = buildFrameFileName(baseName, timeMs)
        val outFile = writeTempJpegWithExif(context, bitmap, sourceJpgUri, fileName, shareDir)
            ?: return@withContext null
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", outFile)
    }

    suspend fun saveMp4ToGallery(
        context: Context,
        mp4File: File,
        baseName: String
    ): Uri? = withContext(Dispatchers.IO) {
        if (!mp4File.exists()) return@withContext null
        val displayName = buildVideoFileName(baseName)
        val primary = MediaStoreOutputWriter.saveFileToMediaStore(
            context = context,
            sourceFile = mp4File,
            displayName = displayName,
            mimeType = "video/mp4",
            collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        )
        if (primary != null) return@withContext primary

        // Fallback to Movies/Darkbag on OEM devices rejecting Pictures/ for video/mp4
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/Darkbag")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val inserted = runCatching {
            resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        }.getOrNull() ?: return@withContext null

        return@withContext try {
            resolver.openOutputStream(inserted, "wt")?.use { out ->
                mp4File.inputStream().use { it.copyTo(out) }
                out.flush()
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(inserted, values, null, null)
            }
            inserted
        } catch (_: Exception) {
            resolver.delete(inserted, null, null)
            null
        }
    }

    suspend fun createShareableMp4Uri(
        context: Context,
        mp4File: File,
        baseName: String
    ): Uri? = withContext(Dispatchers.IO) {
        if (!mp4File.exists()) return@withContext null
        val shareDir = File(context.cacheDir, "shared_motion").apply { mkdirs() }
        val target = File(shareDir, buildVideoFileName(baseName))
        mp4File.copyTo(target, overwrite = true)
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target)
    }

    private fun writeTempJpegWithExif(
        context: Context,
        bitmap: Bitmap,
        sourceJpgUri: Uri,
        fileName: String,
        parentDir: File = File(context.cacheDir, "shared_motion").apply { mkdirs() }
    ): File? {
        val outFile = File(parentDir, fileName)
        return try {
            FileOutputStream(outFile).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, fos)
                fos.flush()
            }
            copyCoreExif(context, sourceJpgUri, outFile)
            outFile
        } catch (_: Exception) {
            outFile.delete()
            null
        }
    }

    private fun copyCoreExif(context: Context, sourceUri: Uri, targetFile: File) {
        try {
            context.contentResolver.openFileDescriptor(sourceUri, "r")?.use { pfd ->
                val srcExif = ExifInterface(pfd.fileDescriptor)
                val dstExif = ExifInterface(targetFile.absolutePath)
                var modified = false
                for (tag in COPIED_EXIF_TAGS) {
                    val value = srcExif.getAttribute(tag)
                    if (!value.isNullOrBlank()) {
                        dstExif.setAttribute(tag, value)
                        modified = true
                    }
                }
                if (modified) dstExif.saveAttributes()
            }
        } catch (_: Exception) {
        }
    }
}
