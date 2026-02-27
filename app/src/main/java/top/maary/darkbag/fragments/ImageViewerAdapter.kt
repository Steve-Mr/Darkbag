package top.maary.darkbag.fragments

import android.graphics.BitmapFactory
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.exifinterface.media.ExifInterface
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import kotlinx.coroutines.*
import top.maary.darkbag.R
import top.maary.darkbag.databinding.ItemImageGroupBinding
import top.maary.darkbag.models.ImageGroup

class ImageViewerAdapter(
    private val groups: List<ImageGroup>,
    private val scope: CoroutineScope
) : RecyclerView.Adapter<ImageViewerAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemImageGroupBinding) : RecyclerView.ViewHolder(binding.root) {
        var loadJob: Job? = null
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

        setupButtons(holder, group)

        // Default to JPG if available, else DNG, else TIFF
        val initialUri = group.jpgUri ?: group.dngUri ?: group.tiffUri
        initialUri?.let { loadImage(holder, it, group) }
    }

    private fun setupButtons(holder: ViewHolder, group: ImageGroup) {
        with(holder.binding) {
            btnJpg.visibility = if (group.jpgUri != null) View.VISIBLE else View.GONE
            btnTiff.visibility = if (group.tiffUri != null) View.VISIBLE else View.GONE
            btnDng.visibility = if (group.dngUri != null) View.VISIBLE else View.GONE

            btnJpg.setOnClickListener { group.jpgUri?.let { loadImage(holder, it, group) } }
            btnTiff.setOnClickListener { group.tiffUri?.let { loadImage(holder, it, group) } }
            btnDng.setOnClickListener { group.dngUri?.let { loadImage(holder, it, group) } }

            // Set initial selected button
            formatToggleGroup.clearChecked()
            when {
                group.jpgUri != null -> formatToggleGroup.check(R.id.btn_jpg)
                group.dngUri != null -> formatToggleGroup.check(R.id.btn_dng)
                group.tiffUri != null -> formatToggleGroup.check(R.id.btn_tiff)
            }
        }
    }

    private fun loadImage(holder: ViewHolder, uri: Uri, group: ImageGroup) {
        holder.loadJob?.cancel()
        holder.binding.loadingIndicator.visibility = View.VISIBLE

        val isDng = uri.toString().endsWith(".dng", ignoreCase = true)

        if (isDng) {
            holder.loadJob = scope.launch {
                val bitmap = withContext(Dispatchers.IO) {
                    try {
                        holder.binding.root.context.contentResolver.openInputStream(uri)?.use { input ->
                            val exif = ExifInterface(input)
                            if (exif.hasThumbnail()) {
                                val thumb = exif.thumbnailBytes
                                if (thumb != null) {
                                    return@withContext BitmapFactory.decodeByteArray(thumb, 0, thumb.size)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("ImageViewerAdapter", "Failed to decode DNG thumbnail: $uri", e)
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
}
