package top.maary.darkbag.pipeline.darkroom

import android.graphics.Bitmap
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import top.maary.darkbag.models.EditConfig
import top.maary.darkbag.pipeline.darkroom.model.DarkroomRenderSpec
import top.maary.darkbag.pipeline.darkroom.model.DarkroomSource
import top.maary.darkbag.pipeline.darkroom.model.HalfFrameComposeSpec
import top.maary.darkbag.pipeline.darkroom.model.RenderOutputFormat
import top.maary.darkbag.pipeline.darkroom.sink.BitmapPreviewSink
import java.io.File

@RunWith(RobolectricTestRunner::class)
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
        assertEquals(10, resolved?.get(0))
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
    fun testRenderHalfFrame_WithCachedBitmaps() = runTest {
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
}
