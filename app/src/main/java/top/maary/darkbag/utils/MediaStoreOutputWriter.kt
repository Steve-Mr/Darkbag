package top.maary.darkbag.utils

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import top.maary.darkbag.models.CaptureMetadata
import top.maary.darkbag.models.EditConfig
import top.maary.darkbag.motionphoto.MotionPhotoXmpWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream

internal object MediaStoreOutputWriter {
    private const val TAG = "MediaStoreOutputWriter"

    fun createMediaStorePendingPfd(
        context: Context,
        displayName: String,
        mimeType: String
    ): Pair<ParcelFileDescriptor, Uri>? {
        val contentResolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Darkbag")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            val pfd = contentResolver.openFileDescriptor(uri, "rwt")
            if (pfd != null) Pair(pfd, uri) else {
                contentResolver.delete(uri, null, null)
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open PFD for MediaStore Uri: $uri", e)
            contentResolver.delete(uri, null, null)
            null
        }
    }

    fun finalizeMediaStorePendingPfd(
        context: Context,
        pfdPair: Pair<ParcelFileDescriptor, Uri>,
        success: Boolean,
        editConfig: EditConfig? = null,
        captureMetadata: CaptureMetadata? = null
    ) {
        val (pfd, uri) = pfdPair
        try {
            pfd.close()
            if (success) {
                if (editConfig != null || captureMetadata != null) {
                    ExifMetadataApplier.writeMetadataToExif(context, uri, editConfig, captureMetadata)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.IS_PENDING, 0)
                    }
                    context.contentResolver.update(uri, values, null, null)
                }
            } else {
                context.contentResolver.delete(uri, null, null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to finalize MediaStore PFD for $uri", e)
        }
    }

    fun saveDngToDestination(
        context: Context,
        dngFile: File,
        displayName: String,
        rawFolderUri: String?
    ): Uri? {
        if (!dngFile.exists()) return null
        if (rawFolderUri != null) {
            return saveFileToFolder(context, dngFile, displayName, "image/x-adobe-dng", rawFolderUri)
        }
        return writeStreamToMediaStore(
            context = context,
            collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            displayName = displayName,
            mimeType = "image/x-adobe-dng",
            openMode = "w"
        ) { out ->
            FileInputStream(dngFile).use { it.copyTo(out) }
        }
    }

    fun saveDngBytesToDestination(
        context: Context,
        dngBytes: ByteArray,
        displayName: String,
        rawFolderUri: String?
    ): Uri? {
        if (dngBytes.isEmpty()) return null
        if (rawFolderUri != null) {
            return createSafFileAndWrite(context, rawFolderUri, "image/x-adobe-dng", displayName) { out ->
                out.write(dngBytes)
                out.flush()
            }?.also { Log.i(TAG, "Saved $displayName bytes to SAF: $it") }
        }
        return writeStreamToMediaStore(
            context = context,
            collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            displayName = displayName,
            mimeType = "image/x-adobe-dng",
            openMode = "wt"
        ) { out -> out.write(dngBytes) }
    }

    private fun createSafFileAndWrite(
        context: Context,
        folderUri: String,
        mimeType: String,
        displayName: String,
        writeBlock: (OutputStream) -> Unit
    ): Uri? {
        return try {
            val parentFolder = DocumentFile.fromTreeUri(context, Uri.parse(folderUri))
            val newFile = parentFolder?.createFile(mimeType, displayName)
            if (newFile == null) {
                Log.e(TAG, "Failed to create file $displayName in SAF folder")
                return null
            }
            context.contentResolver.openOutputStream(newFile.uri)?.use(writeBlock)
            newFile.uri
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save $displayName to SAF folder", e)
            null
        }
    }

    fun saveFileToMediaStore(
        context: Context,
        sourceFile: File,
        displayName: String,
        mimeType: String,
        collection: Uri
    ): Uri? = writeStreamToMediaStore(
        context = context,
        collection = collection,
        displayName = displayName,
        mimeType = mimeType,
        openMode = "wt"
    ) { out -> sourceFile.inputStream().use { it.copyTo(out) } }

