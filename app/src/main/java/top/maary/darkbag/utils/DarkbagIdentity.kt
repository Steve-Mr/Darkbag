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

    fun isDarkbagUri(uri: android.net.Uri, cr: android.content.ContentResolver): Boolean {
        return try {
            val name = when (uri.scheme) {
                "content" -> {
                    cr.query(uri, arrayOf(android.provider.MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    }
                }
                else -> uri.lastPathSegment
            }
            name != null && (name.startsWith(FILE_PREFIX, ignoreCase = true) ||
                    name.startsWith("Darkbag_", ignoreCase = true) ||
                    name.startsWith("RAWVID_", ignoreCase = true) ||
                    name.startsWith("CDNG_", ignoreCase = true))
        } catch (e: Exception) {
            false
        }
    }
}
