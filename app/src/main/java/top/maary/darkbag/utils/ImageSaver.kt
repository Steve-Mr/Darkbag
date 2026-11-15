package top.maary.darkbag.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.maary.darkbag.models.CaptureMetadata
import top.maary.darkbag.models.EditConfig
import top.maary.darkbag.processor.ColorProcessor
import top.maary.darkbag.rawvideo.RawVideoNative
import top.maary.darkbag.repository.PreferenceKeys
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min

object ImageSaver {
    private const val TAG = "ImageSaver"

    fun createMediaStorePendingPfd(
        context: Context,
        displayName: String,
        mimeType: String
    ): Pair<ParcelFileDescriptor, Uri>? =
        MediaStoreOutputWriter.createMediaStorePendingPfd(context, displayName, mimeType)

    fun finalizeMediaStorePendingPfd(
        context: Context,
        pfdPair: Pair<ParcelFileDescriptor, Uri>,
        success: Boolean,
        editConfig: EditConfig? = null,
        captureMetadata: CaptureMetadata? = null
    ) = MediaStoreOutputWriter.finalizeMediaStorePendingPfd(
        context = context,
        pfdPair = pfdPair,
        success = success,
        editConfig = editConfig,
        captureMetadata = captureMetadata
    )

    fun saveDngBytesToMediaStoreOrSaf(
        context: Context,
        dngBytes: ByteArray,
        displayName: String,
        rawFolderUri: String?
    ): Uri? = MediaStoreOutputWriter.saveDngBytesToDestination(context, dngBytes, displayName, rawFolderUri)

    fun writeMetadataToExifFile(file: File, editConfig: EditConfig?, captureMetadata: CaptureMetadata?) =
        ExifMetadataApplier.writeMetadataToExifFile(file, editConfig, captureMetadata)

    fun writeMetadataToExif(context: Context, uri: Uri, editConfig: EditConfig?, captureMetadata: CaptureMetadata?) =
        ExifMetadataApplier.writeMetadataToExif(context, uri, editConfig, captureMetadata)

    fun getExifOrientation(rotationDegrees: Int, mirror: Boolean): Int =
        ExifMetadataApplier.getExifOrientation(rotationDegrees, mirror)

