package top.maary.darkbag.fragments.gallery

import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding

/**
 * 画廊模块通用的 Edge-to-Edge WindowInsets 辅助工具 (GalleryEdgeToEdgeHelper).
 * 统一 DarkroomGallery 与 PlaygroundGallery 的悬浮 Bento 顶栏、底栏与列表滚动安全留白。
 */
object GalleryEdgeToEdgeHelper {

    fun applyFloatingGalleryInsets(
        rootView: View,
        recyclerView: View,
        topHeaderView: View,
        bottomDockView: View,
        bottomAppBarView: View? = null,
        topClearanceDp: Int = 76,
        bottomClearanceDp: Int = 88,
        applyTopPaddingOnHeaderInsteadOfMargin: Boolean = false,
        applyHorizontalInsets: Boolean = true,
        elevateTopHeader: Boolean = false
    ) {
        val density = rootView.resources.displayMetrics.density
        val topClearancePx = (topClearanceDp * density).toInt()
        val bottomClearancePx = (bottomClearanceDp * density).toInt()
        val margin8 = (8 * density).toInt()
        val margin12 = (12 * density).toInt()

        if (elevateTopHeader) {
            topHeaderView.translationZ = 16f * density
        }
        if (applyTopPaddingOnHeaderInsteadOfMargin) {
            recyclerView.updatePadding(
                top = topClearancePx,
                bottom = bottomClearancePx
            )
        }

        ViewCompat.setOnApplyWindowInsetsListener(rootView) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())

            if (applyTopPaddingOnHeaderInsteadOfMargin) {
                topHeaderView.updatePadding(top = systemBars.top)
            } else {
                topHeaderView.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    topMargin = systemBars.top + margin8
                    if (applyHorizontalInsets) {
                        marginStart = systemBars.left + margin12
                        marginEnd = systemBars.right + margin12
                    }
                }
            }

            if (applyHorizontalInsets) {
                recyclerView.updatePadding(
                    top = systemBars.top + topClearancePx,
                    bottom = systemBars.bottom + bottomClearancePx,
                    left = systemBars.left + margin8,
                    right = systemBars.right + margin8
                )
            } else {
                recyclerView.updatePadding(
                    top = systemBars.top + topClearancePx,
                    bottom = systemBars.bottom + bottomClearancePx
                )
            }

            bottomDockView.translationY = 0f
            bottomDockView.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = systemBars.bottom + margin12
                if (applyHorizontalInsets) {
                    marginStart = systemBars.left + margin12
                    marginEnd = systemBars.right + margin12
                }
            }

            bottomAppBarView?.updatePadding(bottom = systemBars.bottom)
            insets
        }
    }
}
