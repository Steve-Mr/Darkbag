package top.maary.darkbag.viewer.editor

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import top.maary.darkbag.R
import top.maary.darkbag.databinding.FragmentImageViewerBinding
import top.maary.darkbag.models.EditConfig
import top.maary.darkbag.models.ImageGroup
import top.maary.darkbag.ui.CameraBentoDeckView
import top.maary.darkbag.ui.ColorRecipeBottomSheet
import top.maary.darkbag.utils.LutManager

/**
 * 暗房调节面板视图委托 (DarkroomEditorPanelDelegate).
 * 封装暗房调节面板、滑块监听、效果按钮遥测同步、半格选区高亮反馈以及 LUT 胶囊弹窗/底部调色盘的交互分发。
 * 遵循 Invariant 4 (零 UI 阻塞与 MVI 架构) 与 Invariant 5 (Bento Matrix & Porcelain 胶囊设计)。
 */
class DarkroomEditorPanelDelegate(
    private val binding: FragmentImageViewerBinding,
    private val viewModel: DarkroomEditorViewModel,
    private val layoutInflater: LayoutInflater,
    private val lifecycleScope: CoroutineScope,
    private val ensureDngBytesLoaded: suspend () -> Unit,
    private val onMarkAdjusted: () -> Unit,
    private val onViewportPaddingRequested: () -> Unit,
    private val onEditStateChanged: (isEditing: Boolean, isAdjusted: Boolean) -> Unit,
    private val onBackPressedCallbackStateChanged: () -> Unit
) {

    private var editorController: DarkroomEditorController? = null
    var userExplicitlyChangedLog: Boolean = false

    fun setup() {
        val controller = DarkroomEditorController(
            binding = binding,
            viewModel = viewModel,
            onViewportPaddingRequested = onViewportPaddingRequested,
            onEditStateChanged = onEditStateChanged
        )
        controller.setup()
        editorController = controller

        binding.touchOverlay.setOnClickListener {
            dismissPillPopup()
        }

        binding.btnTimestamp.setOnClickListener {
            lifecycleScope.launch {
                ensureDngBytesLoaded()
                viewModel.processIntent(DarkroomEditorUserIntent.ToggleTimestamp)
                onMarkAdjusted()
            }
        }

        binding.btnFlare.setOnClickListener {
            lifecycleScope.launch {
                ensureDngBytesLoaded()
                val current = viewModel.uiState.value.activeConfig
                val nextFlare = when (current?.flareType) {
                    -1 -> 1
                    1 -> 2
                    2 -> -1
                    else -> 1
                }
                viewModel.processIntent(DarkroomEditorUserIntent.SetFlareType(nextFlare))
                onMarkAdjusted()
            }
        }

        binding.btnSwap.setOnClickListener {
            lifecycleScope.launch {
                ensureDngBytesLoaded()
                viewModel.processIntent(DarkroomEditorUserIntent.ToggleSwap)
                onMarkAdjusted()
            }
        }

        binding.btnAdjustTofu.setOnClickListener {
            enterEditMode()
        }

        binding.btn3dLut.setOnClickListener {
            viewModel.uiState.value.activeConfig?.let {
                // Trigger LUT menu
            }
        }

        binding.btnEditRecipe?.setOnClickListener {
            // Trigger recipe menu
        }
    }

    fun render(state: DarkroomEditorUiState) {
        editorController?.render(state)
        updateEditUi(state.activeConfig, state.isAdjusted)
    }

    fun updateEditUi(config: EditConfig?, isAdjusted: Boolean) {
        val ctx = binding.root.context
        val logName = if (config != null && config.log != null && config.log != "None") config.log else "None"
        val lutName = if (config != null && config.lut != null && config.lut != "None") config.lut.substringBeforeLast(".") else "None"

        binding.btn3dLut.text = CameraBentoDeckView.formatProTelemetry(
            label = "LUT",
            value = lutName,
            isTabActive = false,
            isValueManual = lutName != "None",
            context = ctx
        )
        binding.btnAdjustTofu.text = CameraBentoDeckView.formatProTelemetry(
            label = "ADJUST",
            value = if (isAdjusted) "ACTIVE" else "LIGHT & COLOR",
            isTabActive = false,
            isValueManual = isAdjusted,
            context = ctx
        )
        binding.btnEditRecipe?.text = CameraBentoDeckView.formatProTelemetry(
            label = "RECIPE",
            value = if (lutName != "None") lutName else logName,
            isTabActive = false,
            isValueManual = (logName != "None" || lutName != "None"),
            context = ctx
        )
        binding.btnSeparateDng.text = CameraBentoDeckView.formatProTelemetry(
            label = "SPLIT",
            value = "HALF FRAME",
            isTabActive = false,
            isValueManual = false,
            context = ctx
        )

        val activeLayout = config?.hfLayout
        val isTB = activeLayout == "TB" || activeLayout?.contains("top", ignoreCase = true) == true
        if (config != null) {
            updateEffectsButtons(config, isTB)
        }
    }

    fun updateEffectsButtons(config: EditConfig, isTB: Boolean) {
        val ctx = binding.root.context
        val iconRes = when (config.flareType) {
            1 -> R.drawable.ic_flare_side
            2 -> R.drawable.ic_flare_corner
            else -> R.drawable.ic_flare_none
        }

        binding.btnTimestamp.text = CameraBentoDeckView.formatProTelemetry(
            label = "DATE",
            value = if (config.showTimestamp) "ON" else "OFF",
            isTabActive = false,
            isValueManual = config.showTimestamp,
            context = ctx
        )
        binding.btnTimestamp.setIconTintResource(if (config.showTimestamp) R.color.vibrant_orange else android.R.color.white)
        binding.btnTimestamp.alpha = if (config.showTimestamp) 1.0f else 0.6f

        val flareSub = when (config.flareType) {
            1 -> "LEAK #1"
            2 -> "LEAK #2"
            else -> "OFF"
        }
        binding.btnFlare.text = CameraBentoDeckView.formatProTelemetry(
            label = "FLARE",
            value = flareSub,
            isTabActive = false,
            isValueManual = config.flareType != -1,
            context = ctx
        )
        binding.btnFlare.setIconTintResource(if (config.flareType != -1) R.color.vibrant_pink else android.R.color.white)
        binding.btnFlare.setIconResource(iconRes)
        binding.btnFlare.alpha = if (config.flareType != -1) 1.0f else 0.6f

        binding.btnSwap.text = CameraBentoDeckView.formatProTelemetry(
            label = "⇄ SWAP",
            value = if (isTB) "T / B" else "F1 / F2",
            isTabActive = false,
            isValueManual = config.isSwapped,
            context = ctx
        )
        binding.btnSwap.setIconResource(if (isTB) R.drawable.ic_swap_vert else R.drawable.ic_swap_horiz)
        binding.btnSwap.setIconTintResource(if (config.isSwapped) R.color.vibrant_cyan else android.R.color.white)
        binding.btnSwap.alpha = if (config.isSwapped) 1.0f else 0.6f
    }

    fun showLutMenu(currentGroup: ImageGroup) {
        val context = binding.root.context
        val lutManager = LutManager(context)
        val activeConfig = viewModel.uiState.value.activeConfig ?: currentGroup.editConfig
        val initialLog = activeConfig?.log ?: "None"
        val initialLut = activeConfig?.lut
        val baselineLog = currentGroup.editConfig?.log ?: initialLog

        ColorRecipeBottomSheet.show(
            context = context,
            lutManager = lutManager,
            initialLog = initialLog,
            initialLut = initialLut,
            baselineLog = baselineLog,
            userExplicitlySelectedLog = userExplicitlyChangedLog,
            persistToPrefs = false,
            onLogExplicitlyChanged = { explicitlyChanged ->
                userExplicitlyChangedLog = explicitlyChanged
            }
        ) { selectedLog, selectedLut ->
            lifecycleScope.launch {
                ensureDngBytesLoaded()
                viewModel.processIntent(DarkroomEditorUserIntent.SetLog(selectedLog))
                viewModel.processIntent(DarkroomEditorUserIntent.SetLut(selectedLut))
                onMarkAdjusted()
            }
        }
    }

    fun showPillPopup(items: List<Pair<String, Boolean>>, autoDismiss: Boolean = true, onSelected: (String, Int) -> Unit) {
        val container = binding.lutListContainer
        val rv = binding.lutList

        binding.touchOverlay.visibility = View.VISIBLE
        container.visibility = View.VISIBLE
        rv.layoutManager = LinearLayoutManager(binding.root.context)
        rv.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            inner class PillViewHolder(val btn: MaterialButton) : RecyclerView.ViewHolder(btn)

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val btn = layoutInflater.inflate(R.layout.item_popup_pill, parent, false) as MaterialButton
                return PillViewHolder(btn)
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val pillHolder = holder as PillViewHolder
                val (title, isEnabled) = items[position]
                pillHolder.btn.text = title
                pillHolder.btn.isEnabled = isEnabled
                pillHolder.btn.alpha = if (isEnabled) 1.0f else 0.5f
                pillHolder.btn.setOnClickListener {
                    onSelected(title, position)
                    if (autoDismiss) {
                        dismissPillPopup()
                    }
                }
            }
            override fun getItemCount() = items.size
        }
    }

    fun dismissPillPopup(): Boolean {
        val container = binding.lutListContainer
        val isVisible = container.visibility == View.VISIBLE
        if (isVisible) {
            container.visibility = View.GONE
            binding.touchOverlay.visibility = View.GONE
            onBackPressedCallbackStateChanged()
            return true
        }
        return false
    }

    fun enterEditMode(group: ImageGroup? = null) {
        lifecycleScope.launch {
            ensureDngBytesLoaded()
            binding.viewerBentoDeck.visibility = View.GONE
            val (b1, b2) = viewModel.getCachedDngBytes()
            val targetGroup = group ?: viewModel.uiState.value.originalConfig?.let {
                // Use active or provided group
                null
            }
            if (group != null) {
                viewModel.processIntent(DarkroomEditorUserIntent.EnterEditMode(group, b1, b2))
            }
        }
    }

    fun enterEditModeWithGroup(group: ImageGroup, bytes1: ByteArray?, bytes2: ByteArray?) {
        binding.viewerBentoDeck.visibility = View.GONE
        viewModel.processIntent(DarkroomEditorUserIntent.EnterEditMode(group, bytes1, bytes2))
    }

    fun exitEditMode(apply: Boolean) {
        viewModel.processIntent(DarkroomEditorUserIntent.ExitEditMode(apply))
    }

    fun selectAdjustmentTab(index: Int) {
        viewModel.processIntent(DarkroomEditorUserIntent.SelectTab(index))
    }

    fun onDestroy() {
        editorController = null
    }
}
