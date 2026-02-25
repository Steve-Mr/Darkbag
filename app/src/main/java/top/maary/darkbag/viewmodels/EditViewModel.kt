package top.maary.darkbag.viewmodels

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.*
import kotlinx.coroutines.Dispatchers
import top.maary.darkbag.MainApplication
import top.maary.darkbag.fragments.SettingsFragment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.maary.darkbag.persistence.ImageEntity
import top.maary.darkbag.persistence.ImageRepository
import top.maary.darkbag.processor.ColorProcessor
import java.io.File
import java.io.FileInputStream

class EditViewModel(private val repository: ImageRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(EditUiState())
    val uiState = _uiState.asStateFlow()

    data class EditUiState(
        val image: ImageEntity? = null,
        val selectedIndex: Int = 0,
        val previewBitmap1: Bitmap? = null,
        val previewBitmap2: Bitmap? = null,
        val isProcessing: Boolean = false
    )

    fun setImage(image: ImageEntity) {
        _uiState.value = _uiState.value.copy(image = image)
        refreshPreview(0)
        if (image.isStitched) {
            refreshPreview(1)
        }
    }

    fun initStitch(image1: ImageEntity, image2: ImageEntity) {
        val stitchEntity = ImageEntity(
            id = "stitch_${System.currentTimeMillis()}",
            path = image1.path,
            isImported = false,
            dateAdded = System.currentTimeMillis(),
            isRaw = image1.isRaw && image2.isRaw,
            isStitched = true,
            secondPath = image2.path,
            layout = "Side-by-side"
        )
        _uiState.value = _uiState.value.copy(image = stitchEntity)
        refreshPreview(0)
        refreshPreview(1)
    }

    fun updateAdjustment(type: String, value: Float) {
        val current = _uiState.value.image ?: return
        val updated = if (_uiState.value.selectedIndex == 0) {
            when (type) {
                "exposure" -> current.copy(exposure = value)
                "contrast" -> current.copy(contrast = value)
                "highlights" -> current.copy(highlights = value)
                "shadows" -> current.copy(shadows = value)
                "whites" -> current.copy(whites = value)
                "blacks" -> current.copy(blacks = value)
                "saturation" -> current.copy(saturation = value)
                "log" -> current.copy(targetLog = value.toInt())
                else -> current
            }
        } else {
            when (type) {
                "exposure" -> current.copy(exposure2 = value)
                "contrast" -> current.copy(contrast2 = value)
                "highlights" -> current.copy(highlights2 = value)
                "shadows" -> current.copy(shadows2 = value)
                "whites" -> current.copy(whites2 = value)
                "blacks" -> current.copy(blacks2 = value)
                "saturation" -> current.copy(saturation2 = value)
                else -> current
            }
        }
        _uiState.value = _uiState.value.copy(image = updated)
        refreshPreview(_uiState.value.selectedIndex)
    }

    fun toggleLayout() {
        val current = _uiState.value.image ?: return
        val nextLayout = if (current.layout == "Side-by-side") "Top-bottom" else "Side-by-side"
        val updated = current.copy(layout = nextLayout)
        _uiState.value = _uiState.value.copy(image = updated)
    }

    fun toggleEffect(type: String) {
        val current = _uiState.value.image ?: return
        val updated = when (type) {
            "dateStamp" -> current.copy(dateStamp = !current.dateStamp)
            "lightLeak" -> current.copy(lightLeak = !current.lightLeak)
            else -> current
        }
        _uiState.value = _uiState.value.copy(image = updated)
    }

    fun setLut(path: String?) {
        val current = _uiState.value.image ?: return
        val updated = current.copy(lutPath = path)
        _uiState.value = _uiState.value.copy(image = updated)
        refreshPreview(0)
        if (updated.isStitched) refreshPreview(1)
    }

    fun setLog(logIndex: Int) {
        val current = _uiState.value.image ?: return
        val updated = current.copy(targetLog = logIndex)
        _uiState.value = _uiState.value.copy(image = updated)
        refreshPreview(0)
        if (updated.isStitched) refreshPreview(1)
    }

    fun setSelectedIndex(index: Int) {
        _uiState.value = _uiState.value.copy(selectedIndex = index)
    }

    private fun refreshPreview(index: Int) {
        val image = _uiState.value.image ?: return

        val prefs = MainApplication.INSTANCE.getSharedPreferences(SettingsFragment.PREFS_NAME, android.content.Context.MODE_PRIVATE)
        val lowRes = prefs.getBoolean(SettingsFragment.KEY_LOW_RES_PREVIEW, false)
        val downsample = if (lowRes) 16 else 4

        viewModelScope.launch(Dispatchers.IO) {
            val path = if (index == 0) image.path else image.secondPath ?: return@launch

            if (!image.isRaw) {
                // Just load the bitmap directly
                val bitmap = com.bumptech.glide.Glide.with(top.maary.darkbag.MainApplication.INSTANCE)
                    .asBitmap()
                    .load(path)
                    .submit()
                    .get()
                withContext(Dispatchers.Main) {
                    if (index == 0) _uiState.value = _uiState.value.copy(previewBitmap1 = bitmap)
                    else _uiState.value = _uiState.value.copy(previewBitmap2 = bitmap)
                }
                return@launch
            }

            val file = File(path)
            if (!file.exists()) return@launch

            val dngData = FileInputStream(file).use { it.readBytes() }

            val adj = if (index == 0) {
                listOf(image.exposure, image.contrast, image.highlights, image.shadows, image.whites, image.blacks, image.saturation)
            } else {
                listOf(image.exposure2, image.contrast2, image.highlights2, image.shadows2, image.whites2, image.blacks2, image.saturation2)
            }
            val exp = adj[0]
            val con = adj[1]
            val hig = adj[2]
            val sha = adj[3]
            val whi = adj[4]
            val bla = adj[5]
            val sat = adj[6]

            val bitmap = ColorProcessor.processRawToBitmap(
                dngData, image.targetLog, image.lutPath,
                0, false, // Orientation/Mirror handled by UI or later
                exp, con, hig, sha, whi, bla, sat,
                downsample
            )

            withContext(Dispatchers.Main) {
                if (index == 0) {
                    _uiState.value = _uiState.value.copy(previewBitmap1 = bitmap)
                } else {
                    _uiState.value = _uiState.value.copy(previewBitmap2 = bitmap)
                }
            }
        }
    }

    fun saveChanges() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value.image?.let {
                if (repository.getImageById(it.id) != null) {
                    repository.update(it)
                } else {
                    repository.insert(it)
                }
            }
        }
    }

    fun export(context: android.content.Context, isSaveAs: Boolean) {
        val image = _uiState.value.image ?: return
        _uiState.value = _uiState.value.copy(isProcessing = true)

        viewModelScope.launch(Dispatchers.IO) {
            top.maary.darkbag.utils.ImageExporter.exportImage(context, image, isSaveAs)

            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(isProcessing = false)
            }
        }
    }
}

class EditViewModelFactory(private val repository: ImageRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(EditViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return EditViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