    private fun writeStreamToMediaStore(
        context: Context,
        collection: Uri,
        displayName: String,
        mimeType: String,
        openMode: String,
        writeBlock: (OutputStream) -> Unit
    ): Uri? {
        val contentResolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Darkbag")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val insertedUri = contentResolver.insert(collection, values) ?: return null
        return try {
            if (openMode == "w") {
                contentResolver.openFileDescriptor(insertedUri, "w")?.use { pfd ->
                    FileOutputStream(pfd.fileDescriptor).use { out ->
                        writeBlock(out)
                        out.flush()
                    }
                }
            } else {
                contentResolver.openOutputStream(insertedUri, openMode)?.use { out ->
                    writeBlock(out)
                    out.flush()
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                contentResolver.update(insertedUri, values, null, null)
            }
            insertedUri
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write $displayName to MediaStore", e)
            contentResolver.delete(insertedUri, null, null)
            null
        }
    }

    private fun writeMotionPhotoToStream(
        context: Context,
        mp4File: File,
        motionPhotoStillPtsUs: Long,
        writeExifMetadata: Boolean,
        editConfig: EditConfig?,
        captureMetadata: CaptureMetadata?,
        out: OutputStream,
        prepareJpegFile: () -> Pair<File, Boolean>
    ) {
        val (jpegFile, shouldDeleteTemp) = prepareJpegFile()
        try {
            if (writeExifMetadata) {
                ExifMetadataApplier.writeMetadataToExifFile(jpegFile, editConfig, captureMetadata)
            }
            MotionPhotoXmpWriter.writeMotionPhoto(
                jpegBytes = jpegFile.readBytes(),
                mp4File = mp4File,
                presentationTimestampUs = motionPhotoStillPtsUs,
                outputStream = out
            )
        } finally {
            if (shouldDeleteTemp) jpegFile.delete()
        }
    }

    fun saveFileToFolder(
        context: Context,
        sourceFile: File,
        displayName: String,
        mimeType: String,
        folderUri: String,
        editConfig: EditConfig? = null,
        zoomFactor: Float = 1.0f,
        captureMetadata: CaptureMetadata? = null,
        writeExifMetadata: Boolean = true,
        motionPhotoMp4Path: String? = null,
        motionPhotoStillPtsUs: Long = 0L
    ): Uri? {
        val isMotionPhoto = motionPhotoMp4Path != null &&
            File(motionPhotoMp4Path).exists() && File(motionPhotoMp4Path).length() > 0
        val finalEditConfig = ExifMetadataApplier.createFinalEditConfig(editConfig, zoomFactor)
        val mp4File = if (isMotionPhoto) File(motionPhotoMp4Path!!) else null
        return try {
            val savedUri = createSafFileAndWrite(context, folderUri, mimeType, displayName) { out ->
                if (isMotionPhoto && mp4File != null) {
                    writeMotionPhotoToStream(
                        context, mp4File, motionPhotoStillPtsUs, writeExifMetadata,
                        finalEditConfig, captureMetadata, out
                    ) { sourceFile to false }
                } else {
                    FileInputStream(sourceFile).copyTo(out)
                }
            } ?: return null
            if (!isMotionPhoto && writeExifMetadata && mimeType == "image/jpeg") {
                ExifMetadataApplier.writeMetadataToExif(context, savedUri, finalEditConfig, captureMetadata)
            }
            Log.i(TAG, "Saved $displayName to custom folder: $savedUri")
            savedUri
        } finally {
            mp4File?.delete()
        }
    }

    fun saveJpegToMediaStore(
        context: Context,
        displayName: String,
        targetUri: Uri?,
        width: Int? = null,
        height: Int? = null,
        editConfig: EditConfig? = null,
        zoomFactor: Float = 1.0f,
        captureMetadata: CaptureMetadata? = null,
        writeExifMetadata: Boolean = true,
        motionPhotoMp4Path: String? = null,
        motionPhotoStillPtsUs: Long = 0L,
        writeData: (OutputStream) -> Unit
    ): Uri? {
        val contentResolver = context.contentResolver
        val jpgValues = ContentValues()
        var uri = targetUri
        val isReplacement = uri != null
        val finalEditConfig = ExifMetadataApplier.createFinalEditConfig(editConfig, zoomFactor)
        val isMotionPhoto = motionPhotoMp4Path != null &&
            File(motionPhotoMp4Path).exists() && File(motionPhotoMp4Path).length() > 0

        try {
            if (uri == null) {
                jpgValues.apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Darkbag")
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                    width?.let { put(MediaStore.MediaColumns.WIDTH, it) }
                    height?.let { put(MediaStore.MediaColumns.HEIGHT, it) }
                }
                uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, jpgValues)
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) jpgValues.put(MediaStore.MediaColumns.IS_PENDING, 1)
                width?.let { jpgValues.put(MediaStore.MediaColumns.WIDTH, it) }
                height?.let { jpgValues.put(MediaStore.MediaColumns.HEIGHT, it) }
                if (jpgValues.size() > 0) contentResolver.update(uri, jpgValues, null, null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to insert/update MediaStore entry", e)
            return null
        }

        if (uri != null) {
            try {
                contentResolver.openFileDescriptor(uri, "w")?.use { pfd ->
                    FileOutputStream(pfd.fileDescriptor).use { out ->
                        if (isMotionPhoto) {
                            val mp4File = File(motionPhotoMp4Path!!)
                            try {
                                writeMotionPhotoToStream(
                                    context, mp4File, motionPhotoStillPtsUs, writeExifMetadata,
                                    finalEditConfig, captureMetadata, out
                                ) {
                                    val tempJpeg = File(context.cacheDir, "temp_motion_exif_${System.currentTimeMillis()}.jpg")
                                    FileOutputStream(tempJpeg).use { writeData(it) }
                                    tempJpeg to true
                                }
                            } finally {
                                mp4File.delete()
                            }
                        } else {
                            writeData(out)
                        }
                        out.flush()
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val finalValues = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                    try {
                        contentResolver.update(uri, finalValues, null, null)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to clear IS_PENDING for $uri", e)
                    }
                }
                if (!isMotionPhoto && writeExifMetadata) {
                    ExifMetadataApplier.writeMetadataToExif(context, uri, finalEditConfig, captureMetadata)
                }
                Log.i(TAG, if (isReplacement) "Replaced JPEG at $uri" else "Saved JPEG to $uri")
                return uri
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write JPEG to MediaStore", e)
                if (!isReplacement) contentResolver.delete(uri, null, null)
            }
        }
        return null
    }

    fun saveJpegToDestination(
        context: Context,
        sourceFile: File,
        displayName: String,
        jpgFolderUri: String?,
        targetUri: Uri?,
        width: Int? = null,
        height: Int? = null,
        editConfig: EditConfig?,
        zoomFactor: Float,
        captureMetadata: CaptureMetadata?,
        writeExifMetadata: Boolean,
        motionPhotoMp4Path: String? = null,
        motionPhotoStillPtsUs: Long = 0L
    ): Uri? {
        return if (jpgFolderUri != null) {
            saveFileToFolder(
                context = context,
                sourceFile = sourceFile,
                displayName = displayName,
                mimeType = "image/jpeg",
                folderUri = jpgFolderUri,
                editConfig = editConfig,
                zoomFactor = zoomFactor,
                captureMetadata = captureMetadata,
                writeExifMetadata = writeExifMetadata,
                motionPhotoMp4Path = motionPhotoMp4Path,
                motionPhotoStillPtsUs = motionPhotoStillPtsUs
            )
        } else {
            saveJpegToMediaStore(
                context = context,
                displayName = displayName,
                targetUri = targetUri,
                width = width,
                height = height,
                editConfig = editConfig,
                zoomFactor = zoomFactor,
                captureMetadata = captureMetadata,
                writeExifMetadata = writeExifMetadata,
                motionPhotoMp4Path = motionPhotoMp4Path,
                motionPhotoStillPtsUs = motionPhotoStillPtsUs
            ) { out -> sourceFile.inputStream().use { it.copyTo(out) } }
        }
    }

    fun persistDngFile(
        context: Context,
        linearDngPath: String,
        baseName: String,
        rawFolderUri: String?,
        isHalfFrameActive: Boolean,
        halfFrameMetadata: HalfFrameManager.Metadata?
    ): Uri? {
        val dngFile = File(linearDngPath)
        if (!dngFile.exists()) return null

        val baseSuffix = if (linearDngPath.contains("_linear")) "_linear" else ""
        val dngDisplayName = "$baseName$baseSuffix.dng"

        if (!isHalfFrameActive) {
            val uri = saveDngToDestination(context, dngFile, dngDisplayName, rawFolderUri)
            dngFile.delete()
            return uri
        }

        val halfFrameSessionStore = HalfFrameSessionStore(context)
        val activeProfile = halfFrameMetadata?.profile ?: halfFrameSessionStore.currentProfile()
        val isFrame1 = halfFrameMetadata?.frame1BaseName == null

        if (isFrame1) {
            val targetStagingDng = halfFrameSessionStore.tempDngFileForProfile(activeProfile)
            try {
                dngFile.copyTo(targetStagingDng, overwrite = true)
                halfFrameSessionStore.setTempDngPath(targetStagingDng.absolutePath, profile = activeProfile)
                Log.d(TAG, "HalfFrame Frame 1 DNG staged internally: ${targetStagingDng.absolutePath}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stage Frame 1 DNG", e)
            } finally {
                dngFile.delete()
            }
            return null
        }

        val frame2Uri = saveDngToDestination(context, dngFile, dngDisplayName, rawFolderUri)
        dngFile.delete()

        val session = halfFrameSessionStore.readSession(profile = activeProfile)
        val stagedPath = session.tempDngPath
        val stagedFile = if (!stagedPath.isNullOrBlank() && File(stagedPath).exists()) {
            File(stagedPath)
        } else {
            halfFrameSessionStore.tempDngFileForProfile(activeProfile).takeIf { it.exists() }
        }

        if (stagedFile != null && stagedFile.exists()) {
            val f1DisplayName = if (baseName.contains("_HF2")) {
                baseName.replace("_HF2", "_HF1") + "$baseSuffix.dng"
            } else {
                "${halfFrameMetadata?.frame1BaseName ?: session.baseName ?: baseName}_HF1$baseSuffix.dng"
            }
            Log.d(TAG, "Publishing staged Frame 1 DNG as $f1DisplayName")
            saveDngToDestination(context, stagedFile, f1DisplayName, rawFolderUri)
            stagedFile.delete()
            halfFrameSessionStore.setTempDngPath(null, profile = activeProfile)
        }
        return frame2Uri
    }
}
