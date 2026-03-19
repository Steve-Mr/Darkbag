package top.maary.darkbag.utils

import android.os.Build
import top.maary.darkbag.BuildConfig

object DarkbagIdentity {
    const val FILE_PREFIX = "DBAG_"

    fun prefixedBaseName(baseName: String): String =
        if (baseName.startsWith(FILE_PREFIX)) baseName else FILE_PREFIX + baseName

    fun normalizedManufacturer(): String = Build.MANUFACTURER?.takeIf { it.isNotBlank() } ?: "Unknown"

    fun normalizedModel(): String = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Unknown"

    fun uniqueCameraModel(cameraId: String? = null): String {
        val base = "${normalizedManufacturer()} ${normalizedModel()}"
        return if (cameraId.isNullOrBlank()) base else "$base [$cameraId]"
    }

    fun softwareString(isHdrPlus: Boolean): String = if (isHdrPlus) {
        "Darkbag HDR+ ${BuildConfig.VERSION_NAME} (${BuildConfig.HDRPLUS_GIT_SHA_SHORT})"
    } else {
        "Darkbag ${BuildConfig.VERSION_NAME}"
    }

    fun imageDescription(isHdrPlus: Boolean): String = if (isHdrPlus) {
        "Processed by Darkbag HDR+"
    } else {
        "Captured with Darkbag"
    }
}
