package top.maary.darkbag.fragments

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.RequestOptions
import top.maary.darkbag.R
import top.maary.darkbag.databinding.ItemDarkbagGalleryGridBinding
import top.maary.darkbag.models.ImageGroup

class DarkbagGalleryGridAdapter(
    private val context: Context
) : RecyclerView.Adapter<DarkbagGalleryGridAdapter.GridViewHolder>() {

    var onItemClick: ((ImageGroup, Int) -> Unit)? = null
    var onItemLongClick: ((ImageGroup, Int) -> Unit)? = null
    var onSelectionChanged: ((Int) -> Unit)? = null

    private val selectedBaseNames = mutableSetOf<String>()
    var isSelectionMode: Boolean = false
        private set

    private val diffCallback = object : DiffUtil.ItemCallback<ImageGroup>() {
        override fun areItemsTheSame(oldItem: ImageGroup, newItem: ImageGroup): Boolean {
            return oldItem.baseName == newItem.baseName
        }

        override fun areContentsTheSame(oldItem: ImageGroup, newItem: ImageGroup): Boolean {
            return oldItem == newItem &&
                    oldItem.metadataLoaded == newItem.metadataLoaded &&
                    oldItem.isMotionPhoto == newItem.isMotionPhoto &&
                    oldItem.lastModified == newItem.lastModified
        }
    }

    private val differ = AsyncListDiffer(this, diffCallback)

    val currentList: List<ImageGroup>
        get() = differ.currentList

    fun submitList(list: List<ImageGroup>, commitCallback: (() -> Unit)? = null) {
        differ.submitList(list, commitCallback)
    }

    fun getItem(position: Int): ImageGroup = differ.currentList[position]

    fun setSelectionMode(enabled: Boolean) {
        if (isSelectionMode == enabled) return
        isSelectionMode = enabled
        if (!enabled) {
            selectedBaseNames.clear()
            onSelectionChanged?.invoke(0)
        }
        notifyDataSetChanged()
    }

    fun toggleSelection(group: ImageGroup) {
        if (selectedBaseNames.contains(group.baseName)) {
            selectedBaseNames.remove(group.baseName)
        } else {
            selectedBaseNames.add(group.baseName)
        }
        val pos = differ.currentList.indexOfFirst { it.baseName == group.baseName }
        if (pos != -1) {
            notifyItemChanged(pos, "SELECTION_CHANGED")
        }
        onSelectionChanged?.invoke(selectedBaseNames.size)
    }

    fun selectAll() {
        selectedBaseNames.clear()
        differ.currentList.forEach { selectedBaseNames.add(it.baseName) }
        notifyDataSetChanged()
        onSelectionChanged?.invoke(selectedBaseNames.size)
    }

    fun deselectAll() {
        selectedBaseNames.clear()
        notifyDataSetChanged()
        onSelectionChanged?.invoke(0)
    }

    fun getSelectedGroups(): List<ImageGroup> {
        return differ.currentList.filter { selectedBaseNames.contains(it.baseName) }
    }

    fun getSelectedCount(): Int = selectedBaseNames.size

    fun isSelected(group: ImageGroup): Boolean = selectedBaseNames.contains(group.baseName)

    override fun getItemCount(): Int = differ.currentList.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GridViewHolder {
        val binding = ItemDarkbagGalleryGridBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return GridViewHolder(binding)
    }

    override fun onBindViewHolder(holder: GridViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains("SELECTION_CHANGED")) {
            val group = getItem(position)
            bindSelectionState(holder, group)
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun onBindViewHolder(holder: GridViewHolder, position: Int) {
        val group = getItem(position)
        val targetUri = group.jpgUri ?: group.dngUri ?: group.dngUri1 ?: group.dngUri2

        // Load thumbnail with Glide
        Glide.with(holder.itemView.context)
            .load(targetUri)
            .apply(RequestOptions().centerCrop().diskCacheStrategy(DiskCacheStrategy.RESOURCE))
            .transition(DrawableTransitionOptions.withCrossFade())
            .into(holder.binding.thumbnailView)

        // Setup format badges
        setupBadges(holder, group)

        // Setup selection state
        bindSelectionState(holder, group)

        // Click listeners
        holder.binding.cardRoot.setOnClickListener {
            val adapterPos = holder.bindingAdapterPosition
            if (adapterPos != RecyclerView.NO_POSITION) {
                val item = getItem(adapterPos)
                if (isSelectionMode) {
                    toggleSelection(item)
                } else {
                    onItemClick?.invoke(item, adapterPos)
                }
            }
        }

        holder.binding.cardRoot.setOnLongClickListener {
            val adapterPos = holder.bindingAdapterPosition
            if (adapterPos != RecyclerView.NO_POSITION) {
                val item = getItem(adapterPos)
                if (!isSelectionMode) {
                    setSelectionMode(true)
                    toggleSelection(item)
                } else {
                    toggleSelection(item)
                }
                onItemLongClick?.invoke(item, adapterPos)
            }
            true
        }
    }

    private fun bindSelectionState(holder: GridViewHolder, group: ImageGroup) {
        val selected = selectedBaseNames.contains(group.baseName)
        if (isSelectionMode) {
            holder.binding.selectionOverlay.visibility = if (selected) View.VISIBLE else View.GONE
            holder.binding.iconSelected.visibility = if (selected) View.VISIBLE else View.GONE
            holder.binding.iconUnselected.visibility = if (selected) View.GONE else View.VISIBLE
            holder.binding.cardRoot.strokeColor = if (selected) {
                com.google.android.material.color.MaterialColors.getColor(holder.itemView, android.R.attr.colorPrimary)
            } else {
                Color.TRANSPARENT
            }
        } else {
            holder.binding.selectionOverlay.visibility = View.GONE
            holder.binding.iconSelected.visibility = View.GONE
            holder.binding.iconUnselected.visibility = View.GONE
            holder.binding.cardRoot.strokeColor = Color.TRANSPARENT
        }
    }

    private fun setupBadges(holder: GridViewHolder, group: ImageGroup) {
        val hasJpg = group.jpgUri != null
        val hasDng = group.dngUri != null || group.dngUri1 != null || group.dngUri2 != null

        if (group.isHalfFrame()) {
            holder.binding.tvFormatBadge.visibility = View.VISIBLE
            holder.binding.tvFormatBadge.text = context.getString(R.string.format_half_frame)
        } else if (hasJpg && hasDng) {
            holder.binding.tvFormatBadge.visibility = View.VISIBLE
            holder.binding.tvFormatBadge.text = context.getString(R.string.format_raw_jpg)
        } else if (hasDng) {
            holder.binding.tvFormatBadge.visibility = View.VISIBLE
            holder.binding.tvFormatBadge.text = context.getString(R.string.format_raw)
        } else if (hasJpg) {
            holder.binding.tvFormatBadge.visibility = View.VISIBLE
            holder.binding.tvFormatBadge.text = context.getString(R.string.format_jpg)
        } else {
            holder.binding.tvFormatBadge.visibility = View.GONE
        }

        if (group.isMotionPhoto) {
            holder.binding.iconMotionBadge.visibility = View.VISIBLE
        } else {
            holder.binding.iconMotionBadge.visibility = View.GONE
        }
    }

    class GridViewHolder(val binding: ItemDarkbagGalleryGridBinding) : RecyclerView.ViewHolder(binding.root)
}
