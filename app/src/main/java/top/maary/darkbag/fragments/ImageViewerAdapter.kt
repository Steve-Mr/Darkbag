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
        when {
            group.jpgUri != null -> loadImage(holder, group.jpgUri, group)
            group.isHalfFrame() -> loadHalfFrameDngs(holder, group)
            group.dngUri != null -> loadImage(holder, group.dngUri, group)
            group.tiffUri != null -> loadImage(holder, group.tiffUri, group)
        }
    }

    private fun setupButtons(holder: ViewHolder, group: ImageGroup) {
        with(holder.binding) {
            btnJpg.visibility = if (group.jpgUri != null) View.VISIBLE else View.GONE
            btnTiff.visibility = if (group.tiffUri != null) View.VISIBLE else View.GONE
            btnDng.visibility = if (group.dngUri != null || group.dngUri1 != null || group.dngUri2 != null) View.VISIBLE else View.GONE

            btnJpg.setOnClickListener { group.jpgUri?.let { loadImage(holder, it, group) } }
            btnTiff.setOnClickListener { group.tiffUri?.let { loadImage(holder, it, group) } }
            btnDng.setOnClickListener {
                if (group.isHalfFrame()) {
                    loadHalfFrameDngs(holder, group)
                } else {
                    group.dngUri?.let { loadImage(holder, it, group) }
                }
            }

            // Set initial selected button
            formatToggleGroup.clearChecked()
            when {
                group.jpgUri != null -> formatToggleGroup.check(R.id.btn_jpg)
                group.dngUri != null || group.dngUri1 != null || group.dngUri2 != null -> formatToggleGroup.check(R.id.btn_dng)
                group.tiffUri != null -> formatToggleGroup.check(R.id.btn_tiff)
            }
        }
    }

    private fun loadImage(holder: ViewHolder, uri: Uri, group: ImageGroup) {
        holder.loadJob?.cancel()
        holder.binding.imageView.visibility = View.VISIBLE
        holder.binding.imageViewHf1.visibility = View.GONE
        holder.binding.imageViewHf2.visibility = View.GONE
        holder.binding.loadingIndicator.visibility = View.VISIBLE

        val isDng = uri.toString().endsWith(".dng", ignoreCase = true)
        val isTiff = uri.toString().endsWith(".tiff", ignoreCase = true) || uri.toString().endsWith(".tif", ignoreCase = true)

        if (isDng || isTiff) {
            holder.loadJob = scope.launch {
                val bitmap = withContext(Dispatchers.IO) {
                    try {
                        val contentResolver = holder.binding.root.context.contentResolver

                        // Try DNG thumbnail first if it's DNG
                        if (isDng) {
                            contentResolver.openInputStream(uri)?.use { input ->
                                val exif = ExifInterface(input)
                                if (exif.hasThumbnail()) {
                                    val thumb = exif.thumbnailBytes
                                    if (thumb != null) {
                                        return@withContext BitmapFactory.decodeByteArray(thumb, 0, thumb.size)
                                    }
                                }
                            }
                        }

                        // Fallback to BitmapFactory with sampling for TIFF or DNG without thumbnail
                        contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                            val options = BitmapFactory.Options().apply {
                                inJustDecodeBounds = true
                            }
                            BitmapFactory.decodeFileDescriptor(pfd.fileDescriptor, null, options)

                            options.inSampleSize = calculateInSampleSize(options, 2048, 2048)
                            options.inJustDecodeBounds = false

                            return@withContext BitmapFactory.decodeFileDescriptor(pfd.fileDescriptor, null, options)
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

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.run { outHeight to outWidth }
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2

            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    private fun loadHalfFrameDngs(holder: ViewHolder, group: ImageGroup) {
        holder.loadJob?.cancel()
        holder.binding.imageView.visibility = View.GONE
        holder.binding.imageViewHf1.visibility = View.VISIBLE
        holder.binding.imageViewHf2.visibility = View.VISIBLE
        holder.binding.loadingIndicator.visibility = View.VISIBLE

        val constraintSet = androidx.constraintlayout.widget.ConstraintSet()
        constraintSet.clone(holder.binding.root)

        if (group.hfLayout == "TB") {
            // Top-bottom
            constraintSet.connect(R.id.image_view_hf1, androidx.constraintlayout.widget.ConstraintSet.BOTTOM, R.id.image_view_hf2, androidx.constraintlayout.widget.ConstraintSet.TOP)
            constraintSet.connect(R.id.image_view_hf2, androidx.constraintlayout.widget.ConstraintSet.TOP, R.id.image_view_hf1, androidx.constraintlayout.widget.ConstraintSet.BOTTOM)

            constraintSet.connect(R.id.image_view_hf1, androidx.constraintlayout.widget.ConstraintSet.START, androidx.constraintlayout.widget.ConstraintSet.PARENT_ID, androidx.constraintlayout.widget.ConstraintSet.START)
            constraintSet.connect(R.id.image_view_hf1, androidx.constraintlayout.widget.ConstraintSet.END, androidx.constraintlayout.widget.ConstraintSet.PARENT_ID, androidx.constraintlayout.widget.ConstraintSet.END)
            constraintSet.connect(R.id.image_view_hf1, androidx.constraintlayout.widget.ConstraintSet.TOP, androidx.constraintlayout.widget.ConstraintSet.PARENT_ID, androidx.constraintlayout.widget.ConstraintSet.TOP)

            constraintSet.connect(R.id.image_view_hf2, androidx.constraintlayout.widget.ConstraintSet.START, androidx.constraintlayout.widget.ConstraintSet.PARENT_ID, androidx.constraintlayout.widget.ConstraintSet.START)
            constraintSet.connect(R.id.image_view_hf2, androidx.constraintlayout.widget.ConstraintSet.END, androidx.constraintlayout.widget.ConstraintSet.PARENT_ID, androidx.constraintlayout.widget.ConstraintSet.END)
            constraintSet.connect(R.id.image_view_hf2, androidx.constraintlayout.widget.ConstraintSet.BOTTOM, androidx.constraintlayout.widget.ConstraintSet.PARENT_ID, androidx.constraintlayout.widget.ConstraintSet.BOTTOM)

            constraintSet.constrainHeight(R.id.image_view_hf1, 0)
            constraintSet.constrainHeight(R.id.image_view_hf2, 0)
            constraintSet.constrainWidth(R.id.image_view_hf1, androidx.constraintlayout.widget.ConstraintSet.MATCH_CONSTRAINT)
            constraintSet.constrainWidth(R.id.image_view_hf2, androidx.constraintlayout.widget.ConstraintSet.MATCH_CONSTRAINT)
        } else {
            // Side-by-side (default)
            constraintSet.connect(R.id.image_view_hf1, androidx.constraintlayout.widget.ConstraintSet.END, R.id.image_view_hf2, androidx.constraintlayout.widget.ConstraintSet.START)
            constraintSet.connect(R.id.image_view_hf2, androidx.constraintlayout.widget.ConstraintSet.START, R.id.image_view_hf1, androidx.constraintlayout.widget.ConstraintSet.END)

            constraintSet.connect(R.id.image_view_hf1, androidx.constraintlayout.widget.ConstraintSet.START, androidx.constraintlayout.widget.ConstraintSet.PARENT_ID, androidx.constraintlayout.widget.ConstraintSet.START)
            constraintSet.connect(R.id.image_view_hf1, androidx.constraintlayout.widget.ConstraintSet.TOP, androidx.constraintlayout.widget.ConstraintSet.PARENT_ID, androidx.constraintlayout.widget.ConstraintSet.TOP)
            constraintSet.connect(R.id.image_view_hf1, androidx.constraintlayout.widget.ConstraintSet.BOTTOM, androidx.constraintlayout.widget.ConstraintSet.PARENT_ID, androidx.constraintlayout.widget.ConstraintSet.BOTTOM)

            constraintSet.connect(R.id.image_view_hf2, androidx.constraintlayout.widget.ConstraintSet.END, androidx.constraintlayout.widget.ConstraintSet.PARENT_ID, androidx.constraintlayout.widget.ConstraintSet.END)
            constraintSet.connect(R.id.image_view_hf2, androidx.constraintlayout.widget.ConstraintSet.TOP, androidx.constraintlayout.widget.ConstraintSet.PARENT_ID, androidx.constraintlayout.widget.ConstraintSet.TOP)
            constraintSet.connect(R.id.image_view_hf2, androidx.constraintlayout.widget.ConstraintSet.BOTTOM, androidx.constraintlayout.widget.ConstraintSet.PARENT_ID, androidx.constraintlayout.widget.ConstraintSet.BOTTOM)

            constraintSet.constrainWidth(R.id.image_view_hf1, 0)
            constraintSet.constrainWidth(R.id.image_view_hf2, 0)
            constraintSet.constrainHeight(R.id.image_view_hf1, androidx.constraintlayout.widget.ConstraintSet.MATCH_CONSTRAINT)
            constraintSet.constrainHeight(R.id.image_view_hf2, androidx.constraintlayout.widget.ConstraintSet.MATCH_CONSTRAINT)
        }
        constraintSet.applyTo(holder.binding.root)

        holder.loadJob = scope.launch {
            val bit1 = group.dngUri1?.let { decodeDngThumbnail(holder.binding.root.context, it) }
            val bit2 = group.dngUri2?.let { decodeDngThumbnail(holder.binding.root.context, it) }

            if (bit1 != null) holder.binding.imageViewHf1.setImageBitmap(bit1) else holder.binding.imageViewHf1.setImageResource(android.R.drawable.ic_menu_gallery)
            if (bit2 != null) holder.binding.imageViewHf2.setImageBitmap(bit2) else holder.binding.imageViewHf2.setImageResource(android.R.drawable.ic_menu_gallery)

            holder.binding.loadingIndicator.visibility = View.GONE
        }
    }

    private suspend fun decodeDngThumbnail(context: android.content.Context, uri: Uri): android.graphics.Bitmap? = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val exif = androidx.exifinterface.media.ExifInterface(input)
                if (exif.hasThumbnail()) {
                    val thumb = exif.thumbnailBytes
                    if (thumb != null) {
                        return@withContext android.graphics.BitmapFactory.decodeByteArray(thumb, 0, thumb.size)
                    }
                }
            }
            // Fallback for DNGs without thumbnails
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                val options = android.graphics.BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                android.graphics.BitmapFactory.decodeFileDescriptor(pfd.fileDescriptor, null, options)
                options.inSampleSize = calculateInSampleSize(options, 1024, 1024)
                options.inJustDecodeBounds = false
                return@withContext android.graphics.BitmapFactory.decodeFileDescriptor(pfd.fileDescriptor, null, options)
            }
        } catch (e: Exception) {
            android.util.Log.e("ImageViewerAdapter", "Failed to decode DNG: $uri", e)
        }
        null
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
