package top.maary.darkbag.viewmodels

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import top.maary.darkbag.persistence.ImageEntity
import top.maary.darkbag.persistence.ImageRepository
import java.io.File
import java.io.FileOutputStream

class GalleryViewModel(private val repository: ImageRepository) : ViewModel() {
    val darkbagRecent = repository.darkbagImages.asLiveData()
    val importedRecent = repository.importedImages.asLiveData()

    fun importImage(context: Context, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return@launch
            val fileName = getFileName(context, uri) ?: "imported_${System.currentTimeMillis()}"
            val destFile = File(context.filesDir, "imported/$fileName")
            destFile.parentFile?.mkdirs()

            FileOutputStream(destFile).use { outputStream ->
                inputStream.copyTo(outputStream)
            }

            val entity = ImageEntity(
                id = destFile.absolutePath,
                path = destFile.absolutePath,
                isImported = true,
                dateAdded = System.currentTimeMillis(),
                isRaw = fileName.lowercase().endsWith(".dng") ||
                        fileName.lowercase().endsWith(".raw") ||
                        fileName.lowercase().endsWith(".cr2") // Basic check
            )
            repository.insert(entity)
        }
    }

    private fun getFileName(context: Context, uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index != -1) {
                        result = it.getString(index)
                    }
                }
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/') ?: -1
            if (cut != -1) {
                result = result?.substring(cut + 1)
            }
        }
        return result
    }
}

class GalleryViewModelFactory(private val repository: ImageRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(GalleryViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return GalleryViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
