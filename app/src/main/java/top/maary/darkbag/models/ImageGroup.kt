package top.maary.darkbag.models

import android.net.Uri
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class ImageGroup(
    val baseName: String,
    val jpgUri: Uri? = null,
    val dngUri: Uri? = null, // Legacy / Standard
    val dngUri1: Uri? = null, // Half-frame Frame 1
    val dngUri2: Uri? = null, // Half-frame Frame 2
    val hfLayout: String? = null, // "SBS" or "TB"
    val width: Int = 0,
    val height: Int = 0,
    val orientation: Int = 0,
    val captureTime: Long = 0L,
    val lastModified: Long = 0L,
    val editConfig: EditConfig? = null,
    val metadataLoaded: Boolean = false,
    val isInProgress: Boolean = false,
    val isPartial: Boolean = false,
    val isMotionPhoto: Boolean = false,
    val motionPhotoPtsUs: Long = 0L,
    val motionPhotoVideoLength: Long = 0L,
    val isMultiCamera: Boolean = false,
    val multiJpgUris: List<Uri> = emptyList(),
    val multiDngUris: List<Uri> = emptyList(),
    val multiCameraLenses: List<MultiCameraLensItem> = emptyList(),
    val rawVideoUri: Uri? = null,
    val isRawVideo: Boolean = false,
    val rawVideoFps: Float = 24.0f,
    val rawVideoFrameCount: Int = 0,
    val rawVideoDurationMs: Long = 0L,
    val mp4VideoUri: Uri? = null,
    val isMp4Video: Boolean = false,
    val derivativeJpgUris: List<Uri> = emptyList(),
    val derivativeMp4Uris: List<Uri> = emptyList()
) : Parcelable {
    fun hasAny(): Boolean = jpgUri != null || dngUri != null || dngUri1 != null || dngUri2 != null || rawVideoUri != null || mp4VideoUri != null || multiJpgUris.isNotEmpty() || multiDngUris.isNotEmpty() || multiCameraLenses.isNotEmpty() || derivativeJpgUris.isNotEmpty() || derivativeMp4Uris.isNotEmpty()

    val allDerivativeUris: List<Uri>
        get() {
            val list = mutableListOf<Uri>()
            jpgUri?.let { list.add(it) }
            for (uri in derivativeJpgUris) {
                if (uri !in list) list.add(uri)
            }
            mp4VideoUri?.let { list.add(it) }
            for (uri in derivativeMp4Uris) {
                if (uri !in list) list.add(uri)
            }
            for (uri in multiJpgUris) {
                if (uri !in list) list.add(uri)
            }
            return list
        }

    val allMasterRawUris: List<Uri>
        get() {
            val list = mutableListOf<Uri>()
            dngUri?.let { list.add(it) }
            dngUri1?.let { list.add(it) }
            dngUri2?.let { list.add(it) }
            rawVideoUri?.let { list.add(it) }
            for (uri in multiDngUris) {
                if (uri !in list) list.add(uri)
            }
            return list
        }

    val allUris: List<Uri>
        get() {
            val list = mutableListOf<Uri>()
            for (uri in allMasterRawUris) {
                if (uri !in list) list.add(uri)
            }
            for (uri in allDerivativeUris) {
                if (uri !in list) list.add(uri)
            }
            return list
        }

    val firstAvailableUri: Uri?
        get() = allDerivativeUris.firstOrNull() ?: allMasterRawUris.firstOrNull()

    val hasMasterRaw: Boolean
        get() = dngUri != null || dngUri1 != null || dngUri2 != null || rawVideoUri != null || multiDngUris.isNotEmpty()

    val hasDerivatives: Boolean
        get() = allDerivativeUris.isNotEmpty()

    val hasMultipleDerivatives: Boolean
        get() = allDerivativeUris.size >= 2

    // 2.5: Only true if it has both DNGs, a stitched JPG, or an explicit layout.
    // If only dngUri1 exists and no jpg, it's NOT a half-frame group (shows as single image).
    fun isHalfFrame(): Boolean = (dngUri1 != null && dngUri2 != null) ||
                                (jpgUri != null && (hfLayout == "SBS" || hfLayout == "TB" || hfLayout == "Side-by-side" || hfLayout == "Top-bottom")) ||
                                (hfLayout == "SBS" || hfLayout == "TB")

    fun isSingleFormat(): Boolean {
        val hasRaw = hasMasterRaw
        val derivCount = allDerivativeUris.size
        return (hasRaw && derivCount == 0) || (!hasRaw && derivCount <= 1)
    }
}

@Parcelize
data class EditConfig(
    val log: String? = "None",
    val lut: String? = "None",
    // Standard or Global adjustments
    val exposure: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val whites: Float = 0f,
    val blacks: Float = 0f,
    val digitalGain: Float = 1.0f,
    // Half-frame specific
    val adjustments: List<BasicAdjustments>? = null, // Index 0 for Frame 1, Index 1 for Frame 2
    val showTimestamp: Boolean = false,
    val flareType: Int = -1, // -1: None, 0: Random, 1: Vertical, 2: Corner
    val hfLayout: String? = null, // "SBS" or "TB"
    val isSwapped: Boolean = false,
    val zoomFactor: Float = 1.0f
) : Parcelable

@Parcelize
data class BasicAdjustments(
    val exposure: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val whites: Float = 0f,
    val blacks: Float = 0f,
    val digitalGain: Float = 1.0f
) : Parcelable

@Parcelize
data class MultiCameraLensItem(
    val lensTag: String,
    val multiplier: Float,
    val jpgUri: Uri? = null,
    val dngUri: Uri? = null
) : Parcelable
