package top.maary.darkbag

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException

class PlaygroundDocumentsProvider : DocumentsProvider() {

    companion object {
        const val AUTHORITY = "top.maary.darkbag.playground.documents"
        private const val ROOT_ID = "playground_root"
        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_SIZE
        )
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
    }

    private lateinit var playgroundDir: File

    override fun onCreate(): Boolean {
        playgroundDir = File(context!!.filesDir, "playground_dngs")
        if (!playgroundDir.exists()) {
            playgroundDir.mkdirs()
        }
        return true
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        val row = result.newRow()
        row.add(DocumentsContract.Root.COLUMN_ROOT_ID, ROOT_ID)
        row.add(DocumentsContract.Root.COLUMN_SUMMARY, "Playground Storage")
        row.add(DocumentsContract.Root.COLUMN_FLAGS, DocumentsContract.Root.FLAG_LOCAL_ONLY or DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD)
        row.add(DocumentsContract.Root.COLUMN_TITLE, "Darkbag Playground")
        row.add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, getDocIdForFile(playgroundDir))
        row.add(DocumentsContract.Root.COLUMN_MIME_TYPES, "*/*")
        row.add(DocumentsContract.Root.COLUMN_AVAILABLE_BYTES, playgroundDir.freeSpace)
        row.add(DocumentsContract.Root.COLUMN_ICON, R.mipmap.ic_launcher) // Or a specific folder icon if available
        return result
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        includeFile(result, getFileForDocId(documentId))
        return result
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val parent = getFileForDocId(parentDocumentId)
        parent.listFiles()?.forEach { file ->
            includeFile(result, file)
        }
        return result
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        val file = getFileForDocId(documentId)
        val accessMode: Int = ParcelFileDescriptor.parseMode(mode)
        return ParcelFileDescriptor.open(file, accessMode)
    }

    private fun getDocIdForFile(file: File): String {
        return if (file.absolutePath == playgroundDir.absolutePath) {
            ROOT_ID
        } else {
            file.name
        }
    }

    private fun getFileForDocId(docId: String): File {
        val target = if (docId == ROOT_ID) {
            playgroundDir
        } else {
            File(playgroundDir, docId)
        }

        val canonicalTarget = target.canonicalPath
        val canonicalPlayground = playgroundDir.canonicalPath
        if (canonicalTarget != canonicalPlayground && !canonicalTarget.startsWith(canonicalPlayground + File.separator)) {
            throw SecurityException("Invalid document ID (path traversal detected): $docId")
        }

        if (!target.exists() && target.absolutePath == playgroundDir.absolutePath) {
            target.mkdirs()
        }
        return target
    }

    private fun includeFile(result: MatrixCursor, file: File) {
        val row = result.newRow()
        row.add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, getDocIdForFile(file))
        row.add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, file.name)
        row.add(DocumentsContract.Document.COLUMN_SIZE, file.length())
        row.add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, file.lastModified())

        var mimeType = DocumentsContract.Document.MIME_TYPE_DIR
        if (file.isDirectory) {
            row.add(DocumentsContract.Document.COLUMN_FLAGS, 0)
        } else {
            mimeType = getMimeType(file)
            var flags = DocumentsContract.Document.FLAG_SUPPORTS_WRITE or DocumentsContract.Document.FLAG_SUPPORTS_DELETE
            if (mimeType.startsWith("image/")) {
                flags = flags or DocumentsContract.Document.FLAG_SUPPORTS_THUMBNAIL
            }
            row.add(DocumentsContract.Document.COLUMN_FLAGS, flags)
        }
        row.add(DocumentsContract.Document.COLUMN_MIME_TYPE, mimeType)
    }

    private fun getMimeType(file: File): String {
        return if (file.extension.lowercase() == "dng") {
            "image/x-adobe-dng"
        } else {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension) ?: "application/octet-stream"
        }
    }

    override fun deleteDocument(documentId: String) {
        if (documentId == ROOT_ID) {
            throw SecurityException("Deletion of the root directory is not allowed.")
        }
        val file = getFileForDocId(documentId)
        if (file.delete()) {
           // Deleted
        } else {
            throw FileNotFoundException("Failed to delete document with id $documentId")
        }
    }
}
