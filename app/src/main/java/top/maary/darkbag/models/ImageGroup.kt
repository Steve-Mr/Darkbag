package top.maary.darkbag.models

import android.net.Uri
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class ImageGroup(
    val baseName: String,
    val jpgUri: Uri? = null,
    val tiffUri: Uri? = null,
    val dngUri: Uri? = null,
    val captureTime: Long = 0L
) : Parcelable {
    fun hasAny(): Boolean = jpgUri != null || tiffUri != null || dngUri != null
}
