package top.maary.darkbag.rawvideo

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import top.maary.darkbag.repository.SettingsRepository
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RawVideoRefactorTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        SettingsRepository.resetForTest(context)
        SettingsRepository.getInstance(context).awaitHydratedBlocking()
    }

    @Test
    fun cinemaDngFrameExporter_formatFrameBaseName_handlesPrefixes() {
        assertEquals("DBAG_PHOTO_20261115_010203_f0", CinemaDngFrameExporter.formatFrameBaseName("RAWVID_20261115_010203", 0))
        assertEquals("DBAG_PHOTO_20261115_010203_f7", CinemaDngFrameExporter.formatFrameBaseName("DBAG_CDNG_20261115_010203", 7))
        assertEquals("DBAG_PHOTO_20261115_010203_f2", CinemaDngFrameExporter.formatFrameBaseName("DBAG_PHOTO_20261115_010203_f2", 2))
    }

    @Test
    fun cinemaDngFrameExporter_exportSingleFrameRawDngOnly_routesViaImageSaver(): Unit = runBlocking {
        val srcDng = File(context.cacheDir, "src_frame.dng").apply {
            writeBytes(byteArrayOf(0x49, 0x49, 0x2A, 0x00, 0x08, 0x00, 0x00, 0x00))
        }
        val outDng = File(context.cacheDir, "exported_frame_media.dng")
        if (outDng.exists()) outDng.delete()

        val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun query(u: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?) = null
            override fun getType(u: Uri) = "image/x-adobe-dng"
            override fun insert(u: Uri, v: ContentValues?): Uri = Uri.parse("content://media/external/images/media/777")
            override fun delete(u: Uri, s: String?, a: Array<out String>?) = 1
            override fun update(u: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 1
            override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
                outDng.createNewFile()
                return ParcelFileDescriptor.open(outDng, ParcelFileDescriptor.MODE_READ_WRITE)
            }
        }
        provider.attachInfo(context, android.content.pm.ProviderInfo().apply { authority = MediaStore.AUTHORITY })
        ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY, provider)

        val (rawUri, jpgUri) = RawVideoExporter.exportSingleFrameFromCinemaDng(
            context = context,
            frameDngUri = Uri.fromFile(srcDng),
            baseName = "RAWVID_20261115_010203",
            frameIndex = 3,
            editConfig = null,
            exportType = RawVideoExporter.FrameExportType.RAW_DNG_ONLY
        )

        assertNotNull(rawUri)
        assertNull(jpgUri)
        assertTrue(outDng.exists())
        assertEquals(8L, outDng.length())
        srcDng.delete()
        outDng.delete()
    }

    @Test
    fun cinemaDngWriter_writeDngFile_writesValidTiffHeaderAndPayload() {
        val outFile = File(context.cacheDir, "writer_unit_test.dng")
        if (outFile.exists()) outFile.delete()

        val header = RawVideoNative.Header(
            width = 16,
            height = 12,
            bitDepth = 16,
            cfaPattern = RawVideoNative.CFA_RGGB,
            fps = 24f,
            compressionType = RawVideoNative.COMPRESSION_NONE,
            audioSampleRate = 48000,
            audioChannels = 1,
            whiteLevel = 1023,
            blackLevel = floatArrayOf(64f, 64f, 64f, 64f),
            neutralPoint = floatArrayOf(0.5f, 1.0f, 0.75f),
            activeLutName = "None",
            activeLogName = "LogC",
            frameCount = 1,
            orientation = 90,
            make = "Darkbag",
            model = "UnitCam"
        )
        val payloadSize = 16 * 12 * 2
        val bayer = ByteBuffer.allocateDirect(payloadSize)
        for (i in 0 until payloadSize) bayer.put((i and 0x7F).toByte())
        val meta = longArrayOf(1_000_000_000L, 10_000_000L, 200L, java.lang.Float.floatToIntBits(1.8f).toLong(), java.lang.Float.floatToIntBits(4.5f).toLong())

        CinemaDngWriter.writeDngFile(outFile, header, meta, bayer, payloadSize)

        assertTrue(outFile.exists())
        val bytes = outFile.readBytes()
        assertTrue(bytes.size > payloadSize)
        val le = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x4949.toShort(), le.getShort(0))
        assertEquals(42.toShort(), le.getShort(2))
        assertEquals(8, le.getInt(4))
        outFile.delete()
    }

    @Test
    fun mp4VideoMuxerPipeline_helpers_computeFpsPtsAndSuccess() {
        assertEquals(30.0f, Mp4VideoMuxerPipeline.calculateMeasuredFps(31, 0L, 1_000_000_000L, 24f), 0.1f)
        assertEquals(24.0f, Mp4VideoMuxerPipeline.calculateMeasuredFps(1, 0L, 0L, 24f), 0.01f)

        val p0 = Mp4VideoMuxerPipeline.calculatePtsUs(0, 1_000_000L, 1_000_000L, -1L, 41_666L)
        val p1 = Mp4VideoMuxerPipeline.calculatePtsUs(1, 1_000_000L, 1_000_000L, p0, 41_666L)
        assertEquals(0L, p0)
        assertEquals(1000L, p1)

        val tmpFile = File(context.cacheDir, "mux_success_check.mp4").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        assertTrue(Mp4VideoMuxerPipeline.isExportSuccessful(muxerStarted = true, videoEos = true, outputFile = tmpFile))
        assertFalse(Mp4VideoMuxerPipeline.isExportSuccessful(muxerStarted = false, videoEos = true, outputFile = tmpFile))
        tmpFile.delete()

        val bmp = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val argb = IntArray(16)
        val nv12 = ByteArray(16 * 3 / 2)
        Mp4VideoMuxerPipeline.bitmapToNv12(bmp, argb, nv12, 4, 4)
        assertTrue(nv12[0].toInt() and 0xFF > 200) // White Y luma > 200
        bmp.recycle()
    }
}
