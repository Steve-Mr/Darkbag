package top.maary.darkbag.utils
import top.maary.darkbag.processor.ColorProcessor

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
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
     * Shared helper to handle Bitmap post-processing (Rotate, Crop, Compress) and Saving (JPG, TIFF, LinearDNG).
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
        tiffPath: String?,
        saveJpg: Boolean,
        saveTiff: Boolean,
        saveRaw: Boolean = true,
        jpgFolderUri: String? = null,
        tiffFolderUri: String? = null,
        rawFolderUri: String? = null,
        targetUri: Uri? = null,
        mirror: Boolean = false,
        isFastPath: Boolean = false,
        halfFrameMetadata: HalfFrameManager.Metadata? = null,
        editConfig: EditConfig? = null,
        digitalGain: Float = 1.0f,
        isAlreadyStitched: Boolean = false,
        captureMetadata: CaptureMetadata? = null,
        onBitmapReady: ((Bitmap) -> Unit)? = null
    ): Uri? {
        val halfFrameManager = HalfFrameManager(context)
        val isHalfFrameActive = !isAlreadyStitched && (halfFrameMetadata != null || halfFrameManager.isEnabled)

        val actualSaveJpg = if (isHalfFrameActive) halfFrameManager.saveJpg else saveJpg
        val actualSaveRaw = if (isHalfFrameActive) halfFrameManager.saveRaw else saveRaw
        val actualSaveTiff = if (isHalfFrameActive) false else saveTiff

        val contentResolver = context.contentResolver
        var finalJpgUri: Uri? = null
        var finalTiffUri: Uri? = null
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
                                    finalJpgUri = saveJpegToMediaStore(context, "$baseName.jpg", targetUri, editConfig = editConfig, zoomFactor = zoomFactor, captureMetadata = captureMetadata) { out ->
                                        finalFile.inputStream().use { it.copyTo(out) }
                                    }
                                }
                            }
                        } else {
                            if (finalPath != null) {
                                val finalFile = File(finalPath)
                                if (jpgFolderUri != null) {
                                    finalJpgUri = saveFileToFolder(context, finalFile, "$baseName.jpg", "image/jpeg", jpgFolderUri, editConfig = editConfig, zoomFactor = zoomFactor, captureMetadata = captureMetadata)
                                } else {
                                    finalJpgUri = saveJpegToMediaStore(context, "$baseName.jpg", targetUri, editConfig = editConfig, zoomFactor = zoomFactor, captureMetadata = captureMetadata) { out ->
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
                            finalJpgUri = saveFileToFolder(context, finalFile, "$baseName.jpg", "image/jpeg", jpgFolderUri, editConfig = editConfig, zoomFactor = zoomFactor, captureMetadata = captureMetadata)
                        } else {
                            finalJpgUri = saveJpegToMediaStore(context, "$baseName.jpg", targetUri, editConfig = editConfig, zoomFactor = zoomFactor, captureMetadata = captureMetadata) { out ->
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
                    if (processedBitmap != null && zoomFactor > 1.05f) {
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
                                    finalJpgUri = saveFileToFolder(context, tempJpg, "$baseName.jpg", "image/jpeg", jpgFolderUri, editConfig = editConfig, zoomFactor = zoomFactor, captureMetadata = captureMetadata)
                                } else {
                                    finalJpgUri = saveJpegToMediaStore(
                                        context,
                                        "$baseName.jpg",
                                        targetUri,
                                        processedBitmap.width,
                                        processedBitmap.height,
                                        editConfig = editConfig,
                                        zoomFactor = zoomFactor,
                                        captureMetadata = captureMetadata
                                    ) { out ->
                                        tempJpg.inputStream().use { it.copyTo(out) }
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

        // 2. Save TIFF
        if (actualSaveTiff && tiffPath != null) {
            val tiffFile = File(tiffPath)
            if (tiffFile.exists()) {
                if (tiffFolderUri != null) {
                    finalTiffUri = saveFileToFolder(context, tiffFile, "$baseName.tiff", "image/tiff", tiffFolderUri)
                } else {
                    val tiffValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, "$baseName.tiff")
                        put(MediaStore.MediaColumns.MIME_TYPE, "image/tiff")
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Darkbag")
                            put(MediaStore.MediaColumns.IS_PENDING, 1)
                        }
                    }
                    val tiffUri = contentResolver.insert(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        tiffValues
                    )
                    if (tiffUri != null) {
                        try {
                            contentResolver.openOutputStream(tiffUri)?.use { out ->
                                FileInputStream(tiffFile).copyTo(out)
                            }

                            updateExifOrientation(context, tiffUri, getExifOrientation(rotationDegrees, mirror))

                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                tiffValues.clear()
                                tiffValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                                contentResolver.update(tiffUri, tiffValues, null, null)
                            }
                            finalTiffUri = tiffUri
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to save TIFF", e)
                            contentResolver.delete(tiffUri, null, null)
                        }
                    }
                }
                tiffFile.delete()
            }
        }

        // 3. Save DNG (Bayer or Linear)
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

                            // Write Metadata and BaselineExposure to DNG
                            writeDngMetadata(context, dngUri, editConfig, digitalGain, captureMetadata)

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
                    tiffPath = tiffPath,
                    dngPath = linearDngPath,
                    jpgPath = bmpPath,
                    targetUri = finalJpgUri.toString(),
                    zoomFactor = zoomFactor,
                    orientation = rotationDegrees,
                    saveTiff = saveTiff,
                    saveJpg = saveJpg
                )
            )
        }

        // Priority for thumbnail: JPEG > DNG > TIFF
        // For half-frame mode, we strictly avoid DNG thumbnails to prevent showing single frames
        if (isHalfFrameActive && finalJpgUri == null) return null

        return finalJpgUri ?: finalRawUri ?: finalTiffUri
    }

    fun writeDngMetadata(context: Context, uri: Uri, editConfig: EditConfig?, digitalGain: Float, captureMetadata: CaptureMetadata?) {
        try {
            context.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                val exif = ExifInterface(pfd.fileDescriptor)

                // 1. Digital Gain
                val gainToUse = editConfig?.digitalGain ?: digitalGain
                if (gainToUse != 1.0f) {
                    val ev = kotlin.math.log2(gainToUse)
                    exif.setAttribute("BaselineExposure", ev.toString())
                    Log.d(TAG, "Wrote BaselineExposure $ev to DNG at $uri")
                }

                // 2. Standard EXIF
                captureMetadata?.let { meta ->
                    meta.iso?.let { exif.setAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS, it.toString()) }
                    meta.exposureTime?.let {
                        val exposureInSec = it.toDouble() / 1_000_000_000.0
                        exif.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, exposureInSec.toString())
                    }
                    meta.fNumber?.let { exif.setAttribute(ExifInterface.TAG_F_NUMBER, it.toString()) }
                    meta.focalLength?.let { exif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH, it.toString()) }
                    meta.dateTimeOriginal?.let {
                        val sdf = java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.US)
                        val dateStr = sdf.format(java.util.Date(it))
                        exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, dateStr)
                    }
                    meta.make?.let { exif.setAttribute(ExifInterface.TAG_MAKE, it) }
                    meta.model?.let { exif.setAttribute(ExifInterface.TAG_MODEL, it) }
                }

                exif.saveAttributes()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write metadata to DNG for $uri", e)
        }
    }

    fun writeMetadataToExif(context: Context, uri: Uri, editConfig: EditConfig?, captureMetadata: CaptureMetadata?) {
        try {
            val json = editConfig?.let { cfg ->
                JSONObject().apply {
                put("log", editConfig.log)
                put("lut", editConfig.lut)
                put("exposure", editConfig.exposure.toDouble())
                put("contrast", editConfig.contrast.toDouble())
                put("saturation", editConfig.saturation.toDouble())
                put("highlights", editConfig.highlights.toDouble())
                put("shadows", editConfig.shadows.toDouble())
                put("whites", editConfig.whites.toDouble())
                put("blacks", editConfig.blacks.toDouble())
                put("digital_gain", editConfig.digitalGain.toDouble())

                editConfig.adjustments?.let { adjs ->
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
                put("show_timestamp", editConfig.showTimestamp)
                put("flare_type", editConfig.flareType)
                put("hf_layout", editConfig.hfLayout)
                put("zoom_factor", cfg.zoomFactor.toDouble())
            }
            }
            context.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                val exif = ExifInterface(pfd.fileDescriptor)
                if (json != null) {
                    exif.setAttribute(ExifInterface.TAG_USER_COMMENT, json.toString())
                }

                captureMetadata?.let { meta ->
                    meta.iso?.let { exif.setAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS, it.toString()) }
                    meta.exposureTime?.let {
                        val exposureInSec = it.toDouble() / 1_000_000_000.0
                        exif.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, exposureInSec.toString())
                    }
                    meta.fNumber?.let { exif.setAttribute(ExifInterface.TAG_F_NUMBER, it.toString()) }
                    meta.focalLength?.let { exif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH, it.toString()) }
                    meta.dateTimeOriginal?.let {
                        val sdf = java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.US)
                        val dateStr = sdf.format(java.util.Date(it))
                        exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, dateStr)
                    }
                    meta.make?.let { exif.setAttribute(ExifInterface.TAG_MAKE, it) }
                    meta.model?.let { exif.setAttribute(ExifInterface.TAG_MODEL, it) }
                }

                exif.saveAttributes()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write EditConfig to EXIF for $uri", e)
        }
    }

    private fun saveDebugStageImagesToMediaStore(context: Context, baseName: String, sourcePath: String) {
        val source = File(sourcePath)
        val parent = source.parentFile ?: return
        val stem = source.nameWithoutExtension

        val debugSuffixes = listOf(
            "_debug_A_linear" to "${baseName}_debug_A_linear.jpg",
            "_debug_B_matrix" to "${baseName}_debug_B_matrix.jpg",
            "_debug_C_log" to "${baseName}_debug_C_log.jpg",
            "_AB_SENSOR_CCM" to "${baseName}_AB_SENSOR_CCM.jpg",
            "_AB_CAPTURE_CCM" to "${baseName}_AB_CAPTURE_CCM.jpg"
        )

        for ((suffix, displayName) in debugSuffixes) {
            val debugFile = File(parent, "$stem${suffix}.jpg")
            if (!debugFile.exists() || debugFile.length() <= 0L) continue

            try {
                saveJpegToMediaStore(context, displayName, null) { out ->
                    FileInputStream(debugFile).use { it.copyTo(out) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to export debug stage image: ${debugFile.absolutePath}", e)
            } finally {
                debugFile.delete()
            }
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

    private fun saveFileToFolder(context: Context, sourceFile: File, displayName: String, mimeType: String, folderUri: String, editConfig: EditConfig? = null, zoomFactor: Float = 1.0f, captureMetadata: CaptureMetadata? = null): Uri? {
        try {
            val treeUri = Uri.parse(folderUri)
            val parentFolder = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, treeUri)
            val newFile = parentFolder?.createFile(mimeType, displayName)
            if (newFile != null) {
                context.contentResolver.openOutputStream(newFile.uri)?.use { out ->
                    FileInputStream(sourceFile).copyTo(out)
                }

                val finalEditConfig = createFinalEditConfig(editConfig, zoomFactor)

                if (mimeType == "image/jpeg") {
                    writeMetadataToExif(context, newFile.uri, finalEditConfig, captureMetadata)
                } else if (mimeType == "image/x-adobe-dng") {
                    writeDngMetadata(context, newFile.uri, finalEditConfig, 1.0f, captureMetadata)
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
        // Use 1.05f threshold to match project conventions for meaningful zoom
        return editConfig?.let {
            if (it.zoomFactor <= 1.05f && zoomFactor > 1.05f) it.copy(zoomFactor = zoomFactor) else it
        } ?: if (zoomFactor > 1.05f) EditConfig(zoomFactor = zoomFactor) else null
    }

    /**
     * Helper to encapsulate MediaStore JPEG saving/updating.
     */
    private fun saveJpegToMediaStore(
        context: Context,
        displayName: String,
        targetUri: Uri?,
        width: Int? = null,
        height: Int? = null,
        editConfig: EditConfig? = null,
        zoomFactor: Float = 1.0f,
        captureMetadata: CaptureMetadata? = null,
        writeData: (OutputStream) -> Unit
    ): Uri? {
        val contentResolver = context.contentResolver
        val jpgValues = ContentValues()

        var uri = targetUri
        val isReplacement = uri != null

        val finalEditConfig = createFinalEditConfig(editConfig, zoomFactor)

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
                // Hardened replacement logic: avoid updating DISPLAY_NAME and RELATIVE_PATH
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
                    writeData(out)
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
                writeMetadataToExif(context, uri, finalEditConfig, captureMetadata)

                if (isReplacement) {
                    Log.i(TAG, "Replaced JPEG at $uri")
                } else {
                    Log.i(TAG, "Saved JPEG to $uri")
                }

                // If this is a final HQ save, notify the UI to update thumbnails and hide progress
                // Note: We don't emit for fast path here because saveProcessedImage handles it.

                return uri
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write JPEG to MediaStore", e)
                if (!isReplacement) contentResolver.delete(uri, null, null)
            }
        }
        return null
    }
}
