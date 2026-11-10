package top.maary.darkbag.utils

/**
 * Shared layout cycle state and logic for gallery grids.
 * Coordinates 3-column / 2-column / 1-column square grids and waterfall aspect-ratio display.
 */
data class GalleryLayoutState(
    val columnCount: Int,
    val isWaterfallMode: Boolean
) {
    companion object {
        fun defaultForOrientation(isLandscape: Boolean): GalleryLayoutState {
            return GalleryLayoutState(
                columnCount = if (isLandscape) 4 else 3,
                isWaterfallMode = false
            )
        }
    }

    /**
     * Cycles to the next layout state.
     * Normal sequence: 3 Cols (Sq) -> 2 Cols (Sq) -> 1 Col (Sq) -> Waterfall (2 Cols) -> 3 Cols (Sq).
     * Landscape sequence: 4 Cols (Sq) -> 3 Cols (Sq) -> 2 Cols (Sq) -> Waterfall (3 Cols) -> 4 Cols (Sq).
     */
    fun cycle(isLandscape: Boolean): GalleryLayoutState {
        if (isWaterfallMode) {
            return GalleryLayoutState(
                columnCount = if (isLandscape) 4 else 3,
                isWaterfallMode = false
            )
        }
        return when (columnCount) {
            4 -> GalleryLayoutState(columnCount = 3, isWaterfallMode = false)
            3 -> GalleryLayoutState(columnCount = 2, isWaterfallMode = false)
            2 -> GalleryLayoutState(columnCount = 1, isWaterfallMode = false)
            1 -> GalleryLayoutState(
                columnCount = if (isLandscape) 3 else 2,
                isWaterfallMode = true
            )
            else -> GalleryLayoutState(
                columnCount = if (isLandscape) 4 else 3,
                isWaterfallMode = false
            )
        }
    }
}
