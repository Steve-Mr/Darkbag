package top.maary.darkbag.image

import android.net.Uri
import top.maary.darkbag.utils.DarkbagMetadata

data class DarkbagImageRequest(
    val uri: Uri,
    val dngUri: Uri?,
    val metadata: DarkbagMetadata,
    val isRawMode: Boolean = false
)
