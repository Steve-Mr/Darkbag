package top.maary.darkbag.models

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class CaptureMetadata(
    val iso: Int? = null,
    val exposureTime: Long? = null, // in nanoseconds
    val fNumber: Float? = null,
    val focalLength: Float? = null,
    val focalLengthIn35mmFilm: Int? = null,
    val dateTimeOriginal: Long? = null,
    val dateTimeDigitized: Long? = null,
    val offsetTime: String? = null,
    val offsetTimeOriginal: String? = null,
    val offsetTimeDigitized: String? = null,
    val make: String? = null,
    val model: String? = null,
    val uniqueCameraModel: String? = null,
    val lensModel: String? = null,
    val software: String? = null,
    val imageDescription: String? = null
) : Parcelable
