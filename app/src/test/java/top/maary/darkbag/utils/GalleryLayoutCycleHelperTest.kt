package top.maary.darkbag.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryLayoutCycleHelperTest {

    @Test
    fun testDefaultForOrientation() {
        val portrait = GalleryLayoutState.defaultForOrientation(isLandscape = false)
        assertEquals(3, portrait.columnCount)
        assertFalse(portrait.isWaterfallMode)

        val landscape = GalleryLayoutState.defaultForOrientation(isLandscape = true)
        assertEquals(4, landscape.columnCount)
        assertFalse(landscape.isWaterfallMode)
    }

    @Test
    fun testPortraitCycle() {
        var state = GalleryLayoutState(columnCount = 3, isWaterfallMode = false)

        state = state.cycle(isLandscape = false)
        assertEquals(2, state.columnCount)
        assertFalse(state.isWaterfallMode)

        state = state.cycle(isLandscape = false)
        assertEquals(1, state.columnCount)
        assertFalse(state.isWaterfallMode)

        state = state.cycle(isLandscape = false)
        assertEquals(2, state.columnCount)
        assertTrue(state.isWaterfallMode)

        state = state.cycle(isLandscape = false)
        assertEquals(3, state.columnCount)
        assertFalse(state.isWaterfallMode)
    }

    @Test
    fun testLandscapeCycle() {
        var state = GalleryLayoutState(columnCount = 4, isWaterfallMode = false)

        state = state.cycle(isLandscape = true)
        assertEquals(3, state.columnCount)
        assertFalse(state.isWaterfallMode)

        state = state.cycle(isLandscape = true)
        assertEquals(2, state.columnCount)
        assertFalse(state.isWaterfallMode)

        state = state.cycle(isLandscape = true)
        assertEquals(1, state.columnCount)
        assertFalse(state.isWaterfallMode)

        state = state.cycle(isLandscape = true)
        assertEquals(3, state.columnCount)
        assertTrue(state.isWaterfallMode)

        state = state.cycle(isLandscape = true)
        assertEquals(4, state.columnCount)
        assertFalse(state.isWaterfallMode)
    }
}
