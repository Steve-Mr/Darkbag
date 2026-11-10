package top.maary.darkbag.viewer.editor

import android.content.Context
import android.content.res.ColorStateList
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.slider.Slider
import com.google.android.material.tabs.TabLayout
import top.maary.darkbag.R
import top.maary.darkbag.databinding.FragmentImageViewerBinding
import kotlin.math.abs

/**
 * 图像编辑器视图控制器 (DarkroomEditorController).
 * 负责封装暗房调节面板 (editAdjustmentPanel) 的所有 View 交互、滑块绑定、Tab 切换与 M3 动画。
 * 遵循 Architectural Invariant 4 与 Invariant 5 (Bento Matrix & M3 Expressive 规范).
 */
class DarkroomEditorController(
    private val binding: FragmentImageViewerBinding,
    private val viewModel: DarkroomEditorViewModel,
    private val onViewportPaddingRequested: () -> Unit,
    private val onEditStateChanged: (isEditing: Boolean, isAdjusted: Boolean) -> Unit
) {

    private var isAnimating = false

    fun setup() {
        // Tab 点击绑定
        binding.btnTabLight.setOnClickListener { viewModel.processIntent(DarkroomEditorUserIntent.SelectTab(0)) }
        binding.btnTabRange.setOnClickListener { viewModel.processIntent(DarkroomEditorUserIntent.SelectTab(1)) }
        binding.btnTabTone.setOnClickListener { viewModel.processIntent(DarkroomEditorUserIntent.SelectTab(2)) }
        binding.btnTabColor.setOnClickListener { viewModel.processIntent(DarkroomEditorUserIntent.SelectTab(3)) }

        binding.editTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                tab?.position?.let { viewModel.processIntent(DarkroomEditorUserIntent.SelectTab(it)) }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        // 半格单帧切换
        binding.groupFrameSelectionIntegrated.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val index = if (checkedId == R.id.btn_select_frame1_integrated) 0 else 1
                viewModel.processIntent(DarkroomEditorUserIntent.SelectHalfFrameIndex(index))
            }
        }

        binding.hfSelection1.setOnClickListener {
            viewModel.processIntent(DarkroomEditorUserIntent.SelectHalfFrameIndex(0))
        }

        binding.hfSelection2.setOnClickListener {
            viewModel.processIntent(DarkroomEditorUserIntent.SelectHalfFrameIndex(1))
        }

        // 滑块变动监听
        binding.slider1.addOnChangeListener { _: Slider, value: Float, fromUser: Boolean ->
            if (fromUser) {
                viewModel.processIntent(DarkroomEditorUserIntent.UpdateSlider1(value))
            }
        }

        binding.slider2.addOnChangeListener { _: Slider, value: Float, fromUser: Boolean ->
            if (fromUser) {
                viewModel.processIntent(DarkroomEditorUserIntent.UpdateSlider2(value))
            }
        }

        // 完成与取消
        binding.btnEditDone.setOnClickListener {
            viewModel.processIntent(DarkroomEditorUserIntent.ExitEditMode(apply = true))
        }

        binding.btnEditCancel.setOnClickListener {
            viewModel.processIntent(DarkroomEditorUserIntent.ExitEditMode(apply = false))
        }
    }

    fun render(state: DarkroomEditorUiState) {
        val context = binding.root.context

        // 1. 面板进出场动画与可见性
        if (state.isEditing) {
            if (binding.editAdjustmentPanel.visibility != View.VISIBLE) {
                binding.editAdjustmentPanel.visibility = View.VISIBLE
                binding.editAdjustmentPanel.alpha = 0f
                binding.editAdjustmentPanel.translationX = 0f
                binding.editAdjustmentPanel.translationY = 100f
                binding.editAdjustmentPanel.animate()
                    .alpha(1f)
                    .translationX(0f)
                    .translationY(0f)
                    .setDuration(300)
                    .withEndAction {
                        onViewportPaddingRequested()
                    }
                    .start()
            }
        } else {
            if (binding.editAdjustmentPanel.visibility == View.VISIBLE && !isAnimating) {
                isAnimating = true
                binding.editAdjustmentPanel.animate()
                    .alpha(0f)
                    .translationY(100f)
                    .setDuration(200)
                    .withEndAction {
                        binding.editAdjustmentPanel.visibility = View.GONE
                        isAnimating = false
                        onViewportPaddingRequested()
                    }
                    .start()
            }
        }

        onEditStateChanged(state.isEditing, state.isAdjusted)

        // 2. Tab 激活样式与选中状态
        updateAdjustmentTabButtons(context, state.selectedTab)
        if (binding.editTabs.selectedTabPosition != state.selectedTab) {
            binding.editTabs.getTabAt(state.selectedTab)?.select()
        }

        // 3. 半格单帧选择器
        if (state.isHalfFrame && state.isEditing) {
            binding.groupFrameSelectionIntegrated.visibility = View.VISIBLE
            val expectedCheckId = if (state.selectedHalfFrameIndex == 0) {
                R.id.btn_select_frame1_integrated
            } else {
                R.id.btn_select_frame2_integrated
            }
            if (binding.groupFrameSelectionIntegrated.checkedButtonId != expectedCheckId) {
                binding.groupFrameSelectionIntegrated.check(expectedCheckId)
            }
        } else {
            binding.groupFrameSelectionIntegrated.visibility = View.GONE
        }

        // 4. 半格高亮框覆盖层
        updateSelectionFeedback(state)

        // 5. 滑块 1 状态
        binding.tvLabel1.text = state.slider1Label
        val safeSlider1Value = state.slider1Value.coerceIn(state.slider1Min, state.slider1Max)
        if (binding.slider1.valueFrom > state.slider1Min) binding.slider1.valueFrom = state.slider1Min
        if (binding.slider1.valueTo < state.slider1Max) binding.slider1.valueTo = state.slider1Max
        if (abs(binding.slider1.value - safeSlider1Value) > 0.005f) {
            binding.slider1.value = safeSlider1Value
        }
        binding.slider1.valueFrom = state.slider1Min
        binding.slider1.valueTo = state.slider1Max
        binding.tvValue1.text = state.slider1DisplayValue

        // 6. 滑块 2 状态
        if (state.isSlider2Visible) {
            binding.layoutSlider2.visibility = View.VISIBLE
            binding.tvLabel2.text = state.slider2Label
            val safeSlider2Value = state.slider2Value.coerceIn(state.slider2Min, state.slider2Max)
            if (binding.slider2.valueFrom > state.slider2Min) binding.slider2.valueFrom = state.slider2Min
            if (binding.slider2.valueTo < state.slider2Max) binding.slider2.valueTo = state.slider2Max
            if (abs(binding.slider2.value - safeSlider2Value) > 0.005f) {
                binding.slider2.value = safeSlider2Value
            }
            binding.slider2.valueFrom = state.slider2Min
            binding.slider2.valueTo = state.slider2Max
            binding.tvValue2.text = state.slider2DisplayValue
        } else {
            binding.layoutSlider2.visibility = View.GONE
        }

        // 7. 防抖渲染加载指示器
        if (state.isEditing) {
            binding.initialLoadingIndicator.visibility = if (state.isGeneratingPreview) View.VISIBLE else View.GONE
        }
    }

    private fun updateAdjustmentTabButtons(context: Context, selectedTab: Int) {
        val density = context.resources.displayMetrics.density
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

        val tabs = listOf(
            binding.btnTabLight,
            binding.btnTabRange,
            binding.btnTabTone,
            binding.btnTabColor
        )
        tabs.forEachIndexed { idx, btn ->
            btn ?: return@forEachIndexed
            val isSelected = idx == selectedTab
            if (isSelected) {
                btn.backgroundTintList = ColorStateList.valueOf(colorPrimaryContainer)
                btn.setTextColor(colorOnPrimaryContainer)
                btn.strokeColor = ColorStateList.valueOf(colorPrimary)
                btn.strokeWidth = (1.5f * density).toInt()
                btn.cornerRadius = (10f * density).toInt()
            } else {
                btn.backgroundTintList = ColorStateList.valueOf(colorSurfaceContainerHigh)
                btn.setTextColor(colorOnSurfaceVariant)
                btn.strokeColor = ColorStateList.valueOf(colorOutlineVariant)
                btn.strokeWidth = (1f * density).toInt()
                btn.cornerRadius = (14f * density).toInt()
            }
        }
    }

    private fun updateSelectionFeedback(state: DarkroomEditorUiState) {
        val activeLayout = state.activeConfig?.hfLayout
        val isTB = activeLayout == "TB" || activeLayout?.contains("top", ignoreCase = true) == true

        if (state.isEditing && state.isHalfFrame) {
            binding.hfSelection1.visibility = if (state.selectedHalfFrameIndex == 1) View.VISIBLE else View.GONE
            binding.hfSelection2.visibility = if (state.selectedHalfFrameIndex == 0) View.VISIBLE else View.GONE

            val lp1 = binding.hfSelection1.layoutParams as ConstraintLayout.LayoutParams
            val lp2 = binding.hfSelection2.layoutParams as ConstraintLayout.LayoutParams

            if (isTB) {
                lp1.bottomToBottom = -1
                lp1.bottomToTop = R.id.hf_selection_divider
                lp1.endToEnd = R.id.image_pager
                lp1.endToStart = -1

                lp2.topToTop = -1
                lp2.topToBottom = R.id.hf_selection_divider
                lp2.startToStart = R.id.image_pager
                lp2.startToEnd = -1
            } else {
                lp1.bottomToBottom = R.id.image_pager
                lp1.bottomToTop = -1
                lp1.endToEnd = -1
                lp1.endToStart = R.id.hf_selection_divider

                lp2.topToTop = R.id.image_pager
                lp2.topToBottom = -1
                lp2.startToStart = -1
                lp2.startToEnd = R.id.hf_selection_divider
            }
            binding.hfSelection1.layoutParams = lp1
            binding.hfSelection2.layoutParams = lp2
        } else {
            binding.hfSelection1.visibility = View.GONE
            binding.hfSelection2.visibility = View.GONE
        }
    }
}
