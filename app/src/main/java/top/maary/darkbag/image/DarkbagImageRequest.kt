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
)
