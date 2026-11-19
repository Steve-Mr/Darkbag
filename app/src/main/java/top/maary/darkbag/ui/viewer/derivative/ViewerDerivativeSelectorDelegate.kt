package top.maary.darkbag.ui.viewer.derivative

import android.content.Context
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import top.maary.darkbag.R
import top.maary.darkbag.models.ImageGroup

/**
 * 衍生版本选择与指示器委托类 (ViewerDerivativeSelectorDelegate).
 * 封装派生 JPG/MP4 版本索引计算、版本轮换 (cycleDerivativeVersion)、选中态映射表管理以及指示器原点视图同步。
 */
class ViewerDerivativeSelectorDelegate {

    private val selectedDerivativeIndices = mutableMapOf<String, Int>()

    /**
     * 获取指定图像组的所有衍生版本 URI 列表（包括主 JPG/MP4 及派生版本）。
     */
    fun getDerivativeUris(group: ImageGroup): List<Uri> {
        val list = mutableListOf<Uri>()
        group.jpgUri?.let { list.add(it) }
        for (u in group.derivativeJpgUris) {
            if (u !in list) list.add(u)
        }
        group.mp4VideoUri?.let { if (it !in list) list.add(it) }
        for (u in group.derivativeMp4Uris) {
            if (u !in list) list.add(u)
        }
        return list
    }

    /**
     * 获取指定 baseName 的当前选中衍生版本索引。
     */
    fun getSelectedDerivativeIndex(baseName: String): Int {
        return selectedDerivativeIndices[baseName] ?: 0
    }

    /**
     * 设置指定 baseName 的当前选中衍生版本索引。
     */
    fun setSelectedDerivativeIndex(baseName: String, index: Int) {
        selectedDerivativeIndices[baseName] = index
    }

    /**
     * 获取当前处于激活状态的衍生版本 URI。
     */
    fun getActiveDerivativeUri(group: ImageGroup): Uri? {
        val derivatives = getDerivativeUris(group)
        val activeIndex = getSelectedDerivativeIndex(group.baseName).coerceIn(0, (derivatives.size - 1).coerceAtLeast(0))
        return derivatives.getOrNull(activeIndex) ?: group.jpgUri ?: group.mp4VideoUri
    }

    /**
     * 轮换下一个衍生版本。
     * 若该组包含至少 2 个衍生版本，则推进至下一个索引并返回 (新索引, 新URI)，否则返回 null。
     */
    fun cycleDerivativeVersion(group: ImageGroup): Pair<Int, Uri>? {
        val derivatives = getDerivativeUris(group)
        if (derivatives.size < 2) return null
        val nextIdx = ((selectedDerivativeIndices[group.baseName] ?: 0) + 1) % derivatives.size
        selectedDerivativeIndices[group.baseName] = nextIdx
        val nextUri = derivatives[nextIdx]
        return Pair(nextIdx, nextUri)
    }

    /**
     * 检查当前激活的衍生项是否为 MP4 视频。
     */
    fun isMp4Active(group: ImageGroup, isRawSelected: Boolean): Boolean {
        if (isRawSelected) return false
        val activeUri = getActiveDerivativeUri(group)
        return activeUri != null && (activeUri.toString().endsWith(".mp4", ignoreCase = true) || group.isMp4Video)
    }

    /**
     * 同步衍生指示器小圆点布局状态。
     */
    fun syncDerivativeDots(
        container: View,
        dotsGroup: ViewGroup,
        group: ImageGroup,
        isUiVisible: Boolean,
        isFormatSwitcherPersistentHidden: Boolean,
        isRawSelected: Boolean,
        onDotSelected: ((Int) -> Unit)? = null
    ) {
        val derivatives = getDerivativeUris(group)
        val hasDots = !group.isMultiCamera && !isRawSelected && derivatives.size >= 2
        val shouldShow = isUiVisible && !isFormatSwitcherPersistentHidden && hasDots

        if (!shouldShow) {
            container.visibility = View.GONE
            container.alpha = 0f
            dotsGroup.removeAllViews()
            return
        }

        container.visibility = View.VISIBLE
        container.alpha = 1f
        dotsGroup.removeAllViews()

        val context = dotsGroup.context
        val activeIndex = getSelectedDerivativeIndex(group.baseName).coerceIn(0, derivatives.size - 1)
        val density = context.resources.displayMetrics.density
        val dotSize = (6 * density).toInt()
        val dotMargin = (3 * density).toInt()

        for (i in derivatives.indices) {
            val dot = ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(dotSize, dotSize).apply {
                    setMargins(dotMargin, 0, dotMargin, 0)
                }
                setImageResource(R.drawable.ic_look_dot)
                val isCurrent = i == activeIndex
                val tintColor = if (isCurrent) {
                    ContextCompat.getColor(context, android.R.color.white)
                } else {
                    ContextCompat.getColor(context, com.google.android.material.R.color.material_on_surface_disabled)
                }
                setColorFilter(tintColor)
                alpha = if (isCurrent) 1.0f else 0.4f
                if (onDotSelected != null) {
                    setOnClickListener {
                        setSelectedDerivativeIndex(group.baseName, i)
                        onDotSelected(i)
                    }
                }
            }
            dotsGroup.addView(dot)
        }
    }

    /**
     * 清理所有缓存的索引状态。
     */
    fun clear() {
        selectedDerivativeIndices.clear()
    }
}
