package top.maary.darkbag.fragments.gallery

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.maary.darkbag.R
import top.maary.darkbag.models.CaptureMetadata
import top.maary.darkbag.models.ImageGroup

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GalleryGridItemBinderTest {

    private lateinit var context: Context
    private lateinit var binder: GalleryGridItemBinder

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.setTheme(R.style.AppTheme)
        binder = GalleryGridItemBinder(
            context = context,
            getRepository = { null },
            getAdapterScope = { CoroutineScope(Dispatchers.Unconfined) },
            getItemAt = { ImageGroup(baseName = "dummy") },
            isSelectionMode = { false },
            isKeySelected = { false },
            getColumnCount = { 3 },
            isWaterfallMode = { false },
            onToggleSingleSelection = {},
            onToggleLensSelection = { _, _ -> },
            onEnterSelectionAndToggleSingle = {},
            onEnterSelectionAndToggleLens = { _, _ -> },
            onItemClick = { _, _, _ -> },
            onItemLongClick = { _, _, _ -> }
        )
    }

    @Test
    fun testResolveFormatBadgeTextAcrossMediaTypes() {
        val jpg = Uri.parse("content://media/1.jpg")
        val jpg2 = Uri.parse("content://media/2.jpg")
        val dng = Uri.parse("content://media/1.dng")
        val mp4 = Uri.parse("content://media/1.mp4")

        assertEquals(
            context.getString(R.string.format_raw_jpg),
            binder.resolveFormatBadgeText(ImageGroup(baseName = "g1", jpgUri = jpg, dngUri = dng))
        )
        assertEquals(
            "RAW+2V",
            binder.resolveFormatBadgeText(
                ImageGroup(baseName = "g2", jpgUri = jpg, dngUri = dng, derivativeJpgUris = listOf(jpg, jpg2))
            )
        )
        assertEquals(
            "CDNG+MP4",
            binder.resolveFormatBadgeText(ImageGroup(baseName = "g3", isCinemaDng = true, mp4VideoUri = mp4))
        )
        assertEquals(
            "RAW VID",
            binder.resolveFormatBadgeText(ImageGroup(baseName = "g4", isRawVideo = true))
        )
        assertEquals(
            context.getString(R.string.format_half_frame),
            binder.resolveFormatBadgeText(ImageGroup(baseName = "g5", dngUri1 = dng, dngUri2 = dng))
        )
        assertEquals(
            "2V",
            binder.resolveFormatBadgeText(ImageGroup(baseName = "g6", jpgUri = jpg, derivativeJpgUris = listOf(jpg, jpg2)))
        )
    }

    @Test
    fun testFormatExifParams() {
        val full = CaptureMetadata(
            focalLengthIn35mmFilm = 35,
            fNumber = 1.8f,
            exposureTime = 10_000_000L, // 1/100s
            iso = 200
        )
        assertEquals("35mm · ƒ/1.8 · 1/100s · ISO 200", binder.formatExifParams(full))

        val longExposure = CaptureMetadata(
            exposureTime = 2_500_000_000L,
            iso = 64
        )
        assertEquals("2.5s · ISO 64", binder.formatExifParams(longExposure))

        assertEquals("--", binder.formatExifParams(CaptureMetadata()))
    }
}
