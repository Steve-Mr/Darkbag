package top.maary.darkbag.utils

import top.maary.darkbag.fragments.GallerySelectedItem

/**
 * Validates batch media homogeneity for unified grading, filtering, or batch transformations.
 * Ensures selected items share compatible media pipelines (all static photos, all motion photos, or all videos).
 */
object GalleryHomogeneityChecker {

    fun isHomogeneous(selected: List<GallerySelectedItem>): Boolean {
        if (selected.isEmpty()) return false

        val isStaticPhoto: (GallerySelectedItem) -> Boolean = { item ->
            val g = item.group
            !g.isMotionPhoto && !g.isRawVideo && g.rawVideoUri == null && g.mp4VideoUri == null &&
                    !g.isCinemaDng && g.cinemaDngFolderUri == null && g.cinemaDngFrameUris.isEmpty()
        }

        val isMotionPhoto: (GallerySelectedItem) -> Boolean = { item ->
            item.group.isMotionPhoto
        }

        val isVideo: (GallerySelectedItem) -> Boolean = { item ->
            val g = item.group
            g.isRawVideo || g.rawVideoUri != null || g.mp4VideoUri != null ||
                    g.isCinemaDng || g.cinemaDngFolderUri != null || g.cinemaDngFrameUris.isNotEmpty()
        }

        return selected.all(isStaticPhoto) || selected.all(isMotionPhoto) || selected.all(isVideo)
    }
}
