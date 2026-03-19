package top.maary.darkbag.models

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class CaptureMetadata(
    val iso: Int? = null,
    val exposureTime: Long? = null, // in nanoseconds
    val fNumber: Float? = null,
    val focalLength: Float? = null,
    val dateTimeOriginal: Long? = null,
    val make: String? = null,
    val model: String? = null
) : Parcelable
