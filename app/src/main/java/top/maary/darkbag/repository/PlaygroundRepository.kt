package top.maary.darkbag.repository

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import top.maary.darkbag.fragments.SettingsFragment
import top.maary.darkbag.models.BasicAdjustments
import top.maary.darkbag.models.EditConfig
import top.maary.darkbag.models.ImageGroup
import top.maary.darkbag.models.PlaygroundItem
import top.maary.darkbag.processor.ColorProcessor
import top.maary.darkbag.utils.HalfFrameComposer
import top.maary.darkbag.utils.HalfFrameManager
import top.maary.darkbag.utils.ImageSaver
import top.maary.darkbag.utils.LutManager
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID

/**
 * 工坊外部沙盒文件仓储 (PlaygroundRepository).
 * 封装工坊目录扫描、半格配对、EXIF 宽高比提取缓存、DNG 导入及删除解散操作。
 * 纯粹运行于 Dispatchers.IO，提供 Stale-While-Revalidate 瞬时交付。
 */
class PlaygroundRepository(private val context: Context) {

    private val playgroundDir: File by lazy {
        val dir = File(context.filesDir, "playground_dngs")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        dir
    }

    @Volatile
    private var cachedItems: List<PlaygroundItem>? = null

    private val aspectRatioCache = mutableMapOf<String, String>()

    fun getPlaygroundDir(): File = playgroundDir

    fun getPlaygroundItemsFlow(forceRefresh: Boolean = false): Flow<List<PlaygroundItem>> = flow {
        // Stage 0: Stale-While-Revalidate 瞬时 T2 交付
        cachedItems?.let { cached ->
            emit(cached)
            if (!forceRefresh) return@flow
        }

        // Stage 1: IO 线程全量扫描
        val fresh = scanPlaygroundItemsInternal()
        cachedItems = fresh
        emit(fresh)
    }.flowOn(Dispatchers.IO)

    suspend fun scanPlaygroundItems(): List<PlaygroundItem> = withContext(Dispatchers.IO) {
        val fresh = scanPlaygroundItemsInternal()
        cachedItems = fresh
        fresh
    }

    private fun scanPlaygroundItemsInternal(): List<PlaygroundItem> {
        val dir = playgroundDir
        val files = dir.listFiles { file ->
            val ext = file.extension.lowercase()
            ext == "dng" || ext == "jpg"
        } ?: emptyArray()

        val dngMap = files.filter { it.extension.lowercase() == "dng" }.groupBy { it.nameWithoutExtension }
        val jpgMap = files.filter { it.extension.lowercase() == "jpg" }.associateBy { it.nameWithoutExtension }

        val newItems = mutableListOf<PlaygroundItem>()
        val processedBases = mutableSetOf<String>()

        for (file in files) {
            val baseName = file.nameWithoutExtension
            if (processedBases.contains(baseName)) continue

            // 检查是否属于半格成片组 (_1 或 _2)
            var groupBase = baseName
            if (baseName.endsWith("_1")) groupBase = baseName.removeSuffix("_1")
            if (baseName.endsWith("_2")) groupBase = baseName.removeSuffix("_2")

            if (processedBases.contains(groupBase)) continue

            val dng1 = dngMap["${groupBase}_1"]?.firstOrNull()
            val dng2 = dngMap["${groupBase}_2"]?.firstOrNull()
            val groupJpg = jpgMap[groupBase]

            if (dng1 != null && dng2 != null && groupJpg != null) {
                val ratio = getOrCalculateAspectRatio(groupJpg)
                newItems.add(PlaygroundItem.Group(groupJpg, dng1, dng2, aspectRatio = ratio))
                processedBases.add(groupBase)
                processedBases.add("${groupBase}_1")
                processedBases.add("${groupBase}_2")
            } else if (file.extension.lowercase() == "dng") {
                if (!processedBases.contains(baseName)) {
                    val singleJpg = jpgMap[baseName]
                    val ratioFile = singleJpg ?: file
                    val ratio = getOrCalculateAspectRatio(ratioFile)
                    newItems.add(PlaygroundItem.Single(file, aspectRatio = ratio))
                    processedBases.add(baseName)
                }
            }
        }

        return newItems.sortedByDescending { it.mainFile.lastModified() }
    }

    private fun getOrCalculateAspectRatio(file: File): String? {
        val cached = aspectRatioCache[file.absolutePath]
        if (cached != null) return cached

        try {
            val exifInterface = ExifInterface(file.absolutePath)
            var width = exifInterface.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
            var height = exifInterface.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)

            if (width == 0 || height == 0) {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, options)
                width = options.outWidth
                height = options.outHeight
            }

