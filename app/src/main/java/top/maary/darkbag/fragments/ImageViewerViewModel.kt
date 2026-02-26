package top.maary.darkbag.fragments

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.maary.darkbag.processor.ColorProcessor
import top.maary.darkbag.utils.DarkbagImage
import top.maary.darkbag.utils.DarkbagMetadata
import java.io.File

class ImageViewerViewModel(application: Application) : AndroidViewModel(application) {

    data class PreviewState(
        val baseName: String,
        val bitmap: Bitmap?
    )

    val currentImage = MutableLiveData<DarkbagImage?>()
    val currentMetadata = MutableLiveData<DarkbagMetadata>()
    val selectedFormat = MutableLiveData<String>()
    val previewState = MutableLiveData<PreviewState>()
    val isModified = MutableLiveData<Boolean>(false)

    private var originalMetadata: DarkbagMetadata? = null
    private var dngData: ByteArray? = null
    private var previewJob: Job? = null

    fun setImage(image: DarkbagImage) {
        if (currentImage.value?.baseName == image.baseName) return

        currentImage.value = image
        selectedFormat.value = image.type
        val meta = image.metadata ?: DarkbagMetadata()
        currentMetadata.value = meta
        originalMetadata = meta
        isModified.value = false
        previewState.value = PreviewState(image.baseName, null)
        dngData = null

        // Load DNG data if available
        image.allUris["DNG"]?.let { dngUri ->
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val bytes = getApplication<Application>().contentResolver.openInputStream(dngUri)?.use { it.readBytes() }
                    dngData = bytes
                    if (bytes != null) {
                        generatePreview(debounce = false)
                    }
                } catch (e: Exception) {
                    dngData = null
                }
            }
        } ?: run {
            dngData = null
        }
    }

    fun updateMetadata(updater: (DarkbagMetadata) -> DarkbagMetadata) {
        val current = currentMetadata.value ?: DarkbagMetadata()
        val next = updater(current)
        if (current == next) return

        currentMetadata.value = next
        isModified.value = next != originalMetadata
        generatePreview(debounce = true)
    }

    private fun generatePreview(debounce: Boolean) {
        val bytes = dngData ?: return
        val meta = currentMetadata.value ?: return
        val image = currentImage.value ?: return
        val baseNameAtStart = image.baseName

        previewJob?.cancel()
        previewJob = viewModelScope.launch(Dispatchers.Default) {
            if (debounce) delay(100)

            val dngUri = image.allUris["DNG"] ?: return@launch
            var width = 1024
            var height = 1024
            var orientation = 0

            try {
                getApplication<Application>().contentResolver.openInputStream(dngUri)?.use { inputStream ->
                    val exif = ExifInterface(inputStream)
                    width = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 1024)
                    height = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 1024)
                    orientation = exif.rotationDegrees
                }
            } catch (e: Exception) { }

            // Calculate aspect ratio correct bitmap
            val maxSide = 1024f
            val scale = if (width > height) maxSide / width else maxSide / height
            val targetW = (width * scale).toInt()
            val targetH = (height * scale).toInt()

            val preview = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)

            val lutDir = File(getApplication<Application>().filesDir, "luts")
            val lutPath = meta.lutName?.let {
                if (it.contains("..") || it.startsWith("/")) null
                else File(lutDir, it).absolutePath
            }

            val res = ColorProcessor.processRawToBitmap(
                dngData = bytes,
                targetLog = meta.logIndex,
                lutPath = lutPath,
                outputBitmap = preview,
                orientation = orientation,
                mirror = false,
                exposure = meta.exposure,
                highlights = meta.highlights,
                shadows = meta.shadows,
                whites = meta.whites,
                blacks = meta.blacks,
                contrast = meta.contrast,
                saturation = meta.saturation
            )
            if (res == 0) {
                if (currentImage.value?.baseName == baseNameAtStart) {
                    previewState.postValue(PreviewState(image.baseName, preview))
                }
            }
        }
    }

    fun setFormat(format: String) {
        selectedFormat.value = format
    }

    fun resetMetadata() {
        originalMetadata?.let { meta -> updateMetadata { meta } } ?: updateMetadata { DarkbagMetadata() }
    }

    fun saveImage(overwrite: Boolean, onComplete: (Uri?) -> Unit) {
        val bytes = dngData ?: return
        val meta = currentMetadata.value ?: return
        val image = currentImage.value ?: return

        viewModelScope.launch(Dispatchers.IO) {
            val dngUri = image.allUris["DNG"]
            val orientation = dngUri?.let { uri ->
                try {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use {
                        ExifInterface(it).rotationDegrees
                    }
                } catch (e: Exception) { 0 }
            } ?: 0

            val lutDir = File(getApplication<Application>().filesDir, "luts")
            val lutPath = meta.lutName?.let {
                if (it.contains("..") || it.startsWith("/")) null
                else File(lutDir, it).absolutePath
            }

            // Generate a temporary path for re-processing
            val tempJpg = File(getApplication<Application>().cacheDir, "reproc_${System.currentTimeMillis()}.jpg")

            val res = ColorProcessor.processRaw(
                dngData = bytes,
                targetLog = meta.logIndex,
                lutPath = lutPath,
                outputTiffPath = null,
                outputJpgPath = tempJpg.absolutePath,
                useGpu = false,
                orientation = orientation,
                mirror = false,
                exposure = meta.exposure,
                highlights = meta.highlights,
                shadows = meta.shadows,
                whites = meta.whites,
                blacks = meta.blacks,
                contrast = meta.contrast,
                saturation = meta.saturation
            )

            if (res == 0) {
                val targetUri = if (overwrite) image.primaryUri else null
                val baseName = if (overwrite) image.baseName else "${image.baseName}_edit"

                val finalUri = top.maary.darkbag.utils.ImageSaver.saveProcessedImage(
                    context = getApplication(),
                    inputBitmap = null,
                    bmpPath = tempJpg.absolutePath,
                    rotationDegrees = 0,
                    zoomFactor = 1.0f,
                    baseName = baseName,
                    linearDngPath = null,
                    tiffPath = null,
                    saveJpg = true,
                    saveTiff = false,
                    saveRaw = false, // Don't save RAW again
                    targetUri = targetUri,
                    metadata = meta.toJson()
                )
                withContext(Dispatchers.Main) {
                    onComplete(finalUri)
                }
            } else {
                withContext(Dispatchers.Main) {
                    onComplete(null)
                }
            }
        }
    }
}
