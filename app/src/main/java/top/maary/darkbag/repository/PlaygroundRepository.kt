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
import top.maary.darkbag.models.BasicAdjustments
import top.maary.darkbag.models.EditConfig
import top.maary.darkbag.models.ImageGroup
import top.maary.darkbag.models.PlaygroundItem
import top.maary.darkbag.pipeline.darkroom.DarkroomRenderEngine
import top.maary.darkbag.pipeline.darkroom.model.DarkroomRenderSpec
import top.maary.darkbag.pipeline.darkroom.model.DarkroomSource
import top.maary.darkbag.pipeline.darkroom.model.RenderOutputFormat
import top.maary.darkbag.pipeline.darkroom.sink.MediaStoreExportSink
import top.maary.darkbag.processor.ColorProcessor
import top.maary.darkbag.utils.HalfFrameManager
import top.maary.darkbag.utils.ImageSaver
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

    val playgroundDir: File by lazy {
        val dir = File(context.filesDir, "playground_dngs")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        dir
    }

    @Volatile
    private var cachedItems: List<PlaygroundItem>? = null

    private val aspectRatioCache = mutableMapOf<String, String>()

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
        val jpgFiles = files.filter { it.extension.lowercase() == "jpg" }
        val exactJpgMap = jpgFiles.associateBy { it.nameWithoutExtension }

        // Group edited derivative JPGs by their master base name
        val derivativeMap = mutableMapOf<String, MutableList<File>>()
        for (jpg in jpgFiles) {
            val name = jpg.nameWithoutExtension
            if (name.contains("_edited_")) {
                val masterBase = name.substringBefore("_edited_")
                derivativeMap.getOrPut(masterBase) { mutableListOf() }.add(jpg)
            }
        }

        val newItems = mutableListOf<PlaygroundItem>()
        val processedBases = mutableSetOf<String>()

        // Pre-mark all derivative JPGs so they are not treated as independent standalone items
        for (derivList in derivativeMap.values) {
            derivList.sortBy { it.lastModified() }
            for (f in derivList) {
                processedBases.add(f.nameWithoutExtension)
            }
        }

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
            val groupJpg = exactJpgMap[groupBase]

            if (dng1 != null && dng2 != null && groupJpg != null) {
                val ratio = getOrCalculateAspectRatio(groupJpg)
                val derivs = derivativeMap[groupBase] ?: emptyList()
                newItems.add(PlaygroundItem.Group(groupJpg, dng1, dng2, derivativeFiles = derivs, aspectRatio = ratio))
                processedBases.add(groupBase)
                processedBases.add("${groupBase}_1")
                processedBases.add("${groupBase}_2")
            } else if (file.extension.lowercase() == "dng") {
                if (!processedBases.contains(baseName)) {
                    val singleJpg = exactJpgMap[baseName]
                    val derivs = derivativeMap[baseName] ?: emptyList()
                    val ratioFile = singleJpg ?: derivs.firstOrNull() ?: file
                    val ratio = getOrCalculateAspectRatio(ratioFile)
                    newItems.add(PlaygroundItem.Single(file, derivativeFiles = derivs, aspectRatio = ratio))
                    processedBases.add(baseName)
                }
            }
        }

        return newItems.sortedByDescending { it.mainFile.lastModified() }
    }

    private fun getOrCalculateAspectRatio(file: File): String? {
        val cached = aspectRatioCache[file.absolutePath]
        if (cached != null) return cached

        val ratio = top.maary.darkbag.utils.GalleryAspectRatioHelper.getOrCalculateAspectRatio(file) ?: "3:4"
        aspectRatioCache[file.absolutePath] = ratio
        return ratio
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
                top.maary.darkbag.utils.GalleryAspectRatioHelper.evict(f.absolutePath)
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
                top.maary.darkbag.utils.GalleryAspectRatioHelper.evict(jpg.absolutePath)
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

                val success = when (item) {
                    is PlaygroundItem.Single -> exportSingleItem(item, appContext, imageRepo)
                    is PlaygroundItem.Group -> exportGroupItem(item, appContext, imageRepo, processedGroups)
                }
                if (success) {
                    successCount++
                }
            } catch (e: Exception) {
                Log.e("PlaygroundRepository", "Failed to export ${file.name}", e)
            }
        }
        successCount
    }

    private suspend fun exportSingleItem(
        item: PlaygroundItem.Single,
        appContext: Context,
        imageRepo: ImageRepository
    ): Boolean {
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

        if (item.file.extension.lowercase() == "jpg") {
            val decodeOpts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val inputBitmap = BitmapFactory.decodeFile(item.file.absolutePath, decodeOpts) ?: return false

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
            inputBitmap.recycle()
            return uri != null
        } else {
            val single = DarkroomSource.Single(
                dngFile = item.file,
                dngUri = dngUri,
                baseName = baseName,
                captureMetadata = imageRepo.getCaptureMetadata(dngUri)
            )
            val mediaStoreSink = MediaStoreExportSink(
                baseName = baseName,
                isReplacement = false
            )
            val spec = DarkroomRenderSpec(
                source = single,
                editConfig = config,
                sink = mediaStoreSink,
                outputFormat = RenderOutputFormat.JPEG
            )
            val result = DarkroomRenderEngine.render(appContext, spec)
            return result.success && result.uri != null
        }
    }

    private suspend fun exportGroupItem(
        item: PlaygroundItem.Group,
        appContext: Context,
        imageRepo: ImageRepository,
        processedGroups: MutableSet<String>
    ): Boolean {
        val baseName = item.jpgFile.nameWithoutExtension
        if (processedGroups.contains(baseName)) return false
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
        val mediaStoreSink = MediaStoreExportSink(
            baseName = baseName,
            isReplacement = false
        )
        val spec = DarkroomRenderSpec.fromImageGroup(
            context = appContext,
            group = loadedGroup,
            editConfig = config,
            sink = mediaStoreSink,
            outputFormat = RenderOutputFormat.JPEG,
            downsampleHalfFrame = HalfFrameManager(appContext).downsample,
            repository = imageRepo
        )
        val result = DarkroomRenderEngine.render(appContext, spec)
        return result.success && result.uri != null
    }

    fun clearCache() {
        cachedItems = null
        aspectRatioCache.clear()
    }
}
