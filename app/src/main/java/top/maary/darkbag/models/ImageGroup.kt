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
    val captureTime: Long = 0L
) : Parcelable {
    fun hasAny(): Boolean = jpgUri != null || tiffUri != null || dngUri != null || dngUri1 != null || dngUri2 != null
    fun isHalfFrame(): Boolean = dngUri1 != null || dngUri2 != null || hfLayout != null
}
