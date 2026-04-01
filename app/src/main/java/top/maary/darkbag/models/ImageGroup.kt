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
    val captureTime: Long = 0L,
    val lastModified: Long = 0L,
    val editConfig: EditConfig? = null,
    val metadataLoaded: Boolean = false,
    val isInProgress: Boolean = false,
    val isPartial: Boolean = false
) : Parcelable {
    fun hasAny(): Boolean = jpgUri != null || dngUri != null || dngUri1 != null || dngUri2 != null

    // 2.5: Only true if it has both DNGs, a stitched JPG, or an explicit layout.
    // If only dngUri1 exists and no jpg, it's NOT a half-frame group (shows as single image).
    fun isHalfFrame(): Boolean = (dngUri1 != null && dngUri2 != null) ||
                                (jpgUri != null && (hfLayout == "SBS" || hfLayout == "TB" || hfLayout == "Side-by-side" || hfLayout == "Top-bottom")) ||
                                (hfLayout == "SBS" || hfLayout == "TB")
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
