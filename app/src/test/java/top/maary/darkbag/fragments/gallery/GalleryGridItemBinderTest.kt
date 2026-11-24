package top.maary.darkbag.fragments.gallery

import android.content.Context
import android.net.Uri
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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
import top.maary.darkbag.fragments.DarkbagGalleryGridAdapter
import top.maary.darkbag.models.CaptureMetadata
import top.maary.darkbag.models.ImageGroup
import top.maary.darkbag.models.MultiCameraLensItem

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

    @Test
    fun testClickAndLongClickForwardToCallbacksDuringSelectionMode() {
        val singleGroup1 = ImageGroup(baseName = "DBAG_20261121_001")
        val singleGroup2 = ImageGroup(baseName = "DBAG_20261121_002")
        val lens1 = MultiCameraLensItem(lensTag = "0.6x", multiplier = 0.6f)
        val lens2 = MultiCameraLensItem(lensTag = "1.0x", multiplier = 1.0f)
        val multiGroup = ImageGroup(
            baseName = "DBAG_20261121_003",
            isMultiCamera = true,
            multiCameraLenses = listOf(lens1, lens2)
        )

        val clickedEvents = mutableListOf<Triple<String, Int, String?>>()
        val longClickedEvents = mutableListOf<Triple<String, Int, String?>>()

        val recyclerView = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
        }
        val adapter = DarkbagGalleryGridAdapter(context).apply {
            onItemClick = { group, pos, lensTag ->
                clickedEvents.add(Triple(group.baseName, pos, lensTag))
            }
            onItemLongClick = { group, pos, lensTag ->
                longClickedEvents.add(Triple(group.baseName, pos, lensTag))
            }
        }
        recyclerView.adapter = adapter
        adapter.submitList(listOf(singleGroup1, singleGroup2, multiGroup))
        adapter.syncSelection(setOf(singleGroup1.baseName), selectionMode = true)

        recyclerView.measure(
            View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(4000, View.MeasureSpec.EXACTLY)
        )
        recyclerView.layout(0, 0, 480, 4000)

        val holder0 = recyclerView.findViewHolderForAdapterPosition(0) as DarkbagGalleryGridAdapter.SingleViewHolder
        val holder1 = recyclerView.findViewHolderForAdapterPosition(1) as DarkbagGalleryGridAdapter.SingleViewHolder
        val holder2 = recyclerView.findViewHolderForAdapterPosition(2) as DarkbagGalleryGridAdapter.MultiCamViewHolder

        assertEquals(View.VISIBLE, holder0.binding.iconSelected.visibility)
        assertEquals(View.VISIBLE, holder1.binding.iconUnselected.visibility)

        // Short-tapping item 2 while already in selection mode MUST forward to onItemClick
        holder1.binding.cardRoot.performClick()
        // Short-tapping a multi-camera lens slot in selection mode MUST forward lensTag to onItemClick
        holder2.binding.slotLens2.performClick()
        // Long-clicking item 1 MUST forward to onItemLongClick
        holder0.binding.cardRoot.performLongClick()

        assertEquals(
            listOf(
                Triple("DBAG_20261121_002", 1, null),
                Triple("DBAG_20261121_003", 2, "1.0x")
            ),
            clickedEvents
        )
        assertEquals(
            listOf(Triple("DBAG_20261121_001", 0, null)),
            longClickedEvents
        )
    }
}
