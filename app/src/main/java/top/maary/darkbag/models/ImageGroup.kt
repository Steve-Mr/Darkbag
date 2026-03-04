package top.maary.darkbag.models

import android.net.Uri
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class ImageGroup(
    val baseName: String,
    val jpgUri: Uri? = null,
    val tiffUri: Uri? = null,
    val dngUri: Uri? = null, // Legacy / Standard
    val dngUri1: Uri? = null, // Half-frame Frame 1
    val dngUri2: Uri? = null, // Half-frame Frame 2
    val hfLayout: String? = null, // "SBS" or "TB"
    val width: Int = 0,
    val height: Int = 0,
    val captureTime: Long = 0L,
    val editConfig: EditConfig? = null
) : Parcelable {
    fun hasAny(): Boolean = jpgUri != null || tiffUri != null || dngUri != null || dngUri1 != null || dngUri2 != null
    fun isHalfFrame(): Boolean = dngUri1 != null || dngUri2 != null || hfLayout != null
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
    // Half-frame specific
    val adjustments: List<BasicAdjustments>? = null, // Index 0 for Frame 1, Index 1 for Frame 2
    val showTimestamp: Boolean = false,
    val flareType: Int = -1 // -1: None, 0: Random, 1: Vertical, 2: Corner
) : Parcelable

@Parcelize
data class BasicAdjustments(
    val exposure: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val whites: Float = 0f,
    val blacks: Float = 0f
) : Parcelable
