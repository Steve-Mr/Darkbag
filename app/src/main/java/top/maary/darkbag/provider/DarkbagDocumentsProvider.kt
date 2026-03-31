package top.maary.darkbag.provider

import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Point
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import java.io.File

class DarkbagDocumentsProvider : DocumentsProvider() {
    override fun onCreate(): Boolean {
        // Ensure export directory exists
        val exportDir = File(checkNotNull(context).filesDir, "shared_exports")
        if (!exportDir.exists()) exportDir.mkdirs()
        return true
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)

        // Root 1: Half-frame Intermediates
        result.newRow().apply {
            add(DocumentsContract.Root.COLUMN_ROOT_ID, ROOT_ID_INTERMEDIATES)
            add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, ROOT_ID_INTERMEDIATES)
            add(DocumentsContract.Root.COLUMN_TITLE, "Darkbag Intermediates")
            add(DocumentsContract.Root.COLUMN_SUMMARY, "In-progress half-frame frames")
            add(DocumentsContract.Root.COLUMN_FLAGS, DocumentsContract.Root.FLAG_SUPPORTS_RECENTS or DocumentsContract.Root.FLAG_LOCAL_ONLY)
            add(DocumentsContract.Root.COLUMN_MIME_TYPES, "image/jpeg")
            add(DocumentsContract.Root.COLUMN_AVAILABLE_BYTES, rootDir().usableSpace)
            add(DocumentsContract.Root.COLUMN_ICON, android.R.drawable.ic_menu_gallery)
        }

        // Root 2: Shared Exports (TIFFs)
        result.newRow().apply {
            add(DocumentsContract.Root.COLUMN_ROOT_ID, ROOT_ID_EXPORTS)
            add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, ROOT_ID_EXPORTS)
            add(DocumentsContract.Root.COLUMN_TITLE, "Darkbag Exports")
            add(DocumentsContract.Root.COLUMN_SUMMARY, "Shared TIFF images")
            add(DocumentsContract.Root.COLUMN_FLAGS, DocumentsContract.Root.FLAG_LOCAL_ONLY)
            add(DocumentsContract.Root.COLUMN_MIME_TYPES, "image/tiff,image/x-adobe-dng")
            add(DocumentsContract.Root.COLUMN_AVAILABLE_BYTES, rootDir().usableSpace)
            add(DocumentsContract.Root.COLUMN_ICON, android.R.drawable.ic_menu_share)
        }

        return result
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        includeDocument(result, documentId)
        result.setNotificationUri(context?.contentResolver, DocumentsContract.buildChildDocumentsUri(AUTHORITY, documentId.substringBefore(":")))
        return result
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        result.setNotificationUri(context?.contentResolver, DocumentsContract.buildChildDocumentsUri(AUTHORITY, parentDocumentId))
        when (parentDocumentId) {
            ROOT_ID_INTERMEDIATES -> {
                rootDir().listFiles()
                    ?.filter { it.isFile && it.name.startsWith("half_frame_frame1_") && it.extension.lowercase() == "jpg" }
                    ?.sortedByDescending { it.lastModified() }
                    ?.forEach { includeFile(result, it, ROOT_ID_INTERMEDIATES) }
            }
            ROOT_ID_EXPORTS -> {
                File(rootDir(), "shared_exports").listFiles()
                    ?.filter { it.isFile }
                    ?.sortedByDescending { it.lastModified() }
                    ?.forEach { includeFile(result, it, ROOT_ID_EXPORTS) }
            }
        }
        return result
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, queryArgs: Bundle?): Cursor {
        return queryChildDocuments(parentDocumentId, projection, queryArgs?.getString(android.content.ContentResolver.QUERY_ARG_SQL_SORT_ORDER))
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val file = fileFromDocumentId(documentId)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }

    override fun getDocumentType(documentId: String): String = when {
        documentId == ROOT_ID_INTERMEDIATES || documentId == ROOT_ID_EXPORTS -> DocumentsContract.Document.MIME_TYPE_DIR
        documentId.endsWith(".tif") || documentId.endsWith(".tiff") -> "image/tiff"
        documentId.endsWith(".dng") -> "image/x-adobe-dng"
        else -> "image/jpeg"
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        return (parentDocumentId == ROOT_ID_INTERMEDIATES && documentId.startsWith("$ROOT_ID_INTERMEDIATES:")) ||
               (parentDocumentId == ROOT_ID_EXPORTS && documentId.startsWith("$ROOT_ID_EXPORTS:"))
    }

    override fun queryRecentDocuments(rootId: String, projection: Array<out String>?): Cursor =
        queryChildDocuments(rootId, projection, null as String?)

    override fun openDocumentThumbnail(documentId: String, sizeHint: Point, signal: CancellationSignal?): AssetFileDescriptor {
        val pfd = openDocument(documentId, "r", signal)
        return AssetFileDescriptor(pfd, 0, AssetFileDescriptor.UNKNOWN_LENGTH)
    }

    private fun includeDocument(result: MatrixCursor, documentId: String) {
        if (documentId == ROOT_ID_INTERMEDIATES || documentId == ROOT_ID_EXPORTS) {
            val row = result.newRow()
            row.add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, documentId)
            row.add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, if (documentId == ROOT_ID_INTERMEDIATES) "Intermediates" else "Exports")
            row.add(DocumentsContract.Document.COLUMN_SIZE, null)
            row.add(DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.MIME_TYPE_DIR)
            row.add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, System.currentTimeMillis())
            row.add(DocumentsContract.Document.COLUMN_FLAGS, DocumentsContract.Document.FLAG_DIR_PREFERS_LAST_MODIFIED)
        } else {
            includeFile(result, fileFromDocumentId(documentId), documentId.substringBefore(":"))
        }
    }

    private fun includeFile(result: MatrixCursor, file: File, rootId: String) {
        if (!file.exists()) return
        val row = result.newRow()
        row.add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, "$rootId:${file.name}")
        row.add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, file.name)
        row.add(DocumentsContract.Document.COLUMN_SIZE, file.length())
        row.add(DocumentsContract.Document.COLUMN_MIME_TYPE, getDocumentType(file.name))
        row.add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, file.lastModified())
        row.add(DocumentsContract.Document.COLUMN_FLAGS, DocumentsContract.Document.FLAG_SUPPORTS_THUMBNAIL)
    }

    private fun fileFromDocumentId(documentId: String): File {
        val rootId = documentId.substringBefore(":")
        val fileName = documentId.substringAfter(":")
        val baseDir = if (rootId == ROOT_ID_EXPORTS) File(rootDir(), "shared_exports") else rootDir()

        val root = baseDir.canonicalFile
        val file = File(root, fileName).canonicalFile
        require(file.path.startsWith(root.path + File.separator)) { "Invalid documentId: $documentId" }
        return file
    }

    private fun rootDir(): File = checkNotNull(context) { "Context not available" }.filesDir

    companion object {
        const val AUTHORITY = "top.maary.darkbag.documents"
        const val ROOT_ID_INTERMEDIATES = "intermediates"
        const val ROOT_ID_EXPORTS = "exports"

        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_MIME_TYPES,
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_ICON,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_SUMMARY,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_AVAILABLE_BYTES
        )
        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_SIZE
        )
    }
}
