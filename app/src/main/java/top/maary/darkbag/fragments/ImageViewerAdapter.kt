package top.maary.darkbag.fragments

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import kotlinx.coroutines.*
import top.maary.darkbag.R
import top.maary.darkbag.databinding.ItemImageGroupBinding
import top.maary.darkbag.models.ImageGroup
import top.maary.darkbag.utils.ImageUtils

class ImageViewerAdapter(
    private var groups: List<ImageGroup>,
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

    class ViewHolder(val binding: ItemImageGroupBinding) : RecyclerView.ViewHolder(binding.root) {
        var loadJob: Job? = null
        var manualBitmap: android.graphics.Bitmap? = null
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

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val group = groups[position]
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
        holder.loadJob?.cancel()
        holder.binding.imageView.visibility = View.VISIBLE
        holder.binding.loadingIndicator.visibility = View.VISIBLE

        holder.loadJob = scope.launch {
            var thumbnailBitmap: android.graphics.Bitmap? = null
            try {
                thumbnailBitmap = ImageUtils.decodeDngThumbnail(holder.binding.root.context, uri, zoomFactor)
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
        holder.loadJob?.cancel()
        holder.binding.imageView.visibility = View.VISIBLE
        holder.binding.loadingIndicator.visibility = View.VISIBLE

        if (uri.toString().endsWith(".dng", ignoreCase = true)) {
            loadDngThumbnailOnly(holder, uri, zoomFactor)
        } else {
            loadWithGlide(holder, uri, skipCache = true, version = version)
        }
    }

    private fun loadHalfFrameDngs(holder: ViewHolder, group: ImageGroup, zoomFactor: Float = 1.0f) {
        holder.loadJob?.cancel()
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

    private fun loadWithGlide(holder: ViewHolder, uri: Uri, skipCache: Boolean = false, version: Long = 0L) {
        clearCurrentBitmap(holder)

        val model = if (version > 0) {
            com.bumptech.glide.signature.ObjectKey(version)
        } else {
            null
        }

        Glide.with(holder.binding.imageView)
            .asDrawable()
            .load(uri)
            .let { if (model != null) it.signature(model) else it }
            .apply {
                if (skipCache) {
                    diskCacheStrategy(DiskCacheStrategy.NONE)
                    skipMemoryCache(true)
                }
            }
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

    override fun getItemCount(): Int = groups.size

    override fun onViewRecycled(holder: ViewHolder) {
        holder.loadJob?.cancel()
        clearCurrentBitmap(holder)
        holder.binding.loadingIndicator.visibility = View.GONE
        super.onViewRecycled(holder)
    }

    fun getGroup(position: Int): ImageGroup = groups[position]

    fun getGroups(): List<ImageGroup> = groups

    fun setFormat(position: Int, format: String) {
        val group = groups[position]
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

    fun updateGroups(newGroups: List<ImageGroup>) {
        val diffResult = androidx.recyclerview.widget.DiffUtil.calculateDiff(object : androidx.recyclerview.widget.DiffUtil.Callback() {
            override fun getOldListSize(): Int = groups.size
            override fun getNewListSize(): Int = newGroups.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return groups[oldItemPosition].baseName == newGroups[newItemPosition].baseName
            }
            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return groups[oldItemPosition] == newGroups[newItemPosition]
            }
        })
        groups = newGroups
        diffResult.dispatchUpdatesTo(this)
    }

    fun findGroupIndex(baseName: String): Int {
        return groups.indexOfFirst { it.baseName == baseName }
    }

    fun updateGroupAt(position: Int, newGroup: ImageGroup) {
        if (position < 0 || position >= groups.size) return
        val updatedList = groups.toMutableList()
        updatedList[position] = newGroup
        groups = updatedList
        notifyItemChanged(position)
    }
}
