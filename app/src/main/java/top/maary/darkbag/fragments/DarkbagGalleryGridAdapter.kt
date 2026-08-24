package top.maary.darkbag.fragments

import android.content.Context
import android.graphics.Color
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
import top.maary.darkbag.databinding.ItemDarkbagGalleryMultiCamGroupBinding
import top.maary.darkbag.models.ImageGroup
import top.maary.darkbag.models.MultiCameraLensItem

data class GallerySelectedItem(
    val group: ImageGroup,
    val specificLens: MultiCameraLensItem? = null
)

class DarkbagGalleryGridAdapter(
    private val context: Context
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        const val VIEW_TYPE_SINGLE = 0
        const val VIEW_TYPE_MULTI_CAMERA = 1
    }

    var onItemClick: ((ImageGroup, Int, String?) -> Unit)? = null
    var onItemLongClick: ((ImageGroup, Int, String?) -> Unit)? = null
    var onSelectionChanged: ((Int) -> Unit)? = null

    // Format for keys: "baseName" for single image; "baseName#lensTag" for multi-camera lens
    private val selectedItemKeys = mutableSetOf<String>()
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
                    oldItem.lastModified == newItem.lastModified &&
                    oldItem.multiCameraLenses == newItem.multiCameraLenses
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
            selectedItemKeys.clear()
            onSelectionChanged?.invoke(0)
        }
        notifyDataSetChanged()
    }

    fun toggleSingleSelection(group: ImageGroup) {
        val key = group.baseName
        if (selectedItemKeys.contains(key)) {
            selectedItemKeys.remove(key)
        } else {
            selectedItemKeys.add(key)
        }
        val pos = differ.currentList.indexOfFirst { it.baseName == group.baseName }
        if (pos != -1) {
            notifyItemChanged(pos, "SELECTION_CHANGED")
        }
        onSelectionChanged?.invoke(selectedItemKeys.size)
    }

    fun toggleLensSelection(group: ImageGroup, lens: MultiCameraLensItem) {
        val key = "${group.baseName}#${lens.lensTag}"
        if (selectedItemKeys.contains(key)) {
            selectedItemKeys.remove(key)
        } else {
            selectedItemKeys.add(key)
        }
        val pos = differ.currentList.indexOfFirst { it.baseName == group.baseName }
        if (pos != -1) {
            notifyItemChanged(pos, "SELECTION_CHANGED")
        }
        onSelectionChanged?.invoke(selectedItemKeys.size)
    }

    fun selectAll() {
        selectedItemKeys.clear()
        for (group in differ.currentList) {
            if (group.isMultiCamera && group.multiCameraLenses.isNotEmpty()) {
                for (lens in group.multiCameraLenses) {
                    selectedItemKeys.add("${group.baseName}#${lens.lensTag}")
                }
            } else {
                selectedItemKeys.add(group.baseName)
            }
        }
        notifyDataSetChanged()
        onSelectionChanged?.invoke(selectedItemKeys.size)
    }

    fun deselectAll() {
        selectedItemKeys.clear()
        notifyDataSetChanged()
        onSelectionChanged?.invoke(0)
    }

    fun getSelectedItems(): List<GallerySelectedItem> {
        val result = mutableListOf<GallerySelectedItem>()
        for (group in differ.currentList) {
            if (group.isMultiCamera && group.multiCameraLenses.isNotEmpty()) {
                for (lens in group.multiCameraLenses) {
                    val key = "${group.baseName}#${lens.lensTag}"
                    if (selectedItemKeys.contains(key)) {
                        result.add(GallerySelectedItem(group, lens))
                    }
                }
            } else {
                if (selectedItemKeys.contains(group.baseName)) {
                    result.add(GallerySelectedItem(group, null))
                }
            }
        }
        return result
    }

    fun getSelectedCount(): Int = selectedItemKeys.size

    override fun getItemViewType(position: Int): Int {
        val item = getItem(position)
        return if (item.isMultiCamera && item.multiCameraLenses.isNotEmpty()) {
            VIEW_TYPE_MULTI_CAMERA
        } else {
            VIEW_TYPE_SINGLE
        }
    }

    override fun getItemCount(): Int = differ.currentList.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_MULTI_CAMERA) {
            val binding = ItemDarkbagGalleryMultiCamGroupBinding.inflate(inflater, parent, false)
            MultiCamViewHolder(binding)
        } else {
            val binding = ItemDarkbagGalleryGridBinding.inflate(inflater, parent, false)
            SingleViewHolder(binding)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains("SELECTION_CHANGED")) {
            val group = getItem(position)
            if (holder is SingleViewHolder) {
                bindSingleSelectionState(holder, group)
            } else if (holder is MultiCamViewHolder) {
                bindMultiCamSelectionState(holder, group)
            }
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val group = getItem(position)
        if (holder is SingleViewHolder) {
            bindSingleViewHolder(holder, group, position)
        } else if (holder is MultiCamViewHolder) {
            bindMultiCamViewHolder(holder, group, position)
        }
    }

    private fun bindSingleViewHolder(holder: SingleViewHolder, group: ImageGroup, position: Int) {
        val targetUri = group.jpgUri ?: group.dngUri ?: group.dngUri1 ?: group.dngUri2

        // Load thumbnail with Glide
        Glide.with(holder.itemView.context)
            .load(targetUri)
            .apply(RequestOptions().centerCrop().diskCacheStrategy(DiskCacheStrategy.RESOURCE))
            .transition(DrawableTransitionOptions.withCrossFade())
            .into(holder.binding.thumbnailView)

        // Setup format badges
        setupSingleBadges(holder, group)

        // Setup selection state
        bindSingleSelectionState(holder, group)

        // Click listeners
        holder.binding.cardRoot.setOnClickListener {
            val adapterPos = holder.bindingAdapterPosition
            if (adapterPos != RecyclerView.NO_POSITION) {
                val item = getItem(adapterPos)
                if (isSelectionMode) {
                    toggleSingleSelection(item)
                } else {
                    onItemClick?.invoke(item, adapterPos, null)
                }
            }
        }

        holder.binding.cardRoot.setOnLongClickListener {
            val adapterPos = holder.bindingAdapterPosition
            if (adapterPos != RecyclerView.NO_POSITION) {
                val item = getItem(adapterPos)
                if (!isSelectionMode) {
                    setSelectionMode(true)
                    toggleSingleSelection(item)
                } else {
                    toggleSingleSelection(item)
                }
                onItemLongClick?.invoke(item, adapterPos, null)
            }
            true
        }
    }

    private fun bindSingleSelectionState(holder: SingleViewHolder, group: ImageGroup) {
        val selected = selectedItemKeys.contains(group.baseName)
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

    private fun setupSingleBadges(holder: SingleViewHolder, group: ImageGroup) {
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

    private fun bindMultiCamViewHolder(holder: MultiCamViewHolder, group: ImageGroup, position: Int) {
        val lenses = group.multiCameraLenses
        holder.binding.tvLensCountBadge.text = "${lenses.size} Lenses"

        val primaryColor = com.google.android.material.color.MaterialColors.getColor(holder.itemView, android.R.attr.colorPrimary)

        // Slot 1
        val lens1 = lenses.getOrNull(0)
        if (lens1 != null) {
            holder.binding.slotLens1.visibility = View.VISIBLE
            val uri1 = lens1.jpgUri ?: lens1.dngUri
            Glide.with(holder.itemView.context)
                .load(uri1)
                .apply(RequestOptions().centerCrop().diskCacheStrategy(DiskCacheStrategy.RESOURCE))
                .transition(DrawableTransitionOptions.withCrossFade())
                .into(holder.binding.thumbnail1)

            holder.binding.tvLensTag1.text = lens1.lensTag
            bindLensFormatBadge(holder.binding.tvFormatBadge1, lens1)
            setupLensSlotListeners(holder.binding.slotLens1, group, lens1, holder)
        } else {
            holder.binding.slotLens1.visibility = View.GONE
        }

        // Slot 2
        val lens2 = lenses.getOrNull(1)
        if (lens2 != null) {
            holder.binding.slotLens2.visibility = View.VISIBLE
            val uri2 = lens2.jpgUri ?: lens2.dngUri
            Glide.with(holder.itemView.context)
                .load(uri2)
                .apply(RequestOptions().centerCrop().diskCacheStrategy(DiskCacheStrategy.RESOURCE))
                .transition(DrawableTransitionOptions.withCrossFade())
                .into(holder.binding.thumbnail2)

            holder.binding.tvLensTag2.text = lens2.lensTag
            bindLensFormatBadge(holder.binding.tvFormatBadge2, lens2)
            setupLensSlotListeners(holder.binding.slotLens2, group, lens2, holder)
        } else {
            holder.binding.slotLens2.visibility = View.GONE
        }

        // Slot 3
        val lens3 = lenses.getOrNull(2)
        if (lens3 != null) {
            holder.binding.slotLens3.visibility = View.VISIBLE
            val uri3 = lens3.jpgUri ?: lens3.dngUri
            Glide.with(holder.itemView.context)
                .load(uri3)
                .apply(RequestOptions().centerCrop().diskCacheStrategy(DiskCacheStrategy.RESOURCE))
                .transition(DrawableTransitionOptions.withCrossFade())
                .into(holder.binding.thumbnail3)

            holder.binding.tvLensTag3.text = lens3.lensTag
            bindLensFormatBadge(holder.binding.tvFormatBadge3, lens3)
            setupLensSlotListeners(holder.binding.slotLens3, group, lens3, holder)
        } else {
            holder.binding.slotLens3.visibility = View.GONE
        }

        bindMultiCamSelectionState(holder, group)
    }

    private fun bindLensFormatBadge(badgeView: android.widget.TextView, lens: MultiCameraLensItem) {
        val hasJpg = lens.jpgUri != null
        val hasDng = lens.dngUri != null
        if (hasJpg && hasDng) {
            badgeView.visibility = View.VISIBLE
            badgeView.text = context.getString(R.string.format_raw_jpg)
        } else if (hasDng) {
            badgeView.visibility = View.VISIBLE
            badgeView.text = context.getString(R.string.format_raw)
        } else if (hasJpg) {
            badgeView.visibility = View.VISIBLE
            badgeView.text = context.getString(R.string.format_jpg)
        } else {
            badgeView.visibility = View.GONE
        }
    }

    private fun setupLensSlotListeners(
        slotView: View,
        group: ImageGroup,
        lens: MultiCameraLensItem,
        holder: MultiCamViewHolder
    ) {
        slotView.setOnClickListener {
            val adapterPos = holder.bindingAdapterPosition
            if (adapterPos != RecyclerView.NO_POSITION) {
                if (isSelectionMode) {
                    toggleLensSelection(group, lens)
                } else {
                    onItemClick?.invoke(group, adapterPos, lens.lensTag)
                }
            }
        }

        slotView.setOnLongClickListener {
            val adapterPos = holder.bindingAdapterPosition
            if (adapterPos != RecyclerView.NO_POSITION) {
                if (!isSelectionMode) {
                    setSelectionMode(true)
                    toggleLensSelection(group, lens)
                } else {
                    toggleLensSelection(group, lens)
                }
                onItemLongClick?.invoke(group, adapterPos, lens.lensTag)
            }
            true
        }
    }

    private fun bindMultiCamSelectionState(holder: MultiCamViewHolder, group: ImageGroup) {
        val primaryColor = com.google.android.material.color.MaterialColors.getColor(holder.itemView, android.R.attr.colorPrimary)
        val lenses = group.multiCameraLenses

        // Lens 1 selection
        val lens1 = lenses.getOrNull(0)
        if (lens1 != null) {
            val sel1 = selectedItemKeys.contains("${group.baseName}#${lens1.lensTag}")
            if (isSelectionMode) {
                holder.binding.selectionOverlay1.visibility = if (sel1) View.VISIBLE else View.GONE
                holder.binding.iconSelected1.visibility = if (sel1) View.VISIBLE else View.GONE
                holder.binding.iconUnselected1.visibility = if (sel1) View.GONE else View.VISIBLE
                holder.binding.slotLens1.strokeWidth = if (sel1) (2 * holder.itemView.context.resources.displayMetrics.density).toInt() else 0
                holder.binding.slotLens1.strokeColor = if (sel1) primaryColor else Color.TRANSPARENT
            } else {
                holder.binding.selectionOverlay1.visibility = View.GONE
                holder.binding.iconSelected1.visibility = View.GONE
                holder.binding.iconUnselected1.visibility = View.GONE
                holder.binding.slotLens1.strokeWidth = 0
                holder.binding.slotLens1.strokeColor = Color.TRANSPARENT
            }
        }

        // Lens 2 selection
        val lens2 = lenses.getOrNull(1)
        if (lens2 != null) {
            val sel2 = selectedItemKeys.contains("${group.baseName}#${lens2.lensTag}")
            if (isSelectionMode) {
                holder.binding.selectionOverlay2.visibility = if (sel2) View.VISIBLE else View.GONE
                holder.binding.iconSelected2.visibility = if (sel2) View.VISIBLE else View.GONE
                holder.binding.iconUnselected2.visibility = if (sel2) View.GONE else View.VISIBLE
                holder.binding.slotLens2.strokeWidth = if (sel2) (2 * holder.itemView.context.resources.displayMetrics.density).toInt() else 0
                holder.binding.slotLens2.strokeColor = if (sel2) primaryColor else Color.TRANSPARENT
            } else {
                holder.binding.selectionOverlay2.visibility = View.GONE
                holder.binding.iconSelected2.visibility = View.GONE
                holder.binding.iconUnselected2.visibility = View.GONE
                holder.binding.slotLens2.strokeWidth = 0
                holder.binding.slotLens2.strokeColor = Color.TRANSPARENT
            }
        }

        // Lens 3 selection
        val lens3 = lenses.getOrNull(2)
        if (lens3 != null) {
            val sel3 = selectedItemKeys.contains("${group.baseName}#${lens3.lensTag}")
            if (isSelectionMode) {
                holder.binding.selectionOverlay3.visibility = if (sel3) View.VISIBLE else View.GONE
                holder.binding.iconSelected3.visibility = if (sel3) View.VISIBLE else View.GONE
                holder.binding.iconUnselected3.visibility = if (sel3) View.GONE else View.VISIBLE
                holder.binding.slotLens3.strokeWidth = if (sel3) (2 * holder.itemView.context.resources.displayMetrics.density).toInt() else 0
                holder.binding.slotLens3.strokeColor = if (sel3) primaryColor else Color.TRANSPARENT
            } else {
                holder.binding.selectionOverlay3.visibility = View.GONE
                holder.binding.iconSelected3.visibility = View.GONE
                holder.binding.iconUnselected3.visibility = View.GONE
                holder.binding.slotLens3.strokeWidth = 0
                holder.binding.slotLens3.strokeColor = Color.TRANSPARENT
            }
        }
    }

    class SingleViewHolder(val binding: ItemDarkbagGalleryGridBinding) : RecyclerView.ViewHolder(binding.root)
    class MultiCamViewHolder(val binding: ItemDarkbagGalleryMultiCamGroupBinding) : RecyclerView.ViewHolder(binding.root)
}
