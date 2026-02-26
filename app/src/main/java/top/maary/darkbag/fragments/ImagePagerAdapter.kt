package top.maary.darkbag.fragments

import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import top.maary.darkbag.databinding.ItemImagePagerBinding
import top.maary.darkbag.utils.DarkbagImage

class ImagePagerAdapter(private var images: List<DarkbagImage>) : RecyclerView.Adapter<ImagePagerAdapter.ViewHolder>() {

    private val processedBitmaps = mutableMapOf<String, Bitmap>()
    private val rawBitmaps = mutableMapOf<String, Bitmap>()
    private var activeFormats = mutableMapOf<String, String>()

    class ViewHolder(val binding: ItemImagePagerBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemImagePagerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val image = images[position]
        val format = activeFormats[image.baseName] ?: image.type
        val processed = processedBitmaps[image.baseName]
        val raw = rawBitmaps[image.baseName]

        when {
            format == "JPG" && processed != null -> {
                holder.binding.ivDisplay.setImageBitmap(processed)
            }
            format == "DNG" && raw != null -> {
                holder.binding.ivDisplay.setImageBitmap(raw)
            }
            else -> {
                val uri = image.allUris[format] ?: image.allUris["JPG"] ?: image.primaryUri

                Glide.with(holder.itemView)
                    .load(uri)
                    .placeholder(holder.binding.ivDisplay.drawable)
                    .into(holder.binding.ivDisplay)
            }
        }
    }

    override fun getItemCount(): Int = images.size

    fun updateImages(newImages: List<DarkbagImage>) {
        images = newImages
        // We don't clear previewBitmaps here to keep previews during list updates
        notifyDataSetChanged()
    }

    fun setPreviewBitmaps(baseName: String, processed: Bitmap?, raw: Bitmap?) {
        if (processed == null) processedBitmaps.remove(baseName) else processedBitmaps[baseName] = processed
        if (raw == null) rawBitmaps.remove(baseName) else rawBitmaps[baseName] = raw

        val index = images.indexOfFirst { it.baseName == baseName }
        if (index != -1) {
            // Post to avoid "Cannot call this method in a scroll callback"
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                notifyItemChanged(index)
            }
        }
    }

    fun setFormat(baseName: String, format: String) {
        activeFormats[baseName] = format
        val index = images.indexOfFirst { it.baseName == baseName }
        if (index != -1) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                notifyItemChanged(index)
            }
        }
    }

    fun getImage(position: Int): DarkbagImage? {
        if (position in images.indices) return images[position]
        return null
    }
}
