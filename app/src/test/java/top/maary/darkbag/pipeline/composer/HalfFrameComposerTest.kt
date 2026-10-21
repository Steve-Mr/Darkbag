package top.maary.darkbag.pipeline.composer

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.maary.darkbag.utils.HalfFrameUtils

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HalfFrameComposerTest {

    @Test
    fun testSideBySideComposition() {
        val f1 = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val f2 = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }

        val gap = HalfFrameUtils.calculateGap(200)
        val expectedW = 100 + gap + 100
        val expectedH = 200

        val result = HalfFrameComposer.compose(
            frame1 = f1,
            frame2 = f2,
            layout = "SBS",
            isSwapped = false,
            downsample = false
        )

        assertNotNull(result)
        assertEquals(expectedW, result!!.width)
        assertEquals(expectedH, result.height)

        // Pixel check: Left frame is red, right frame is blue
        assertEquals(Color.RED, result.getPixel(10, 10))
        assertEquals(Color.BLUE, result.getPixel(expectedW - 10, 10))

        f1.recycle()
        f2.recycle()
        result.recycle()
    }

    @Test
    fun testTopBottomComposition() {
        // Provide landscape images for top-bottom
        val f1 = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        val f2 = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.YELLOW) }

        val gap = HalfFrameUtils.calculateGap(200)
        val expectedW = 200
        val expectedH = 100 + gap + 100

        val result = HalfFrameComposer.compose(
            frame1 = f1,
            frame2 = f2,
            layout = "TB",
            isSwapped = false,
            downsample = false
        )

        assertNotNull(result)
        assertEquals(expectedW, result!!.width)
        assertEquals(expectedH, result.height)

        // Pixel check: Top frame is green, bottom frame is yellow
        assertEquals(Color.GREEN, result.getPixel(10, 10))
        assertEquals(Color.YELLOW, result.getPixel(10, expectedH - 10))

        f1.recycle()
        f2.recycle()
        result.recycle()
    }

    @Test
    fun testFrameSwapping() {
        val f1 = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val f2 = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }

        val gap = HalfFrameUtils.calculateGap(200)
        val expectedW = 100 + gap + 100

        val result = HalfFrameComposer.compose(
            frame1 = f1,
            frame2 = f2,
            layout = "SBS",
            isSwapped = true,
            downsample = false
        )

        assertNotNull(result)
        // With isSwapped = true, left slot should be f2 (BLUE), right slot should be f1 (RED)
        assertEquals(Color.BLUE, result!!.getPixel(10, 10))
        assertEquals(Color.RED, result.getPixel(expectedW - 10, 10))

        f1.recycle()
        f2.recycle()
        result.recycle()
    }

    @Test
    fun testMissingFramePadding_frame1Null() {
        val f2 = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val gap = HalfFrameUtils.calculateGap(200)
        val expectedW = 100 + gap + 100

        val result = HalfFrameComposer.compose(
            frame1 = null,
            frame2 = f2,
            layout = "SBS",
            isSwapped = false,
            downsample = false
        )

        assertNotNull(result)
        assertEquals(expectedW, result!!.width)
        assertEquals(200, result.height)

        // Left slot should be padded dummy black bitmap, right slot is BLUE
        assertEquals(Color.BLACK, result.getPixel(10, 10))
        assertEquals(Color.BLUE, result.getPixel(expectedW - 10, 10))

        f2.recycle()
        result.recycle()
    }

    @Test
    fun testMissingFramePadding_frame2Null() {
        val f1 = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val gap = HalfFrameUtils.calculateGap(200)
        val expectedW = 100 + gap + 100

        val result = HalfFrameComposer.compose(
            frame1 = f1,
            frame2 = null,
            layout = "SBS",
            isSwapped = false,
            downsample = false
        )

        assertNotNull(result)
        assertEquals(expectedW, result!!.width)
        assertEquals(200, result.height)

        // Left slot is RED, right slot should be padded dummy black bitmap
        assertEquals(Color.RED, result.getPixel(10, 10))
        assertEquals(Color.BLACK, result.getPixel(expectedW - 10, 10))

        f1.recycle()
        result.recycle()
    }

    @Test
    fun testBothFramesNullReturnsNull() {
        val result = HalfFrameComposer.compose(
            frame1 = null,
            frame2 = null,
            layout = "SBS"
        )
        assertNull(result)
    }

    @Test
    fun testDownsamplingFlag() {
        val f1 = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val f2 = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }

        val gap = HalfFrameUtils.calculateGap(200)
        val normalW = 100 + gap + 100
        val normalH = 200

        val nonDownsampled = HalfFrameComposer.compose(
            frame1 = f1,
            frame2 = f2,
            layout = "SBS",
            downsample = false
        )

        val downsampled = HalfFrameComposer.compose(
            frame1 = f1,
            frame2 = f2,
            layout = "SBS",
            downsample = true
        )

        assertNotNull(nonDownsampled)
        assertNotNull(downsampled)

        assertEquals(normalW, nonDownsampled!!.width)
        assertEquals(normalH, nonDownsampled.height)

        val expectedDownsampledW = (normalW * 0.707f).toInt()
        val expectedDownsampledH = (normalH * 0.707f).toInt()
        assertEquals(expectedDownsampledW, downsampled!!.width)
        assertEquals(expectedDownsampledH, downsampled.height)

        assertTrue(downsampled.width < nonDownsampled.width)
        assertTrue(downsampled.height < nonDownsampled.height)

        f1.recycle()
        f2.recycle()
        nonDownsampled.recycle()
        downsampled.recycle()
    }

    @Test
    fun testOrientationCorrection_landscapeToPortraitForSBS() {
        // Feed landscape images (200x100) into Side-by-side (which requires portrait)
        val f1 = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val f2 = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }

        val result = HalfFrameComposer.compose(
            frame1 = f1,
            frame2 = f2,
            layout = "SBS"
        )

        assertNotNull(result)
        // Oriented images should be 100x200 (portrait)
        val gap = HalfFrameUtils.calculateGap(200)
        val expectedW = 100 + gap + 100
        val expectedH = 200
        assertEquals(expectedW, result!!.width)
        assertEquals(expectedH, result.height)

        f1.recycle()
        f2.recycle()
        result.recycle()
    }

    @Test
    fun testEffectsApplied() {
        val f1 = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
        val f2 = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }

        val result = HalfFrameComposer.compose(
            frame1 = f1,
            frame2 = f2,
            layout = "SBS",
            showTimestamp = true,
            flareType = 1 // vertical light leak
        )

        assertNotNull(result)
        // Flare or timestamp will have modified some pixels away from pure black
        var foundNonBlack = false
        for (x in 0 until result!!.width step 10) {
            for (y in 0 until result.height step 10) {
                if (result.getPixel(x, y) != Color.BLACK) {
                    foundNonBlack = true
                    break
                }
            }
            if (foundNonBlack) break
        }
        assertTrue("Expected non-black pixels from effects", foundNonBlack)

        f1.recycle()
        f2.recycle()
        result.recycle()
    }
}

