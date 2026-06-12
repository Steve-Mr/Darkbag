package top.maary.darkbag.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Parcelable
import android.provider.OpenableColumns
import android.widget.Toast
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

object ShareUtils {

    suspend fun processShareIntent(context: Context, intent: Intent): List<String> = withContext(Dispatchers.IO) {
        val dngPaths = mutableListOf<String>()
        val urisToProcess = mutableListOf<Uri>()

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

        var invalidFilesCount = 0

        for (uri in urisToProcess) {
            val fileName = getFileName(context, uri)
            if (fileName != null && fileName.lowercase().endsWith(".dng")) {
                val copiedPath = copyFileToPlayground(context, uri, fileName)
                if (copiedPath != null) {
                    dngPaths.add(copiedPath)
                } else {
                    invalidFilesCount++
                }
            } else {
                invalidFilesCount++
            }
        }

        withContext(Dispatchers.Main) {
            if (invalidFilesCount > 0) {

                val view = (context as? android.app.Activity)?.findViewById<android.view.View>(android.R.id.content)
                if (view != null) {
                    com.google.android.material.snackbar.Snackbar.make(view, "$invalidFilesCount file(s) ignored. Only DNG format is supported.", com.google.android.material.snackbar.Snackbar.LENGTH_LONG).show()
                } else {
                    Toast.makeText(context, "$invalidFilesCount file(s) ignored. Only DNG format is supported.", Toast.LENGTH_LONG).show()
                }

            }
        }

        return@withContext dngPaths
    }

    private fun getFileName(context: Context, uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) {
                        result = cursor.getString(index)
                    }
                }
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

            val sanitizedFileName = File(originalFileName).name
            var destFile = File(playgroundDir, sanitizedFileName)
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
            e.printStackTrace()
            return null
        }
    }
}