    /**
     * Shared helper to handle Bitmap post-processing (Rotate, Crop, Compress) and Saving (JPG, LinearDNG).
     * Deletes input temp files after saving.
     *
     * @param rotationDegrees The degrees of rotation to apply to the [inputBitmap] or the bitmap at [bmpPath].
     *                        If the source at [bmpPath] was already rotated (e.g., by the JNI layer),
     *                        this should be 0 to avoid double-rotation and incorrect EXIF orientation.
     * @param mirror Whether to mirror the image horizontally. Should be false if already mirrored by JNI.
     */
    suspend fun saveProcessedImage(
        context: Context,
        inputBitmap: Bitmap?,
        bmpPath: String?,
        rotationDegrees: Int,
        zoomFactor: Float,
        baseName: String,
        linearDngPath: String?,
        saveJpg: Boolean,
        saveRaw: Boolean = true,
        jpgFolderUri: String? = null,
        rawFolderUri: String? = null,
        targetUri: Uri? = null,
        mirror: Boolean = false,
        isFastPath: Boolean = false,
        halfFrameMetadata: HalfFrameManager.Metadata? = null,
        editConfig: EditConfig? = null,
        digitalGain: Float = 1.0f,
        isAlreadyStitched: Boolean = false,
        captureMetadata: CaptureMetadata? = null,
        tiffPath: String? = null,
        isAlreadyCropped: Boolean = false,
        motionPhotoMp4Path: String? = null,
        motionPhotoStillPtsUs: Long = 0L,
        onBitmapReady: ((Bitmap) -> Unit)? = null
    ): Uri? {
        val halfFrameManager = HalfFrameManager(context)
        val isHalfFrameActive = !isAlreadyStitched &&
            (halfFrameMetadata != null && halfFrameMetadata.profile != HalfFrameSessionStore.PROFILE_NORMAL)

        // Motion photos are strictly disabled in half-frame mode
        val effectiveMotionMp4Path = if (isHalfFrameActive) {
            motionPhotoMp4Path?.let { File(it).delete() }
            null
        } else {
            motionPhotoMp4Path
        }

        val actualSaveJpg = if (isHalfFrameActive) halfFrameManager.saveJpg else saveJpg
        val actualSaveRaw = if (isHalfFrameActive) halfFrameManager.saveRaw else saveRaw

        var finalJpgUri: Uri? = null
        var finalRawUri: Uri? = null

        // 1. Process Input Bitmap or JPEG File from JNI -> Final MediaStore JPG
        if (inputBitmap != null || bmpPath != null) {
            val isNativeJpeg = bmpPath != null && (bmpPath.endsWith(".jpg") || bmpPath.endsWith(".jpeg"))
            val effectiveZoomNeedsCrop = zoomFactor > 1.05f && !isAlreadyCropped
            val needsBitmapProcessing = rotationDegrees != 0 || effectiveZoomNeedsCrop || inputBitmap != null || mirror

            if (isNativeJpeg && !needsBitmapProcessing && actualSaveJpg) {
                // FAST PATH: Directly use JNI-generated JPEG
                val f = File(bmpPath!!)
                if (f.exists() && f.length() > 0) {
                    if (isHalfFrameActive) {
                        val finalPath = halfFrameManager.handleCapture(
                            f.absolutePath, baseName, isFastPath, halfFrameMetadata, digitalGain = digitalGain
                        )
                        finalJpgUri = saveHalfFrameResult(
                            context = context,
                            sourceTempPath = f.absolutePath,
                            finalPath = finalPath,
                            baseName = baseName,
                            isFastPath = isFastPath,
                            halfFrameMetadata = halfFrameMetadata,
                            jpgFolderUri = jpgFolderUri,
                            targetUri = targetUri,
                            editConfig = editConfig,
                            zoomFactor = zoomFactor,
                            captureMetadata = captureMetadata,
                            tiffPath = tiffPath,
                            fallbackWidth = null,
                            fallbackHeight = null
                        )
                    } else {
                        finalJpgUri = MediaStoreOutputWriter.saveJpegToDestination(
                            context = context,
                            sourceFile = f,
                            displayName = "$baseName.jpg",
                            jpgFolderUri = jpgFolderUri,
                            targetUri = targetUri,
                            editConfig = editConfig,
                            zoomFactor = zoomFactor,
                            captureMetadata = captureMetadata,
                            writeExifMetadata = !isFastPath,
                            motionPhotoMp4Path = effectiveMotionMp4Path,
                            motionPhotoStillPtsUs = motionPhotoStillPtsUs
                        )
                    }
                } else {
                    Log.e(TAG, "Fast path source file missing or empty: ${f.absolutePath}, size: ${if (f.exists()) f.length() else -1}")
                }
                File(bmpPath!!).delete()
            } else {
                // SLOW PATH: Decode, Rotate, Crop, Encode
                var processedBitmap: Bitmap? = inputBitmap ?: bmpPath?.let {
                    BitmapFactory.decodeFile(it).also { bmp ->
                        if (bmp == null) Log.e(TAG, "BitmapFactory.decodeFile returned null for $it")
                    }
                }

                try {
                    processedBitmap = applyOrientationAndCrop(processedBitmap, rotationDegrees, mirror, zoomFactor, isAlreadyCropped)
                    if (processedBitmap != null) {
                        onBitmapReady?.invoke(processedBitmap)
                    }

                    if (actualSaveJpg) {
                        if (processedBitmap != null) {
                            val tempJpg = File(context.cacheDir, "temp_proc_$baseName.jpg")
                            FileOutputStream(tempJpg).use { out ->
                                processedBitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                            }
                            if (isHalfFrameActive) {
                                val finalPath = halfFrameManager.handleCapture(
                                    tempJpg.absolutePath, baseName, isFastPath, halfFrameMetadata, digitalGain = digitalGain
                                )
                                finalJpgUri = saveHalfFrameResult(
                                    context = context,
                                    sourceTempPath = tempJpg.absolutePath,
                                    finalPath = finalPath,
                                    baseName = baseName,
                                    isFastPath = isFastPath,
                                    halfFrameMetadata = halfFrameMetadata,
                                    jpgFolderUri = jpgFolderUri,
                                    targetUri = targetUri,
                                    editConfig = editConfig,
                                    zoomFactor = zoomFactor,
                                    captureMetadata = captureMetadata,
                                    tiffPath = tiffPath,
                                    fallbackWidth = processedBitmap.width,
                                    fallbackHeight = processedBitmap.height
                                )
                            } else {
                                finalJpgUri = MediaStoreOutputWriter.saveJpegToDestination(
                                    context = context,
                                    sourceFile = tempJpg,
                                    displayName = "$baseName.jpg",
                                    jpgFolderUri = jpgFolderUri,
                                    targetUri = targetUri,
                                    width = processedBitmap.width,
                                    height = processedBitmap.height,
                                    editConfig = editConfig,
                                    zoomFactor = zoomFactor,
                                    captureMetadata = captureMetadata,
                                    writeExifMetadata = true,
                                    motionPhotoMp4Path = effectiveMotionMp4Path,
                                    motionPhotoStillPtsUs = motionPhotoStillPtsUs
                                )
                                if (jpgFolderUri == null && tiffPath != null) {
                                    ColorProcessor.saveBitmapToTiff(
                                        processedBitmap, tiffPath, captureMetadata ?: CaptureMetadata()
                                    )
                                }
                            }
                            tempJpg.delete()
                        } else {
                            Log.e(TAG, "Cannot save JPEG: processedBitmap is null (Slow Path)")
                        }
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "Error processing bitmap", t)
                } finally {
                    if (processedBitmap != null && processedBitmap != inputBitmap) {
                        processedBitmap.recycle()
                    }
                    bmpPath?.let { File(it).delete() }
                }
            }
        }

        // 2. Save DNG (Bayer or Linear)
        if (actualSaveRaw && linearDngPath != null) {
            finalRawUri = MediaStoreOutputWriter.persistDngFile(
                context = context,
                linearDngPath = linearDngPath,
                baseName = baseName,
                rawFolderUri = rawFolderUri,
                isHalfFrameActive = isHalfFrameActive,
                halfFrameMetadata = halfFrameMetadata
            )
        }

        if (!isFastPath && finalJpgUri != null) {
            ColorProcessor.backgroundSaveFlow.tryEmit(
                ColorProcessor.BackgroundSaveEvent(
                    baseName = baseName,
                    dngPath = linearDngPath,
                    jpgPath = bmpPath,
                    targetUri = finalJpgUri.toString(),
                    zoomFactor = zoomFactor,
                    orientation = rotationDegrees,
                    saveJpg = saveJpg
                )
            )
        }

        // For half-frame mode, strictly avoid DNG thumbnails to prevent showing single frames
        if (isHalfFrameActive && finalJpgUri == null) return null

        return finalJpgUri ?: finalRawUri
    }

