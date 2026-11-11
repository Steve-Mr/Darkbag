package top.maary.darkbag.fragments

import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import top.maary.darkbag.R
import top.maary.darkbag.databinding.ItemImageGroupBinding
import top.maary.darkbag.models.ImageGroup
import top.maary.darkbag.models.MultiCameraLensItem
import top.maary.darkbag.ui.viewer.ImageViewerDiffCallback
import top.maary.darkbag.ui.viewer.derivative.ViewerDerivativeSelectorDelegate
import top.maary.darkbag.ui.viewer.filmstrip.ViewerFilmstripDelegate
import top.maary.darkbag.ui.viewer.image.ViewerImageLoaderDelegate
import top.maary.darkbag.ui.viewer.lens.ViewerLensSelectorDelegate
import top.maary.darkbag.ui.viewer.playback.ViewerPagePlaybackController

/**
 * 图像查看器适配器 (ImageViewerAdapter).
 * 负责 ViewPager2 页面绑定、手势监听与多模态媒体分发。
 * 遵循 Invariant 4 与 Invariant 5，具体多摄选择、衍生版本轮换、胶片条交互、视频播放及图像异步加载分别委托给专用 Delegate 与 Controller。
 */
class ImageViewerAdapter(
    initialGroups: List<ImageGroup>,
    private val scope: CoroutineScope,
    context: android.content.Context
) : RecyclerView.Adapter<ImageViewerAdapter.ViewHolder>() {

    companion object {
        const val FORMAT_JPG = "JPG"
        const val FORMAT_DNG = "DNG"
    }

    private val margin = runCatching { context.resources.getDimensionPixelSize(R.dimen.margin_medium).toFloat() }.getOrDefault(0f)
    private val radius = runCatching { context.resources.getDimension(R.dimen.radius_medium) }.getOrDefault(0f)

    var onImageTapped: (() -> Unit)? = null
    var onZoomChanged: ((Boolean) -> Unit)? = null
    var onLongPressStarted: ((top.maary.darkbag.ui.ZoomableImageView) -> Unit)? = null
    var onLongPressEnded: ((top.maary.darkbag.ui.ZoomableImageView) -> Unit)? = null
    var onCurrentListChanged: ((List<ImageGroup>, List<ImageGroup>) -> Unit)? = null
    var onMotionPhotoIndicatorTapped: ((Int) -> Unit)? = null
    var onPinchToOverview: (() -> Unit)? = null
    var isMotionPhotoAutoPlay: Boolean = true
    var onCinemaDngFrameSelected: ((group: ImageGroup, index: Int, uri: Uri?) -> Unit)? = null
    var onVideoPlaybackStateChanged: ((position: Int, isPlaying: Boolean, currentMs: Long, totalMs: Long) -> Unit)? = null

    private var recyclerView: RecyclerView? = null
    private val selectedFormats = mutableMapOf<String, String>()

    val derivativeSelectorDelegate = ViewerDerivativeSelectorDelegate()
    val lensSelectorDelegate = ViewerLensSelectorDelegate()
    val filmstripDelegate = ViewerFilmstripDelegate()

    val playbackController = ViewerPagePlaybackController(
        context = context,
        scope = scope,
        getRecyclerView = { recyclerView },
        getGroupAt = { pos -> backingList.getOrNull(pos) },
        getBackingList = { backingList },
        radius = radius,
        loadCinemaDngFrame = { h, g, u, idx -> loadCinemaDngFrame(h, g, u, idx) },
        onCinemaDngFrameSelected = { g, idx, u -> onCinemaDngFrameSelected?.invoke(g, idx, u) },
        onVideoPlaybackStateChanged = { pos, isPlaying, cur, total -> onVideoPlaybackStateChanged?.invoke(pos, isPlaying, cur, total) }
    )
    val videoDurationCache get() = playbackController.videoDurationCache

    val imageLoaderDelegate = ViewerImageLoaderDelegate(
        scope = scope,
        videoDurationCache = playbackController.videoDurationCache,
        formatVideoTime = { playbackController.formatVideoTime(it) }
    )

    var onMultiCameraLensChanged: ((position: Int, lensIndex: Int) -> Unit)? = null
    var onDerivativeVersionChanged: ((position: Int, derivativeUri: Uri) -> Unit)? = null
    var onFormatChanged: ((position: Int, format: String) -> Unit)? = null
    private var isUiVisible = true
    private var isFormatSwitcherPersistentHidden = false

    internal val diffCallback = ImageViewerDiffCallback()
    private val differ = AsyncListDiffer(this, diffCallback)
    private var backingList: MutableList<ImageGroup> = initialGroups.toMutableList()

    init {
        differ.addListListener { previousList, currentList ->
            backingList = diffCallback.mergeMetadata(currentList, backingList).toMutableList()
            onCurrentListChanged?.invoke(previousList, backingList)
        }
        differ.submitList(initialGroups)
    }

    class ViewHolder(val binding: ItemImageGroupBinding) : RecyclerView.ViewHolder(binding.root) {
        var loadJob: Job? = null
        var extractJob: Job? = null
        var player: androidx.media3.exoplayer.ExoPlayer? = null
        var rawVideoPlayer: top.maary.darkbag.rawvideo.RawVideoPlayer? = null
        var isPlayingVideo: Boolean = false
        var playCompletionCallback: (() -> Unit)? = null
        var manualBitmap: Bitmap? = null
        var currentUri: Uri? = null
        var currentBaseName: String? = null
        var currentVersion: Long = 0L
        var currentLoadedRawUri: Uri? = null
        val videoOutlineRect = android.graphics.Rect()
        var videoOutlineRadius = 0f
        var activeCinemaDngFrameIndex: Int = 0
        var activeCinemaDngFrameUri: Uri? = null
        var filmstripFadeRunnable: Runnable? = null
        var lastDisplayRect: RectF? = null
        var videoProgressRunnable: Runnable? = null
        var cinemaDngPlaybackJob: Job? = null

        init {
            binding.videoView.clipToOutline = true
            binding.videoView.outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(videoOutlineRect, videoOutlineRadius)
                }
            }
        }
    }

    fun updateSingleGroup(updatedGroup: ImageGroup, onComplete: (() -> Unit)? = null) {
        val index = backingList.indexOfFirst { it.baseName == updatedGroup.baseName }
        if (index != -1) {
            backingList[index] = updatedGroup
            val holder = recyclerView?.findViewHolderForAdapterPosition(index) as? ViewHolder
            if (holder != null) setupButtons(holder) else notifyItemChanged(index, setOf("METADATA_LOADED"))
        }
        onComplete?.invoke()
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        this.recyclerView = recyclerView
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemImageGroupBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        val holder = ViewHolder(binding)
        holder.binding.videoView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            holder.lastDisplayRect?.let { rect -> playbackController.updateVideoViewBounds(holder, rect) }
        }
        return holder
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty()) {
            val importantPayloads = setOf("JPG_URI_CHANGED", "LAST_MODIFIED_CHANGED", "EDIT_CONFIG_CHANGED", "CINEMADNG_CHANGED", "MP4_URI_CHANGED", "DERIVATIVES_CHANGED")
            val needsReload = payloads.any { p -> (p as? Set<*>)?.any { it in importantPayloads } == true }
            if (needsReload) onBindViewHolder(holder, position) else setupButtons(holder)
            return
        }
        onBindViewHolder(holder, position)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val group = backingList.getOrNull(position) ?: return
        val isCinemaDngGroup = group.isCinemaDng || group.cinemaDngFolderUri != null || group.cinemaDngFirstFrameUri != null || group.cinemaDngFrameUris.isNotEmpty()
        holder.loadJob?.cancel()

        val isSameImage = holder.currentBaseName == group.baseName
        holder.binding.imageView.setMaintainZoomOnNextImage(isSameImage)
        holder.currentBaseName = group.baseName
        holder.binding.imageView.setVisualParams(margin, radius)

        holder.binding.videoView.isClickable = false
        holder.binding.videoView.isFocusable = false
        holder.binding.videoView.isLongClickable = false

        holder.binding.imageView.onTapped = { onImageTapped?.invoke() }
        holder.binding.imageView.onLongPressStarted = { onLongPressStarted?.invoke(it) }
        holder.binding.imageView.onLongPressEnded = { onLongPressEnded?.invoke(it) }
        holder.binding.imageView.onPinchToOverview = { onPinchToOverview?.invoke() }

        filmstripDelegate.bindFilmstrip(holder, group, isUiVisible, isFormatSwitcherPersistentHidden) { frameIndex, uri ->
            val currentPos = holder.bindingAdapterPosition
            val currentGroup = if (currentPos != RecyclerView.NO_POSITION && currentPos in backingList.indices) backingList[currentPos] else group
            if (uri != null) loadCinemaDngFrame(holder, currentGroup, uri, frameIndex)
            onCinemaDngFrameSelected?.invoke(currentGroup, frameIndex, uri)
        }

        holder.binding.imageView.onZoomChanged = { isZoomed ->
            onZoomChanged?.invoke(isZoomed)
            if (isCinemaDngGroup) {
                val shouldShow = isUiVisible && !isZoomed && !isFormatSwitcherPersistentHidden
                holder.binding.filmstripContainer.visibility = if (shouldShow) View.VISIBLE else View.GONE
                holder.binding.filmstripContainer.alpha = if (shouldShow) 0.35f else 0f
            }
        }

        if (!isSameImage) {
            holder.binding.imageView.rotation = 0f
            listOf(holder.binding.cameraOverlaysVerticalGroup, holder.binding.multiCameraToggleContainer, holder.binding.derivativeDotsContainer, holder.binding.videoDurationGroup).forEach {
                it.translationX = 0f
                it.translationY = 0f
            }
            stopMotionVideo(holder)
        }

        holder.binding.imageView.onMatrixChanged = { rect ->
            holder.lastDisplayRect = RectF(rect)
            val w = holder.binding.imageView.measuredWidth.takeIf { it > 0 } ?: holder.binding.imageView.width
            val h = holder.binding.imageView.measuredHeight.takeIf { it > 0 } ?: holder.binding.imageView.height
            if (w > 0 && h > 0) {
                val rMargin = (w - rect.right).coerceAtLeast(0f)
                val bMargin = (h - rect.bottom).coerceAtLeast(0f)
                holder.binding.cameraOverlaysVerticalGroup.translationY = -bMargin
                holder.binding.videoDurationGroup.translationX = -rMargin
                holder.binding.videoDurationGroup.translationY = -bMargin
                playbackController.updateVideoViewBounds(holder, rect)
            }
        }

        val format = getSelectedFormat(group)
        selectedFormats[group.baseName] = format
        setupButtons(holder)
        loadSelectedFormat(holder, group, format)
    }

    fun getDerivativeUris(group: ImageGroup): List<Uri> = derivativeSelectorDelegate.getDerivativeUris(group)

    fun getMultiCameraLenses(group: ImageGroup): List<MultiCameraLensItem> = lensSelectorDelegate.getMultiCameraLenses(group)

    private fun setupButtons(holder: ViewHolder) = with(holder.binding) {
        videoDurationGroup.visibility = View.GONE
        videoDurationGroup.alpha = 0f
        btnVideoDuration.setOnClickListener(null)
        multiCameraToggleContainer.visibility = View.GONE
        multiCameraToggleContainer.alpha = 0f
        multiCameraToggleGroup.removeAllViews()
        derivativeDotsContainer.visibility = View.GONE
        derivativeDotsContainer.alpha = 0f
        derivativeDotsGroup.removeAllViews()
    }

    private fun loadSelectedFormat(holder: ViewHolder, group: ImageGroup, format: String) =
        imageLoaderDelegate.loadSelectedFormat(holder, group, format, lensSelectorDelegate, derivativeSelectorDelegate, playbackController)

    fun setManualBitmap(position: Int, bitmap: Bitmap) {
        val holder = recyclerView?.findViewHolderForAdapterPosition(position) as? ViewHolder
        if (holder != null) imageLoaderDelegate.setBitmapAndRecyclePrevious(holder, bitmap) else bitmap.recycle()
    }

    fun loadCinemaDngFrame(holder: ViewHolder, group: ImageGroup, uri: Uri, index: Int) {
        holder.activeCinemaDngFrameIndex = index
        holder.activeCinemaDngFrameUri = uri
        stopMotionVideo(holder)
        holder.binding.videoView.visibility = View.GONE
        holder.binding.imageView.visibility = View.VISIBLE
        imageLoaderDelegate.loadImage(holder, uri)
    }

    override fun getItemCount(): Int = backingList.size

    override fun onViewRecycled(holder: ViewHolder) {
        holder.loadJob?.cancel()
        filmstripDelegate.onViewRecycled(holder)
        playbackController.onViewRecycled(holder)
        imageLoaderDelegate.clearCurrentBitmap(holder)
        holder.currentUri = null
        holder.currentBaseName = null
        holder.currentVersion = 0L
        holder.binding.loadingIndicator.visibility = View.GONE
        super.onViewRecycled(holder)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        playbackController.onDetachedFromRecyclerView(recyclerView, itemCount)
        super.onDetachedFromRecyclerView(recyclerView)
        this.recyclerView = null
    }

    fun playMotionVideo(holder: ViewHolder, group: ImageGroup, onComplete: (() -> Unit)? = null) =
        playbackController.playMotionVideo(holder, group, (selectedFormats[group.baseName] ?: getSelectedFormat(group)) == FORMAT_DNG, onComplete)

    fun stopMotionVideo(holder: ViewHolder) = playbackController.stopMotionVideo(holder)

    fun playMotionVideoForPosition(position: Int, onComplete: (() -> Unit)? = null) =
        backingList.getOrNull(position)?.let { g -> playbackController.playMotionVideoForPosition(position, (selectedFormats[g.baseName] ?: getSelectedFormat(g)) == FORMAT_DNG, onComplete) } ?: onComplete?.invoke()

    fun stopMotionVideoForPosition(position: Int) = playbackController.stopMotionVideoForPosition(position)

    fun playRawVideo(holder: ViewHolder, group: ImageGroup) = playbackController.playRawVideo(holder, group)

    fun stopRawVideo(holder: ViewHolder) = playbackController.stopRawVideo(holder)

    fun toggleRawVideoForPosition(position: Int) = playbackController.toggleRawVideoForPosition(position)

    fun playMp4Video(holder: ViewHolder, group: ImageGroup) {
        derivativeSelectorDelegate.getActiveDerivativeUri(group)?.let { playbackController.playMp4Video(holder, it) }
    }

    fun toggleMp4VideoForPosition(position: Int) {
        backingList.getOrNull(position)?.let { g -> playbackController.toggleMp4VideoForPosition(position, derivativeSelectorDelegate.getActiveDerivativeUri(g)) }
    }

    fun updateRawVideoAdjustments(position: Int, editConfig: top.maary.darkbag.models.EditConfig?) = playbackController.updateRawVideoAdjustments(position, editConfig)

    fun playCinemaDng(holder: ViewHolder, group: ImageGroup) = playbackController.playCinemaDng(holder, group)

    fun stopCinemaDng(holder: ViewHolder) = playbackController.stopCinemaDng(holder)

    fun toggleCinemaDngForPosition(position: Int) = playbackController.toggleCinemaDngForPosition(position)

    fun selectCinemaDngFrame(position: Int, frameIndex: Int, uri: Uri? = null) = playbackController.selectCinemaDngFrame(position, frameIndex, uri)

    fun isVideoPlaying(position: Int): Boolean = playbackController.isVideoPlaying(position)

    fun getVideoDuration(position: Int): Long = playbackController.getVideoDuration(position)

    fun getVideoCurrentPosition(position: Int): Long = playbackController.getVideoCurrentPosition(position)

    fun stopAllMotionVideos() = playbackController.stopAllVideos(itemCount)

    fun setMotionPhotoAutoPlayEnabled(enabled: Boolean) {
        if (this.isMotionPhotoAutoPlay == enabled) return
        this.isMotionPhotoAutoPlay = enabled
        recyclerView?.let { rv ->
            for (i in 0 until itemCount) {
                (rv.findViewHolderForAdapterPosition(i) as? ViewHolder)?.let { h ->
                    val pos = h.bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION && pos in backingList.indices) setupButtons(h)
                }
            }
        }
    }

    fun getGroup(position: Int): ImageGroup = backingList[position]

    fun getGroups(): List<ImageGroup> = backingList

    fun getSelectedDerivativeIndex(baseName: String): Int = derivativeSelectorDelegate.getSelectedDerivativeIndex(baseName)

    fun cycleOrToggleFormat(position: Int): String {
        val group = backingList.getOrNull(position) ?: return FORMAT_JPG
        val currentFormat = selectedFormats[group.baseName] ?: getSelectedFormat(group)
        val isRawSelected = currentFormat == FORMAT_DNG

        val activeLens = lensSelectorDelegate.getActiveLens(group)
        val hasDng = if (group.isMultiCamera) activeLens?.dngUri != null else group.hasMasterRaw
        val derivatives = getDerivativeUris(group)
        val hasDerivatives = if (group.isMultiCamera) activeLens?.jpgUri != null else derivatives.isNotEmpty()

        if (hasDng && hasDerivatives) {
            val newFormat = if (isRawSelected) FORMAT_JPG else FORMAT_DNG
            forceFormat(group.baseName, newFormat)
            onFormatChanged?.invoke(position, newFormat)
            return newFormat
        }
        return currentFormat
    }

    fun cycleDerivativeVersion(position: Int) {
        val group = backingList.getOrNull(position) ?: return
        val result = derivativeSelectorDelegate.cycleDerivativeVersion(group) ?: return
        val (_, nextUri) = result
        selectedFormats[group.baseName] = FORMAT_JPG

        val holder = recyclerView?.findViewHolderForAdapterPosition(position) as? ViewHolder
        if (holder != null) {
            setupButtons(holder)
            loadSelectedFormat(holder, group, FORMAT_JPG)
        } else {
            notifyItemChanged(position)
        }
        onDerivativeVersionChanged?.invoke(position, nextUri)
        onFormatChanged?.invoke(position, FORMAT_JPG)
    }

    fun setFormat(position: Int, format: String) {
        val group = backingList.getOrNull(position) ?: return
        if (selectedFormats[group.baseName] == format) return
        selectedFormats[group.baseName] = format
        (recyclerView?.findViewHolderForAdapterPosition(position) as? ViewHolder)?.let { holder ->
            setupButtons(holder)
            loadSelectedFormat(holder, group, format)
        }
    }

    fun getSelectedFormat(group: ImageGroup): String =
        selectedFormats[group.baseName] ?: when {
            group.isMultiCamera -> {
                val lens = lensSelectorDelegate.getActiveLens(group)
                if (lens?.jpgUri != null) FORMAT_JPG else if (lens?.dngUri != null) FORMAT_DNG else FORMAT_JPG
            }
            getDerivativeUris(group).isNotEmpty() -> FORMAT_JPG
            group.hasMasterRaw -> FORMAT_DNG
            else -> FORMAT_JPG
        }

    fun getSelectedFormat(position: Int): String =
        backingList.getOrNull(position)?.let { getSelectedFormat(it) } ?: FORMAT_JPG

    fun getSelectedLensIndex(position: Int): Int =
        backingList.getOrNull(position)?.let { lensSelectorDelegate.getSelectedLensIndex(it.baseName) } ?: 0

    fun setSelectedLensIndex(position: Int, lensIndex: Int) {
        val group = backingList.getOrNull(position) ?: return
        lensSelectorDelegate.setSelectedLensIndex(group.baseName, lensIndex)
        (recyclerView?.findViewHolderForAdapterPosition(position) as? ViewHolder)?.let { holder ->
            setupButtons(holder)
            loadSelectedFormat(holder, group, selectedFormats[group.baseName] ?: getSelectedFormat(group))
        }
        onMultiCameraLensChanged?.invoke(position, lensIndex)
    }

    fun getActiveCinemaDngFrame(position: Int): Pair<Int, Uri?>? {
        val group = backingList.getOrNull(position) ?: return null
        val isCinemaDngGroup = group.isCinemaDng || group.cinemaDngFolderUri != null || group.cinemaDngFirstFrameUri != null || group.cinemaDngFrameUris.isNotEmpty()
        if (!isCinemaDngGroup) return null

        val holder = recyclerView?.findViewHolderForAdapterPosition(position) as? ViewHolder
        val index = holder?.activeCinemaDngFrameIndex ?: 0
        val uri = holder?.activeCinemaDngFrameUri ?: group.cinemaDngFrameUris.getOrNull(index) ?: group.cinemaDngFirstFrameUri
        return Pair(index, uri)
    }

    fun updateVideoViewBounds(holder: ViewHolder, rect: RectF) = playbackController.updateVideoViewBounds(holder, rect)

    fun getCurrentUri(position: Int): Uri? {
        val group = backingList.getOrNull(position) ?: return null
        val format = getSelectedFormat(group)
        val isCinemaDngGroup = group.isCinemaDng || group.cinemaDngFolderUri != null || group.cinemaDngFirstFrameUri != null || group.cinemaDngFrameUris.isNotEmpty()
        if (isCinemaDngGroup) {
            val holder = recyclerView?.findViewHolderForAdapterPosition(position) as? ViewHolder
            return holder?.activeCinemaDngFrameUri ?: group.cinemaDngFirstFrameUri ?: group.cinemaDngFrameUris.firstOrNull() ?: group.dngUri
        }
        return if (group.isMultiCamera) {
            val lens = lensSelectorDelegate.getActiveLens(group)
            if (format == FORMAT_DNG) (lens?.dngUri ?: lens?.jpgUri) else (lens?.jpgUri ?: lens?.dngUri)
        } else {
            if (format == FORMAT_DNG) {
                group.rawVideoUri ?: group.dngUri ?: group.dngUri1 ?: group.dngUri2 ?: group.jpgUri
            } else {
                derivativeSelectorDelegate.getActiveDerivativeUri(group) ?: group.rawVideoUri ?: group.dngUri
            }
        }
    }

    private fun updateOverlaysVisibility(isVisible: Boolean, isHidden: Boolean) {
        recyclerView?.let { rv ->
            for (i in 0 until itemCount) {
                (rv.findViewHolderForAdapterPosition(i) as? ViewHolder)?.let { holder ->
                    setupButtons(holder)
                    filmstripDelegate.updateFilmstripVisibility(holder, getGroup(i), isVisible, isHidden)
                }
            }
        }
    }

    fun setUiVisibility(isVisible: Boolean) {
        this.isUiVisible = isVisible
        updateOverlaysVisibility(isVisible, isFormatSwitcherPersistentHidden)
    }

    fun setFormatSwitcherPersistentHidden(hidden: Boolean) {
        if (this.isFormatSwitcherPersistentHidden == hidden) return
        this.isFormatSwitcherPersistentHidden = hidden
        if (hidden) stopAllMotionVideos()
        updateOverlaysVisibility(isUiVisible, hidden)
    }

    fun cancelLoadJob(position: Int, clearView: Boolean = true) {
        val holder = recyclerView?.findViewHolderForAdapterPosition(position) as? ViewHolder
        if (holder != null) {
            holder.loadJob?.cancel()
            if (clearView) imageLoaderDelegate.clearCurrentBitmap(holder)
            holder.binding.loadingIndicator.visibility = View.GONE
        }
    }

    fun updateGroups(newGroups: List<ImageGroup>, commitCallback: (() -> Unit)? = null) = differ.submitList(newGroups, commitCallback)

    fun findGroupIndex(baseName: String): Int = backingList.indexOfFirst { it.baseName == baseName }

    fun setFormatForGroup(baseName: String, format: String) { selectedFormats[baseName] = format }

    fun forceFormat(baseName: String, format: String) {
        selectedFormats[baseName] = format
        val index = findGroupIndex(baseName)
        if (index != -1) {
            val holder = recyclerView?.findViewHolderForAdapterPosition(index) as? ViewHolder
            if (holder != null) {
                setupButtons(holder)
                loadSelectedFormat(holder, backingList[index], format)
            } else {
                notifyItemChanged(index)
            }
        }
    }
}
