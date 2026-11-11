package top.maary.darkbag.repository

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DngBaselineExposureTest {

    private lateinit var context: Context
    private lateinit var repository: ImageRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        repository = ImageRepository(context)
    }

    @Test
    fun testReadDngBaselineExposure_SyntheticTiff() {
        val tempFile = File.createTempFile("test_dng_", ".dng", context.cacheDir)
        try {
            // Write minimal valid TIFF with tag 50730 (BaselineExposure = 1.0 EV)
            val buf = ByteBuffer.allocate(34).order(ByteOrder.LITTLE_ENDIAN)
            // Header
            buf.put('I'.code.toByte())
            buf.put('I'.code.toByte())
            buf.putShort(42.toShort())
            buf.putInt(8) // IFD offset

            // IFD at offset 8
            buf.putShort(1.toShort()) // 1 entry
            // Entry 0
            buf.putShort(50730.toShort()) // Tag: BaselineExposure (0xC62A)
            buf.putShort(10.toShort())    // Type: SRATIONAL
            buf.putInt(1)                 // Count: 1
            buf.putInt(26)                // Value offset: 26

            // Next IFD offset
            buf.putInt(0)

            // Value at offset 26
            buf.putInt(1) // num = 1
            buf.putInt(1) // den = 1 -> 1.0 EV

            FileOutputStream(tempFile).use { it.write(buf.array()) }

            val uri = Uri.fromFile(tempFile)
            val config = repository.readDngBaselineExposure(uri, false)

            assertNotNull("Config should not be null", config)
            assertEquals("Digital gain should be 2.0 for 1.0 EV", 2.0f, config!!.digitalGain, 0.001f)
            assertEquals("User exposure adjustment must remain 0.0f", 0.0f, config.exposure, 0.001f)
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun testReadDngBaselineExposure_RealCaptureDngIfPresent() {
        val sampleDng = File("/home/antigravity/sample/DBAG_capture.dng")
        if (!sampleDng.exists()) return

        val uri = Uri.fromFile(sampleDng)
        val config = repository.readDngBaselineExposure(uri, false)

        assertNotNull("Config should be parsed from real sample DNG", config)
        assertEquals("Digital gain should be 2.0 (1 EV baseline exposure)", 2.0f, config!!.digitalGain, 0.01f)
        assertEquals("Exposure slider must be 0.0f", 0.0f, config.exposure, 0.001f)
    }

    @Test
    fun testReadDngBaselineExposure_HalfFrame() {
        val tempFile = File.createTempFile("test_hf_dng_", ".dng", context.cacheDir)
        try {
            val buf = ByteBuffer.allocate(34).order(ByteOrder.LITTLE_ENDIAN)
            buf.put('I'.code.toByte())
            buf.put('I'.code.toByte())
            buf.putShort(42.toShort())
            buf.putInt(8)
            buf.putShort(1.toShort())
            buf.putShort(50730.toShort())
            buf.putShort(10.toShort())
            buf.putInt(1)
            buf.putInt(26)
            buf.putInt(0)
            buf.putInt(1) // num = 1
            buf.putInt(1) // den = 1 -> 1.0 EV

            FileOutputStream(tempFile).use { it.write(buf.array()) }

            val uri = Uri.fromFile(tempFile)
            val config = repository.readDngBaselineExposure(uri, true, index = 1)

            assertNotNull("Config should not be null", config)
            val adjs = config!!.adjustments
            assertNotNull("Adjustments should not be null for half-frame", adjs)
            assertEquals(2, adjs!!.size)
            assertEquals(1.0f, adjs[0].digitalGain, 0.001f)
            assertEquals(2.0f, adjs[1].digitalGain, 0.001f)
            assertEquals(0.0f, adjs[1].exposure, 0.001f)
        } finally {
            tempFile.delete()
        }
    }
}
