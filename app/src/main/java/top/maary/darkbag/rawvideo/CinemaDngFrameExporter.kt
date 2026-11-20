package top.maary.darkbag.rawvideo

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.maary.darkbag.models.EditConfig
import top.maary.darkbag.processor.ColorProcessor
import top.maary.darkbag.repository.ImageRepository
import top.maary.darkbag.repository.PreferenceKeys
import top.maary.darkbag.repository.SettingsRepository
import top.maary.darkbag.utils.DarkbagIdentity
import top.maary.darkbag.utils.ImageSaver
import top.maary.darkbag.utils.LutManager
import java.io.File
import java.nio.ByteBuffer
import java.util.Locale

internal object CinemaDngFrameExporter {
    private const val TAG = "CinemaDngFrameExporter"

    fun formatFrameBaseName(baseName: String, frameIndex: Int): String {
        var clean = baseName.removePrefix(DarkbagIdentity.FILE_PREFIX)
            .replace(Regex("^(RAWVID|CDNG)_"), "PHOTO_")
        if (!clean.startsWith("PHOTO_")) {
            clean = "PHOTO_$clean"
        }
        val frameSuffix = "_f$frameIndex"
        if (!clean.endsWith(frameSuffix) && !clean.contains(Regex("_f\\d+$"))) {
            clean += frameSuffix
        }
        return DarkbagIdentity.prefixedBaseName(clean)
    }

