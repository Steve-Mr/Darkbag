package top.maary.darkbag.ui.viewer.playback

import android.graphics.Matrix
import android.graphics.RectF

/**
 * 计算 TextureView 视频帧到查看器坐标系的可视变换。
 *
 * 视频纹理默认会被拉伸铺满整个 TextureView (viewWidth × viewHeight)，
 * 因此必须把完整视频帧映射到图像未经视口裁切的 contentRect 上，
 * 才能让播放中的视频与底层图像保持完全一致的等比缩放与平移。
 */
internal object VideoSurfaceTransform {

    fun compute(contentRect: RectF, viewWidth: Float, viewHeight: Float): Matrix {
        val matrix = Matrix()
        if (viewWidth <= 0f || viewHeight <= 0f) return matrix
        matrix.setScale(contentRect.width() / viewWidth, contentRect.height() / viewHeight)
        matrix.postTranslate(contentRect.left, contentRect.top)
        return matrix
    }
}
