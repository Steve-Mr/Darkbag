package top.maary.darkbag.repository

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import top.maary.darkbag.utils.ImageUtils

/**
 * Encapsulates SAF document tree traversal, child querying, and CinemaDNG/RAW file recognition.
 */
object SafStorageScanner {

    private val CINEMADNG_FRAME_REGEX = Regex("^(?:DBAG_)?((?:RAWVID|CDNG)_[0-9]{8}_[0-9]{6}(?:_[0-9]+)?)_([0-9]{6})\\.dng$", RegexOption.IGNORE_CASE)
    private val CINEMADNG_AUDIO_REGEX = Regex("^(?:DBAG_)?((?:RAWVID|CDNG)_[0-9]{8}_[0-9]{6}(?:_[0-9]+)?)\\.wav$", RegexOption.IGNORE_CASE)

    fun isCinemaDngDirectoryName(name: String): Boolean {
        return ImageRepository.isDarkbagFileName(name) &&
                (name.contains("RAWVID", ignoreCase = true) || name.contains("CDNG", ignoreCase = true))
    }

    fun scanSafFolder(
        context: Context,
        folderUri: String,
        groups: MutableMap<String, ImageGroupBuilder>,
        fast: Boolean = false,
        readExif: (Uri, ImageGroupBuilder) -> Unit
    ) {
        try {
            val treeUri = Uri.parse(folderUri)
            val docId = if (DocumentsContract.isTreeUri(treeUri)) {
                DocumentsContract.getTreeDocumentId(treeUri)
            } else {
                DocumentsContract.getDocumentId(treeUri)
            }
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                DocumentsContract.Document.COLUMN_SIZE
            )

            var scannedWithDirectQuery = false
            try {
                context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                    scannedWithDirectQuery = true
                    val idColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val nameColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val mimeColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    val modifiedColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                    val sizeColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)

                    while (cursor.moveToNext()) {
                        try {
                            if (idColumn == -1 || nameColumn == -1) continue
                            val childDocId = cursor.getString(idColumn) ?: continue
                            val name = cursor.getString(nameColumn) ?: continue
                            val mime = if (mimeColumn != -1) cursor.getString(mimeColumn) else null
                            val lastModified = if (modifiedColumn != -1) cursor.getLong(modifiedColumn) else 0L
                            val size = if (sizeColumn != -1) cursor.getLong(sizeColumn) else 0L

                            if (mime == DocumentsContract.Document.MIME_TYPE_DIR || mime == "vnd.android.document/directory") {
                                if (isCinemaDngDirectoryName(name)) {
                                    val baseName = ImageUtils.getBaseName(name)
                                    val builder = groups.getOrPut(baseName) { ImageGroupBuilder(baseName, context) }
                                    val folderDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childDocId)

                                    var firstFrameUri: Uri? = null
                                    var audioUri: Uri? = null
                                    var folderLastModified = lastModified
                                    val frameUris = mutableListOf<Uri>()

                                    try {
                                        val subChildrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, childDocId)
                                        context.contentResolver.query(subChildrenUri, projection, null, null, null)?.use { subCursor ->
                                            val subIdCol = subCursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                                            val subNameCol = subCursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                                            val subModCol = subCursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                                            val subSizeCol = subCursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)

                                            val subFrames = mutableListOf<Pair<String, Uri>>()

                                            while (subCursor.moveToNext()) {
                                                if (subIdCol == -1 || subNameCol == -1) continue
                                                val subDocId = subCursor.getString(subIdCol) ?: continue
                                                val subName = subCursor.getString(subNameCol) ?: continue
                                                val subMod = if (subModCol != -1) subCursor.getLong(subModCol) else 0L
                                                val subSize = if (subSizeCol != -1) subCursor.getLong(subSizeCol) else 0L
                                                val subDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, subDocId)
                                                if (subMod > folderLastModified) folderLastModified = subMod
                                                builder.addSize(subSize)

                                                if (subName.endsWith(".dng", ignoreCase = true)) {
                                                    subFrames.add(subName to subDocUri)
                                                } else if (subName.endsWith(".wav", ignoreCase = true)) {
                                                    audioUri = subDocUri
                                                }
                                            }

                                            subFrames.sortBy { it.first }
                                            firstFrameUri = subFrames.firstOrNull { it.first.contains("_000000.dng", ignoreCase = true) }?.second
                                                ?: subFrames.firstOrNull()?.second
                                            frameUris.addAll(subFrames.map { it.second })
                                        }
                                    } catch (e: Exception) {
                                        android.util.Log.w("SafStorageScanner", "Failed to query child documents for CinemaDNG folder $name", e)
                                    }

                                    builder.setCinemaDngFolder(
                                        folderUri = folderDocUri,
                                        firstFrameUri = firstFrameUri,
                                        audioUri = audioUri,
                                        frameCount = frameUris.size,
                                        time = folderLastModified,
                                        modifiedTime = folderLastModified
                                    )
                                    if (frameUris.isNotEmpty()) {
                                        builder.cinemaDngFrameUris.clear()
                                        builder.cinemaDngFrameUris.addAll(frameUris)
                                    }

                                    if (!fast) {
                                        firstFrameUri?.let { readExif(it, builder) }
                                    }
                                    continue
                                }
                            }

                            if (!ImageRepository.isDarkbagFileName(name)) continue
                            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childDocId)

                            val cdngFrameMatch = CINEMADNG_FRAME_REGEX.find(name)
                            val cdngAudioMatch = CINEMADNG_AUDIO_REGEX.find(name)

                            val matchedBuilder = when {
                                cdngFrameMatch != null -> {
                                    val clipBaseName = cdngFrameMatch.groupValues[1]
                                    val frameIndex = cdngFrameMatch.groupValues[2].toIntOrNull() ?: 0
                                    val builder = groups.getOrPut(clipBaseName) { ImageGroupBuilder(clipBaseName, context) }
                                    builder.addCinemaDngFrame(docUri, frameIndex, lastModified)
                                    if (!fast && frameIndex == 0) {
                                        readExif(docUri, builder)
                                    }
                                    builder
                                }
                                cdngAudioMatch != null -> {
                                    val clipBaseName = cdngAudioMatch.groupValues[1]
                                    val builder = groups.getOrPut(clipBaseName) { ImageGroupBuilder(clipBaseName, context) }
                                    builder.setCinemaDngAudio(docUri, lastModified)
                                    builder
                                }
                                name.contains("_MULTI_") && (name.endsWith(".jpg", ignoreCase = true) || name.endsWith(".jpeg", ignoreCase = true)) -> {
                                    val baseName = ImageUtils.getBaseName(name)
                                    val builder = groups.getOrPut(baseName) { ImageGroupBuilder(baseName, context) }
                                    builder.addMultiJpg(docUri, name, lastModified)
                                    if (!fast) {
                                        readExif(docUri, builder)
                                    }
                                    builder
                                }
                                name.contains("_MULTI_") && name.endsWith(".dng", ignoreCase = true) -> {
                                    val baseName = ImageUtils.getBaseName(name)
                                    val builder = groups.getOrPut(baseName) { ImageGroupBuilder(baseName, context) }
                                    builder.addMultiDng(docUri, name, lastModified)
                                    builder
                                }
                                name.endsWith(".jpg", ignoreCase = true) || name.endsWith(".jpeg", ignoreCase = true) -> {
                                    val baseName = ImageUtils.getBaseName(name)
                                    val builder = groups.getOrPut(baseName) { ImageGroupBuilder(baseName, context) }
                                    builder.setJpg(docUri, lastModified)
                                    if (!fast) {
                                        readExif(docUri, builder)
                                    }
                                    builder
                                }
                                name.contains("_HF2") && name.endsWith(".dng", ignoreCase = true) -> {
                                    val baseName = ImageUtils.getBaseName(name)
                                    val builder = groups.getOrPut(baseName) { ImageGroupBuilder(baseName, context) }
                                    builder.setDng2(docUri, lastModified)
                                    builder
                                }
                                name.contains("_HF1") && name.endsWith(".dng", ignoreCase = true) -> {
                                    val baseName = ImageUtils.getBaseName(name)
                                    val builder = groups.getOrPut(baseName) { ImageGroupBuilder(baseName, context) }
                                    builder.setDng1(docUri, lastModified)
                                    builder
                                }
                                name.endsWith(".dng", ignoreCase = true) -> {
                                    val baseName = ImageUtils.getBaseName(name)
                                    val builder = groups.getOrPut(baseName) { ImageGroupBuilder(baseName, context) }
                                    builder.setDng(docUri, lastModified)
                                    builder
                                }
                                name.endsWith(".rawvid", ignoreCase = true) -> {
                                    val baseName = ImageUtils.getBaseName(name)
                                    val builder = groups.getOrPut(baseName) { ImageGroupBuilder(baseName, context) }
                                    builder.setRawVideo(docUri, lastModified)
                                    builder
                                }
                                name.endsWith(".mp4", ignoreCase = true) -> {
                                    val baseName = ImageUtils.getBaseName(name)
                                    val builder = groups.getOrPut(baseName) { ImageGroupBuilder(baseName, context) }
                                    builder.setMp4Video(docUri, lastModified)
                                    builder
                                }
                                else -> null
                            }
                            matchedBuilder?.addSize(size)
                        } catch (e: Throwable) {
                            android.util.Log.w("SafStorageScanner", "Error processing SAF entry", e)
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("SafStorageScanner", "Direct SAF children query failed for $folderUri, falling back", e)
            }

            // Fallback for providers that don't support buildChildDocumentsUriUsingTree
            if (!scannedWithDirectQuery) {
                val root = DocumentFile.fromTreeUri(context, treeUri)
                root?.listFiles()?.forEach { file ->
                    val name = file.name ?: return@forEach
                    if (file.isDirectory) {
                        if (isCinemaDngDirectoryName(name)) {
                            val baseName = ImageUtils.getBaseName(name)
                            val builder = groups.getOrPut(baseName) { ImageGroupBuilder(baseName, context) }
                            var firstFrameUri: Uri? = null
                            var audioUri: Uri? = null
                            val subFrames = mutableListOf<Pair<String, Uri>>()
                            var folderLastModified = file.lastModified()

                            file.listFiles().forEach { subFile ->
                                val subName = subFile.name ?: return@forEach
                                val subMod = subFile.lastModified()
                                if (subMod > folderLastModified) folderLastModified = subMod
                                if (subName.endsWith(".dng", ignoreCase = true)) {
                                    subFrames.add(subName to subFile.uri)
                                } else if (subName.endsWith(".wav", ignoreCase = true)) {
                                    audioUri = subFile.uri
                                }
                            }
                            subFrames.sortBy { it.first }
                            firstFrameUri = subFrames.firstOrNull { it.first.contains("_000000.dng", ignoreCase = true) }?.second
                                ?: subFrames.firstOrNull()?.second
                            val frameUris = subFrames.map { it.second }

                            builder.setCinemaDngFolder(
                                folderUri = file.uri,
                                firstFrameUri = firstFrameUri,
                                audioUri = audioUri,
                                frameCount = frameUris.size,
                                time = folderLastModified,
                                modifiedTime = folderLastModified
                            )
                            if (frameUris.isNotEmpty()) {
                                builder.cinemaDngFrameUris.clear()
                                builder.cinemaDngFrameUris.addAll(frameUris)
                            }
                            if (!fast) {
                                firstFrameUri?.let { readExif(it, builder) }
                            }
                            return@forEach
                        }
                    }

                    if (!ImageRepository.isDarkbagFileName(name)) return@forEach
                    val lastModified = file.lastModified()

                    val cdngFrameMatch = CINEMADNG_FRAME_REGEX.find(name)
                    val cdngAudioMatch = CINEMADNG_AUDIO_REGEX.find(name)

                    when {
                        cdngFrameMatch != null -> {
                            val clipBaseName = cdngFrameMatch.groupValues[1]
                            val frameIndex = cdngFrameMatch.groupValues[2].toIntOrNull() ?: 0
                            val builder = groups.getOrPut(clipBaseName) { ImageGroupBuilder(clipBaseName, context) }
                            builder.addCinemaDngFrame(file.uri, frameIndex, lastModified)
                            if (!fast && frameIndex == 0) {
                                readExif(file.uri, builder)
                            }
                        }
                        cdngAudioMatch != null -> {
                            val clipBaseName = cdngAudioMatch.groupValues[1]
                            val builder = groups.getOrPut(clipBaseName) { ImageGroupBuilder(clipBaseName, context) }
                            builder.setCinemaDngAudio(file.uri, lastModified)
                        }
                        name.contains("_MULTI_") && (name.endsWith(".jpg", ignoreCase = true) || name.endsWith(".jpeg", ignoreCase = true)) -> {
                            val baseName = ImageUtils.getBaseName(name)
                            val builder = groups.getOrPut(baseName) { ImageGroupBuilder(baseName, context) }
                            builder.addMultiJpg(file.uri, name, lastModified)
                            if (!fast) {
                                readExif(file.uri, builder)
                            }
                        }
                        name.contains("_MULTI_") && name.endsWith(".dng", ignoreCase = true) -> {
                            val baseName = ImageUtils.getBaseName(name)
                            val builder = groups.getOrPut(baseName) { ImageGroupBuilder(baseName, context) }
                            builder.addMultiDng(file.uri, name, lastModified)
                        }
                        name.endsWith(".jpg", ignoreCase = true) || name.endsWith(".jpeg", ignoreCase = true) -> {
                            val baseName = ImageUtils.getBaseName(name)
                            val builder = groups.getOrPut(baseName) { ImageGroupBuilder(baseName, context) }
                            builder.setJpg(file.uri, lastModified)
                            if (!fast) {
                                readExif(file.uri, builder)
                            }
                        }
                        name.contains("_HF2") && name.endsWith(".dng", ignoreCase = true) -> {
                            val baseName = ImageUtils.getBaseName(name)
                            val builder = groups.getOrPut(baseName) { ImageGroupBuilder(baseName, context) }
                            builder.setDng2(file.uri, lastModified)
                        }
                        name.contains("_HF1") && name.endsWith(".dng", ignoreCase = true) -> {
                            val baseName = ImageUtils.getBaseName(name)
                            val builder = groups.getOrPut(baseName) { ImageGroupBuilder(baseName, context) }
                            builder.setDng1(file.uri, lastModified)
                        }
                        name.endsWith(".dng", ignoreCase = true) -> {
                            val baseName = ImageUtils.getBaseName(name)
                            val builder = groups.getOrPut(baseName) { ImageGroupBuilder(baseName, context) }
                            builder.setDng(file.uri, lastModified)
                        }
                        name.endsWith(".rawvid", ignoreCase = true) -> {
                            val baseName = ImageUtils.getBaseName(name)
                            val builder = groups.getOrPut(baseName) { ImageGroupBuilder(baseName, context) }
                            builder.setRawVideo(file.uri, lastModified)
                        }
                        name.endsWith(".mp4", ignoreCase = true) -> {
                            val baseName = ImageUtils.getBaseName(name)
                            val builder = groups.getOrPut(baseName) { ImageGroupBuilder(baseName, context) }
                            builder.setMp4Video(file.uri, lastModified)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("SafStorageScanner", "Failed to scan SAF folder: $folderUri", e)
        }
    }
}
