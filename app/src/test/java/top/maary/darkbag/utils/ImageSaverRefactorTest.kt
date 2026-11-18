package top.maary.darkbag.utils

import android.content.Context
import android.graphics.Bitmap
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import top.maary.darkbag.models.BasicAdjustments
import top.maary.darkbag.models.CaptureMetadata
import top.maary.darkbag.models.EditConfig
import top.maary.darkbag.repository.SettingsRepository
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ImageSaverRefactorTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        SettingsRepository.resetForTest(context)
        SettingsRepository.getInstance(context).awaitHydratedBlocking()
        HalfFrameSessionStore(context).setTempDngPath(null, HalfFrameSessionStore.PROFILE_HALF_SIDE)
    }

    @Test
    fun exifMetadataApplier_getExifOrientation_coversNormalAndMirroredCases() {
        assertEquals(ExifInterface.ORIENTATION_NORMAL, ImageSaver.getExifOrientation(0, mirror = false))
        assertEquals(ExifInterface.ORIENTATION_FLIP_HORIZONTAL, ImageSaver.getExifOrientation(0, mirror = true))
        assertEquals(ExifInterface.ORIENTATION_ROTATE_90, ImageSaver.getExifOrientation(90, mirror = false))
        assertEquals(ExifInterface.ORIENTATION_TRANSPOSE, ImageSaver.getExifOrientation(90, mirror = true))
        assertEquals(ExifInterface.ORIENTATION_ROTATE_180, ImageSaver.getExifOrientation(180, mirror = false))
        assertEquals(ExifInterface.ORIENTATION_FLIP_VERTICAL, ImageSaver.getExifOrientation(180, mirror = true))
        assertEquals(ExifInterface.ORIENTATION_ROTATE_270, ImageSaver.getExifOrientation(270, mirror = false))
        assertEquals(ExifInterface.ORIENTATION_TRANSVERSE, ImageSaver.getExifOrientation(270, mirror = true))
    }

    @Test
    fun exifMetadataApplier_createFinalEditConfig_appliesZoomOnlyWhenSignificant() {
        assertNull(ExifMetadataApplier.createFinalEditConfig(null, 1.0f))
        assertEquals(1.5f, ExifMetadataApplier.createFinalEditConfig(null, 1.5f)!!.zoomFactor, 0.001f)

        val existing = EditConfig(log = "LogC", zoomFactor = 1.0f)
        assertEquals(1.5f, ExifMetadataApplier.createFinalEditConfig(existing, 1.5f)!!.zoomFactor, 0.001f)

        val alreadyZoomed = EditConfig(log = "LogC", zoomFactor = 2.0f)
        assertEquals(2.0f, ExifMetadataApplier.createFinalEditConfig(alreadyZoomed, 1.5f)!!.zoomFactor, 0.001f)
    }

    @Test
    fun exifMetadataApplier_buildEditConfigJson_and_writeMetadataToExifFile() {
        val tempJpeg = File(context.cacheDir, "test_exif_${System.currentTimeMillis()}.jpg")
        val bmp = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        FileOutputStream(tempJpeg).use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bmp.recycle()

        val editConfig = EditConfig(
            log = "HDR+ LogC",
            lut = "Kodak2383.cube",
            exposure = 0.5f,
            contrast = 0.1f,
            adjustments = listOf(BasicAdjustments(exposure = -0.25f)),
            showTimestamp = true,
            flareType = 2,
            hfLayout = "SBS",
            isSwapped = true,
            zoomFactor = 1.4f,
            colorEngineMode = 1
        )
        val captureMetadata = CaptureMetadata(
            iso = 320,
            exposureTime = 10_000_000L,
            fNumber = 1.8f,
            focalLength = 6.9f,
            focalLengthIn35mmFilm = 24,
            dateTimeOriginal = 1_700_000_000_000L,
            make = "DarkbagLab",
            model = "DB-1",
            lensModel = "24mm f/1.8"
        )

        ImageSaver.writeMetadataToExifFile(tempJpeg, editConfig, captureMetadata)

        val exif = ExifInterface(tempJpeg.absolutePath)
        assertEquals("DarkbagLab", exif.getAttribute(ExifInterface.TAG_MAKE))
        assertEquals("DB-1", exif.getAttribute(ExifInterface.TAG_MODEL))
        assertEquals("24mm f/1.8", exif.getAttribute(ExifInterface.TAG_LENS_MODEL))
        assertEquals(DarkbagIdentity.softwareString(true), exif.getAttribute(ExifInterface.TAG_SOFTWARE))
        assertEquals(DarkbagIdentity.imageDescription(true), exif.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION))

        val userComment = exif.getAttribute(ExifInterface.TAG_USER_COMMENT)
        assertNotNull(userComment)
        val json = JSONObject(userComment!!)
        assertEquals("HDR+ LogC", json.getString("log"))
        assertEquals("Kodak2383.cube", json.getString("lut"))
        assertEquals(2, json.getInt("flare_type"))
        assertEquals("SBS", json.getString("hf_layout"))
        assertTrue(json.getBoolean("is_swapped"))
        assertEquals(1, json.getJSONArray("adjustments").length())

        tempJpeg.delete()
    }

    @Test
    fun imageSaver_saveDngBytesToMediaStoreOrSaf_handlesEmptyAndNonEmptyBytes() {
        assertNull(ImageSaver.saveDngBytesToMediaStoreOrSaf(context, ByteArray(0), "empty.dng", null))

        // Verify Robolectric ContentResolver accepts insert without throwing when ShadowContentProvider is absent
        val dummyProvider = object : android.content.ContentProvider() {
            override fun onCreate() = true
            override fun query(u: android.net.Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?) = null
            override fun getType(u: android.net.Uri) = "image/x-adobe-dng"
            override fun insert(u: android.net.Uri, v: android.content.ContentValues?): android.net.Uri {
                return android.net.Uri.parse("content://media/external/images/media/42")
            }
            override fun delete(u: android.net.Uri, s: String?, a: Array<out String>?) = 1
            override fun update(u: android.net.Uri, v: android.content.ContentValues?, s: String?, a: Array<out String>?) = 1
            override fun openFile(uri: android.net.Uri, mode: String): android.os.ParcelFileDescriptor {
                val f = File(this@ImageSaverRefactorTest.context.cacheDir, "provider_out_42.dng")
                f.createNewFile()
                return android.os.ParcelFileDescriptor.open(f, android.os.ParcelFileDescriptor.MODE_READ_WRITE)
            }
        }
        dummyProvider.attachInfo(context, android.content.pm.ProviderInfo().apply { authority = MediaStore.AUTHORITY })
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal(
            MediaStore.AUTHORITY, dummyProvider
        )

        val payload = byteArrayOf(0x49, 0x49, 0x2A, 0x00, 0x08, 0x00, 0x00, 0x00)
        val savedUri = ImageSaver.saveDngBytesToMediaStoreOrSaf(context, payload, "frame_0.dng", null)
        assertNotNull(savedUri)
        val written = File(this@ImageSaverRefactorTest.context.cacheDir, "provider_out_42.dng")
        assertTrue(written.exists())
        assertEquals(payload.size, written.readBytes().size)
        written.delete()
    }

    @Test
    fun mediaStoreOutputWriter_persistDngFile_stagesAndPublishesHalfFrameDngs() {
        val store = HalfFrameSessionStore(context)
        val f1 = File(this@ImageSaverRefactorTest.context.cacheDir, "hf_f1.dng").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val meta1 = HalfFrameManager.Metadata(
            profile = HalfFrameSessionStore.PROFILE_HALF_SIDE,
            dateStamp = false,
            frame1BaseName = null
        )

        val f1Result = MediaStoreOutputWriter.persistDngFile(
            context = context,
            linearDngPath = f1.absolutePath,
            baseName = "20261115_120000_HF1",
            rawFolderUri = null,
            isHalfFrameActive = true,
            halfFrameMetadata = meta1
        )
        assertNull(f1Result)
        assertFalse(f1.exists())
        var stagedPath: String? = null
        val waitDeadline = System.currentTimeMillis() + 1000L
        while (stagedPath == null && System.currentTimeMillis() < waitDeadline) {
            stagedPath = store.readSession(profile = HalfFrameSessionStore.PROFILE_HALF_SIDE).tempDngPath
            if (stagedPath == null) Thread.sleep(10)
        }
        assertNotNull(stagedPath)
        assertTrue(File(stagedPath!!).exists())

        val outDir = File(this@ImageSaverRefactorTest.context.cacheDir, "dng_out_clean")
        outDir.mkdirs()
        // Register provider so MediaStore publish on Frame 2 succeeds and clears staged file
        val dummyProvider = object : android.content.ContentProvider() {
            var count = 100
            override fun onCreate() = true
            override fun query(u: android.net.Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?) = null
            override fun getType(u: android.net.Uri) = "image/x-adobe-dng"
            override fun insert(u: android.net.Uri, v: android.content.ContentValues?): android.net.Uri =
                android.net.Uri.parse("content://media/external/images/media/${count++}")
            override fun delete(u: android.net.Uri, s: String?, a: Array<out String>?) = 1
            override fun update(u: android.net.Uri, v: android.content.ContentValues?, s: String?, a: Array<out String>?) = 1
            override fun openFile(uri: android.net.Uri, mode: String): android.os.ParcelFileDescriptor {
                val f = File(outDir, "${uri.lastPathSegment}.dng")
                f.createNewFile()
                return android.os.ParcelFileDescriptor.open(f, android.os.ParcelFileDescriptor.MODE_READ_WRITE)
            }
        }
        dummyProvider.attachInfo(context, android.content.pm.ProviderInfo().apply { authority = MediaStore.AUTHORITY })
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY, dummyProvider)

        val f2 = File(context.cacheDir, "hf_f2.dng").apply { writeBytes(byteArrayOf(5, 6, 7, 8)) }
        val meta2 = HalfFrameManager.Metadata(
            profile = HalfFrameSessionStore.PROFILE_HALF_SIDE,
            dateStamp = false,
            frame1BaseName = "20261115_120000"
        )
        val f2Result = MediaStoreOutputWriter.persistDngFile(
            context = context,
            linearDngPath = f2.absolutePath,
            baseName = "20261115_120000_HF2",
            rawFolderUri = null,
            isHalfFrameActive = true,
            halfFrameMetadata = meta2
        )
        assertNotNull(f2Result)
        assertFalse(f2.exists())
        assertFalse(File(stagedPath).exists())
        val clearDeadline = System.currentTimeMillis() + 1000L
        while (store.readSession(profile = HalfFrameSessionStore.PROFILE_HALF_SIDE).tempDngPath != null &&
            System.currentTimeMillis() < clearDeadline
        ) {
            Thread.sleep(10)
        }
        assertNull(store.readSession(profile = HalfFrameSessionStore.PROFILE_HALF_SIDE).tempDngPath)
        outDir.deleteRecursively()
    }

    @Test
    fun imageSaver_suppressesDngThumbnailEmissionInHalfFrameWhenJpgIsNull() = runBlocking {
        val events = mutableListOf<top.maary.darkbag.processor.ColorProcessor.BackgroundSaveEvent>()
        val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined).launch {
            top.maary.darkbag.processor.ColorProcessor.backgroundSaveFlow.collect { events.add(it) }
        }

        val dngFile = File(context.cacheDir, "hf_f1_test.dng").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val meta1 = HalfFrameManager.Metadata(
            profile = HalfFrameSessionStore.PROFILE_HALF_SIDE,
            dateStamp = false,
            frame1BaseName = null
        )

        val resultUri = ImageSaver.saveProcessedImage(
            context = context,
            inputBitmap = null,
            bmpPath = null,
            rotationDegrees = 0,
            zoomFactor = 1.0f,
            baseName = "20261115_120000_HF1",
            linearDngPath = dngFile.absolutePath,
            saveJpg = false,
            saveRaw = true,
            jpgFolderUri = null,
            rawFolderUri = null,
            isFastPath = false,
            halfFrameMetadata = meta1
        )

        job.cancel()
        assertNull("Half-frame Frame 1 must return null URI to prevent showing unstiched DNG", resultUri)
        assertTrue("Half-frame Frame 1 must not emit backgroundSaveFlow for DNG", events.isEmpty())
    }
}
