package top.maary.darkbag.fragments

import android.content.Context
import android.content.res.Configuration
import android.view.LayoutInflater
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.button.MaterialButton
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.maary.darkbag.R
import top.maary.darkbag.databinding.FragmentImageViewerBinding
import top.maary.darkbag.databinding.FragmentPlaygroundGalleryBinding
import top.maary.darkbag.databinding.ItemPlaygroundImageBinding
import top.maary.darkbag.ui.bento.BentoContainerRole
import top.maary.darkbag.ui.bento.BentoTrayCard
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PlaygroundLayoutAndTopBarTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.setTheme(R.style.AppTheme)
    }

    @Test
    fun testTopBarConstraintLayoutRightAlignmentWhenGalleryPillGone() {
        val binding = FragmentImageViewerBinding.inflate(LayoutInflater.from(context))

        val topBarCard = binding.topBarContainer
        assertNotNull(topBarCard)

        val innerLayout = topBarCard.getChildAt(0)
        assertTrue("Top bar inner layout must be ConstraintLayout", innerLayout is ConstraintLayout)
        val constraintLayout = innerLayout as ConstraintLayout

        val btnBack = binding.btnBack
        val btnGalleryPill = binding.btnGalleryPill
        val btnDelete = binding.btnDelete
        val btnActionMain = binding.btnActionMain

        val backParams = btnBack.layoutParams as ConstraintLayout.LayoutParams
        val pillParams = btnGalleryPill.layoutParams as ConstraintLayout.LayoutParams
        val deleteParams = btnDelete.layoutParams as ConstraintLayout.LayoutParams
        val actionParams = btnActionMain.layoutParams as ConstraintLayout.LayoutParams

        // Verify start/end anchor constraints
        assertEquals(ConstraintLayout.LayoutParams.PARENT_ID, backParams.startToStart)
        assertEquals(ConstraintLayout.LayoutParams.PARENT_ID, actionParams.endToEnd)
        assertEquals(btnActionMain.id, deleteParams.endToStart)

        assertEquals(btnBack.id, pillParams.startToEnd)
        assertEquals(btnDelete.id, pillParams.endToStart)

        // Simulate PlaygroundViewerFragment where isGallerySupported = false -> btnGalleryPill is GONE
        btnGalleryPill.visibility = View.GONE
        assertEquals(View.GONE, btnGalleryPill.visibility)

        // In ConstraintLayout, hiding btnGalleryPill does not affect btnDelete or btnActionMain
        assertEquals(ConstraintLayout.LayoutParams.PARENT_ID, actionParams.endToEnd)
        assertEquals(btnActionMain.id, deleteParams.endToStart)
    }

    @Test
    fun testPlaygroundDynamicAspectRatioLayoutParams() {
        val binding = ItemPlaygroundImageBinding.inflate(LayoutInflater.from(context))
        val imageView = binding.imageViewThumbnail

        // Landscape ratio: 4000:3000
        val landscapeRatio = "4000:3000"
        val landscapeItem = PlaygroundItem.Single(File("/fake/landscape.dng"), aspectRatio = landscapeRatio)
        assertEquals(landscapeRatio, landscapeItem.aspectRatio)

        val layoutParams = imageView.layoutParams as ConstraintLayout.LayoutParams
        layoutParams.dimensionRatio = landscapeItem.aspectRatio
        layoutParams.height = 0
        imageView.layoutParams = layoutParams

        assertEquals("4000:3000", (imageView.layoutParams as ConstraintLayout.LayoutParams).dimensionRatio)
        assertEquals(0, (imageView.layoutParams as ConstraintLayout.LayoutParams).height)

        // Portrait ratio: 3000:4000
        val portraitRatio = "3000:4000"
        val portraitItem = PlaygroundItem.Single(File("/fake/portrait.dng"), aspectRatio = portraitRatio)
        assertEquals(portraitRatio, portraitItem.aspectRatio)

        layoutParams.dimensionRatio = portraitItem.aspectRatio
        layoutParams.height = 0
        imageView.layoutParams = layoutParams

        assertEquals("3000:4000", (imageView.layoutParams as ConstraintLayout.LayoutParams).dimensionRatio)
        assertEquals(0, (imageView.layoutParams as ConstraintLayout.LayoutParams).height)

        // Fallback null ratio: wrap content
        val fallbackItem = PlaygroundItem.Single(File("/fake/fallback.dng"), aspectRatio = null)
        assertNull(fallbackItem.aspectRatio)

        layoutParams.dimensionRatio = null
        layoutParams.height = ConstraintLayout.LayoutParams.WRAP_CONTENT
        imageView.layoutParams = layoutParams

        assertNull((imageView.layoutParams as ConstraintLayout.LayoutParams).dimensionRatio)
        assertEquals(ConstraintLayout.LayoutParams.WRAP_CONTENT, (imageView.layoutParams as ConstraintLayout.LayoutParams).height)
    }

    @Test
    fun testPlaygroundStaggeredGridSpanCount() {
        val portraitConfig = Configuration().apply { orientation = Configuration.ORIENTATION_PORTRAIT }
        val landscapeConfig = Configuration().apply { orientation = Configuration.ORIENTATION_LANDSCAPE }

        val portraitSpanCount = if (portraitConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) 3 else 2
        val landscapeSpanCount = if (landscapeConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) 3 else 2

        assertEquals(2, portraitSpanCount)
        assertEquals(3, landscapeSpanCount)

        val layoutManager = StaggeredGridLayoutManager(portraitSpanCount, StaggeredGridLayoutManager.VERTICAL).apply {
            gapStrategy = StaggeredGridLayoutManager.GAP_HANDLING_NONE
        }
        assertEquals(2, layoutManager.spanCount)
        assertEquals(StaggeredGridLayoutManager.GAP_HANDLING_NONE, layoutManager.gapStrategy)
    }

    @Test
    fun testPlaygroundGalleryBentoParity() {
        val binding = FragmentPlaygroundGalleryBinding.inflate(LayoutInflater.from(context))

        // Top bento card
        assertNotNull(binding.playgroundTopBentoCard)
        assertTrue(
            "playgroundTopBentoCard must be BentoTrayCard",
            binding.playgroundTopBentoCard is BentoTrayCard
        )
        val topTray = binding.playgroundTopBentoCard as BentoTrayCard
        assertEquals(BentoContainerRole.SURFACE_CONTAINER, topTray.containerRole)

        // Bottom bento dock
        assertNotNull(binding.playgroundBentoDock)
        assertTrue(
            "playgroundBentoDock must be BentoTrayCard",
            binding.playgroundBentoDock is BentoTrayCard
        )
        val dockTray = binding.playgroundBentoDock as BentoTrayCard
        assertEquals(BentoContainerRole.SURFACE_CONTAINER_LOW, dockTray.containerRole)

        // Test dynamic mode reactivity
        dockTray.setContainerRole(BentoContainerRole.SECONDARY_CONTAINER, animated = false)
        assertEquals(BentoContainerRole.SECONDARY_CONTAINER, dockTray.containerRole)

        // Verify all required buttons and text views
        assertNotNull(binding.btnPlaygroundBack)
        assertNotNull(binding.tvPlaygroundTitle)
        assertNotNull(binding.tvPlaygroundSubtitle)
        assertNotNull(binding.btnPlaygroundSelectAll)
        assertNotNull(binding.btnPlaygroundSettings)
        assertNotNull(binding.recyclerView)
        assertNotNull(binding.emptyStateText)
        assertNotNull(binding.fabAdd)
        assertNotNull(binding.btnMerge)
        assertNotNull(binding.btnDisband)
        assertNotNull(binding.btnExport)
        assertNotNull(binding.btnDelete)
    }

    @Test
    fun testImageViewerBentoDeckAndAdjustmentIslandParity() {
        val binding = FragmentImageViewerBinding.inflate(LayoutInflater.from(context))

        // Bottom modular bento deck
        assertNotNull(binding.viewerBentoDeck)
        assertTrue(
            "viewerBentoDeck must be BentoTrayCard",
            binding.viewerBentoDeck is BentoTrayCard
        )
        val viewerDeck = binding.viewerBentoDeck as BentoTrayCard
        assertEquals(BentoContainerRole.SURFACE_CONTAINER_LOW, viewerDeck.containerRole)

        // Test dynamic mode reactivity (Half-frame -> Tertiary, Multi-camera -> Secondary, Normal -> SurfaceContainerLow)
        viewerDeck.setContainerRole(BentoContainerRole.TERTIARY_CONTAINER, animated = false)
        assertEquals(BentoContainerRole.TERTIARY_CONTAINER, viewerDeck.containerRole)

        viewerDeck.setContainerRole(BentoContainerRole.SECONDARY_CONTAINER, animated = false)
        assertEquals(BentoContainerRole.SECONDARY_CONTAINER, viewerDeck.containerRole)

        viewerDeck.setContainerRole(BentoContainerRole.SURFACE_CONTAINER_LOW, animated = false)
        assertEquals(BentoContainerRole.SURFACE_CONTAINER_LOW, viewerDeck.containerRole)

        // Adjustment island
        assertNotNull(binding.adjustmentIsland)
        assertTrue(
            "adjustmentIsland must be BentoTrayCard",
            binding.adjustmentIsland is BentoTrayCard
        )

        // Gallery bottom bar
        assertNotNull(binding.galleryBottomBar)
        assertTrue(
            "galleryBottomBar must be BentoTrayCard",
            binding.galleryBottomBar is BentoTrayCard
        )
    }
}
