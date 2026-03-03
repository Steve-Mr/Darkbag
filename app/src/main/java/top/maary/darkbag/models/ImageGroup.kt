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
    val exposure: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val whites: Float = 0f,
    val blacks: Float = 0f
) : Parcelable
