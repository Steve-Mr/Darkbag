package top.maary.darkbag.fragments

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import android.view.View
import coil3.asImage
import coil3.load
import coil3.request.ImageRequest
import coil3.request.crossfade
import top.maary.darkbag.databinding.ItemImagePagerBinding
import top.maary.darkbag.image.DarkbagImageRequest
import top.maary.darkbag.utils.DarkbagImage
import top.maary.darkbag.utils.DarkbagMetadata

class ImagePagerAdapter(private var images: List<DarkbagImage>) : RecyclerView.Adapter<ImagePagerAdapter.ViewHolder>() {

    private var currentMetadata: DarkbagMetadata = DarkbagMetadata()
    private var currentFormat: String = "JPG"
    private var currentActivePosition: Int = 0

    class ViewHolder(val binding: ItemImagePagerBinding) : RecyclerView.ViewHolder(binding.root) {
        var currentBaseName: String? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemImagePagerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val image = images[position]

        val meta = if (position == currentActivePosition) currentMetadata else (image.metadata ?: DarkbagMetadata())
        val isModified = !meta.isNeutral()

        val request = DarkbagImageRequest(
            uri = image.allUris[currentFormat] ?: image.allUris["JPG"] ?: image.primaryUri,
            dngUri = image.allUris["DNG"],
            metadata = meta,
            isRawMode = currentFormat == "DNG",
            isModified = isModified,
            isThumbnail = position != currentActivePosition // Only thumbnails for off-screen items
        )

        val sameImage = holder.currentBaseName == image.baseName
        holder.currentBaseName = image.baseName
        val loadToken = "${image.baseName}_${position}_${System.nanoTime()}"

        // RecyclerView item may be reused from a previously loading RAW view.
        // Always reset indicator state before starting a new request.
        holder.binding.loadingIndicator.visibility = View.GONE
        holder.binding.loadingIndicator.tag = null

        val shouldShowLoading = position == currentActivePosition && (isModified || currentFormat == "DNG")

        holder.binding.ivDisplay.load(request) {
            // Use current image as placeholder ONLY IF it's the same physical photo,
            // to avoid flickering when switching formats/metadata.
            if (sameImage) {
                placeholder(holder.binding.ivDisplay.drawable?.asImage())
            }
            crossfade(true)

            listener(
                onStart = {
                    // Only show loading for the current page and RAW-backed renders.
                    // Delay avoids 1-frame flashes for fast completions.
                    if (shouldShowLoading) {
                        holder.binding.loadingIndicator.tag = loadToken
                        holder.binding.loadingIndicator.postDelayed({
                            // Keep indicator tied to the newest request bound to this ViewHolder.
                            if (holder.binding.loadingIndicator.tag == loadToken) {
                                holder.binding.loadingIndicator.visibility = View.VISIBLE
                            }
                        }, 100)
                    }
                },
                onSuccess = { _, _ ->
                    holder.binding.loadingIndicator.visibility = View.GONE
                    holder.binding.loadingIndicator.tag = null
                },
                onError = { _, _ ->
                    holder.binding.loadingIndicator.visibility = View.GONE
                    holder.binding.loadingIndicator.tag = null
                }
            )
        }
    }

    override fun getItemCount(): Int = images.size

    fun updateImages(newImages: List<DarkbagImage>) {
        images = newImages
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            notifyDataSetChanged()
        }
    }

    fun updateParams(metadata: DarkbagMetadata, format: String, position: Int) {
        if (this.currentMetadata == metadata && this.currentFormat == format && this.currentActivePosition == position) return

        this.currentMetadata = metadata
        this.currentFormat = format
        this.currentActivePosition = position

        android.os.Handler(android.os.Looper.getMainLooper()).post {
            notifyItemChanged(position)
        }
    }

    fun getImage(position: Int): DarkbagImage? {
        if (position in images.indices) return images[position]
        return null
    }
}
