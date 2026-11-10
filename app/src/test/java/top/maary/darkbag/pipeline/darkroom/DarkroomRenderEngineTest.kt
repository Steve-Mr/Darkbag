package top.maary.darkbag.pipeline.darkroom

import android.graphics.Bitmap
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import top.maary.darkbag.models.EditConfig
import top.maary.darkbag.pipeline.darkroom.model.DarkroomRenderSpec
import top.maary.darkbag.pipeline.darkroom.model.DarkroomSource
import top.maary.darkbag.pipeline.darkroom.model.HalfFrameComposeSpec
import top.maary.darkbag.pipeline.darkroom.model.RenderOutputFormat
import top.maary.darkbag.pipeline.darkroom.sink.BitmapPreviewSink
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DarkroomRenderEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testResolveDngBytes_PrefersByteArray() {
        val context = RuntimeEnvironment.getApplication()
        val dummyBytes = byteArrayOf(10, 20, 30)
        val single = DarkroomSource.Single(dngBytes = dummyBytes)

        val resolved = DarkroomRenderEngine.resolveDngBytes(context, single)
        assertNotNull(resolved)
        assertEquals(3, resolved?.size)
        assertEquals(10.toByte(), resolved?.get(0))
    }

    @Test
    fun testResolveDngBytes_ReadsFromDngFile() {
        val context = RuntimeEnvironment.getApplication()
        val testFile = tempFolder.newFile("sample.dng").apply {
            writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        }
        val single = DarkroomSource.Single(dngFile = testFile)

        val resolved = DarkroomRenderEngine.resolveDngBytes(context, single)
        assertNotNull(resolved)
        assertEquals(5, resolved?.size)
    }

    @Test
    fun testResolveRotationDegrees_ExplicitValue() {
        val context = RuntimeEnvironment.getApplication()
        val single = DarkroomSource.Single(orientationDegrees = 90)

        val rot = DarkroomRenderEngine.resolveRotationDegrees(context, single, null)
        assertEquals(90, rot)
    }

    @Test
    fun testResolveDimensions_ExplicitDimensions() {
        val context = RuntimeEnvironment.getApplication()
        val single = DarkroomSource.Single(rawWidth = 4000, rawHeight = 3000)

        val (w, h) = DarkroomRenderEngine.resolveDimensions(context, single, null, rotDegrees = 0)
        assertEquals(4000, w)
        assertEquals(3000, h)

        val (wRot, hRot) = DarkroomRenderEngine.resolveDimensions(context, single, null, rotDegrees = 90)
        assertEquals(3000, wRot)
        assertEquals(4000, hRot)
    }

    @Test
    fun testRenderHalfFrame_WithCachedBitmaps() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val bmp1 = Bitmap.createBitmap(100, 150, Bitmap.Config.ARGB_8888)
        val bmp2 = Bitmap.createBitmap(100, 150, Bitmap.Config.ARGB_8888)

        val hfSource = DarkroomSource.HalfFrame(
            frame1 = DarkroomSource.Single(baseName = "frame1"),
            frame2 = DarkroomSource.Single(baseName = "frame2"),
            composeSpec = HalfFrameComposeSpec(layout = "SBS"),
            baseName = "HF_TEST",
            cachedFrame1Bitmap = bmp1,
            cachedFrame2Bitmap = bmp2
        )

        val previewSink = BitmapPreviewSink()
        val spec = DarkroomRenderSpec(
            source = hfSource,
            editConfig = EditConfig(hfLayout = "SBS"),
            outputFormat = RenderOutputFormat.BITMAP,
            sink = previewSink
        )

        val result = DarkroomRenderEngine.render(context, spec)

        assertTrue(result.success)
        assertNotNull(result.bitmap)
        assertNotNull(previewSink.deliveredBitmap)
        assertEquals(result.bitmap, previewSink.deliveredBitmap)
    }

    @Test
    fun testLibRawCfaNormalization_AllFourBayerPatterns() {
        // LibRaw encodes the 4 Bayer subpixels using cdesc = "RGBG" (0='R', 1='G', 2='B', 3='G').
        // Ensure that normalizing index 3 ('G') to canonical 1 ('G') accurately identifies all 4 CFA patterns
        // and prevents BGGR (2, 3, 1, 0) or GBRG (3, 2, 0, 1) from falling back to RGGB (0).
        val cdesc = "RGBG"
        fun normCfaColor(idx: Int): Int {
            if (idx in 0..3) {
                return when (cdesc[idx]) {
                    'R', 'r' -> 0
                    'G', 'g' -> 1
                    'B', 'b' -> 2
                    else -> if (idx == 3) 1 else idx
                }
            }
            return if (idx == 3) 1 else idx
        }

        fun resolveCfaPattern(raw00: Int, raw01: Int, raw10: Int, raw11: Int): Int {
            val c00 = normCfaColor(raw00)
            val c01 = normCfaColor(raw01)
            val c10 = normCfaColor(raw10)
            val c11 = normCfaColor(raw11)
            return when {
                c00 == 0 && c01 == 1 && c10 == 1 && c11 == 2 -> 0 // RGGB
                c00 == 1 && c01 == 0 && c10 == 2 && c11 == 1 -> 1 // GRBG
                c00 == 1 && c01 == 2 && c10 == 0 && c11 == 1 -> 2 // GBRG
                c00 == 2 && c01 == 1 && c10 == 1 && c11 == 0 -> 3 // BGGR
                else -> -1
            }
        }

        // RGGB: LibRaw filters produce (0, 1, 3, 2) or (0, 3, 1, 2)
        assertEquals(0, resolveCfaPattern(0, 1, 3, 2))
        assertEquals(0, resolveCfaPattern(0, 3, 1, 2))

        // GRBG: LibRaw filters produce (1, 0, 2, 3) or (3, 0, 2, 1)
        assertEquals(1, resolveCfaPattern(1, 0, 2, 3))
        assertEquals(1, resolveCfaPattern(3, 0, 2, 1))

        // GBRG: LibRaw filters produce (3, 2, 0, 1) or (1, 2, 0, 3)
        assertEquals(2, resolveCfaPattern(3, 2, 0, 1))
        assertEquals(2, resolveCfaPattern(1, 2, 0, 3))

        // BGGR: LibRaw filters produce (2, 3, 1, 0) or (2, 1, 3, 0)
        assertEquals(3, resolveCfaPattern(2, 3, 1, 0))
        assertEquals(3, resolveCfaPattern(2, 1, 3, 0))
    }
}
