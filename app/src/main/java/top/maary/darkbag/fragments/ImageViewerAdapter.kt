package top.maary.darkbag.fragments

import android.graphics.BitmapFactory
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import kotlinx.coroutines.*
import top.maary.darkbag.R
import top.maary.darkbag.databinding.ItemImageGroupBinding
import top.maary.darkbag.models.ImageGroup
import top.maary.darkbag.utils.ImageUtils

class ImageViewerAdapter(
    private val groups: List<ImageGroup>,
    private val scope: CoroutineScope
) : RecyclerView.Adapter<ImageViewerAdapter.ViewHolder>() {

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
        holder.binding.imageView.resetZoom()
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
            group.tiffUri != null -> "TIFF"
            else -> "JPG"
        }
        selectedFormats[position] = format

        val targetId = when (format) {
            "JPG" -> R.id.btnJpg
            "TIFF" -> R.id.btnTiff
            "DNG" -> R.id.btnDng
            else -> R.id.btnJpg
        }
        selectButton(holder, targetId)

        when (format) {
            "JPG" -> group.jpgUri?.let { loadImage(holder, it) }
            "TIFF" -> group.tiffUri?.let { loadImage(holder, it) }
            "DNG" -> if (group.isHalfFrame()) loadHalfFrameDngs(holder, group) else group.dngUri?.let { loadImage(holder, it) }
        }
    }

    private fun setupButtons(holder: ViewHolder, group: ImageGroup, position: Int) {
        with(holder.binding) {
            btnJpg.visibility = if (group.jpgUri != null) View.VISIBLE else View.GONE
            btnTiff.visibility = if (group.tiffUri != null) View.VISIBLE else View.GONE
            btnDng.visibility = if (group.dngUri != null || group.dngUri1 != null || group.dngUri2 != null) View.VISIBLE else View.GONE

            btnJpg.setOnClickListener {
                if (selectedFormats[position] == "JPG") return@setOnClickListener
                selectedFormats[position] = "JPG"
                selectButton(holder, R.id.btnJpg)
                group.jpgUri?.let { loadImage(holder, it) }
            }
            btnTiff.setOnClickListener {
                if (selectedFormats[position] == "TIFF") return@setOnClickListener
                selectedFormats[position] = "TIFF"
                selectButton(holder, R.id.btnTiff)
                group.tiffUri?.let { loadImage(holder, it) }
            }
            btnDng.setOnClickListener {
                if (selectedFormats[position] == "DNG") return@setOnClickListener
                selectedFormats[position] = "DNG"
                selectButton(holder, R.id.btnDng)

                // Optimization: If a JPG exists, use it as a placeholder for the DNG tab to avoid immediate heavy RAW decoding
                if (group.jpgUri != null && !group.isHalfFrame()) {
                    loadWithGlide(holder, group.jpgUri, skipCache = false)
                } else {
                    if (group.isHalfFrame()) loadHalfFrameDngs(holder, group) else group.dngUri?.let { loadImage(holder, it) }
                }
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

    private fun loadImage(holder: ViewHolder, uri: Uri) {
        holder.loadJob?.cancel()
        holder.binding.imageView.visibility = View.VISIBLE
        holder.binding.loadingIndicator.visibility = View.VISIBLE

        val isDng = uri.toString().endsWith(".dng", ignoreCase = true)
        val isTiff = uri.toString().endsWith(".tiff", ignoreCase = true) || uri.toString().endsWith(".tif", ignoreCase = true)

        if (isDng || isTiff) {
            holder.loadJob = scope.launch {
                val bitmap = withContext(Dispatchers.IO) {
                    try {
                        val contentResolver = holder.binding.root.context.contentResolver
                        if (isDng) {
                            val thumb = ImageUtils.decodeDngThumbnail(holder.binding.root.context, uri)
                            if (thumb != null) return@withContext thumb
                        }

                        contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeFileDescriptor(pfd.fileDescriptor, null, options)
                            options.inSampleSize = ImageUtils.calculateInSampleSize(options, 2048, 2048)
                            options.inJustDecodeBounds = false
                            val bitmap = BitmapFactory.decodeFileDescriptor(pfd.fileDescriptor, null, options)
                            val orientation = try {
                                holder.binding.root.context.contentResolver.openInputStream(uri)?.use { input ->
                                    androidx.exifinterface.media.ExifInterface(input).getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL)
                                } ?: androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
                            } catch (e: Exception) { androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL }
                            return@withContext bitmap?.let { ImageUtils.rotateBitmap(it, orientation) }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("ImageViewerAdapter", "Failed to decode preview: $uri", e)
                        null
                    }
                    null
                }

                if (bitmap != null) {
                    holder.binding.imageView.setImageBitmap(bitmap)
                    holder.binding.loadingIndicator.visibility = View.GONE
                } else {
                    loadWithGlide(holder, uri)
                }
            }
        } else {
            loadWithGlide(holder, uri, skipCache = true)
        }
    }

    private fun loadHalfFrameDngs(holder: ViewHolder, group: ImageGroup) {
        holder.loadJob?.cancel()
        holder.binding.imageView.visibility = View.VISIBLE
        holder.binding.loadingIndicator.visibility = View.VISIBLE
        holder.loadJob = scope.launch {
            val composite = ImageUtils.generateHalfFrameComposite(
                holder.binding.root.context,
                group.dngUri1,
                group.dngUri2,
                group.hfLayout
            )
            if (composite != null) {
                holder.binding.imageView.setImageBitmap(composite)
            } else {
                holder.binding.imageView.setImageResource(android.R.drawable.ic_menu_gallery)
            }
            holder.binding.loadingIndicator.visibility = View.GONE
        }
    }

    private fun loadWithGlide(holder: ViewHolder, uri: Uri, skipCache: Boolean = false) {
        Glide.with(holder.binding.imageView)
            .asDrawable()
            .load(uri)
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

    fun getGroup(position: Int): ImageGroup = groups[position]

    fun setFormat(position: Int, format: String) {
        val group = groups[position]
        if (selectedFormats[position] == format) return
        selectedFormats[position] = format

        val holder = recyclerView?.findViewHolderForAdapterPosition(position) as? ViewHolder
        if (holder != null) {
            val targetId = when (format) {
                "JPG" -> R.id.btnJpg
                "TIFF" -> R.id.btnTiff
                "DNG" -> R.id.btnDng
                else -> R.id.btnJpg
            }
            selectButton(holder, targetId)
            when (format) {
                "JPG" -> group.jpgUri?.let { loadImage(holder, it) }
                "TIFF" -> group.tiffUri?.let { loadImage(holder, it) }
                "DNG" -> if (group.isHalfFrame()) loadHalfFrameDngs(holder, group) else group.dngUri?.let { loadImage(holder, it) }
            }
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
                Glide.with(holder.binding.imageView).clear(holder.binding.imageView)
            }
            holder.binding.loadingIndicator.visibility = View.GONE
        }
    }
}