            if (width > 0 && height > 0) {
                val orientation = exifInterface.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                val swapDims = orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
                               orientation == ExifInterface.ORIENTATION_ROTATE_270 ||
                               orientation == ExifInterface.ORIENTATION_TRANSPOSE ||
                               orientation == ExifInterface.ORIENTATION_TRANSVERSE

                val ratio = if (swapDims) "$height:$width" else "$width:$height"
                aspectRatioCache[file.absolutePath] = ratio
                return ratio
            }
        } catch (e: Exception) {
            Log.w("PlaygroundRepository", "Failed to calculate aspect ratio for ${file.name}", e)
        }
        return null
    }

    suspend fun importDngs(uris: List<Uri>, contentResolver: ContentResolver): Int = withContext(Dispatchers.IO) {
        val dir = playgroundDir
        var importedCount = 0
        for (uri in uris) {
            try {
                val fileName = getFileName(contentResolver, uri) ?: "imported_${UUID.randomUUID()}.dng"
                val sanitizedFileName = File(fileName).name.ifEmpty { "imported_${UUID.randomUUID()}.dng" }
                var destFile = File(dir, sanitizedFileName)
                var counter = 1
                val baseName = destFile.nameWithoutExtension
                val extension = destFile.extension.let { if (it.isNotEmpty()) ".$it" else "" }
                while (destFile.exists()) {
                    destFile = File(dir, "${baseName}_$counter$extension")
                    counter++
                }
                val copied = contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(destFile).use { output ->
                        input.copyTo(output)
                    }
                    true
                } ?: false
                if (copied) {
                    importedCount++
                }
            } catch (e: Exception) {
                Log.e("PlaygroundRepository", "Failed to import URI: $uri", e)
            }
        }
        if (importedCount > 0) {
            scanPlaygroundItemsInternal().also { cachedItems = it }
        }
        importedCount
    }

    private fun getFileName(contentResolver: ContentResolver, uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index != -1) {
                        result = cursor.getString(index)
                    }
                }
            }
        }
        if (result == null) {
            result = uri.path?.let { path -> File(path).name }
        }
        return result
    }

    suspend fun deleteFiles(filesToDelete: Set<File>, currentItems: List<PlaygroundItem>): Int = withContext(Dispatchers.IO) {
        val cascadeFiles = mutableListOf<File>()
        for (file in filesToDelete) {
            val item = currentItems.find {
                when (it) {
                    is PlaygroundItem.Single -> it.file == file
                    is PlaygroundItem.Group -> it.jpgFile == file || it.dng1 == file || it.dng2 == file
                }
            }
            when (item) {
                is PlaygroundItem.Single -> cascadeFiles.add(item.file)
                is PlaygroundItem.Group -> {
                    if (file == item.jpgFile) {
                        cascadeFiles.add(item.jpgFile)
                        cascadeFiles.add(item.dng1)
                        cascadeFiles.add(item.dng2)
                    } else {
                        cascadeFiles.add(file)
                        cascadeFiles.add(item.jpgFile)
                    }
                }
                null -> cascadeFiles.add(file)
            }
        }
        val distinctFiles = cascadeFiles.distinct()
        var deletedCount = 0
        for (f in distinctFiles) {
            if (f.delete()) {
                aspectRatioCache.remove(f.absolutePath)
                deletedCount++
            }
        }
        scanPlaygroundItemsInternal().also { cachedItems = it }
        deletedCount
    }

    suspend fun disbandGroups(groupJpgFiles: Set<File>): Int = withContext(Dispatchers.IO) {
        var disbandedCount = 0
        for (jpg in groupJpgFiles) {
            if (jpg.delete()) {
                aspectRatioCache.remove(jpg.absolutePath)
                disbandedCount++
            }
        }
        scanPlaygroundItemsInternal().also { cachedItems = it }
        disbandedCount
    }

    suspend fun exportFiles(
        filesToExport: List<File>,
        itemsSnapshot: List<PlaygroundItem>
    ): Int = withContext(ColorProcessor.imageProcessingDispatcher) {
        var successCount = 0
        val appContext = context.applicationContext
        val imageRepo = ImageRepository(appContext)
        val processedGroups = mutableSetOf<String>()

        for (file in filesToExport) {
            try {
                val item = itemsSnapshot.find {
                    when (it) {
                        is PlaygroundItem.Single -> it.file == file
                        is PlaygroundItem.Group -> it.jpgFile == file || it.dng1 == file || it.dng2 == file
                    }
                } ?: continue

                when (item) {
                    is PlaygroundItem.Single -> {
                        val baseName = item.file.nameWithoutExtension
                        val dngUri = Uri.fromFile(item.file)
                        val jpgFile = File(item.file.parent, "$baseName.jpg")
                        val jpgUri = if (jpgFile.exists()) Uri.fromFile(jpgFile) else null

                        val group = ImageGroup(
                            baseName = baseName,
                            dngUri = dngUri,
                            jpgUri = jpgUri,
                            captureTime = item.file.lastModified(),
                            lastModified = item.file.lastModified()
                        )
                        val loadedGroup = imageRepo.loadMetadata(group)
                        val config = loadedGroup.editConfig ?: EditConfig()

                        val logIndex = SettingsFragment.LOG_CURVES.indexOf(config.log)
                        val lutManager = LutManager(appContext)
                        val lutPath = if (config.lut != null && config.lut != "None") {
                            File(lutManager.lutDir, config.lut).absolutePath
                        } else null

                        if (item.file.extension.lowercase() == "jpg") {
                            val decodeOpts = BitmapFactory.Options().apply {
                                inPreferredConfig = Bitmap.Config.ARGB_8888
                            }
                            val inputBitmap = BitmapFactory.decodeFile(item.file.absolutePath, decodeOpts)

                            val uri = ImageSaver.saveProcessedImage(
                                context = appContext,
                                inputBitmap = inputBitmap,
                                bmpPath = null,
                                rotationDegrees = 0,
                                zoomFactor = config.zoomFactor,
                                baseName = baseName,
                                linearDngPath = null,
                                saveJpg = true,
                                saveRaw = false,
                                editConfig = config,
                                isAlreadyStitched = true,
                                captureMetadata = imageRepo.getCaptureMetadata(Uri.fromFile(item.file))
                            )
                            if (uri != null) successCount++
                            inputBitmap?.recycle()
                        } else {
                            val finalBytes = FileInputStream(item.file).use { it.readBytes() }
                            val orientation = try {
                                appContext.contentResolver.openInputStream(dngUri)?.use { input ->
                                    ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                                } ?: ExifInterface.ORIENTATION_NORMAL
                            } catch (e: Exception) { ExifInterface.ORIENTATION_NORMAL }

                            val rotDegrees = when (orientation) {
                                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                                else -> 0
                            }

                            val adj = config.adjustments?.getOrNull(0) ?: BasicAdjustments(config.exposure, config.contrast, config.saturation, config.highlights, config.shadows, config.whites, config.blacks, config.digitalGain)
                            val meta = imageRepo.getCaptureMetadata(dngUri)
                            val tempJpgFile = File(appContext.cacheDir, "temp_export_${System.currentTimeMillis()}.jpg")

                            ColorProcessor.processRaw(
                                dngData = finalBytes,
                                targetLog = logIndex,
                                lutPath = lutPath,
                                exposure = adj.exposure,
                                contrast = adj.contrast,
                                saturation = adj.saturation,
                                highlights = adj.highlights,
                                shadows = adj.shadows,
                                whites = adj.whites,
                                blacks = adj.blacks,
                                digitalGain = adj.digitalGain,
                                outputJpgPath = tempJpgFile.absolutePath,
                                useGpu = false,
                                orientation = rotDegrees,
                                mirror = false,
                                outputBitmap = null,
                                downsampleFactor = 1,
                                zoomFactor = config.zoomFactor,
                                metadata = meta
                            )

                            if (tempJpgFile.exists()) {
                                val uri = ImageSaver.saveProcessedImage(
                                    context = appContext,
                                    inputBitmap = null,
                                    bmpPath = tempJpgFile.absolutePath,
                                    rotationDegrees = 0,
                                    zoomFactor = 1.0f,
                                    baseName = baseName,
                                    linearDngPath = null,
                                    saveJpg = true,
                                    saveRaw = false,
                                    editConfig = config,
                                    isAlreadyStitched = true,
                                    captureMetadata = meta
                                )
                                if (uri != null) successCount++
                                tempJpgFile.delete()
                            }
                        }
                    }
                    is PlaygroundItem.Group -> {
                        val baseName = item.jpgFile.nameWithoutExtension
                        if (processedGroups.contains(baseName)) continue
                        processedGroups.add(baseName)

                        val dngUri1 = Uri.fromFile(item.dng1)
                        val dngUri2 = Uri.fromFile(item.dng2)
                        val jpgUri = Uri.fromFile(item.jpgFile)

                        val group = ImageGroup(
                            baseName = baseName,
                            dngUri1 = dngUri1,
                            dngUri2 = dngUri2,
                            jpgUri = jpgUri,
                            captureTime = item.jpgFile.lastModified(),
                            lastModified = item.jpgFile.lastModified()
                        )

                        val loadedGroup = imageRepo.loadMetadata(group)
                        val config = loadedGroup.editConfig ?: EditConfig()
                        val logIndex = SettingsFragment.LOG_CURVES.indexOf(config.log)
                        val lutManager = LutManager(appContext)
                        val lutPath = if (config.lut != null && config.lut != "None") {
                            File(lutManager.lutDir, config.lut).absolutePath
                        } else null

                        fun processFullSafe(targetFile: File?, uri: Uri?, index: Int): Bitmap? {
                            if (targetFile == null || !targetFile.exists() || uri == null) return null
                            val finalBytes = FileInputStream(targetFile).use { it.readBytes() }

                            val orientation = try {
                                appContext.contentResolver.openInputStream(uri)?.use { input ->
                                    ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                                } ?: ExifInterface.ORIENTATION_NORMAL
                            } catch (e: Exception) { ExifInterface.ORIENTATION_NORMAL }

                            val rotDegrees = when (orientation) {
                                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                                else -> 0
                            }

                            val adj = config.adjustments?.getOrNull(index) ?: BasicAdjustments(config.exposure, config.contrast, config.saturation, config.highlights, config.shadows, config.whites, config.blacks, config.digitalGain)
                            val meta = imageRepo.getCaptureMetadata(uri)
                            val tempJpgFile = File(appContext.cacheDir, "temp_export_${System.currentTimeMillis()}_$index.jpg")

                            ColorProcessor.processRaw(
                                dngData = finalBytes,
                                targetLog = logIndex,
                                lutPath = lutPath,
                                exposure = adj.exposure,
                                contrast = adj.contrast,
                                saturation = adj.saturation,
                                highlights = adj.highlights,
                                shadows = adj.shadows,
                                whites = adj.whites,
                                blacks = adj.blacks,
                                digitalGain = adj.digitalGain,
                                outputJpgPath = tempJpgFile.absolutePath,
                                useGpu = false,
                                orientation = rotDegrees,
                                mirror = false,
                                outputBitmap = null,
                                downsampleFactor = 1,
                                zoomFactor = config.zoomFactor,
                                metadata = meta
                            )

                            if (!tempJpgFile.exists()) return null

                            val decodeOpts = BitmapFactory.Options().apply {
                                inPreferredConfig = Bitmap.Config.ARGB_8888
                            }
                            val bitmap = BitmapFactory.decodeFile(tempJpgFile.absolutePath, decodeOpts)
                            tempJpgFile.delete()
                            return bitmap
                        }

                        val f1 = processFullSafe(item.dng1, dngUri1, 0)
                        val f2 = processFullSafe(item.dng2, dngUri2, 1)

                        val time1 = imageRepo.getCaptureMetadata(dngUri1)?.dateTimeOriginal ?: item.dng1.lastModified()
                        val time2 = imageRepo.getCaptureMetadata(dngUri2)?.dateTimeOriginal ?: item.dng2.lastModified()

                        val finalCompositeBitmap = HalfFrameComposer.compose(
                            frame1 = f1,
                            frame2 = f2,
                            layout = config.hfLayout,
                            isSwapped = config.isSwapped,
                            showTimestamp = config.showTimestamp,
                            flareType = config.flareType,
                            time1 = time1,
                            time2 = time2,
                            downsample = HalfFrameManager(appContext).downsample
                        )
                        f1?.recycle()
                        f2?.recycle()

                        finalCompositeBitmap?.let { bitmap ->
                            val captureMetadata = imageRepo.getCaptureMetadata(dngUri1) ?: imageRepo.getCaptureMetadata(jpgUri)

                            val uri = ImageSaver.saveProcessedImage(
                                context = appContext,
                                inputBitmap = bitmap,
                                bmpPath = null,
                                rotationDegrees = 0,
                                zoomFactor = 1.0f,
                                baseName = baseName,
                                linearDngPath = null,
                                saveJpg = true,
                                saveRaw = false,
                                editConfig = config,
                                isAlreadyStitched = true,
                                captureMetadata = captureMetadata
                            )
                            if (uri != null) successCount++
                            bitmap.recycle()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("PlaygroundRepository", "Failed to export ${file.name}", e)
            }
        }
        successCount
    }

    fun clearCache() {
        cachedItems = null
        aspectRatioCache.clear()
    }
}
