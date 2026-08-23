package top.maary.darkbag.fragments

import android.graphics.Matrix
import android.graphics.RectF
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
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

    private val margin = context.resources.getDimensionPixelSize(R.dimen.margin_medium).toFloat()
    private val radius = context.resources.getDimension(R.dimen.radius_medium)

    var onImageTapped: (() -> Unit)? = null
    var onZoomChanged: ((Boolean) -> Unit)? = null
    var onLongPressStarted: ((top.maary.darkbag.ui.ZoomableImageView) -> Unit)? = null
    var onLongPressEnded: ((top.maary.darkbag.ui.ZoomableImageView) -> Unit)? = null
    var onCurrentListChanged: ((List<ImageGroup>, List<ImageGroup>) -> Unit)? = null
    var onMotionPhotoIndicatorTapped: ((Int) -> Unit)? = null
    var isMotionPhotoAutoPlay: Boolean = true

    private var recyclerView: RecyclerView? = null
    private val selectedFormats = mutableMapOf<String, String>()
    private val selectedLensIndices = mutableMapOf<String, Int>()
    var onMultiCameraLensChanged: ((position: Int, lensIndex: Int) -> Unit)? = null
    private var isUiVisible = true
    private var isFormatSwitcherPersistentHidden = false

    private val diffCallback = object : DiffUtil.ItemCallback<ImageGroup>() {
        override fun areItemsTheSame(oldItem: ImageGroup, newItem: ImageGroup): Boolean {
            return oldItem.baseName == newItem.baseName
        }

        override fun areContentsTheSame(oldItem: ImageGroup, newItem: ImageGroup): Boolean {
            return oldItem == newItem && oldItem.metadataLoaded == newItem.metadataLoaded && oldItem.isMotionPhoto == newItem.isMotionPhoto
        }

        override fun getChangePayload(oldItem: ImageGroup, newItem: ImageGroup): Any? {
            val payloads = mutableSetOf<String>()
            if (oldItem.jpgUri != newItem.jpgUri) payloads.add("JPG_URI_CHANGED")
            if (oldItem.dngUri != newItem.dngUri || oldItem.dngUri1 != newItem.dngUri1 || oldItem.dngUri2 != newItem.dngUri2) payloads.add("DNG_AVAILABILITY_CHANGED")
            if (oldItem.metadataLoaded != newItem.metadataLoaded) payloads.add("METADATA_LOADED")
            if (oldItem.lastModified != newItem.lastModified) payloads.add("LAST_MODIFIED_CHANGED")
            if (oldItem.editConfig != newItem.editConfig) payloads.add("EDIT_CONFIG_CHANGED")
            if (oldItem.isMotionPhoto != newItem.isMotionPhoto) payloads.add("MOTION_PHOTO_CHANGED")

            return if (payloads.isEmpty()) null else payloads
        }
    }

    private val differ = AsyncListDiffer(this, diffCallback)

    init {
        differ.addListListener { previousList, currentList ->
            onCurrentListChanged?.invoke(previousList, currentList)
        }
        differ.submitList(initialGroups)
    }

    class ViewHolder(val binding: ItemImageGroupBinding) : RecyclerView.ViewHolder(binding.root) {
        var loadJob: Job? = null
        var extractJob: Job? = null
        var player: androidx.media3.exoplayer.ExoPlayer? = null
        var isPlayingVideo: Boolean = false
        var playCompletionCallback: (() -> Unit)? = null
        var manualBitmap: android.graphics.Bitmap? = null
        var currentUri: Uri? = null
        var currentBaseName: String? = null
        var currentVersion: Long = 0L
        val videoOutlineRect = android.graphics.Rect()
        var videoOutlineRadius = 0f

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
        val currentList = differ.currentList.toMutableList()
        val index = currentList.indexOfFirst { it.baseName == updatedGroup.baseName }
        if (index != -1) {
            currentList[index] = updatedGroup
            differ.submitList(currentList, onComplete)
        } else {
            onComplete?.invoke()
        }
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        this.recyclerView = recyclerView
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemImageGroupBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty()) {
            val group = differ.currentList[position]
            val importantPayloads = setOf("JPG_URI_CHANGED", "LAST_MODIFIED_CHANGED", "EDIT_CONFIG_CHANGED")

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
        val group = differ.currentList[position]
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

        val shouldShow = isUiVisible && !isFormatSwitcherPersistentHidden
        holder.binding.formatToggleGroup.visibility = if (shouldShow) View.VISIBLE else View.GONE
        holder.binding.formatToggleGroup.alpha = if (shouldShow) 1f else 0f
        holder.binding.motionPhotoToggleGroup.visibility = if (shouldShow) View.VISIBLE else View.GONE
        holder.binding.motionPhotoToggleGroup.alpha = if (shouldShow) 1f else 0f

        holder.binding.imageView.onZoomChanged = { isZoomed ->
            onZoomChanged?.invoke(isZoomed)
            val currentlyShouldShow = isUiVisible && !isZoomed && !isFormatSwitcherPersistentHidden
            holder.binding.formatToggleGroup.visibility = if (currentlyShouldShow) View.VISIBLE else View.GONE
            holder.binding.formatToggleGroup.alpha = if (currentlyShouldShow) 1f else 0f
            holder.binding.motionPhotoToggleGroup.visibility = if (currentlyShouldShow) View.VISIBLE else View.GONE
            holder.binding.motionPhotoToggleGroup.alpha = if (currentlyShouldShow) 1f else 0f
        }

        if (!isSameImage) {
            holder.binding.formatToggleGroup.translationX = 0f
            holder.binding.formatToggleGroup.translationY = 0f
            holder.binding.motionPhotoToggleGroup.translationX = 0f
            holder.binding.motionPhotoToggleGroup.translationY = 0f
            stopMotionVideo(holder)
        }

        holder.binding.imageView.onMatrixChanged = { rect ->
            // Use measured dimensions if layout hasn't happened yet
            val viewWidth = holder.binding.imageView.measuredWidth.takeIf { it > 0 } ?: holder.binding.imageView.width
            val viewHeight = holder.binding.imageView.measuredHeight.takeIf { it > 0 } ?: holder.binding.imageView.height

            if (viewWidth > 0 && viewHeight > 0) {
                // Determine actual visual right margin (distance from right edge of screen)
                val visualRightSpace = viewWidth - rect.right
                val rightMargin = if (visualRightSpace > 0) visualRightSpace else 0f
                val visualLeftSpace = rect.left
                val leftMargin = if (visualLeftSpace > 0) visualLeftSpace else 0f

                // Determine actual visual top margin
                val topMargin = if (rect.top > 0) rect.top else 0f

                // Instead of layoutParams (which causes requestLayout and UI jank), use translationX/Y.
                holder.binding.formatToggleGroup.translationX = -rightMargin
                holder.binding.formatToggleGroup.translationY = topMargin
                holder.binding.motionPhotoToggleGroup.translationX = leftMargin
                holder.binding.motionPhotoToggleGroup.translationY = topMargin

                updateVideoViewBounds(holder, rect)
            }
        }

        val format = getSelectedFormat(group)
        selectedFormats[group.baseName] = format

        setupButtons(holder, group)

        loadSelectedFormat(holder, group, format)
    }

    private fun setupButtons(holder: ViewHolder, group: ImageGroup) {
        with(holder.binding) {
            val currentFormat = selectedFormats[group.baseName] ?: getSelectedFormat(group)
            val isRawSelected = currentFormat == FORMAT_DNG

            // 1. Motion Photo button setup (disabled/hidden in RAW state)
            val isMotion = group.isMotionPhoto && group.jpgUri != null && !isFormatSwitcherPersistentHidden && !isRawSelected
            if (isMotion) {
                btnMotionPhotoIndicator.visibility = View.VISIBLE
                if (isMotionPhotoAutoPlay) {
                    btnMotionPhotoIndicator.setIconResource(R.drawable.ic_play_arrow)
                    btnMotionPhotoIndicator.contentDescription = "Motion Photo Auto-play On"
                } else {
                    btnMotionPhotoIndicator.setIconResource(R.drawable.ic_pause)
                    btnMotionPhotoIndicator.contentDescription = "Motion Photo Auto-play Off"
                }
                btnMotionPhotoIndicator.setOnClickListener {
                    val currentPos = holder.bindingAdapterPosition
                    if (currentPos != RecyclerView.NO_POSITION) {
                        onMotionPhotoIndicatorTapped?.invoke(currentPos)
                    }
                }
            } else {
                btnMotionPhotoIndicator.visibility = View.GONE
                btnMotionPhotoIndicator.setOnClickListener(null)
            }

            // 2. RAW Format indicator setup
            val hasJpg = if (group.isMultiCamera) {
                val idx = selectedLensIndices[group.baseName] ?: 0
                group.multiJpgUris.getOrNull(idx) != null
            } else {
                group.jpgUri != null
            }

            val hasDng = if (group.isMultiCamera) {
                val idx = selectedLensIndices[group.baseName] ?: 0
                group.multiDngUris.getOrNull(idx) != null
            } else {
                group.dngUri != null || group.dngUri1 != null || group.dngUri2 != null
            }

            if (!hasDng) {
                // Only JPG exists, hide the indicator completely
                btnFormatIndicator.visibility = View.GONE
                return
            } else {
                btnFormatIndicator.visibility = View.VISIBLE
            }

            btnFormatIndicator.isSelected = isRawSelected

            // If we have both, it's clickable
            if (hasJpg && hasDng) {
                btnFormatIndicator.isClickable = true
                btnFormatIndicator.setOnClickListener {
                    val currentPos = holder.bindingAdapterPosition
                    if (currentPos == RecyclerView.NO_POSITION) return@setOnClickListener
                    val currentGroup = getGroup(currentPos)

                    btnFormatIndicator.isSelected = !btnFormatIndicator.isSelected
                    val newFormat = if (btnFormatIndicator.isSelected) FORMAT_DNG else FORMAT_JPG
                    selectedFormats[currentGroup.baseName] = newFormat
                    stopMotionVideo(holder)
                    setupButtons(holder, currentGroup)
                    loadSelectedFormat(holder, currentGroup, newFormat)
                }
            } else {
                // Only RAW exists, show badge but make it unclickable
                btnFormatIndicator.isClickable = false
                btnFormatIndicator.setOnClickListener(null)
            }
        }
    }

    private fun loadSelectedFormat(holder: ViewHolder, group: ImageGroup, format: String) {
        if (group.isMultiCamera) {
            val idx = selectedLensIndices[group.baseName] ?: 0
            when (format) {
                FORMAT_JPG -> {
                    val uri = group.multiJpgUris.getOrNull(idx) ?: group.multiDngUris.getOrNull(idx)
                    uri?.let { loadImage(holder, it, version = group.lastModified) }
                }
                FORMAT_DNG -> {
                    val uri = group.multiDngUris.getOrNull(idx) ?: group.multiJpgUris.getOrNull(idx)
                    uri?.let { loadImage(holder, it) }
                }
            }
            return
        }

        when (format) {
            FORMAT_JPG -> group.jpgUri?.let { loadImage(holder, it, version = group.lastModified) }
            FORMAT_DNG -> {
                if (group.isHalfFrame()) {
                    loadHalfFrameDngs(holder, group, group.editConfig?.zoomFactor ?: 1.0f)
                } else {
                    val dngUri = group.dngUri ?: group.dngUri1 ?: group.dngUri2
                    dngUri?.let { loadImage(holder, it) }
                }
            }
        }
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

    override fun getItemCount(): Int = differ.currentList.size

    override fun onViewRecycled(holder: ViewHolder) {
        holder.loadJob?.cancel()
        stopMotionVideo(holder)
        holder.player?.release()
        holder.player = null
        clearCurrentBitmap(holder)
        holder.currentUri = null
        holder.currentBaseName = null
        holder.currentVersion = 0L
        holder.binding.loadingIndicator.visibility = View.GONE
        super.onViewRecycled(holder)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        stopAllMotionVideos()
        for (i in 0 until itemCount) {
            (recyclerView.findViewHolderForAdapterPosition(i) as? ViewHolder)?.let { holder ->
                holder.player?.release()
                holder.player = null
            }
        }
        super.onDetachedFromRecyclerView(recyclerView)
        this.recyclerView = null
    }

    fun playMotionVideo(holder: ViewHolder, group: ImageGroup, onComplete: (() -> Unit)? = null) {
        val currentFormat = selectedFormats[group.baseName] ?: getSelectedFormat(group)
        if (!group.isMotionPhoto || group.jpgUri == null || currentFormat == FORMAT_DNG) {
            onComplete?.invoke()
            return
        }
        val context = holder.binding.root.context
        holder.extractJob?.cancel()
        holder.playCompletionCallback = onComplete

        // Align videoView bounds immediately to match current imageView rect
        val iv = holder.binding.imageView
        val d = iv.drawable
        if (d != null && iv.width > 0 && iv.height > 0) {
            val rect = RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
            iv.imageMatrix.mapRect(rect)
            rect.intersect(0f, 0f, iv.width.toFloat(), iv.height.toFloat())
            updateVideoViewBounds(holder, rect)
        }

        // Make TextureView part of layout hierarchy with alpha 0 so its SurfaceTexture is allocated immediately
        holder.binding.videoView.alpha = 0f
        holder.binding.videoView.visibility = View.VISIBLE

        holder.extractJob = scope.launch {
            val videoFile = withContext(Dispatchers.IO) {
                val info = if (group.motionPhotoVideoLength > 0) {
                    top.maary.darkbag.motionphoto.MotionPhotoInfo(
                        videoLength = group.motionPhotoVideoLength,
                        presentationTimestampUs = group.motionPhotoPtsUs
                    )
                } else null
                top.maary.darkbag.motionphoto.MotionPhotoReader.extractVideoToCache(
                    context,
                    group.jpgUri,
                    group.baseName,
                    info
                )
            }
            ensureActive()
            if (videoFile == null || !videoFile.exists()) {
                stopMotionVideo(holder)
                val cb = holder.playCompletionCallback
                holder.playCompletionCallback = null
                cb?.invoke()
                return@launch
            }

            val player = holder.player ?: androidx.media3.exoplayer.ExoPlayer.Builder(context).build().also { p ->
                holder.player = p
                p.setVideoTextureView(holder.binding.videoView)
                p.addListener(object : androidx.media3.common.Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == androidx.media3.common.Player.STATE_ENDED) {
                            stopMotionVideo(holder)
                            val cb = holder.playCompletionCallback
                            holder.playCompletionCallback = null
                            cb?.invoke()
                        }
                    }

                    override fun onRenderedFirstFrame() {
                        holder.binding.videoView.alpha = 1f
                    }

                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        android.util.Log.e("ImageViewerAdapter", "Motion photo playback error", error)
                        stopMotionVideo(holder)
                        val cb = holder.playCompletionCallback
                        holder.playCompletionCallback = null
                        cb?.invoke()
                    }
                })
            }

            val mediaItem = androidx.media3.common.MediaItem.fromUri(Uri.fromFile(videoFile))
            player.setMediaItem(mediaItem)
            player.repeatMode = androidx.media3.common.Player.REPEAT_MODE_OFF
            player.seekTo(0)
            player.prepare()
            player.play()
            holder.isPlayingVideo = true
        }
    }

    fun stopMotionVideo(holder: ViewHolder) {
        holder.extractJob?.cancel()
        holder.extractJob = null
        holder.player?.stop()
        holder.binding.videoView.visibility = View.INVISIBLE
        holder.binding.videoView.alpha = 0f
        holder.isPlayingVideo = false
    }

    private fun updateVideoViewBounds(holder: ViewHolder, rect: RectF) {
        val videoView = holder.binding.videoView
        val viewWidth = videoView.width.toFloat().takeIf { it > 0 }
            ?: holder.binding.imageView.measuredWidth.toFloat().takeIf { it > 0 }
            ?: return
        val viewHeight = videoView.height.toFloat().takeIf { it > 0 }
            ?: holder.binding.imageView.measuredHeight.toFloat().takeIf { it > 0 }
            ?: return

        if (rect.width() <= 0 || rect.height() <= 0) return

        val matrix = Matrix()
        val scaleX = rect.width() / viewWidth
        val scaleY = rect.height() / viewHeight
        matrix.setScale(scaleX, scaleY)
        matrix.postTranslate(rect.left, rect.top)
        videoView.setTransform(matrix)

        holder.videoOutlineRect.set(
            rect.left.toInt(),
            rect.top.toInt(),
            rect.right.toInt(),
            rect.bottom.toInt()
        )
        holder.videoOutlineRadius = radius
        videoView.invalidateOutline()
    }

    fun playMotionVideoForPosition(position: Int, onComplete: (() -> Unit)? = null) {
        val holder = recyclerView?.findViewHolderForAdapterPosition(position) as? ViewHolder
        if (holder != null && position in differ.currentList.indices) {
            playMotionVideo(holder, differ.currentList[position], onComplete)
        } else {
            onComplete?.invoke()
        }
    }

    fun stopMotionVideoForPosition(position: Int) {
        val holder = recyclerView?.findViewHolderForAdapterPosition(position) as? ViewHolder
        if (holder != null) {
            stopMotionVideo(holder)
        }
    }

    fun stopAllMotionVideos() {
        recyclerView?.let { rv ->
            for (i in 0 until itemCount) {
                (rv.findViewHolderForAdapterPosition(i) as? ViewHolder)?.let { holder ->
                    stopMotionVideo(holder)
                }
            }
        }
    }

    fun setMotionPhotoAutoPlayEnabled(enabled: Boolean) {
        if (this.isMotionPhotoAutoPlay == enabled) return
        this.isMotionPhotoAutoPlay = enabled
        recyclerView?.let { rv ->
            for (i in 0 until itemCount) {
                (rv.findViewHolderForAdapterPosition(i) as? ViewHolder)?.let { holder ->
                    val pos = holder.bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION && pos in differ.currentList.indices) {
                        setupButtons(holder, differ.currentList[pos])
                    }
                }
            }
        }
    }

    fun getGroup(position: Int): ImageGroup = differ.currentList[position]

    fun getGroups(): List<ImageGroup> = differ.currentList

    fun setFormat(position: Int, format: String) {
        if (position >= differ.currentList.size) return
        val group = differ.currentList[position]
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
                val idx = selectedLensIndices[group.baseName] ?: 0
                if (group.multiJpgUris.getOrNull(idx) != null) FORMAT_JPG
                else if (group.multiDngUris.getOrNull(idx) != null) FORMAT_DNG
                else FORMAT_JPG
            }
            group.jpgUri != null -> FORMAT_JPG
            group.isHalfFrame() || group.dngUri != null || group.dngUri1 != null || group.dngUri2 != null -> FORMAT_DNG
            else -> FORMAT_JPG
        }
    }

    fun getSelectedFormat(position: Int): String {
        if (position >= differ.currentList.size) return FORMAT_JPG
        return getSelectedFormat(differ.currentList[position])
    }

    fun getSelectedLensIndex(position: Int): Int {
        if (position >= differ.currentList.size) return 0
        val group = differ.currentList[position]
        return selectedLensIndices[group.baseName] ?: 0
    }

    fun setSelectedLensIndex(position: Int, lensIndex: Int) {
        if (position >= differ.currentList.size) return
        val group = differ.currentList[position]
        selectedLensIndices[group.baseName] = lensIndex
        val holder = recyclerView?.findViewHolderForAdapterPosition(position) as? ViewHolder
        if (holder != null) {
            setupButtons(holder, group)
            val format = selectedFormats[group.baseName] ?: getSelectedFormat(group)
            loadSelectedFormat(holder, group, format)
        }
        onMultiCameraLensChanged?.invoke(position, lensIndex)
    }

    fun getCurrentUri(position: Int): Uri? {
        if (position >= differ.currentList.size) return null
        val group = differ.currentList[position]
        val format = getSelectedFormat(group)
        return if (group.isMultiCamera) {
            val idx = selectedLensIndices[group.baseName] ?: 0
            if (format == FORMAT_DNG) {
                group.multiDngUris.getOrNull(idx) ?: group.multiJpgUris.getOrNull(idx)
            } else {
                group.multiJpgUris.getOrNull(idx) ?: group.multiDngUris.getOrNull(idx)
            }
        } else {
            if (format == FORMAT_DNG) {
                group.dngUri ?: group.dngUri1 ?: group.dngUri2
            } else {
                group.jpgUri
            }
        }
    }

    fun setUiVisibility(isVisible: Boolean) {
        this.isUiVisible = isVisible
        recyclerView?.let { rv ->
            for (i in 0 until itemCount) {
                (rv.findViewHolderForAdapterPosition(i) as? ViewHolder)?.let { holder ->
                    val formatGroup = holder.binding.formatToggleGroup
                    val motionGroup = holder.binding.motionPhotoToggleGroup
                    val shouldBeVisible = isVisible && !isFormatSwitcherPersistentHidden
                    if (shouldBeVisible) {
                        formatGroup.visibility = View.VISIBLE
                        formatGroup.animate().alpha(1f).setDuration(200).setListener(null).start()
                        motionGroup.visibility = View.VISIBLE
                        motionGroup.animate().alpha(1f).setDuration(200).setListener(null).start()
                    } else {
                        formatGroup.animate().alpha(0f).setDuration(200)
                            .setListener(object : android.animation.AnimatorListenerAdapter() {
                                override fun onAnimationEnd(animation: android.animation.Animator) {
                                    if (!isUiVisible || isFormatSwitcherPersistentHidden) formatGroup.visibility = View.GONE
                                }
                            }).start()
                        motionGroup.animate().alpha(0f).setDuration(200)
                            .setListener(object : android.animation.AnimatorListenerAdapter() {
                                override fun onAnimationEnd(animation: android.animation.Animator) {
                                    if (!isUiVisible || isFormatSwitcherPersistentHidden) motionGroup.visibility = View.GONE
                                }
                            }).start()
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
                    val formatGroup = holder.binding.formatToggleGroup
                    val motionGroup = holder.binding.motionPhotoToggleGroup
                    val shouldBeVisible = isUiVisible && !isFormatSwitcherPersistentHidden
                    if (shouldBeVisible) {
                        formatGroup.visibility = View.VISIBLE
                        formatGroup.animate().alpha(1f).setDuration(200).setListener(null).start()
                        motionGroup.visibility = View.VISIBLE
                        motionGroup.animate().alpha(1f).setDuration(200).setListener(null).start()
                    } else {
                        formatGroup.animate().alpha(0f).setDuration(200)
                            .setListener(object : android.animation.AnimatorListenerAdapter() {
                                override fun onAnimationEnd(animation: android.animation.Animator) {
                                    if (isFormatSwitcherPersistentHidden) formatGroup.visibility = View.GONE
                                }
                            }).start()
                        motionGroup.animate().alpha(0f).setDuration(200)
                            .setListener(object : android.animation.AnimatorListenerAdapter() {
                                override fun onAnimationEnd(animation: android.animation.Animator) {
                                    if (isFormatSwitcherPersistentHidden) motionGroup.visibility = View.GONE
                                }
                            }).start()
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
        return differ.currentList.indexOfFirst { it.baseName == baseName }
    }

    fun forceFormat(baseName: String, format: String) {

        selectedFormats[baseName] = format
        val index = findGroupIndex(baseName)
        if (index != -1) {
            val holder = recyclerView?.findViewHolderForAdapterPosition(index) as? ViewHolder
            if (holder != null) {
                val group = differ.currentList[index]
                setupButtons(holder, group)
                loadSelectedFormat(holder, group, format)
            } else {
                notifyItemChanged(index)
            }
        }
    }
}
