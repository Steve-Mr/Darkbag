package top.maary.darkbag.viewer.chrome

import android.content.Context
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.FragmentManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import top.maary.darkbag.R
import top.maary.darkbag.databinding.FragmentImageViewerBinding
import top.maary.darkbag.fragments.HalfFrameShareSheet
import top.maary.darkbag.fragments.ImageViewerAdapter
import top.maary.darkbag.fragments.MultiCameraCollageSheet
import top.maary.darkbag.models.ImageGroup
import top.maary.darkbag.viewer.metadata.ViewerMetadataSheet
import top.maary.darkbag.viewer.strategy.ViewerMediaStrategy
import top.maary.darkbag.viewer.strategy.ViewerSaveAction

/**
 * 查看器外观栏、Edge-to-Edge WindowInsets 与交互菜单代理 (ViewerChromeAndInsetsDelegate).
 * 统一管理状态栏/导航栏安全留白、顶底栏显隐动画、工具栏主操作按钮、弹窗触发及返回键拦截状态。
 */
class ViewerChromeAndInsetsDelegate(
    private val context: Context,
    private val binding: FragmentImageViewerBinding,
    private val fragmentManager: FragmentManager,
    private val getAdapter: () -> ImageViewerAdapter?,
    private val isGallerySupported: () -> Boolean,
    private val isAdjusted: () -> Boolean,
    private val isEditingAdjustments: () -> Boolean,
    private val isSaving: () -> Boolean,
    private val isLutListVisible: () -> Boolean,
    private val onBackNavigate: () -> Unit,
    private val onDismissPillPopup: () -> Boolean,
    private val onExitEditMode: (apply: Boolean) -> Unit,
    private val onEnterEditMode: () -> Unit,
    private val onResetAdjustments: () -> Unit,
    private val onSaveEdit: (isReplacement: Boolean) -> Unit,
    private val onShowSaveMenu: (anchor: View) -> Unit,
    private val onOpenExportHub: () -> Unit,
    private val onPerformShare: () -> Unit,
    private val onPerformShareAsTiff: () -> Unit,
    private val onShowLutMenu: (ImageGroup) -> Unit,
    private val onShowDeleteDialog: (ImageGroup) -> Unit,
    private val onUpdateBackPressedEnabled: (Boolean) -> Unit,
    private val shouldNavigateBackOnDiscard: () -> Boolean = { false },
    private val onOpenMotionStudio: (ImageGroup) -> Unit = {}
) {

    var isUiVisible: Boolean = true
        private set

    private var systemTopInset = 0
    private var systemBottomInset = 0
    private var topBarHeight = 0
    private var bentoDeckHeight = 0

    fun setupEdgeToEdge() {
        binding.topBarContainer.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val newHeight = bottom - top
            if (newHeight > 0 && newHeight != topBarHeight) {
                topBarHeight = newHeight
                updateViewportPadding()
            }
        }
        binding.viewerBentoDeck.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val newHeight = bottom - top
            if (newHeight > 0 && newHeight != bentoDeckHeight) {
                bentoDeckHeight = newHeight
                updateViewportPadding()
            }
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val statusBarInsets = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBarInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            systemTopInset = statusBarInsets.top
            systemBottomInset = navBarInsets.bottom
            val density = binding.root.resources.displayMetrics.density

            binding.topBarContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = systemTopInset + (8 * density).toInt()
            }
            binding.viewerBentoDeck.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = systemBottomInset + (12 * density).toInt()
            }
            binding.editAdjustmentPanel.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = systemBottomInset + (12 * density).toInt()
            }
            updateViewportPadding()
            insets
        }
    }

    fun updateViewportPadding() {
        val density = binding.root.resources.displayMetrics.density
        val topMarginExtra = (8 * density).toInt()
        val bottomMarginExtra = (12 * density).toInt()
        val topPadding = if (isUiVisible) systemTopInset + topMarginExtra + topBarHeight else 0
        val bottomPadding = if (isEditingAdjustments()) {
            val panelHeight = binding.editAdjustmentPanel.height
            if (panelHeight > 0) systemBottomInset + bottomMarginExtra + panelHeight else systemBottomInset + bottomMarginExtra + 200
        } else if (isUiVisible) {
            systemBottomInset + bottomMarginExtra + bentoDeckHeight
        } else 0
        binding.imagePager.setPadding(0, topPadding, 0, bottomPadding)
    }

    fun setupToolbar() {
        updateSplitButtons()
        updateToolbarIcon()
        binding.btnBack.setOnClickListener {
            if (onDismissPillPopup()) return@setOnClickListener
            when {
                isAdjusted() -> showDiscardChangesDialog()
                isEditingAdjustments() -> onExitEditMode(false)
                else -> onBackNavigate()
            }
        }
    }

    fun updateToolbarIcon() {
        binding.btnBack.setIconResource(
            if (isAdjusted() || isEditingAdjustments()) R.drawable.ic_close else R.drawable.ic_back
        )
    }

    fun updateSplitButtons() {
        if (isEditingAdjustments() || isAdjusted()) {
            binding.btnActionMain.setIconResource(R.drawable.ic_save)
            binding.btnActionMain.text = context.getString(R.string.save).uppercase()
            binding.btnActionMain.contentDescription = context.getString(R.string.save)
        } else {
            val adapter = getAdapter()
            val currentIndex = binding.imagePager.currentItem
            val currentGroup = if (adapter != null && currentIndex in adapter.getGroups().indices) {
                adapter.getGroup(currentIndex)
            } else null
            val isUnrendered = currentGroup != null && isUnrenderedVideoGroup(currentGroup)

            if (isUnrendered) {
                binding.btnActionMain.setIconResource(R.drawable.ic_save)
                binding.btnActionMain.text = context.getString(R.string.action_export_main).uppercase(java.util.Locale.getDefault())
                binding.btnActionMain.contentDescription = context.getString(R.string.action_export_main)
            } else {
                binding.btnActionMain.setIconResource(R.drawable.ic_share)
                binding.btnActionMain.text = context.getString(R.string.share_button_alt).uppercase(java.util.Locale.getDefault())
                binding.btnActionMain.contentDescription = context.getString(R.string.share_button_alt)
            }
        }
    }

    fun updateGalleryPill(position: Int, totalCount: Int) {
        if (!isGallerySupported()) {
            binding.btnGalleryPill.visibility = View.GONE
            return
        }
        binding.btnGalleryPill.visibility = View.VISIBLE
        binding.btnGalleryPill.text = "${position + 1} / $totalCount"
    }

    fun setupActionButtons() {
        binding.btnActionMain.setOnClickListener {
            if (isSaving()) return@setOnClickListener
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)

            if (isEditingAdjustments() || isAdjusted()) {
                onSaveEdit(true)
            } else {
                val adapter = getAdapter()
                if (adapter != null && adapter.itemCount > 0) {
                    val currentGroup = adapter.getGroup(binding.imagePager.currentItem)
                    if (isUnrenderedVideoGroup(currentGroup)) onOpenExportHub() else onPerformShare()
                } else {
                    onPerformShare()
                }
            }
        }

        binding.btnActionMain.setOnLongClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            binding.btnActionMenu.isChecked = true
            if (isEditingAdjustments() || isAdjusted()) {
                onShowSaveMenu(binding.splitAction)
            } else {
                showViewMenu(binding.splitAction)
            }
            true
        }

        binding.btnActionMenu.setOnClickListener {
            binding.btnActionMenu.isCheckable = true
            binding.btnActionMenu.isChecked = true
            if (isEditingAdjustments() || isAdjusted()) {
                onShowSaveMenu(binding.splitAction)
            } else {
                showViewMenu(binding.splitAction)
            }
        }

        binding.btnSeparateDng.setOnClickListener {
            val adapter = getAdapter()
            if (adapter != null && adapter.itemCount > 0) {
                showHalfFrameShareSheet(adapter.getGroup(binding.imagePager.currentItem))
            }
        }

        binding.btnLogCurve.setOnClickListener {
            val adapter = getAdapter()
            if (adapter != null && adapter.itemCount > 0) {
                onShowLutMenu(adapter.getGroup(binding.imagePager.currentItem))
            }
        }

        binding.btn3dLut.setOnClickListener {
            val adapter = getAdapter()
            if (adapter != null && adapter.itemCount > 0) {
                onShowLutMenu(adapter.getGroup(binding.imagePager.currentItem))
            }
        }

        binding.btnAdjustTofu.setOnClickListener { onEnterEditMode() }

        binding.btnVideoPlayback.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            val adapter = getAdapter() ?: return@setOnClickListener
            val curPos = binding.imagePager.currentItem
            if (curPos !in 0 until adapter.itemCount) return@setOnClickListener
            val grp = adapter.getGroup(curPos)
            val fmt = adapter.getSelectedFormat(curPos)
            val isRawVideo = (fmt == ImageViewerAdapter.FORMAT_DNG || fmt == "RAW" || fmt == "RAWVID") &&
                (grp.isRawVideo || grp.rawVideoUri != null)
            val isCdng = (fmt == ImageViewerAdapter.FORMAT_DNG || fmt == "RAW" || fmt == "CDNG") &&
                (grp.isCinemaDng || grp.cinemaDngFolderUri != null || grp.cinemaDngFrameUris.isNotEmpty())
            when {
                isCdng -> adapter.toggleCinemaDngForPosition(curPos)
                isRawVideo -> adapter.toggleRawVideoForPosition(curPos)
                else -> adapter.toggleMp4VideoForPosition(curPos)
            }
        }
    }

    fun showViewMenu(anchor: View) {
        val adapter = getAdapter()
        if (adapter == null || adapter.itemCount == 0) {
            binding.btnActionMenu.isChecked = false
            return
        }
        val grp = adapter.getGroup(binding.imagePager.currentItem)
        val isUnrendered = isUnrenderedVideoGroup(grp)
        val items = mutableListOf<BentoViewerActionMenu.ActionItem>()

        if (isUnrendered) {
            items.add(
                BentoViewerActionMenu.ActionItem(
                    id = MENU_SHARE,
                    title = context.getString(R.string.share_button_alt),
                    iconRes = R.drawable.ic_share,
                    role = BentoViewerActionMenu.Role.HERO_GRID,
                    onClick = { onPerformShare() }
                )
            )
        } else {
            items.add(
                BentoViewerActionMenu.ActionItem(
                    id = MENU_EXPORT_HUB,
                    title = context.getString(R.string.action_export),
                    iconRes = R.drawable.ic_save,
                    role = BentoViewerActionMenu.Role.HERO_GRID,
                    onClick = { onOpenExportHub() }
                )
            )
        }

        items.add(
            BentoViewerActionMenu.ActionItem(
                id = MENU_DETAILS,
                title = context.getString(R.string.viewer_menu_details),
                iconRes = R.drawable.ic_info,
                role = BentoViewerActionMenu.Role.HERO_GRID,
                onClick = { showImageDetails() }
            )
        )

        if (!isUnrendered && (grp.dngUri != null || grp.dngUri1 != null || grp.dngUri2 != null)) {
            items.add(
                BentoViewerActionMenu.ActionItem(
                    id = MENU_SHARE_TIFF,
                    title = context.getString(R.string.share_as_tiff),
                    iconRes = R.drawable.ic_photo,
                    role = BentoViewerActionMenu.Role.ROW,
                    onClick = { onPerformShareAsTiff() }
                )
            )
        }
        if (grp.isMultiCamera) {
            items.add(
                BentoViewerActionMenu.ActionItem(
                    id = MENU_COLLAGE,
                    title = context.getString(R.string.create_multi_cam_collage),
                    iconRes = R.drawable.ic_photo,
                    role = BentoViewerActionMenu.Role.ROW,
                    onClick = { showMultiCamCollageDialog(adapter.getGroup(binding.imagePager.currentItem)) }
                )
            )
        }
        if (grp.isMotionPhoto) {
            items.add(
                BentoViewerActionMenu.ActionItem(
                    id = MENU_MOTION_STUDIO,
                    title = context.getString(R.string.viewer_motion_studio),
                    iconRes = R.drawable.ic_motion_photo_on,
                    role = BentoViewerActionMenu.Role.ROW,
                    onClick = { onOpenMotionStudio(grp) }
                )
            )
        }
        items.add(
            BentoViewerActionMenu.ActionItem(
                id = MENU_DELETE,
                title = context.getString(R.string.delete_button_alt),
                iconRes = R.drawable.ic_delete,
                role = BentoViewerActionMenu.Role.DESTRUCTIVE,
                onClick = { onShowDeleteDialog(adapter.getGroup(binding.imagePager.currentItem)) }
            )
        )

        BentoViewerActionMenu(context, items) {
            binding.btnActionMenu.isChecked = false
        }.show(anchor)
    }

    fun showSaveMenu(
        anchor: View,
        mediaStrategy: ViewerMediaStrategy,
        onAction: (ViewerSaveAction) -> Unit
    ) {
        val adapter = getAdapter()
        if (adapter == null || adapter.itemCount == 0) {
            binding.btnActionMenu.isChecked = false
            return
        }
        mediaStrategy.showSaveMenu(
            context = context,
            anchor = anchor,
            currentGroup = adapter.getGroup(binding.imagePager.currentItem),
            onAction = onAction,
            onDismiss = { binding.btnActionMenu.isChecked = false }
        )
    }

    fun showHalfFrameShareSheet(group: ImageGroup) {
        HalfFrameShareSheet.newInstance(group.dngUri1, group.dngUri2).show(fragmentManager, HalfFrameShareSheet.TAG)
    }

    fun showImageDetails() {
        val adapter = getAdapter() ?: return
        val pos = binding.imagePager.currentItem
        val uri = adapter.getCurrentUri(pos) ?: return
        val group = if (pos in adapter.getGroups().indices) adapter.getGroup(pos) else null
        ViewerMetadataSheet.show(fragmentManager, uri, group)
    }

    fun showMultiCamCollageDialog(group: ImageGroup) {
        MultiCameraCollageSheet.newInstance(group).show(fragmentManager, MultiCameraCollageSheet.TAG)
    }

    fun showDiscardChangesDialog() {
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.discard_changes_title)
            .setMessage(R.string.discard_changes_message)
            .setPositiveButton(R.string.discard) { _, _ ->
                onResetAdjustments()
                if (shouldNavigateBackOnDiscard()) {
                    onBackNavigate()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    fun toggleUi() {
        if (isUiVisible) hideUi() else showUi()
    }

    fun showUi() {
        if (isUiVisible) return
        isUiVisible = true
        binding.topBarContainer.animate().alpha(1f).translationY(0f).setDuration(200)
            .withStartAction { binding.topBarContainer.visibility = View.VISIBLE }.start()
        if (!isEditingAdjustments()) {
            binding.viewerBentoDeck.animate().alpha(1f).translationY(0f).setDuration(200)
                .withStartAction { binding.viewerBentoDeck.visibility = View.VISIBLE }.start()
        }
        binding.btnGalleryPill.animate().alpha(1f).translationY(0f).setDuration(200)
            .withStartAction { binding.btnGalleryPill.visibility = View.VISIBLE }.start()
        getAdapter()?.setUiVisibility(true)
        updateViewportPadding()
    }

    fun hideUi() {
        if (!isUiVisible) return
        isUiVisible = false
        binding.topBarContainer.animate().alpha(0f).translationY(-topBarHeight.toFloat()).setDuration(200)
            .withEndAction { binding.topBarContainer.visibility = View.GONE }.start()
        binding.viewerBentoDeck.animate().alpha(0f).translationY(bentoDeckHeight.toFloat()).setDuration(200)
            .withEndAction { binding.viewerBentoDeck.visibility = View.GONE }.start()
        binding.btnGalleryPill.animate().alpha(0f).translationY(bentoDeckHeight.toFloat()).setDuration(200)
            .withEndAction { binding.btnGalleryPill.visibility = View.GONE }.start()
        getAdapter()?.setUiVisibility(false)
        updateViewportPadding()
    }

    fun updateBackPressedCallbackState() {
        onUpdateBackPressedEnabled(isAdjusted() || isEditingAdjustments() || isLutListVisible())
    }

    private fun isUnrenderedVideoGroup(group: ImageGroup): Boolean {
        return (group.isRawVideo || group.rawVideoUri != null ||
            group.isCinemaDng || group.cinemaDngFolderUri != null ||
            group.cinemaDngFirstFrameUri != null || group.cinemaDngFrameUris.isNotEmpty()) &&
            group.mp4VideoUri == null
    }

    companion object {
        private const val MENU_DETAILS = 1
        private const val MENU_DELETE = 2
        private const val MENU_SHARE_TIFF = 4
        private const val MENU_COLLAGE = 5
        private const val MENU_SHARE = 9
        private const val MENU_EXPORT_HUB = 10
        private const val MENU_MOTION_STUDIO = 11
    }
}
