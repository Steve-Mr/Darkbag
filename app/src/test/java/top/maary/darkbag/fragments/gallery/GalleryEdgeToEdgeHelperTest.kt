package top.maary.darkbag.fragments.gallery

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.maary.darkbag.R
import top.maary.darkbag.databinding.FragmentDarkroomGalleryBinding
import top.maary.darkbag.databinding.FragmentPlaygroundGalleryBinding

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GalleryEdgeToEdgeHelperTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.setTheme(R.style.AppTheme)
    }

    @Test
    fun testApplyFloatingGalleryInsets_darkroomGalleryMode() {
        val binding = FragmentDarkroomGalleryBinding.inflate(LayoutInflater.from(context))
        val density = context.resources.displayMetrics.density

        GalleryEdgeToEdgeHelper.applyFloatingGalleryInsets(
            rootView = binding.root,
            recyclerView = binding.galleryRecyclerView,
            topHeaderView = binding.galleryHeaderCard,
            bottomDockView = binding.galleryBottomBar,
            topClearanceDp = 76,
            bottomClearanceDp = 88,
            applyTopPaddingOnHeaderInsteadOfMargin = false,
            applyHorizontalInsets = true,
            elevateTopHeader = false
        )

        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(10, 40, 20, 60))
            .build()
        ViewCompat.dispatchApplyWindowInsets(binding.root, insets)

        val headerLp = binding.galleryHeaderCard.layoutParams as ViewGroup.MarginLayoutParams
        assertEquals(40 + (8 * density).toInt(), headerLp.topMargin)
        assertEquals(10 + (12 * density).toInt(), headerLp.marginStart)
        assertEquals(20 + (12 * density).toInt(), headerLp.marginEnd)

        assertEquals(40 + (76 * density).toInt(), binding.galleryRecyclerView.paddingTop)
        assertEquals(60 + (88 * density).toInt(), binding.galleryRecyclerView.paddingBottom)

        val dockLp = binding.galleryBottomBar.layoutParams as ViewGroup.MarginLayoutParams
        assertEquals(60 + (12 * density).toInt(), dockLp.bottomMargin)
        assertEquals(0f, binding.galleryBottomBar.translationY)
    }

    @Test
    fun testApplyFloatingGalleryInsets_playgroundGalleryMode() {
        val binding = FragmentPlaygroundGalleryBinding.inflate(LayoutInflater.from(context))
        val density = context.resources.displayMetrics.density

        GalleryEdgeToEdgeHelper.applyFloatingGalleryInsets(
            rootView = binding.root,
            recyclerView = binding.recyclerView,
            topHeaderView = binding.appBarLayout,
            bottomDockView = binding.playgroundBentoDock,
            bottomAppBarView = binding.bottomAppBar,
            topClearanceDp = 68,
            bottomClearanceDp = 88,
            applyTopPaddingOnHeaderInsteadOfMargin = true,
            applyHorizontalInsets = false,
            elevateTopHeader = true
        )

        assertEquals(16f * density, binding.appBarLayout.translationZ)

        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, 50, 0, 70))
            .build()
        ViewCompat.dispatchApplyWindowInsets(binding.root, insets)

        assertEquals(50, binding.appBarLayout.paddingTop)
        assertEquals(50 + (68 * density).toInt(), binding.recyclerView.paddingTop)
        assertEquals(70 + (88 * density).toInt(), binding.recyclerView.paddingBottom)
        assertEquals(70, binding.bottomAppBar.paddingBottom)
    }
}
