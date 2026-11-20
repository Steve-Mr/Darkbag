package top.maary.darkbag.ui

import android.graphics.RectF

/**
 * 图像在查看器视口中的几何快照 (ImageDisplayGeometry).
 *
 * 这是 [ZoomableImageView] 对外暴露的唯一几何契约，用于解耦「画面变换」与「可视边界」两种职责：
 * - [contentRect]: 完整图像经当前缩放/平移矩阵映射后的边界，允许超出视口，视频纹理必须使用该矩形等比对齐；
 * - [visibleRect]: [contentRect] 与视口的交集，仅用于浮动控件锚定等可视区域计算；
 * - [outlineRect] / [outlineRadius]: 与图像卡片完全一致的圆角轮廓，随缩放平滑过渡为全屏直角。
 *
 * 调用方不得修改其中的 [RectF] 实例；[ImageDisplayGeometry.copy] 为浅拷贝，快照仅在主线程内同步消费。
 */
data class ImageDisplayGeometry(
    val contentRect: RectF,
    val visibleRect: RectF,
    val outlineRect: RectF,
    val outlineRadius: Float
)
