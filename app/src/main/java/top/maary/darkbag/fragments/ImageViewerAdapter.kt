package top.maary.darkbag.fragments

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.net.Uri
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import kotlinx.coroutines.*
import top.maary.darkbag.R
import top.maary.darkbag.databinding.ItemImageGroupBinding
import top.maary.darkbag.models.ImageGroup
import top.maary.darkbag.utils.ImageUtils

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
    private val selectedLensIndices = mutableMapOf<String, Int>()
    private val selectedDerivativeIndices = mutableMapOf<String, Int>()
    val playbackController = top.maary.darkbag.ui.viewer.playback.ViewerPagePlaybackController(
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
    var onMultiCameraLensChanged: ((position: Int, lensIndex: Int) -> Unit)? = null
    var onDerivativeVersionChanged: ((position: Int, derivativeUri: Uri) -> Unit)? = null
    var onFormatChanged: ((position: Int, format: String) -> Unit)? = null
    private var isUiVisible = true
    private var isFormatSwitcherPersistentHidden = false

    internal val diffCallback = object : DiffUtil.ItemCallback<ImageGroup>() {
        override fun areItemsTheSame(oldItem: ImageGroup, newItem: ImageGroup): Boolean {
            return oldItem.baseName == newItem.baseName
        }

        override fun areContentsTheSame(oldItem: ImageGroup, newItem: ImageGroup): Boolean {
            return oldItem == newItem &&
                oldItem.metadataLoaded == newItem.metadataLoaded &&
                oldItem.isMotionPhoto == newItem.isMotionPhoto &&
                oldItem.isCinemaDng == newItem.isCinemaDng &&
                oldItem.cinemaDngFrameUris == newItem.cinemaDngFrameUris &&
                oldItem.mp4VideoUri == newItem.mp4VideoUri &&
                oldItem.derivativeMp4Uris == newItem.derivativeMp4Uris &&
                oldItem.derivativeJpgUris == newItem.derivativeJpgUris &&
                oldItem.cinemaDngFolderUri == newItem.cinemaDngFolderUri
        }

        override fun getChangePayload(oldItem: ImageGroup, newItem: ImageGroup): Any? {
            val payloads = mutableSetOf<String>()
            if (oldItem.jpgUri != newItem.jpgUri) payloads.add("JPG_URI_CHANGED")
            if (oldItem.dngUri != newItem.dngUri || oldItem.dngUri1 != newItem.dngUri1 || oldItem.dngUri2 != newItem.dngUri2) payloads.add("DNG_AVAILABILITY_CHANGED")
            if (oldItem.metadataLoaded != newItem.metadataLoaded) payloads.add("METADATA_LOADED")
            if (oldItem.lastModified != newItem.lastModified) payloads.add("LAST_MODIFIED_CHANGED")
            if (oldItem.editConfig != newItem.editConfig) payloads.add("EDIT_CONFIG_CHANGED")
            if (oldItem.isMotionPhoto != newItem.isMotionPhoto) payloads.add("MOTION_PHOTO_CHANGED")
            if (oldItem.cinemaDngFolderUri != newItem.cinemaDngFolderUri || oldItem.isCinemaDng != newItem.isCinemaDng || oldItem.cinemaDngFrameUris != newItem.cinemaDngFrameUris) payloads.add("CINEMADNG_CHANGED")
            if (oldItem.mp4VideoUri != newItem.mp4VideoUri || oldItem.derivativeMp4Uris != newItem.derivativeMp4Uris) payloads.add("MP4_URI_CHANGED")
            if (oldItem.allDerivativeUris != newItem.allDerivativeUris) payloads.add("DERIVATIVES_CHANGED")

            return if (payloads.isEmpty()) null else payloads
        }
    }

    private val differ = AsyncListDiffer(this, diffCallback)
    private var backingList: MutableList<ImageGroup> = initialGroups.toMutableList()

    init {
        differ.addListListener { previousList, currentList ->
            val metadataMap = backingList.filter { it.metadataLoaded }.associateBy { it.baseName }
            backingList = currentList.map { item ->
                val cached = metadataMap[item.baseName]
                if (cached != null && !item.metadataLoaded) {
                    item.copy(
                        metadataLoaded = true,
                        width = if (item.width > 0) item.width else cached.width,
                        height = if (item.height > 0) item.height else cached.height,
                        orientation = if (item.orientation != 0) item.orientation else cached.orientation,
                        editConfig = item.editConfig ?: cached.editConfig,
                        isMotionPhoto = item.isMotionPhoto || cached.isMotionPhoto,
                        motionPhotoPtsUs = if (item.motionPhotoPtsUs > 0) item.motionPhotoPtsUs else cached.motionPhotoPtsUs,
                        motionPhotoVideoLength = if (item.motionPhotoVideoLength > 0) item.motionPhotoVideoLength else cached.motionPhotoVideoLength,
                        rawVideoFps = if (item.rawVideoFps > 0) item.rawVideoFps else cached.rawVideoFps,
                        rawVideoFrameCount = if (item.rawVideoFrameCount > 0) item.rawVideoFrameCount else cached.rawVideoFrameCount,
                        rawVideoDurationMs = if (item.rawVideoDurationMs > 0) item.rawVideoDurationMs else cached.rawVideoDurationMs,
                        isCinemaDng = item.isCinemaDng || cached.isCinemaDng,
                        cinemaDngFrameCount = if (item.cinemaDngFrameCount > 0) item.cinemaDngFrameCount else cached.cinemaDngFrameCount
                    )
                } else {
                    item
                }
            }.toMutableList()
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
        var manualBitmap: android.graphics.Bitmap? = null
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
            if (holder != null) {
                setupButtons(holder, updatedGroup)
            } else {
                notifyItemChanged(index, setOf("METADATA_LOADED"))
            }
        }
        onComplete?.invoke()
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        this.recyclerView = recyclerView
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemImageGroupBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        val holder = ViewHolder(binding)
        holder.binding.videoView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            holder.lastDisplayRect?.let { rect ->
                playbackController.updateVideoViewBounds(holder, rect)
            }
        }
        return holder
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty()) {
            val group = backingList.getOrNull(position) ?: return
            val importantPayloads = setOf(
                "JPG_URI_CHANGED",
                "LAST_MODIFIED_CHANGED",
                "EDIT_CONFIG_CHANGED",
                "CINEMADNG_CHANGED",
                "MP4_URI_CHANGED",
                "DERIVATIVES_CHANGED"
            )

            val needsReload = payloads.any { payload ->
                (payload as? Set<*>)?.any { it in importantPayloads } == true
            }

            if (needsReload) {
                // Important changes that might require image reload
                onBindViewHolder(holder, position)
            } else {
                // Minor changes (DNG appeared, metadata loaded) - just update buttons
                setupButtons(holder, group)
            }
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

        val shouldShow = isUiVisible && !isFormatSwitcherPersistentHidden

        if (isCinemaDngGroup) {
            holder.binding.filmstripContainer.visibility = if (shouldShow) View.VISIBLE else View.GONE
            holder.binding.filmstripContainer.alpha = if (shouldShow) 0.35f else 0f
            holder.binding.filmstripSeekbar.alpha = 1.0f

            val frameUris = if (group.cinemaDngFrameUris.isNotEmpty()) {
                group.cinemaDngFrameUris
            } else if (group.cinemaDngFirstFrameUri != null) {
                listOf(group.cinemaDngFirstFrameUri)
            } else {
                emptyList()
            }

            val currentActiveIndex = holder.activeCinemaDngFrameIndex.coerceIn(0, (frameUris.size - 1).coerceAtLeast(0))
            val currentActiveUri = frameUris.getOrNull(currentActiveIndex)
            holder.activeCinemaDngFrameIndex = currentActiveIndex
            holder.activeCinemaDngFrameUri = currentActiveUri

            holder.binding.filmstripSeekbar.setFrames(frameUris, currentActiveIndex)

            holder.binding.filmstripSeekbar.onScrubStateChanged = { isScrubbing ->
                holder.filmstripFadeRunnable?.let { holder.binding.filmstripContainer.removeCallbacks(it) }
                holder.filmstripFadeRunnable = null

                if (isScrubbing) {
                    holder.binding.filmstripContainer.animate()
                        .alpha(1.0f)
                        .setDuration(150)
                        .start()
                } else {
                    val fadeRunnable = Runnable {
                        if (isUiVisible && !isFormatSwitcherPersistentHidden) {
                            holder.binding.filmstripContainer.animate()
                                .alpha(0.35f)
                                .setDuration(400)
                                .start()
                        }
                    }
                    holder.filmstripFadeRunnable = fadeRunnable
                    holder.binding.filmstripContainer.postDelayed(fadeRunnable, 3000)
                }
            }

            holder.binding.filmstripSeekbar.onFrameSelected = { frameIndex, uri ->
                val currentPos = holder.bindingAdapterPosition
                val currentGroup = if (currentPos != RecyclerView.NO_POSITION && currentPos in backingList.indices) {
                    backingList[currentPos]
                } else {
                    group
                }
                holder.activeCinemaDngFrameIndex = frameIndex
                holder.activeCinemaDngFrameUri = uri
                if (uri != null) {
                    loadCinemaDngFrame(holder, currentGroup, uri, frameIndex)
                }
                onCinemaDngFrameSelected?.invoke(currentGroup, frameIndex, uri)
            }
        } else {
            holder.filmstripFadeRunnable?.let { holder.binding.filmstripContainer.removeCallbacks(it) }
            holder.filmstripFadeRunnable = null
            holder.binding.filmstripContainer.visibility = View.GONE
            holder.activeCinemaDngFrameIndex = 0
            holder.activeCinemaDngFrameUri = null
        }

        holder.binding.imageView.onZoomChanged = { isZoomed ->
            onZoomChanged?.invoke(isZoomed)
            val currentlyShouldShow = isUiVisible && !isZoomed && !isFormatSwitcherPersistentHidden
            val isRawSelected = selectedFormats[group.baseName] == FORMAT_DNG
            val hasDots = !group.isMultiCamera && !isRawSelected && getDerivativeUris(group).size >= 2
            if (hasDots) {
                holder.binding.derivativeDotsContainer.visibility = if (currentlyShouldShow) View.VISIBLE else View.GONE
                holder.binding.derivativeDotsContainer.alpha = if (currentlyShouldShow) 1f else 0f
            } else {
                holder.binding.derivativeDotsContainer.visibility = View.GONE
                holder.binding.derivativeDotsContainer.alpha = 0f
            }
            val activeDerivUri = getDerivativeUris(group).getOrNull(selectedDerivativeIndices[group.baseName] ?: 0) ?: group.jpgUri ?: group.mp4VideoUri
            val isMp4 = !isRawSelected && activeDerivUri != null && (activeDerivUri.toString().endsWith(".mp4", ignoreCase = true) || group.isMp4Video)
            val isVideo = (isRawSelected && group.isRawVideo) || isMp4
            if (isVideo) {
                holder.binding.videoDurationGroup.visibility = if (currentlyShouldShow) View.VISIBLE else View.GONE
                holder.binding.videoDurationGroup.alpha = if (currentlyShouldShow) 1f else 0f
            } else {
                holder.binding.videoDurationGroup.visibility = View.GONE
                holder.binding.videoDurationGroup.alpha = 0f
            }
            if (isCinemaDngGroup) {
                holder.binding.filmstripContainer.visibility = if (currentlyShouldShow) View.VISIBLE else View.GONE
                holder.binding.filmstripContainer.alpha = if (currentlyShouldShow) 0.35f else 0f
            }
        }

        if (!isSameImage) {
            holder.binding.imageView.rotation = 0f
            holder.binding.cameraOverlaysVerticalGroup.translationX = 0f
            holder.binding.cameraOverlaysVerticalGroup.translationY = 0f
            holder.binding.multiCameraToggleContainer.translationX = 0f
            holder.binding.multiCameraToggleContainer.translationY = 0f
            holder.binding.derivativeDotsContainer.translationX = 0f
            holder.binding.derivativeDotsContainer.translationY = 0f
            holder.binding.videoDurationGroup.translationX = 0f
            holder.binding.videoDurationGroup.translationY = 0f
            stopMotionVideo(holder)
        }

        holder.binding.imageView.onMatrixChanged = { rect ->
            holder.lastDisplayRect = RectF(rect)
            // Use measured dimensions if layout hasn't happened yet
            val viewWidth = holder.binding.imageView.measuredWidth.takeIf { it > 0 } ?: holder.binding.imageView.width
            val viewHeight = holder.binding.imageView.measuredHeight.takeIf { it > 0 } ?: holder.binding.imageView.height

            if (viewWidth > 0 && viewHeight > 0) {
                // Determine actual visual right margin (distance from right edge of screen)
                val visualRightSpace = viewWidth - rect.right
                val rightMargin = if (visualRightSpace > 0) visualRightSpace else 0f
                val visualLeftSpace = rect.left
                val leftMargin = if (visualLeftSpace > 0) visualLeftSpace else 0f

                // Determine actual visual top and bottom margins
                val topMargin = if (rect.top > 0) rect.top else 0f
                val visualBottomSpace = viewHeight - rect.bottom
                val bottomMargin = if (visualBottomSpace > 0) visualBottomSpace else 0f

                // Instead of layoutParams (which causes requestLayout and UI jank), use translationX/Y.
                holder.binding.cameraOverlaysVerticalGroup.translationY = -bottomMargin
                holder.binding.multiCameraToggleContainer.translationY = 0f
                holder.binding.derivativeDotsContainer.translationY = 0f
                holder.binding.videoDurationGroup.translationX = -rightMargin
                holder.binding.videoDurationGroup.translationY = -bottomMargin

                updateVideoViewBounds(holder, rect)
            }
        }

        val format = getSelectedFormat(group)
        selectedFormats[group.baseName] = format

        setupButtons(holder, group)

        loadSelectedFormat(holder, group, format)
    }

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

    fun getMultiCameraLenses(group: ImageGroup): List<top.maary.darkbag.models.MultiCameraLensItem> {
        if (!group.isMultiCamera) return emptyList()
        if (group.multiCameraLenses.isNotEmpty()) {
            return group.multiCameraLenses
        }

        // Fallback for tests or legacy ImageGroups without multiCameraLenses pre-populated
        val lenses = mutableListOf<top.maary.darkbag.models.MultiCameraLensItem>()
        val maxLen = maxOf(group.multiJpgUris.size, group.multiDngUris.size)
        for (i in 0 until maxLen) {
            val jpg = group.multiJpgUris.getOrNull(i)
            val dng = group.multiDngUris.getOrNull(i)
            val sample = jpg ?: dng
            val tag = if (sample != null) top.maary.darkbag.utils.ImageUtils.extractMultiCameraLensTag(sample.toString()) else ""
            val mult = if (sample != null) top.maary.darkbag.utils.ImageUtils.extractMultiCameraMultiplier(sample.toString()) else 1.0f
            val effectiveTag = if (tag.isNotEmpty()) tag else String.format(java.util.Locale.US, "%.1fx", mult)
            lenses.add(top.maary.darkbag.models.MultiCameraLensItem(
                lensTag = effectiveTag,
                multiplier = mult,
                jpgUri = jpg,
                dngUri = dng
            ))
        }
        return lenses.sortedWith(compareBy<top.maary.darkbag.models.MultiCameraLensItem> { it.multiplier }.thenBy { it.lensTag })
    }

    private fun setupButtons(holder: ViewHolder, group: ImageGroup) {
        with(holder.binding) {
            val currentFormat = selectedFormats[group.baseName] ?: getSelectedFormat(group)
            val isRawSelected = currentFormat == FORMAT_DNG
            val shouldShow = isUiVisible && !isFormatSwitcherPersistentHidden

            val isRawVideo = group.isRawVideo || group.rawVideoUri != null
            val derivatives = getDerivativeUris(group)
            val activeDerivativeIndex = (selectedDerivativeIndices[group.baseName] ?: 0).coerceIn(0, (derivatives.size - 1).coerceAtLeast(0))
            val activeDerivativeUri = derivatives.getOrNull(activeDerivativeIndex) ?: group.jpgUri ?: group.mp4VideoUri
            val isMp4Active = !isRawSelected && activeDerivativeUri != null && (activeDerivativeUri.toString().endsWith(".mp4", ignoreCase = true) || group.isMp4Video)

            // Video Duration indicator setup (Superseded by Bento Deck viewer_video_deck)
            val videoDurationGroup = holder.binding.videoDurationGroup
            val btnVideoDuration = holder.binding.btnVideoDuration
            videoDurationGroup.visibility = View.GONE
            videoDurationGroup.alpha = 0f
            btnVideoDuration.setOnClickListener(null)

            // 3. Multi-Camera & Derivative overlays are replaced by Bento Deck controls, keep GONE
            multiCameraToggleContainer.visibility = View.GONE
            multiCameraToggleContainer.alpha = 0f
            multiCameraToggleGroup.removeAllViews()

            derivativeDotsContainer.visibility = View.GONE
            derivativeDotsContainer.alpha = 0f
            derivativeDotsGroup.removeAllViews()
        }
    }

    private fun loadSelectedFormat(holder: ViewHolder, group: ImageGroup, format: String) {
        if (group.isMultiCamera) {
            val lenses = getMultiCameraLenses(group)
            val idx = (selectedLensIndices[group.baseName] ?: 0).coerceIn(0, (lenses.size - 1).coerceAtLeast(0))
            val lens = lenses.getOrNull(idx)
            when (format) {
                FORMAT_JPG -> {
                    val uri = lens?.jpgUri ?: lens?.dngUri
                    uri?.let { loadImage(holder, it, version = group.lastModified) }
                }
                FORMAT_DNG -> {
                    val uri = lens?.dngUri ?: lens?.jpgUri
                    uri?.let { loadImage(holder, it) }
                }
            }
            return
        }

        if (format == FORMAT_DNG) {
            stopMotionVideo(holder)
            val isRawVideo = group.isRawVideo || group.rawVideoUri != null
            if (isRawVideo) {
                val rawUri = group.rawVideoUri
                if (rawUri != null) {
                    loadRawVideoPosterFrame(holder, rawUri, group)
                }
                return
            }

            if (group.isHalfFrame()) {
                loadHalfFrameDngs(holder, group, group.editConfig?.zoomFactor ?: 1.0f)
            } else {
                val isCinemaDngGroup = group.isCinemaDng || group.cinemaDngFolderUri != null || group.cinemaDngFirstFrameUri != null || group.cinemaDngFrameUris.isNotEmpty()
                val dngUri = if (isCinemaDngGroup) {
                    holder.activeCinemaDngFrameUri ?: group.cinemaDngFirstFrameUri ?: group.cinemaDngFrameUris.firstOrNull()
                } else {
                    group.dngUri ?: group.dngUri1 ?: group.dngUri2
                }
                dngUri?.let { loadImage(holder, it) }
            }
            return
        }

        // FORMAT_JPG (Derivative version loading)
        val derivatives = getDerivativeUris(group)
        val activeIndex = (selectedDerivativeIndices[group.baseName] ?: 0).coerceIn(0, (derivatives.size - 1).coerceAtLeast(0))
        val activeDerivativeUri = derivatives.getOrNull(activeIndex) ?: group.jpgUri ?: group.mp4VideoUri

        if (activeDerivativeUri != null) {
            val isMp4 = activeDerivativeUri.toString().endsWith(".mp4", ignoreCase = true) || (group.isMp4Video && group.rawVideoUri == null)
            if (isMp4) {
                holder.binding.videoView.visibility = View.GONE
                holder.binding.imageView.visibility = View.VISIBLE
                loadMp4PosterFrame(holder, activeDerivativeUri)
            } else {
                loadImage(holder, activeDerivativeUri, version = group.lastModified)
            }
        } else if (group.rawVideoUri != null) {
            loadRawVideoPosterFrame(holder, group.rawVideoUri!!, group)
        }
    }

    private fun loadMp4PosterFrame(holder: ViewHolder, uri: Uri) {
        val ctx = holder.binding.root.context
        scope.launch(Dispatchers.IO) {
            var bmp: Bitmap? = null
            var durMs: Long = 0L
            try {
                val retriever = android.media.MediaMetadataRetriever()
                if (uri.scheme == "content") {
                    ctx.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                        retriever.setDataSource(pfd.fileDescriptor)
                        bmp = retriever.getFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        durMs = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    }
                } else {
                    retriever.setDataSource(uri.path)
                    bmp = retriever.getFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    durMs = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                }
                retriever.release()
            } catch (e: Exception) {
                Log.w("ImageViewerAdapter", "MediaMetadataRetriever failed for $uri", e)
            }

            if (durMs > 0) {
                videoDurationCache[uri] = durMs
            }

            withContext(Dispatchers.Main) {
                if (bmp != null) {
                    if (holder.binding.imageView.rotation != 0f) {
                        holder.binding.imageView.rotation = 0f
                    }
                    holder.binding.imageView.setImageBitmap(bmp)
                } else {
                    Glide.with(holder.binding.imageView)
                        .asBitmap()
                        .load(uri)
                        .apply(RequestOptions().frame(0).diskCacheStrategy(DiskCacheStrategy.RESOURCE))
                        .into(holder.binding.imageView)
                }
                if (durMs > 0 && !holder.isPlayingVideo) {
                    holder.binding.btnVideoDuration.text = formatVideoTime(durMs)
                }
            }
        }
    }

    private fun loadRawVideoPosterFrame(holder: ViewHolder, uri: Uri, group: ImageGroup) {
        playbackController.loadRawVideoPosterFrame(holder, uri, group)
    }

    private fun setBitmapAndRecyclePrevious(holder: ViewHolder, bitmap: android.graphics.Bitmap) {
        Glide.with(holder.binding.imageView).clear(holder.binding.imageView)
        val oldManual = holder.manualBitmap
        if (oldManual != null && oldManual !== bitmap && !oldManual.isRecycled) {
            oldManual.recycle()
        }
        holder.manualBitmap = bitmap
        holder.binding.imageView.setImageBitmap(bitmap)
    }


    fun setManualBitmap(position: Int, bitmap: android.graphics.Bitmap) {
        val holder = recyclerView?.findViewHolderForAdapterPosition(position) as? ViewHolder
        if (holder != null) {
            setBitmapAndRecyclePrevious(holder, bitmap)
        } else {
            bitmap.recycle()
        }
    }

    private fun clearCurrentBitmap(holder: ViewHolder) {
        Glide.with(holder.binding.imageView).clear(holder.binding.imageView)
        holder.manualBitmap?.let { bitmap ->
            if (!bitmap.isRecycled) bitmap.recycle()
        }
        holder.manualBitmap = null
        holder.binding.imageView.setImageDrawable(null)
    }

    private fun loadDngThumbnailOnly(holder: ViewHolder, uri: Uri, zoomFactor: Float = 1.0f) {
        val trackingUri = uri.buildUpon().appendQueryParameter("zoom", zoomFactor.toString()).build()
        if (holder.currentUri == trackingUri && holder.binding.imageView.drawable != null) {
            holder.binding.loadingIndicator.visibility = View.GONE
            return
        }
        holder.loadJob?.cancel()
        holder.currentUri = trackingUri
        holder.binding.imageView.visibility = View.VISIBLE
        holder.binding.loadingIndicator.visibility = View.VISIBLE

        holder.loadJob = scope.launch {
            var thumbnailBitmap: android.graphics.Bitmap? = null
            try {
                thumbnailBitmap =
                    ImageUtils.decodeDngThumbnail(holder.binding.root.context, uri, zoomFactor)
                ensureActive()

                if (thumbnailBitmap == null) {
                    thumbnailBitmap = ImageUtils.renderDngBitmap(holder.binding.root.context, uri, reqWidth = 1024, reqHeight = 1024, zoomFactor = zoomFactor)
                    ensureActive()
                }

                if (thumbnailBitmap != null) {
                    setBitmapAndRecyclePrevious(holder, thumbnailBitmap)
                    thumbnailBitmap = null // Now owned by ViewHolder
                } else {
                    loadWithGlide(holder, uri)
                }
            } catch (e: CancellationException) {
                thumbnailBitmap?.recycle()
                throw e
            } catch (e: Exception) {
                android.util.Log.e("ImageViewerAdapter", "Failed to load DNG thumbnail: $uri", e)
                thumbnailBitmap?.recycle()
                loadWithGlide(holder, uri)
            } finally {
                holder.binding.loadingIndicator.visibility = View.GONE
            }
        }
    }


    fun loadCinemaDngFrame(holder: ViewHolder, group: ImageGroup, uri: Uri, index: Int) {
        holder.activeCinemaDngFrameIndex = index
        holder.activeCinemaDngFrameUri = uri
        stopMotionVideo(holder)
        holder.binding.videoView.visibility = View.GONE
        holder.binding.imageView.visibility = View.VISIBLE
        loadImage(holder, uri)
    }

    private fun loadImage(holder: ViewHolder, uri: Uri, zoomFactor: Float = 1.0f, version: Long = 0L) {
        if (holder.currentUri == uri && holder.currentVersion == version && holder.binding.imageView.drawable != null) {
            holder.binding.loadingIndicator.visibility = View.GONE
            return
        }
        holder.loadJob?.cancel()
        holder.binding.imageView.visibility = View.VISIBLE
        holder.binding.loadingIndicator.visibility = View.VISIBLE

        if (uri.toString().endsWith(".dng", ignoreCase = true)) {
            loadDngThumbnailOnly(holder, uri, zoomFactor)
        } else {
            loadWithGlide(holder, uri, version = version)
        }
    }

    private fun loadHalfFrameDngs(holder: ViewHolder, group: ImageGroup, zoomFactor: Float = 1.0f) {
        val trackingUri = Uri.parse("hf://${group.baseName}?layout=${group.hfLayout}&zoom=$zoomFactor")
        if (holder.currentUri == trackingUri && holder.binding.imageView.drawable != null) {
            holder.binding.loadingIndicator.visibility = View.GONE
            return
        }
        holder.loadJob?.cancel()
        holder.currentUri = trackingUri
        holder.binding.imageView.visibility = View.VISIBLE
        holder.binding.loadingIndicator.visibility = View.VISIBLE
        holder.loadJob = scope.launch {
            var composite: android.graphics.Bitmap? = null
            try {
                composite = ImageUtils.generateHalfFrameComposite(
                    holder.binding.root.context,
                    group.dngUri1,
                    group.dngUri2,
                    group.hfLayout,
                    zoomFactor
                )
                ensureActive()
                if (composite != null) {
                    setBitmapAndRecyclePrevious(holder, composite)
                    composite = null // Now owned by ViewHolder
                } else {
                    clearCurrentBitmap(holder)
                    holder.binding.imageView.setImageResource(android.R.drawable.ic_menu_gallery)
                }
            } catch (e: CancellationException) {
                composite?.recycle()
                throw e
            } catch (e: Exception) {
                android.util.Log.e("ImageViewerAdapter", "Failed to load half-frame DNGs", e)
                composite?.recycle()
                clearCurrentBitmap(holder)
                holder.binding.imageView.setImageResource(android.R.drawable.ic_menu_gallery)
            } finally {
                holder.binding.loadingIndicator.visibility = View.GONE
            }
        }
    }

    private fun loadWithGlide(holder: ViewHolder, uri: Uri, version: Long = 0L) {
        if (holder.currentUri == uri && holder.currentVersion == version && holder.binding.imageView.drawable != null) {
            holder.binding.loadingIndicator.visibility = View.GONE
            return
        }
        val previousDrawable = holder.binding.imageView.drawable
        holder.currentUri = uri
        holder.currentVersion = version

        val model = if (version > 0) {
            com.bumptech.glide.signature.ObjectKey(version)
        } else {
            null
        }

        Glide.with(holder.binding.imageView)
            .asDrawable()
            .load(uri)
            .let { if (model != null) it.signature(model) else it }
            .placeholder(previousDrawable)
            .into(object : com.bumptech.glide.request.target.DrawableImageViewTarget(holder.binding.imageView) {
                override fun onLoadFailed(errorDrawable: android.graphics.drawable.Drawable?) {
                    super.onLoadFailed(errorDrawable)
                    holder.binding.loadingIndicator.visibility = android.view.View.GONE
                }
                override fun onResourceReady(
                    resource: android.graphics.drawable.Drawable,
                    transition: com.bumptech.glide.request.transition.Transition<in android.graphics.drawable.Drawable>?
                ) {
                    super.onResourceReady(resource, transition)
                    holder.binding.loadingIndicator.visibility = android.view.View.GONE
                }
            })
    }

    override fun getItemCount(): Int = backingList.size

    override fun onViewRecycled(holder: ViewHolder) {
        holder.loadJob?.cancel()
        holder.filmstripFadeRunnable?.let { holder.binding.filmstripContainer.removeCallbacks(it) }
        holder.filmstripFadeRunnable = null
        playbackController.onViewRecycled(holder)
        clearCurrentBitmap(holder)
        holder.currentUri = null
        holder.currentBaseName = null
        holder.currentVersion = 0L
        holder.activeCinemaDngFrameIndex = 0
        holder.activeCinemaDngFrameUri = null
        holder.binding.loadingIndicator.visibility = View.GONE
        super.onViewRecycled(holder)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        playbackController.onDetachedFromRecyclerView(recyclerView, itemCount)
        super.onDetachedFromRecyclerView(recyclerView)
        this.recyclerView = null
    }

    private fun formatVideoTime(millis: Long): String = playbackController.formatVideoTime(millis)

    fun playMotionVideo(holder: ViewHolder, group: ImageGroup, onComplete: (() -> Unit)? = null) {
        val currentFormat = selectedFormats[group.baseName] ?: getSelectedFormat(group)
        playbackController.playMotionVideo(holder, group, currentFormat == FORMAT_DNG, onComplete)
    }

    fun stopMotionVideo(holder: ViewHolder) {
        playbackController.stopMotionVideo(holder)
    }

    fun playMotionVideoForPosition(position: Int, onComplete: (() -> Unit)? = null) {
        val group = backingList.getOrNull(position) ?: run { onComplete?.invoke(); return }
        val isDng = (selectedFormats[group.baseName] ?: getSelectedFormat(group)) == FORMAT_DNG
        playbackController.playMotionVideoForPosition(position, isDng, onComplete)
    }

    fun stopMotionVideoForPosition(position: Int) {
        playbackController.stopMotionVideoForPosition(position)
    }

    fun playRawVideo(holder: ViewHolder, group: ImageGroup) {
        playbackController.playRawVideo(holder, group)
    }

    fun stopRawVideo(holder: ViewHolder) {
        playbackController.stopRawVideo(holder)
    }

    fun toggleRawVideoForPosition(position: Int) {
        playbackController.toggleRawVideoForPosition(position)
    }

    fun playMp4Video(holder: ViewHolder, group: ImageGroup) {
        val derivatives = getDerivativeUris(group)
        val activeIndex = (selectedDerivativeIndices[group.baseName] ?: 0).coerceIn(0, (derivatives.size - 1).coerceAtLeast(0))
        val uri = derivatives.getOrNull(activeIndex) ?: group.mp4VideoUri ?: return
        playbackController.playMp4Video(holder, uri)
    }

    fun toggleMp4VideoForPosition(position: Int) {
        val group = backingList.getOrNull(position) ?: return
        val derivatives = getDerivativeUris(group)
        val activeIndex = (selectedDerivativeIndices[group.baseName] ?: 0).coerceIn(0, (derivatives.size - 1).coerceAtLeast(0))
        val uri = derivatives.getOrNull(activeIndex) ?: group.mp4VideoUri
        playbackController.toggleMp4VideoForPosition(position, uri)
    }

    fun updateRawVideoAdjustments(position: Int, editConfig: top.maary.darkbag.models.EditConfig?) {
        playbackController.updateRawVideoAdjustments(position, editConfig)
    }

    fun playCinemaDng(holder: ViewHolder, group: ImageGroup) {
        playbackController.playCinemaDng(holder, group)
    }

    fun stopCinemaDng(holder: ViewHolder) {
        playbackController.stopCinemaDng(holder)
    }

    fun toggleCinemaDngForPosition(position: Int) {
        playbackController.toggleCinemaDngForPosition(position)
    }

    fun selectCinemaDngFrame(position: Int, frameIndex: Int, uri: Uri? = null) {
        playbackController.selectCinemaDngFrame(position, frameIndex, uri)
    }

    fun isVideoPlaying(position: Int): Boolean = playbackController.isVideoPlaying(position)

    fun getVideoDuration(position: Int): Long = playbackController.getVideoDuration(position)

    fun getVideoCurrentPosition(position: Int): Long = playbackController.getVideoCurrentPosition(position)

    fun stopAllMotionVideos() {
        playbackController.stopAllVideos(itemCount)
    }

    fun setMotionPhotoAutoPlayEnabled(enabled: Boolean) {
        if (this.isMotionPhotoAutoPlay == enabled) return
        this.isMotionPhotoAutoPlay = enabled
        recyclerView?.let { rv ->
            for (i in 0 until itemCount) {
                (rv.findViewHolderForAdapterPosition(i) as? ViewHolder)?.let { holder ->
                    val pos = holder.bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION && pos in backingList.indices) {
                        setupButtons(holder, backingList[pos])
                    }
                }
            }
        }
    }

    fun getGroup(position: Int): ImageGroup = backingList[position]

    fun getGroups(): List<ImageGroup> = backingList

    fun getSelectedDerivativeIndex(baseName: String): Int = selectedDerivativeIndices[baseName] ?: 0

    fun cycleOrToggleFormat(position: Int): String {
        if (position !in backingList.indices) return FORMAT_JPG
        val group = backingList[position]
        val currentFormat = selectedFormats[group.baseName] ?: getSelectedFormat(group)
        val isRawSelected = currentFormat == FORMAT_DNG

        val lenses = if (group.isMultiCamera) getMultiCameraLenses(group) else emptyList()
        val activeLens = if (group.isMultiCamera && lenses.isNotEmpty()) {
            val idx = (selectedLensIndices[group.baseName] ?: 0).coerceIn(0, lenses.size - 1)
            lenses[idx]
        } else null

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
        if (position !in backingList.indices) return
        val group = backingList[position]
        val derivatives = getDerivativeUris(group)
        if (derivatives.size < 2) return
        val nextIdx = ((selectedDerivativeIndices[group.baseName] ?: 0) + 1) % derivatives.size
        selectedDerivativeIndices[group.baseName] = nextIdx
        selectedFormats[group.baseName] = FORMAT_JPG
        val holder = recyclerView?.findViewHolderForAdapterPosition(position) as? ViewHolder
        if (holder != null) {
            setupButtons(holder, group)
            loadSelectedFormat(holder, group, FORMAT_JPG)
        } else {
            notifyItemChanged(position)
        }
        onDerivativeVersionChanged?.invoke(position, derivatives[nextIdx])
        onFormatChanged?.invoke(position, FORMAT_JPG)
    }

    fun setFormat(position: Int, format: String) {
        if (position >= backingList.size) return
        val group = backingList[position]
        if (selectedFormats[group.baseName] == format) return
        selectedFormats[group.baseName] = format

        val holder = recyclerView?.findViewHolderForAdapterPosition(position) as? ViewHolder
        if (holder != null) {
            setupButtons(holder, group)
            loadSelectedFormat(holder, group, format)
        }
    }

    fun getSelectedFormat(group: ImageGroup): String {
        return selectedFormats[group.baseName] ?: when {
            group.isMultiCamera -> {
                val lenses = getMultiCameraLenses(group)
                val idx = (selectedLensIndices[group.baseName] ?: 0).coerceIn(0, (lenses.size - 1).coerceAtLeast(0))
                val lens = lenses.getOrNull(idx)
                if (lens?.jpgUri != null) FORMAT_JPG
                else if (lens?.dngUri != null) FORMAT_DNG
                else FORMAT_JPG
            }
            getDerivativeUris(group).isNotEmpty() -> FORMAT_JPG
            group.hasMasterRaw -> FORMAT_DNG
            else -> FORMAT_JPG
        }
    }

    fun getSelectedFormat(position: Int): String {
        if (position >= backingList.size) return FORMAT_JPG
        return getSelectedFormat(backingList[position])
    }

    fun getSelectedLensIndex(position: Int): Int {
        if (position >= backingList.size) return 0
        val group = backingList[position]
        return selectedLensIndices[group.baseName] ?: 0
    }

    fun setSelectedLensIndex(position: Int, lensIndex: Int) {
        if (position >= backingList.size) return
        val group = backingList[position]
        selectedLensIndices[group.baseName] = lensIndex
        val holder = recyclerView?.findViewHolderForAdapterPosition(position) as? ViewHolder
        if (holder != null) {
            setupButtons(holder, group)
            val format = selectedFormats[group.baseName] ?: getSelectedFormat(group)
            loadSelectedFormat(holder, group, format)
        }
        onMultiCameraLensChanged?.invoke(position, lensIndex)
    }

    fun getActiveCinemaDngFrame(position: Int): Pair<Int, Uri?>? {
        if (position !in backingList.indices) return null
        val group = backingList[position]
        val isCinemaDngGroup = group.isCinemaDng || group.cinemaDngFolderUri != null || group.cinemaDngFirstFrameUri != null || group.cinemaDngFrameUris.isNotEmpty()
        if (!isCinemaDngGroup) return null

        val holder = recyclerView?.findViewHolderForAdapterPosition(position) as? ViewHolder
        val index = holder?.activeCinemaDngFrameIndex ?: 0
        val uri = holder?.activeCinemaDngFrameUri ?: group.cinemaDngFrameUris.getOrNull(index) ?: group.cinemaDngFirstFrameUri
        return Pair(index, uri)
    }

    fun getCurrentUri(position: Int): Uri? {
        if (position >= backingList.size) return null
        val group = backingList[position]
        val format = getSelectedFormat(group)
        val isCinemaDngGroup = group.isCinemaDng || group.cinemaDngFolderUri != null || group.cinemaDngFirstFrameUri != null || group.cinemaDngFrameUris.isNotEmpty()
        if (isCinemaDngGroup) {
            val holder = recyclerView?.findViewHolderForAdapterPosition(position) as? ViewHolder
            return holder?.activeCinemaDngFrameUri ?: group.cinemaDngFirstFrameUri ?: group.cinemaDngFrameUris.firstOrNull() ?: group.dngUri
        }
        return if (group.isMultiCamera) {
            val lenses = getMultiCameraLenses(group)
            val idx = (selectedLensIndices[group.baseName] ?: 0).coerceIn(0, (lenses.size - 1).coerceAtLeast(0))
            val lens = lenses.getOrNull(idx)
            if (format == FORMAT_DNG) {
                lens?.dngUri ?: lens?.jpgUri
            } else {
                lens?.jpgUri ?: lens?.dngUri
            }
        } else {
            if (format == FORMAT_DNG) {
                group.rawVideoUri ?: group.dngUri ?: group.dngUri1 ?: group.dngUri2 ?: group.jpgUri
            } else {
                val derivatives = getDerivativeUris(group)
                val idx = (selectedDerivativeIndices[group.baseName] ?: 0).coerceIn(0, (derivatives.size - 1).coerceAtLeast(0))
                derivatives.getOrNull(idx) ?: group.jpgUri ?: group.mp4VideoUri ?: group.rawVideoUri ?: group.dngUri
            }
        }
    }

    fun setUiVisibility(isVisible: Boolean) {
        this.isUiVisible = isVisible
        recyclerView?.let { rv ->
            for (i in 0 until itemCount) {
                (rv.findViewHolderForAdapterPosition(i) as? ViewHolder)?.let { holder ->
                    val multiCamContainer = holder.binding.multiCameraToggleContainer
                    val dotsContainer = holder.binding.derivativeDotsContainer
                    val filmstripContainer = holder.binding.filmstripContainer
                    val durationGroup = holder.binding.videoDurationGroup
                    val group = getGroup(i)
                    val isCinemaDngGroup = group.isCinemaDng || group.cinemaDngFolderUri != null || group.cinemaDngFirstFrameUri != null || group.cinemaDngFrameUris.isNotEmpty()
                    val shouldBeVisible = isVisible && !isFormatSwitcherPersistentHidden
                    multiCamContainer.visibility = View.GONE
                    multiCamContainer.alpha = 0f
                    dotsContainer.visibility = View.GONE
                    dotsContainer.alpha = 0f
                    durationGroup.visibility = View.GONE
                    durationGroup.alpha = 0f
                    if (shouldBeVisible) {
                        if (isCinemaDngGroup) {
                            filmstripContainer.visibility = View.VISIBLE
                            filmstripContainer.animate().alpha(0.35f).setDuration(200).setListener(null).start()
                        }
                    } else {
                        if (isCinemaDngGroup) {
                            holder.filmstripFadeRunnable?.let { filmstripContainer.removeCallbacks(it) }
                            holder.filmstripFadeRunnable = null
                            filmstripContainer.animate().alpha(0f).setDuration(200)
                                .setListener(object : android.animation.AnimatorListenerAdapter() {
                                    override fun onAnimationEnd(animation: android.animation.Animator) {
                                        if (!isUiVisible || isFormatSwitcherPersistentHidden) filmstripContainer.visibility = View.GONE
                                    }
                                }).start()
                        }
                    }
                }
            }
        }
    }

    fun setFormatSwitcherPersistentHidden(hidden: Boolean) {
        if (this.isFormatSwitcherPersistentHidden == hidden) return
        this.isFormatSwitcherPersistentHidden = hidden
        if (hidden) {
            stopAllMotionVideos()
        }
        recyclerView?.let { rv ->
            for (i in 0 until itemCount) {
                (rv.findViewHolderForAdapterPosition(i) as? ViewHolder)?.let { holder ->
                    val multiCamContainer = holder.binding.multiCameraToggleContainer
                    val dotsContainer = holder.binding.derivativeDotsContainer
                    val filmstripContainer = holder.binding.filmstripContainer
                    val durationGroup = holder.binding.videoDurationGroup
                    val group = getGroup(i)
                    val isCinemaDngGroup = group.isCinemaDng || group.cinemaDngFolderUri != null || group.cinemaDngFirstFrameUri != null || group.cinemaDngFrameUris.isNotEmpty()
                    val shouldBeVisible = isUiVisible && !isFormatSwitcherPersistentHidden
                    multiCamContainer.visibility = View.GONE
                    multiCamContainer.alpha = 0f
                    dotsContainer.visibility = View.GONE
                    dotsContainer.alpha = 0f
                    durationGroup.visibility = View.GONE
                    durationGroup.alpha = 0f
                    if (shouldBeVisible) {
                        if (isCinemaDngGroup) {
                            filmstripContainer.visibility = View.VISIBLE
                            filmstripContainer.animate().alpha(0.35f).setDuration(200).setListener(null).start()
                        }
                    } else {
                        if (isCinemaDngGroup) {
                            holder.filmstripFadeRunnable?.let { filmstripContainer.removeCallbacks(it) }
                            holder.filmstripFadeRunnable = null
                            filmstripContainer.animate().alpha(0f).setDuration(200)
                                .setListener(object : android.animation.AnimatorListenerAdapter() {
                                    override fun onAnimationEnd(animation: android.animation.Animator) {
                                        if (isFormatSwitcherPersistentHidden) filmstripContainer.visibility = View.GONE
                                    }
                                }).start()
                        }
                    }
                }
            }
        }
    }

    fun cancelLoadJob(position: Int, clearView: Boolean = true) {
        val holder = recyclerView?.findViewHolderForAdapterPosition(position) as? ViewHolder
        if (holder != null) {
            holder.loadJob?.cancel()
            if (clearView) {
                clearCurrentBitmap(holder)
            }
            holder.binding.loadingIndicator.visibility = View.GONE
        }
    }

    fun updateGroups(newGroups: List<ImageGroup>, commitCallback: (() -> Unit)? = null) {
        differ.submitList(newGroups, commitCallback)
    }

    fun findGroupIndex(baseName: String): Int {
        return backingList.indexOfFirst { it.baseName == baseName }
    }

    fun setFormatForGroup(baseName: String, format: String) {
        selectedFormats[baseName] = format
    }

    fun forceFormat(baseName: String, format: String) {

        selectedFormats[baseName] = format
        val index = findGroupIndex(baseName)
        if (index != -1) {
            val holder = recyclerView?.findViewHolderForAdapterPosition(index) as? ViewHolder
            if (holder != null) {
                val group = backingList[index]
                setupButtons(holder, group)
                loadSelectedFormat(holder, group, format)
            } else {
                notifyItemChanged(index)
            }
        }
    }
}
