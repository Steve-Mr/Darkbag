package top.maary.darkbag.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import top.maary.darkbag.databinding.ItemGalleryCarouselBinding
import top.maary.darkbag.persistence.ImageEntity

class StaggeredGalleryAdapter(
    private val onImageClick: (ImageEntity) -> Unit,
    private val onLongClick: (ImageEntity) -> Unit,
    private val onSelectionChange: (Set<String>) -> Unit
) : ListAdapter<ImageEntity, StaggeredGalleryAdapter.ViewHolder>(DiffCallback()) {

    private val selectedIds = mutableSetOf<String>()
    var isSelectionMode = false
        set(value) {
            field = value
            if (!value) selectedIds.clear()
            notifyDataSetChanged()
        }

    fun getSelectedImages() = currentList.filter { it.id in selectedIds }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemGalleryCarouselBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        // Adjust width for staggered grid (match parent width)
        binding.root.layoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemGalleryCarouselBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ImageEntity) {
            Glide.with(binding.imageView).load(item.path).into(binding.imageView)

            val isSelected = selectedIds.contains(item.id)
            binding.root.strokeWidth = if (isSelected) 4 else 0
            binding.root.strokeColor = binding.root.context.getColor(android.R.color.holo_blue_light)

            binding.root.setOnClickListener {
                if (isSelectionMode) {
                    toggleSelection(item.id)
                } else {
                    onImageClick(item)
                }
            }

            binding.root.setOnLongClickListener {
                if (!isSelectionMode) {
                    isSelectionMode = true
                    toggleSelection(item.id)
                    onLongClick(item)
                    true
                } else false
            }
        }

        private fun toggleSelection(id: String) {
            if (selectedIds.contains(id)) {
                selectedIds.remove(id)
            } else {
                selectedIds.add(id)
            }
            notifyItemChanged(adapterPosition)
            onSelectionChange(selectedIds)
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<ImageEntity>() {
        override fun areItemsTheSame(oldItem: ImageEntity, newItem: ImageEntity) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: ImageEntity, newItem: ImageEntity) = oldItem == newItem
    }
}
