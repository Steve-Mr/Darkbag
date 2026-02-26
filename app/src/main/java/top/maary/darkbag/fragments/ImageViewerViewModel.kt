package top.maary.darkbag.fragments

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
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

    val currentImage = MutableLiveData<DarkbagImage?>()
    val currentMetadata = MutableLiveData<DarkbagMetadata>()
    val previewBitmap = MutableLiveData<Bitmap?>()
    val isModified = MutableLiveData<Boolean>(false)

    private var originalMetadata: DarkbagMetadata? = null
    private var dngData: ByteArray? = null
    private var previewJob: Job? = null

    fun setImage(image: DarkbagImage) {
        if (currentImage.value?.primaryUri == image.primaryUri) return

        currentImage.value = image
        val meta = image.metadata ?: DarkbagMetadata()
        currentMetadata.value = meta
        originalMetadata = meta
        isModified.value = false
        previewBitmap.value = null

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

    fun updateMetadata(newMeta: DarkbagMetadata) {
        currentMetadata.value = newMeta
        isModified.value = newMeta != originalMetadata
        generatePreview(debounce = true)
    }

    private fun generatePreview(debounce: Boolean) {
        val bytes = dngData ?: return
        val meta = currentMetadata.value ?: return
        val image = currentImage.value ?: return

        previewJob?.cancel()
        previewJob = viewModelScope.launch(Dispatchers.Default) {
            if (debounce) delay(100)

            // Create a bitmap for preview. We'll use a size that fits common screens.
            // Orientation handling: we'll use 0 for preview if we just want to see adjustments
            // but for correct aspect ratio in UI, we might need more.
            val preview = Bitmap.createBitmap(1024, 1024, Bitmap.Config.ARGB_8888)

            val lutDir = File(getApplication<Application>().filesDir, "luts")
            val lutPath = meta.lutName?.let { File(lutDir, it).absolutePath }

            val res = ColorProcessor.processRawToBitmap(
                dngData = bytes,
                targetLog = meta.logIndex,
                lutPath = lutPath,
                outputBitmap = preview,
                orientation = 0,
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
                previewBitmap.postValue(preview)
            }
        }
    }

    fun resetMetadata() {
        originalMetadata?.let { updateMetadata(it) } ?: updateMetadata(DarkbagMetadata())
    }

    fun saveImage(overwrite: Boolean, onComplete: (Uri?) -> Unit) {
        val bytes = dngData ?: return
        val meta = currentMetadata.value ?: return
        val image = currentImage.value ?: return

        viewModelScope.launch(Dispatchers.IO) {
            val lutDir = File(getApplication<Application>().filesDir, "luts")
            val lutPath = meta.lutName?.let { File(lutDir, it).absolutePath }

            // Generate a temporary path for re-processing
            val tempJpg = File(getApplication<Application>().cacheDir, "reproc_${System.currentTimeMillis()}.jpg")

            val res = ColorProcessor.processRaw(
                dngData = bytes,
                targetLog = meta.logIndex,
                lutPath = lutPath,
                outputTiffPath = null,
                outputJpgPath = tempJpg.absolutePath,
                useGpu = false,
                orientation = 0,
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
