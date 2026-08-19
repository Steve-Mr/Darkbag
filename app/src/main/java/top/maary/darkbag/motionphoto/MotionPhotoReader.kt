package top.maary.darkbag.motionphoto

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern

/**
 * Metadata info extracted from a Motion Photo file.
 */
data class MotionPhotoInfo(
    val videoLength: Long,
    val presentationTimestampUs: Long = 0L
)

/**
 * Utility to detect, parse, and extract the embedded MP4 micro-video from Motion Photo JPEG files.
 */
object MotionPhotoReader {
    private const val TAG = "MotionPhotoReader"
    private const val XMP_HEADER = "http://ns.adobe.com/xap/1.0/"

    private val MOTION_PHOTO_PATTERN = Pattern.compile("GCamera:MotionPhoto\\s*=\\s*\"?1\"?", Pattern.CASE_INSENSITIVE)
    private val MICRO_VIDEO_PATTERN = Pattern.compile("GCamera:MicroVideo\\s*=\\s*\"?1\"?", Pattern.CASE_INSENSITIVE)
    private val MICRO_VIDEO_OFFSET_PATTERN = Pattern.compile("GCamera:MicroVideoOffset\\s*=\\s*\"?(\\d+)\"?", Pattern.CASE_INSENSITIVE)
    private val ITEM_MOTION_PHOTO_LENGTH_PATTERN = Pattern.compile("Item:Semantic\\s*=\\s*\"?MotionPhoto\"?[^>]*Item:Length\\s*=\\s*\"?(\\d+)\"?", Pattern.CASE_INSENSITIVE)
    private val ITEM_LENGTH_MOTION_PHOTO_PATTERN = Pattern.compile("Item:Length\\s*=\\s*\"?(\\d+)\"?[^>]*Item:Semantic\\s*=\\s*\"?MotionPhoto\"?", Pattern.CASE_INSENSITIVE)
    private val PTS_PATTERN = Pattern.compile("GCamera:MotionPhotoPresentationTimestampUs\\s*=\\s*\"?(\\d+)\"?", Pattern.CASE_INSENSITIVE)
    private val MICRO_VIDEO_PTS_PATTERN = Pattern.compile("GCamera:MicroVideoPresentationTimestampUs\\s*=\\s*\"?(\\d+)\"?", Pattern.CASE_INSENSITIVE)

    /**
     * Checks whether the given URI points to a Motion Photo.
     */
    fun isMotionPhoto(context: Context, uri: Uri?): Boolean {
        if (uri == null || uri == Uri.EMPTY) return false
        return parseMotionPhotoInfo(context, uri) != null
    }

