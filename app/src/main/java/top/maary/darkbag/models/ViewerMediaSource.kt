package top.maary.darkbag.models

import android.net.Uri

sealed interface ViewerMediaSource {
    data class MediaStoreSource(val initialUri: Uri?) : ViewerMediaSource
    data class SandboxSource(val dngPaths: List<String>, val hfLayout: String? = null) : ViewerMediaSource
}