    private fun applyOrientationAndCrop(
        bitmap: Bitmap?,
        rotationDegrees: Int,
        mirror: Boolean,
        zoomFactor: Float,
        isAlreadyCropped: Boolean
    ): Bitmap? {
        var current = bitmap ?: return null
        if (rotationDegrees != 0 || mirror) {
            val matrix = Matrix().apply {
                if (rotationDegrees != 0) postRotate(rotationDegrees.toFloat())
                if (mirror) postScale(-1f, 1f)
            }
            val rotated = Bitmap.createBitmap(current, 0, 0, current.width, current.height, matrix, true)
            if (rotated != current) {
                current.recycle()
                current = rotated
            }
        }
        if (zoomFactor > 1.05f && !isAlreadyCropped) {
            val newWidth = (current.width / zoomFactor).toInt()
            val newHeight = (current.height / zoomFactor).toInt()
            val safeX = max(0, (current.width - newWidth) / 2)
            val safeY = max(0, (current.height - newHeight) / 2)
            val safeWidth = min(newWidth, current.width - safeX)
            val safeHeight = min(newHeight, current.height - safeY)
            val cropped = Bitmap.createBitmap(current, safeX, safeY, safeWidth, safeHeight)
            if (cropped != current) {
                current.recycle()
                current = cropped
            }
        }
        return current
    }

