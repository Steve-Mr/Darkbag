package top.maary.darkbag.fragments

import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import top.maary.darkbag.databinding.ItemImagePagerBinding
import top.maary.darkbag.utils.DarkbagImage

class ImagePagerAdapter(private var images: List<DarkbagImage>) : RecyclerView.Adapter<ImagePagerAdapter.ViewHolder>() {

    private val previewBitmaps = mutableMapOf<Int, Bitmap>()

    class ViewHolder(val binding: ItemImagePagerBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemImagePagerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val image = images[position]
        val preview = previewBitmaps[position]

        if (preview != null) {
            holder.binding.ivDisplay.setImageBitmap(preview)
        } else {
            Glide.with(holder.itemView)
                .load(image.primaryUri)
                .placeholder(holder.binding.ivDisplay.drawable)
                .into(holder.binding.ivDisplay)
        }
    }

    override fun getItemCount(): Int = images.size

    fun updateImages(newImages: List<DarkbagImage>) {
        images = newImages
        previewBitmaps.clear()
        notifyDataSetChanged()
    }

    fun setPreviewBitmap(position: Int, bitmap: Bitmap?) {
        if (bitmap == null) {
            previewBitmaps.remove(position)
        } else {
            previewBitmaps[position] = bitmap
        }
        notifyItemChanged(position)
    }

    fun getImage(position: Int): DarkbagImage? {
        if (position in images.indices) return images[position]
        return null
    }
}
