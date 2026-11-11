package top.maary.darkbag.ui.viewer.lens

import android.content.Context
import android.content.res.ColorStateList
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import top.maary.darkbag.R
import top.maary.darkbag.models.ImageGroup
import top.maary.darkbag.models.MultiCameraLensItem
import top.maary.darkbag.utils.ImageUtils
import java.util.Locale

/**
 * 多摄镜头选择与胶囊按钮委托类 (ViewerLensSelectorDelegate).
 * 负责多摄镜组解析 (getMultiCameraLenses)、选中镜头映射状态管理，以及多摄切换胶囊按钮的动态构建与交互。
 */
class ViewerLensSelectorDelegate {

    private val selectedLensIndices = mutableMapOf<String, Int>()

    /**
     * 获取多摄镜组列表。优先使用 ImageGroup 内部已缓存的 multiCameraLenses，
     * 否则根据 multiJpgUris 与 multiDngUris 动态解析焦段倍率与 LensTag。
     */
    fun getMultiCameraLenses(group: ImageGroup): List<MultiCameraLensItem> {
        if (!group.isMultiCamera) return emptyList()
        if (group.multiCameraLenses.isNotEmpty()) {
            return group.multiCameraLenses
        }

        val lenses = mutableListOf<MultiCameraLensItem>()
        val maxLen = maxOf(group.multiJpgUris.size, group.multiDngUris.size)
        for (i in 0 until maxLen) {
            val jpg = group.multiJpgUris.getOrNull(i)
            val dng = group.multiDngUris.getOrNull(i)
            val sample = jpg ?: dng
            val tag = if (sample != null) ImageUtils.extractMultiCameraLensTag(sample.toString()) else ""
            val mult = if (sample != null) ImageUtils.extractMultiCameraMultiplier(sample.toString()) else 1.0f
            val effectiveTag = if (tag.isNotEmpty()) tag else String.format(Locale.US, "%.1fx", mult)
            lenses.add(
                MultiCameraLensItem(
                    lensTag = effectiveTag,
                    multiplier = mult,
                    jpgUri = jpg,
                    dngUri = dng
                )
            )
        }
        return lenses.sortedWith(compareBy<MultiCameraLensItem> { it.multiplier }.thenBy { it.lensTag })
    }

    /**
     * 获取指定 baseName 的当前选中镜头索引。
     */
    fun getSelectedLensIndex(baseName: String): Int {
        return selectedLensIndices[baseName] ?: 0
    }

    /**
     * 设置指定 baseName 的当前选中镜头索引。
     */
    fun setSelectedLensIndex(baseName: String, lensIndex: Int) {
        selectedLensIndices[baseName] = lensIndex
    }

    /**
     * 获取指定图像组当前选中的镜组实体。
     */
    fun getActiveLens(group: ImageGroup): MultiCameraLensItem? {
        val lenses = getMultiCameraLenses(group)
        if (lenses.isEmpty()) return null
        val idx = getSelectedLensIndex(group.baseName).coerceIn(0, lenses.size - 1)
        return lenses.getOrNull(idx)
    }

    /**
     * 动态构建多摄切换胶囊按钮并绑定点击回调。
     */
    fun setupMultiCameraPillButtons(
        container: View,
        toggleGroup: ViewGroup,
        group: ImageGroup,
        isUiVisible: Boolean,
        isFormatSwitcherPersistentHidden: Boolean,
        onLensSelected: (Int) -> Unit
    ) {
        val lenses = getMultiCameraLenses(group)
        val shouldShow = isUiVisible && !isFormatSwitcherPersistentHidden && group.isMultiCamera && lenses.size >= 2

        if (!shouldShow) {
            container.visibility = View.GONE
            container.alpha = 0f
            toggleGroup.removeAllViews()
            return
        }

        container.visibility = View.VISIBLE
        container.alpha = 1f
        toggleGroup.removeAllViews()

        val context = toggleGroup.context
        val density = context.resources.displayMetrics.density
        val activeIndex = getSelectedLensIndex(group.baseName).coerceIn(0, lenses.size - 1)

        val colorPrimaryContainer = MaterialColors.getColor(
            context, com.google.android.material.R.attr.colorPrimaryContainer, 0xFF334433.toInt()
        )
        val colorOnPrimaryContainer = MaterialColors.getColor(
            context, com.google.android.material.R.attr.colorOnPrimaryContainer, 0xFFFFFFFF.toInt()
        )
        val colorPrimary = MaterialColors.getColor(
            context, androidx.appcompat.R.attr.colorPrimary, 0xFF88CC88.toInt()
        )
        val colorSurfaceContainerHigh = MaterialColors.getColor(
            context, com.google.android.material.R.attr.colorSurfaceContainerHigh, 0xFF2B2B2B.toInt()
        )
        val colorOnSurfaceVariant = MaterialColors.getColor(
            context, com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFFCCCCCC.toInt()
        )
        val colorOutlineVariant = MaterialColors.getColor(
            context, com.google.android.material.R.attr.colorOutlineVariant, 0xFF444444.toInt()
        )

        for ((idx, lens) in lenses.withIndex()) {
            val isSelected = idx == activeIndex
            val btn = MaterialButton(
                context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                text = lens.lensTag.ifEmpty { String.format(Locale.US, "%.1fx", lens.multiplier) }
                textSize = 12f
                insetTop = 0
                insetBottom = 0
                val padH = (10 * density).toInt()
                val padV = (4 * density).toInt()
                setPadding(padH, padV, padH, padV)
                minHeight = 0
                minimumHeight = (32 * density).toInt()
                cornerRadius = (16 * density).toInt()

                if (isSelected) {
                    backgroundTintList = ColorStateList.valueOf(colorPrimaryContainer)
                    setTextColor(colorOnPrimaryContainer)
                    strokeColor = ColorStateList.valueOf(colorPrimary)
                    strokeWidth = (1.5f * density).toInt()
                } else {
                    backgroundTintList = ColorStateList.valueOf(colorSurfaceContainerHigh)
                    setTextColor(colorOnSurfaceVariant)
                    strokeColor = ColorStateList.valueOf(colorOutlineVariant)
                    strokeWidth = (1f * density).toInt()
                }

                setOnClickListener {
                    setSelectedLensIndex(group.baseName, idx)
                    onLensSelected(idx)
                }

                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    (32 * density).toInt()
                ).apply {
                    val margin = (4 * density).toInt()
                    setMargins(margin, 0, margin, 0)
                }
                layoutParams = lp
            }
            toggleGroup.addView(btn)
        }
    }

    /**
     * 清理所有缓存的镜头选中状态。
     */
    fun clear() {
        selectedLensIndices.clear()
    }
}
