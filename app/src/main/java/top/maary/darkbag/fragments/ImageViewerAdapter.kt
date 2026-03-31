package top.maary.darkbag.fragments

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

    private val margin = context.resources.getDimensionPixelSize(R.dimen.margin_medium).toFloat()
    private val radius = context.resources.getDimension(R.dimen.radius_medium)

    var onImageTapped: (() -> Unit)? = null
    var onZoomChanged: ((Boolean) -> Unit)? = null
    var onLongPressStarted: ((top.maary.darkbag.ui.ZoomableImageView) -> Unit)? = null
    var onLongPressEnded: ((top.maary.darkbag.ui.ZoomableImageView) -> Unit)? = null
    private var recyclerView: RecyclerView? = null
    private val selectedFormats = mutableMapOf<Int, String>()
    private var isUiVisible = true
    private var isFormatSwitcherPersistentHidden = false

    private val diffCallback = object : DiffUtil.ItemCallback<ImageGroup>() {
        override fun areItemsTheSame(oldItem: ImageGroup, newItem: ImageGroup): Boolean {
            return oldItem.baseName == newItem.baseName
        }

        override fun areContentsTheSame(oldItem: ImageGroup, newItem: ImageGroup): Boolean {
            return oldItem == newItem && oldItem.metadataLoaded == newItem.metadataLoaded
        }

        override fun getChangePayload(oldItem: ImageGroup, newItem: ImageGroup): Any? {
            val payloads = mutableSetOf<String>()
            if (oldItem.jpgUri != newItem.jpgUri) payloads.add("JPG_URI_CHANGED")
            if (oldItem.dngUri != newItem.dngUri || oldItem.dngUri1 != newItem.dngUri1 || oldItem.dngUri2 != newItem.dngUri2) payloads.add("DNG_AVAILABILITY_CHANGED")
            if (oldItem.metadataLoaded != newItem.metadataLoaded) payloads.add("METADATA_LOADED")
            if (oldItem.lastModified != newItem.lastModified) payloads.add("LAST_MODIFIED_CHANGED")
            if (oldItem.editConfig != newItem.editConfig) payloads.add("EDIT_CONFIG_CHANGED")

            return if (payloads.isEmpty()) null else payloads
        }
    }

    private val differ = AsyncListDiffer(this, diffCallback)

    init {
        differ.submitList(initialGroups)
    }

    class ViewHolder(val binding: ItemImageGroupBinding) : RecyclerView.ViewHolder(binding.root) {
        var loadJob: Job? = null
        var manualBitmap: android.graphics.Bitmap? = null
        var currentUri: Uri? = null
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        this.recyclerView = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        this.recyclerView = null
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
                setupButtons(holder, group, position)
            }
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val group = differ.currentList[position]
        holder.loadJob?.cancel()

        holder.binding.imageView.setVisualParams(margin, radius)

        holder.binding.imageView.onTapped = { onImageTapped?.invoke() }
        holder.binding.imageView.onLongPressStarted = { onLongPressStarted?.invoke(it) }
        holder.binding.imageView.onLongPressEnded = { onLongPressEnded?.invoke(it) }

        val shouldShow = isUiVisible && !isFormatSwitcherPersistentHidden
        holder.binding.formatToggleGroup.visibility = if (shouldShow) View.VISIBLE else View.GONE
        holder.binding.formatToggleGroup.alpha = if (shouldShow) 1f else 0f

        holder.binding.imageView.onZoomChanged = { isZoomed ->
            onZoomChanged?.invoke(isZoomed)
            val currentlyShouldShow = isUiVisible && !isZoomed && !isFormatSwitcherPersistentHidden
            holder.binding.formatToggleGroup.visibility = if (currentlyShouldShow) View.VISIBLE else View.GONE
            holder.binding.formatToggleGroup.alpha = if (currentlyShouldShow) 1f else 0f
        }

        setupButtons(holder, group, position)

        val format = selectedFormats[position] ?: when {
            group.jpgUri != null -> "JPG"
            group.isHalfFrame() || group.dngUri != null -> "DNG"
            else -> "JPG"
        }
        selectedFormats[position] = format

        val targetId = when (format) {
            "JPG" -> R.id.btnJpg
            "DNG" -> R.id.btnDng
            else -> R.id.btnJpg
        }
        selectButton(holder, targetId)
        loadSelectedFormat(holder, group, format)
    }

    private fun setupButtons(holder: ViewHolder, group: ImageGroup, position: Int) {
        with(holder.binding) {
            btnJpg.visibility = if (group.jpgUri != null) View.VISIBLE else View.GONE
            btnDng.visibility = if (group.dngUri != null || group.dngUri1 != null || group.dngUri2 != null) View.VISIBLE else View.GONE

            btnJpg.setOnClickListener {
                if (selectedFormats[position] == "JPG") return@setOnClickListener
                selectedFormats[position] = "JPG"
                selectButton(holder, R.id.btnJpg)
                loadSelectedFormat(holder, group, "JPG")
            }
            btnDng.setOnClickListener {
                if (selectedFormats[position] == "DNG") return@setOnClickListener
                selectedFormats[position] = "DNG"
                selectButton(holder, R.id.btnDng)
                loadSelectedFormat(holder, group, "DNG")
            }
        }
    }

    private fun loadSelectedFormat(holder: ViewHolder, group: ImageGroup, format: String) {
        when (format) {
            "JPG" -> group.jpgUri?.let { loadImage(holder, it, version = group.lastModified) }
            "DNG" -> {
                if (group.isHalfFrame()) {
                    loadHalfFrameDngs(holder, group, group.editConfig?.zoomFactor ?: 1.0f)
                } else {
                    group.dngUri?.let { loadImage(holder, it) }
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

    private fun selectButton(holder: ViewHolder, selectedId: Int) {
        val group = holder.binding.formatToggleGroup
        val colorPrimary = com.google.android.material.color.MaterialColors.getColor(group, android.R.attr.colorPrimary)
        val colorDimWhite = android.graphics.Color.parseColor("#B3FFFFFF") // 70% white
        val colorWhite = android.graphics.Color.WHITE

        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i) as? com.google.android.material.button.MaterialButton
            if (child != null) {
                val isSelected = child.id == selectedId
                if (isSelected) {
                    child.setIconTint(android.content.res.ColorStateList.valueOf(colorPrimary))
                    child.icon?.alpha = 255
                } else {
                    child.setIconTint(android.content.res.ColorStateList.valueOf(colorWhite))
                    child.icon?.alpha = 128
                }
            }
        }
    }

    private fun loadImage(holder: ViewHolder, uri: Uri, zoomFactor: Float = 1.0f, version: Long = 0L) {
        if (holder.currentUri == uri && holder.binding.imageView.drawable != null) {
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
        if (holder.currentUri == uri && holder.binding.imageView.drawable != null) {
            holder.binding.loadingIndicator.visibility = View.GONE
            return
        }
        val previousDrawable = holder.binding.imageView.drawable
        holder.currentUri = uri

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
        clearCurrentBitmap(holder)
        holder.currentUri = null
        holder.binding.loadingIndicator.visibility = View.GONE
        super.onViewRecycled(holder)
    }

    fun getGroup(position: Int): ImageGroup = differ.currentList[position]

    fun getGroups(): List<ImageGroup> = differ.currentList

    fun setFormat(position: Int, format: String) {
        if (position >= differ.currentList.size) return
        val group = differ.currentList[position]
        if (selectedFormats[position] == format) return
        selectedFormats[position] = format

        val holder = recyclerView?.findViewHolderForAdapterPosition(position) as? ViewHolder
        if (holder != null) {
            val targetId = when (format) {
                "JPG" -> R.id.btnJpg
                "DNG" -> R.id.btnDng
                else -> R.id.btnJpg
            }
            selectButton(holder, targetId)
            loadSelectedFormat(holder, group, format)
        }
    }

    fun getSelectedFormat(position: Int): String {
        return selectedFormats[position] ?: "JPG"
    }

    fun setUiVisibility(isVisible: Boolean) {
        this.isUiVisible = isVisible
        recyclerView?.let { rv ->
            for (i in 0 until itemCount) {
                (rv.findViewHolderForAdapterPosition(i) as? ViewHolder)?.let { holder ->
                    val group = holder.binding.formatToggleGroup
                    val shouldBeVisible = isVisible && !isFormatSwitcherPersistentHidden
                    if (shouldBeVisible) {
                        group.visibility = View.VISIBLE
                        group.animate().alpha(1f).setDuration(200).setListener(null).start()
                    } else {
                        group.animate().alpha(0f).setDuration(200)
                            .setListener(object : android.animation.AnimatorListenerAdapter() {
                                override fun onAnimationEnd(animation: android.animation.Animator) {
                                    if (!isUiVisible || isFormatSwitcherPersistentHidden) group.visibility = View.GONE
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
        recyclerView?.let { rv ->
            for (i in 0 until itemCount) {
                (rv.findViewHolderForAdapterPosition(i) as? ViewHolder)?.let { holder ->
                    val group = holder.binding.formatToggleGroup
                    val shouldBeVisible = isUiVisible && !isFormatSwitcherPersistentHidden
                    if (shouldBeVisible) {
                        group.visibility = View.VISIBLE
                        group.animate().alpha(1f).setDuration(200).setListener(null).start()
                    } else {
                        group.animate().alpha(0f).setDuration(200)
                            .setListener(object : android.animation.AnimatorListenerAdapter() {
                                override fun onAnimationEnd(animation: android.animation.Animator) {
                                    if (isFormatSwitcherPersistentHidden) group.visibility = View.GONE
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
}