    private fun saveHalfFrameResult(
        context: Context,
        sourceTempPath: String,
        finalPath: String?,
        baseName: String,
        isFastPath: Boolean,
        halfFrameMetadata: HalfFrameManager.Metadata?,
        jpgFolderUri: String?,
        targetUri: Uri?,
        editConfig: EditConfig?,
        zoomFactor: Float,
        captureMetadata: CaptureMetadata?,
        tiffPath: String?,
        fallbackWidth: Int?,
        fallbackHeight: Int?
    ): Uri? {
        val effectiveJpgBaseName = if (halfFrameMetadata?.frame1BaseName != null) {
            baseName.replace("_HF2", "")
        } else {
            baseName
        }

        if (isFastPath) {
            val session = if (halfFrameMetadata != null) {
                HalfFrameSessionStore(context).readSession(profile = halfFrameMetadata.profile)
            } else {
                HalfFrameSessionStore(context).readSession()
            }
            if (session.baseName == baseName) {
                ColorProcessor.halfFrameFlow.tryEmit(1)
                return null
            }
            ColorProcessor.halfFrameFlow.tryEmit(2)
            val stitchedFile = finalPath?.let { File(it) } ?: return null
            val uri = MediaStoreOutputWriter.saveJpegToMediaStore(
                context = context,
                displayName = "$effectiveJpgBaseName.jpg",
                targetUri = targetUri,
                width = fallbackWidth,
                height = fallbackHeight,
                editConfig = editConfig,
                zoomFactor = if (fallbackWidth == null) zoomFactor else 1.0f,
                captureMetadata = captureMetadata,
                writeExifMetadata = fallbackWidth != null
            ) { out ->
                stitchedFile.inputStream().use { it.copyTo(out) }
            }
            if (fallbackWidth != null && tiffPath != null) {
                BitmapFactory.decodeFile(finalPath)?.let {
                    ColorProcessor.saveBitmapToTiff(it, tiffPath, captureMetadata ?: CaptureMetadata())
                    it.recycle()
                }
            }
            return uri
        }

        val finalFile = finalPath?.let { File(it) } ?: return null
        val (finalW, finalH) = if (jpgFolderUri == null && fallbackWidth != null && fallbackHeight != null) {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(finalPath, options)
            val w = if (options.outWidth > 0) options.outWidth else fallbackWidth
            val h = if (options.outHeight > 0) options.outHeight else fallbackHeight
            w to h
        } else {
            null to null
        }
        val uri = MediaStoreOutputWriter.saveJpegToDestination(
            context = context,
            sourceFile = finalFile,
            displayName = "$effectiveJpgBaseName.jpg",
            jpgFolderUri = jpgFolderUri,
            targetUri = targetUri,
            width = finalW,
            height = finalH,
            editConfig = editConfig,
            zoomFactor = if (jpgFolderUri != null && fallbackWidth != null) 1.0f else zoomFactor,
            captureMetadata = if (jpgFolderUri != null && fallbackWidth != null) null else captureMetadata,
            writeExifMetadata = fallbackWidth != null || !isFastPath
        )
        if (finalPath != sourceTempPath) {
            finalFile.delete()
        }
        return uri
    }

    suspend fun saveMp4Video(
        context: Context,
        mp4File: File,
        baseName: String,
        mediaFolderUri: String? = null
    ): Pair<Uri?, Bitmap?> = withContext(Dispatchers.IO) {
        if (!mp4File.exists() || mp4File.length() == 0L) {
            Log.e(TAG, "MP4 file does not exist or is empty: ${mp4File.absolutePath}")
            return@withContext Pair(null, null)
        }

        val effectiveBaseName = DarkbagIdentity.prefixedBaseName(baseName)
        val mp4FileName = "$effectiveBaseName.mp4"
        var thumbnailBitmap: Bitmap? = null

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(mp4File.absolutePath)
            thumbnailBitmap = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract MP4 thumbnail", e)
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }

        val videoUri = if (mediaFolderUri != null) {
            MediaStoreOutputWriter.saveFileToFolder(context, mp4File, mp4FileName, "video/mp4", mediaFolderUri)
        } else {
            MediaStoreOutputWriter.saveFileToMediaStore(
                context = context,
                sourceFile = mp4File,
                displayName = mp4FileName,
                mimeType = "video/mp4",
                collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            )?.also { Log.i(TAG, "Saved MP4 video to MediaStore: $it") }
        }

