package top.maary.darkbag.utils
import top.maary.darkbag.processor.ColorProcessor

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import org.json.JSONObject
import top.maary.darkbag.models.CaptureMetadata
import top.maary.darkbag.models.EditConfig
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import kotlin.math.max
import kotlin.math.min

object ImageSaver {
    private const val TAG = "ImageSaver"

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
        val isHalfFrameActive = !isAlreadyStitched && (halfFrameMetadata != null || halfFrameManager.isEnabled)

        // Motion photos are strictly disabled in half-frame mode
        val effectiveMotionMp4Path = if (isHalfFrameActive) {
            motionPhotoMp4Path?.let { File(it).delete() }
            null
        } else {
            motionPhotoMp4Path
        }

        val actualSaveJpg = if (isHalfFrameActive) halfFrameManager.saveJpg else saveJpg
        val actualSaveRaw = if (isHalfFrameActive) halfFrameManager.saveRaw else saveRaw

        val contentResolver = context.contentResolver
        var finalJpgUri: Uri? = null
        var finalRawUri: Uri? = null

        // 1. Process Input Bitmap or JPEG File from JNI -> Final MediaStore JPG
        if (inputBitmap != null || bmpPath != null) {
            val isNativeJpeg = bmpPath != null && (bmpPath.endsWith(".jpg") || bmpPath.endsWith(".jpeg"))
            val needsBitmapProcessing = rotationDegrees != 0 || zoomFactor > 1.05f || inputBitmap != null || mirror

            if (isNativeJpeg && !needsBitmapProcessing && actualSaveJpg) {
                // FAST PATH: Directly use JNI-generated JPEG
                val f = File(bmpPath!!)
                if (f.exists() && f.length() > 0) {
                    if (isHalfFrameActive) {
                        val finalPath = halfFrameManager.handleCapture(f.absolutePath, baseName, isFastPath, halfFrameMetadata, digitalGain = digitalGain)

                        if (isFastPath) {
                            val session = if (halfFrameMetadata != null) {
                                HalfFrameSessionStore(context).readSession(profile = halfFrameMetadata.profile)
                            } else {
                                HalfFrameSessionStore(context).readSession()
                            }

                            if (session.baseName == baseName) {
                                ColorProcessor.halfFrameFlow.tryEmit(1)
                            } else {
                                ColorProcessor.halfFrameFlow.tryEmit(2)
                                if (finalPath != null) {
                                    val finalFile = File(finalPath)
                                    finalJpgUri = saveJpegToMediaStore(context, "$baseName.jpg", targetUri, editConfig = editConfig, zoomFactor = zoomFactor, captureMetadata = captureMetadata, writeExifMetadata = !isFastPath) { out ->
                                        finalFile.inputStream().use { it.copyTo(out) }
                                    }
                                }
                            }
                        } else {
                            if (finalPath != null) {
                                val finalFile = File(finalPath)
                                if (jpgFolderUri != null) {
                                    finalJpgUri = saveFileToFolder(context, finalFile, "$baseName.jpg", "image/jpeg", jpgFolderUri, editConfig = editConfig, zoomFactor = zoomFactor, captureMetadata = captureMetadata, writeExifMetadata = !isFastPath)
                                } else {
                                    finalJpgUri = saveJpegToMediaStore(context, "$baseName.jpg", targetUri, editConfig = editConfig, zoomFactor = zoomFactor, captureMetadata = captureMetadata, writeExifMetadata = !isFastPath) { out ->
                                        finalFile.inputStream().use { it.copyTo(out) }
                                    }
                                }
                                if (finalPath != f.absolutePath) {
                                    finalFile.delete()
                                }
                            }
                        }
                    } else {
                        val finalFile = f
                        if (jpgFolderUri != null) {
                            finalJpgUri = saveFileToFolder(context, finalFile, "$baseName.jpg", "image/jpeg", jpgFolderUri, editConfig = editConfig, zoomFactor = zoomFactor, captureMetadata = captureMetadata, writeExifMetadata = !isFastPath, motionPhotoMp4Path = effectiveMotionMp4Path, motionPhotoStillPtsUs = motionPhotoStillPtsUs)
                        } else {
                            finalJpgUri = saveJpegToMediaStore(context, "$baseName.jpg", targetUri, editConfig = editConfig, zoomFactor = zoomFactor, captureMetadata = captureMetadata, writeExifMetadata = !isFastPath, motionPhotoMp4Path = effectiveMotionMp4Path, motionPhotoStillPtsUs = motionPhotoStillPtsUs) { out ->
                                finalFile.inputStream().use { it.copyTo(out) }
                            }
                        }
                    }
                } else {
                    Log.e(TAG, "Fast path source file missing or empty: ${f.absolutePath}, size: ${if(f.exists()) f.length() else -1}")
                }
                File(bmpPath!!).delete()
            } else {
                // SLOW PATH: Decode, Rotate, Crop, Encode
                var processedBitmap: Bitmap? = null
                if (inputBitmap != null) {
                    processedBitmap = inputBitmap
                } else if (bmpPath != null) {
                    processedBitmap = BitmapFactory.decodeFile(bmpPath)
                    if (processedBitmap == null) {
                        Log.e(TAG, "BitmapFactory.decodeFile returned null for $bmpPath")
                    }
                }

                try {
                    // Rotate and Mirror if needed
                    if (processedBitmap != null && (rotationDegrees != 0 || mirror)) {
                        val matrix = Matrix()
                        if (rotationDegrees != 0) {
                            matrix.postRotate(rotationDegrees.toFloat())
                        }
                        if (mirror) {
                            // Mirror horizontally after rotation
                            matrix.postScale(-1f, 1f)
                        }

                        val rotated = Bitmap.createBitmap(
                            processedBitmap, 0, 0, processedBitmap.width, processedBitmap.height, matrix, true
                        )
                        if (rotated != processedBitmap) {
                            processedBitmap.recycle()
                            processedBitmap = rotated
                        }
                    }

                    // Crop if needed (Digital Zoom)
                    if (processedBitmap != null && zoomFactor > 1.05f && !isAlreadyCropped) {
                        val newWidth = (processedBitmap.width / zoomFactor).toInt()
                        val newHeight = (processedBitmap.height / zoomFactor).toInt()
                        val x = (processedBitmap.width - newWidth) / 2
                        val y = (processedBitmap.height - newHeight) / 2
                        val safeX = max(0, x)
                        val safeY = max(0, y)
                        val safeWidth = min(newWidth, processedBitmap.width - safeX)
                        val safeHeight = min(newHeight, processedBitmap.height - safeY)

                        val croppedBitmap = Bitmap.createBitmap(
                            processedBitmap, safeX, safeY, safeWidth, safeHeight
                        )
                        if (croppedBitmap != processedBitmap) {
                            processedBitmap.recycle()
                            processedBitmap = croppedBitmap
                        }
                    }

                    // Invoke callback for thumbnail generation or other usage before compression/recycling
                    if (processedBitmap != null) {
                        onBitmapReady?.invoke(processedBitmap)
                    }

                    // Save JPG
                    if (actualSaveJpg) {
                        if (processedBitmap != null) {
                            if (isHalfFrameActive) {
                                // First, get a local JPG file for internal processing/stitching
                                val tempJpg = File(context.cacheDir, "temp_proc_$baseName.jpg")
                                FileOutputStream(tempJpg).use { out ->
                                    processedBitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                                }

                                val finalPath = halfFrameManager.handleCapture(tempJpg.absolutePath, baseName, isFastPath, halfFrameMetadata, digitalGain = digitalGain)

                                if (isFastPath) {
                                    val session = if (halfFrameMetadata != null) {
                                        HalfFrameSessionStore(context).readSession(profile = halfFrameMetadata.profile)
                                    } else {
                                        HalfFrameSessionStore(context).readSession()
                                    }

                                    if (session.baseName == baseName) {
                                        ColorProcessor.halfFrameFlow.tryEmit(1)
                                    } else {
                                        ColorProcessor.halfFrameFlow.tryEmit(2)
                                        if (finalPath != null) {
                                            val finalFile = File(finalPath)
                                            finalJpgUri = saveJpegToMediaStore(
                                                context,
                                                "$baseName.jpg",
                                                targetUri,
                                                processedBitmap.width,
                                                processedBitmap.height,
                                                editConfig = editConfig,
                                                captureMetadata = captureMetadata
                                            ) { out ->
                                                finalFile.inputStream().use { it.copyTo(out) }
                                            }
                                            if (tiffPath != null) {
                                                // Since finalPath is a stitched JPEG, we need to decode it to bitmap to save as TIFF
                                                BitmapFactory.decodeFile(finalPath)?.let {
                                                    top.maary.darkbag.processor.ColorProcessor.saveBitmapToTiff(it, tiffPath, captureMetadata ?: top.maary.darkbag.models.CaptureMetadata())
                                                    it.recycle()
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    if (finalPath != null) {
                                        val finalFile = File(finalPath)
                                        if (jpgFolderUri != null) {
                                        finalJpgUri = saveFileToFolder(context, finalFile, "$baseName.jpg", "image/jpeg", jpgFolderUri, editConfig = editConfig)
                                        } else {
                                            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                            BitmapFactory.decodeFile(finalPath, options)
                                            val finalW = if (options.outWidth > 0) options.outWidth else processedBitmap.width
                                            val finalH = if (options.outHeight > 0) options.outHeight else processedBitmap.height

                                            finalJpgUri = saveJpegToMediaStore(
                                                context,
                                                "$baseName.jpg",
                                                targetUri,
                                                finalW,
                                                finalH,
                                                editConfig = editConfig,
                                                zoomFactor = zoomFactor,
                                                captureMetadata = captureMetadata
                                            ) { out ->
                                                finalFile.inputStream().use { it.copyTo(out) }
                                            }
                                        }
                                        if (finalPath != tempJpg.absolutePath) {
                                             finalFile.delete()
                                        }
                                    }
                                }
                                tempJpg.delete()
                            } else {
                                // Normal Mode
                                val tempJpg = File(context.cacheDir, "temp_proc_$baseName.jpg")
                                FileOutputStream(tempJpg).use { out ->
                                    processedBitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                                }
                                if (jpgFolderUri != null) {
                                    finalJpgUri = saveFileToFolder(context, tempJpg, "$baseName.jpg", "image/jpeg", jpgFolderUri, editConfig = editConfig, zoomFactor = zoomFactor, captureMetadata = captureMetadata, motionPhotoMp4Path = effectiveMotionMp4Path, motionPhotoStillPtsUs = motionPhotoStillPtsUs)
                                } else {
                                    finalJpgUri = saveJpegToMediaStore(
                                        context,
                                        "$baseName.jpg",
                                        targetUri,
                                        processedBitmap.width,
                                        processedBitmap.height,
                                        editConfig = editConfig,
                                        zoomFactor = zoomFactor,
                                        captureMetadata = captureMetadata,
                                        motionPhotoMp4Path = effectiveMotionMp4Path,
                                        motionPhotoStillPtsUs = motionPhotoStillPtsUs
                                    ) { out ->
                                        tempJpg.inputStream().use { it.copyTo(out) }
                                    }
                                    if (tiffPath != null) {
                                        top.maary.darkbag.processor.ColorProcessor.saveBitmapToTiff(processedBitmap, tiffPath, captureMetadata ?: top.maary.darkbag.models.CaptureMetadata())
                                    }
                                }
                                tempJpg.delete()
                            }
                        } else {
                            Log.e(TAG, "Cannot save JPEG: processedBitmap is null (Slow Path)")
                        }
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "Error processing bitmap", t)
                } finally {
                    // We don't recycle if it was passed in as input and not replaced
                    if (processedBitmap != null && processedBitmap != inputBitmap) {
                        processedBitmap.recycle()
                    }
                    // Cleanup BMP if it was used
                    bmpPath?.let { File(it).delete() }
                }
            }
        }

        // 2. Save DNG (Bayer or Linear)
        if (actualSaveRaw && linearDngPath != null) {
            val dngFile = File(linearDngPath)
            if (dngFile.exists()) {
                val baseSuffix = if (linearDngPath.contains("_linear")) "_linear" else ""
                val dngDisplayName = "$baseName$baseSuffix.dng"
                if (rawFolderUri != null) {
                    finalRawUri = saveFileToFolder(context, dngFile, dngDisplayName, "image/x-adobe-dng", rawFolderUri)
                } else {
                    val dngValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, dngDisplayName)
                        put(MediaStore.MediaColumns.MIME_TYPE, "image/x-adobe-dng")
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Darkbag")
                            put(MediaStore.MediaColumns.IS_PENDING, 1)
                        }
                    }
                    val dngUri = contentResolver.insert(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        dngValues
                    )
                    if (dngUri != null) {
                        try {
                            contentResolver.openOutputStream(dngUri)?.use { out ->
                                FileInputStream(dngFile).copyTo(out)
                            }

                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                dngValues.clear()
                                dngValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                                contentResolver.update(dngUri, dngValues, null, null)
                            }

                            finalRawUri = dngUri
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to save Linear DNG", e)
                            contentResolver.delete(dngUri, null, null)
                        }
                    }
                }
                dngFile.delete()
            }
        }

