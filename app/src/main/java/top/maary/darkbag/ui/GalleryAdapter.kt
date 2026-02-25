package top.maary.darkbag.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import top.maary.darkbag.databinding.ItemGalleryCarouselBinding
import top.maary.darkbag.databinding.ItemGalleryImportBinding
import top.maary.darkbag.persistence.ImageEntity

class GalleryAdapter(
    private val isImportSection: Boolean,
    private val onImageClick: (ImageEntity) -> Unit,
    private val onImportClick: () -> Unit = {}
) : ListAdapter<ImageEntity, RecyclerView.ViewHolder>(DiffCallback()) {

    companion object {
        private const val TYPE_IMPORT = 0
        private const val TYPE_IMAGE = 1
    }

    override fun getItemViewType(position: Int): Int {
        return if (isImportSection && position == 0) TYPE_IMPORT else TYPE_IMAGE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_IMPORT) {
            ImportViewHolder(ItemGalleryImportBinding.inflate(inflater, parent, false))
        } else {
            ImageViewHolder(ItemGalleryCarouselBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is ImageViewHolder) {
            val item = getItem(if (isImportSection) position - 1 else position)
            holder.bind(item, onImageClick)
        } else if (holder is ImportViewHolder) {
            holder.bind(onImportClick)
        }
    }

    override fun getItemCount(): Int {
        val baseCount = currentList.size
        return if (isImportSection) baseCount + 1 else baseCount
    }

    class ImageViewHolder(private val binding: ItemGalleryCarouselBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ImageEntity, onClick: (ImageEntity) -> Unit) {
            Glide.with(binding.imageView).load(item.path).into(binding.imageView)
            binding.root.setOnClickListener { onClick(item) }
        }
    }

    class ImportViewHolder(private val binding: ItemGalleryImportBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(onClick: () -> Unit) {
            binding.root.setOnClickListener { onClick() }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<ImageEntity>() {
        override fun areItemsTheSame(oldItem: ImageEntity, newItem: ImageEntity) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: ImageEntity, newItem: ImageEntity) = oldItem == newItem
    }
}
