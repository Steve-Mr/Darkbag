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
import top.maary.darkbag.pipeline.darkroom.sink.BitmapPreviewSink
import top.maary.darkbag.pipeline.darkroom.sink.SandboxExportSink
import top.maary.darkbag.pipeline.darkroom.sink.ShareFileSink
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExportSinkTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testBitmapPreviewSink_DeliversBitmapAndRetainsOwnership() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        var callbackInvoked = false
        val sink = BitmapPreviewSink {
            callbackInvoked = true
        }
        val spec = DarkroomRenderSpec(
            source = DarkroomSource.Single(baseName = "test"),
            editConfig = EditConfig(),
            sink = sink
        )
        val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)

        val retained = sink.onBitmapExported(context, bitmap, spec)

        assertTrue(retained)
        assertTrue(callbackInvoked)
        assertNotNull(sink.deliveredBitmap)
        assertEquals(bitmap, sink.deliveredBitmap)
    }

    @Test
    fun testSandboxExportSink_ExportsBitmapToFile() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val targetFile = File(tempFolder.root, "sandbox_output.jpg")
        val sink = SandboxExportSink(targetFile = targetFile)
        val spec = DarkroomRenderSpec(
            source = DarkroomSource.Single(baseName = "sandbox_test"),
            sink = sink
        )
        val bitmap = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888)

        sink.onBitmapExported(context, bitmap, spec)

        assertTrue(targetFile.exists())
        assertTrue(targetFile.length() > 0)
        assertEquals(targetFile, sink.exportedFile)
    }

    @Test
    fun testShareFileSink_ExportsFileDirectly() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val srcFile = tempFolder.newFile("source.tif").apply {
            writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        }
        val targetFile = File(tempFolder.root, "shared_target.tif")
        val sink = ShareFileSink(outputFile = targetFile, mimeType = "image/tiff")
        val spec = DarkroomRenderSpec(
            source = DarkroomSource.Single(baseName = "share_test"),
            sink = sink
        )

        val uri = sink.onFileExported(context, srcFile, "image/tiff", spec)

        assertNotNull(uri)
        assertTrue(targetFile.exists())
        assertEquals(5, targetFile.length())
        assertEquals(targetFile, sink.deliveredFile)
    }
}
