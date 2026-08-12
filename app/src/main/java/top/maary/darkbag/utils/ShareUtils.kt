package top.maary.darkbag.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Parcelable
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

object ShareUtils {

    private const val TAG = "ShareUtils"

    suspend fun processShareIntent(context: Context, intent: Intent): List<String> = withContext(Dispatchers.IO) {
        val dngPaths = mutableListOf<String>()
        val urisToProcess = LinkedHashSet<Uri>()

        // 1. Extract Uris from ClipData
        intent.clipData?.let { clipData ->
            for (i in 0 until clipData.itemCount) {
                clipData.getItemAt(i)?.uri?.let { urisToProcess.add(it) }
            }
        }

        // 2. Extract Uris from EXTRA_STREAM
        if (intent.action == Intent.ACTION_SEND) {
            val uri = intent.getParcelableExtra<Parcelable>(Intent.EXTRA_STREAM) as? Uri
            if (uri != null) {
                urisToProcess.add(uri)
            }
        } else if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            val uris = intent.getParcelableArrayListExtra<Parcelable>(Intent.EXTRA_STREAM)
            uris?.forEach {
                if (it is Uri) urisToProcess.add(it)
            }
        }

        // Also check intent.data
        intent.data?.let { urisToProcess.add(it) }

        var invalidFormatCount = 0
        var ioErrorCount = 0

        for (uri in urisToProcess) {
            val fileName = getFileName(context, uri)
            val isDng = isDngFile(context, uri, fileName)
            if (isDng) {
                val copiedPath = copyFileToPlayground(context, uri, fileName ?: "imported_${java.util.UUID.randomUUID()}.dng")
                if (copiedPath != null) {
                    dngPaths.add(copiedPath)
                } else {
                    ioErrorCount++
                }
            } else {
                invalidFormatCount++
            }
        }

        withContext(Dispatchers.Main) {
            val message = when {
                invalidFormatCount > 0 && ioErrorCount > 0 ->
                    "$invalidFormatCount non-DNG file(s) ignored, $ioErrorCount file(s) failed to read."
                invalidFormatCount > 0 ->
                    "$invalidFormatCount file(s) ignored. Only DNG format is supported."
                ioErrorCount > 0 ->
                    "Failed to import $ioErrorCount file(s) due to read/permission errors."
                else -> null
            }

            if (message != null) {
                val view = (context as? android.app.Activity)?.findViewById<android.view.View>(android.R.id.content)
                if (view != null) {
                    Snackbar.make(view, message, Snackbar.LENGTH_LONG).show()
                } else {
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                }
            }
        }

        return@withContext dngPaths
    }

    private fun isDngFile(context: Context, uri: Uri, fileName: String?): Boolean {
        // 1. Check filename extension
        if (fileName != null && fileName.lowercase().endsWith(".dng")) {
            return true
        }

        // 2. Check MIME type
        val mimeType = context.contentResolver.getType(uri)
        if (mimeType != null) {
            val mimeLower = mimeType.lowercase()
            if (mimeLower.contains("dng") || mimeLower.contains("x-adobe-dng") || mimeLower.contains("x-raw")) {
                return true
            }
        }

        // 3. Inspect TIFF/DNG magic header bytes (0x4949 0x2A00 or 0x4D4D 0x002A)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val header = ByteArray(4)
                val bytesRead = input.read(header, 0, 4)
                if (bytesRead == 4) {
                    // Little-endian TIFF ('II' + 42): 0x49, 0x49, 0x2A, 0x00
                    val isLittleEndian = (header[0] == 'I'.code.toByte() && header[1] == 'I'.code.toByte() &&
                            header[2] == 0x2A.toByte() && header[3] == 0x00.toByte())
                    // Big-endian TIFF ('MM' + 42): 0x4D, 0x4D, 0x00, 0x2A
                    val isBigEndian = (header[0] == 'M'.code.toByte() && header[1] == 'M'.code.toByte() &&
                            header[2] == 0x00.toByte() && header[3] == 0x2A.toByte())

                    if (isLittleEndian || isBigEndian) {
                        return true
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read header for URI: $uri", e)
        }

        return false
    }

    private fun getFileName(context: Context, uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index >= 0) {
                            result = cursor.getString(index)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to query display name for URI: $uri", e)
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/')
            if (cut != null && cut != -1) {
                result = result?.substring(cut + 1)
            }
        }
        return result
    }

    private fun copyFileToPlayground(context: Context, uri: Uri, originalFileName: String): String? {
        try {
            val playgroundDir = File(context.filesDir, "playground_dngs")
            if (!playgroundDir.exists()) {
                playgroundDir.mkdirs()
            }

            var baseFileName = File(originalFileName).name.ifEmpty { "imported_${java.util.UUID.randomUUID()}.dng" }
            if (!baseFileName.lowercase().endsWith(".dng")) {
                baseFileName = "$baseFileName.dng"
            }

            var destFile = File(playgroundDir, baseFileName)
            var counter = 1
            val baseName = destFile.nameWithoutExtension
            while (destFile.exists()) {
                destFile = File(playgroundDir, "${baseName}_$counter.dng")
                counter++
            }

            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            return destFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Error copying URI $uri to playground", e)
            return null
        }
    }
}