        // If this is not a fast path and we have a result, emit a save event for UI updates
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

        // Priority for thumbnail: JPEG > DNG
        // For half-frame mode, we strictly avoid DNG thumbnails to prevent showing single frames
        if (isHalfFrameActive && finalJpgUri == null) return null

        return finalJpgUri ?: finalRawUri
    }

    private fun writeStandardExif(exif: ExifInterface, captureMetadata: CaptureMetadata?) {
        captureMetadata?.let { meta ->
            meta.iso?.let { exif.setAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS, it.toString()) }
            meta.exposureTime?.let {
                val exposureInSec = it.toDouble() / 1_000_000_000.0
                exif.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, exposureInSec.toString())
            }
            meta.fNumber?.let { exif.setAttribute(ExifInterface.TAG_F_NUMBER, it.toString()) }
            meta.focalLength?.let { exif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH, it.toString()) }
            meta.focalLengthIn35mmFilm?.let { exif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, it.toString()) }

            val sdf = java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.US)
            val subSecSdf = java.text.SimpleDateFormat("SSS", java.util.Locale.US)

            meta.dateTimeOriginal?.let {
                val dateStr = sdf.format(java.util.Date(it))
                val subSecStr = subSecSdf.format(java.util.Date(it))
                exif.setAttribute(ExifInterface.TAG_DATETIME, dateStr)
                exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, dateStr)
                exif.setAttribute(ExifInterface.TAG_SUBSEC_TIME, subSecStr)
                exif.setAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, subSecStr)
            }

            meta.dateTimeDigitized?.let {
                val dateStr = sdf.format(java.util.Date(it))
                val subSecStr = subSecSdf.format(java.util.Date(it))
                exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, dateStr)
                exif.setAttribute(ExifInterface.TAG_SUBSEC_TIME_DIGITIZED, subSecStr)
            } ?: meta.dateTimeOriginal?.let {
                val dateStr = sdf.format(java.util.Date(it))
                val subSecStr = subSecSdf.format(java.util.Date(it))
                exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, dateStr)
                exif.setAttribute(ExifInterface.TAG_SUBSEC_TIME_DIGITIZED, subSecStr)
            }

            meta.offsetTime?.let { exif.setAttribute(ExifInterface.TAG_OFFSET_TIME, it) }
            meta.offsetTimeOriginal?.let { exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, it) }
            meta.offsetTimeDigitized?.let { exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_DIGITIZED, it) }

            meta.make?.let { exif.setAttribute(ExifInterface.TAG_MAKE, it) }
            meta.model?.let { exif.setAttribute(ExifInterface.TAG_MODEL, it) }
            meta.lensModel?.let { exif.setAttribute(ExifInterface.TAG_LENS_MODEL, it) }

            meta.location?.let { loc ->
                try {
                    exif.setGpsInfo(loc)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to write GPS info to EXIF", e)
                }
            }
        }
    }

    private fun applyDarkbagIdentity(
        exif: ExifInterface,
        isHdrPlus: Boolean,
        captureMetadata: CaptureMetadata?,
        imageDescriptionOverride: String? = null
    ) {
        val manufacturer = captureMetadata?.make?.takeIf { it.isNotBlank() } ?: DarkbagIdentity.normalizedManufacturer()
        val model = captureMetadata?.model?.takeIf { it.isNotBlank() } ?: DarkbagIdentity.normalizedModel()
        exif.setAttribute(ExifInterface.TAG_MAKE, manufacturer)
        exif.setAttribute(ExifInterface.TAG_MODEL, model)
        exif.setAttribute(ExifInterface.TAG_SOFTWARE, DarkbagIdentity.softwareString(isHdrPlus))
        exif.setAttribute(
            ExifInterface.TAG_IMAGE_DESCRIPTION,
            imageDescriptionOverride ?: DarkbagIdentity.imageDescription(isHdrPlus)
        )
    }

    private fun buildEditConfigJson(editConfig: EditConfig?): JSONObject? {
        return editConfig?.let { cfg ->
            JSONObject().apply {
                put("log", cfg.log)
                put("lut", cfg.lut)
                put("exposure", cfg.exposure.toDouble())
                put("contrast", cfg.contrast.toDouble())
                put("saturation", cfg.saturation.toDouble())
                put("highlights", cfg.highlights.toDouble())
                put("shadows", cfg.shadows.toDouble())
                put("whites", cfg.whites.toDouble())
                put("blacks", cfg.blacks.toDouble())
                put("digital_gain", cfg.digitalGain.toDouble())

                cfg.adjustments?.let { adjs ->
                    val array = org.json.JSONArray()
                    adjs.forEach { adj ->
                        array.put(JSONObject().apply {
                            put("exposure", adj.exposure.toDouble())
                            put("contrast", adj.contrast.toDouble())
                            put("saturation", adj.saturation.toDouble())
                            put("highlights", adj.highlights.toDouble())
                            put("shadows", adj.shadows.toDouble())
                            put("whites", adj.whites.toDouble())
                            put("blacks", adj.blacks.toDouble())
                            put("digital_gain", adj.digitalGain.toDouble())
                        })
                    }
                    put("adjustments", array)
                }
                put("show_timestamp", cfg.showTimestamp)
                put("flare_type", cfg.flareType)
                put("hf_layout", cfg.hfLayout)
                put("is_swapped", cfg.isSwapped)
                put("zoom_factor", cfg.zoomFactor.toDouble())
            }
        }
    }

    fun writeMetadataToExifFile(file: File, editConfig: EditConfig?, captureMetadata: CaptureMetadata?) {
        try {
            val json = buildEditConfigJson(editConfig)
            val exif = ExifInterface(file.absolutePath)
            if (json != null) {
                exif.setAttribute(ExifInterface.TAG_USER_COMMENT, json.toString())
            }

            writeStandardExif(exif, captureMetadata)
            val isHdrPlus = (editConfig?.log ?: "").contains("HDR+", ignoreCase = true) ||
                file.name.contains("_HDRPLUS", ignoreCase = true)
            applyDarkbagIdentity(exif, isHdrPlus = isHdrPlus, captureMetadata = captureMetadata)

            exif.saveAttributes()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write EditConfig to EXIF file ${file.absolutePath}", e)
        }
    }

    fun writeMetadataToExif(context: Context, uri: Uri, editConfig: EditConfig?, captureMetadata: CaptureMetadata?) {
        try {
            val json = buildEditConfigJson(editConfig)
            context.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                val exif = ExifInterface(pfd.fileDescriptor)
                if (json != null) {
                    exif.setAttribute(ExifInterface.TAG_USER_COMMENT, json.toString())
                }

                writeStandardExif(exif, captureMetadata)
                val isHdrPlus = (editConfig?.log ?: "").contains("HDR+", ignoreCase = true) ||
                    uri.toString().contains("_HDRPLUS", ignoreCase = true)
                applyDarkbagIdentity(exif, isHdrPlus = isHdrPlus, captureMetadata = captureMetadata)

                exif.saveAttributes()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write EditConfig to EXIF for $uri", e)
        }
    }

    fun getExifOrientation(rotationDegrees: Int, mirror: Boolean): Int {
        return when (rotationDegrees) {
            90 -> if (mirror) ExifInterface.ORIENTATION_TRANSPOSE else ExifInterface.ORIENTATION_ROTATE_90
            180 -> if (mirror) ExifInterface.ORIENTATION_FLIP_VERTICAL else ExifInterface.ORIENTATION_ROTATE_180
            270 -> if (mirror) ExifInterface.ORIENTATION_TRANSVERSE else ExifInterface.ORIENTATION_ROTATE_270
            else -> if (mirror) ExifInterface.ORIENTATION_FLIP_HORIZONTAL else ExifInterface.ORIENTATION_NORMAL
        }
    }

    private fun updateExifOrientation(context: Context, uri: Uri, orientation: Int) {
        try {
            context.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                val exif = ExifInterface(pfd.fileDescriptor)
                exif.setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                exif.saveAttributes()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update EXIF orientation for $uri", e)
        }
    }

    private fun saveFileToFolder(
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
        try {
            val treeUri = Uri.parse(folderUri)
            val parentFolder = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, treeUri)
            val newFile = parentFolder?.createFile(mimeType, displayName)
            if (newFile != null) {
                val isMotionPhoto = motionPhotoMp4Path != null && File(motionPhotoMp4Path).exists() && File(motionPhotoMp4Path).length() > 0
                val finalEditConfig = createFinalEditConfig(editConfig, zoomFactor)

                val mp4File = if (isMotionPhoto) File(motionPhotoMp4Path!!) else null
                try {
                    context.contentResolver.openOutputStream(newFile.uri)?.use { out ->
                        if (isMotionPhoto && mp4File != null) {
                            if (writeExifMetadata) {
                                writeMetadataToExifFile(sourceFile, finalEditConfig, captureMetadata)
                            }
                            val jpegBytes = sourceFile.readBytes()
                            top.maary.darkbag.motionphoto.MotionPhotoXmpWriter.writeMotionPhoto(
                                jpegBytes = jpegBytes,
                                mp4File = mp4File,
                                presentationTimestampUs = motionPhotoStillPtsUs,
                                outputStream = out
                            )
                        } else {
                            FileInputStream(sourceFile).copyTo(out)
                        }
                    }
                } finally {
                    mp4File?.delete()
                }

                if (!isMotionPhoto && writeExifMetadata && mimeType == "image/jpeg") {
                    writeMetadataToExif(context, newFile.uri, finalEditConfig, captureMetadata)
                }

                Log.i(TAG, "Saved $displayName to custom folder: ${newFile.uri}")
                return newFile.uri
            } else {
                Log.e(TAG, "Failed to create file $displayName in custom folder")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving $displayName to custom folder", e)
        }
        return null
    }

    private fun createFinalEditConfig(editConfig: EditConfig?, zoomFactor: Float): EditConfig? {
        return editConfig?.let {
            if (it.zoomFactor <= 1.05f && zoomFactor > 1.05f) it.copy(zoomFactor = zoomFactor) else it
        } ?: if (zoomFactor > 1.05f) EditConfig(zoomFactor = zoomFactor) else null
    }

    private fun saveJpegToMediaStore(
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

        val finalEditConfig = createFinalEditConfig(editConfig, zoomFactor)
        val isMotionPhoto = motionPhotoMp4Path != null && File(motionPhotoMp4Path).exists() && File(motionPhotoMp4Path).length() > 0

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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    jpgValues.put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                width?.let { jpgValues.put(MediaStore.MediaColumns.WIDTH, it) }
                height?.let { jpgValues.put(MediaStore.MediaColumns.HEIGHT, it) }

                if (jpgValues.size() > 0) {
                    contentResolver.update(uri, jpgValues, null, null)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to insert/update MediaStore entry", e)
            return null
        }

        if (uri != null) {
            try {
                contentResolver.openOutputStream(uri, "wt")?.use { out ->
                    if (isMotionPhoto) {
                        val mp4File = File(motionPhotoMp4Path!!)
                        val tempJpeg = File(context.cacheDir, "temp_motion_exif_${System.currentTimeMillis()}.jpg")
                        try {
                            FileOutputStream(tempJpeg).use { writeData(it) }
                            if (writeExifMetadata) {
                                writeMetadataToExifFile(tempJpeg, finalEditConfig, captureMetadata)
                            }
                            val jpegBytes = tempJpeg.readBytes()
                            top.maary.darkbag.motionphoto.MotionPhotoXmpWriter.writeMotionPhoto(
                                jpegBytes = jpegBytes,
                                mp4File = mp4File,
                                presentationTimestampUs = motionPhotoStillPtsUs,
                                outputStream = out
                            )
                        } finally {
                            tempJpeg.delete()
                            mp4File.delete()
                        }
                    } else {
                        writeData(out)
                    }
                    out.flush()
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val finalValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.IS_PENDING, 0)
                    }
                    try {
                        contentResolver.update(uri, finalValues, null, null)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to clear IS_PENDING for $uri", e)
                    }
                }
                if (!isMotionPhoto && writeExifMetadata) {
                    writeMetadataToExif(context, uri, finalEditConfig, captureMetadata)
                }

                if (isReplacement) {
                    Log.i(TAG, "Replaced JPEG at $uri")
                } else {
                    Log.i(TAG, "Saved JPEG to $uri")
                }

                return uri
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write JPEG to MediaStore", e)
                if (!isReplacement) contentResolver.delete(uri, null, null)
            }
        }
        return null
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
        var videoUri: Uri? = null
        var thumbnailBitmap: Bitmap? = null

        // 1. Extract first frame thumbnail (for immediate in-memory UI preview)
        val retriever = android.media.MediaMetadataRetriever()
        try {
            retriever.setDataSource(mp4File.absolutePath)
            thumbnailBitmap = retriever.getFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract MP4 thumbnail", e)
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }

        // 2. Save MP4 file to mediaFolderUri (if configured) or Pictures/Darkbag via MediaStore
        if (mediaFolderUri != null) {
            videoUri = saveFileToFolder(context, mp4File, mp4FileName, "video/mp4", mediaFolderUri)
        } else {
            val contentResolver = context.contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, mp4FileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Darkbag")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }
            val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            val insertedUri = contentResolver.insert(collection, contentValues)
            if (insertedUri != null) {
                try {
                    contentResolver.openOutputStream(insertedUri, "wt")?.use { out ->
                        mp4File.inputStream().use { it.copyTo(out) }
                        out.flush()
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val finalValues = ContentValues().apply {
                            put(MediaStore.MediaColumns.IS_PENDING, 0)
                        }
                        contentResolver.update(insertedUri, finalValues, null, null)
                    }
                    videoUri = insertedUri
                    Log.i(TAG, "Saved MP4 video to MediaStore: $videoUri")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to write MP4 video to MediaStore", e)
                    contentResolver.delete(insertedUri, null, null)
                }
            }
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
        var thumbnailBitmap: Bitmap? = null
        var videoUri: Uri? = null

        // 1. Generate thumbnail from first frame using JNI
        val readerHandle = top.maary.darkbag.rawvideo.RawVideoNative.nativeOpenReader(rawVideoFile.absolutePath)
        if (readerHandle != 0L) {
            try {
                val header = top.maary.darkbag.rawvideo.RawVideoNative.readHeader(readerHandle)
                if (header != null && header.width > 0 && header.height > 0) {
                    val thumbWidth = 640
                    val swapDims = (header.orientation == 90 || header.orientation == 270)
                    val thumbW = if (swapDims) (thumbWidth * header.height) / header.width else thumbWidth
                    val thumbH = if (swapDims) thumbWidth else (thumbWidth * header.height) / header.width
                    val bmp = Bitmap.createBitmap(thumbW, thumbH, Bitmap.Config.ARGB_8888)
                    val bufferSize = header.width * header.height * 2
                    val directBuf = java.nio.ByteBuffer.allocateDirect(bufferSize)
                    val meta = LongArray(3)
                    val readBytes = top.maary.darkbag.rawvideo.RawVideoNative.nativeReadFrame(readerHandle, 0, meta, directBuf)
                    if (readBytes > 0) {
                        val targetLogIndex = if (header.activeLogName.isNotBlank() && header.activeLogName != "None") {
                            top.maary.darkbag.fragments.SettingsFragment.LOG_CURVES.indexOf(header.activeLogName).takeIf { it >= 0 } ?: -1
                        } else -1
                        val lutManager = top.maary.darkbag.utils.LutManager(context)
                        val lutPath = if (header.activeLutName.isNotBlank() && header.activeLutName != "None") {
                            val f = java.io.File(lutManager.lutDir, header.activeLutName)
                            if (f.exists()) f.absolutePath else {
                                val f2 = java.io.File(java.io.File(context.filesDir, "luts"), header.activeLutName)
                                if (f2.exists()) f2.absolutePath else null
                            }
                        } else null

                        val debayered = top.maary.darkbag.rawvideo.RawVideoNative.nativeDebayerFrameToBitmap(
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
                        if (debayered) {
                            thumbnailBitmap = bmp
                        }
                    }
                }
            } finally {
                top.maary.darkbag.rawvideo.RawVideoNative.nativeCloseReader(readerHandle)
            }
        }

        // 2. Save .rawvid file
        val rawFileName = "$effectiveBaseName.rawvid"
        if (rawFolderUri != null) {
            videoUri = saveFileToFolder(context, rawVideoFile, rawFileName, "application/octet-stream", rawFolderUri)
        } else {
            // Save to MediaStore
            val contentResolver = context.contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, rawFileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Darkbag")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }

            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
            } else {
                MediaStore.Files.getContentUri("external")
            }

            val insertedUri = contentResolver.insert(collection, contentValues)
            if (insertedUri != null) {
                try {
                    contentResolver.openOutputStream(insertedUri, "wt")?.use { out ->
                        rawVideoFile.inputStream().use { it.copyTo(out) }
                        out.flush()
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val finalValues = ContentValues().apply {
                            put(MediaStore.MediaColumns.IS_PENDING, 0)
                        }
                        contentResolver.update(insertedUri, finalValues, null, null)
                    }
                    videoUri = insertedUri
                    Log.i(TAG, "Saved raw video to MediaStore: $videoUri")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to write raw video to MediaStore", e)
                    contentResolver.delete(insertedUri, null, null)
                }
            }
        }

        // Cleanup temp file
        rawVideoFile.delete()

        Pair(videoUri, thumbnailBitmap)
    }
}
