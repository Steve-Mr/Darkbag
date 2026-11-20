package top.maary.darkbag.ui.viewer.playback

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.maary.darkbag.R
import top.maary.darkbag.databinding.ItemImageGroupBinding
import top.maary.darkbag.fragments.ImageViewerAdapter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ViewerPagePlaybackControllerVideoBoundsTest {

    private companion object {
        const val VIEW_WIDTH = 1080
        const val VIEW_HEIGHT = 1920
        const val IMAGE_WIDTH = 4000
        const val IMAGE_HEIGHT = 3000
        const val EPSILON = 0.01f
    }

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.setTheme(R.style.AppTheme)
    }

    @Test
    fun `video transform follows the unclipped image content while zoomed`() {
        val binding = ItemImageGroupBinding.inflate(LayoutInflater.from(context))
        val holder = ImageViewerAdapter.ViewHolder(binding)
        layout(binding.imageView)
        layout(binding.videoView)
        binding.imageView.setImageDrawable(FixedSizeDrawable(IMAGE_WIDTH, IMAGE_HEIGHT))

        // 1.25x: the image is wider than the viewport but still shorter than it.
        val zoom = 1.25f
        val totalScale = (VIEW_WIDTH.toFloat() / IMAGE_WIDTH) * zoom
        binding.imageView.restoreZoomState(
            Matrix().apply {
                setScale(totalScale, totalScale)
                postTranslate(-135f, 0f)
            },
            zoom
        )

        val controller = ViewerPagePlaybackController(
            context = context,
            scope = CoroutineScope(Dispatchers.Unconfined),
            getRecyclerView = { null },
            getGroupAt = { null },
            getBackingList = { emptyList() },
            loadCinemaDngFrame = { _, _, _, _ -> },
            onCinemaDngFrameSelected = null
        )

        controller.updateVideoViewBounds(holder)

        val geometry = requireNotNull(binding.imageView.currentDisplayGeometry())
        val actualValues = FloatArray(9).also {
            binding.videoView.getTransform(Matrix()).getValues(it)
        }

        // The whole image content rect drives the texture transform, so the video
        // keeps following the 1.25x zoom on both axes instead of being clamped by
        // the viewport on X only.
        assertEquals(1.25f, actualValues[Matrix.MSCALE_X], EPSILON)
        assertEquals(0f, actualValues[Matrix.MSKEW_X], EPSILON)
        assertEquals(-135f, actualValues[Matrix.MTRANS_X], EPSILON)
        assertEquals(0f, actualValues[Matrix.MSKEW_Y], EPSILON)
        assertEquals(1012.5f / 1920f, actualValues[Matrix.MSCALE_Y], EPSILON)
        assertEquals(0f, actualValues[Matrix.MTRANS_Y], EPSILON)

        assertEquals(geometry.outlineRect.left.toInt(), holder.videoOutlineRect.left)
        assertEquals(geometry.outlineRect.top.toInt(), holder.videoOutlineRect.top)
        assertEquals(geometry.outlineRect.right.toInt(), holder.videoOutlineRect.right)
        assertEquals(geometry.outlineRect.bottom.toInt(), holder.videoOutlineRect.bottom)
        assertEquals(geometry.outlineRadius, holder.videoOutlineRadius, EPSILON)
    }

    private fun layout(view: View) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(VIEW_WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(VIEW_HEIGHT, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, VIEW_WIDTH, VIEW_HEIGHT)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    private class FixedSizeDrawable(
        private val width: Int,
        private val height: Int
    ) : Drawable() {
        override fun draw(canvas: Canvas) = Unit
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        override fun getOpacity(): Int = PixelFormat.OPAQUE
        override fun getIntrinsicWidth(): Int = width
        override fun getIntrinsicHeight(): Int = height
    }
}