    /**
     * Parses the Motion Photo metadata (video length and still PTS) from the given URI.
     */
    fun parseMotionPhotoInfo(context: Context, uri: Uri): MotionPhotoInfo? {
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                parseMotionPhotoInfo(pfd)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse motion photo info from $uri", e)
            null
        }
    }

    /**
     * Parses Motion Photo metadata from a [ParcelFileDescriptor].
     */
    fun parseMotionPhotoInfo(pfd: ParcelFileDescriptor): MotionPhotoInfo? {
        val totalSize = pfd.statSize
        if (totalSize <= 0) return null

        return try {
            try {
                android.system.Os.lseek(pfd.fileDescriptor, 0L, android.system.OsConstants.SEEK_SET)
            } catch (_: Exception) {}
            val fis = FileInputStream(pfd.fileDescriptor)
            val nonClosingStream = object : java.io.FilterInputStream(fis) {
                override fun close() {
                    // Do not close underlying file descriptor
                }
            }
            val info = parseMotionPhotoInfo(nonClosingStream, totalSize)
            try {
                android.system.Os.lseek(pfd.fileDescriptor, 0L, android.system.OsConstants.SEEK_SET)
            } catch (_: Exception) {}
            info
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read motion photo info", e)
            null
        }
    }

    /**
     * Parses Motion Photo metadata from an [InputStream].
     */
    fun parseMotionPhotoInfo(inputStream: InputStream, totalFileSize: Long = 0L): MotionPhotoInfo? {
        return try {
            val xmpString = extractXmpString(inputStream) ?: return null
            parseXmpPayload(xmpString, totalFileSize)
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing XMP from input stream", e)
            null
        }
    }

    /**
     * Parses the XMP XML string to extract [MotionPhotoInfo].
     */
    fun parseXmpPayload(xmp: String, totalFileSize: Long = 0L): MotionPhotoInfo? {
        val isMotionPhoto = MOTION_PHOTO_PATTERN.matcher(xmp).find() ||
                MICRO_VIDEO_PATTERN.matcher(xmp).find() ||
                xmp.contains("Semantic=\"MotionPhoto\"", ignoreCase = true) ||
                xmp.contains("Item:Semantic=\"MotionPhoto\"", ignoreCase = true)

        if (!isMotionPhoto) return null

        var videoLength = 0L
        val offsetMatcher = MICRO_VIDEO_OFFSET_PATTERN.matcher(xmp)
        if (offsetMatcher.find()) {
            videoLength = offsetMatcher.group(1)?.toLongOrNull() ?: 0L
        }

        if (videoLength <= 0L) {
            val itemMatcher1 = ITEM_MOTION_PHOTO_LENGTH_PATTERN.matcher(xmp)
            if (itemMatcher1.find()) {
                videoLength = itemMatcher1.group(1)?.toLongOrNull() ?: 0L
            }
        }

        if (videoLength <= 0L) {
            val itemMatcher2 = ITEM_LENGTH_MOTION_PHOTO_PATTERN.matcher(xmp)
            if (itemMatcher2.find()) {
                videoLength = itemMatcher2.group(1)?.toLongOrNull() ?: 0L
            }
        }

        var ptsUs = 0L
        val ptsMatcher = PTS_PATTERN.matcher(xmp)
        if (ptsMatcher.find()) {
            ptsUs = ptsMatcher.group(1)?.toLongOrNull() ?: 0L
        } else {
            val microPtsMatcher = MICRO_VIDEO_PTS_PATTERN.matcher(xmp)
            if (microPtsMatcher.find()) {
                ptsUs = microPtsMatcher.group(1)?.toLongOrNull() ?: 0L
            }
        }

        if (videoLength <= 0L) return null
        if (totalFileSize > 0 && videoLength >= totalFileSize) return null

        return MotionPhotoInfo(videoLength = videoLength, presentationTimestampUs = ptsUs)
    }

    /**
     * Scans JPEG APP segments to extract the XMP metadata string.
     */
    fun extractXmpString(inputStream: InputStream): String? {
        val header = ByteArray(2)
        if (inputStream.read(header) != 2 || (header[0] != 0xFF.toByte() || header[1] != 0xD8.toByte())) {
            return null // Not a JPEG
        }

        val buf = ByteArray(4)
        while (inputStream.read(buf, 0, 4) == 4) {
            if (buf[0] != 0xFF.toByte()) {
                break
            }
            val marker = buf[1].toInt() and 0xFF
            val length = ((buf[2].toInt() and 0xFF) shl 8) or (buf[3].toInt() and 0xFF)
            if (length < 2) break

            val payloadLength = length - 2
            if (marker == 0xE1) { // APP1
                val payload = ByteArray(payloadLength)
                var read = 0
                while (read < payloadLength) {
                    val r = inputStream.read(payload, read, payloadLength - read)
                    if (r <= 0) break
                    read += r
                }
                if (read == payloadLength) {
                    val str = String(payload, StandardCharsets.UTF_8)
                    if (str.startsWith(XMP_HEADER) || str.contains("<x:xmpmeta", ignoreCase = true)) {
                        return str
                    }
                }
            } else if (marker == 0xDA || marker == 0xD9) { // SOS or EOI, start of image data
                break
            } else {
                // Skip non-APP1 segment
                var skipped = 0L
                while (skipped < payloadLength) {
                    val s = inputStream.skip(payloadLength - skipped)
                    if (s <= 0) break
                    skipped += s
                }
            }
        }
        return null
    }

    /**
     * Extracts the embedded MP4 video to the application cache directory for playback.
     * Reuses previously extracted cache file if available and valid.
     *
     * @param context Application context
     * @param uri URI of the Motion Photo JPEG
     * @param baseName Identifier for cache naming
     * @param info Pre-parsed [MotionPhotoInfo], or null to parse on the fly
     * @return Extracted MP4 [File], or null on error
     */
    fun extractVideoToCache(
        context: Context,
        uri: Uri,
        baseName: String,
        info: MotionPhotoInfo? = null
    ): File? {
        val cacheDir = File(context.cacheDir, "motion_cache")
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }

        val targetFile = File(cacheDir, "${baseName}.mp4")

        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                val totalSize = pfd.statSize
                if (totalSize <= 0) return null

                val effectiveInfo = info ?: parseMotionPhotoInfo(pfd) ?: return null
                val videoLength = effectiveInfo.videoLength

                if (videoLength <= 0 || videoLength >= totalSize) {
                    return null
                }

                // If cache exists and matches expected size, reuse
                if (targetFile.exists() && targetFile.length() == videoLength) {
                    return targetFile
                }

                val offset = totalSize - videoLength
                val tempFile = File(cacheDir, "${baseName}_${System.currentTimeMillis()}.tmp")

                try {
                    android.system.Os.lseek(pfd.fileDescriptor, offset, android.system.OsConstants.SEEK_SET)
                } catch (_: Exception) {}

                val fis = FileInputStream(pfd.fileDescriptor)
                FileOutputStream(tempFile).use { fos ->
                    val buffer = ByteArray(64 * 1024)
                    var remaining = videoLength
                    while (remaining > 0) {
                        val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                        val read = fis.read(buffer, 0, toRead)
                        if (read <= 0) break
                        fos.write(buffer, 0, read)
                        remaining -= read
                    }
                    fos.flush()
                }

                if (tempFile.exists() && tempFile.length() == videoLength) {
                    if (targetFile.exists()) targetFile.delete()
                    if (tempFile.renameTo(targetFile)) {
                        targetFile
                    } else {
                        tempFile
                    }
                } else {
                    tempFile.delete()
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract motion photo video from $uri", e)
            null
        }
    }
}