    /**
     * Exports a single frame from a CinemaDNG / RAW video sequence:
     * - RAW_DNG_ONLY: exports DBAG_PHOTO_YYYYMMDD_HHMMSS_f{index}.dng
     * - GRADED_JPG_ONLY: exports DBAG_PHOTO_YYYYMMDD_HHMMSS_f{index}_graded.jpg
     * - RAW_AND_GRADED_PAIR: exports both files with matching baseName for ImageGroup pairing
     */
    suspend fun exportSingleFrameFromCinemaDng(
        context: Context,
        frameDngUri: Uri,
        baseName: String,
        frameIndex: Int,
        editConfig: EditConfig?,
        exportType: RawVideoExporter.FrameExportType
    ): Pair<Uri?, Uri?> = withContext(Dispatchers.IO) {
        try {
            val currentPrefs = SettingsRepository.getInstance(context).current()
            val jpgFolderUri = currentPrefs.jpgStorageUri
            val rawFolderUri = currentPrefs.rawStorageUri

            // 1. Read source DNG bytes
            val dngBytes = context.contentResolver.openInputStream(frameDngUri)?.use { it.readBytes() }
                ?: return@withContext Pair(null, null)

            if (dngBytes.isEmpty()) {
                Log.e(TAG, "Empty DNG frame data for uri: $frameDngUri")
                return@withContext Pair(null, null)
            }

            val fullBaseName = formatFrameBaseName(baseName, frameIndex)
            var finalRawUri: Uri? = null
            var finalJpgUri: Uri? = null

            val captureMetadata = ImageRepository(context).getCaptureMetadata(frameDngUri)

            // 2. Export RAW DNG if requested (delegated to ImageSaver -> MediaStoreOutputWriter)
            if (exportType == RawVideoExporter.FrameExportType.RAW_DNG_ONLY ||
                exportType == RawVideoExporter.FrameExportType.RAW_AND_GRADED_PAIR
            ) {
                finalRawUri = ImageSaver.saveDngBytesToMediaStoreOrSaf(
                    context = context,
                    dngBytes = dngBytes,
                    displayName = "$fullBaseName.dng",
                    rawFolderUri = rawFolderUri
                )
                if (finalRawUri != null) {
                    Log.i(TAG, "Exported single frame RAW DNG: $finalRawUri")
                }
            }

            // 3. Export Graded JPG if requested
            if (exportType == RawVideoExporter.FrameExportType.GRADED_JPG_ONLY ||
                exportType == RawVideoExporter.FrameExportType.RAW_AND_GRADED_PAIR
            ) {
                val targetLogIndex = if (editConfig?.log != null && editConfig.log != "None") {
                    PreferenceKeys.LOG_CURVES.indexOf(editConfig.log).takeIf { it >= 0 } ?: 0
                } else 0

                val lutManager = LutManager(context)
                val lutPath = if (editConfig?.lut != null && editConfig.lut != "None" && editConfig.lut!!.isNotBlank()) {
                    val f = File(lutManager.lutDir, editConfig.lut!!)
                    if (f.exists()) f.absolutePath else {
                        File(File(context.filesDir, "luts"), editConfig.lut!!).takeIf { it.exists() }?.absolutePath
                    }
                } else null

                val orientation = try {
                    context.contentResolver.openInputStream(frameDngUri)?.use { input ->
                        ExifInterface(input).getAttributeInt(
                            ExifInterface.TAG_ORIENTATION,
                            ExifInterface.ORIENTATION_NORMAL
                        )
                    } ?: ExifInterface.ORIENTATION_NORMAL
                } catch (e: Exception) {
                    ExifInterface.ORIENTATION_NORMAL
                }

                val rotDegrees = when (orientation) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }

                val tempJpg = File(context.cacheDir, "temp_graded_${fullBaseName}_${System.currentTimeMillis()}.jpg")
                val wbParams = editConfig?.let {
                    floatArrayOf(it.wbMode.toFloat(), it.wbTempOffset, it.wbTintOffset, it.wbManualTempK)
                }

                val ret = ColorProcessor.processRaw(
                    dngData = dngBytes,
                    targetLog = targetLogIndex,
                    lutPath = lutPath,
                    exposure = editConfig?.exposure ?: 0f,
                    contrast = editConfig?.contrast ?: 0f,
                    saturation = editConfig?.saturation ?: 0f,
                    highlights = editConfig?.highlights ?: 0f,
                    shadows = editConfig?.shadows ?: 0f,
                    whites = editConfig?.whites ?: 0f,
                    blacks = editConfig?.blacks ?: 0f,
                    digitalGain = editConfig?.digitalGain ?: 1.0f,
                    outputJpgPath = tempJpg.absolutePath,
                    outputTiffPath = null,
                    useGpu = false,
                    orientation = rotDegrees,
                    mirror = false,
                    outputBitmap = null,
                    downsampleFactor = 1,
                    zoomFactor = editConfig?.zoomFactor ?: 1.0f,
                    metadata = captureMetadata,
                    wbParams = wbParams
                )

                if (ret >= 0 && tempJpg.exists() && tempJpg.length() > 0) {
                    val jpgDisplayName = "${fullBaseName}_graded"
                    finalJpgUri = ImageSaver.saveProcessedImage(
                        context = context,
                        inputBitmap = null,
                        bmpPath = tempJpg.absolutePath,
                        rotationDegrees = 0,
                        zoomFactor = 1.0f,
                        baseName = jpgDisplayName,
                        linearDngPath = null,
                        saveJpg = true,
                        saveRaw = false,
                        jpgFolderUri = jpgFolderUri,
                        editConfig = editConfig,
                        isAlreadyStitched = true,
                        captureMetadata = captureMetadata
                    )
                    Log.i(TAG, "Exported single frame Graded JPG: $finalJpgUri")
                } else {
                    Log.e(TAG, "ColorProcessor.processRaw failed to generate graded JPG (ret=$ret)")
                    if (tempJpg.exists()) tempJpg.delete()
                }
            }

            Pair(finalRawUri, finalJpgUri)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export single frame from CinemaDNG", e)
            Pair(null, null)
        }
    }

    /**
     * Exports a .rawvid clip into a standard Adobe CinemaDNG folder:
     * [ClipName]/
     *   ├── [ClipName]_%06d.dng
     *   └── [ClipName].wav
     */
    suspend fun exportToCinemaDng(
        context: Context,
        rawVideoUri: Uri,
        outputDir: File,
        clipName: String? = null,
        isCancelled: (() -> Boolean)? = null,
        onProgress: (current: Int, total: Int) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        var nativeHandle = 0L
        var clipDir: File? = null
        var isSuccess = false
        if (isCancelled?.invoke() == true) {
            Log.i(TAG, "Export cancelled by user before opening reader, aborting...")
            return@withContext null
        }
        val pfd = try {
            context.contentResolver.openFileDescriptor(rawVideoUri, "r")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open rawVideoUri: $rawVideoUri", e)
            null
        } ?: return@withContext null

        pfd.use { parcelFd ->
            try {
                val fd = parcelFd.fd
                nativeHandle = RawVideoNative.nativeOpenReaderFd(fd)
                if (nativeHandle == 0L) {
                    Log.e(TAG, "Failed to open native reader for CinemaDNG export (fd=$fd, uri=$rawVideoUri)")
                    return@withContext null
                }

                val header = RawVideoNative.readHeader(nativeHandle) ?: return@withContext null
                val totalFrames = RawVideoNative.nativeGetFrameCount(nativeHandle)
                if (totalFrames <= 0) {
                    return@withContext null
                }

                val rawSegment = rawVideoUri.lastPathSegment ?: "RAWVID_${System.currentTimeMillis()}"
                val defaultClipName = rawSegment.substringAfterLast("/").substringAfterLast(":").substringBeforeLast(".")
                val effectiveClipName = clipName ?: if (
                    outputDir.name.startsWith("CDNG_") ||
                    outputDir.name.startsWith("RAWVID_") ||
                    outputDir.name == defaultClipName
                ) {
                    outputDir.name
                } else {
                    defaultClipName
                }
                val dir = if (outputDir.name == effectiveClipName) outputDir else File(outputDir, effectiveClipName).apply { mkdirs() }
                clipDir = dir

                val bayerBufferSize = header.width * header.height * 2
                val frameBuffer = ByteBuffer.allocateDirect(bayerBufferSize)

                for (i in 0 until totalFrames) {
                    if (isCancelled?.invoke() == true) {
                        Log.i(TAG, "Export cancelled by user, aborting CinemaDNG frame export...")
                        return@withContext null
                    }
                    frameBuffer.clear()
                    val meta = LongArray(5)
                    val readBytes = RawVideoNative.nativeReadFrame(nativeHandle, i, meta, frameBuffer)
                    if (readBytes > 0) {
                        val dngFile = File(dir, String.format(Locale.US, "%s_%06d.dng", effectiveClipName, i))
                        CinemaDngWriter.writeDngFile(dngFile, header, meta, frameBuffer, readBytes)
                    }
                    onProgress(i + 1, totalFrames)

                    if (isCancelled?.invoke() == true) {
                        Log.i(TAG, "Export cancelled by user after frame $i, aborting...")
                        return@withContext null
                    }
                }

                if (isCancelled?.invoke() == true) {
                    Log.i(TAG, "Export cancelled by user before WAV write, aborting...")
                    return@withContext null
                }

                val wavFile = File(dir, "$effectiveClipName.wav")
                CinemaDngWriter.writeWavFile(nativeHandle, wavFile, header)

                isSuccess = true
                return@withContext dir
            } catch (e: Exception) {
                Log.e(TAG, "Failed CinemaDNG export", e)
                return@withContext null
            } finally {
                if (!isSuccess) {
                    clipDir?.let { d ->
                        try {
                            if (d.exists()) d.deleteRecursively()
                        } catch (_: Exception) {}
                    }
                }
                if (nativeHandle != 0L) {
                    try { RawVideoNative.nativeCloseReader(nativeHandle) } catch (_: Exception) {}
                    nativeHandle = 0L
                }
            }
        }
    }
}
