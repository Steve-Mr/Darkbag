package top.maary.darkbag.adapters

import android.net.Uri
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
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
    var onScrubStateChanged: ((isScrubbing: Boolean) -> Unit)? = null

    private var recyclerView: RecyclerView? = null

    private var isScrubbing = false
        set(value) {
            if (field != value) {
                field = value
                onScrubStateChanged?.invoke(value)
            }
        }

    private val scrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
            super.onScrollStateChanged(recyclerView, newState)
            if (newState == RecyclerView.SCROLL_STATE_DRAGGING || newState == RecyclerView.SCROLL_STATE_SETTLING) {
                isScrubbing = true
            } else if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                isScrubbing = false
            }
        }

        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            super.onScrolled(recyclerView, dx, dy)
            if (recyclerView.scrollState == RecyclerView.SCROLL_STATE_DRAGGING) {
                val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return
                val centerX = recyclerView.width / 2f
                var closestChild: View? = null
                var closestDistance = Float.MAX_VALUE
                for (i in 0 until layoutManager.childCount) {
                    val child = layoutManager.getChildAt(i) ?: continue
                    val childCenter = (child.left + child.right) / 2f
                    val distance = Math.abs(childCenter - centerX)
                    if (distance < closestDistance) {
                        closestDistance = distance
                        closestChild = child
                    }
                }
                if (closestChild != null) {
                    val pos = layoutManager.getPosition(closestChild)
                    if (pos != RecyclerView.NO_POSITION && pos != selectedFrameIndex && pos in frameUris.indices) {
                        val prev = selectedFrameIndex
                        selectedFrameIndex = pos
                        if (prev in 0 until itemCount) {
                            notifyItemChanged(prev, "SELECTION_CHANGED")
                        }
                        notifyItemChanged(selectedFrameIndex, "SELECTION_CHANGED")
                        val hapticConstant = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            HapticFeedbackConstants.CLOCK_TICK
                        } else {
                            HapticFeedbackConstants.KEYBOARD_TAP
                        }
                        recyclerView.performHapticFeedback(hapticConstant)
                        onFrameSelected?.invoke(pos, frameUris.getOrNull(pos))
                    }
                }
            }
        }
    }

    private val itemTouchListener = object : RecyclerView.SimpleOnItemTouchListener() {
        override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    isScrubbing = true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (rv.scrollState == RecyclerView.SCROLL_STATE_IDLE) {
                        isScrubbing = false
                    }
                }
            }
            return false
        }
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        this.recyclerView = recyclerView
        recyclerView.addOnScrollListener(scrollListener)
        recyclerView.addOnItemTouchListener(itemTouchListener)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        recyclerView.removeOnScrollListener(scrollListener)
        recyclerView.removeOnItemTouchListener(itemTouchListener)
        super.onDetachedFromRecyclerView(recyclerView)
        this.recyclerView = null
    }

    fun setFrames(uris: List<Uri>, selectedIndex: Int = 0) {
        this.frameUris = uris
        this.selectedFrameIndex = selectedIndex.coerceIn(0, (uris.size - 1).coerceAtLeast(0))
        notifyDataSetChanged()
        if (frameUris.isNotEmpty()) {
            recyclerView?.post {
                centerPosition(selectedFrameIndex, smoothScroll = false)
            }
        }
    }

    fun setSelectedPosition(index: Int, smoothScroll: Boolean = true, triggerFeedback: Boolean = true) {
        if (itemCount == 0) return
        val clampedIndex = index.coerceIn(0, itemCount - 1)
        if (selectedFrameIndex == clampedIndex) return

        val previousIndex = selectedFrameIndex
        selectedFrameIndex = clampedIndex

        if (previousIndex in 0 until itemCount) {
            notifyItemChanged(previousIndex, "SELECTION_CHANGED")
        }
        notifyItemChanged(selectedFrameIndex, "SELECTION_CHANGED")

        if (triggerFeedback) {
            recyclerView?.let { rv ->
                val hapticConstant = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    HapticFeedbackConstants.CLOCK_TICK
                } else {
                    HapticFeedbackConstants.KEYBOARD_TAP
                }
                rv.performHapticFeedback(hapticConstant)
            }
        }

        centerPosition(selectedFrameIndex, smoothScroll)
    }

    fun centerPosition(index: Int, smoothScroll: Boolean = true) {
        val rv = recyclerView ?: return
        val layoutManager = rv.layoutManager as? LinearLayoutManager ?: return

        if (smoothScroll) {
            val scroller = object : LinearSmoothScroller(rv.context) {
                override fun calculateDtToFit(
                    viewStart: Int,
                    viewEnd: Int,
                    boxStart: Int,
                    boxEnd: Int,
                    snapPreference: Int
                ): Int {
                    return (boxStart + (boxEnd - boxStart) / 2) - (viewStart + (viewEnd - viewStart) / 2)
                }
            }
            scroller.targetPosition = index
            layoutManager.startSmoothScroll(scroller)
        } else {
            val halfWidth = rv.width / 2
            val child = layoutManager.findViewByPosition(index)
            val itemWidth = child?.width ?: (42 * rv.context.resources.displayMetrics.density).toInt()
            val offset = halfWidth - (itemWidth / 2)
            layoutManager.scrollToPositionWithOffset(index, offset)
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
                    setSelectedPosition(pos, smoothScroll = true, triggerFeedback = true)
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
