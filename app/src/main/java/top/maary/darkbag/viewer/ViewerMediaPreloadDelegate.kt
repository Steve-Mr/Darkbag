package top.maary.darkbag.viewer

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.view.HapticFeedbackConstants
import com.bumptech.glide.Glide
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.maary.darkbag.databinding.FragmentImageViewerBinding
import top.maary.darkbag.fragments.ImageViewerAdapter
import top.maary.darkbag.models.ImageGroup
import top.maary.darkbag.processor.ColorProcessor
import top.maary.darkbag.repository.ImageRepository
import top.maary.darkbag.repository.SettingsRepository
import top.maary.darkbag.ui.ZoomableImageView
import top.maary.darkbag.viewer.bento.ViewerBentoDeckCoordinator
import top.maary.darkbag.viewer.chrome.ViewerChromeAndInsetsDelegate
import top.maary.darkbag.viewer.editor.DarkroomEditorUserIntent
import top.maary.darkbag.viewer.editor.DarkroomEditorViewModel
import java.io.FileInputStream

/**
 * 查看器媒体预加载、后台落盘同步与适配器交互代理 (ViewerMediaPreloadDelegate).
 * 封装邻近页元数据预加载、DNG 字节读取、后台保存流刷新以及适配器长按对比/动态照片回调。
 */
