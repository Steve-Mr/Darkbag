package top.maary.darkbag.adapters

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import top.maary.darkbag.databinding.ItemFilmstripFrameBinding

class FilmstripFrameAdapter(
    private var frameUris: List<Uri> = emptyList(),
    initialSelectedIndex: Int = 0
) : RecyclerView.Adapter<FilmstripFrameAdapter.FrameViewHolder>() {

    var selectedFrameIndex: Int = initialSelectedIndex
        private set

    var onFrameSelected: ((index: Int, uri: Uri?) -> Unit)? = null

    private var recyclerView: RecyclerView? = null

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        this.recyclerView = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        this.recyclerView = null
    }

    fun setFrames(uris: List<Uri>, selectedIndex: Int = 0) {
        this.frameUris = uris
        this.selectedFrameIndex = selectedIndex.coerceIn(0, (uris.size - 1).coerceAtLeast(0))
        notifyDataSetChanged()
    }

    fun setSelectedPosition(index: Int, smoothScroll: Boolean = true) {
        if (itemCount == 0) return
        val clampedIndex = index.coerceIn(0, itemCount - 1)
        if (selectedFrameIndex == clampedIndex) return

        val previousIndex = selectedFrameIndex
        selectedFrameIndex = clampedIndex

        if (previousIndex in 0 until itemCount) {
            notifyItemChanged(previousIndex, "SELECTION_CHANGED")
        }
        notifyItemChanged(selectedFrameIndex, "SELECTION_CHANGED")

        if (smoothScroll) {
            recyclerView?.smoothScrollToPosition(selectedFrameIndex)
        } else {
            recyclerView?.scrollToPosition(selectedFrameIndex)
        }
    }

    override fun getItemCount(): Int = frameUris.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FrameViewHolder {
        val binding = ItemFilmstripFrameBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return FrameViewHolder(binding)
    }

    override fun onBindViewHolder(holder: FrameViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains("SELECTION_CHANGED")) {
            holder.bindSelection(position == selectedFrameIndex)
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun onBindViewHolder(holder: FrameViewHolder, position: Int) {
        val uri = frameUris.getOrNull(position)
        val isSelected = position == selectedFrameIndex
        holder.bind(uri, position, isSelected)
    }

    inner class FrameViewHolder(private val binding: ItemFilmstripFrameBinding) :
        RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION && pos in frameUris.indices) {
                    val uri = frameUris[pos]
                    setSelectedPosition(pos, smoothScroll = true)
                    onFrameSelected?.invoke(pos, uri)
                }
            }
        }

        fun bind(uri: Uri?, position: Int, isSelected: Boolean) {
            bindSelection(isSelected)

            binding.tvFrameIndex.text = (position + 1).toString()
            binding.tvFrameIndex.visibility = View.VISIBLE

            if (uri != null) {
                Glide.with(binding.ivFrameThumb)
                    .load(uri)
                    .apply(
                        RequestOptions()
                            .override(120, 80)
                            .centerCrop()
                            .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                    )
                    .into(binding.ivFrameThumb)
            } else {
                Glide.with(binding.ivFrameThumb).clear(binding.ivFrameThumb)
                binding.ivFrameThumb.setImageDrawable(null)
            }
        }

        fun bindSelection(isSelected: Boolean) {
            binding.viewSelectionBorder.visibility = if (isSelected) View.VISIBLE else View.GONE
        }
    }
}
