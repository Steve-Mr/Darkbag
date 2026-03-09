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
    private var recyclerView: RecyclerView? = null

    class ViewHolder(val binding: ItemImageGroupBinding) : RecyclerView.ViewHolder(binding.root) {
        var loadJob: Job? = null
        var currentFormat: String = "JPG"
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
        holder.binding.imageView.onZoomChanged = { isZoomed -> onZoomChanged?.invoke(isZoomed) }

        // Default to JPG if available, else DNG, else TIFF
        when {
            group.jpgUri != null -> {
                holder.currentFormat = "JPG"
                loadImage(holder, group.jpgUri)
            }
            group.isHalfFrame() -> {
                holder.currentFormat = "DNG"
                loadHalfFrameDngs(holder, group)
            }
            group.dngUri != null -> {
                holder.currentFormat = "DNG"
                loadImage(holder, group.dngUri)
            }
            group.tiffUri != null -> {
                holder.currentFormat = "TIFF"
                loadImage(holder, group.tiffUri)
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
        val holder = recyclerView?.findViewHolderForAdapterPosition(position) as? ViewHolder ?: return
        if (holder.currentFormat == format) return
        holder.currentFormat = format
        when (format) {
            "JPG" -> group.jpgUri?.let { loadImage(holder, it) }
            "TIFF" -> group.tiffUri?.let { loadImage(holder, it) }
            "DNG" -> if (group.isHalfFrame()) loadHalfFrameDngs(holder, group) else group.dngUri?.let { loadImage(holder, it) }
        }
    }

    fun getSelectedFormat(position: Int): String {
        val holder = recyclerView?.findViewHolderForAdapterPosition(position) as? ViewHolder
        return holder?.currentFormat ?: "JPG"
    }
}