        mp4File.delete()
        Pair(videoUri, thumbnailBitmap)
    }

    suspend fun saveRawVideo(
        context: Context,
        rawVideoFile: File,
        baseName: String,
        targetFps: Float = 24.0f,
        rawFolderUri: String? = null,
        jpgFolderUri: String? = null
    ): Pair<Uri?, Bitmap?> = withContext(Dispatchers.IO) {
        if (!rawVideoFile.exists() || rawVideoFile.length() == 0L) {
            Log.e(TAG, "Raw video file does not exist or is empty: ${rawVideoFile.absolutePath}")
            return@withContext Pair(null, null)
        }

        val effectiveBaseName = DarkbagIdentity.prefixedBaseName(baseName)
        val thumbnailBitmap = extractRawVideoThumbnail(context, rawVideoFile)

        val rawFileName = "$effectiveBaseName.rawvid"
        val videoUri = if (rawFolderUri != null) {
            MediaStoreOutputWriter.saveFileToFolder(
                context, rawVideoFile, rawFileName, "application/octet-stream", rawFolderUri
            )
        } else {
            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
            } else {
                MediaStore.Files.getContentUri("external")
            }
            MediaStoreOutputWriter.saveFileToMediaStore(
                context = context,
                sourceFile = rawVideoFile,
                displayName = rawFileName,
                mimeType = "application/octet-stream",
                collection = collection
            )?.also { Log.i(TAG, "Saved raw video to MediaStore: $it") }
        }

        rawVideoFile.delete()
        Pair(videoUri, thumbnailBitmap)
    }

    private fun extractRawVideoThumbnail(context: Context, rawVideoFile: File): Bitmap? {
        val readerHandle = RawVideoNative.nativeOpenReader(rawVideoFile.absolutePath)
        if (readerHandle == 0L) return null
        return try {
            val header = RawVideoNative.readHeader(readerHandle) ?: return null
            if (header.width <= 0 || header.height <= 0) return null
            val thumbWidth = 640
            val swapDims = (header.orientation == 90 || header.orientation == 270)
            val thumbW = if (swapDims) (thumbWidth * header.height) / header.width else thumbWidth
            val thumbH = if (swapDims) thumbWidth else (thumbWidth * header.height) / header.width
            val bmp = Bitmap.createBitmap(thumbW, thumbH, Bitmap.Config.ARGB_8888)
            val directBuf = ByteBuffer.allocateDirect(header.width * header.height * 2)
            val meta = LongArray(3)
            if (RawVideoNative.nativeReadFrame(readerHandle, 0, meta, directBuf) <= 0) return null

            val targetLogIndex = if (header.activeLogName.isNotBlank() && header.activeLogName != "None") {
                PreferenceKeys.LOG_CURVES.indexOf(header.activeLogName).takeIf { it >= 0 } ?: -1
            } else -1
            val lutManager = LutManager(context)
            val lutPath = if (header.activeLutName.isNotBlank() && header.activeLutName != "None") {
                val f = File(lutManager.lutDir, header.activeLutName)
                if (f.exists()) f.absolutePath else {
                    File(File(context.filesDir, "luts"), header.activeLutName).takeIf { it.exists() }?.absolutePath
                }
            } else null

            val debayered = RawVideoNative.nativeDebayerFrameToBitmap(
                bayerBuffer = directBuf,
                width = header.width,
                height = header.height,
                orientation = header.orientation,
                cfaPattern = header.cfaPattern,
                whiteLevel = header.whiteLevel,
                blackLevel = header.blackLevel.firstOrNull() ?: 64f,
                neutralPoint = header.neutralPoint,
                targetLog = targetLogIndex,
                lutPath = lutPath,
                exposure = 0.0f,
                contrast = 0.0f,
                saturation = 0.0f,
                outBitmap = bmp
            )
            if (debayered) bmp else null
        } finally {
            RawVideoNative.nativeCloseReader(readerHandle)
        }
    }
}