class ViewerMediaPreloadDelegate(
    private val context: Context,
    private val binding: FragmentImageViewerBinding,
    private val lifecycleScope: CoroutineScope,
    private val repository: ImageRepository,
    private val editorViewModel: DarkroomEditorViewModel,
    private val bentoDeckCoordinator: ViewerBentoDeckCoordinator,
    private val chromeDelegate: ViewerChromeAndInsetsDelegate,
    private val getAdapter: () -> ImageViewerAdapter?,
    private val isBindingAlive: () -> Boolean,
    private val isMotionPhotoAutoPlay: () -> Boolean,
    private val setMotionPhotoAutoPlay: (Boolean) -> Unit,
    private val getHasAutoPlayedPosition: () -> Int,
    private val setHasAutoPlayedPosition: (Int) -> Unit,
    private val isAdjusted: () -> Boolean,
    private val getLastCompositeBitmap: () -> Bitmap?,
    private val setLongPressing: (Boolean) -> Unit,
    private val getSourceDngBytes: () -> Pair<ByteArray?, ByteArray?>,
    private val setSourceDngBytes: (ByteArray?, ByteArray?) -> Unit,
    private val setCurrentCinemaDngFrame: (Pair<Int, Uri?>?) -> Unit,
    private val onPrepareEditConfig: (ImageGroup) -> Unit,
    private val onUpdateControlsVisibility: () -> Unit,
    private val onUpdateFormatBentoRow: () -> Unit,
    private val onOpenDarkroomGallery: () -> Unit
) {

    fun createViewerAdapter(groups: List<ImageGroup>): ImageViewerAdapter {
        return ImageViewerAdapter(groups, lifecycleScope, context).apply {
            isMotionPhotoAutoPlay = this@ViewerMediaPreloadDelegate.isMotionPhotoAutoPlay()
            onImageTapped = { chromeDelegate.toggleUi() }
            onZoomChanged = { isZoomed -> if (isZoomed) chromeDelegate.hideUi() else chromeDelegate.showUi() }
            onLongPressStarted = { handleLongPressStarted(it) }
            onLongPressEnded = { handleLongPressEnded(it) }
            onMotionPhotoIndicatorTapped = { pos -> handleMotionPhotoIndicatorTapped(pos) }
            onPinchToOverview = { onOpenDarkroomGallery() }
            onMultiCameraLensChanged = { _, _ ->
                chromeDelegate.updateSplitButtons()
                chromeDelegate.updateToolbarIcon()
                onUpdateControlsVisibility()
            }
            onFormatChanged = { _, _ -> onUpdateControlsVisibility() }
            onDerivativeVersionChanged = { _, _ -> onUpdateControlsVisibility() }
            setFormatSwitcherPersistentHidden(isAdjusted())
            onVideoPlaybackStateChanged = { _, isPlaying, curMs, totalMs ->
                bentoDeckCoordinator.updateVideoPlaybackButton(isPlaying, curMs, totalMs)
            }
            onCinemaDngFrameSelected = { group, frameIndex, uri ->
                val frameUris = when {
                    group.cinemaDngFrameUris.isNotEmpty() -> group.cinemaDngFrameUris
                    group.cinemaDngFirstFrameUri != null -> listOf(group.cinemaDngFirstFrameUri)
                    else -> emptyList()
                }
                val targetUri = uri ?: frameUris.getOrNull(frameIndex)
                setCurrentCinemaDngFrame(Pair(frameIndex, targetUri))
                getAdapter()?.selectCinemaDngFrame(binding.imagePager.currentItem, frameIndex, targetUri)
            }
            onCurrentListChanged = { previousList, currentList ->
                val cur = binding.imagePager.currentItem
                if (cur in currentList.indices) {
                    val curGroup = currentList[cur]
                    val prev = previousList.getOrNull(cur)
                    if (prev != null && !prev.metadataLoaded && curGroup.metadataLoaded && curGroup.editConfig != null) {
                        onPrepareEditConfig(curGroup)
                    }
                    onUpdateControlsVisibility()
                }
            }
        }
    }

    fun matchesTarget(group: ImageGroup, uri: String?, base: String?): Boolean {
        if (uri == null) return false
        return (base != null && group.baseName == base) ||
            group.rawVideoUri?.toString() == uri ||
            group.mp4VideoUri?.toString() == uri ||
            group.jpgUri?.toString() == uri ||
            group.dngUri?.toString() == uri ||
            group.dngUri1?.toString() == uri ||
            group.dngUri2?.toString() == uri ||
            group.derivativeJpgUris.any { it.toString() == uri } ||
            group.derivativeMp4Uris.any { it.toString() == uri } ||
            group.multiJpgUris.any { it.toString() == uri } ||
            group.multiDngUris.any { it.toString() == uri }
    }

    fun handlePageSelected(position: Int, onUpdateGalleryPill: (Int, Int) -> Unit) {
        val adapter = getAdapter() ?: return
        if (position >= adapter.itemCount) return
        val currentGroup = adapter.getGroup(position)
        onPrepareEditConfig(currentGroup)
        chromeDelegate.updateSplitButtons()
        chromeDelegate.updateToolbarIcon()
        chromeDelegate.updateGalleryPill(position, adapter.itemCount)

        adapter.stopAllMotionVideos()
        if (isMotionPhotoAutoPlay() && position != getHasAutoPlayedPosition()) {
            setHasAutoPlayedPosition(position)
            if (adapter.getSelectedFormat(position) != ImageViewerAdapter.FORMAT_DNG) {
                adapter.playMotionVideoForPosition(position)
            }
        }

        lifecycleScope.launch {
            val fullyLoaded = repository.loadMetadata(currentGroup)
            if (fullyLoaded != currentGroup && isBindingAlive()) {
                val curAdapter = getAdapter() ?: return@launch
                val curIdx = binding.imagePager.currentItem
                if (curIdx in 0 until curAdapter.itemCount && curAdapter.getGroup(curIdx).baseName == fullyLoaded.baseName) {
                    curAdapter.updateSingleGroup(fullyLoaded) {
                        if (!isBindingAlive()) return@updateSingleGroup
                        onPrepareEditConfig(fullyLoaded)
                        onUpdateControlsVisibility()
                        chromeDelegate.updateSplitButtons()
                        chromeDelegate.updateToolbarIcon()
                    }
                }
            }
        }
        onUpdateControlsVisibility()
        preloadAdjacentMetadata(position)
    }

    fun preloadAdjacentMetadata(position: Int) {
        val adapter = getAdapter() ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val groups = adapter.getGroups()
            val p1 = position - 1
            val p2 = position + 1
            if (p1 in groups.indices && !groups[p1].metadataLoaded) repository.loadMetadata(groups[p1])
            if (p2 in groups.indices && !groups[p2].metadataLoaded) repository.loadMetadata(groups[p2])
        }
    }

    fun readUriBytes(uri: Uri): ByteArray? = runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            FileInputStream(pfd.fileDescriptor).use { it.readBytes() }
        }
    }.getOrNull()

    suspend fun ensureDngBytesLoaded() {
        val adapter = getAdapter() ?: return
        val cur = binding.imagePager.currentItem
        if (cur !in adapter.getGroups().indices) return
        val group = adapter.getGroup(cur)

        withContext(Dispatchers.IO) {
            val (existing1, existing2) = getSourceDngBytes()
            if (!group.isHalfFrame()) {
                if (existing1 == null) {
                    val dngUri = if (group.isMultiCamera) {
                        val lenses = adapter.getMultiCameraLenses(group)
                        val idx = adapter.getSelectedLensIndex(cur).coerceIn(0, (lenses.size - 1).coerceAtLeast(0))
                        lenses.getOrNull(idx)?.dngUri ?: group.multiDngUris.getOrNull(idx) ?: group.dngUri
                    } else {
                        group.dngUri ?: group.cinemaDngFirstFrameUri
                    }
                    dngUri?.let {
                        val bytes = readUriBytes(it)
                        setSourceDngBytes(bytes, null)
                        editorViewModel.processIntent(DarkroomEditorUserIntent.SetDngBytes(bytes, null))
                    }
                }
            } else if (existing1 == null || existing2 == null) {
                val u1 = group.dngUri1 ?: group.dngUri
                val u2 = group.dngUri2
                val b1 = existing1 ?: u1?.let { readUriBytes(it) }
                val b2 = existing2 ?: u2?.let { readUriBytes(it) }
                setSourceDngBytes(b1, b2)
                editorViewModel.processIntent(DarkroomEditorUserIntent.SetDngBytes(b1, b2))
            }
        }
    }

    fun handleBackgroundSaveEvent(@Suppress("UNUSED_PARAMETER") event: ColorProcessor.BackgroundSaveEvent) {
        val adapter = getAdapter() ?: return
        lifecycleScope.launch {
            repository.invalidateCache()
            val newGroups = repository.getGroupedImages(forceRefresh = true)
            adapter.updateGroups(newGroups)
            val currentIndex = binding.imagePager.currentItem
            if (currentIndex in newGroups.indices) {
                onPrepareEditConfig(newGroups[currentIndex])
            }
        }
    }

    suspend fun refreshCurrentGroupAndAdapter(targetBaseName: String, autoSelectFormat: String? = null) {
        val adapter = getAdapter() ?: return
        repository.invalidateCache()
        val newGroups = repository.getGroupedImages(forceRefresh = true)
        if (!isBindingAlive()) return
        adapter.updateGroups(newGroups) {
            if (!isBindingAlive()) return@updateGroups
            val currentAdapter = getAdapter() ?: return@updateGroups
            if (autoSelectFormat != null) currentAdapter.setFormatForGroup(targetBaseName, autoSelectFormat)
            val cur = binding.imagePager.currentItem
            if (cur in newGroups.indices && newGroups[cur].baseName == targetBaseName) {
                currentAdapter.notifyItemChanged(cur)
                onPrepareEditConfig(newGroups[cur])
                chromeDelegate.updateSplitButtons()
                onUpdateControlsVisibility()
                chromeDelegate.updateToolbarIcon()
            }
        }
    }

    fun handleLongPressStarted(imageView: ZoomableImageView) {
        val adapter = getAdapter() ?: return
        if (isAdjusted()) {
            binding.root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            val jpgUri = adapter.getGroup(binding.imagePager.currentItem).jpgUri ?: return
            setLongPressing(true)
            imageView.setMaintainZoomOnNextImage(true)
            adapter.cancelLoadJob(binding.imagePager.currentItem, clearView = false)
            Glide.with(imageView).asBitmap().load(jpgUri).placeholder(imageView.drawable).dontAnimate().skipMemoryCache(true).into(imageView)
            return
        }
        val cur = binding.imagePager.currentItem
        if (cur in adapter.getGroups().indices && adapter.getSelectedFormat(cur) != ImageViewerAdapter.FORMAT_DNG) {
            val g = adapter.getGroup(cur)
            if (g.isMotionPhoto && g.jpgUri != null) {
                binding.root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                adapter.playMotionVideoForPosition(cur)
            }
        }
    }

    fun handleLongPressEnded(imageView: ZoomableImageView) {
        val adapter = getAdapter() ?: return
        if (isAdjusted()) {
            setLongPressing(false)
            binding.root.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            imageView.setMaintainZoomOnNextImage(true)
            getLastCompositeBitmap()?.let { adapter.setManualBitmap(binding.imagePager.currentItem, it) }
            return
        }
        val cur = binding.imagePager.currentItem
        if (cur in adapter.getGroups().indices && adapter.getSelectedFormat(cur) != ImageViewerAdapter.FORMAT_DNG) {
            if (adapter.getGroup(cur).isMotionPhoto) adapter.stopMotionVideoForPosition(cur)
        }
    }

    fun handleMotionPhotoIndicatorTapped(position: Int) {
        val adapter = getAdapter() ?: return
        val nextAutoPlay = !isMotionPhotoAutoPlay()
        setMotionPhotoAutoPlay(nextAutoPlay)
        SettingsRepository.getInstance(context).setViewerMotionPhotoAutoPlay(nextAutoPlay)
        onUpdateFormatBentoRow()
        binding.root.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        adapter.setMotionPhotoAutoPlayEnabled(nextAutoPlay)
        if (nextAutoPlay) {
            setHasAutoPlayedPosition(position)
            adapter.playMotionVideoForPosition(position)
        } else {
            adapter.stopAllMotionVideos()
        }
    }
}
