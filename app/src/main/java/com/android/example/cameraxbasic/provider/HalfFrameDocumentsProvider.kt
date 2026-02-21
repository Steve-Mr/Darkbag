package com.android.example.cameraxbasic.provider

import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Point
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import java.io.File

class HalfFrameDocumentsProvider : DocumentsProvider() {
    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        val row = result.newRow()
        row.add(DocumentsContract.Root.COLUMN_ROOT_ID, ROOT_ID)
        row.add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, ROOT_ID)
        row.add(DocumentsContract.Root.COLUMN_TITLE, "Half-frame Intermediate")
        row.add(DocumentsContract.Root.COLUMN_SUMMARY, "In-progress half-frame frame1")
        row.add(DocumentsContract.Root.COLUMN_FLAGS, DocumentsContract.Root.FLAG_SUPPORTS_RECENTS or DocumentsContract.Root.FLAG_LOCAL_ONLY)
        row.add(DocumentsContract.Root.COLUMN_MIME_TYPES, "image/jpeg")
        row.add(DocumentsContract.Root.COLUMN_AVAILABLE_BYTES, rootDir().usableSpace)
        row.add(DocumentsContract.Root.COLUMN_ICON, android.R.drawable.ic_menu_gallery)
        return result
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        includeDocument(result, documentId)
        return result
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        if (parentDocumentId != ROOT_ID) return result
        rootDir().listFiles()
            ?.filter { it.isFile && it.name.startsWith("half_frame_frame1_") && it.extension.lowercase() == "jpg" }
            ?.sortedByDescending { it.lastModified() }
            ?.forEach { includeFile(result, it) }
        return result
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val file = fileFromDocumentId(documentId)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }

    override fun getDocumentType(documentId: String): String =
        if (documentId == ROOT_ID) DocumentsContract.Document.MIME_TYPE_DIR else "image/jpeg"

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        parentDocumentId == ROOT_ID && documentId.startsWith("file:")

    override fun queryRecentDocuments(rootId: String, projection: Array<out String>?): Cursor =
        queryChildDocuments(ROOT_ID, projection, null)

    override fun openDocumentThumbnail(documentId: String, sizeHint: Point, signal: CancellationSignal?): AssetFileDescriptor {
        val pfd = openDocument(documentId, "r", signal)
        return AssetFileDescriptor(pfd, 0, AssetFileDescriptor.UNKNOWN_LENGTH)
    }

    private fun includeDocument(result: MatrixCursor, documentId: String) {
        if (documentId == ROOT_ID) {
            val row = result.newRow()
            row.add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, ROOT_ID)
            row.add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, "Half-frame Intermediate")
            row.add(DocumentsContract.Document.COLUMN_SIZE, null)
            row.add(DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.MIME_TYPE_DIR)
            row.add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, System.currentTimeMillis())
            row.add(DocumentsContract.Document.COLUMN_FLAGS, DocumentsContract.Document.FLAG_DIR_PREFERS_LAST_MODIFIED)
        } else {
            includeFile(result, fileFromDocumentId(documentId))
        }
    }

    private fun includeFile(result: MatrixCursor, file: File) {
        if (!file.exists()) return
        val row = result.newRow()
        row.add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, "file:${file.name}")
        row.add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, file.name)
        row.add(DocumentsContract.Document.COLUMN_SIZE, file.length())
        row.add(DocumentsContract.Document.COLUMN_MIME_TYPE, "image/jpeg")
        row.add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, file.lastModified())
        row.add(DocumentsContract.Document.COLUMN_FLAGS, DocumentsContract.Document.FLAG_SUPPORTS_THUMBNAIL)
    }

    private fun fileFromDocumentId(documentId: String): File {
        require(documentId.startsWith("file:")) { "Unsupported documentId: $documentId" }
        return File(rootDir(), documentId.removePrefix("file:"))
    }

    private fun rootDir(): File = context!!.filesDir

    companion object {
        const val AUTHORITY = "com.android.example.cameraxbasic.halfframe.documents"
        const val ROOT_ID = "half_frame_intermediate_root"
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
