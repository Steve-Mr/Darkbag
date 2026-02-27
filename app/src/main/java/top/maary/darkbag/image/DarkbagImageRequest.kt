package top.maary.darkbag.image

import android.net.Uri
import top.maary.darkbag.utils.DarkbagMetadata

data class DarkbagImageRequest(
    val uri: Uri,
    val dngUri: Uri?,
    val metadata: DarkbagMetadata,
    val isRawMode: Boolean = false,
    val quality: Int = 0,
    val forceRaw: Boolean = false,
    val isModified: Boolean = false,
    val isThumbnail: Boolean = false
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DarkbagImageRequest) return false

        if (uri != other.uri) return false

        val thisNeutral = !isRawMode && !isModified && !forceRaw
        val otherNeutral = !other.isRawMode && !other.isModified && !other.forceRaw

        if (thisNeutral && otherNeutral) {
            return true
        }

        if (dngUri != other.dngUri) return false
        if (isRawMode != other.isRawMode) return false
        if (forceRaw != other.forceRaw) return false
        if (isModified != other.isModified) return false
        if (metadata != other.metadata) return false
        if (quality != other.quality) return false
        if (isThumbnail != other.isThumbnail) return false

        return true
    }

    override fun hashCode(): Int {
        var result = uri.hashCode()
        val isNeutral = !isRawMode && !isModified && !forceRaw
        if (isNeutral) return result

        result = 31 * result + (dngUri?.hashCode() ?: 0)
        result = 31 * result + isRawMode.hashCode()
        result = 31 * result + forceRaw.hashCode()
        result = 31 * result + isModified.hashCode()
        result = 31 * result + metadata.hashCode()
        result = 31 * result + quality
        result = 31 * result + isThumbnail.hashCode()
        return result
    }
}
